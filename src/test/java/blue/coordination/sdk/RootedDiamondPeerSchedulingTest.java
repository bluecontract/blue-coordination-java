package blue.coordination.sdk;

import blue.coordination.api.ManagedCatchUpStatus;
import blue.coordination.api.ManagedOccurrenceCatchUpPlan;
import blue.coordination.api.ContractsExecutionPolicy;
import blue.coordination.internal.CoordinationTestControl;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Public scheduling only: no substituted input, acquisition probe, or joint-publication control. */
final class RootedDiamondPeerSchedulingTest {
    private static final List<String> NAMES = List.of("A", "B", "D", "C");
    private static final String ORIGINAL_NAMESPACE = "witness-forwarding";

    @Test
    void theSameExactDiamondRetainsItsHistoryAndGasAcrossPermittedSchedules() throws Exception {
        // given
        var canonical = run(ORIGINAL_NAMESPACE, false);
        // when
        var reversedDirectCalls = run(ORIGINAL_NAMESPACE, true);
        // then
        assertEquals(canonical, reversedDirectCalls,
                "Physical call order must not change settled histories, retained logical gas, or aggregate call gas");
    }

    @Test
    void reversedContentDerivedPeerOrderKeepsSemanticsWithoutACrossIdByteEqualityClaim() throws Exception {
        // given
        String namespace = namespaceWithReversedPeerOrder();
        // when
        var permuted = run(namespace, false);
        // then
        assertNotEquals("5G1qitMzxCuQ3UpCuJyh2fZimmbdRNwsthMYJUWZBjVL", permuted.entryBlueId());
        assertTrue(permuted.documentIds().get("B").compareTo(permuted.documentIds().get("D")) < 0);
        // Changing authored IDs is a different exact case, not a cache or physical-schedule variant.
        // run() checks the same origin-labelled counts, frozen anchor and publication invariants.
    }

    @Test
    void anEffectFreeDiamondStillSettlesWithoutUsingAnEmittedTokenAsJoinProof() throws Exception {
        // given
        boolean emitTokens = false;
        // when
        var outcome = run(ORIGINAL_NAMESPACE, false, emitTokens);
        // then
        assertTrue(outcome.aggregateCallGas() > 0L);
    }

    @Test
    void oneGasOriginalFailureIsComparedAfterBothRealSchedulesFinish() throws Exception {
        // given
        var policy = ContractsExecutionPolicy.exactSharedGas(1L, "diamond-preflight-tight");
        // when
        var early = runTightGas(true, policy);
        var late = runTightGas(false, policy);
        // then
        System.out.println("DIAMOND_TIGHT_GAS_COMPARE early=" + early + " late=" + late);
        assertNotNull(early.failure(), "The early gas-1 original must retain its actual terminal failure");
        assertEquals(early, late,
                "Compare final durable outcomes and every charged attempt, not intermediate failure versus wait");
    }

    @Test
    void resolvedOriginalGasBoundaryMatchesAcrossCompletePhysicalSchedules() throws Exception {
        // given
        long gas = calibrateResolvedOriginalGas();
        // when
        var comparisons = new ArrayList<org.junit.jupiter.api.function.Executable>();
        for (long delta : List.of(-1L, 0L, 1L)) comparisons.add(() -> {
            var policy = ContractsExecutionPolicy.exactSharedGas(Math.addExact(gas, delta), "diamond-original-boundary");
            var prepared = runOriginalBoundary(false, policy);
            var early = runOriginalBoundary(true, policy);
            System.out.println("DIAMOND_G_BOUNDARY_COMPARE G=" + gas + " delta=" + delta
                    + " prepared=" + prepared + " early=" + early);
            assertNotNull(prepared.original(), "C/D preparation must expose B's actual terminal result");
            assertEquals(delta >= 0L ? "APPLIED" : "GAS_LIMIT_EXCEEDED", prepared.originalDisposition());
            assertEquals(delta >= 0L, prepared.original().commits(), "Actual admitted original at G" + delta);
            assertEquals(delta >= 0L ? "SUCCESS" : "GAS_LIMIT_EXCEEDED", prepared.original().status());
            if (delta >= 0L) assertEquals(gas, prepared.original().gas());
            else assertNotNull(prepared.original().rejected());
            // Genuine repeated completed attempts may add aggregate call charges.
            // Compare retained logical evidence, not physical attempt-count symmetry.
            assertAll("Same fixed policy: retained histories, dispositions, logical charges and owned results",
                    () -> assertEquals(prepared.entry(), early.entry()),
                    () -> assertEquals(prepared.stop(), early.stop()),
                    () -> assertEquals(prepared.documents(), early.documents()),
                    () -> assertEquals(prepared.original(), early.original()),
                    () -> assertEquals(prepared.originalDisposition(), early.originalDisposition()),
                    () -> assertEquals(prepared.terminals(), early.terminals()),
                    () -> assertEquals(prepared.observed(), early.observed()),
                    () -> assertEquals(prepared.events(), early.events()));
        });
        // then
        assertAll("Actual admitted B-original G-1/G/G+1; no gas-1 rerun", comparisons);
    }

