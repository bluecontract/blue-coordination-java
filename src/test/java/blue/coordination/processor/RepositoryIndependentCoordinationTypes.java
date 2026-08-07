package blue.coordination.processor;

import blue.language.identity.DirectBlueIdCalculator;
import blue.language.mapping.TypeClassResolver;
import blue.language.model.Node;
import blue.language.processor.model.DocumentUpdateChannel;
import blue.language.processor.model.EmbeddedNodeChannel;
import blue.language.processor.model.ProcessEmbedded;
import blue.language.processor.model.TriggeredEventChannel;
import blue.language.processor.registry.RuntimeBlueIds;
import blue.coordination.processor.workflow.WorkflowStepTypeProfile;
import blue.repo.coordination.ChatMessage;
import blue.repo.coordination.AllTimelinesChannel;
import blue.repo.coordination.Compute;
import blue.repo.coordination.CompositeTimelineChannel;
import blue.repo.coordination.OperationRequest;
import blue.repo.coordination.SequentialWorkflow;
import blue.repo.coordination.SequentialWorkflowOperation;
import blue.repo.coordination.Timeline;
import blue.repo.coordination.TimelineChannel;
import blue.repo.coordination.TimelineEntry;
import blue.repo.coordination.TriggerEvent;
import blue.repo.coordination.UpdateDocument;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Exact test-owned type surface for repository-independent Coordination runs.
 *
 * <p>The five processor-bearing types are deliberately test identities. They
 * do not impersonate fixed Repository definitions or aliases. Generated Java
 * models are used only as the production processors' independently loadable
 * data classes.</p>
 */
public final class RepositoryIndependentCoordinationTypes {

    public static final String TIMELINE_CHANNEL_NAME =
            "Coordination Test/Repository Independent Timeline Channel";
    public static final String SEQUENTIAL_WORKFLOW_NAME =
            "Coordination Test/Repository Independent Sequential Workflow";
    public static final String SEQUENTIAL_WORKFLOW_OPERATION_NAME =
            "Coordination Test/Repository Independent Sequential Workflow Operation";
    public static final String COMPOSITE_TIMELINE_CHANNEL_NAME =
            "Coordination Test/Repository Independent Composite Timeline Channel";
    public static final String ALL_TIMELINES_CHANNEL_NAME =
            "Coordination Test/Repository Independent All Timelines Channel";
    public static final String TIMELINE_ENTRY_NAME =
            "Coordination Test/Repository Independent Timeline Entry";
    public static final String OPERATION_REQUEST_NAME =
            "Coordination Test/Repository Independent Operation Request";
    public static final String TIMELINE_NAME =
            "Coordination Test/Repository Independent Timeline";
    public static final String ACTOR_NAME =
            "Coordination Test/Repository Independent Actor";
    public static final String CHAT_MESSAGE_NAME =
            "Coordination Test/Repository Independent Chat Message";
    public static final String UPDATE_DOCUMENT_NAME =
            "Coordination Test/Repository Independent Update Document";
    public static final String TRIGGER_EVENT_NAME =
            "Coordination Test/Repository Independent Trigger Event";
    public static final String COMPUTE_NAME =
            "Coordination Test/Repository Independent Compute";

    private static final Node TIMELINE_CHANNEL_TYPE =
            new Node().name(TIMELINE_CHANNEL_NAME);
    private static final Node SEQUENTIAL_WORKFLOW_TYPE =
            new Node().name(SEQUENTIAL_WORKFLOW_NAME);
    private static final Node SEQUENTIAL_WORKFLOW_OPERATION_TYPE =
            new Node().name(SEQUENTIAL_WORKFLOW_OPERATION_NAME);
    private static final Node COMPOSITE_TIMELINE_CHANNEL_TYPE =
            new Node().name(COMPOSITE_TIMELINE_CHANNEL_NAME);
    private static final Node ALL_TIMELINES_CHANNEL_TYPE =
            new Node().name(ALL_TIMELINES_CHANNEL_NAME);
    private static final Node TIMELINE_ENTRY_TYPE =
            new Node().name(TIMELINE_ENTRY_NAME);
    private static final Node OPERATION_REQUEST_TYPE =
            new Node().name(OPERATION_REQUEST_NAME);
    private static final Node TIMELINE_TYPE =
            new Node().name(TIMELINE_NAME);
    private static final Node ACTOR_TYPE =
            new Node().name(ACTOR_NAME);
    private static final Node CHAT_MESSAGE_TYPE =
            new Node().name(CHAT_MESSAGE_NAME);
    private static final Node UPDATE_DOCUMENT_TYPE =
            new Node().name(UPDATE_DOCUMENT_NAME);
    private static final Node TRIGGER_EVENT_TYPE =
            new Node().name(TRIGGER_EVENT_NAME);
    private static final Node COMPUTE_TYPE =
            new Node().name(COMPUTE_NAME);

