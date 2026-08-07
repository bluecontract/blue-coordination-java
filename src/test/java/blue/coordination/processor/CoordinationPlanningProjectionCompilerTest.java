package blue.coordination.processor;

import blue.coordination.fastpath.AdmittedProjection;
import blue.coordination.fastpath.FastPathWorkMetrics;
import blue.coordination.fastpath.ProjectionGenerationKey;
import blue.language.model.Node;
import blue.language.processor.ExternalChannelDependencySnapshot;
import blue.language.processor.ExecutionEvidenceUnavailableException;
import blue.language.processor.ExternalOrderKey;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Reference-aware proof for the production admitted projection compiler. */
final class CoordinationPlanningProjectionCompilerTest {

    @Test
    void shouldCompileTheSameNestedScopeChainThroughAnExactReference() {
        // given
        Node nested = new Node().properties(
                "value", new Node().value("nested"));
        String nestedBlueId = blueId(nested);
        Node expandedRoot = new Node().properties(
                "nested", nested.clone());
        Node referencedRoot = new Node().properties(
                "nested", new Node().blueId(nestedBlueId));
        String rootBlueId = blueId(expandedRoot);
        assertEquals(rootBlueId, blueId(referencedRoot));
        CoordinationSubscriptionSnapshot snapshot = snapshot(
                rootBlueId, nestedBlueId);
        ProjectionGenerationKey generation = new ProjectionGenerationKey(
                "environment",
                "session",
                rootBlueId,
                snapshot.rootRevision(),
                "inventory",
                snapshot.digest(),
                "runtime-provider-generation");
        CoordinationPlanningProjectionCompiler compiler =
                new CoordinationPlanningProjectionCompiler(
                        new FastPathWorkMetrics());

        // when
        AdmittedProjection expanded = compiler.compileAdmitted(
                generation, snapshot, expandedRoot);
        AdmittedProjection referenced = compiler.compileAdmitted(
                generation,
                snapshot,
                referencedRoot,
                requested -> nestedBlueId.equals(requested)
                        ? Collections.singletonList(
                                directFragment(nested))
                        : Collections.<Node>emptyList());

        // then
        assertEquals(expanded.projectionIdentity(),
                referenced.projectionIdentity());
        assertEquals(
                Arrays.asList(rootBlueId, nestedBlueId),
                referenced.occurrences().get(0).scopeChainBlueIds());
    }

    @Test
    void shouldFailClosedWhenAReferencedScopeHasNoExactWinner() {
        // given
        Node nested = new Node().properties(
                "value", new Node().value("nested"));
        String nestedBlueId = blueId(nested);
        Node root = new Node().properties(
                "nested", new Node().blueId(nestedBlueId));
        String rootBlueId = blueId(root);
        CoordinationSubscriptionSnapshot snapshot = snapshot(
                rootBlueId, nestedBlueId);
        ProjectionGenerationKey generation = new ProjectionGenerationKey(
                "environment",
                "session",
                rootBlueId,
                snapshot.rootRevision(),
                "inventory",
                snapshot.digest(),
                "runtime-provider-generation");

        // when / then
        assertThrows(
                ExecutionEvidenceUnavailableException.class,
                () -> new CoordinationPlanningProjectionCompiler(
                        new FastPathWorkMetrics()).compileAdmitted(
                                generation,
                                snapshot,
                                root,
                                ignored -> Collections.<Node>emptyList()));
    }

    @Test
    void shouldPropagateAnUnexpectedProviderFailure() {
        // given
        Node nested = new Node().properties(
                "value", new Node().value("nested"));
        String nestedBlueId = blueId(nested);
        Node root = new Node().properties(
                "nested", new Node().blueId(nestedBlueId));
        String rootBlueId = blueId(root);
        CoordinationSubscriptionSnapshot snapshot = snapshot(
                rootBlueId, nestedBlueId);
        ProjectionGenerationKey generation = new ProjectionGenerationKey(
                "environment",
                "session",
                rootBlueId,
                snapshot.rootRevision(),
                "inventory",
                snapshot.digest(),
                "runtime-provider-generation");
        IllegalStateException unexpected = new IllegalStateException(
                "unexpected provider failure");

        // when
        IllegalStateException propagated = assertThrows(
                IllegalStateException.class,
                () -> new CoordinationPlanningProjectionCompiler(
                        new FastPathWorkMetrics()).compileAdmitted(
                                generation,
                                snapshot,
                                root,
                                ignored -> {
                                    throw unexpected;
                                }));

        // then
        assertSame(unexpected, propagated);
    }

    private static CoordinationSubscriptionSnapshot snapshot(
            String rootBlueId,
            String nestedBlueId) {
        ExternalOrderKey frontier = ExternalOrderKey.of(
                Arrays.<Object>asList(0L));
        CoordinationSubscriptionOccurrence occurrence =
                new CoordinationSubscriptionOccurrence(
                        "/nested",
                        nestedBlueId,
                        "/",
                        CoordinationSubscriptionOccurrence.Origin.EXPLICIT,
                        "/nested",
                        null,
                        null,
                        "channel",
                        Collections.singletonList("source-contribution"),
                        "effective-type",
                        0,
                        "checkpoint-domain",
                        "header-identity",
                        Collections.<String, String>emptyMap(),
                        Collections.singletonList("subscription-key"),
                        Long.valueOf(1L),
                        frontier,
                        null,
                        ExternalChannelDependencySnapshot.none());
        return new CoordinationSubscriptionSnapshot(
                "language-runtime",
                "coordination-runtime",
                rootBlueId,
                1L,
                frontier,
                Collections.singletonList(occurrence),
                Collections.<String, java.util.List<String>>emptyMap(),
                Collections.<String>emptySet());
    }

    private static String blueId(Node value) {
        return new CoordinationExactNodeIndex().blueId(value);
    }

    private static Node directFragment(Node value) {
        CoordinationExactNodeIndex index = new CoordinationExactNodeIndex();
        index.blueId(value);
        return index.directFragment(value);
    }
}
