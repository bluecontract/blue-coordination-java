package blue.coordination.sdk;

import blue.coordination.api.DocumentInstanceRef;
import blue.coordination.api.DocumentInstanceRetirement;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** A witnessed historical interval belongs to its original source after independent replacement. */
final class LogicalRetainedInstanceApplicationTest {
    @Test void completeOldSourceReceiptCanApplyWithoutExecutingTheReplacementSource() {
        // given
        var fixture = attachedInterval(); var account = fixture.account();
        var source = fixture.source(); var sourceHead = fixture.sourceHead(); var consumer = fixture.consumer();
        // when
        var retired = account.transact(scope -> scope.retireInstance(source.instance()));
        assertEquals(DocumentInstanceRetirement.Status.PREPARED, retired.status(), retired.blockers().toString());
        var replacement = new DocumentInstanceRef(source.instance().documentId(), "retained-source-replacement");
        account.transact(scope -> scope.startInstance(replacement, source));
        blue.coordination.api.storage.CoordinationRecords.Publication applicationPacket;
        try (var attempt = account.records.attempt(); var scope = account.open(attempt)) {
            var b = scope.documentHandle(consumer.instance().documentId()).orElseThrow();
            var application = scope.coordination().processing().processNextStage(b);
            assertEquals(1, application.managedEpochApplications().size());
            assertEquals(1, application.managedEpochApplicationAttempts().get(0).work().sourceEpoch());
            assertEquals(1, ((Number) scope.coordination().advanced().auditDocument(b.id()).current().copyNode().get("/seen")).intValue());
            scope.stage();
            applicationPacket = attempt.prepare("retained-only-application", List.of(), LogicalInstanceHistoryTest.Account.EVIDENCE);
            assertNoReplacementAuthority(applicationPacket, replacement);
        }
        account.transact(scope -> {
            var a = scope.documentHandle(replacement.documentId()).orElseThrow();
            assertEquals(0, a.snapshot().longAt("/counter"));
            assertEquals(1, scope.coordination().processing().processNextStage(a).entries().size());
            return scope.instancePosition(a.id());
        });
        // then
        assertTrue(account.records.publish(applicationPacket), "Independent A2 advancement must not invalidate old A1 application");
        blue.coordination.api.storage.CoordinationRecords.Publication terminalPacket;
        try (var attempt = account.records.attempt(); var scope = account.open(attempt)) {
            var b = scope.documentHandle(consumer.instance().documentId()).orElseThrow();
            assertEquals(1, ((Number) scope.coordination().advanced().auditDocument(b.id()).current().copyNode().get("/seen")).intValue());
            var last = scope.coordination().processing().processNextStage(b);
            assertEquals(1, last.managedEpochApplications().size(), last.diagnostic().toString());
            assertEquals(2, b.snapshot().longAt("/seen"));
            assertEquals(blue.coordination.api.ManagedCatchUpStatus.COMPLETE,
                    scope.coordination().advanced().auditManagedCatchUpPlans(b.id()).get(0).status());
            scope.stage(); terminalPacket = attempt.prepare("terminal-retained-only", List.of(), LogicalInstanceHistoryTest.Account.EVIDENCE);
            assertNoReplacementAuthority(terminalPacket, replacement);
        }
        var advancedA2 = account.transact(scope -> {
            scope.coordination().processing().processNextStage(scope.documentHandle(replacement.documentId()).orElseThrow());
            return scope.instancePosition(replacement.documentId());
        });
        assertTrue(account.records.publish(terminalPacket), "Terminal historical application must remain independent of A2");
        var finishedB = account.transact(scope -> {
            var b = scope.documentHandle(consumer.instance().documentId()).orElseThrow();
            assertEquals(2, b.snapshot().longAt("/seen"));
            assertEquals(advancedA2, scope.instancePosition(replacement.documentId()));
            assertEquals(2, ((Number) scope.retainedRevision(sourceHead).after().scalarAt("/counter")).intValue());
            return scope.instancePosition(b.id());
        });
        account.transact(scope -> {
            var a = scope.documentHandle(replacement.documentId()).orElseThrow();
            var next = account.append(scope, a, "rcp2/source", "owner", "setCounter", "counterValue: 9");
            assertTrue(scope.coordination().processing().processNextStage(a).entry(next).applied());
            assertEquals(9, a.snapshot().longAt("/counter"));
            assertEquals(finishedB, scope.instancePosition(consumer.instance().documentId()), "A2 work cannot update B's original A1 occurrence");
            return null;
        });
        try (var attempt = account.records.attempt(); var scope = account.open(attempt)) {
            assertEquals(DocumentInstanceRetirement.Status.PREPARED, scope.retireInstance(replacement).status());
            scope.stage(); var prepared = attempt.prepare("before-occurrence-role-write", List.of(), LogicalInstanceHistoryTest.Account.EVIDENCE);
            var association = prepared.points().stream().filter(point ->
                    point.key().family() == blue.coordination.api.storage.CoordinationRecords.Family.INSTANCE_OCCURRENCE
                            && point.expected().present()).findFirst().orElseThrow();
            // Even a same-bytes native role rewrite must conflict while Boolean incoming membership stays unchanged.
            var original = account.records.data.get(association.key());
            account.records.data.put(association.key(), new blue.coordination.api.storage.CoordinationRecords.Value(
                    original.revision() + 1, original.content()));
            assertFalse(account.records.publish(prepared));
        }
        var retiredReplacement = account.transact(scope -> scope.retireInstance(replacement));
        assertEquals(DocumentInstanceRetirement.Status.PREPARED, retiredReplacement.status(),
                "B retains A1, not A2: " + retiredReplacement.blockers());
        var third = new DocumentInstanceRef(source.instance().documentId(), "third-source-instance");
        var startThird = account.transact(scope -> scope.startInstance(third, source));
        assertEquals(blue.coordination.api.DocumentInstanceStart.Status.PREPARED, startThird.status(), startThird.blockers().toString());
        account.transact(scope -> {
            assertEquals(third, scope.instancePosition(third.documentId()).instance());
            assertEquals(finishedB, scope.instancePosition(consumer.instance().documentId()));
            return null;
        });
        assertEquals(DocumentInstanceRetirement.Status.PREPARED, account.transact(scope -> scope.retireInstance(third)).status());
        account.transact(scope -> {
            assertTrue(scope.documentHandle(replacement.documentId()).isEmpty());
            var b = scope.documentHandle(consumer.instance().documentId()).orElseThrow();
            var detach = scope.coordination().operations().on(b).from(scope.timelineHandle("rcp2/parent").orElseThrow())
                    .call("detach").through("owner").requestYaml("{}").submit();
            var detached = scope.coordination().processing().processStage(b, detach);
            assertTrue(detached.entry(detach).applied(), detached.diagnostic() + " " + detached.entry(detach).diagnostic());
            assertFalse(scope.coordination().advanced().auditManagedOccurrence(b.id(), "/child").orElseThrow().active());
            assertNull(blue.language.model.NodePathEditor.getOrNull(scope.coordination().advanced().auditDocument(b.id()).current().copyNode(), "/child"));
            return null;
        });
    }

