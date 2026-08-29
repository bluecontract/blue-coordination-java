package blue.coordination.internal;

import blue.coordination.api.CoordinationErrorCode;
import blue.coordination.api.CoordinationException;
import blue.coordination.api.DocumentId;
import blue.coordination.api.ExactValue;
import blue.coordination.sdk.ExactNodeEvidence;
import blue.coordination.sdk.ExactNodeProvider;
import blue.language.identity.BlueIds;
import blue.language.codec.jackson.UncheckedObjectMapper;
import blue.language.model.Node;
import blue.language.processor.closure.AdmissionKind;
import blue.language.processor.closure.AffectedClosureSnapshot;
import blue.language.processor.closure.ClosureEvidenceFactory;
import blue.language.processor.closure.ClosureInvocationInput;
import blue.language.processor.closure.ComponentSnapshot;
import blue.language.processor.closure.ManagedDocumentSnapshot;
import blue.language.provider.NodeProvider;
import blue.language.provider.CyclicAwareNodeProvider;
import blue.language.provider.CyclicSetProof;
import blue.language.provider.CyclicSetProofResult;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Compiles one untouched authored Root into a demand-driven ADMIT input. */
public final class Contracts10StaticEmbeddedAdmissionCompiler {
    private static final String ADMISSION_POLICY =
            "contracts-top-level-admission-v1";
    private static final String ADMISSION_LABEL =
            "contracts10-static-process-embedded-admission";

    private final DefaultCoordinationEngine engine;

    public Contracts10StaticEmbeddedAdmissionCompiler(
            DefaultCoordinationEngine engine) {
        this.engine = Objects.requireNonNull(engine, "engine");
    }

    /** Compiles one exact authored static Root without prewalking occurrences. */
    public CompiledStaticAdmission compile(
            String authoredYaml,
            ExactNodeProvider provider) {
        return compile(
                authoredYaml,
                provider,
                Contracts10AuthoredClosureCompiler.ActivationInputs.fromNow());
    }

    /** Compiles one exact authored static Root without prewalking occurrences. */
    public CompiledStaticAdmission compile(
            String authoredYaml,
            ExactNodeProvider provider,
            Contracts10AuthoredClosureCompiler.ActivationInputs activation) {
        Objects.requireNonNull(provider, "provider");
        Contracts10AuthoredClosureCompiler.ActivationInputs selectedActivation =
                Objects.requireNonNull(activation, "activation");
        ExactValue authored = engine.exactValue(requireText(
                authoredYaml, "authoredYaml"));
        DocumentId rootDocumentId = DocumentId.of(authored.blueId());
        blue.language.processor.closure.DocumentId closureRoot =
                new blue.language.processor.closure.DocumentId(
                        rootDocumentId.value());
        ManagedDocumentSnapshot root = new ManagedDocumentSnapshot(
                closureRoot,
                authored.blueId(),
                authored.copyNode(),
                false,
                false,
                true,
                0L,
                1L);
        ComponentSnapshot component = ClosureEvidenceFactory.acyclicComponent(
                root);
        AffectedClosureSnapshot snapshot = ClosureEvidenceFactory
                .affectedClosure(
                        1L,
                        List.of(root),
                        List.of(),
                        List.of(component),
                        List.of(closureRoot));
        ContractsClosureAdmissionAdapter adapter =
                engine.contractsClosureAdmissionAdapter();
        ClosureInvocationInput invocation = ClosureEvidenceFactory.admitClosure(
                snapshot,
                ClosureEvidenceFactory.admissionCause(
                        AdmissionKind.TOP_LEVEL_ADMISSION,
                        ADMISSION_LABEL,
                        null,
                        null,
                        ADMISSION_POLICY),
                null,
                adapter.executionPolicy(),
                adapter.environment());
        return new CompiledStaticAdmission(
                invocation,
                selectedActivation,
                rootDocumentId,
                authored.copyNode());
    }

    /** Read-only verified provider leaf installed in the engine runtime. */
    static NodeProvider verifiedProvider(ExactNodeProvider delegate) {
        return new VerifiedExactNodeProvider(delegate);
    }

