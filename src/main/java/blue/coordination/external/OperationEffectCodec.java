package blue.coordination.external;

import blue.language.processor.closure.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import static blue.coordination.external.OperationReceiptCodec.*;

/** Typed sparse effect projections; does not serialize the legacy whole-closure result. */
final class OperationEffectCodec {
    private OperationEffectCodec() { }

    static String encode(CoordinationCore.PreparedOperation operation, FrozenNodeEvidenceCodec.Encoder encoder) {
        return encode(operation.ownedOccurrenceBindings(), operation.ownedGraphChanges(),
                operation.ownedSubscriptionDeltas(), operation.ownedCheckpointWrites(), encoder);
    }

    static String encode(SameOriginOperationResult operation, FrozenNodeEvidenceCodec.Encoder encoder) {
        return encode(operation.occurrenceBindings(), operation.graphChanges(),
                operation.subscriptionDeltas(), operation.checkpointWrites(), encoder);
    }

    private static String encode(List<ManagedOccurrenceBinding> ownedBindings, List<GraphChange> ownedGraph,
            List<SubscriptionDelta> ownedSubscriptions, List<CheckpointWrite> ownedCheckpoints,
            FrozenNodeEvidenceCodec.Encoder encoder) {
        List<String> bindings = new ArrayList<>(), graph = new ArrayList<>(), subscriptions = new ArrayList<>(), checkpoints = new ArrayList<>();
        for (var binding : ownedBindings) bindings.add(encoder.blob(bytes(map(
                "occurrence", binding.occurrenceIdentity(), "binding", binding.bindingIdentity(), "policy", binding.bindingPolicyIdentity(),
                "source", binding.sourceDocumentId().value(), "path", binding.sourcePath(), "activation", binding.activationGeneration(),
                "target", binding.targetDocumentId().value(), "blueId", binding.expectedTargetBlueId(), "active", binding.active(),
                "pendingEpoch", binding.pendingHistoricalEpoch()))));
        for (var change : ownedGraph) graph.add(encoder.blob(bytes(map(
                "ordinal", change.graphChangeOrdinal(), "kind", change.changeKind().name(), "source", change.sourceDocumentId().value(),
                "path", change.sourcePath(), "before", side(change.before()), "after", side(change.after())))));
        for (var delta : ownedSubscriptions) subscriptions.add(encoder.blob(bytes(map(
                "ordinal", delta.subscriptionDeltaOrdinal(), "operation", delta.operation().name(), "scope", delta.targetManagedScopeIdentity(),
                "channel", delta.channelOccurrenceIdentity(), "before", subscription(delta.beforeSubscription()),
                "after", subscription(delta.afterSubscription())))));
        for (var write : ownedCheckpoints) checkpoints.add(encoder.blob(bytes(map(
                "ordinal", write.checkpointWriteOrdinal(), "scope", write.targetManagedScopeIdentity(),
                "owner", write.targetManagedScopeKey().documentId().value(), "channel", write.rawChannelKey(),
                "before", checkpoint(write.beforeDomainValue(), write.beforeSubjectBlueId()),
                "after", checkpoint(write.afterDomainValue(), write.afterSubjectBlueId())))));
        return encoder.blob(bytes(map("format", "blue-owned-effects-poc-1", "bindings", bindings,
                "graph", graph, "subscriptions", subscriptions, "checkpoints", checkpoints)));
    }