    private void assertNoReplacementAuthority(blue.coordination.api.storage.CoordinationRecords.Publication packet,
            DocumentInstanceRef replacement) {
        assertTrue(packet.points().stream().noneMatch(point ->
                (point.key().family() == blue.coordination.api.storage.CoordinationRecords.Family.INSTANCE_BINDING
                        || point.key().family() == blue.coordination.api.storage.CoordinationRecords.Family.SESSION)
                        && new String(point.key().key().copy(), java.nio.charset.StandardCharsets.UTF_8)
                                .equals(replacement.documentId().value())), "Retained application must not select A2's binding or head");
    }

    @Test void unprocessedEarlierAcceptedInputStillBlocksSourceRetirement() {
        // given
        var fixture = attachedInterval(); var account = fixture.account();
        // when
        account.transact(scope -> {
            var a = scope.documentHandle(fixture.source().instance().documentId()).orElseThrow();
            account.append(scope, a, "rcp2/source", "owner", "tick", "{}");
            return null;
        });
        // then
        try (var attempt = account.records.attempt(); var scope = account.open(attempt)) {
            var blocked = scope.retireInstance(fixture.source().instance());
            assertEquals(DocumentInstanceRetirement.Status.BLOCKED_POLICY_REQUIRED, blocked.status());
            assertTrue(blocked.blockers().stream().anyMatch(reason -> reason.startsWith("UNRESOLVED_PLAN")));
            scope.stage();
            assertTrue(attempt.prepare("unresolved-source-prefix", List.of(), LogicalInstanceHistoryTest.Account.EVIDENCE).mutations().isEmpty());
        }
    }

