package blue.coordination.sdk;

import blue.coordination.internal.BundledContracts10Release;
import blue.coordination.internal.RootedCalculationFixture;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Arranges actual SDK inputs; no expected runtime state is generated here. */
final class RootedSdkFixture implements AutoCloseable {
    final Map<String, String> exact = new LinkedHashMap<>();
    final BlueCoordination blue = BlueCoordination.builder().contentDerivedDocumentIds()
            .release(BundledContracts10Release.manifest().blueLanguageSpecification(),
                    BundledContracts10Release.manifest().contractsSpecification())
            .exactNodeProvider(id -> Optional.ofNullable(exact.get(id))).build();
    final RootedCalculationFixture control = new RootedCalculationFixture(blue.advanced().rawEngine());
    final Map<String, TimelineHandle> timelines = new LinkedHashMap<>();
    final Map<String, String> previousEntries = new LinkedHashMap<>();

    DocumentHandle start(String resource, String timeline, Map<String, String> references) throws IOException {
        timelines.computeIfAbsent(timeline, t -> blue.timelines().register(t, "alice"));
        StringBuilder yaml = new StringBuilder(resource(resource));
        references.forEach((path, id) -> yaml.append("\n").append(path).append(":\n  blueId: ").append(id).append("\n"));
        return startYaml(yaml.toString(), timeline);
    }

    DocumentHandle startYaml(String yaml, String timeline) {
        timelines.computeIfAbsent(timeline, t -> blue.timelines().register(t, "alice"));
        DocumentHandle document = blue.documents().admitStaticProcessEmbedded(yaml,
                ActivationPolicy.importFullHistory()).document("root");
        retain(document);
        return document;
    }

    String retain(DocumentHandle document) {
        String id = document.snapshot().blueId();
        exact.put(id, document.snapshot().exact().json());
        return id;
    }

    EntryHandle append(DocumentHandle target, String timeline, String operation, long timestamp, String request) {
        return append(target, timeline, operation, timestamp, request, false);
    }

    EntryHandle append(DocumentHandle target, String timeline, String operation, long timestamp, String request, boolean exactVersion) {
        return appendReference(target.snapshot().blueId(), timeline, operation, timestamp, request, exactVersion);
    }

    EntryHandle appendReference(String targetReference, String timeline, String operation, long timestamp, String request, boolean exactVersion) {
        String yaml = """
                type: Coordination/Timeline Entry
                timeline:
                  type: MyOS/MyOS Timeline
                  timelineId: %s
                timestamp: %d
                actor:
                  type: MyOS/Principal Actor
                  accountId: alice
                message:
                  type: Coordination/Operation Request
                  document:
                    blueId: %s
                  requireExactDocumentVersion: %s
                  operation: %s
                  channel: owner
                  request:
                %s
                """.formatted(timeline, timestamp, targetReference, exactVersion, operation, request.indent(4));
        String previous = previousEntries.get(timeline);
        if (previous != null) yaml += "\nprevEntry:\n  blueId: " + previous + "\n";
        EntryHandle entry = blue.events().from(timelines.get(timeline)).exact(blue.values().yaml(yaml)).submit();
        previousEntries.put(timeline, entry.blueId());
        return entry;
    }

    List<String> history(DocumentHandle document) {
        return blue.advanced().auditManagedEpochs(document.id()).stream().map(ManagedEpochReceipt::receiptIdentity).toList();
    }

    ExactBlueValue selected(DocumentHandle owner, String path) {
        return blue.values().retained(owner.snapshot().valueAt(path).blueId()).orElseThrow();
    }

    @Override public void close() { blue.close(); }

    private static String resource(String name) throws IOException {
        try (var in = RootedSdkFixture.class.getResourceAsStream("/rooted/" + name)) {
            if (in == null) throw new IOException("Missing rooted input " + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
