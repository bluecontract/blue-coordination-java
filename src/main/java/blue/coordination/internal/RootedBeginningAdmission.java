package blue.coordination.internal;

import blue.coordination.api.CoordinationErrorCode;
import blue.coordination.api.CoordinationException;
import blue.coordination.api.Timeline;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import blue.language.processor.EffectiveContractSnapshot;
import blue.language.processor.EffectiveContractSnapshotConstants;
import blue.language.processor.ExternalOrderKey;
import blue.language.processor.ManagedRootSubscriptionSurface;
import blue.language.processor.closure.ClosureInvocationInput;
import blue.language.processor.closure.ClosureProcessResult;
import blue.repo.coordination.AllTimelinesChannel;
import blue.repo.coordination.CompositeTimelineChannel;
import blue.repo.coordination.TimelineChannel;
import blue.repo.myos.MyOSTimeline;
import blue.repo.myos.PrincipalActor;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.Function;

/** A host-authenticated admission fact, never an authored BEGINNING assertion. */
final class RootedBeginningAdmission {
    static final ExternalOrderKey BOUND = ExternalOrderKey.of(List.of(BigInteger.ZERO));

    @FunctionalInterface
    interface Verifier {
        RootedBeginningAdmission verify(ClosureInvocationInput input, ClosureProcessResult result,
                Function<Node, ManagedRootSubscriptionSurface> project);
    }

    private final String invocation;
    private final String inputClosure;
    private final String outputClosure;
    private final String companion;
    private final Node evidence;

    private RootedBeginningAdmission(ClosureInvocationInput input, ClosureProcessResult result,
            List<Node> sourceSurface) {
        invocation = input.invocationIdentity();
        inputClosure = input.snapshot().closureIdentity();
        outputClosure = result.outputClosureIdentity();
        companion = result.platformCommitCompanion().companionIdentity();
        evidence = new Node().properties(Map.of(
                "kind", text("BEGINNING"),
                "admissionInvocationIdentity", text(invocation),
                "inputClosureIdentity", text(inputClosure),
                "outputClosureIdentity", text(outputClosure),
                "admissionCompanionIdentity", text(companion),
                "sourceSurface", new Node().items(sourceSurface)));
    }

    /** Only the owning in-memory provider can issue this fact for its complete journal. */
    static RootedBeginningAdmission verify(ClosureInvocationInput input, ClosureProcessResult result,
            InMemoryTimelineJournal journal, Map<String, Timeline> registered,
            Function<String, String> registeredActorType,
            Function<Node, ManagedRootSubscriptionSurface> project) {
        requireSuccessfulAdmission(input, result);
        Objects.requireNonNull(journal, "owning journal");
        Objects.requireNonNull(registered, "registered timelines");
        Objects.requireNonNull(registeredActorType, "registered actor types");
        // All new and selected forward members are included, even without a Handler.
        TreeMap<String, Node> surface = new TreeMap<>(EmbeddingBinding.TEXT_ORDER);
        boolean requiresProvider = false;
        for (var member : result.resultingDocuments()) {
            var projected = project.apply(member.document());
            var catalog = projected.effectiveRootContracts();
            LinkedHashMap<String, EffectiveContractSnapshot> direct = new LinkedHashMap<>();
            List<EffectiveContractSnapshot> aggregates = new ArrayList<>();
            for (var contract : catalog) {
                if (!EffectiveContractSnapshotConstants.Role.EXTERNAL_CHANNEL.equals(contract.role())) continue;
                if (!"/".equals(contract.scopePath())) {
                    throw invalid("INVALID_SOURCE_SURFACE", "Beginning proof requires the complete same-Root catalog");
                }
                String type = contract.effectiveTypeBlueId();
                if (CompositeTimelineChannel.blueId().equals(type) || AllTimelinesChannel.blueId().equals(type)) {
                    aggregates.add(contract);
                    continue;
                }
                if (!TimelineChannel.blueId().equals(type)) {
                    throw invalid("UNSUPPORTED_COORDINATION_TYPE", "No beginning verifier for external Channel " + type);
                }
                if (direct.putIfAbsent(contract.key(), contract) != null) {
                    throw invalid("INVALID_SOURCE_SURFACE", "Duplicate effective Timeline member");
                }
                var timeline = contract.headerFields().get("timeline");
                if (timeline == null || timeline.at("/timelineId") == null
                        || !(timeline.at("/timelineId").getValue() instanceof String timelineId)
                        || timelineId.isBlank()) {
                    throw invalid("INVALID_SOURCE_SURFACE", "Timeline source is not exact and complete");
                }
                Timeline owner = registered.get(timelineId);
                if (owner == null) throw invalid("UNKNOWN_PROVIDER", "Timeline has no owning registered provider: " + timelineId);
                // Compare effective headers in the same declaration context: TimelineChannel
                // contributes required/schema metadata that is absent from a standalone value.
                var expectedHeaders = registeredHeaders(project, timelineId, owner.actorId());
                String actualId = DirectBlueIdCalculator.calculateBlueId(timeline.toNode());
                if (!timeline.sameResolvedStructure(expectedHeaders.get("timeline"))) {
                    throw invalid("UNSUPPORTED_COORDINATION_TYPE", "Exact Timeline is not the registered MyOS provider value");
                }
                Node row = sourceRow(member.documentId().value(), contract)
                        .properties("timelineBlueId", text(actualId));
                var actor = contract.headerFields().get("actor");
                if (actor != null) {
                    if (!PrincipalActor.qualifiedName().equals(registeredActorType.apply(timelineId))) {
                        throw invalid("UNSUPPORTED_ACTOR_PROVIDER", "No beginning verifier for registered actor type");
                    }
                    String actualActorId = DirectBlueIdCalculator.calculateBlueId(actor.toNode());
                    if (!actor.sameResolvedStructure(expectedHeaders.get("actor"))) {
                        throw invalid("REGISTERED_ACTOR_MISMATCH", "Exact Principal Actor does not match the registered provider actor");
                    }
                    row.properties("actorBlueId", text(actualActorId));
                }
                requiresProvider = true;
                surface.put(member.documentId().value() + "\u0000" + contract.key(), row);
            }
            // The same actual catalog supplies every union member. Every direct source above
            // has already passed provider and actor verification, including unused channels.
            // Unsupported members fail closed above; they cannot disappear from an All set.
            for (var aggregate : aggregates) {
                Set<String> selected = new LinkedHashSet<>();
                if (CompositeTimelineChannel.blueId().equals(aggregate.effectiveTypeBlueId())) {
                    var channels = aggregate.headerFields().get("channels");
                    if (channels == null || channels.getItems() == null || channels.getItems().isEmpty()) {
                        throw invalid("INVALID_SOURCE_SURFACE", "Composite requires its exact nonempty member list");
                    }
                    for (var item : channels.getItems()) {
                        if (!(item.getValue() instanceof String key) || key.isEmpty() || !direct.containsKey(key)) {
                            throw invalid("INVALID_SOURCE_SURFACE", "Composite member is absent or not a verified direct Timeline");
                        }
                        selected.add(key);
                    }
                } else selected.addAll(direct.keySet());
                // Catalog order is the processor's canonical member order. Duplicate authored
                // Composite keys share one member, exactly as TimelineMemberSubscriptions does.
                List<String> memberKeys = direct.keySet().stream().filter(selected::contains).toList();
                surface.put(member.documentId().value() + "\u0000" + aggregate.key(),
                        sourceRow(member.documentId().value(), aggregate).properties("memberChannelKeys", strings(memberKeys)));
            }
        }
        // A local cache miss is never authority. This journal is the installed provider's full store.
        // No-entry source surfaces have no provider-completeness obligation, but still fence the
        // actual selected beginning admission point against a concurrently changed journal.
        journal.requireBeginningAdmission(requiresProvider);
        return new RootedBeginningAdmission(input, result, new ArrayList<>(surface.values()));
    }

