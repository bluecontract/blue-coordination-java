package blue.coordination.external;

import blue.language.model.Node;
import blue.language.processor.EffectiveContractSnapshotConstants;
import blue.language.processor.closure.AffectedClosureSnapshot;
import blue.language.processor.closure.DocumentId;
import blue.language.processor.closure.RootChannelMetadata;
import java.util.*;

/** Owning complete Root channel inventories, never an arbitrary host Timeline subset. */
final class DirectedTimelineMembership {
    record Checked(Set<String> timelines, List<String> needs) { }
    private DirectedTimelineMembership() { }

    static Checked verify(AffectedClosureSnapshot directed, Collection<RootChannelMetadata> metadata,
                          String registry, Set<String> asserted) {
        return verify(directed, metadata, registry, asserted,
                directed.managedDocuments().stream().map(s -> s.documentId()).collect(java.util.stream.Collectors.toSet()));
    }

    static Checked verify(AffectedClosureSnapshot directed, Collection<RootChannelMetadata> metadata,
                          String registry, Set<String> asserted, Set<DocumentId> selectedRoots) {
        if (selectedRoots.stream().anyMatch(id -> directed.managedDocument(id) == null))
            throw new IllegalArgumentException("Selected Timeline Root is outside the complete read cut");
        Map<DocumentId, RootChannelMetadata> byOwner = new HashMap<>();
        for (RootChannelMetadata root : metadata) {
            var state = directed.managedDocument(root.documentId());
            if (state == null || byOwner.put(root.documentId(), root) != null)
                throw new IllegalArgumentException("Duplicate or foreign Root Timeline metadata");
            root.verifyState(state); root.verifyRegistry(registry);
        }
        Set<String> timelines = new TreeSet<>(); List<String> needs = new ArrayList<>();
        for (var state : directed.managedDocuments()) {
            if (!selectedRoots.contains(state.documentId())) continue;
            RootChannelMetadata root = byOwner.get(state.documentId());
            if (root == null) { needs.add("root-channel-metadata:" + state.documentId().value() + ":" + state.blueId()); continue; }
            if (!state.initialized() || state.terminated()) continue;
            for (var contract : root.surface().effectiveRootContracts()) {
                if (!EffectiveContractSnapshotConstants.Role.EXTERNAL_CHANNEL.equals(contract.role())) continue;
                var binding = contract.headerFields().get("timeline");
                if (binding == null) {
                    // Both selectors are complete unions of this same Root's Timeline Channel members.
                    if (blue.repo.coordination.CompositeTimelineChannel.blueId().equals(contract.effectiveTypeBlueId())
                            || blue.repo.coordination.AllTimelinesChannel.blueId().equals(contract.effectiveTypeBlueId())) continue;
                    needs.add("finite-timeline-membership:" + state.documentId().value() + ":" + contract.key()); continue;
                }
                Node timeline = binding.toNode();
                Node locator = timeline.getProperties() == null ? null : timeline.getProperties().get("timelineId");
                if (timeline.getType() == null || !blue.repo.myos.MyOSTimeline.blueId().equals(timeline.getType().getBlueId())
                        || locator == null || !(locator.getValue() instanceof String) || ((String) locator.getValue()).isEmpty()) {
                    needs.add("typed-timeline-binding:" + state.documentId().value() + ":" + contract.key()); continue;
                }
                timelines.add((String) locator.getValue());
            }
        }
        if (needs.isEmpty() && !timelines.equals(asserted)) {
            for (String timeline : timelines) if (!asserted.contains(timeline)) needs.add("timeline-membership-missing:" + timeline);
            for (String timeline : new TreeSet<>(asserted)) if (!timelines.contains(timeline)) needs.add("timeline-membership-extraneous:" + timeline);
        }
        return new Checked(Set.copyOf(timelines), List.copyOf(needs));
    }
}
