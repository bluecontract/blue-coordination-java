package blue.coordination.external;

import blue.language.processor.InvalidExecutionEvidenceException;
import blue.language.processor.closure.*;

import java.util.*;

/** Immutable input authority for original fresh seeds; it never acquires evidence or runs a source. */
final class SourceInputAdmissionPlan {
    private final CoordinationCore.TimelineInput input;
    private final Map<DocumentId, String> predecessors, roots, expectedBases;
    private final Map<DocumentId, SourceInputAdmission> admitted;
    private final Set<DocumentId> freshOwners;
    private final SameOriginAttachmentPolicy choices;
    private final ClosureEnvironment environment;
    private final ExecutionPolicy policy;

    SourceInputAdmissionPlan(Set<DocumentId> currentOwners, AffectedClosureSnapshot snapshot,
            CoordinationCore.EvaluationEvidence evidence, CoordinationCore.TimelineInput input,
            ClosureEnvironment environment, ExecutionPolicy policy, SameOriginAttachmentPolicy originalChoices,
            SourceInputAdmission currentAdmission) {
        this.input = input; this.environment = environment; this.policy = policy;
        predecessors = evidence.precedingOperations();
        Map<DocumentId, String> selectedRoots = new TreeMap<>(evidence.originalSourceInputRoots());
        Map<DocumentId, String> bases = new TreeMap<>(evidence.expectedSourceBases());
        Map<DocumentId, SourceInputAdmission> verified = new TreeMap<>();
        Map<DocumentId, Set<DocumentId>> components = new HashMap<>();
        for (var component : snapshot.components()) {
            Set<DocumentId> members = Set.copyOf(component.orderedMemberDocumentIds());
            members.forEach(member -> components.put(member, members));
        }
        Map<DocumentId, List<DocumentId>> outgoing = new HashMap<>();
        for (var binding : snapshot.occurrences()) if (binding.active())
            outgoing.computeIfAbsent(binding.sourceDocumentId(), ignored -> new ArrayList<>()).add(binding.targetDocumentId());
        Map<Set<DocumentId>, Set<DocumentId>> reachable = new IdentityHashMap<>();
        Map<Set<DocumentId>, String> verifiedComponents = new IdentityHashMap<>();
        Map<String, SourceInputAdmission> offered = new HashMap<>();
        evidence.sourceInputAdmissions().forEach(record -> offered.put(record.identity(), record));
        if (currentAdmission != null) {
            for (DocumentId member : currentOwners) {
                String prior = selectedRoots.putIfAbsent(member, currentAdmission.identity());
                if (prior != null && !prior.equals(currentAdmission.identity()))
                    throw invalid("Original input roots disagree for one producer");
            }
            accept(currentAdmission, currentOwners, verified, bases);
            verifiedComponents.put(components.get(currentOwners.iterator().next()), currentAdmission.identity());
        }
        for (var selected : selectedRoots.entrySet()) {
            DocumentId source = selected.getKey();
            // Unrelated offered inventory cannot expand this invocation's directed scope.
            if (snapshot.managedDocument(source) == null) continue;
            SourceInputAdmission record = offered.get(selected.getValue());
            if (record == null) continue; // Required only if this producer is actually admitted.
            Set<DocumentId> owned = components.get(source);
            String checked = verifiedComponents.get(owned);
            if (checked != null) {
                if (!checked.equals(record.identity())) throw invalid("Original co-owned input roots disagree");
                continue;
            }
            String basis = bases.getOrDefault(source, record.sourceBases().get(source));
            if (basis == null) throw invalid("Original input root does not own its named producer");
            // The usual original record is confined to this SCC: no graph walk is needed.
            Set<DocumentId> live = owned.containsAll(record.sourceBases().keySet()) ? owned
                    : reachable.computeIfAbsent(owned, ignored -> directedMembers(outgoing, source));
            record.verify(source, basis, input, predecessors, live, owned);
            // A root selected for X authenticates X's original SCC, not every independent
            // descendant merely named in X's offered record. Those need their own root selections.
            accept(record, owned, verified, bases);
            verifiedComponents.put(owned, record.identity());
        }
        Set<DocumentId> allowed = new TreeSet<>(currentOwners);
        for (DocumentId owner : currentOwners) {
            String declared = bases.get(owner);
            if (declared != null) SourceExecutionBasis.requireProducerBasis(declared, owner, environment, policy);
        }
        for (Set<DocumentId> component : verifiedComponents.keySet()) {
            boolean compatible = component.stream().allMatch(member -> verified.containsKey(member)
                    && SourceExecutionBasis.identity(member, environment, policy).equals(bases.get(member)));
            if (compatible) allowed.addAll(component);
        }
        List<SameOriginAttachmentPolicy.Selection> merged = new ArrayList<>();
        for (DocumentId owner : currentOwners) {
            SourceInputAdmission record = verified.get(owner);
            merged.addAll((record == null ? originalChoices : record.selections()).ownedBy(Set.of(owner)).entries());
        }
        for (DocumentId source : allowed) {
            if (currentOwners.contains(source)) continue;
            SourceInputAdmission record = verified.get(source);
            if (record != null) merged.addAll(record.selections().ownedBy(Set.of(source)).entries());
        }
        choices = new SameOriginAttachmentPolicy(merged);
        // Explicit new-input choices for another producer must agree with its independently admitted input.
        for (var choice : originalChoices.entries()) if (allowed.contains(choice.creatorLineage())) {
            var actual = choices.selection(choice.occurrenceIdentity());
            if (actual.isEmpty() || !sameSelection(choice, actual.orElseThrow()))
                throw invalid("Independent producer choices differ from its original input admission");
        }
        roots = Map.copyOf(selectedRoots); expectedBases = Map.copyOf(bases);
        admitted = Map.copyOf(verified); freshOwners = Set.copyOf(allowed);
    }