    void requireFor(ClosureInvocationInput input, ClosureProcessResult result) {
        requireSuccessfulAdmission(input, result);
        if (!invocation.equals(input.invocationIdentity()) || !inputClosure.equals(input.snapshot().closureIdentity())
                || !outputClosure.equals(result.outputClosureIdentity())
                || !companion.equals(result.platformCommitCompanion().companionIdentity())) {
            throw invalid("WRONG_ADMISSION_PROOF", "BEGINNING evidence belongs to another exact admission");
        }
    }

    Node evidence() { return evidence.clone(); }

    private static void requireSuccessfulAdmission(ClosureInvocationInput input, ClosureProcessResult result) {
        Objects.requireNonNull(input, "input"); Objects.requireNonNull(result, "result");
        if (input.operation() != ClosureInvocationInput.Operation.ADMIT_CLOSURE || !result.commits()
                || result.platformCommitCompanion() == null
                || !input.invocationIdentity().equals(result.invocationIdentity())
                || !input.snapshot().closureIdentity().equals(result.inputClosureIdentity())) {
            throw invalid("INVALID_ADMISSION_PROOF", "BEGINNING requires a complete successful exact admission");
        }
    }

    private static Map<String, blue.language.snapshot.FrozenNode> registeredHeaders(
            Function<Node, ManagedRootSubscriptionSurface> project, String timelineId, String actorId) {
        Node channel = new Node().type(new Node().blueId(TimelineChannel.blueId())).properties(
                "timeline", new Node().type(new Node().blueId(MyOSTimeline.blueId()))
                        .properties("timelineId", text(timelineId)),
                "actor", new Node().type(new Node().blueId(PrincipalActor.blueId()))
                        .properties("accountId", text(actorId)));
        var contracts = project.apply(new Node().contracts(new Node().properties("registered", channel)))
                .effectiveRootContracts();
        if (contracts.size() != 1 || !"registered".equals(contracts.get(0).key())
                || !TimelineChannel.blueId().equals(contracts.get(0).effectiveTypeBlueId())) {
            throw invalid("INVALID_SOURCE_SURFACE", "Registered provider reference did not project one exact Timeline Channel");
        }
        return contracts.get(0).headerFields();
    }

    private static Node sourceRow(String documentId, EffectiveContractSnapshot contract) {
        return new Node().properties(Map.of("documentId", text(documentId),
                "channelKey", text(contract.key()), "channelTypeBlueId", text(contract.effectiveTypeBlueId()),
                "sourceContributions", strings(contract.sourceContributionNodeBlueIds())));
    }

    private static Node text(String value) { return new Node().value(value); }
    private static Node strings(List<String> values) { return new Node().items(values.stream().map(RootedBeginningAdmission::text).toList()); }
    private static CoordinationException invalid(String reason, String message) {
        return new CoordinationException(CoordinationErrorCode.INVALID_ACTIVATION_EVIDENCE, message, null, Map.of("reason", reason));
    }
}
