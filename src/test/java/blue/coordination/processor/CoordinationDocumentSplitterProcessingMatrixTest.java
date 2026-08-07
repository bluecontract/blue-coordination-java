package blue.coordination.processor;

import blue.language.provider.NodeProvider;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import blue.language.model.TypeBlueId;
import blue.language.processor.CheckpointDomain;
import blue.language.processor.ChannelEvaluation;
import blue.language.processor.ChannelEvaluationContext;
import blue.language.processor.ChannelProcessor;
import blue.language.processor.BlueContracts;
import blue.language.processor.ContractProcessorRegistry;
import blue.language.processor.ContractProcessorRegistryBuilder;
import blue.language.processor.CoordinationFragmentationCatalogHarness;
import blue.language.processor.DocumentProcessingResult;
import blue.language.processor.DocumentProcessor;
import blue.language.processor.EffectiveContractSnapshotConstants;
import blue.language.processor.ExternalDeliveryPlan;
import blue.language.processor.ExternalDeliverySnapshot;
import blue.language.processor.ExternalChannelSubscriptionFunctions;
import blue.language.processor.ExternalOrderKey;
import blue.language.processor.GasSchedule;
import blue.language.processor.GasTraceEntry;
import blue.language.processor.HandlerMatchContext;
import blue.language.processor.HandlerProcessor;
import blue.language.processor.ProcessingConformanceTrace;
import blue.language.processor.ProcessingDebugResult;
import blue.language.processor.ProcessingTraceRecord;
import blue.language.processor.ProcessorDiagnostic;
import blue.language.processor.ProcessorExecutionContext;
import blue.language.processor.ProcessorStatus;
import blue.language.processor.SubscriptionDelta;
import blue.language.processor.model.ChannelContract;
import blue.language.processor.model.HandlerContract;
import blue.language.processor.model.JsonPatch;
import blue.language.processor.registry.BlueRuntimeTypeRegistry;
import blue.language.processor.registry.RuntimeBlueIds;
import blue.language.processor.registry.RuntimeTypeKey;
import blue.language.provider.SequentialNodeProvider;
import blue.language.runtime.BlueLanguage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end proof that the public Coordination splitter feeds Language's
 * two-BlueId PROCESS boundary without changing execution semantics.
 *
 * <p>The fixture deliberately has no Process Embedded declaration. Its five
 * Handler headers are all reactive, while only one executable body can be
 * selected by the immutable delivery plan. A large archive and four decoy
 * bodies remain available to the strict provider, but any demand for them
 * fails the run immediately.</p>
 */
final class CoordinationDocumentSplitterProcessingMatrixTest {

    private static final String SELECTED_CHANNEL = "incoming";
    private static final String REJECTED_CHANNEL = "rejected";
    private static final String SELECTED_HANDLER = "selectedWorkflow";
    private static final String SUBSCRIPTION_KEY =
            "coordination-fragment-matrix";
    private static final String CHECKPOINT_DISCRIMINATOR =
            "coordination-fragment-matrix-v1";
    private static final int LARGE_VALUE_SIZE = 24_000;
    private static final ExternalOrderKey EVENT_ORDER =
            ExternalOrderKey.of(Arrays.<Object>asList(
                    8128, "coordination-fragment-matrix", 1));

    @Test
    void shouldPreserveProcessSemanticsAcrossSplitRepresentations() {
        // given
        Scenario scenario = Scenario.create();
        List<Variant> variants =
                Variant.matrix();

        // when
        List<Run> runs =
                new ArrayList<>();
        for (Variant variant : variants) {
            runs.add(execute(
                    scenario, variant));
        }

        // then
        SemanticProjection baseline = null;
        for (Run run : runs) {
            assertLocalityAndCheckpoint(run);
            SemanticProjection projection =
                    SemanticProjection.of(run);
            if (baseline == null) {
                baseline = projection;
            } else {
                assertEquals(
                        baseline,
                        projection,
                        "semantic drift for "
                                + run.variant);
            }
        }

        assertNotNull(baseline);
        assertEquals(ProcessorStatus.SUCCESS, baseline.status);
        assertEquals("processed", baseline.rootValue);
        assertEquals(8, variants.size());
        System.out.println(providerDemandEvidence(
                scenario,
                runs));
    }

    private static String providerDemandEvidence(
            Scenario scenario,
            List<Run> runs) {
        int total = 0;
        int selectedBodyDemands = 0;
        for (Run run : runs) {
            total += run.providerRequests.size();
            selectedBodyDemands += frequency(
                    run.providerRequests,
                    scenario.selectedBodyBlueId);
        }
        return "coordination.providerDemands={"
                + "\"schema\":\"blue.coordination/"
                + "provider-demands/1.0\","
                + "\"total\":" + total
                + ",\"forbidden\":0,"
                + "\"variants\":" + runs.size()
                + ",\"selectedBodyDemands\":"
                + selectedBodyDemands
                + ",\"forbiddenIdentities\":"
                + scenario.forbiddenBlueIds.size()
                + "}";
    }

