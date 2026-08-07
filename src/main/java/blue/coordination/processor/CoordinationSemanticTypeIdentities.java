package blue.coordination.processor;

import blue.language.api.NodeProviderOutcome;
import blue.language.identity.BlueIds;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import blue.language.provider.NodeProvider;
import blue.language.provider.NodeProviderResult;

import java.util.List;
import java.util.Objects;

/**
 * Immutable semantic event identities used by Coordination routing.
 *
 * <p>Published defaults preserve the Coordination 1.0 wire contract. A host
 * may bind independently authored exact types for an isolated runtime, but a
 * custom binding is admitted only when the runtime provider returns canonical
 * content whose direct identity is exactly the configured BlueId.</p>
 */
public final class CoordinationSemanticTypeIdentities {

    private static final CoordinationSemanticTypeIdentities
            PUBLISHED_DEFAULTS =
            publishedDefaultsFromCurrentRepository();

    private final String timelineEntryBlueId;
    private final String operationRequestBlueId;
    private final String timelineBlueId;
    private final String actorBlueId;
    private final String profileIdentity;
    private final boolean custom;

    private static CoordinationSemanticTypeIdentities
    publishedDefaultsFromCurrentRepository() {
        CoordinationCurrentRepositoryIdentities current =
                CoordinationCurrentRepositoryIdentities.current();
        return new CoordinationSemanticTypeIdentities(
                current.timelineEntryBlueId(),
                current.operationRequestBlueId(),
                current.timelineBlueId(),
                current.actorBlueId(),
                false);
    }

    private CoordinationSemanticTypeIdentities(
            String timelineEntryBlueId,
            String operationRequestBlueId,
            String timelineBlueId,
            String actorBlueId,
            boolean custom) {
        this.timelineEntryBlueId = requireBlueId(
                timelineEntryBlueId, "timelineEntryBlueId");
        this.operationRequestBlueId = requireBlueId(
                operationRequestBlueId, "operationRequestBlueId");
        this.timelineBlueId = requireBlueId(
                timelineBlueId, "timelineBlueId");
        this.actorBlueId = requireBlueId(
                actorBlueId, "actorBlueId");
        this.custom = custom;
        this.profileIdentity = DirectBlueIdCalculator.calculateBlueId(
                new Node()
                        .properties("kind", new Node().value(
                                "blue.coordination/semantic-type-profile/1"))
                        .properties("timelineEntry", new Node().value(
                                this.timelineEntryBlueId))
                        .properties("operationRequest", new Node().value(
                                this.operationRequestBlueId))
                        .properties("timeline", new Node().value(
                                this.timelineBlueId))
                        .properties("actor", new Node().value(
                                this.actorBlueId)));
    }

    /** Returns the unchanged fixed-Repository Coordination 1.0 identities. */
    public static CoordinationSemanticTypeIdentities publishedDefaults() {
        return PUBLISHED_DEFAULTS;
    }

    /**
     * Creates custom runtime identities. The result must be validated against
     * the exact provider selected for that runtime before processor assembly.
     */
    public static CoordinationSemanticTypeIdentities exact(
            String timelineEntryBlueId,
            String operationRequestBlueId,
            String timelineBlueId,
            String actorBlueId) {
        return new CoordinationSemanticTypeIdentities(
                timelineEntryBlueId,
                operationRequestBlueId,
                timelineBlueId,
                actorBlueId,
                true);
    }

    public String timelineEntryBlueId() {
        return timelineEntryBlueId;
    }

    public String operationRequestBlueId() {
        return operationRequestBlueId;
    }

    public String timelineBlueId() {
        return timelineBlueId;
    }

    public String actorBlueId() {
        return actorBlueId;
    }

    /** Identity of the complete immutable quartet, for persisted evidence. */
    public String profileIdentity() {
        return profileIdentity;
    }

    /** Returns whether these identities require provider-evidence admission. */
    public boolean custom() {
        return custom;
    }

    /**
     * Validates every custom identity against exact canonical provider
     * content. Published defaults retain their existing release binding.
     */
    CoordinationSemanticTypeIdentities validatedAgainst(
            NodeProvider provider) {
        if (!custom) {
            return this;
        }
        NodeProvider exactProvider = Objects.requireNonNull(
                provider, "provider");
        requireExactCanonicalContent(
                exactProvider,
                timelineEntryBlueId,
                "Timeline Entry");
        requireExactCanonicalContent(
                exactProvider,
                operationRequestBlueId,
                "Operation Request");
        requireExactCanonicalContent(
                exactProvider,
                timelineBlueId,
                "Timeline");
        requireExactCanonicalContent(
                exactProvider,
                actorBlueId,
                "Actor");
        return this;
    }

    private static void requireExactCanonicalContent(
            NodeProvider provider,
            String expectedBlueId,
            String semanticName) {
        NodeProviderResult result = provider.fetchResultByBlueId(
                expectedBlueId);
        if (result.outcome() != NodeProviderOutcome.FOUND) {
            throw new IllegalArgumentException(
                    semanticName + " semantic identity " + expectedBlueId
                            + " has no exact canonical provider content: "
                            + result.outcome());
        }
        List<Node> nodes = result.nodes();
        if (nodes.size() != 1) {
            throw new IllegalArgumentException(
                    semanticName + " semantic identity " + expectedBlueId
                            + " requires exactly one canonical node, found "
                            + nodes.size());
        }
        Node canonical = nodes.get(0).clone();
        if (canonical.getBlueId() != null) {
            if (!expectedBlueId.equals(canonical.getBlueId())) {
                throw new IllegalArgumentException(
                        semanticName + " provider content declares "
                                + canonical.getBlueId() + " instead of "
                                + expectedBlueId);
            }
            canonical.blueId(null);
        }
        String calculated;
        try {
            calculated = DirectBlueIdCalculator.calculateBlueId(canonical);
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException(
                    semanticName + " provider content is not canonical "
                            + "BlueId input for " + expectedBlueId,
                    invalid);
        }
        if (!expectedBlueId.equals(calculated)) {
            throw new IllegalArgumentException(
                    semanticName + " provider content calculated to "
                            + calculated + " instead of " + expectedBlueId);
        }
    }

    private static String requireBlueId(
            String blueId,
            String label) {
        String exact = Objects.requireNonNull(blueId, label).trim();
        if (exact.isEmpty() || BlueIds.hasCyclicMemberSeparator(exact)) {
            throw new IllegalArgumentException(
                    label + " must be one non-cyclic exact BlueId");
        }
        return exact;
    }
}