    private static long calibrateResolvedOriginalGas() throws Exception {
        try (var s = new Scenario(ORIGINAL_NAMESPACE, true)) {
            var run = new OriginalBoundaryRun(s, ContractsExecutionPolicy.releaseDefault(), "calibration");
            run.prefix("C");
            run.prefix("D");
            var peer = s.f.blue.advanced().auditDocument(s.root("D").id());
            run.processB("after-real-C-D-prefixes");
            assertNotNull(run.original, "Calibration must retain the actual admitted B original");
            assertEquals("APPLIED", run.originalDisposition);
            assertTrue(run.original.commits(), run.original.toString());
            var input = s.f.blue.advanced().closureInvocation(run.original.publication()).orElseThrow();
            var actual = s.f.blue.advanced().closureExecution(run.original.publication()).orElseThrow();
            var a = input.snapshot().managedDocument(new blue.language.processor.closure.DocumentId(s.root("A").id().value()));
            var d = input.snapshot().managedDocument(new blue.language.processor.closure.DocumentId(s.root("D").id().value()));
            assertNotNull(a, "A must be present in the actual fully resolved invocation, not a raw missing-demand capture");
            assertNotNull(d);
            assertEquals(s.sourceBlueId, a.blueId());
            assertEquals(s.sourceEpoch, a.epoch());
            assertEquals(peer.blueId(), d.blueId());
            assertEquals(peer.epoch(), d.epoch());
            assertEquals(List.of(new blue.language.processor.closure.DocumentId(s.root("B").id().value())),
                    actual.rootedProjection().context().entryOwners());
            assertTrue(actual.totalGas() > 1L && actual.totalGas() < input.executionPolicy().sharedLimit());
            System.out.println("DIAMOND_G_BOUNDARY_CALIBRATION terminal=" + run.original);
            return actual.totalGas();
        }
    }

    private static OriginalBoundaryOutcome runOriginalBoundary(boolean early, ContractsExecutionPolicy policy) throws Exception {
        try (var s = new Scenario(ORIGINAL_NAMESPACE, true)) {
            var run = new OriginalBoundaryRun(s, policy, early ? "B-before-C" : "C-D-before-B");
            if (early) run.processB("before-C");
            run.prefix("C");
            run.prefix("D");
            // The same explicit policy is used even if the first B call returned a
            // genuine terminal failure. A retained replay adds no new call gas.
            run.processB("after-C-D");
            String stop = "BLOCKED_WITHOUT_B_TERMINAL";
            if (run.original != null) {
                stop = "BOUND";
                for (int step = 0; step < 160; step++) {
                    var next = s.f.blue.processing().drain(new DrainBudget(1, 1));
                    run.observe("drain-" + step, next);
                    if (next.quiescent()) { stop = "QUIESCENT"; break; }
                    if (next.blocked()) { stop = "BLOCKED"; break; }
                }
            }
            // Do not admit an unresolved B original at the drain's default policy.
            assertNotEquals("BOUND", stop, "No real blocked or quiet endpoint within the diagnostic bound");
            var outcome = new OriginalBoundaryOutcome(run.entry.blueId(), stop, s.state(), run.original, run.originalDisposition,
                    run.terminals(), run.chargedGas, NAMES.stream().map(s::observed).toList(),
                    NAMES.stream().map(s::eventCount).toList());
            System.out.println("DIAMOND_G_BOUNDARY_FINAL schedule=" + run.schedule + " limit="
                    + policy.sharedGasLimit() + " outcome=" + outcome);
            CoordinationTestControl.attach(s.f.blue.advanced().rawEngine()).restartFromStores();
            assertEquals(outcome.documents(), s.state());
            assertEquals(outcome.terminals(), run.terminals());
            run.requireRetainedOriginal();
            return outcome;
        }
    }

    private static final class OriginalBoundaryRun {
        final Scenario s;
        final ContractsExecutionPolicy policy;
        final String schedule;
        final EntryHandle entry;
        final java.util.Set<String> prefixTerminals;
        OriginalBoundaryTerminal original;
        String originalDisposition;
        long chargedGas;

        OriginalBoundaryRun(Scenario s, ContractsExecutionPolicy policy, String schedule) {
            this.s = s; this.policy = policy; this.schedule = schedule;
            prefixTerminals = retained().keySet();
            entry = s.f.append(s.root("C"), s.timeline, "attach", 300, attachment("a", s.root("A")), true);
        }

        void prefix(String name) {
            var first = s.f.blue.processing().process(s.root(name), entry);
            observe(name + "-original", first);
            if (first.blocked() || first.find(entry).map(value -> !value.applied()).orElse(false)) return;
            for (int step = 0; step < 32; step++) {
                var next = s.f.blue.processing().processNext(s.root(name));
                observe(name + "-prefix-" + step, next);
                if (next.blocked() || next.quiescent()) return;
            }
            fail("No real prefix endpoint for " + name + " in " + schedule);
        }

        void processB(String stage) throws java.io.IOException {
            var next = s.f.blue.advanced().process(s.root("B"), entry, policy);
            observe(stage, next);
            for (var result : next.entries()) for (var closure : result.closures()) {
                var input = s.f.blue.advanced().closureInvocation(closure.closureId()).orElse(null);
                if (input == null || !(input.cause() instanceof blue.language.processor.closure.ExternalEventCause)
                        || !input.snapshot().publicRootDocumentIds().equals(List.of(
                                new blue.language.processor.closure.DocumentId(s.root("B").id().value())))) continue;
                var actual = s.f.blue.advanced().closureExecution(closure.closureId()).orElseThrow();
                var terminal = terminal(closure.closureId(), input, actual);
                assertEquals(policy.sharedGasLimit(), terminal.limit());
                assertEquals(policy.label(), terminal.label());
                if (original == null) {
                    original = terminal;
                    originalDisposition = closure.disposition().name();
                    RootedGasEvidence.write("diamond-boundary-" + schedule + "-" + policy.sharedGasLimit()
                            + "-B-original", input, actual);
                } else {
                    assertEquals(original, terminal, "An exact retry cannot replace the original B result");
                    assertEquals(originalDisposition, closure.disposition().name());
                }
            }
            requireRetainedOriginal();
        }