    private static Run execute(
            Scenario scenario,
            Variant variant) {
        StrictFragmentProvider fragments =
                new StrictFragmentProvider(
                        scenario.allowedFragments,
                        scenario.forbiddenFragments);
        if (variant.warm) {
            fragments.warmAllowed();
        }

        BlueRuntimeTypeRegistry runtimeTypes =
                BlueRuntimeTypeRegistry.getDefault();
        CountingMockHandlerProcessor handlers =
                new CountingMockHandlerProcessor();
        ContractProcessorRegistry registry =
                ContractProcessorRegistryBuilder.create()
                        .registerDefaults()
                        .register(
                                MockTypeBlueIds.MOCK_EXTERNAL_CHANNEL,
                                runtimeTypes.node(
                                        RuntimeTypeKey
                                                .SCRIPTED_EXTERNAL_CHANNEL),
                                new FragmentAwareMockExternalChannelProcessor())
                        .register(
                                MockTypeBlueIds.MOCK_HANDLER,
                                runtimeTypes.node(
                                        RuntimeTypeKey.SCRIPTED_HANDLER),
                                handlers)
                        .build();
        NodeProvider nodeProvider = new SequentialNodeProvider(
                runtimeTypes.asProvider(),
                registry.exactTypeProvider(),
                fragments);
        try (BlueLanguage language = BlueLanguage.builder()
                     .nodeProvider(nodeProvider)
                     .build();
             BlueContracts contracts = BlueContracts.builder(
                             language.processing())
                     .runtimeRegistry(registry)
                     .build();
             DocumentProcessor processor = DocumentProcessor.builder()
                     .runtimeAccess(contracts.runtimeAccess())
                     .runtimeRegistry(registry)
                     .gasSchedule(GasSchedule.contracts10())
                     .runtimeRegistryIdentity(
                             "blue.coordination/test/fragment-matrix/1")
                     .deliveryPlanDeriver(
                             (root, event) -> scenario.plan)
                     .build()) {
            fragments.resetRequests();
            ProcessingDebugResult debug =
                    processor.processDocumentWithTrace(
                            variant.document(scenario),
                            variant.event(scenario));
            Node exactResultDocument = exactResultDocument(
                    debug.processResult().document(),
                    fragments);
            return new Run(
                    variant,
                    scenario,
                    debug,
                    exactResultDocument,
                    fragments.requests(),
                    handlers.executions());
        }
    }

    private static Node exactResultDocument(
            Node publicResult,
            StrictFragmentProvider fragments) {
        if (!publicResult.isReferenceOnly()) {
            return publicResult.clone();
        }
        List<Node> candidates = fragments.fetchByBlueId(
                publicResult.getBlueId());
        if (candidates == null || candidates.size() != 1) {
            throw new AssertionError(
                    "Result reference did not have one exact provider value: "
                            + publicResult.getBlueId());
        }
        return candidates.get(0).clone();
    }

    private static void assertLocalityAndCheckpoint(
            Run run) {
        String context = run.variant.toString();
        DocumentProcessingResult result =
                run.debug.processResult();
        Node publicResultDocument =
                result.document();
        Node semanticResultDocument =
                run.exactResultDocument;
        Node canonicalResultDocument =
                run.exactResultDocument;
        String resultingRootBlueId =
                DirectBlueIdCalculator.calculateBlueId(
                        canonicalResultDocument);
        String publicResultBlueId =
                DirectBlueIdCalculator.calculateBlueId(
                        publicResultDocument);
        boolean canonicalPublicProjection =
                resultingRootBlueId.equals(
                        publicResultBlueId);
        boolean exactHandlerAndEventEffects =
                run.handlerExecutions == 1
                        && Collections.singletonList(
                                run.scenario.emittedEventBlueId)
                        .equals(nodeBlueIds(
                                result.events()));

        assertTrue(
                canonicalPublicProjection,
                context + ": public result must expose the canonical "
                        + "resulting Root");
        assertTrue(
                exactHandlerAndEventEffects,
                context + ": selected Handler effects must be exact");
        assertEquals(
                ProcessorStatus.SUCCESS,
                result.status(),
                context + ": "
                        + diagnosticProjection(
                        result.diagnostic()));
        assertEquals(
                "processed",
                textAt(semanticResultDocument, "state"),
                "Language pure-reference Root transition defect: "
                        + context + ": resolved state="
                        + semanticResultDocument
                        .getProperties().get("state")
                        + ", handlerExecutions="
                        + run.handlerExecutions);
        assertEquals(
                resultingRootBlueId,
                DirectBlueIdCalculator.calculateBlueId(
                        publicResultDocument),
                context + ": public ProcessResult must project "
                        + "the resulting canonical Root identity");
        assertEquals(
                1,
                run.handlerExecutions,
                context + ": exactly one Handler must execute");
        assertEquals(
                run.variant.documentForm
                        == DocumentForm.INLINE
                        ? 0
                        : 1,
                frequency(
                        run.providerRequests,
                        run.scenario.selectedBodyBlueId),
                context + ": selected executable-body demand");
        assertTrue(
                Collections.disjoint(
                        run.providerRequests,
                        run.scenario.forbiddenBlueIds),
                context + ": forbidden demand "
                        + run.providerRequests);
        assertFalse(
                run.providerRequests.contains(
                        run.scenario.archiveBlueId),
                context + ": large unrelated archive was fetched");

        assertEquals(
                Collections.singletonList(
                        run.scenario.emittedEventBlueId),
                nodeBlueIds(result.events()),
                context + ": Root event drift");
        assertEquals(
                1,
                run.debug.trace()
                        .records(
                                ProcessingTraceRecord.Kind
                                        .CHECKPOINT_WRITE)
                        .size(),
                context + ": checkpoint write count");
        assertEquals(
                SELECTED_CHANNEL,
                run.debug.trace()
                        .records(
                                ProcessingTraceRecord.Kind
                                        .CHECKPOINT_WRITE)
                        .get(0)
                        .contractKey(),
                context + ": checkpoint source ownership");

        Node checkpoint = canonicalResultDocument
                .getContracts()
                .getProperties()
                .get("checkpoint");
        assertNotNull(checkpoint, context);
        Node entries = checkpoint.getProperties()
                .get("entries");
        assertNotNull(entries, context);
        Node selected = entries.getProperties()
                .get(SELECTED_CHANNEL);
        assertNotNull(selected, context);
        assertEquals(
                run.scenario.selectedCheckpointDomain,
                selected.getProperties()
                        .get("domain")
                        .getBlueId(),
                context);
        assertEquals(
                run.scenario.eventBlueId,
                DirectBlueIdCalculator.calculateBlueId(
                        selected.getProperties()
                                .get("subject")),
                context);
        assertNull(
                entries.getProperties()
                        .get(REJECTED_CHANNEL),
                context + ": rejected source acquired a checkpoint");
    }

