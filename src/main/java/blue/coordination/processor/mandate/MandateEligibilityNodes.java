package blue.coordination.processor.mandate;

import blue.language.api.BlueLanguageErrorCategory;
import blue.language.api.BlueLanguageErrorClassifier;
import blue.language.model.Node;
import blue.language.processor.ContractMatchingService;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.identity.BlueIds;
import blue.language.identity.NodeToBlueIdInput;
import blue.language.mapping.TypeClassResolver;
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
    private static final TypeClassResolver REPOSITORY_TYPES =
            new TypeClassResolver("blue.repo");

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
        return new MatchingContext();
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
                return BlueIds.requireBlueIdOrCyclicMember(
                        node.getBlueId(), role);
            }
            return DirectBlueIdCalculator.INSTANCE
                    .directBlueIdFromCanonicalInput(
                            NodeToBlueIdInput
                                    .getWithResolvedBlueIdMetadata(node));
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
        private final ContractMatchingService matchingService;

        private MatchingContext() {
            this.matchingService = new ContractMatchingService();
        }

        Match operationMandateType(Node value) {
            return fixedType(
                    value,
                    OperationMandate.blueId(),
                    OperationMandate.class);
        }

        Match documentResponderMandateType(Node value) {
            return fixedType(
                    value,
                    DocumentResponderMandate.blueId(),
                    DocumentResponderMandate.class);
        }

        Match activeStatusType(Node value) {
            return fixedType(
                    value,
                    StatusActive.blueId(),
                    StatusActive.class);
        }

        Match mandateAuthorityType(Node value) {
            return fixedType(
                    value,
                    MandateAuthority.blueId(),
                    MandateAuthority.class);
        }

        boolean matches(Node candidate, Node pattern) {
            return matchingService.matches(
                    candidate, pattern);
        }

        private Match fixedType(
                Node value,
                String fixedTypeBlueId,
                Class<?> fixedTypeClass) {
            if (value == null || value.getType() == null) {
                return Match.NO_MATCH;
            }
            try {
                String candidateTypeBlueId =
                        exactBlueId(value.getType(), "mandate type");
                if (fixedTypeBlueId.equals(candidateTypeBlueId)) {
                    return Match.MATCH;
                }
                Class<?> candidateType =
                        REPOSITORY_TYPES.resolveClass(
                                candidateTypeBlueId);
                return candidateType != null
                        && fixedTypeClass.isAssignableFrom(candidateType)
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
        }
    }
}