        void observe(String stage, DrainResult drain) {
            chargedGas = Math.addExact(chargedGas, drain.stats().gas());
            requireRetainedOriginal();
            var source = s.f.blue.advanced().auditManagedEpoch(s.root("A").id(), s.sourceEpoch).orElseThrow();
            assertEquals(s.sourceReceipt, source.receiptIdentity());
            assertEquals(s.sourceBlueId, source.afterBlueId());
            System.out.println("DIAMOND_G_BOUNDARY_CALL schedule=" + schedule + " limit=" + policy.sharedGasLimit()
                    + " stage=" + stage + " blocked=" + drain.blocked() + " quiet=" + drain.quiescent()
                    + " gas=" + drain.stats().gas() + " committed=" + drain.stats().committedTransitions()
                    + " entries=" + drain.entries().stream().map(value -> value.disposition() + ":"
                            + value.closures().stream().map(closure -> closure.closureId() + ":" + closure.disposition()).toList()).toList()
                    + " retained=" + drain.rootedRetainedApplications().stream().map(value -> value.rootDocumentId()
                            + ":" + value.work().workIdentity() + ":" + value.result().closureId()).toList()
                    + " managed=" + drain.managedEpochApplicationAttempts().stream().map(value -> value.work().workIdentity()
                            + ":published=" + value.published() + ":replayed=" + value.replayed()).toList());
        }

        void requireRetainedOriginal() {
            if (original != null) assertEquals(original, terminal(original.publication(),
                    s.f.blue.advanced().closureInvocation(original.publication()).orElseThrow(),
                    s.f.blue.advanced().closureExecution(original.publication()).orElseThrow()));
        }

        Map<String, blue.coordination.internal.RootedCalculationFixture.RetainedTerminal> retained() {
            var found = new TreeMap<String, blue.coordination.internal.RootedCalculationFixture.RetainedTerminal>();
            // This maintained fixture enumerates the store-wide terminal inventory.
            for (var value : s.f.control.retainedTerminals(s.root("B").id()))
                found.put(value.identity(), value);
            return Map.copyOf(found);
        }

        Map<String, OriginalBoundaryTerminal> terminals() {
            var results = new TreeMap<String, OriginalBoundaryTerminal>();
            retained().forEach((identity, value) -> {
                if (prefixTerminals.contains(identity)) return;
                var evidence = terminal(identity, value.input(), value.result());
                boolean bLive = value.input().cause() instanceof blue.language.processor.closure.ExternalEventCause
                        && value.input().snapshot().publicRootDocumentIds().equals(List.of(
                                new blue.language.processor.closure.DocumentId(s.root("B").id().value())));
                if (bLive) {
                    assertNotNull(original, "No hidden default-policy B original may be admitted by continuation");
                    assertEquals(original, evidence, "Every new B LIVE terminal must retain the exact explicit original policy/result");
                } else {
                    // Local histories and the joined terminal use the configured
                    // adapter policy, not the old B LIVE call's explicit limit.
                    assertEquals(ContractsExecutionPolicy.releaseDefault().sharedGasLimit(), evidence.limit());
                    assertEquals(ContractsExecutionPolicy.releaseDefault().label(), evidence.label());
                }
                results.put(identity, evidence);
            });
            return Map.copyOf(results);
        }
    }

    private static OriginalBoundaryTerminal terminal(String publication,
            blue.language.processor.closure.ClosureInvocationInput input,
            blue.language.processor.closure.ClosureProcessResult result) {
        assertEquals(input.invocationIdentity(), result.invocationIdentity());
        assertEquals(input.snapshot().closureIdentity(), result.inputClosureIdentity());
        var charges = result.gasTrace().stream().map(value -> new OriginalBoundaryCharge(value.sequence(),
                value.namespace().name(), value.counter(), value.quantity(), value.weight(), value.subtotal(),
                value.documentId() == null ? null : value.documentId().value(), value.scopePath(),
                value.activationGeneration(), value.componentGeneration(), value.contractKey(), value.logicalPath(),
                value.workOccurrenceId(), value.reason())).toList();
        var rejected = result.rejectedCharge();
        String rejection = rejected == null ? null : rejected.rejectedChargeIdentity() + ":" + rejected.namespace()
                + ":" + rejected.counter() + ":" + rejected.quantity() + ":" + rejected.weight() + ":"
                + rejected.subtotal() + ":remaining=" + rejected.remainingBeforeCharge()
                + ":cap=" + rejected.applicableCap().kind() + ":owner=" + rejected.owner().kind()
                + ":work=" + rejected.owner().workOccurrenceIdentity() + ":finalization=" + rejected.owner().finalizationOrdinal();
        var projection = result.rootedProjection();
        var owned = projection == null ? List.<String>of() : result.resultingDocuments().stream()
                .filter(value -> projection.owns(value.documentId())).map(value -> value.documentId().value() + ":"
                        + value.beforeBlueId() + "->" + value.afterBlueId() + ":epoch=" + value.epoch()
                        + ":generation=" + value.componentGeneration()).toList();
        return new OriginalBoundaryTerminal(publication, input.invocationIdentity(), input.snapshot().closureIdentity(),
                input.cause().getClass().getSimpleName(), input.cause().causeIdentity(), input.executionPolicy().identity(),
                input.executionPolicy().sharedLimit(), input.executionPolicy().label(), result.status().name(),
                result.commits(), result.rollbackToInput(), result.outputClosureIdentity(), result.totalGas(),
                result.gasTraceIdentity(), charges, rejection, owned,
                result.commitCompanion() == null ? null : result.commitCompanion().companionIdentity());
    }

