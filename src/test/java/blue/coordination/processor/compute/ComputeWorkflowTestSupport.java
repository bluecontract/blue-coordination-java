package blue.coordination.processor.compute;

import blue.coordination.processor.CoordinationProcessorOptions;
import blue.coordination.processor.CoordinationTestRuntime;
import blue.coordination.processor.CoordinationTestResources;
import blue.language.provider.NodeProvider;
import blue.language.model.Node;
import blue.language.processor.DocumentProcessingResult;
import blue.repo.BlueRepository;

final class ComputeWorkflowTestSupport {
    private int timestamp = 1;

    final BlueRepository repository;
    final CoordinationTestRuntime blue;

    private ComputeWorkflowTestSupport(
            BlueRepository repository,
            CoordinationTestRuntime blue) {
        this.repository = repository;
        this.blue = blue;
    }

    static ComputeWorkflowTestSupport create() {
        return create(null);
    }

    static ComputeWorkflowTestSupport create(CoordinationProcessorOptions options) {
        return create(options, null);
    }

    static ComputeWorkflowTestSupport create(
            CoordinationProcessorOptions options,
            NodeProvider localProvider) {
        BlueRepository repository = BlueRepository.current();
        CoordinationTestRuntime blue =
                CoordinationTestResources.configuredBlue(repository);
        if (localProvider != null) {
            blue.addNodeProvider(localProvider);
        }
        if (options != null) {
            blue.configure(options);
        }
        return new ComputeWorkflowTestSupport(repository, blue);
    }

    Node yaml(String source) {
        Node node = blue.parseSourceYaml(source);
        return CoordinationTestResources
                .preprocessWithFixedRepository(
                        blue,
                        repository,
                        node);
    }

    Node yamlResource(String resourcePath) {
        return CoordinationTestResources.yamlResource(blue, repository, resourcePath);
    }

    DocumentProcessingResult initialize(Node document) {
        return blue.initializeDocument(
                CoordinationTestResources
                        .preprocessWithFixedRepository(
                                blue,
                                repository,
                                document));
    }

    DocumentProcessingResult process(Node snapshot, Node event) {
        return blue.processDocument(snapshot, event);
    }

    DocumentProcessingResult processRun(Node snapshot) {
        return processRun(snapshot, new Node().value("request"));
    }

    DocumentProcessingResult processRun(Node snapshot, Node request) {
        return process(snapshot, operationRequest("run", request));
    }

    Node operationRequest(String operation, Node request) {
        return operationRequest("owner", timestamp++, operation, "ownerChannel", request);
    }

    Node operationRequest(String operation, String channel, Node request) {
        return operationRequest("owner", timestamp++, operation, channel, request);
    }

    Node operationRequest(String timelineId,
                          int timestamp,
                          String operation,
                          String channel,
                          Node request) {
        return CoordinationTestResources.operationRequestEvent(blue,
                repository,
                timelineId,
                timestamp,
                operation,
                channel,
                request);
    }

    String operationWorkflowDocument(String body) {
        return operationWorkflowDocumentWithContracts("", body);
    }

    String operationWorkflowDocumentWithStatus(String rootFields, String body) {
        return String.join("\n",
                "name: Compute Workflow Test",
                "status: idle",
                rootFields,
                "contracts:",
                CoordinationTestResources.simpleTimelineChannelYaml("ownerChannel", "owner", 2),
                "  run:",
                "    type: Coordination/Sequential Workflow Operation",
                "    channel: ownerChannel",
                body);
    }

    String operationWorkflowDocumentWithContracts(String extraContracts, String body) {
        return String.join("\n",
                "name: Compute Workflow Test",
                "status: idle",
                "contracts:",
                CoordinationTestResources.simpleTimelineChannelYaml("ownerChannel", "owner", 2),
                "  run:",
                "    type: Coordination/Sequential Workflow Operation",
                "    channel: ownerChannel",
                body,
                extraContracts);
    }

    Node initializedOperationWorkflow(String body) {
        return initialize(yaml(operationWorkflowDocument(body))).document();
    }
}