    public static final String TIMELINE_CHANNEL_BLUE_ID =
            DirectBlueIdCalculator.calculateBlueId(
                    TIMELINE_CHANNEL_TYPE);
    public static final String SEQUENTIAL_WORKFLOW_BLUE_ID =
            DirectBlueIdCalculator.calculateBlueId(
                    SEQUENTIAL_WORKFLOW_TYPE);
    public static final String SEQUENTIAL_WORKFLOW_OPERATION_BLUE_ID =
            DirectBlueIdCalculator.calculateBlueId(
                    SEQUENTIAL_WORKFLOW_OPERATION_TYPE);
    public static final String COMPOSITE_TIMELINE_CHANNEL_BLUE_ID =
            DirectBlueIdCalculator.calculateBlueId(
                    COMPOSITE_TIMELINE_CHANNEL_TYPE);
    public static final String ALL_TIMELINES_CHANNEL_BLUE_ID =
            DirectBlueIdCalculator.calculateBlueId(
                    ALL_TIMELINES_CHANNEL_TYPE);
    public static final String TIMELINE_ENTRY_BLUE_ID =
            DirectBlueIdCalculator.calculateBlueId(TIMELINE_ENTRY_TYPE);
    public static final String OPERATION_REQUEST_BLUE_ID =
            DirectBlueIdCalculator.calculateBlueId(OPERATION_REQUEST_TYPE);
    public static final String TIMELINE_BLUE_ID =
            DirectBlueIdCalculator.calculateBlueId(TIMELINE_TYPE);
    public static final String ACTOR_BLUE_ID =
            DirectBlueIdCalculator.calculateBlueId(ACTOR_TYPE);
    public static final String CHAT_MESSAGE_BLUE_ID =
            DirectBlueIdCalculator.calculateBlueId(CHAT_MESSAGE_TYPE);
    public static final String UPDATE_DOCUMENT_BLUE_ID =
            DirectBlueIdCalculator.calculateBlueId(UPDATE_DOCUMENT_TYPE);
    public static final String TRIGGER_EVENT_BLUE_ID =
            DirectBlueIdCalculator.calculateBlueId(TRIGGER_EVENT_TYPE);
    public static final String COMPUTE_BLUE_ID =
            DirectBlueIdCalculator.calculateBlueId(COMPUTE_TYPE);

    private static final Map<String, String> ALIASES = aliasesInternal();
    private static final Map<String, Node> CANONICAL_TYPES =
            canonicalTypesInternal();

    static {
        assertCanonicalIdentities();
    }

    private RepositoryIndependentCoordinationTypes() {
    }

    /** Returns the exact test Timeline Channel definition. */
    public static Node timelineChannelType() {
        return TIMELINE_CHANNEL_TYPE.clone();
    }

    /** Returns the exact test Sequential Workflow definition. */
    public static Node sequentialWorkflowType() {
        return SEQUENTIAL_WORKFLOW_TYPE.clone();
    }

    /** Returns the exact test Sequential Workflow Operation definition. */
    public static Node sequentialWorkflowOperationType() {
        return SEQUENTIAL_WORKFLOW_OPERATION_TYPE.clone();
    }