    private static TightGasOutcome runTightGas(boolean early, ContractsExecutionPolicy policy) throws Exception {
        try (var s = new Scenario(ORIGINAL_NAMESPACE, true)) {
            var entry = s.f.append(s.root("C"), s.timeline, "attach", 300, attachment("a", s.root("A")), true);
            var run = new TightGasRun(s, entry, policy, early ? "early" : "late");
            if (early) run.processB("before-C");
            run.prefix("C");
            if (!early) run.processB("after-C-before-D");
            run.prefix("D");
            if (!early) run.processB("after-D");

            String stop = "BLOCKED_WITHOUT_B_TERMINAL";
            if (run.failure != null) {
                // The retained B terminal must be replayed/skipped, not retried with
                // the drain's default budget. Audit it after every actual call.
                stop = "BOUND";
                for (int step = 0; step < 160; step++) {
                    var next = s.f.blue.processing().drain(new DrainBudget(1, 1));
                    run.observe("drain-" + step, next);
                    if (next.quiescent()) { stop = "QUIESCENT"; break; }
                    if (next.blocked()) { stop = "BLOCKED"; break; }
                }
            }
            // Without a retained failure, default drain could admit B at a different
            // policy. A still-blocked real prefix is an observed diagnostic endpoint.
            var state = s.state();
            var plans = new LinkedHashMap<String, List<String>>();
            for (String name : NAMES) {
                var history = s.f.history(s.root(name));
                var prefix = s.prefixes.get(name);
                assertEquals(prefix, history.subList(0, prefix.size()));
                plans.put(name, s.plans(name).stream().map(plan -> plan.planIdentity() + ":" + plan.status()
                        + ":" + plan.nextSourceEpoch() + ":" + plan.requiredThroughSourceEpoch()).toList());
            }
            var outcome = new TightGasOutcome(entry.blueId(), stop, state, Map.copyOf(plans), run.failure,
                    run.chargedGas, NAMES.stream().map(s::observed).toList(), NAMES.stream().map(s::eventCount).toList());
            System.out.println("DIAMOND_TIGHT_GAS_FINAL schedule=" + run.schedule + " outcome=" + outcome);
            assertNotEquals("BOUND", stop, "Diagnostic did not reach a real quiet or blocked endpoint");
            CoordinationTestControl.attach(s.f.blue.advanced().rawEngine()).restartFromStores();
            assertEquals(state, s.state());
            run.requireRetainedFailure();
            return outcome;
        }
    }

    private static final class TightGasRun {
        final Scenario s;
        final EntryHandle entry;
        final ContractsExecutionPolicy policy;
        final String schedule;
        long chargedGas;
        TightGasFailure failure;

        TightGasRun(Scenario s, EntryHandle entry, ContractsExecutionPolicy policy, String schedule) {
            this.s = s; this.entry = entry; this.policy = policy; this.schedule = schedule;
        }

        void processB(String stage) {
            var before = s.state();
            var result = s.f.blue.advanced().process(s.root("B"), entry, policy);
            observe(stage, result);
            assertEquals(before, s.state(), "B's gas-1 failure or prerequisite wait cannot publish document state");
            result.find(entry).ifPresent(terminal -> assertEquals(EntryDisposition.GAS_LIMIT_EXCEEDED,
                    terminal.disposition(), stage + " " + terminal.diagnostic()));
        }

        void prefix(String name) {
            var first = s.f.blue.processing().process(s.root(name), entry);
            observe(name + "-original", first);
            if (first.blocked() || first.find(entry).map(result -> !result.applied()).orElse(false)) return;
            for (int step = 0; step < 32; step++) {
                var next = s.f.blue.processing().processNext(s.root(name));
                observe(name + "-prefix-" + step, next);
                if (next.blocked() || next.quiescent()) return;
            }
            System.out.println("DIAMOND_TIGHT_GAS_PREFIX_BOUND schedule=" + schedule + " root=" + name);
        }

