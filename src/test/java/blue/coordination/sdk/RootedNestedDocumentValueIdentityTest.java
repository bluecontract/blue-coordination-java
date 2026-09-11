package blue.coordination.sdk;

import blue.coordination.internal.CoordinationTestControl;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** A typed exact document carried by $document must keep canonical identity during retained work. */
final class RootedNestedDocumentValueIdentityTest {
    private static final String TIMELINE = "retained/nested-existing/alice";

    @Test
    void laterWorkflowStepCopiesCurrentCanonicalValueAndReadsResolvedType() {
        // given
        try (var f = new RootedSdkFixture()) {
            var a = f.startYaml("""
                    name: Canonical document preview
                    stored:
                      type: {name: Original typed value}
                      marker: before
                    contracts:
                      ownerChannel:
                        type: Coordination/Timeline Channel
                        timeline:
                          type: MyOS/MyOS Timeline
                          timelineId: retained/nested-existing/alice
                        actor:
                          type: MyOS/Principal Actor
                          accountId: alice
                      replaceAndCopy:
                        type: Coordination/Sequential Workflow Operation
                        channel: ownerChannel
                        request: {replacement: {}}
                        steps:
                          - type: Coordination/Compute
                            do:
                              - $appendChange:
                                  op: replace
                                  path: /stored
                                  val: {$binding: event/message/request/replacement}
                              - $return: true
                          - type: Coordination/Compute
                            do:
                              - $appendChange:
                                  op: add
                                  path: /copied
                                  val: {$document: /stored}
                              - $appendChange:
                                  op: add
                                  path: /seen
                                  val: {$document: /stored/marker}
                              - $appendChange:
                                  op: add
                                  path: /typeName
                                  val: {$document: /stored/type/name}
                              - $return: true
                    """, TIMELINE);
            var before = a.snapshot();
            var replacement = f.blue.values().yaml("""
                    type: {name: Replacement typed value}
                    marker: after
                    """);
            assertNotEquals(before.valueAt("/stored").blueId(), replacement.blueId());

            // when
            var result = f.blue.operations().on(a).from(f.timelines.get(TIMELINE))
                    .call("replaceAndCopy").through("ownerChannel")
                    .request(request -> request.exact("replacement", replacement)).execute();

            // then
            assertEquals(EntryDisposition.APPLIED, result.disposition(), result.diagnostic().toString());
            assertEquals(replacement.blueId(), a.snapshot().valueAt("/stored").blueId());
            assertEquals(replacement.blueId(), a.snapshot().valueAt("/copied").blueId());
            assertEquals(replacement.json(), a.snapshot().valueAt("/copied").json());
            assertEquals("after", a.snapshot().textAt("/seen"));
            assertEquals("Replacement typed value", a.snapshot().textAt("/typeName"));
            assertEquals("before", before.textAt("/stored/marker"));
            assertEquals(2, a.history().size());
            String finalHead = a.snapshot().blueId();
            CoordinationTestControl.attach(f.blue.advanced().rawEngine()).restartFromStores();
            assertEquals(finalHead, a.snapshot().blueId());
        }
    }

    @Test
    void retainedBEventCarriesInlineC0WithoutInventingAResolvedContentDemand() throws Exception {
        // given
        try (var f = new RootedSdkFixture()) {
            var b = f.startYaml(RootedSdkFixture.resource("canonical-document-view-source.yaml"), TIMELINE);
            var c = f.startYaml(RootedSdkFixture.resource("canonical-document-view-source.yaml")
                    .replace("Nested existing source B", "Nested existing source C"), TIMELINE);
            var bZero = b.snapshot().exact();
            var cZero = c.snapshot().exact();
            increment(f, b);
            increment(f, c);
            var a = f.startYaml(RootedSdkFixture.resource("canonical-document-view-consumer.yaml"), TIMELINE);
            var sourceHeads = List.of(b.snapshot().blueId(), c.snapshot().blueId());
            var sourceHistories = List.of(f.history(b), f.history(c));
            var entry = f.blue.operations().on(a).from(f.timelines.get(TIMELINE))
                    .call("attachBAndRetainC").through("ownerChannel")
                    .request(request -> request.exact("candidateC", cZero).exact("b", bZero))
                    .selectManagedEpoch(ManagedEpochSelector.exact(b.id(), 0L, bZero.blueId(), "/children/b"))
                    .submit();
            var attached = f.blue.processing().processNext(a).entry(entry);
            assertEquals(EntryDisposition.APPLIED, attached.disposition(), attached.diagnostic().toString());
            var committed = ExactBlueValue.wrap(f.blue.advanced().auditDocument(a.id()).current());
            assertEquals(cZero.blueId(), committed.valueAt("/candidateC").blueId());
            var originalPlan = f.blue.advanced().auditManagedCatchUpPlans(a.id()).get(0);

            // when
            var appliedB = f.blue.processing().processNext(a);

            // then
            assertEquals(1, appliedB.managedEpochApplications().size(),
                    () -> "The inline canonical C0 is already exact input: " + appliedB.managedEpochApplicationAttempts());
            assertTrue(appliedB.managedEpochApplicationAttempts().get(0).attempt().isComplete());
            assertTrue(appliedB.managedEpochApplicationAttempts().get(0).attempt().requiredExactBlueIds().isEmpty());
            var cPlan = f.blue.advanced().auditManagedCatchUpPlans(a.id()).stream()
                    .filter(plan -> plan.sourceDocumentId().equals(c.id())).findFirst().orElseThrow();
            assertEquals(cZero.blueId(), cPlan.admittedSourceBlueId());
            assertEquals(0L, cPlan.admittedSourceEpoch());
            assertEquals("/children/c", cPlan.targetPath());
            assertEquals(originalPlan.barrierIdentity(), cPlan.barrierIdentity());
            assertEquals(sourceHeads, List.of(b.snapshot().blueId(), c.snapshot().blueId()));
            assertEquals(sourceHistories, List.of(f.history(b), f.history(c)));

            var appliedC = f.blue.processing().processNext(a);
            assertEquals(1, appliedC.managedEpochApplications().size(), appliedC.managedEpochApplicationAttempts().toString());
            assertTrue(appliedC.quiescent());
            assertEquals(1L, a.snapshot().longAt("/bChanges"));
            assertEquals(1L, a.snapshot().longAt("/cChanges"));
            assertEquals(c.snapshot().blueId(), a.snapshot().valueAt("/children/c").blueId());
            assertEquals(cZero.blueId(), a.snapshot().valueAt("/candidateC").blueId());
            assertEquals(sourceHeads, List.of(b.snapshot().blueId(), c.snapshot().blueId()));
            assertEquals(sourceHistories, List.of(f.history(b), f.history(c)));
            var finalHead = a.snapshot().blueId();
            var finalHistory = f.history(a);
            CoordinationTestControl.attach(f.blue.advanced().rawEngine()).restartFromStores();
            assertEquals(finalHead, a.snapshot().blueId());
            assertEquals(finalHistory, f.history(a));
            assertTrue(f.blue.processing().processNext(a).quiescent());
        }
    }

    private static void increment(RootedSdkFixture f, DocumentHandle document) {
        var entry = f.blue.operations().on(document).from(f.timelines.get(TIMELINE))
                .call("increment").through("ownerChannel").requestYaml("{}").submit();
        var result = f.blue.processing().processNext(document).entry(entry);
        assertEquals(EntryDisposition.APPLIED, result.disposition(), result.diagnostic().toString());
        f.retain(document);
    }
}