    /** Returns the exact test Composite Timeline Channel definition. */
    public static Node compositeTimelineChannelType() {
        return COMPOSITE_TIMELINE_CHANNEL_TYPE.clone();
    }

    /** Returns the exact test All Timelines Channel definition. */
    public static Node allTimelinesChannelType() {
        return ALL_TIMELINES_CHANNEL_TYPE.clone();
    }

    /** Returns immutable aliases bound only to the test identities. */
    public static Map<String, String> aliases() {
        return ALIASES;
    }

    /** Returns clone-isolated canonical test type content by exact BlueId. */
    public static Map<String, Node> canonicalTypes() {
        Map<String, Node> copy = new LinkedHashMap<String, Node>();
        for (Map.Entry<String, Node> entry : CANONICAL_TYPES.entrySet()) {
            copy.put(entry.getKey(), entry.getValue().clone());
        }
        return Collections.unmodifiableMap(copy);
    }

    /**
     * Creates the explicit processor-model resolver used instead of package
     * scanning. In particular, this never loads a Repository composition root.
     */
    public static TypeClassResolver newTypeResolver() {
        return new TypeClassResolver()
                .register(TIMELINE_CHANNEL_BLUE_ID, TimelineChannel.class)
                .register(SEQUENTIAL_WORKFLOW_BLUE_ID,
                        SequentialWorkflow.class)
                .register(SEQUENTIAL_WORKFLOW_OPERATION_BLUE_ID,
                        SequentialWorkflowOperation.class)
                .register(COMPOSITE_TIMELINE_CHANNEL_BLUE_ID,
                        CompositeTimelineChannel.class)
                .register(ALL_TIMELINES_CHANNEL_BLUE_ID,
                        AllTimelinesChannel.class)
                .register(RuntimeBlueIds.PROCESS_EMBEDDED,
                        ProcessEmbedded.class)
                .register(RuntimeBlueIds.DOCUMENT_UPDATE_CHANNEL,
                        DocumentUpdateChannel.class)
                .register(RuntimeBlueIds.TRIGGERED_EVENT_CHANNEL,
                        TriggeredEventChannel.class)
                .register(RuntimeBlueIds.EMBEDDED_NODE_CHANNEL,
                        EmbeddedNodeChannel.class)
                .register(TIMELINE_ENTRY_BLUE_ID, TimelineEntry.class)
                .register(TIMELINE_BLUE_ID, Timeline.class)
                .register(ACTOR_BLUE_ID,
                        blue.repo.coordination.Actor.class)
                .register(CHAT_MESSAGE_BLUE_ID, ChatMessage.class)
                .register(OPERATION_REQUEST_BLUE_ID,
                        OperationRequest.class)
                .register(UPDATE_DOCUMENT_BLUE_ID,
                        UpdateDocument.class)
                .register(TRIGGER_EVENT_BLUE_ID, TriggerEvent.class)
                .register(COMPUTE_BLUE_ID, Compute.class);
    }

    /** Returns the exact custom semantic identity profile for this runtime. */
    public static CoordinationSemanticTypeIdentities semanticTypes() {
        return CoordinationSemanticTypeIdentities.exact(
                TIMELINE_ENTRY_BLUE_ID,
                OPERATION_REQUEST_BLUE_ID,
                TIMELINE_BLUE_ID,
                ACTOR_BLUE_ID);
    }

    /** Exact polymorphic workflow-step bindings for this test generation. */
    public static WorkflowStepTypeProfile workflowStepTypes() {
        return WorkflowStepTypeProfile.builder()
                .updateDocument(UPDATE_DOCUMENT_BLUE_ID)
                .triggerEvent(TRIGGER_EVENT_BLUE_ID)
                .compute(COMPUTE_BLUE_ID)
                .build();
    }

    /** Fails immediately if any retained canonical node drifts from its key. */
    public static void assertCanonicalIdentities() {
        for (Map.Entry<String, Node> entry
                : canonicalTypesInternal().entrySet()) {
            String actual = DirectBlueIdCalculator.calculateBlueId(
                    entry.getValue());
            if (!entry.getKey().equals(actual)) {
                throw new IllegalStateException(
                        "Repository-independent canonical type identity "
                                + "mismatch: expected " + entry.getKey()
                                + " but calculated " + actual);
            }
        }
    }