        void observe(String stage, DrainResult drain) {
            // SDK aggregate gas counts genuine completed failures too, and excludes
            // retained replays. Never deduplicate actual charges by invocation ID.
            chargedGas = Math.addExact(chargedGas, drain.stats().gas());
            for (var terminal : drain.entries()) for (var closure : terminal.closures()) {
                var input = s.f.blue.advanced().closureInvocation(closure.closureId()).orElse(null);
                if (input == null || !(input.cause() instanceof blue.language.processor.closure.ExternalEventCause)
                        || !terminal.entry().blueId().equals(entry.blueId())
                        || !input.snapshot().publicRootDocumentIds().equals(List.of(
                                new blue.language.processor.closure.DocumentId(s.root("B").id().value())))) continue;
                var retained = readFailure(closure.closureId());
                if (failure == null) failure = retained;
                else assertEquals(failure, retained, "A later call replaced B's actual failed original terminal");
                assertEquals(EntryDisposition.GAS_LIMIT_EXCEEDED, closure.disposition());
                assertTrue(closure.changes().isEmpty());
                assertTrue(closure.publicEvents().isEmpty());
            }
            requireRetainedFailure();
            var source = s.f.blue.advanced().auditManagedEpoch(s.root("A").id(), s.sourceEpoch).orElseThrow();
            assertEquals(s.sourceReceipt, source.receiptIdentity());
            assertEquals(s.sourceBlueId, source.afterBlueId());
            System.out.println("DIAMOND_TIGHT_GAS schedule=" + schedule + " stage=" + stage
                    + " blocked=" + drain.blocked() + " quiet=" + drain.quiescent() + " paused=" + drain.paused()
                    + " gas=" + drain.stats().gas() + " chargedGas=" + chargedGas
                    + " diagnostic=" + drain.diagnostic() + " entries=" + drain.entries().stream().map(value ->
                            value.entry().blueId() + ":" + value.disposition() + ":" + value.closures().stream()
                                    .map(closure -> closure.closureId() + ":" + closure.disposition()).toList()).toList()
                    + " retained=" + drain.rootedRetainedResults().stream().map(value ->
                            value.closureId() + ":" + value.disposition()).toList()
                    + " applications=" + drain.managedEpochApplicationAttempts().stream().map(value ->
                            value.work().workIdentity() + ":published=" + value.published()).toList()
                    + " epochs=" + NAMES.stream().map(name -> s.f.blue.advanced().auditDocument(s.root(name).id()).epoch()).toList()
                    + " observed=" + NAMES.stream().map(s::observed).toList() + " Bfailure=" + failure);
        }

        void requireRetainedFailure() {
            if (failure != null) assertEquals(failure, readFailure(failure.publicationIdentity()),
                    "B's exact gas-1 terminal must survive later publications, replay and restart");
        }

        TightGasFailure readFailure(String publicationIdentity) {
            var input = s.f.blue.advanced().closureInvocation(publicationIdentity).orElseThrow();
            var actual = s.f.blue.advanced().closureExecution(publicationIdentity).orElseThrow();
            assertEquals(policy.sharedGasLimit(), input.executionPolicy().sharedLimit());
            assertEquals(policy.label(), input.executionPolicy().label());
            assertEquals(input.invocationIdentity(), actual.invocationIdentity());
            assertFalse(actual.commits(), "A failed B original must not be replaced by a successful default-budget original");
            assertTrue(actual.rollbackToInput());
            assertEquals("GAS_LIMIT_EXCEEDED", actual.status().name());
            assertNotNull(actual.rejectedCharge());
            assertNull(actual.commitCompanion());
            assertNull(actual.rootedProjection());
            assertTrue(actual.checkpointWrites().isEmpty());
            assertTrue(actual.publicEvents().isEmpty());
            return new TightGasFailure(publicationIdentity, input.invocationIdentity(), input.snapshot().closureIdentity(),
                    input.cause().causeIdentity(), input.executionPolicy().identity(), actual.inputClosureIdentity(),
                    actual.outputClosureIdentity(), actual.status().name(), actual.totalGas(), actual.gasTraceIdentity(),
                    actual.rejectedCharge().rejectedChargeIdentity());
        }
    }

    private static Outcome run(String namespace, boolean reversedDirectCalls) throws Exception {
        return run(namespace, reversedDirectCalls, true);
    }

