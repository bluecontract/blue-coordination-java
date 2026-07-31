package blue.coordination.processor.mandate;

import blue.language.Blue;
import blue.language.BlueLanguageErrorCategory;
import blue.language.BlueLanguageErrorClassifier;
import blue.language.model.Node;
import blue.language.processor.ContractMatchingService;
import blue.language.processor.GasSchedule;
import blue.language.processor.GasScheduleConstants;
import blue.language.snapshot.FrozenNode;
import blue.language.utils.BlueIdCalculator;
import blue.language.utils.FrozenTypeMatcher;
import blue.language.utils.NodeToBlueIdInput;
import blue.repo.BlueRepository;
import blue.repo.mandate.DocumentResponderMandate;
import blue.repo.mandate.MandateAuthority;
import blue.repo.mandate.OperationMandate;
import blue.repo.mandate.StatusActive;

import java.math.BigInteger;

/**
 * Exact-node and fixed-repository matching support shared by Mandate
 * eligibility decisions.
 *
 * <p>Structural patterns are evaluated by Language's contract matcher, while
 * Mandate/status/authority types are checked against their generated fixed
 * repository identities and verified subtype lineage. The four-way match
 * result keeps unavailable provider evidence distinct from malformed or
 * ordinary non-matching evidence.</p>
 */
final class MandateEligibilityNodes {
    private static final BlueRepository REPOSITORY =
            BlueRepository.latest();

    /** Outcome vocabulary used to preserve evidence failure semantics. */
    enum Match {
        MATCH,
        NO_MATCH,
        UNAVAILABLE,
        INVALID
    }

    private MandateEligibilityNodes() {
    }

    static MatchingContext fixedRepositoryMatchingContext() {
        return new MatchingContext(
                REPOSITORY.configure(new Blue()));
    }

    static Node property(Node node, String key) {
        if (node == null || node.getProperties() == null) {
            return null;
        }
        return node.getProperties().get(key);
    }

    static Node participant(Node mandate, String key) {
        Node direct = property(mandate, key);
        if (direct != null) {
            return direct;
        }
        return property(mandate != null ? mandate.getContracts() : null, key);
    }

    static String text(Node node) {
        Object value = node != null ? node.getValue() : null;
        return value instanceof String && !((String) value).trim().isEmpty()
                ? (String) value
                : null;
    }

    static BigInteger integer(Node node) {
        Object value = node != null ? node.getValue() : null;
        if (value instanceof BigInteger) {
            return (BigInteger) value;
        }
        if (value instanceof Byte || value instanceof Short
                || value instanceof Integer || value instanceof Long) {
            return BigInteger.valueOf(((Number) value).longValue());
        }
        return null;
    }

    static String exactBlueId(Node node, String role) {
        if (node == null) {
            throw new IllegalArgumentException(role + " is required");
        }
        try {
            if (node.isReferenceOnly()) {
                return BlueIdCalculator.calculateBlueId(node);
            }
            return BlueIdCalculator.INSTANCE.calculate(
                    NodeToBlueIdInput.getWithResolvedBlueIdMetadata(node));
        } catch (RuntimeException invalidExactNode) {
            throw new IllegalArgumentException(
                    role + " must be an exact alias-free Blue node",
                    invalidExactNode);
        }
    }

    static boolean sameExact(Node left, Node right) {
        return exactBlueId(left, "left exact node").equals(
                exactBlueId(right, "right exact node"));
    }

    static Match matchesPattern(
            MatchingContext context,
            Node candidate,
            Node pattern) {
        if (pattern == null) {
            return Match.MATCH;
        }
        if (candidate == null) {
            return Match.NO_MATCH;
        }
        try {
            if (candidate.isReferenceOnly()
                    && !pattern.isReferenceOnly()) {
                return Match.UNAVAILABLE;
            }
            return context.matches(candidate, pattern)
                    ? Match.MATCH
                    : Match.NO_MATCH;
        } catch (RuntimeException invalidExactNode) {
            return Match.INVALID;
        }
    }

    static String nonBlank(String value, String fallback) {
        return value != null && !value.trim().isEmpty()
                ? value
                : fallback;
    }

    /**
     * Invocation-local owner of Language matching and verified-type caches.
     * Closing it releases all provider-backed caches after one decision.
     */
    static final class MatchingContext implements AutoCloseable {
        private final Blue blue;
        private final ContractMatchingService matchingService;
        private final FrozenTypeMatcher fixedTypeMatcher;
        private final long maximumTypeChainEdges;

        private MatchingContext(Blue blue) {
            this.blue = blue;
            this.matchingService =
                    new ContractMatchingService(blue);
            this.fixedTypeMatcher =
                    FrozenTypeMatcher
                            .withVerifiedReferenceMaterializer(
                                    reference ->
                                            blue.loadSnapshot(
                                                    reference
                                                            .getReferenceBlueId())
                                                    .frozenCanonicalRoot());
            this.maximumTypeChainEdges =
                    GasSchedule.contracts10()
                            .portableLimit(
                                    GasScheduleConstants
                                            .PortableLimit
                                            .TYPE_CHAIN_EDGES);
        }

        Match operationMandateType(Node value) {
            return fixedType(
                    value,
                    OperationMandate
                            .repositoryType()
                            .reference());
        }

        Match documentResponderMandateType(Node value) {
            return fixedType(
                    value,
                    DocumentResponderMandate
                            .repositoryType()
                            .reference());
        }

        Match activeStatusType(Node value) {
            return fixedType(
                    value,
                    StatusActive
                            .repositoryType()
                            .reference());
        }

        Match mandateAuthorityType(Node value) {
            return fixedType(
                    value,
                    MandateAuthority
                            .repositoryType()
                            .reference());
        }

        boolean matches(Node candidate, Node pattern) {
            return matchingService.matches(
                    candidate, pattern);
        }

        private Match fixedType(
                Node value,
                Node fixedType) {
            if (value == null || value.getType() == null) {
                return Match.NO_MATCH;
            }
            try {
                if (sameExact(value.getType(), fixedType)) {
                    return Match.MATCH;
                }
                return fixedTypeMatcher.isSubtypeOrSame(
                        FrozenNode.fromNode(
                                value.getType().clone()),
                        FrozenNode.fromNode(fixedType),
                        maximumTypeChainEdges)
                        ? Match.MATCH
                        : Match.NO_MATCH;
            } catch (RuntimeException failure) {
                return BlueLanguageErrorClassifier
                        .classify(failure)
                        == BlueLanguageErrorCategory
                        .ProviderUnavailable
                        ? Match.UNAVAILABLE
                        : Match.INVALID;
            }
        }

        @Override
        public void close() {
            matchingService.clearCaches();
            fixedTypeMatcher.clearCaches();
            blue.close();
        }
    }
}
