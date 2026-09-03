package blue.coordination.internal;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Stable diagnostic projection for one declared Process Embedded collection. */
record EmbeddedCollectionPlanningAudit(
        String collectionPath,
        State state,
        int currentMemberCount,
        String detail) {

    enum State {
        ABSENT,
        PRESENT_EMPTY,
        PRESENT_MEMBERS,
        INCOMPLETE,
        INVALID_KIND
    }

    EmbeddedCollectionPlanningAudit {
        collectionPath = Objects.requireNonNull(
                collectionPath, "collectionPath");
        state = Objects.requireNonNull(state, "state");
        detail = Objects.requireNonNull(detail, "detail");
        boolean countable = state == State.ABSENT
                || state == State.PRESENT_EMPTY
                || state == State.PRESENT_MEMBERS;
        if ((countable && currentMemberCount < 0)
                || (!countable && currentMemberCount != -1)) {
            throw new IllegalArgumentException(
                    "Member count must be non-negative for complete states "
                            + "and -1 otherwise");
        }
    }

    static EmbeddedCollectionPlanningAudit completeObject(
            String collectionPath,
            boolean present,
            int currentMemberCount) {
        if (!present && currentMemberCount != 0) {
            throw new IllegalArgumentException(
                    "An absent collection cannot have current members");
        }
        return new EmbeddedCollectionPlanningAudit(
                collectionPath,
                !present
                        ? State.ABSENT
                        : currentMemberCount == 0
                                ? State.PRESENT_EMPTY
                                : State.PRESENT_MEMBERS,
                currentMemberCount,
                !present
                        ? "declared collection is absent"
                        : currentMemberCount == 0
                                ? "declared collection is present and empty"
                                : "declared collection has current members");
    }

    static EmbeddedCollectionPlanningAudit incomplete(
            String collectionPath,
            String detail) {
        return new EmbeddedCollectionPlanningAudit(
                collectionPath, State.INCOMPLETE, -1, detail);
    }

    static EmbeddedCollectionPlanningAudit invalidKind(
            String collectionPath,
            String detail) {
        return new EmbeddedCollectionPlanningAudit(
                collectionPath, State.INVALID_KIND, -1, detail);
    }

    /** Deterministic fields suitable for logs, reports, and test evidence. */
    Map<String, Object> reportFields() {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("collectionPath", collectionPath);
        fields.put("state", state.name());
        fields.put("currentMemberCount", currentMemberCount);
        fields.put("detail", detail);
        return Map.copyOf(fields);
    }
}