    private static Outcome run(String namespace, boolean reversedDirectCalls, boolean emitTokens) throws Exception {
        try (var s = new Scenario(namespace, emitTokens)) {
            var entry = s.f.append(s.root("C"), s.timeline, "attach", 300, attachment("a", s.root("A")), true);
            if (emitTokens && ORIGINAL_NAMESPACE.equals(namespace))
                assertEquals("5G1qitMzxCuQ3UpCuJyh2fZimmbdRNwsthMYJUWZBjVL", entry.blueId());

            if (reversedDirectCalls) {
                // No independently retained C return exists yet. This call must discover
                // the prospective prerequisite without publishing B's incomplete view.
                s.requireUnchangedBlockedOriginal("B", entry, true);
                s.requireUnchangedBlockedOriginal("B", entry, true);
                CoordinationTestControl.attach(s.f.blue.advanced().rawEngine()).restartFromStores();
                s.requireUnchangedBlockedOriginal("B", entry, true);

                assertEquals(blue.coordination.api.ProcessingSelection.Kind.JOURNAL,
                        s.f.blue.advanced().auditNextProcessingSelection().kind(),
                        "The real original C lane must remain eligible after the blocked B call");
                var directC = s.f.blue.processing().drain(new DrainBudget(1, 1));
                assertEquals(EntryDisposition.APPLIED, directC.entry(entry).disposition());
                assertEquals(List.of(s.root("C").id()), directC.entry(entry).closures().stream()
                        .flatMap(closure -> closure.changes().stream()).map(DocumentChange::documentId).distinct().toList(),
                        "Ordinary drain must make the advertised C progress, not execute B or D inside the blocked call");
                s.observe(directC);
                boolean terminalWait = false;
                for (int step = 0; step < 32; step++) {
                    var next = s.f.blue.processing().processNext(s.root("C"));
                    s.observe(next);
                    if (next.blocked()) { terminalWait = true; break; }
                    assertFalse(next.quiescent(), "C must wait for the original receiving peers");
                }
                assertTrue(terminalWait, "C did not reach its real frozen terminal prerequisite");
                assertEquals(2L, s.observed("C"));
                assertEquals(s.sourceEpoch, s.frozenPlan.requiredThroughSourceEpoch());
                s.requireUnchangedBlockedOriginal("B", entry, false);

                var directD = s.f.blue.processing().process(s.root("D"), entry);
                assertEquals(EntryDisposition.APPLIED, directD.entry(entry).disposition());
                s.observe(directD);
            }

            boolean quiet = false;
            for (int step = 0; step < 160; step++) {
                var next = s.f.blue.processing().drain(new DrainBudget(1, 1));
                s.observe(next);
                assertFalse(next.blocked(), "Normal diamond selection stalled at step " + step + ": " + next.diagnostic());
                if (next.quiescent()) { quiet = true; break; }
            }
            assertTrue(quiet, "The normal selector did not settle the original diamond");
            assertNotNull(s.frozenPlan, "The real C-to-A frozen history plan was never observed");
            assertEquals(emitTokens ? List.of(0L, 1L, 1L, 2L) : List.of(0L, 0L, 0L, 0L),
                    NAMES.stream().map(s::observed).toList());
            long tokenCount = emitTokens ? 1L : 0L;
            assertEquals(tokenCount, s.tokens("A", "C", "B"));
            assertEquals(tokenCount, s.tokens("A", "C", "D"));
            assertEquals(tokenCount, s.tokens("C", "B", "stop"));
            assertEquals(tokenCount, s.tokens("C", "D", "stop"));
            assertEquals(emitTokens ? List.of(2L, 0L, 0L, 2L) : List.of(0L, 0L, 0L, 0L),
                    NAMES.stream().map(s::eventCount).toList());
            for (String name : NAMES) {
                var history = s.f.history(s.root(name));
                var prefix = s.prefixes.get(name);
                assertEquals(prefix, history.subList(0, prefix.size()), "Retained prefix changed for " + name);
                assertTrue(s.f.blue.advanced().auditManagedDocumentReadiness(s.root(name).id()).orElseThrow().ready());
                assertTrue(s.plans(name).stream().allMatch(plan -> plan.status() == ManagedCatchUpStatus.COMPLETE));
                for (var receipt : s.f.blue.advanced().auditManagedEpochs(s.root(name).id())) {
                    if (prefix.contains(receipt.receiptIdentity())) continue;
                    assertTrue(!receipt.emittedEvents().isEmpty()
                                    || receipt.beforeBlueId().filter(before -> !before.equals(receipt.afterBlueId())).isPresent(),
                            "No numbered epoch may be fabricated just to fill the joint result: " + receipt.receiptIdentity());
                }
            }

            var settled = s.state();
            assertFalse(s.retainedGas.isEmpty(), "No exact successful publication gas was observed");
            var retainedGas = Map.copyOf(s.retainedGas);
            assertTrue(s.aggregateCallGas > 0L, "No successful drain call gas was observed");
            long aggregateCallGas = s.aggregateCallGas;
            CoordinationTestControl.attach(s.f.blue.advanced().rawEngine()).restartFromStores();
            assertEquals(settled, s.state());
            var restarted = s.f.blue.processing().drain(new DrainBudget(1, 1));
            assertTrue(restarted.quiescent(), String.valueOf(restarted.diagnostic()));
            assertEquals(0L, restarted.stats().committedTransitions());
            assertEquals(0L, restarted.stats().gas());
            s.observe(restarted);
            assertEquals(settled, s.state());
            assertEquals(retainedGas, s.retainedGas);
            assertEquals(aggregateCallGas, s.aggregateCallGas);
            Map<String, String> ids = new LinkedHashMap<>();
            NAMES.forEach(name -> ids.put(name, s.root(name).id().value()));
            return new Outcome(entry.blueId(), Map.copyOf(ids), settled, retainedGas, aggregateCallGas);
        }
    }

    private static final class Scenario implements AutoCloseable {
        final RootedSdkFixture f = new RootedSdkFixture();
        final String timeline;
        final Map<String, DocumentHandle> roots = new LinkedHashMap<>();
        final Map<String, List<String>> prefixes = new LinkedHashMap<>();
        final Map<String, GasEvidence> retainedGas = new TreeMap<>();
        final long sourceEpoch;
        final String sourceReceipt;
        final String sourceBlueId;
        FrozenPlan frozenPlan;
        long aggregateCallGas;

        Scenario(String namespace, boolean emitTokens) throws Exception {
            timeline = namespace + "/alice";
            String template = RootedSdkFixture.resource("node-graph.template.json");
            for (String name : NAMES) {
                String authored = authored(template, name, namespace);
                var exact = f.blue.values().yaml(authored);
                f.exact.put(exact.blueId(), exact.json());
                roots.put(name, f.startYaml(authored, timeline));
            }
            assertTrue(ORIGINAL_NAMESPACE.equals(namespace)
                            ? root("D").id().value().compareTo(root("B").id().value()) < 0
                            : root("B").id().value().compareTo(root("D").id().value()) < 0,
                    "The ID control must really reverse the B/D canonical order");
            prefix("D", "attach", 100, attachment("c", root("C")));
            prefix("B", "attach", 125, attachment("c", root("C")));
            prefix("A", "attach", 150, attachment("b", root("B")));
            prefix("A", "attach", 175, attachment("d", root("D")));
            if (emitTokens) {
                prefix("A", "emit", 200, "to: C\nnext: B");
                prefix("A", "emit", 210, "to: C\nnext: D");
            }
            prefix("A", "touch", 250, "{}");
            sourceEpoch = f.blue.advanced().auditDocument(root("A").id()).epoch();
            if (emitTokens) assertEquals(11L, sourceEpoch);
            assertEquals(List.of(sourceEpoch, 2L, 2L, 0L), NAMES.stream().map(name ->
                    f.blue.advanced().auditDocument(root(name).id()).epoch()).toList());
            for (String name : NAMES) prefixes.put(name, f.history(root(name)));
            var source = f.blue.advanced().auditManagedEpoch(root("A").id(), sourceEpoch).orElseThrow();
            sourceReceipt = source.receiptIdentity(); sourceBlueId = source.afterBlueId();
        }