    Set<DocumentId> freshOwners() { return freshOwners; }
    Map<DocumentId, String> expectedBases() { return expectedBases; }

    List<String> verifyRetained(Set<DocumentId> owners, String operation,
            Optional<Map<DocumentId, List<String>>> originalSelections) {
        List<String> needs = new ArrayList<>();
        for (DocumentId source : owners) {
            SourceInputAdmission record = admitted.get(source);
            if (record == null && !roots.containsKey(source)) continue;
            if (record == null) { needs.add("source-input-admission-record:" + roots.get(source)); continue; }
            if (originalSelections.isEmpty()) {
                needs.add("source-input-selection-binding:" + source.value() + ":" + input.entry().blueId()
                        + ":" + record.identity() + ":" + operation);
                continue;
            }
            List<String> expected = record.selections().ownedBy(Set.of(source)).entries().stream()
                    .map(SameOriginAttachmentPolicy.Selection::identity).toList();
            if (!expected.equals(originalSelections.orElseThrow().get(source)))
                throw invalid("Retained producer choices differ from its original input admission");
        }
        return needs.stream().distinct().sorted().toList();
    }

    SameOriginAttachmentPolicy choices(Set<DocumentId> retainedSources) {
        Set<DocumentId> fresh = new HashSet<>(freshOwners); fresh.removeAll(retainedSources);
        return choices.ownedBy(fresh);
    }

    CoordinationCore.NeedEvidence needs(Set<DocumentId> sources) {
        Set<String> keys = new TreeSet<>();
        for (DocumentId source : sources) {
            String basis = expectedBases.get(source);
            if (basis != null && !SourceExecutionBasis.identity(source, environment, policy).equals(basis)) {
                keys.add("canonical-source-operation:" + source.value() + ":" + input.entry().blueId() + ":" + basis
                        + ":" + predecessors.getOrDefault(source, "no-predecessor"));
            } else if (!admitted.containsKey(source)) {
                String root = roots.get(source);
                keys.add(root == null ? "source-input-admission:" + source.value() + ":" + input.entry().blueId()
                        : "source-input-admission-record:" + root);
            } else throw invalid("Original producer admission is incompatible with its complete component");
        }
        return new CoordinationCore.NeedEvidence(List.copyOf(keys));
    }

    private static void accept(SourceInputAdmission record, Set<DocumentId> owners, Map<DocumentId, SourceInputAdmission> accepted,
            Map<DocumentId, String> expected) {
        for (DocumentId owner : owners) {
            SourceInputAdmission previous = accepted.putIfAbsent(owner, record);
            if (previous != null && !previous.identity().equals(record.identity()))
                throw invalid("Conflicting original input admissions for one producer");
            String declared = record.sourceBases().get(owner);
            String prior = expected.putIfAbsent(owner, declared);
            if (prior != null && !prior.equals(declared)) throw invalid("Original producer policy differs from its expected basis");
        }
    }

    private static Set<DocumentId> directedMembers(Map<DocumentId, List<DocumentId>> outgoing, DocumentId source) {
        Set<DocumentId> result = new HashSet<>(); ArrayDeque<DocumentId> pending = new ArrayDeque<>(); pending.add(source);
        while (!pending.isEmpty()) {
            DocumentId member = pending.removeFirst();
            if (result.add(member)) pending.addAll(outgoing.getOrDefault(member, List.of()));
        }
        return result;
    }

    private static boolean sameSelection(SameOriginAttachmentPolicy.Selection left, SameOriginAttachmentPolicy.Selection right) {
        return left.mode() == right.mode() && left.creatorLineage().equals(right.creatorLineage())
                && left.occurrenceIdentity().equals(right.occurrenceIdentity()) && left.targetLineage().equals(right.targetLineage())
                && left.suppliedExactRefBlueId().equals(right.suppliedExactRefBlueId()) && left.frontier().equals(right.frontier());
    }

    private static InvalidExecutionEvidenceException invalid(String message) { return new InvalidExecutionEvidenceException(message); }
}
