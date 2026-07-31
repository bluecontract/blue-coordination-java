package blue.coordination.processor.workflow;

import blue.coordination.processor.bex.BexProcessingMetrics;
import blue.language.model.Node;
import blue.language.processor.model.FrozenJsonPatch;
import blue.language.snapshot.FrozenNode;
import blue.language.utils.BlueIdCalculator;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StaticUpdatePlanTest {

    @Test
    void shouldCompileOrderedFrozenTemplatesWithoutRetainingMutableValues() {
        // Given
        Node value = new Node().properties("status", new Node().value("authored"));
        Node changeset = new Node().items(Arrays.asList(
                patch("add", "/added", value),
                patch("replace", "/replaced", new Node().value(2)),
                patch("remove", "/removed", null)));

        // When
        StaticUpdatePlan plan = StaticUpdatePlan.compile(FrozenNode.fromNode(changeset));
        value.getProperties().get("status").value("mutated");

        // Then
        assertTrue(plan.valid());
        assertEquals(3, plan.patches().size());
        FrozenJsonPatch first = plan.patches().get(0).bind("/scope/added");
        assertEquals(blue.language.processor.model.JsonPatch.Op.ADD, first.getOp());
        assertEquals("/scope/added", first.getPath());
        assertEquals("authored", first.getValue().getProperties().get("status").getValue());
        assertTrue(first.getValue().isStrictCanonical());
        assertTrue(plan.approximateWeightBytes() > 0L);
    }

    @Test
    void shouldEstimateRetainedExactValueWeightWithoutTraversingPayload() {
        // Given
        Node largeValue = new Node().value("leaf");
        for (int index = 0; index < 128; index++) {
            largeValue = new Node().properties("nested", largeValue);
        }

        // When
        StaticUpdatePlan small = compile(
                patch("add", "/value", new Node().value("small")));
        StaticUpdatePlan large = compile(
                patch("add", "/value", largeValue));

        // Then
        assertTrue(small.valid());
        assertTrue(large.valid());
        assertEquals(
                small.approximateWeightBytes(),
                large.approximateWeightBytes());
    }

    @Test
    void shouldCanonicalizeResolvedFallbackExactlyOnceDuringPlanCompilation() {
        // Given
        BexProcessingMetrics metrics = new BexProcessingMetrics();
        Node changeset = new Node().items(patch("add", "/value",
                new Node().properties("nested", new Node().value("authored"))));

        // When
        StaticUpdatePlan plan = StaticUpdatePlan.compile(
                FrozenNode.fromResolvedNode(changeset), metrics);
        FrozenJsonPatch first = plan.patches().get(0).bind("/scope/value");
        FrozenJsonPatch second = plan.patches().get(0).bind("/other/value");

        // Then
        assertTrue(first.getValue().isStrictCanonical());
        assertTrue(second.getValue().isStrictCanonical());
        assertEquals(first.getValue().blueId(), second.getValue().blueId());
        assertEquals(1L, metric(metrics, "staticUpdateResolvedValueCanonicalizations"));
    }

    @Test
    void shouldRemoveProviderProvenanceFromResolvedPatchValue() {
        // Given
        Node authoredValue = new Node().value(3);
        String valueBlueId =
                BlueIdCalculator.calculateBlueId(
                        authoredValue);
        Node resolvedValue =
                authoredValue.clone()
                        .blueId(valueBlueId);
        FrozenNode resolvedChangeset =
                FrozenNode.fromResolvedNode(
                        new Node().items(
                                patch(
                                        "replace",
                                        "/state",
                                        resolvedValue)));

        // When
        StaticUpdatePlan plan =
                StaticUpdatePlan.compile(
                        resolvedChangeset);
        FrozenNode exactValue =
                plan.patches().get(0)
                        .bind("/state")
                        .getValue();
        Node exactNode =
                exactValue.toNode();

        // Then
        assertTrue(plan.valid());
        assertTrue(exactValue.isStrictCanonical());
        assertEquals(valueBlueId, exactValue.blueId());
        assertFalse(
                exactNode.getBlueId() != null
                        && !exactNode.isReferenceOnly(),
                "runtime output must not combine provider provenance "
                        + "with expanded exact content");
    }

    @Test
    void shouldCompileExactRemoveOperationWithAbsentValue() {
        // Given
        BexProcessingMetrics metrics = new BexProcessingMetrics();

        // When
        StaticUpdatePlan exact = StaticUpdatePlan.compile(
                FrozenNode.fromResolvedNode(new Node().items(
                        patch("remove", "/removed", null))), metrics);

        // Then
        assertTrue(exact.valid());
        assertEquals(blue.language.processor.model.JsonPatch.Op.REMOVE,
                exact.patches().get(0).bind("/scope/removed").getOp());
        assertEquals(0L, metric(metrics, "staticUpdateResolvedValueCanonicalizations"));
    }

    @Test
    void shouldRejectRemoveOperationWithAuthoredValue() {
        // Given
        BexProcessingMetrics metrics = new BexProcessingMetrics();
        Node forbiddenResolvedValue = new Node()
                .blueId("GX7CFUmSDrE2MzptunLCCdZwnuwwrenRQqEnHL4x3uoC")
                .properties("expanded", new Node().value("forbidden"));

        // When
        StaticUpdatePlan withValue = StaticUpdatePlan.compile(
                FrozenNode.fromResolvedNode(new Node().items(
                        patch("remove", "/removed", forbiddenResolvedValue))), metrics);

        // Then
        assertEquals("Update Document patch value must be absent for remove",
                withValue.validationFailure());
        assertEquals(0L, metric(metrics, "staticUpdateResolvedValueCanonicalizations"));
    }

    @Test
    void shouldRejectNonCanonicalRemoveOperationText() {
        // Given
        BexProcessingMetrics metrics = new BexProcessingMetrics();

        // When
        StaticUpdatePlan nonCanonicalOp = StaticUpdatePlan.compile(
                FrozenNode.fromResolvedNode(new Node().items(
                        patch(" REMOVE ", "/removed", null))), metrics);

        // Then
        assertEquals("Unsupported Update Document patch operation:  REMOVE ",
                nonCanonicalOp.validationFailure());
        assertEquals(0L, metric(metrics, "staticUpdateResolvedValueCanonicalizations"));
    }

    @Test
    void shouldPreserveDollarPrefixedLiteralValues() {
        // Given
        Node patch = patch("replace", "/status",
                new Node().properties("$binding", new Node().value("event")));

        // When
        StaticUpdatePlan literal = compile(patch);

        // Then
        assertTrue(literal.valid());
        assertEquals("event",
                literal.patches().get(0).bind("/status")
                        .getValue().property("$binding").getValue());
    }

    @Test
    void shouldRejectReplaceOperationWithoutValue() {
        // Given
        Node patch = patch("replace", "/status", null);

        // When
        StaticUpdatePlan missingValue = compile(patch);

        // Then
        assertEquals("Update Document patch value is required for operation: replace",
                missingValue.validationFailure());
    }

    @Test
    void shouldRejectUnsupportedPatchOperation() {
        // Given
        Node patch = patch("move", "/status", new Node().value("x"));

        // When
        StaticUpdatePlan badOperation = compile(patch);

        // Then
        assertEquals("Unsupported Update Document patch operation: move",
                badOperation.validationFailure());
    }

    @Test
    void shouldRejectScalarChangesetEntry() {
        // Given
        FrozenNode changeset = FrozenNode.fromResolvedNode(
                new Node().items(new Node().value("not-a-patch")));

        // When
        StaticUpdatePlan scalarEntry = StaticUpdatePlan.compile(changeset);

        // Then
        assertEquals("Update Document changeset entry 0 must be a static patch object",
                scalarEntry.validationFailure());
    }

    @Test
    void shouldRejectNonTextPatchFieldsWithStableDiagnostic() {
        // Given
        Node nonText = new Node().properties("op", new Node().value(1))
                .properties("path", new Node().value("/status"))
                .properties("val", new Node().value("x"));

        // When
        StaticUpdatePlan badField = StaticUpdatePlan.compile(FrozenNode.fromResolvedNode(
                new Node().items(nonText)));

        // Then
        assertEquals("Update Document changeset entry 0 field 'op' must be text",
                badField.validationFailure());
    }

    @Test
    void shouldRejectNonListChangesetWithStableDiagnostic() {
        // Given
        FrozenNode changeset = FrozenNode.fromResolvedNode(
                new Node().properties("op", new Node().value("replace")));

        // When
        StaticUpdatePlan notList = StaticUpdatePlan.compile(changeset);

        // Then
        assertEquals("Update Document changeset must be a static patch list",
                notList.validationFailure());
    }

    private static StaticUpdatePlan compile(Node patch) {
        return StaticUpdatePlan.compile(FrozenNode.fromResolvedNode(new Node().items(patch)));
    }

    private static Node patch(String op, String path, Node value) {
        Node patch = new Node()
                .properties("op", new Node().value(op))
                .properties("path", new Node().value(path));
        if (value != null) {
            patch.properties("val", value);
        }
        return patch;
    }

    private static long metric(BexProcessingMetrics metrics, String name) {
        Long value = metrics.languageCounters().get(name);
        return value != null ? value.longValue() : 0L;
    }
}