    private static String requireText(String value, String label) {
        String selected = Objects.requireNonNull(value, label);
        if (selected.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return selected;
    }

    /** Root-only input retained until automatic Contracts resolution completes. */
    public static final class CompiledStaticAdmission {
        private final ClosureInvocationInput invocation;
        private final Contracts10AuthoredClosureCompiler.ActivationInputs
                activationInputs;
        private final DocumentId rootDocumentId;
        private final Node authoredRoot;

        private CompiledStaticAdmission(
                ClosureInvocationInput invocation,
                Contracts10AuthoredClosureCompiler.ActivationInputs activation,
                DocumentId rootDocumentId,
                Node authoredRoot) {
            this.invocation = Objects.requireNonNull(invocation, "invocation");
            this.activationInputs = Objects.requireNonNull(
                    activation, "activation");
            this.rootDocumentId = Objects.requireNonNull(
                    rootDocumentId, "rootDocumentId");
            this.authoredRoot = Objects.requireNonNull(
                    authoredRoot, "authoredRoot").clone();
        }

        public ClosureInvocationInput invocation() {
            return invocation;
        }

        public Contracts10AuthoredClosureCompiler.ActivationInputs
                activationInputs() {
            return activationInputs;
        }

        public DocumentId rootDocumentId() {
            return rootDocumentId;
        }

        public Node authoredRoot() {
            return authoredRoot.clone();
        }
    }

    private static final class VerifiedExactNodeProvider
            implements NodeProvider, CyclicAwareNodeProvider {
        private final ExactNodeProvider delegate;
        private final Map<String, Optional<ExactValue>> verified =
                new LinkedHashMap<>();

        private VerifiedExactNodeProvider(ExactNodeProvider delegate) {
            this.delegate = Objects.requireNonNull(delegate, "delegate");
        }

        @Override
        public synchronized List<Node> fetchByBlueId(String blueId) {
            String selected = requireText(blueId, "blueId");
            Optional<ExactValue> retained = resolve(selected);
            return retained.isEmpty()
                    ? List.of()
                    : List.of(retained.orElseThrow().copyNode());
        }

        @Override
        public synchronized CyclicSetProofResult cyclicSetProofFor(
                String blueId) {
            Optional<ExactValue> retained = resolve(requireText(
                    blueId, "blueId"));
            if (retained.isEmpty()) {
                return CyclicSetProofResult.notFound();
            }
            return retained.orElseThrow().cyclicSetProof()
                    .map(CyclicSetProofResult::found)
                    .orElseGet(CyclicSetProofResult::notFound);
        }

        private Optional<ExactValue> resolve(String selected) {
            Optional<ExactValue> retained = verified.get(selected);
            if (retained != null) {
                return retained;
            }
            Optional<ExactNodeEvidence> supplied;
            try {
                supplied = Objects.requireNonNull(
                        delegate.findExactEvidence(selected),
                        "provider evidence result");
            } catch (CoordinationException failure) {
                throw failure;
            } catch (RuntimeException failure) {
                throw invalid(selected, null,
                        "Exact-node provider failed while reading content",
                        failure);
            }
            if (supplied.isEmpty()) {
                return Optional.empty();
            }
            ExactNodeEvidence evidence = Objects.requireNonNull(
                    supplied.orElseThrow(), "provider exact evidence");
            Node body;
            try {
                body = UncheckedObjectMapper.YAML_MAPPER.readValue(
                        evidence.exactContent(),
                        Node.class);
            } catch (RuntimeException failure) {
                throw invalid(selected, null,
                        "Exact-node provider returned undecodable content",
                        failure);
            }
            if (body.isReferenceOnly()) {
                throw invalid(selected, null,
                        "Exact-node provider returned another pure reference",
                        null);
            }
            ExactValue exact;
            if (BlueIds.hasCyclicMemberSeparator(selected)) {
                CyclicSetProof proof = evidence.cyclicSetProof().orElseThrow(
                        () -> proofFailure(
                                CoordinationErrorCode
                                        .MISSING_EXACT_VALUE_PROOF,
                                selected,
                                "Exact-node provider returned a cyclic member "
                                        + "without its complete proof",
                                null));
                try {
                    exact = ExactValue.fromVerifiedProviderEvidence(
                            selected, body, proof);
                } catch (RuntimeException failure) {
                    throw proofFailure(
                            CoordinationErrorCode.INVALID_EXACT_VALUE_PROOF,
                            selected,
                            "Exact-node provider cyclic evidence failed "
                                    + "complete-set verification",
                            failure);
                }
            } else {
                if (evidence.cyclicSetProof().isPresent()) {
                    throw proofFailure(
                            CoordinationErrorCode.INVALID_EXACT_VALUE_PROOF,
                            selected,
                            "Exact-node provider attached a cyclic proof to "
                                    + "an ordinary BlueId",
                            null);
                }
                exact = ExactValue.verified(body);
                if (!selected.equals(exact.blueId())) {
                    throw invalid(selected, exact.blueId(),
                            "Exact-node provider returned content with a "
                                    + "different BlueId",
                            null);
                }
            }
            Optional<ExactValue> authenticated = Optional.of(exact);
            verified.put(selected, authenticated);
            return authenticated;
        }

        private static CoordinationException invalid(
                String expectedBlueId,
                String actualBlueId,
                String diagnostic,
                Throwable cause) {
            LinkedHashMap<String, String> details = new LinkedHashMap<>();
            details.put("blueId", expectedBlueId);
            if (actualBlueId != null) {
                details.put("actualBlueId", actualBlueId);
            }
            return new CoordinationException(
                    CoordinationErrorCode.INVALID_DOCUMENT_IDENTITY,
                    diagnostic + " for " + expectedBlueId,
                    cause,
                    details);
        }

        private static CoordinationException proofFailure(
                CoordinationErrorCode code,
                String expectedBlueId,
                String diagnostic,
                Throwable cause) {
            return new CoordinationException(
                    code,
                    diagnostic + " for " + expectedBlueId,
                    cause,
                    Map.of("blueId", expectedBlueId));
        }
    }
}