    @Test void missingOriginalNumberedRevisionPoisonsHistoricalApplication() {
        // given
        var fixture = attachedInterval(); var account = fixture.account();
        var originalReceipt = account.transact(scope -> scope.coordination().advanced()
                .auditManagedEpoch(fixture.source().instance().documentId(), 1).orElseThrow().receiptIdentity());
        account.transact(scope -> scope.retireInstance(fixture.source().instance()));
        account.transact(scope -> scope.startInstance(new DocumentInstanceRef(fixture.source().instance().documentId(), "fault-replacement"), fixture.source()));
        byte[] marker = "blue-coordination/document-revision-storage/2".getBytes(java.nio.charset.StandardCharsets.UTF_16BE);
        byte[] receipt = originalReceipt.getBytes(java.nio.charset.StandardCharsets.UTF_16BE);
        var missing = account.objects.values.entrySet().stream().filter(row -> contains(row.getValue(), marker)
                && contains(row.getValue(), receipt)).map(java.util.Map.Entry::getKey).toList();
        assertFalse(missing.isEmpty(), "Fault must select actual original numbered revision bytes");
        // when
        missing.forEach(account.objects.values::remove);
        // then
        try (var attempt = account.records.attempt(); var scope = account.open(attempt)) {
            var b = scope.documentHandle(fixture.consumer().instance().documentId()).orElseThrow();
            assertThrows(RuntimeException.class, () -> scope.coordination().processing().processNextStage(b));
            assertThrows(RuntimeException.class, scope::stage);
            assertThrows(IllegalStateException.class, () -> attempt.prepare("failed-original-history", List.of(), LogicalInstanceHistoryTest.Account.EVIDENCE));
        }
    }
    private static boolean contains(byte[] bytes, byte[] needle) {
        outer: for (int start = 0; start <= bytes.length - needle.length; start++) {
            for (int n = 0; n < needle.length; n++) if (bytes[start + n] != needle[n]) continue outer;
            return true;
        }
        return false;
    }

    private record Fixture(LogicalInstanceHistoryTest.Account account, blue.coordination.api.DocumentInstancePosition source,
            blue.coordination.api.DocumentInstancePosition sourceHead, blue.coordination.api.DocumentInstancePosition consumer) { }
    private Fixture attachedInterval() {
        var account = new LogicalInstanceHistoryTest.Account();
        var source = account.transact(scope -> {
            scope.coordination().timelines().register("rcp2/source", "alice");
            var a = scope.coordination().documents().admitStaticProcessEmbedded(resource("source.yaml"), ActivationPolicy.importFullHistory()).document("root");
            account.exact.put(a.snapshot().blueId(), a.exact().json());
            return scope.instancePosition(a.id());
        });
        var sourceHead = account.transact(scope -> {
            var a = scope.documentHandle(source.instance().documentId()).orElseThrow();
            for (int i = 0; i < 2; i++) {
                var tick = account.append(scope, a, "rcp2/source", "owner", "tick", "{}");
                assertTrue(scope.coordination().processing().processNextStage(a).entry(tick).applied());
            }
            return scope.instancePosition(a.id());
        });
        var consumer = account.transact(scope -> {
            scope.coordination().timelines().register("rcp2/parent", "alice");
            var b = scope.coordination().documents().admitStaticProcessEmbedded(resource("parent.yaml") + """
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
                """, ActivationPolicy.importFullHistory()).document("root");
            var old = scope.retainedRepresentation(source).blueId();
            var attach = scope.coordination().operations().on(b).from(scope.timelineHandle("rcp2/parent").orElseThrow())
                    .call("attach").through("owner").requestYaml("child: {blueId: " + old + "}")
                    .selectManagedEpoch(ManagedEpochSelector.exact(source.instance().documentId(), 0, old, "/child")).submit();
            var result = scope.coordination().processing().processStage(b, attach);
            assertTrue(result.entry(attach).applied(), result.diagnostic().toString());
            assertFalse(scope.coordination().advanced().auditManagedOccurrence(b.id(), "/child").orElseThrow().active());
            return scope.instancePosition(b.id());
        });
        return new Fixture(account, source, sourceHead, consumer);
    }

    private static String resource(String name) {
        try { return RootedSdkFixture.resource(name); }
        catch (java.io.IOException failure) { throw new IllegalStateException(failure); }
    }
}
