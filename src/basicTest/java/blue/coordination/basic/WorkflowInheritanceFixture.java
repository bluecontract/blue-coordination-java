package blue.coordination.basic;

import blue.coordination.basic.engine.BasicCoordinationEngine;
import blue.coordination.basic.engine.ExactNodeValue;

import java.util.Objects;

/** Builds exact type documents for the inherited-workflow scaling proof. */
final class WorkflowInheritanceFixture {
    private WorkflowInheritanceFixture() {
    }

    static RegisteredHierarchy registerOneWorkflow(
            BasicCoordinationEngine engine) {
        ExactNodeValue type = engine.registerType(typeYaml(
                "One Workflow Type",
                null,
                0,
                false));
        return new RegisteredHierarchy(
                type,
                flatInstanceYaml("workflow-one", 0),
                null,
                1);
    }

    static RegisteredHierarchy registerThreeByTwenty(
            BasicCoordinationEngine engine) {
        ExactNodeValue base = engine.registerType(typeYaml(
                "Workflow Base 20",
                null,
                20,
                false));
        ExactNodeValue middle = engine.registerType(typeYaml(
                "Workflow Middle 20",
                base.blueId(),
                20,
                false));
        ExactNodeValue top = engine.registerType(typeYaml(
                "Workflow Top 20",
                middle.blueId(),
                20,
                false));
        return new RegisteredHierarchy(
                top,
                flatInstanceYaml("workflow-sixty", 60),
                null,
                61);
    }

    private static String typeYaml(
            String name,
            String parentBlueId,
            int unrelatedWorkflows,
            boolean defineChannelAndSelected) {
        StringBuilder yaml = new StringBuilder();
        yaml.append("name: ").append(name).append('\n');
        if (parentBlueId != null) {
            yaml.append("type: {blueId: ")
                    .append(parentBlueId)
                    .append("}\n");
        }
        yaml.append(!defineChannelAndSelected && unrelatedWorkflows == 0
                ? "contracts: {}\n"
                : "contracts:\n");
        if (defineChannelAndSelected) {
            yaml.append("  benchmarkChannel:\n")
                    .append("    type: Coordination/Timeline Channel\n")
                    .append("    timeline:\n")
                    .append("      type: MyOS/MyOS Timeline\n")
                    .append("      timelineId: examples/workflow-scale/alice\n")
                    .append("    actor:\n")
                    .append("      type: MyOS/Principal Actor\n")
                    .append("      accountId: alice\n")
                    .append("  selected:\n")
                    .append("    type: Coordination/Sequential Workflow Operation\n")
                    .append("    channel: benchmarkChannel\n")
                    .append("    request: {}\n")
                    .append("    steps:\n")
                    .append("      - type: Coordination/Compute\n")
                    .append("        do:\n")
                    .append("          - $appendChange:\n")
                    .append("              op: replace\n")
                    .append("              path: /counter\n")
                    .append("              val: {$add: [$document: /counter, 1]}\n")
                    .append("          - $return: true\n");
        }
        appendUnrelatedWorkflows(yaml, name, unrelatedWorkflows);
        return yaml.toString();
    }

    private static void appendUnrelatedWorkflows(
            StringBuilder yaml,
            String name,
            int unrelatedWorkflows) {
        String prefix = name.replaceAll("[^A-Za-z0-9]", "_").toLowerCase();
        for (int index = 0; index < unrelatedWorkflows; index++) {
            yaml.append("  ")
                    .append(prefix)
                    .append('_')
                    .append(index)
                    .append(":\n")
                    .append("    type: Coordination/Sequential Workflow Operation\n")
                    .append("    channel: unrelatedChannel\n")
                    .append("    request:\n")
                    .append("      ignored: {type: Integer}\n")
                    .append("    steps:\n")
                    .append("      - type: Coordination/Compute\n")
                    .append("        do:\n")
                    .append("          - $return: true\n");
        }
    }

    private static String flatInstanceYaml(
            String documentId,
            int unrelatedWorkflows) {
        StringBuilder yaml = new StringBuilder();
        yaml.append("documentId: ")
                .append(Objects.requireNonNull(documentId, "documentId"))
                .append("\ncounter: 0\n")
                .append("contracts:\n")
                .append(selectedContractsYaml(
                        "examples/workflow-scale/alice"));
        int remaining = unrelatedWorkflows;
        for (String layer : new String[]{
                "Workflow Base 20",
                "Workflow Middle 20",
                "Workflow Top 20"}) {
            int atLayer = Math.min(20, remaining);
            appendUnrelatedWorkflows(yaml, layer, atLayer);
            remaining -= atLayer;
        }
        return yaml.toString();
    }

    private static String selectedContractsYaml(String timelineId) {
        return """
                  benchmarkChannel:
                    type: Coordination/Timeline Channel
                    timeline:
                      type: MyOS/MyOS Timeline
                      timelineId: %s
                    actor:
                      type: MyOS/Principal Actor
                      accountId: alice
                  unrelatedChannel:
                    type: Coordination/Timeline Channel
                    timeline:
                      type: MyOS/MyOS Timeline
                      timelineId: %s/unrelated
                    actor:
                      type: MyOS/Principal Actor
                      accountId: alice
                  selected:
                    type: Coordination/Sequential Workflow Operation
                    channel: benchmarkChannel
                    request: {}
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendChange:
                              op: replace
                              path: /counter
                              val: {$add: [$document: /counter, 1]}
                          - $return: true
                """.formatted(
                Objects.requireNonNull(timelineId, "timelineId"),
                timelineId);
    }

    record RegisteredHierarchy(
            ExactNodeValue topType,
            String instanceYaml,
            String inheritanceProbeYaml,
            int effectiveOperationCount) {
        RegisteredHierarchy {
            Objects.requireNonNull(topType, "topType");
            Objects.requireNonNull(instanceYaml, "instanceYaml");
            if (effectiveOperationCount < 1) {
                throw new IllegalArgumentException(
                        "effectiveOperationCount must be positive");
            }
        }
    }
}
