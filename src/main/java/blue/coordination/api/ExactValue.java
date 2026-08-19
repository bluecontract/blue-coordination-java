package blue.coordination.api;

import blue.language.merge.ResolvedSnapshot;
import blue.language.model.Node;
import blue.language.model.wire.JsonPointer;
import blue.language.processor.closure.ClosureProcessResult;
import blue.language.processor.closure.ClosureInvocationInput;
import blue.language.processor.closure.ComponentKind;
import blue.language.processor.closure.ComponentSnapshot;
import blue.language.processor.closure.ManagedDocumentSnapshot;
import blue.language.processor.closure.ResultingDocument;
import blue.language.snapshot.FrozenNode;

import java.util.Objects;
import java.util.Optional;

/**
 * One immutable whole exact Blue object.
 *
 * <p>The value keeps Language's shareable {@link FrozenNode}; when it originated
 * from a complete resolver run it also keeps that {@link ResolvedSnapshot} so
 * canonical/resolved roots, path indexes, provenance, and memoized BlueIds are
 * not discarded at the Coordination boundary.</p>
 */
public final class ExactValue {
    private final String blueId;
    private final FrozenNode frozen;
    private final ResolvedSnapshot snapshot;
    private final boolean cyclicMember;

    private ExactValue(
            String blueId,
            FrozenNode frozen,
            ResolvedSnapshot snapshot) {
        this(blueId, frozen, snapshot, false);
    }

    private ExactValue(
            String blueId,
            FrozenNode frozen,
            ResolvedSnapshot snapshot,
            boolean cyclicMember) {
        this.blueId = requireText(blueId, "blueId");
        this.frozen = Objects.requireNonNull(frozen, "frozen");
        this.snapshot = snapshot;
        this.cyclicMember = cyclicMember;
        if (!cyclicMember && !this.blueId.equals(this.frozen.blueId())) {
            throw new IllegalArgumentException(
                    "Frozen value does not match supplied BlueId");
        }
        if (cyclicMember && !this.blueId.contains("#")) {
            throw new IllegalArgumentException(
                    "Cyclic member identity requires a numeric member suffix");
        }
        if (cyclicMember && snapshot != null) {
            throw new IllegalArgumentException(
                    "Cyclic member state cannot carry an acyclic resolver snapshot");
        }
        if (snapshot != null && !this.blueId.equals(snapshot.blueId())) {
            throw new IllegalArgumentException(
                    "Snapshot does not match supplied BlueId");
        }
    }

    /** Freezes a detached mutable Node and verifies its exact BlueId. */
    public static ExactValue verified(Node exact) {
        FrozenNode frozen = FrozenNode.fromNode(
                Objects.requireNonNull(exact, "exact"));
        return new ExactValue(frozen.blueId(), frozen, null);
    }

    /** Freezes a Node and checks it against an expected exact BlueId. */
    public static ExactValue verified(String expectedBlueId, Node exact) {
        String expected = requireText(expectedBlueId, "expectedBlueId");
        FrozenNode frozen = FrozenNode.fromNode(
                Objects.requireNonNull(exact, "exact"));
        String actual = frozen.blueId();
        if (!expected.equals(actual)) {
            throw new IllegalArgumentException(
                    "Exact value identity mismatch: expected " + expected
                            + ", actual " + actual);
        }
        return new ExactValue(expected, frozen, null);
    }

    /** Retains an existing immutable Language snapshot without re-freezing it. */
    public static ExactValue fromSnapshot(ResolvedSnapshot snapshot) {
        ResolvedSnapshot exact = Objects.requireNonNull(snapshot, "snapshot");
        return new ExactValue(
                exact.blueId(), exact.frozenCanonicalRoot(), exact);
    }

    /** Retains an already strict canonical frozen value without materializing. */
    public static ExactValue fromFrozen(FrozenNode frozen) {
        FrozenNode exact = Objects.requireNonNull(frozen, "frozen");
        return new ExactValue(exact.blueId(), exact, null);
    }

