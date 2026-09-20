package blue.coordination.internal;

import blue.language.model.Node;
import blue.repo.coordination.TimelineEntry;
import blue.repo.myos.MyOSTimeline;
import blue.repo.myos.PrincipalActor;

/** Exact canonical fixtures; no operation wrapper or authored alias rewrite. */
final class GeneralTimelineEntryMother {
    private GeneralTimelineEntryMother() {}
    static Node event(Node message) {
        return new Node().type(new Node().blueId(TimelineEntry.blueId()))
                .properties("timeline", new Node().type(new Node().blueId(MyOSTimeline.blueId()))
                        .properties("timelineId", new Node().value("general-entry")))
                .properties("actor", new Node().type(new Node().blueId(PrincipalActor.blueId()))
                        .properties("accountId", new Node().value("alice")))
                .properties("timestamp", new Node().value(1_800_000_000_000_000L))
                .properties("message", message);
    }
}
