package blue.bex;

import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import blue.bex.result.BexExecutionResult;
import static blue.bex.test.BexTestFixtures.*;

/** Review-only actual interpreter probe: transient reference vs special schema field. */
public final class BexCyclicSchemaProbe {
    public static void main(String[] args) {
        String member = DirectBlueIdCalculator.calculateBlueId(new Node().value("nonexistent-set")) + "#0";
        Node reference = op("$objectSet", obj("object", op("$emptyObject", true), "key", "blueId", "val", member));
        for (String field : new String[]{"nested", "type", "schema"}) {
            Node value = op("$objectSet", obj("object", op("$emptyObject", true), "key", field, "val", reference));
            try {
                BexExecutionResult identity = runExpr(op("$nodeBlueId", value));
                BexExecutionResult emitted = runStep(stepDo(list(op("$appendEvent", value))), defaultContext());
                BexExecutionResult patch = runStep(stepDo(list(op("$appendChange", obj("op", "replace", "path", "/status", "val", value)))), defaultContext());
                System.out.println(field + " ACCEPTED identity=" + identity.value().asText()
                        + " events=" + emitted.events().events().size() + " patches=" + patch.changeset().entries().size()
                        + " inventedMember=" + member);
            } catch (RuntimeException failure) {
                System.out.println(field + " REJECTED " + failure.getClass().getName() + ": " + failure.getMessage());
            }
        }
    }
}