    /**
     * Retains one document from an already verified successful closure result.
     *
     * <p>This is the only Coordination boundary that may associate a local
     * cyclic member body with its {@code MASTER#n} identity. The supplied
     * Contracts result has already verified the complete component proof and
     * every resulting document together; callers cannot inject a claimed
     * cyclic identity independently of that evidence.</p>
     *
     * @param result verified successful Contracts closure result
     * @param documentId selected managed document lineage
     * @return exact durable value with its authoritative closure identity
     */
    public static ExactValue fromVerifiedClosureResult(
            ClosureProcessResult result,
            DocumentId documentId) {
        ClosureProcessResult verified = Objects.requireNonNull(result, "result");
        DocumentId selected = Objects.requireNonNull(documentId, "documentId");
        if (!verified.commits()) {
            throw new IllegalArgumentException(
                    "Only a successful closure result can publish document state");
        }
        ResultingDocument document = verified.resultingDocuments().stream()
                .filter(candidate -> candidate.documentId().value()
                        .equals(selected.value()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Closure result has no document " + selected));
        FrozenNode body = FrozenNode.fromNode(document.document());
        if (document.memberIndex() == null) {
            if (!document.afterBlueId().equals(body.blueId())) {
                throw new IllegalArgumentException(
                        "Acyclic closure document identity does not match its body");
            }
            return new ExactValue(document.afterBlueId(), body, null);
        }
        return new ExactValue(document.afterBlueId(), body, null, true);
    }

    /**
     * Retains an admission input body only after the matching successful
     * Contracts invocation has authenticated the complete closure.
     *
     * <p>This is deliberately stricter than {@link #verified(String, Node)}:
     * a standalone {@code MASTER#n} claim is never accepted. The exact input
     * closure identity, invocation identity, commit-companion head fence, and
     * complete cyclic component record must all agree with the successful
     * result before the local member body can be retained.</p>
     *
     * @param input exact {@code ADMIT_CLOSURE} input which was executed
     * @param result verified successful result produced from {@code input}
     * @param documentId selected managed document lineage
     * @return exact authenticated input value for the initial history record
     */
    public static ExactValue fromVerifiedClosureAdmissionInput(
            ClosureInvocationInput input,
            ClosureProcessResult result,
            DocumentId documentId) {
        ClosureInvocationInput admission = Objects.requireNonNull(
                input, "input");
        ClosureProcessResult verified = Objects.requireNonNull(
                result, "result");
        DocumentId selected = Objects.requireNonNull(documentId, "documentId");
        if (admission.operation()
                != ClosureInvocationInput.Operation.ADMIT_CLOSURE) {
            throw new IllegalArgumentException(
                    "Only an ADMIT_CLOSURE input can retain admission state");
        }
        if (!verified.commits() || verified.platformCommitCompanion() == null) {
            throw new IllegalArgumentException(
                    "Only a successful closure admission can retain input state");
        }
        if (!verified.invocationIdentity().equals(
                admission.invocationIdentity())
                || !verified.inputClosureIdentity().equals(
                admission.snapshot().closureIdentity())) {
            throw new IllegalArgumentException(
                    "Closure result does not authenticate the admission input");
        }
        ManagedDocumentSnapshot document = admission.snapshot()
                .managedDocuments().stream()
                .filter(candidate -> candidate.documentId().value()
                        .equals(selected.value()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Admission input has no document " + selected));
        boolean companionFence = verified.platformCommitCompanion()
                .expectedInputDocuments().stream()
                .anyMatch(candidate -> candidate.documentId().value()
                        .equals(selected.value())
                        && candidate.blueId().equals(document.blueId()));
        if (!companionFence) {
            throw new IllegalArgumentException(
                    "Commit companion does not fence admission input "
                            + selected);
        }

        FrozenNode body = FrozenNode.fromNode(document.document());
        ComponentSnapshot component = admission.snapshot().components()
                .stream()
                .filter(candidate -> candidate.orderedMemberDocumentIds()
                        .stream().anyMatch(member -> member.value()
                                .equals(selected.value())))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Admission input has no component for " + selected));
        if (component.kind() == ComponentKind.ACYCLIC) {
            if (!document.blueId().equals(body.blueId())) {
                throw new IllegalArgumentException(
                        "Acyclic admission identity does not match its body");
            }
            return new ExactValue(document.blueId(), body, null);
        }
        int memberIndex = -1;
        for (int index = 0;
                index < component.orderedMemberDocumentIds().size(); index++) {
            if (component.orderedMemberDocumentIds().get(index).value()
                    .equals(selected.value())) {
                memberIndex = index;
                break;
            }
        }
        if (memberIndex < 0
                || component.completeCyclicProof() == null
                || !component.orderedMemberBlueIds().get(memberIndex)
                        .equals(document.blueId())) {
            throw new IllegalArgumentException(
                    "Cyclic admission component does not authenticate "
                            + selected);
        }
        return new ExactValue(document.blueId(), body, null, true);
    }

    /** Returns the content-addressed identity of the whole exact value. */
    public String blueId() {
        return blueId;
    }

    /** Returns a detached mutable boundary copy for a frozen public API call. */
    public Node copyNode() {
        return frozen.toNode();
    }

    /** Returns a semantic pure reference to this whole exact object. */
    public Node referenceNode() {
        return new Node().blueId(blueId);
    }

    /**
     * Returns the shareable immutable local body.
     *
     * <p>For an authenticated cyclic member, {@link #blueId()} is the
     * authoritative {@code MASTER#n} identity while this frozen value is the
     * corresponding local member body.</p>
     */
    public FrozenNode frozen() {
        return frozen;
    }

    /** Returns whether the authoritative identity is a cyclic member suffix. */
    public boolean isCyclicMember() {
        return cyclicMember;
    }

    /** Returns the retained resolver snapshot when one was available. */
    public Optional<ResolvedSnapshot> snapshot() {
        return Optional.ofNullable(snapshot);
    }

    /** Selects an immutable canonical value by JSON Pointer. */
    public FrozenNode canonicalAt(String pointer) {
        String canonical = JsonPointer.canonicalize(
                Objects.requireNonNull(pointer, "pointer"));
        return snapshot != null
                ? snapshot.canonicalAt(canonical)
                : frozen.pathIndex().get(canonical);
    }

    /** Returns the selected canonical BlueId, or null when the path is absent. */
    public String canonicalBlueIdAt(String pointer) {
        FrozenNode selected = canonicalAt(pointer);
        return selected == null ? null : selected.blueId();
    }

    /** Compares exact identity and resolved immutable structure. */
    public boolean sameExactValue(ExactValue other) {
        return other != null
                && blueId.equals(other.blueId)
                && (frozen == other.frozen
                || frozen.sameResolvedStructure(other.frozen));
    }

    private static String requireText(String value, String label) {
        String checked = Objects.requireNonNull(value, label);
        if (checked.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return checked;
    }
}