    static OperationReceiptCodec.Effects decode(String identity, FrozenNodeEvidenceCodec.Decoder decoder, Set<DocumentId> owners) {
        JsonNode root = json(decoder.blob(identity));
        if (!"blue-owned-effects-poc-1".equals(text(root, "format"))) throw invalid("Unexpected owned effect format");
        List<ManagedOccurrenceBinding> bindings = new ArrayList<>();
        for (String ref : strings(root, "bindings")) {
            JsonNode row = json(decoder.blob(ref)); DocumentId owner = owner(row, "source", owners);
            bindings.add(new ManagedOccurrenceBinding(text(row, "occurrence"), text(row, "binding"), text(row, "policy"), owner,
                    ScopeAddress.embedded(text(row, "path"), integer(row, "activation")), new DocumentId(text(row, "target")),
                    text(row, "blueId"), bool(row, "active"), optionalInteger(row, "pendingEpoch")));
        }
        List<GraphChange> graph = new ArrayList<>();
        for (String ref : strings(root, "graph")) {
            JsonNode row = json(decoder.blob(ref));
            graph.add(new GraphChange(integer(row, "ordinal"), GraphChange.Kind.valueOf(text(row, "kind")),
                    owner(row, "source", owners), text(row, "path"), side(row.get("before")), side(row.get("after"))));
        }
        List<SubscriptionDelta> subscriptions = new ArrayList<>();
        for (String ref : strings(root, "subscriptions")) {
            JsonNode row = json(decoder.blob(ref));
            SubscriptionState before = subscription(row.get("before")), after = subscription(row.get("after"));
            if (before != null && !owners.contains(before.channelOccurrence().managedDocumentId())
                    || after != null && !owners.contains(after.channelOccurrence().managedDocumentId())) throw invalid("Unowned subscription effect");
            subscriptions.add(new SubscriptionDelta(integer(row, "ordinal"), SubscriptionDelta.Operation.valueOf(text(row, "operation")),
                    text(row, "scope"), text(row, "channel"), before, after));
        }
        List<CheckpointWrite> checkpoints = new ArrayList<>();
        for (String ref : strings(root, "checkpoints")) {
            JsonNode row = json(decoder.blob(ref));
            checkpoints.add(new CheckpointWrite(integer(row, "ordinal"), ManagedScopeKey.root(owner(row, "owner", owners)),
                    text(row, "scope"), text(row, "channel"), checkpoint(row.get("before")), checkpoint(row.get("after"))));
        }
        return new OperationReceiptCodec.Effects(bindings, graph, subscriptions, checkpoints);
    }

    private static DocumentId owner(JsonNode row, String key, Set<DocumentId> owners) {
        DocumentId owner = new DocumentId(text(row, key));
        if (!owners.contains(owner)) throw invalid("Receipt cannot publish a read-only lineage effect"); return owner;
    }
    private static Object side(GraphChange.Side side) {
        return side == null ? null : map("activation", side.activationGeneration(), "occurrence", side.occurrenceIdentity(),
                "binding", side.bindingIdentity(), "target", side.targetDocumentId().value(), "blueId", side.targetBlueId());
    }
    private static GraphChange.Side side(JsonNode row) {
        return row == null || row.isNull() ? null : new GraphChange.Side(integer(row, "activation"), text(row, "occurrence"),
                text(row, "binding"), new DocumentId(text(row, "target")), text(row, "blueId"));
    }
    private static Object subscription(SubscriptionState state) {
        if (state == null) return null;
        var channel = state.channelOccurrence();
        return map("identity", state.subscriptionIdentity(), "documentBlueId", state.documentBlueId(),
                "graphGeneration", state.graphGeneration(), "componentGeneration", state.componentGeneration(),
                "channel", map("identity", channel.channelOccurrenceIdentity(), "document", channel.managedDocumentId().value(),
                        "path", channel.scopePath(), "activation", channel.scopeActivationGeneration(), "key", channel.rawChannelKey(),
                        "contribution", channel.effectiveRuntimeContributionBlueId(), "header", channel.subscriptionHeaderBlueId()));
    }
    private static SubscriptionState subscription(JsonNode row) {
        if (row == null || row.isNull()) return null;
        JsonNode channel = row.get("channel");
        return new SubscriptionState(text(row, "identity"), new ChannelOccurrence(text(channel, "identity"),
                new DocumentId(text(channel, "document")), text(channel, "path"), integer(channel, "activation"),
                text(channel, "key"), text(channel, "contribution"), text(channel, "header")), text(row, "documentBlueId"),
                integer(row, "graphGeneration"), integer(row, "componentGeneration"));
    }
    private static Object checkpoint(CheckpointDomainValue domain, String subject) {
        if (domain == null) return null;
        return map("domainBlueId", domain.blueId(), "subject", subject, "type", domain.effectiveTypeBlueId(),
                "contributions", domain.sourceContributionNodeBlueIds(), "dependencies", domain.deterministicDependencyNodeBlueIds(),
                "runtime", domain.runtimeDiscriminator());
    }
    private static CheckpointWrite.State checkpoint(JsonNode row) {
        if (row == null || row.isNull()) return null;
        CheckpointDomainValue domain = new CheckpointDomainValue(text(row, "type"), strings(row, "contributions"),
                strings(row, "dependencies"), nullable(row, "runtime"));
        return new CheckpointWrite.State(text(row, "domainBlueId"), domain, text(row, "subject"));
    }
}