    /** Authors one test Timeline Channel with generated binding value types. */
    public static Node timelineChannel(
            String timelineId,
            String actorId) {
        return typed(TIMELINE_CHANNEL_BLUE_ID)
                .properties("timeline", typed(TIMELINE_BLUE_ID)
                        .properties("timelineId",
                                scalar(timelineId)))
                .properties("actor",
                        typed(ACTOR_BLUE_ID)
                                .properties("accountId",
                                        scalar(actorId)));
    }

    /** Authors one production-model Sequential Workflow under the test type. */
    public static Node sequentialWorkflow(
            String channelKey,
            Node... steps) {
        return typed(SEQUENTIAL_WORKFLOW_BLUE_ID)
                .properties("channel", scalar(channelKey))
                .properties("steps", new Node().items(
                        Arrays.asList(cloneNodes(steps))));
    }

    /** Authors one production-model workflow operation under the test type. */
    public static Node sequentialWorkflowOperation(
            String channelKey,
            Node... steps) {
        return typed(SEQUENTIAL_WORKFLOW_OPERATION_BLUE_ID)
                .properties("channel", scalar(channelKey))
                .properties("steps", new Node().items(
                        Arrays.asList(cloneNodes(steps))));
    }

    /** Authors one real Update Document workflow step. */
    public static Node updateDocumentStep(
            String operation,
            String path,
            Node value) {
        return typed(UPDATE_DOCUMENT_BLUE_ID)
                .properties("changeset", new Node().items(
                        new Node()
                                .properties("op", scalar(operation))
                                .properties("path", scalar(path))
                                .properties("val", Objects.requireNonNull(
                                        value, "value").clone())));
    }

    /** Authors one replacement Update Document workflow step. */
    public static Node updateDocumentStep(String path, Node value) {
        return updateDocumentStep("replace", path, value);
    }

    /** Authors one real Trigger Event workflow step. */
    public static Node triggerEventStep(Node event) {
        return typed(TRIGGER_EVENT_BLUE_ID)
                .properties("event", Objects.requireNonNull(
                        event, "event").clone());
    }

    /** Authors one exact generated Chat Message without a Repository alias. */
    public static Node chatMessage(String message) {
        return typed(CHAT_MESSAGE_BLUE_ID)
                .properties("message", scalar(message));
    }

    /** Authors one exact generated Operation Request without an alias. */
    public static Node operationRequest(
            String operation,
            String channel,
            Node request) {
        return typed(OPERATION_REQUEST_BLUE_ID)
                .properties("operation", scalar(operation))
                .properties("channel", scalar(channel))
                .properties("request", Objects.requireNonNull(
                        request, "request").clone());
    }

    /** Authors one exact Timeline Entry around any exact message. */
    public static Node timelineEntry(
            String timelineId,
            String actorId,
            BigInteger timestamp,
            Node message) {
        return typed(TIMELINE_ENTRY_BLUE_ID)
                .properties("timeline", typed(TIMELINE_BLUE_ID)
                        .properties("timelineId", scalar(timelineId)))
                .properties("actor",
                        typed(ACTOR_BLUE_ID)
                                .properties("accountId", scalar(actorId)))
                .properties("timestamp", scalar(Objects.requireNonNull(
                        timestamp, "timestamp")))
                .properties("message", Objects.requireNonNull(
                        message, "message").clone());
    }

    /** Authors one Timeline Entry whose message is an Operation Request. */
    public static Node operationRequestTimelineEntry(
            String timelineId,
            String actorId,
            BigInteger timestamp,
            String operation,
            String channel,
            Node request) {
        return timelineEntry(
                timelineId,
                actorId,
                timestamp,
                operationRequest(operation, channel, request));
    }

    /** Authors an exact type reference. */
    public static Node typed(String blueId) {
        return new Node().type(new Node().blueId(
                Objects.requireNonNull(blueId, "blueId")));
    }