    private enum DocumentForm {
        INLINE,
        PURE_REFERENCE,
        DIRECT_FRAGMENT
    }

    private enum EventForm {
        INLINE,
        PURE_REFERENCE,
        DIRECT_FRAGMENT
    }

    private static final class Variant {
        private final String label;
        private final DocumentForm documentForm;
        private final EventForm eventForm;
        private final boolean warm;

        private Variant(
                String label,
                DocumentForm documentForm,
                EventForm eventForm,
                boolean warm) {
            this.label = label;
            this.documentForm = documentForm;
            this.eventForm = eventForm;
            this.warm = warm;
        }

        private static List<Variant> matrix() {
            return Arrays.asList(
                    new Variant(
                            "A inline/inline/cold",
                            DocumentForm.INLINE,
                            EventForm.INLINE,
                            false),
                    new Variant(
                            "B Root-ref/inline/cold",
                            DocumentForm.PURE_REFERENCE,
                            EventForm.INLINE,
                            false),
                    new Variant(
                            "C inline/Event-ref/cold",
                            DocumentForm.INLINE,
                            EventForm.PURE_REFERENCE,
                            false),
                    new Variant(
                            "D Root-ref/Event-ref/cold",
                            DocumentForm.PURE_REFERENCE,
                            EventForm.PURE_REFERENCE,
                            false),
                    new Variant(
                            "E direct/direct/cold",
                            DocumentForm.DIRECT_FRAGMENT,
                            EventForm.DIRECT_FRAGMENT,
                            false),
                    new Variant(
                            "F Root-ref/Event-ref/warm",
                            DocumentForm.PURE_REFERENCE,
                            EventForm.PURE_REFERENCE,
                            true),
                    new Variant(
                            "G direct/Event-ref/cold",
                            DocumentForm.DIRECT_FRAGMENT,
                            EventForm.PURE_REFERENCE,
                            false),
                    new Variant(
                            "H Root-ref/direct/cold",
                            DocumentForm.PURE_REFERENCE,
                            EventForm.DIRECT_FRAGMENT,
                            false));
        }

        private Node document(
                Scenario scenario) {
            switch (documentForm) {
                case INLINE:
                    return scenario.inlineRoot.clone();
                case PURE_REFERENCE:
                    return new Node().blueId(
                            scenario.rootBlueId);
                case DIRECT_FRAGMENT:
                    return scenario.directRoot.clone();
                default:
                    throw new IllegalStateException(
                            "Unhandled document form");
            }
        }

