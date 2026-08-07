package blue.coordination.examples.support;

import blue.coordination.engine.api.LocalityDiagnostics;
import blue.language.model.Node;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Focused business and locality assertions shared by the example tests. */
public final class MyOsDemoAssertions {

    private MyOsDemoAssertions() {
    }

    public static void assertValue(
            MyOsDemoRuntime runtime,
            String documentKey,
            String path,
            Object expected) {
        Object actual = runtime.value(documentKey, path);
        if (expected instanceof Integer integer) {
            assertEquals(BigInteger.valueOf(integer.longValue()), actual, path);
            return;
        }
        if (expected instanceof Long number) {
            assertEquals(BigInteger.valueOf(number), actual, path);
            return;
        }
        assertEquals(expected, actual, path);
    }

    public static void assertSuccessful(MyOsDemoResult result) {
        var process = result.delivery().transition().platformResult()
                .processResult();
        assertTrue(
                process.commits(),
                () -> "Expected committed PROCESS result but got "
                        + result.delivery().transition().status().wireValue()
                        + (process.diagnostic() == null
                        ? ""
                        : ": " + process.diagnostic().category()
                        + " - " + process.diagnostic().message()
                        + " " + process.diagnostic().details())
                        + "; requested=" + result.delivery().transition()
                        .locality().requestedBlueIds()
                        + "; loaded=" + result.delivery().transition()
                        .locality().backendLoadedBlueIds()
                        + "; forbidden=" + result.delivery().transition()
                        .locality().forbiddenReadCount());
        assertTrue(result.delivery().commitOutcome().committed());
        assertLocality(result);
    }

    public static void assertLocality(MyOsDemoResult result) {
        LocalityDiagnostics locality = result.delivery().transition().locality();
        assertEquals(0, locality.forbiddenReadCount(), "forbidden reads");
        assertEquals(0, locality.fallbackReadCount(),
                () -> "fallback reads; required="
                        + result.delivery().transition().plan()
                        .requiredSeedBlueIds()
                        + "; preferred="
                        + result.delivery().transition().plan()
                        .preferredPrefetchBlueIds()
                        + "; causal=" + locality.causallySelectedBlueIds());
        assertFalse(locality.backendLoadedBlueIds().isEmpty(),
                "a real PROCESS path should load an exact request-local bundle");
    }

    public static void assertRootEventKind(
            MyOsDemoRuntime runtime,
            MyOsDemoResult result,
            String expectedKind) {
        List<Node> events = result.delivery().transition().platformResult()
                .processResult().events();
        assertTrue(events.stream().anyMatch(event ->
                        expectedKind.equals(runtime.value(event, "/kind"))),
                () -> "Missing Root event kind " + expectedKind);
    }


    public static void assertRootEventKinds(
            MyOsDemoRuntime runtime,
            MyOsDemoResult result,
            String... expectedKinds) {
        List<Node> events = result.delivery().transition().platformResult()
                .processResult().events();
        List<Object> actualKinds = events.stream()
                .map(event -> runtime.value(event, "/kind"))
                .toList();
        for (String expectedKind : expectedKinds) {
            assertTrue(actualKinds.contains(expectedKind),
                    () -> "Missing Root event kind " + expectedKind
                            + " in " + actualKinds);
        }
    }

    public static void assertExactRootEventKindsInOrder(
            MyOsDemoRuntime runtime,
            MyOsDemoResult result,
            String... expectedKinds) {
        List<Object> actualKinds = result.delivery().transition()
                .platformResult().processResult().events().stream()
                .map(event -> runtime.value(event, "/kind"))
                .toList();
        assertEquals(
                List.of(expectedKinds),
                actualKinds,
                "exact ordered public Root event kinds");
    }

    public static void assertNoRootEventKind(
            MyOsDemoRuntime runtime,
            MyOsDemoResult result,
            String unexpectedKind) {
        List<Node> events = result.delivery().transition().platformResult()
                .processResult().events();
        assertFalse(events.stream().anyMatch(event ->
                        unexpectedKind.equals(runtime.value(event, "/kind"))),
                () -> "Unexpected Root event kind " + unexpectedKind);
    }

    public static void assertSelectedScopes(
            MyOsDemoResult result,
            String... expectedScopePaths) {
        List<String> expected = List.of(expectedScopePaths);
        List<String> actual = new ArrayList<>(
                result.delivery().transition().plan().preparedDelivery()
                        .selectedScopeChainIdentities().keySet());
        assertEquals(expected, actual, "selected scope path order");
    }

    public static void assertStrictFragmentLocality(
            MyOsDemoRuntime runtime,
            String documentKey,
            MyOsDemoResult result) {
        Set<String> completeInventory = runtime.currentFragmentBlueIds(
                documentKey);
        Set<String> loadedDocumentFragments = new LinkedHashSet<>(
                result.delivery().transition().locality()
                        .backendLoadedBlueIds());
        loadedDocumentFragments.retainAll(completeInventory);
        assertTrue(
                loadedDocumentFragments.size() < completeInventory.size(),
                () -> "Expected fragment-local processing, but loaded "
                        + loadedDocumentFragments.size() + " of "
                        + completeInventory.size()
                        + " current fragments");
    }

    public static void assertDifferentRoots(
            MyOsDemoRuntime runtime,
            String first,
            String second) {
        assertNotEquals(
                runtime.currentRootBlueId(first),
                runtime.currentRootBlueId(second));
    }
}