        DocumentHandle root(String name) { return roots.get(name); }

        void prefix(String name, String operation, long time, String request) {
            var entry = f.append(root(name), timeline, operation, time, request, true);
            var first = f.blue.processing().process(root(name), entry);
            assertEquals(EntryDisposition.APPLIED, first.entry(entry).disposition());
            for (int step = 0; step < 16; step++) {
                var next = f.blue.processing().processNext(root(name));
                assertFalse(next.blocked(), String.valueOf(next.diagnostic()));
                if (next.quiescent()) return;
            }
            fail("Unchanged prefix did not settle for " + name + " at " + time);
        }

        void requireUnchangedBlockedOriginal(String name, EntryHandle entry, boolean requirePreflight) {
            var before = state();
            var control = CoordinationTestControl.attach(f.blue.advanced().rawEngine());
            var metricsBefore = control.metricsSnapshot();
            var blocked = f.blue.processing().process(root(name), entry);
            var metricsAfter = control.metricsSnapshot();
            if (requirePreflight) {
                assertEquals(metricsBefore.counters().getOrDefault("contracts.rootedJoinPreflight.blocked", 0L) + 1L,
                        metricsAfter.counters().getOrDefault("contracts.rootedJoinPreflight.blocked", 0L).longValue(),
                        "Each pre-C call must discover and block its actual prospective join prerequisite");
                assertTrue(metricsAfter.counters().getOrDefault("contracts.rootedJoinPreflight.calculations", 0L)
                                > metricsBefore.counters().getOrDefault("contracts.rootedJoinPreflight.calculations", 0L),
                        "Pre-C blocking must be backed by a real preflight calculation, including after restart");
                assertTrue(metricsAfter.phaseNanos().getOrDefault("contracts.rootedJoinPreflight", 0L)
                                > metricsBefore.phaseNanos().getOrDefault("contracts.rootedJoinPreflight", 0L),
                        "Preflight physical time must increase independently of zero committed logical gas");
            }
            assertTrue(blocked.blocked(), "Prospective peer prerequisite was not reported: " + blocked.diagnostic());
            assertEquals(0L, blocked.stats().committedTransitions());
            assertEquals(0L, blocked.stats().gas());
            assertEquals(before, state(), "A blocked original cannot publish heads, receipts, or plan progress");
            blocked.find(entry).ifPresent(result -> {
                assertNotEquals(EntryDisposition.APPLIED, result.disposition());
                assertNotEquals(EntryDisposition.REJECTED, result.disposition());
                assertNotEquals(EntryDisposition.NO_MATCH, result.disposition());
                assertTrue(result.publicEvents().isEmpty());
            });
            assertTrue(blocked.managedEpochApplications().isEmpty());
        }

        void observe(DrainResult drain) {
            // Readiness describes the next step, not whether this call completed work.
            // Pure prerequisite waits are checked separately by requireUnchangedBlockedOriginal.
            aggregateCallGas = Math.addExact(aggregateCallGas, drain.stats().gas());
            var source = f.blue.advanced().auditManagedEpoch(root("A").id(), sourceEpoch).orElseThrow();
            assertEquals(sourceReceipt, source.receiptIdentity());
            assertEquals(sourceBlueId, source.afterBlueId());
            for (var plan : plans("C")) if (plan.sourceDocumentId().equals(root("A").id())) {
                var captured = FrozenPlan.from(plan);
                if (frozenPlan == null) frozenPlan = captured;
                assertEquals(frozenPlan, captured, "Scheduling changed C's original frozen source interval");
                assertEquals(sourceEpoch, plan.requiredThroughSourceEpoch());
                if (plan.status() != ManagedCatchUpStatus.COMPLETE) {
                    assertTrue(plan.nextSourceEpoch() <= sourceEpoch);
                    var head = f.blue.advanced().auditDocument(root("A").id());
                    assertEquals(sourceEpoch, head.epoch(), "The peer prerequisite must not execute a later A frontier");
                    assertEquals(sourceBlueId, head.blueId());
                }
            }
            var closures = new ArrayList<ClosureResult>(drain.rootedRetainedResults());
            drain.entries().forEach(entry -> closures.addAll(entry.closures()));
            for (var closure : closures) if (closure.applied()) {
                var result = f.blue.advanced().closureExecution(closure.closureId()).orElseThrow();
                assertTrue(result.commits());
                retain(result.invocationIdentity(), new GasEvidence(result.outputClosureIdentity(),
                        result.totalGas(), result.gasTraceIdentity()));
            }
            for (var application : drain.managedEpochApplicationAttempts()) if (application.published()) {
                var result = application.attempt().processResult();
                assertTrue(result.commits());
                retain(result.invocationIdentity(), new GasEvidence(result.outputClosureIdentity(),
                        result.totalGas(), result.gasTraceIdentity()));
            }
        }