        private Node event(
                Scenario scenario) {
            switch (eventForm) {
                case INLINE:
                    return scenario.inlineEvent.clone();
                case PURE_REFERENCE:
                    return new Node().blueId(
                            scenario.eventBlueId);
                case DIRECT_FRAGMENT:
                    return scenario.directEvent.clone();
                default:
                    throw new IllegalStateException(
                            "Unhandled event form");
            }
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private static final class Scenario {
        private final Node inlineRoot;
        private final Node directRoot;
        private final Node inlineEvent;
        private final Node directEvent;
        private final String rootBlueId;
        private final String eventBlueId;
        private final String selectedBodyBlueId;
        private final String archiveBlueId;
        private final String emittedEventBlueId;
        private final String selectedCheckpointDomain;
        private final Map<String, Node> allowedFragments;
        private final Map<String, Node> forbiddenFragments;
        private final Set<String> forbiddenBlueIds;
        private final ExternalDeliveryPlan plan;

        private Scenario(
                Node inlineRoot,
                Node directRoot,
                Node inlineEvent,
                Node directEvent,
                String rootBlueId,
                String eventBlueId,
                String selectedBodyBlueId,
                String archiveBlueId,
                String emittedEventBlueId,
                String selectedCheckpointDomain,
                Map<String, Node> allowedFragments,
                Map<String, Node> forbiddenFragments,
                ExternalDeliveryPlan plan) {
            this.inlineRoot = inlineRoot;
            this.directRoot = directRoot;
            this.inlineEvent = inlineEvent;
            this.directEvent = directEvent;
            this.rootBlueId = rootBlueId;
            this.eventBlueId = eventBlueId;
            this.selectedBodyBlueId =
                    selectedBodyBlueId;
            this.archiveBlueId = archiveBlueId;
            this.emittedEventBlueId =
                    emittedEventBlueId;
            this.selectedCheckpointDomain =
                    selectedCheckpointDomain;
            this.allowedFragments =
                    immutableNodes(allowedFragments);
            this.forbiddenFragments =
                    immutableNodes(forbiddenFragments);
            this.forbiddenBlueIds =
                    Collections.unmodifiableSet(
                            new LinkedHashSet<>(
                                    forbiddenFragments.keySet()));
            this.plan = plan;
        }

        private static Scenario create() {
            Node emitted = new Node()
                    .properties(
                            "kind",
                            scalar("matrix-result"))
                    .properties(
                            "id",
                            scalar("result-1"));
            String emittedEventBlueId =
                    DirectBlueIdCalculator.calculateBlueId(
                            emitted);
            Node selectedBody = new Node()
                    .properties(
                            "patches",
                            list(new Node()
                                    .properties(
                                            "op",
                                            scalar("replace"))
                                    .properties(
                                            "path",
                                            scalar("/state"))
                                    .properties(
                                            "val",
                                            scalar("processed"))))
                    .properties(
                            "events",
                            list(emitted));
            String selectedBodyBlueId =
                    DirectBlueIdCalculator.calculateBlueId(
                            selectedBody);

            List<Node> unselectedBodies =
                    new ArrayList<>();
            for (int index = 0; index < 4; index++) {
                unselectedBodies.add(
                        largeBody(
                                "unselected-" + index,
                                (char) ('a' + index)));
            }

            Node archive = new Node()
                    .properties(
                            "kind",
                            scalar("unrelated-archive"))
                    .properties(
                            "payload",
                            scalar(padding(
                                    LARGE_VALUE_SIZE * 3,
                                    'z')));
            String archiveBlueId =
                    DirectBlueIdCalculator.calculateBlueId(
                            archive);

            Node contracts = new Node()
                    .properties(
                            "initialized",
                            new Node()
                                    .type(reference(
                                            RuntimeBlueIds
                                                    .PROCESSING_INITIALIZED_MARKER))
                                    .properties(
                                            "document",
                                            scalar(
                                                    "coordination-fragment-matrix")));
            Node selectedChannel = channel(
                    0, true, CHECKPOINT_DISCRIMINATOR);
            Node rejectedChannel = channel(
                    1, false, "rejected-domain");
            contracts.properties(
                    SELECTED_CHANNEL,
                    selectedChannel);
            contracts.properties(
                    REJECTED_CHANNEL,
                    rejectedChannel);
            contracts.properties(
                    SELECTED_HANDLER,
                    handler(
                            SELECTED_CHANNEL,
                            0,
                            null,
                            selectedBody));
            for (int index = 0; index < 4; index++) {
                contracts.properties(
                        "unselectedWorkflow" + index,
                        handler(
                                REJECTED_CHANNEL,
                                index + 1,
                                "never-" + index,
                                unselectedBodies.get(index)));
            }

            Node inlineRoot = new Node()
                    .properties(
                            "state",
                            scalar("pending"))
                    .properties(
                            "archive",
                            archive.clone())
                    .contracts(contracts);
            String rootBlueId =
                    DirectBlueIdCalculator.calculateBlueId(
                            inlineRoot);

            CoordinationDocumentSplitter.SplitGraph document;
            document =
                    CoordinationFragmentationCatalogHarness
                            .splitter(
                                    inlineRoot,
                                    Collections.singletonMap(
                                            MockTypeBlueIds
                                                    .MOCK_HANDLER,
                                            Collections.singletonList(
                                                    "result")),
                                    Collections.singletonMap(
                                            MockTypeBlueIds
                                                    .MOCK_EXTERNAL_CHANNEL,
                                            EffectiveContractSnapshotConstants
                                                    .Role
                                                    .EXTERNAL_CHANNEL))
                            .splitDocument(inlineRoot);
            assertEquals(rootBlueId, document.rootBlueId());
            assertEquals(
                    5,
                    bodyFragmentCount(
                            document.metadata()),
                    "all five Handler bodies must be independently retained");

            Node directRoot =
                    document.processingRootView();
            directRoot.getProperties().put(
                    "archive",
                    reference(archiveBlueId));
            assertEquals(
                    rootBlueId,
                    DirectBlueIdCalculator.calculateBlueId(
                            directRoot),
                    "unrelated archive cut must preserve the Root BlueId");

            Node eventMessage = new Node()
                    .properties(
                            "kind",
                            scalar("unrelated-event-message"))
                    .properties(
                            "payload",
                            scalar(padding(
                                    LARGE_VALUE_SIZE,
                                    'm')));
            Node inlineEvent = new Node()
                    .properties(
                            "subscriptionKey",
                            scalar(SUBSCRIPTION_KEY))
                    .properties(
                            "kind",
                            scalar("selected"))
                    .properties(
                            "id",
                            scalar("fragment-event-1"))
                    .properties(
                            "message",
                            eventMessage);
            String eventBlueId =
                    DirectBlueIdCalculator.calculateBlueId(
                            inlineEvent);
            CoordinationDocumentSplitter splitter =
                    CoordinationDocumentSplitter
                            .forEventSplitting();
            CoordinationDocumentSplitter.SplitGraph event =
                    splitter.splitEvent(inlineEvent);
            CoordinationDocumentSplitter.SplitGraph message =
                    splitter.splitEvent(eventMessage);
            assertEquals(eventBlueId, event.rootBlueId());

            Map<String, Node> forbidden =
                    new LinkedHashMap<>();
            forbidden.put(
                    archiveBlueId,
                    archive.clone());
            for (Node unselectedBody : unselectedBodies) {
                putExact(
                        forbidden,
                        unselectedBody);
            }
            forbidden.putAll(
                    message.fragments());

            Map<String, Node> allowed =
                    new LinkedHashMap<>(
                            processingFragments(
                                    document));
            allowed.putAll(
                    event.fragments());
            for (String forbiddenBlueId :
                    forbidden.keySet()) {
                allowed.remove(forbiddenBlueId);
            }
            assertTrue(
                    allowed.containsKey(
                            selectedBodyBlueId),
                    "selected body must remain provider-available");

            String selectedContribution =
                    DirectBlueIdCalculator.calculateBlueId(
                            selectedChannel);
            String rejectedContribution =
                    DirectBlueIdCalculator.calculateBlueId(
                            rejectedChannel);
            String selectedDomain =
                    CheckpointDomain.derive(
                            MockTypeBlueIds
                                    .MOCK_EXTERNAL_CHANNEL,
                            Collections.singletonList(
                                    selectedContribution),
                            CHECKPOINT_DISCRIMINATOR);
            String rejectedDomain =
                    CheckpointDomain.derive(
                            MockTypeBlueIds
                                    .MOCK_EXTERNAL_CHANNEL,
                            Collections.singletonList(
                                    rejectedContribution),
                            "rejected-domain");
            ExternalDeliveryPlan plan =
                    ExternalDeliveryPlan.builder()
                            .revisions(41L, 41L)
                            .eventOrderKey(EVENT_ORDER)
                            .delivery(delivery(
                                    SELECTED_CHANNEL,
                                    0,
                                    selectedContribution,
                                    selectedDomain,
                                    eventBlueId))
                            .delivery(delivery(
                                    REJECTED_CHANNEL,
                                    1,
                                    rejectedContribution,
                                    rejectedDomain,
                                    eventBlueId))
                            .activeSubscriptionInterval(
                                    active(
                                            SELECTED_CHANNEL,
                                            0,
                                            selectedContribution,
                                            selectedDomain))
                            .activeSubscriptionInterval(
                                    active(
                                            REJECTED_CHANNEL,
                                            1,
                                            rejectedContribution,
                                            rejectedDomain))
                            .exactRuntimeState()
                            .build();

            return new Scenario(
                    inlineRoot,
                    directRoot,
                    inlineEvent,
                    event.fragmentedRoot(),
                    rootBlueId,
                    eventBlueId,
                    selectedBodyBlueId,
                    archiveBlueId,
                    emittedEventBlueId,
                    selectedDomain,
                    allowed,
                    forbidden,
                    plan);
        }
    }

    private static Map<String, Node> processingFragments(
            CoordinationDocumentSplitter.SplitGraph graph) {
        Map<String, Node> result =
                new LinkedHashMap<>();
        for (String blueId
                : graph.fragments().keySet()) {
            List<Node> provided =
                    graph.provider()
                            .fetchByBlueId(
                                    blueId);
            assertNotNull(
                    provided,
                    "PROCESS provider omitted "
                            + blueId);
            assertEquals(
                    1,
                    provided.size(),
                    "PROCESS provider returned ambiguous content for "
                            + blueId);
            assertEquals(
                    blueId,
                    DirectBlueIdCalculator.calculateBlueId(
                            provided.get(0)));
            result.put(
                    blueId,
                    provided.get(0).clone());
        }
        return result;
    }

    private static final class CountingMockHandlerProcessor
            implements HandlerProcessor<MockHandler> {
        private final AtomicInteger executions =
                new AtomicInteger();

        @Override
        public Class<MockHandler> contractType() {
            return MockHandler.class;
        }

        @Override
        public List<String> executableBodyFields() {
            return Collections.singletonList("result");
        }

        @Override
        public boolean matches(
                MockHandler contract,
                HandlerMatchContext context) {
            return context.matchesEventPattern(
                    contract.getEvent());
        }

        @Override
        public void execute(
                MockHandler contract,
                ProcessorExecutionContext context) {
            executions.incrementAndGet();
            Node result = contract.getResult();
            Node patches = property(result, "patches");
            if (patches != null && patches.getItems() != null) {
                for (Node patch : patches.getItems()) {
                    applyPatch(context, patch);
                }
            }
            Node events = property(result, "events");
            if (events != null && events.getItems() != null) {
                for (Node event : events.getItems()) {
                    context.emitEvent(event);
                }
            }
        }

        private static void applyPatch(
                ProcessorExecutionContext context,
                Node patch) {
            String operation = textAt(patch, "op");
            String path = textAt(patch, "path");
            Node value = property(patch, "val");
            if ("add".equals(operation)) {
                context.applyPatch(JsonPatch.add(path, value.clone()));
            } else if ("replace".equals(operation)) {
                context.applyPatch(JsonPatch.replace(path, value.clone()));
            } else if ("remove".equals(operation)) {
                context.applyPatch(JsonPatch.remove(path));
            } else {
                throw new IllegalArgumentException(
                        "Unsupported scripted patch operation: "
                                + operation);
            }
        }

        private static Node property(Node node, String key) {
            return node != null && node.getProperties() != null
                    ? node.getProperties().get(key)
                    : null;
        }

        private int executions() {
            return executions.get();
        }
    }

    private static final class MockTypeBlueIds {
        private static final String MOCK_EXTERNAL_CHANNEL =
                RuntimeBlueIds.SCRIPTED_EXTERNAL_CHANNEL;
        private static final String MOCK_HANDLER =
                RuntimeBlueIds.SCRIPTED_HANDLER;

        private MockTypeBlueIds() {
        }
    }

    @TypeBlueId(MockTypeBlueIds.MOCK_HANDLER)
    public static final class MockHandler
            extends HandlerContract {
        private Node result;

        public MockHandler() {
        }

        public Node getResult() {
            return result;
        }

        public void setResult(Node result) {
            this.result = result;
        }
    }

    @TypeBlueId(MockTypeBlueIds.MOCK_EXTERNAL_CHANNEL)
    public static final class MockExternalChannel
            extends ChannelContract {
        private String subscriptionKey;
        private Boolean accept;
        private String checkpointDomain;

        public MockExternalChannel() {
        }

        public String getSubscriptionKey() {
            return subscriptionKey;
        }

        public void setSubscriptionKey(
                String subscriptionKey) {
            this.subscriptionKey = subscriptionKey;
        }

        public Boolean getAccept() {
            return accept;
        }

        public void setAccept(Boolean accept) {
            this.accept = accept;
        }

        public String getCheckpointDomain() {
            return checkpointDomain;
        }

        public void setCheckpointDomain(
                String checkpointDomain) {
            this.checkpointDomain = checkpointDomain;
        }
    }

    /**
     * The published fixture's legacy evaluate method reads the event as an
     * already expanded object. This adapter keeps its immutable subscription
     * functions and makes the occurrence evaluation representation-blind; the
     * verified plan has already performed exact key preselection.
     */
    private static final class FragmentAwareMockExternalChannelProcessor
            implements ChannelProcessor<MockExternalChannel> {
        private final ExternalChannelSubscriptionFunctions<
                MockExternalChannel> subscriptions =
                new ExternalChannelSubscriptionFunctions<
                        MockExternalChannel>() {
                    @Override
                    public List<String> channelKeys(
                            MockExternalChannel contract) {
                        return Collections.singletonList(
                                contract.getSubscriptionKey());
                    }

                    @Override
                    public boolean accepts(
                            MockExternalChannel contract,
                            Node exactEvent) {
                        /*
                         * This method is the compatibility projection used
                         * after the revision-complete plan has already
                         * selected the exact occurrence.
                         */
                        return !Boolean.FALSE.equals(
                                contract.getAccept());
                    }

                    @Override
                    public boolean accepts(
                            MockExternalChannel contract,
                            Node exactEvent,
                            blue.language.processor
                                    .ExternalChannelFunctionContext
                                    context) {
                        return !Boolean.FALSE.equals(
                                contract.getAccept())
                                && preselects(
                                        contract,
                                        exactEvent,
                                        context);
                    }

                    @Override
                    public String checkpointDomainDiscriminator(
                            MockExternalChannel contract) {
                        return contract
                                .getCheckpointDomain();
                    }
                };

        @Override
        public Class<MockExternalChannel> contractType() {
            return MockExternalChannel.class;
        }

        @Override
        public ExternalChannelSubscriptionFunctions<
                MockExternalChannel> externalSubscriptionFunctions() {
            return subscriptions;
        }

        @Override
        public ChannelEvaluation evaluate(
                MockExternalChannel contract,
                ChannelEvaluationContext context) {
            return Boolean.FALSE.equals(
                    contract.getAccept())
                    ? ChannelEvaluation.noMatch()
                    : ChannelEvaluation.match(
                            context.event(), null);
        }
    }

    private static final class StrictFragmentProvider
            implements NodeProvider {
        private final Map<String, Node> allowed;
        private final Map<String, Node> forbidden;
        private final Map<String, Node> cache =
                new LinkedHashMap<>();
        private final List<String> requests =
                new ArrayList<>();

        private StrictFragmentProvider(
                Map<String, Node> allowed,
                Map<String, Node> forbidden) {
            this.allowed =
                    new LinkedHashMap<>(allowed);
            this.forbidden =
                    new LinkedHashMap<>(forbidden);
        }

        @Override
        public synchronized List<Node> fetchByBlueId(
                String blueId) {
            if (forbidden.containsKey(blueId)) {
                throw new AssertionError(
                        "PROCESS demanded forbidden fragment "
                                + blueId);
            }
            Node exact = allowed.get(blueId);
            if (exact == null) {
                throw new AssertionError(
                        "PROCESS escaped the exact-fragment "
                                + "allow-list: " + blueId);
            }
            requests.add(blueId);
            Node cached = cache.get(blueId);
            if (cached == null) {
                cached = exact.clone();
                cache.put(blueId, cached);
            }
            return Collections.singletonList(
                    cached.clone());
        }

        private synchronized void warmAllowed() {
            for (Map.Entry<String, Node> entry :
                    allowed.entrySet()) {
                cache.put(
                        entry.getKey(),
                        entry.getValue().clone());
            }
        }

        private synchronized void resetRequests() {
            requests.clear();
        }

        private synchronized List<String> requests() {
            return Collections.unmodifiableList(
                    new ArrayList<>(requests));
        }
    }

    private static final class Run {
        private final Variant variant;
        private final Scenario scenario;
        private final ProcessingDebugResult debug;
        private final Node exactResultDocument;
        private final List<String> providerRequests;
        private final int handlerExecutions;

        private Run(
                Variant variant,
                Scenario scenario,
                ProcessingDebugResult debug,
                Node exactResultDocument,
                List<String> providerRequests,
                int handlerExecutions) {
            this.variant = variant;
            this.scenario = scenario;
            this.debug = debug;
            this.exactResultDocument = exactResultDocument;
            this.providerRequests = providerRequests;
            this.handlerExecutions =
                    handlerExecutions;
        }
    }

    private static final class SemanticProjection {
        private final ProcessorStatus status;
        private final String rootValue;
        private final String resultingRootBlueId;
        private final List<String> rootEventBlueIds;
        private final String diagnostic;
        private final long totalGas;
        private final List<String> gas;
        private final List<String> trace;
        private final String checkpointBlueId;

        private SemanticProjection(
                ProcessorStatus status,
                String rootValue,
                String resultingRootBlueId,
                List<String> rootEventBlueIds,
                String diagnostic,
                long totalGas,
                List<String> gas,
                List<String> trace,
                String checkpointBlueId) {
            this.status = status;
            this.rootValue = rootValue;
            this.resultingRootBlueId =
                    resultingRootBlueId;
            this.rootEventBlueIds =
                    rootEventBlueIds;
            this.diagnostic = diagnostic;
            this.totalGas = totalGas;
            this.gas = gas;
            this.trace = trace;
            this.checkpointBlueId =
                    checkpointBlueId;
        }

        private static SemanticProjection of(Run run) {
            ProcessingDebugResult debug = run.debug;
            DocumentProcessingResult result =
                    debug.processResult();
            Node semanticResultDocument =
                    run.exactResultDocument;
            Node checkpoint =
                    run.exactResultDocument
                    .getContracts()
                    .getProperties()
                    .get("checkpoint");
            return new SemanticProjection(
                    result.status(),
                    textAt(
                            semanticResultDocument,
                            "state"),
                    DirectBlueIdCalculator.calculateBlueId(
                            run.exactResultDocument),
                    nodeBlueIds(result.events()),
                    diagnosticProjection(
                            result.diagnostic()),
                    result.totalGas(),
                    gasProjection(debug.trace()),
                    traceProjection(debug.trace()),
                    DirectBlueIdCalculator.calculateBlueId(
                            checkpoint));
        }

        @Override
        public boolean equals(
                Object other) {
            if (!(other
                    instanceof SemanticProjection)) {
                return false;
            }
            SemanticProjection that =
                    (SemanticProjection) other;
            return status == that.status
                    && totalGas == that.totalGas
                    && Objects.equals(
                    rootValue, that.rootValue)
                    && resultingRootBlueId.equals(
                    that.resultingRootBlueId)
                    && rootEventBlueIds.equals(
                    that.rootEventBlueIds)
                    && Objects.equals(
                    diagnostic, that.diagnostic)
                    && gas.equals(that.gas)
                    && trace.equals(that.trace)
                    && checkpointBlueId.equals(
                    that.checkpointBlueId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(
                    status,
                    rootValue,
                    resultingRootBlueId,
                    rootEventBlueIds,
                    diagnostic,
                    totalGas,
                    gas,
                    trace,
                    checkpointBlueId);
        }

        @Override
        public String toString() {
            return "SemanticProjection{"
                    + "status=" + status
                    + ", rootValue=" + rootValue
                    + ", rootBlueId="
                    + resultingRootBlueId
                    + ", events="
                    + rootEventBlueIds
                    + ", totalGas="
                    + totalGas
                    + '}';
        }
    }

    private static List<String> gasProjection(
            ProcessingConformanceTrace trace) {
        List<String> projection =
                new ArrayList<>();
        for (GasTraceEntry entry : trace.gas()) {
            projection.add(
                    entry.sequence()
                            + "|" + entry.namespace()
                            + "|" + entry.counter()
                            + "|" + entry.quantity()
                            + "|" + entry.weight()
                            + "|" + entry.subtotal()
                            + "|" + entry.scopePath()
                            + "|" + entry.contractKey()
                            + "|" + entry.logicalPath()
                            + "|" + entry.reason());
        }
        return Collections.unmodifiableList(
                projection);
    }

    private static List<String> traceProjection(
            ProcessingConformanceTrace trace) {
        List<String> projection =
                new ArrayList<>();
        for (ProcessingTraceRecord record :
                trace.records()) {
            Node node = record.node();
            projection.add(
                    record.sequence()
                            + "|" + record.kind()
                            + "|" + record.scopePath()
                            + "|" + record.contractKey()
                            + "|" + record.logicalPath()
                            + "|" + record.details()
                            + "|" + (node != null
                            ? DirectBlueIdCalculator
                            .calculateBlueId(node)
                            : null));
        }
        return Collections.unmodifiableList(
                projection);
    }

    private static Node channel(
            int order,
            boolean accept,
            String domain) {
        return new Node()
                .type(reference(
                        MockTypeBlueIds
                                .MOCK_EXTERNAL_CHANNEL))
                .properties(
                        "order",
                        scalar(order))
                .properties(
                        "subscriptionKey",
                        scalar(SUBSCRIPTION_KEY))
                .properties(
                        "eventKey",
                        scalar(SUBSCRIPTION_KEY))
                .properties(
                        "accept",
                        scalar(accept))
                .properties(
                        "checkpointDomain",
                        scalar(domain));
    }

    private static Node handler(
            String channel,
            int order,
            String eventKind,
            Node body) {
        Node handler = new Node()
                .type(reference(
                        MockTypeBlueIds.MOCK_HANDLER))
                .properties(
                        "channel",
                        scalar(channel))
                .properties(
                        "order",
                        scalar(order))
                .properties(
                        "result",
                        body.clone());
        if (eventKind != null) {
            handler.properties(
                    "event",
                    new Node().properties(
                            "kind",
                            scalar(eventKind)));
        }
        return handler;
    }

    private static Node largeBody(
            String tag,
            char padding) {
        return new Node()
                .properties(
                        "patches",
                        new Node().items(
                                Collections
                                        .<Node>emptyList()))
                .properties(
                        "events",
                        new Node().items(
                                Collections
                                        .<Node>emptyList()))
                .properties(
                        "tag",
                        scalar(tag))
                .properties(
                        "payload",
                        scalar(padding(
                                LARGE_VALUE_SIZE,
                                padding)));
    }

    private static ExternalDeliverySnapshot delivery(
            String channel,
            int order,
            String contribution,
            String domain,
            String eventBlueId) {
        return ExternalDeliverySnapshot.builder(
                        "/", channel)
                .order(order)
                .sourceContribution(contribution)
                .effectiveTypeBlueId(
                        MockTypeBlueIds
                                .MOCK_EXTERNAL_CHANNEL)
                .subscriptionKey(
                        SUBSCRIPTION_KEY)
                .checkpointDomainBlueId(domain)
                .checkpointSubjectBlueId(
                        eventBlueId)
                .build();
    }

    private static SubscriptionDelta.Entry active(
            String channel,
            int order,
            String contribution,
            String domain) {
        return new SubscriptionDelta.Entry(
                "/",
                channel,
                MockTypeBlueIds.MOCK_EXTERNAL_CHANNEL,
                Collections.singletonList(
                        contribution),
                order,
                Collections.singletonList(
                        SUBSCRIPTION_KEY),
                domain,
                0L,
                null,
                null);
    }

    private static int bodyFragmentCount(
            List<CoordinationDocumentSplitter.FragmentMetadata>
                    metadata) {
        int count = 0;
        for (CoordinationDocumentSplitter.FragmentMetadata entry :
                metadata) {
            if (entry.kind()
                    == CoordinationDocumentSplitter
                    .FragmentKind.EXECUTABLE_BODY) {
                count++;
            }
        }
        return count;
    }

    private static Map<String, Node> immutableNodes(
            Map<String, Node> source) {
        Map<String, Node> result =
                new LinkedHashMap<>();
        for (Map.Entry<String, Node> entry :
                source.entrySet()) {
            result.put(
                    entry.getKey(),
                    entry.getValue().clone());
        }
        return Collections.unmodifiableMap(
                result);
    }

    private static String putExact(
            Map<String, Node> target,
            Node exact) {
        String blueId =
                DirectBlueIdCalculator.calculateBlueId(exact);
        target.put(blueId, exact.clone());
        return blueId;
    }

    private static Node list(
            Node... values) {
        return new Node().items(
                Arrays.asList(values));
    }

    private static Node scalar(
            Object value) {
        return new Node().value(value);
    }

    private static Node reference(
            String blueId) {
        return new Node().blueId(blueId);
    }

    private static String padding(
            int length,
            char value) {
        char[] values = new char[length];
        Arrays.fill(values, value);
        return new String(values);
    }

    private static String textAt(
            Node root,
            String property) {
        Node value = root != null
                && root.getProperties() != null
                ? root.getProperties().get(
                        property)
                : null;
        if (value != null
                && value.isReferenceOnly()
                && typedTextBlueId("processed")
                .equals(value.getBlueId())) {
            return "processed";
        }
        if (value != null
                && value.isReferenceOnly()
                && typedTextBlueId("pending")
                .equals(value.getBlueId())) {
            return "pending";
        }
        return value != null
                && value.getValue() != null
                ? String.valueOf(
                        value.getValue())
                : null;
    }

    private static String typedTextBlueId(String value) {
        return DirectBlueIdCalculator.calculateBlueId(
                scalar(value)
                        .type(new Node().blueId(
                                blue.language.model.wire.BlueLanguageConstants
                                        .TEXT_TYPE_BLUE_ID)));
    }

    private static List<String> nodeBlueIds(
            List<Node> nodes) {
        List<String> result =
                new ArrayList<>(nodes.size());
        for (Node node : nodes) {
            result.add(
                    DirectBlueIdCalculator.calculateBlueId(
                            node));
        }
        return Collections.unmodifiableList(
                result);
    }

    private static int frequency(
            List<String> values,
            String expected) {
        int count = 0;
        for (String value : values) {
            if (expected.equals(value)) {
                count++;
            }
        }
        return count;
    }

    private static String diagnosticProjection(
            ProcessorDiagnostic diagnostic) {
        return diagnostic == null
                ? null
                : diagnostic.category()
                + "|" + diagnostic.message()
                + "|" + diagnostic.details();
    }
}
