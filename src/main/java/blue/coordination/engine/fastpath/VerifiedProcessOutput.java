package blue.coordination.engine.fastpath;

import blue.language.model.Node;
import blue.language.processor.PlatformProcessingResult;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * PROCESS result plus identities calculated once at the semantic boundary.
 * Downstream transition, commit-plan and outbox code consumes these values
 * instead of independently hashing the same document/events again.
 */
public final class VerifiedProcessOutput {
    private final PlatformProcessingResult platform;
    private final String resultingRootBlueId;
    private final List<String> emittedEventBlueIds;
    private final ExactNodeHandle resultingRoot;
    private final List<ExactNodeHandle> emittedEvents;

    public VerifiedProcessOutput(
            PlatformProcessingResult platform,
            String priorRootBlueId,
            RequestDigestMemo digests) {
        this.platform = Objects.requireNonNull(platform, "platform");
        RequestDigestMemo memo = Objects.requireNonNull(digests, "digests");
        Node resultDocument = platform.processResult().document();
        this.resultingRootBlueId = platform.processResult().commits()
                ? memo.blueId(resultDocument)
                : requireText(priorRootBlueId, "priorRootBlueId");
        if (!platform.processResult().commits()) {
            // The verified platform companion establishes that a
            // noncommitting result retains the prior Root identity.
            memo.bindVerified(resultDocument, resultingRootBlueId);
        }
        /* The request memo is also the unforgeable, request-local ownership
         * capability. The engine that supplied it may therefore continue
         * with this one defensive PROCESS-result snapshot instead of asking
         * DocumentProcessingResult to clone the complete Root a second time.
         * The memo is never retained by a public transition accessor. */
        Object owner = memo;
        this.resultingRoot = ExactNodeHandle.adoptBound(
                resultingRootBlueId, resultDocument, owner, memo);
        List<String> eventIds = new ArrayList<String>();
        List<ExactNodeHandle> eventHandles =
                new ArrayList<ExactNodeHandle>();
        List<Node> processEvents = platform.processResult().events();
        for (Node event : processEvents) {
            String eventBlueId = memo.blueId(event);
            eventIds.add(eventBlueId);
            eventHandles.add(ExactNodeHandle.adoptBound(
                    eventBlueId, event, owner, memo));
        }
        this.emittedEventBlueIds = Collections.unmodifiableList(eventIds);
        this.emittedEvents = Collections.unmodifiableList(eventHandles);
    }

    public PlatformProcessingResult platform() { return platform; }
    public String resultingRootBlueId() { return resultingRootBlueId; }
    public List<String> emittedEventBlueIds() { return emittedEventBlueIds; }
    public ExactNodeHandle resultingRoot() { return resultingRoot; }
    public List<ExactNodeHandle> emittedEvents() { return emittedEvents; }

    private static String requireText(String value, String label) {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException(label + " must not be empty");
        }
        return value;
    }
}