        void retain(String invocation, GasEvidence evidence) {
            var prior = retainedGas.putIfAbsent(invocation, evidence);
            if (prior != null) assertEquals(prior, evidence, "A retained invocation changed its exact gas or output");
        }

        List<ManagedOccurrenceCatchUpPlan> plans(String name) {
            return f.blue.advanced().auditManagedCatchUpPlans(root(name).id());
        }

        Map<String, DocumentState> state() {
            Map<String, DocumentState> result = new LinkedHashMap<>();
            for (String name : NAMES) {
                var head = f.blue.advanced().auditDocument(root(name).id());
                var history = f.blue.advanced().auditManagedEpochs(root(name).id());
                result.put(name, new DocumentState(head.epoch(), head.blueId(),
                        history.stream().map(ManagedEpochReceipt::receiptIdentity).toList(),
                        history.stream().map(ManagedEpochReceipt::processingGas).toList(),
                        plans(name).stream().map(ManagedOccurrenceCatchUpPlan::snapshotIdentity).toList()));
            }
            return Map.copyOf(result);
        }

        long observed(String name) {
            return ((Number) ExactBlueValue.wrap(f.blue.advanced().auditDocument(root(name).id()).current())
                    .scalarAt("/observed")).longValue();
        }

        long tokens(String name, String to, String next) {
            return f.blue.advanced().auditManagedEpochs(root(name).id()).stream()
                    .flatMap(receipt -> receipt.emittedEvents().stream())
                    .filter(event -> to.equals(event.exactEvent().scalarAt("/to"))
                            && next.equals(event.exactEvent().scalarAt("/next"))).count();
        }

        long eventCount(String name) {
            return f.blue.advanced().auditManagedEpochs(root(name).id()).stream()
                    .flatMap(receipt -> receipt.emittedEvents().stream()).count();
        }

        public void close() { f.close(); }
    }

    private static String attachment(String edge, DocumentHandle source) {
        return "edge: " + edge + "\nsource: {blueId: " + source.id().value() + "}";
    }

    private static String authored(String template, String name, String namespace) {
        return template.replace("<NODE>", name).replace("<NAMESPACE>", namespace)
                .replace("<TIMELINE>", namespace + "/alice");
    }

    private static String namespaceWithReversedPeerOrder() throws Exception {
        String template = RootedSdkFixture.resource("node-graph.template.json");
        try (var f = new RootedSdkFixture()) {
            for (int index = 0; index < 32; index++) {
                String namespace = "witness-forwarding-peer-permutation-" + index;
                String b = f.blue.values().yaml(authored(template, "B", namespace)).blueId();
                String d = f.blue.values().yaml(authored(template, "D", namespace)).blueId();
                if (b.compareTo(d) < 0) return namespace;
            }
        }
        throw new AssertionError("No reversed peer ordering in the fixed bounded namespace candidates");
    }

    private record FrozenPlan(String identity, String barrier, String occurrence, long generation,
            String source, long admittedEpoch, String admittedBlueId, long requiredThroughSourceEpoch, String cause) {
        static FrozenPlan from(ManagedOccurrenceCatchUpPlan plan) {
            return new FrozenPlan(plan.planIdentity(), plan.barrierIdentity(), plan.targetOccurrenceIdentity(),
                    plan.activationGeneration(), plan.sourceDocumentId().value(), plan.admittedSourceEpoch(),
                    plan.admittedSourceBlueId(), plan.requiredThroughSourceEpoch(), plan.causedByIdentity());
        }
    }

    private record DocumentState(long epoch, String blueId, List<String> receipts,
            List<Long> processingGas, List<String> planSnapshots) { }
    private record GasEvidence(String outputClosureIdentity, long totalGas, String gasTraceIdentity) { }
    private record OriginalBoundaryCharge(long sequence, String namespace, String counter, long quantity,
            long weight, long subtotal, String document, String scope, Long activationGeneration,
            Long componentGeneration, String contract, String logicalPath, String work, String reason) { }
    private record OriginalBoundaryTerminal(String publication, String invocation, String inputClosure,
            String causeKind, String cause, String policy, long limit, String label, String status,
            boolean commits, boolean rollback, String outputClosure, long gas, String trace,
            List<OriginalBoundaryCharge> charges, String rejected, List<String> owned, String companion) { }
    private record OriginalBoundaryOutcome(String entry, String stop, Map<String, DocumentState> documents,
            OriginalBoundaryTerminal original, String originalDisposition,
            Map<String, OriginalBoundaryTerminal> terminals, long chargedGas,
            List<Long> observed, List<Long> events) { }
    private record TightGasFailure(String publicationIdentity, String inputInvocationIdentity,
            String capturedClosureIdentity, String causeIdentity, String policyIdentity, String resultInputClosureIdentity,
            String outputClosureIdentity, String status, long gas, String traceIdentity, String rejectedChargeIdentity) { }
    private record TightGasOutcome(String entryBlueId, String stop, Map<String, DocumentState> documents,
            Map<String, List<String>> plans, TightGasFailure failure, long chargedGas,
            List<Long> observed, List<Long> eventCounts) { }
    private record Outcome(String entryBlueId, Map<String, String> documentIds,
            Map<String, DocumentState> documents, Map<String, GasEvidence> gas, long aggregateCallGas) { }
}
