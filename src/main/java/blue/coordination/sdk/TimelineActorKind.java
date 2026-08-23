package blue.coordination.sdk;

/** Actor shape authored for entries submitted through one SDK Timeline. */
public enum TimelineActorKind {
    PRINCIPAL("MyOS/Principal Actor"),
    AGENT("MyOS/MyOS Agent Actor");

    private final String blueType;

    TimelineActorKind(String blueType) {
        this.blueType = blueType;
    }

    String blueType() {
        return blueType;
    }
}
