package blue.coordination.sdk;

import blue.coordination.api.DocumentInstanceRef;
import blue.coordination.api.DocumentInstanceRetirement;
import blue.coordination.api.DocumentInstanceStart;
import blue.coordination.api.storage.CoordinationRecords.Family;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Replacement preserves native catalog authority independently of semantic history. */
final class LogicalInstanceCatalogTest {
    @Test void detachedEmbeddedMemberDoesNotBecomeAPublicRootOnRestart() {
        // given
        boolean explicitlyPromoted = false;
        // when
        long publicRoots = replace(explicitlyPromoted);
        // then
        assertEquals(1, publicRoots);
    }
    @Test void explicitPromotionSurvivesRestartFromAPrePromotionBasis() {
        // given
        boolean explicitlyPromoted = true;
        // when
        long publicRoots = replace(explicitlyPromoted);
        // then
        assertEquals(2, publicRoots);
    }

    private long replace(boolean promote) {
        var account = new LogicalInstanceHistoryTest.Account();
        var positions = detached(account);
        var basis = positions.get(1);
        assertEquals(1, roots(account));
        if (promote) account.transact(scope -> scope.coordination().documents().promotePublicRoot(basis.instance().documentId()));
        assertEquals(promote ? 2 : 1, roots(account));
        var retired = account.transact(scope -> scope.retireInstance(basis.instance()));
        assertEquals(DocumentInstanceRetirement.Status.PREPARED, retired.status(), retired.blockers().toString());
        assertEquals(1, roots(account));
        var next = new DocumentInstanceRef(basis.instance().documentId(), "catalog-replacement");
        var started = account.transact(scope -> scope.startInstance(next, basis));
        assertEquals(DocumentInstanceStart.Status.PREPARED, started.status(), started.blockers().toString());
        assertEquals(promote ? 2 : 1, roots(account), "Restart must preserve the retired native catalog role");
        account.transact(scope -> {
            assertEquals(positions.get(0), scope.instancePosition(positions.get(0).instance().documentId()));
            assertEquals(next, scope.instancePosition(next.documentId()).instance());
            return null;
        });
        return roots(account);
    }
    @Test void concurrentPromotionConflictsWithPreparedRetirement() {
        // given
        var account = new LogicalInstanceHistoryTest.Account();
        var basis = detached(account).get(1);
        blue.coordination.api.storage.CoordinationRecords.Publication retiring;
        try (var attempt = account.records.attempt(); var scope = account.open(attempt)) {
            assertEquals(DocumentInstanceRetirement.Status.PREPARED, scope.retireInstance(basis.instance()).status());
            scope.stage();
            retiring = attempt.prepare("before-promotion", java.util.List.of(), LogicalInstanceHistoryTest.Account.EVIDENCE);
        }
        // when
        account.transact(scope -> scope.coordination().documents().promotePublicRoot(basis.instance().documentId()));
        // then
        assertFalse(account.records.publish(retiring), "Catalog-role selection must fence concurrent promotion");
        assertEquals(2, roots(account));
        assertEquals(basis, account.transact(scope -> scope.instancePosition(basis.instance().documentId())));
    }

    @Test void missingOrStaleCatalogAuthorityPoisonsRestart() {
        // given
        var account = new LogicalInstanceHistoryTest.Account();
        var basis = detached(account).get(1);
        account.transact(scope -> scope.retireInstance(basis.instance()));
        var key = account.records.data.keySet().stream().filter(candidate -> candidate.family() == Family.INSTANCE_IDENTITY
                && new String(candidate.scope().copy(), java.nio.charset.StandardCharsets.UTF_8).equals("blue-coordination/instance-catalog/1"))
                .findFirst().orElseThrow();
        // when
        var original = account.records.data.remove(key);
        // then
        assertInvalidRestart(account, basis, "missing-role");
        account.records.data.put(key, original);
        var second = new DocumentInstanceRef(basis.instance().documentId(), "second-catalog-instance");
        account.transact(scope -> scope.startInstance(second, basis));
        account.transact(scope -> scope.retireInstance(second));
        // Physical stale-row fault, preserving a well-formed but obsolete generation and instance.
        account.records.data.put(key, original);
        assertInvalidRestart(account, basis, "stale-role");
    }

    private void assertInvalidRestart(LogicalInstanceHistoryTest.Account account,
            blue.coordination.api.DocumentInstancePosition basis, String id) {
        try (var attempt = account.records.attempt(); var scope = account.open(attempt)) {
            assertThrows(RuntimeException.class, () -> scope.startInstance(new DocumentInstanceRef(basis.instance().documentId(), id), basis));
            assertThrows(RuntimeException.class, scope::stage);
            assertThrows(IllegalStateException.class, () -> attempt.prepare("bad-role", java.util.List.of(), LogicalInstanceHistoryTest.Account.EVIDENCE));
        }
    }

    private java.util.List<blue.coordination.api.DocumentInstancePosition> detached(LogicalInstanceHistoryTest.Account account) {
        return account.transact(scope -> {
            var blue = scope.coordination();
            blue.timelines().register("rcp2/parent", "alice");
            blue.timelines().register("rcp2/source", "alice");
            var parent = resource("parent.yaml") + """
                  detach:
                    type: Coordination/Sequential Workflow Operation
                    channel: owner
                    request: {}
                    steps:
                    - type: Coordination/Compute
                      do:
                      - $appendChange:
                          op: remove
                          path: /child
                      - $return: true
                """ + "\nchild:\n" + resource("source.yaml").indent(2);
            var closure = blue.documents().admitStaticProcessEmbedded(parent, ActivationPolicy.importFullHistory());
            var root = closure.document("root");
            var child = closure.documents().values().stream().filter(member -> !member.id().equals(root.id())).findFirst().orElseThrow();
            var detach = account.append(scope, root, "rcp2/parent", "owner", "detach", "{}");
            assertTrue(blue.processing().processStage(root, detach).entry(detach).applied());
            var tick = account.append(scope, child, "rcp2/source", "owner", "tick", "{}");
            assertTrue(blue.processing().processStage(child, tick).entry(tick).applied());
            return java.util.List.of(scope.instancePosition(root.id()), scope.instancePosition(child.id()));
        });
    }
    private long roots(LogicalInstanceHistoryTest.Account account) {
        return account.records.data.entrySet().stream().filter(row -> row.getKey().family() == Family.ACTIVE_ROOT && row.getValue().present()).count();
    }
    private static String resource(String name) {
        try { return RootedSdkFixture.resource(name); }
        catch (java.io.IOException failure) { throw new IllegalStateException(failure); }
    }
}
