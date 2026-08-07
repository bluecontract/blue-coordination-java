package blue.coordination.processor;

import blue.language.model.Node;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.identity.BlueIds;
import blue.language.mapping.BlueMapper;

/**
 * Compares Blue values by semantic identity rather than serialized
 * representation.
 *
 * <p>Reference-only nodes keep their declared identity. Equality completes
 * materialized values through Language's current mapping and identity APIs,
 * while exact event/checkpoint identity hashes Language's canonical exact
 * representation without recursively opening opaque header references.</p>
 */
final class BlueSemanticIdentity {
    private static final BlueMapper REPOSITORY_MAPPER =
            BlueMapper.builder()
                    .scanPackage("blue.repo")
                    .build();

    private BlueSemanticIdentity() {
    }

    static boolean equals(Node left, Node right) {
        if (left == null || right == null) {
            return false;
        }
        if (left.isReferenceOnly()
                && right.isReferenceOnly()) {
            return referenceIdentity(left).equals(
                    referenceIdentity(right));
        }
        if (left.isReferenceOnly()) {
            return referenceMatches(referenceIdentity(left), right);
        }
        if (right.isReferenceOnly()) {
            return referenceMatches(referenceIdentity(right), left);
        }
        Node leftExact = exactCopy(left);
        Node rightExact = exactCopy(right);
        Class<?> leftClass = semanticClass(leftExact);
        Class<?> rightClass = semanticClass(rightExact);
        /*
         * A typed parent can legitimately omit the type on one of its
         * authored children. Mapping the two values through the class known
         * by either representation preserves that authored semantic shape.
         */
        return semanticIdentity(
                leftExact,
                leftClass != null ? leftClass : rightClass)
                .equals(semanticIdentity(
                        rightExact,
                        rightClass != null ? rightClass : leftClass));
    }

    static String identity(Node node) {
        if (node == null) {
            throw new IllegalArgumentException(
                    "Semantic identity node must be present");
        }
        if (node.isReferenceOnly()) {
            return BlueIds.requireBlueIdOrCyclicMember(
                    node.getBlueId(),
                    "Exact identity reference");
        }
        return DirectBlueIdCalculator.calculateBlueId(
                CoordinationProcessHeaderBridge
                        .canonicalExactCopy(node));
    }

    private static String semanticIdentity(
            Node exact,
            Class<?> semanticClass) {
        return DirectBlueIdCalculator.calculateBlueId(
                normalize(exact, semanticClass));
    }

    private static Node normalize(
            Node exact,
            Class<?> semanticClass) {
        if (semanticClass != null) {
            /*
             * Repository completion contributes inherited field
             * descriptions and schemas to resolved generated values. The
             * generated-object round trip projects those values back to
             * their authored semantic fields before comparison, while the
             * canonical exact copy above prevents a resolved nominal type
             * from becoming an illegal mixed BlueId node.
             */
            exact = REPOSITORY_MAPPER.toNode(
                    REPOSITORY_MAPPER.fromNode(
                            exact, semanticClass));
        }
        return exact;
    }

    private static boolean referenceMatches(
            String referenceIdentity,
            Node materialized) {
        Node exact = exactCopy(materialized);
        Class<?> semanticClass = semanticClass(exact);
        Node normalized = normalize(exact, semanticClass);
        if (referenceIdentity.equals(
                DirectBlueIdCalculator.calculateBlueId(normalized))) {
            return true;
        }
        if (semanticClass == null) {
            return false;
        }
        /*
         * A reference can have been calculated while the value was an
         * untyped child of a typed parent. Retain the exact authored fields
         * and omit only the inferred root type when checking that legitimate
         * representation. Nested field types and all content remain
         * identity-bearing.
         */
        Node inferredChildProjection =
                normalized.clone()
                        .type((Node) null);
        return referenceIdentity.equals(
                DirectBlueIdCalculator.calculateBlueId(
                        inferredChildProjection));
    }

    private static Class<?> semanticClass(Node exact) {
        return REPOSITORY_MAPPER.mappedClass(exact)
                .filter(candidate ->
                        !Object.class.equals(candidate)
                                && !Node.class.equals(candidate))
                .orElse(null);
    }

    private static Node exactCopy(Node node) {
        return CoordinationProcessHeaderBridge
                .canonicalExactCopy(node);
    }

    private static String referenceIdentity(Node node) {
        return BlueIds.requireBlueIdOrCyclicMember(
                node.getBlueId(),
                "Semantic identity reference");
    }
}