    private static Node scalar(Object value) {
        return new Node().value(value);
    }

    private static Node[] cloneNodes(Node[] nodes) {
        Objects.requireNonNull(nodes, "nodes");
        Node[] copy = new Node[nodes.length];
        for (int index = 0; index < nodes.length; index++) {
            copy[index] = Objects.requireNonNull(
                    nodes[index], "steps[" + index + "]").clone();
        }
        return copy;
    }

    private static Map<String, String> aliasesInternal() {
        Map<String, String> aliases = new LinkedHashMap<String, String>();
        aliases.put(TIMELINE_CHANNEL_NAME, TIMELINE_CHANNEL_BLUE_ID);
        aliases.put(SEQUENTIAL_WORKFLOW_NAME, SEQUENTIAL_WORKFLOW_BLUE_ID);
        aliases.put(SEQUENTIAL_WORKFLOW_OPERATION_NAME,
                SEQUENTIAL_WORKFLOW_OPERATION_BLUE_ID);
        aliases.put(COMPOSITE_TIMELINE_CHANNEL_NAME,
                COMPOSITE_TIMELINE_CHANNEL_BLUE_ID);
        aliases.put(ALL_TIMELINES_CHANNEL_NAME,
                ALL_TIMELINES_CHANNEL_BLUE_ID);
        aliases.put(TIMELINE_ENTRY_NAME, TIMELINE_ENTRY_BLUE_ID);
        aliases.put(OPERATION_REQUEST_NAME, OPERATION_REQUEST_BLUE_ID);
        aliases.put(TIMELINE_NAME, TIMELINE_BLUE_ID);
        aliases.put(ACTOR_NAME, ACTOR_BLUE_ID);
        aliases.put(CHAT_MESSAGE_NAME, CHAT_MESSAGE_BLUE_ID);
        aliases.put(UPDATE_DOCUMENT_NAME, UPDATE_DOCUMENT_BLUE_ID);
        aliases.put(TRIGGER_EVENT_NAME, TRIGGER_EVENT_BLUE_ID);
        aliases.put(COMPUTE_NAME, COMPUTE_BLUE_ID);
        return Collections.unmodifiableMap(aliases);
    }

    private static Map<String, Node> canonicalTypesInternal() {
        Map<String, Node> types = new LinkedHashMap<String, Node>();
        types.put(TIMELINE_CHANNEL_BLUE_ID,
                TIMELINE_CHANNEL_TYPE.clone());
        types.put(SEQUENTIAL_WORKFLOW_BLUE_ID,
                SEQUENTIAL_WORKFLOW_TYPE.clone());
        types.put(SEQUENTIAL_WORKFLOW_OPERATION_BLUE_ID,
                SEQUENTIAL_WORKFLOW_OPERATION_TYPE.clone());
        types.put(COMPOSITE_TIMELINE_CHANNEL_BLUE_ID,
                COMPOSITE_TIMELINE_CHANNEL_TYPE.clone());
        types.put(ALL_TIMELINES_CHANNEL_BLUE_ID,
                ALL_TIMELINES_CHANNEL_TYPE.clone());
        types.put(TIMELINE_ENTRY_BLUE_ID, TIMELINE_ENTRY_TYPE.clone());
        types.put(OPERATION_REQUEST_BLUE_ID,
                OPERATION_REQUEST_TYPE.clone());
        types.put(TIMELINE_BLUE_ID, TIMELINE_TYPE.clone());
        types.put(ACTOR_BLUE_ID, ACTOR_TYPE.clone());
        types.put(CHAT_MESSAGE_BLUE_ID, CHAT_MESSAGE_TYPE.clone());
        types.put(UPDATE_DOCUMENT_BLUE_ID, UPDATE_DOCUMENT_TYPE.clone());
        types.put(TRIGGER_EVENT_BLUE_ID, TRIGGER_EVENT_TYPE.clone());
        types.put(COMPUTE_BLUE_ID, COMPUTE_TYPE.clone());
        return types;
    }
}
