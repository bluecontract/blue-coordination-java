package blue.coordination.sdk;

import blue.coordination.api.ContractsExecutionPolicy;
import blue.coordination.api.SourceHistoryPrerequisite;
import blue.coordination.api.SourceHistoryPrerequisiteObservation;
import blue.coordination.internal.CoordinationTestControl;
import blue.language.processor.ExternalOrderKey;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import static blue.coordination.api.SourceHistoryPrerequisiteObservation.Status.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real stopped requester authority is independent of physical source-selection freshness. */
final class RootedSourcePrerequisiteObservationTest {
    @Test void independentlyAdmittedAndAdvancedSourceSatisfiesWithoutRetryingParent() throws Exception {
        // given
        try (var s = new Scenario(null)) {
            var original = s.selection();
            assertEquals(SourceHistoryPrerequisite.Kind.ADMISSION, original.kind());
            assertEquals(PENDING, s.observe(original).status(), "An actual incomplete attempt is not terminal");
            var before = s.parentState();
            var source = s.f.startYaml(RootedSdkFixture.resource("source.yaml"), "rcp2/source");
            var live = s.observe(original);
            assertEquals(PENDING, live.status());
            assertEquals(SourceHistoryPrerequisite.Kind.LIVE, live.pending().orElseThrow().kind());
            // when
            assertEquals(EntryDisposition.APPLIED,
                    s.f.blue.processing().processNext(source).entry(s.source15).disposition());
            var sourceHistory = s.f.history(source);

            // then
            assertTrue(s.f.blue.advanced().sourceHistoryPrerequisites(s.parent).isEmpty());
            assertEquals(new SourceHistoryPrerequisiteObservation(SATISFIED, Optional.empty()), s.observe(original));
            assertEquals(SATISFIED, s.observe(live.pending().orElseThrow()).status());
            assertTrue(s.f.blue.advanced().sourceHistoryPrerequisites(s.parent).isEmpty());
            assertEquals(SATISFIED, s.observe(original).status(), "A harmless prior selection audit must not erase satisfaction");
            assertEquals(before, s.parentState());
            assertEquals(sourceHistory, s.f.history(source));

            assertEquals(EntryDisposition.APPLIED,
                    s.f.blue.processing().processNext(s.parent).entry(s.attachment).disposition());
            assertEquals(STALE, s.observe(original).status());
            assertTrue(s.observe(original).pending().isEmpty());
        }
    }

    @Test void pendingSelectionRefreshesPhysicalFencesWithoutChangingFrozenAuthority() throws Exception {
        // given
        try (var s = new Scenario(null)) {
            var original = s.selection();
            var before = s.parentState();
            s.f.appendReference(original.authoredBlueId(), "rcp2/source", "setCounter", 51, "counterValue: 99", false);
            // when
            var observed = s.observe(original);
            // then
            assertEquals(PENDING, observed.status());
            var refreshed = observed.pending().orElseThrow();
            assertNotEquals(original.selectionIdentity(), refreshed.selectionIdentity());
            assertNotEquals(original.journalRevision(), refreshed.journalRevision());
            assertEquals(original.requestingRoot(), refreshed.requestingRoot());
            assertEquals(original.requestingInvocationIdentity(), refreshed.requestingInvocationIdentity());
            assertEquals(original.demandIdentity(), refreshed.demandIdentity());
            assertEquals(original.sourceDocumentId(), refreshed.sourceDocumentId());
            assertEquals(original.authoredBlueId(), refreshed.authoredBlueId());
            assertEquals(original.cutoffExclusive(), refreshed.cutoffExclusive());
            assertEquals(List.of(refreshed), s.f.blue.advanced().sourceHistoryPrerequisites(s.parent));
            assertThrows(IllegalArgumentException.class,
                    () -> s.f.blue.advanced().processSourceHistoryPrerequisite(original));
            assertEquals(observed, s.observe(original));
            assertEquals(before, s.parentState());
        }
    }

    @Test void waitAndUnavailableAuthorityNeverBecomeSatisfied() throws Exception {
        // given
        try (var s = new Scenario(null)) {
            var original = s.selection();
            var before = s.parentState();
            var control = CoordinationTestControl.attach(s.f.blue.advanced().rawEngine());
            control.makeHistoricalUnavailable("exact source completeness unavailable");
            // when
            var waiting = s.observe(original);
            // then
            assertEquals(PENDING, waiting.status());
            assertEquals(SourceHistoryPrerequisite.Kind.WAIT, waiting.pending().orElseThrow().kind());
            assertEquals(before, s.parentState());
            control.makeHistoricalAvailable();
            assertEquals(SourceHistoryPrerequisite.Kind.ADMISSION, s.observe(original).pending().orElseThrow().kind());
            try (var other = new RootedSdkFixture()) {
                var absent = other.blue.advanced().observeSourceHistoryPrerequisite(original);
                assertEquals(STALE, absent.status());
                assertTrue(absent.pending().isEmpty());
            }
            assertEquals(before, s.parentState());
        }
    }

    @Test void changedFrozenOperandsAreRejectedAndExceptionalQueriesReleaseTheirProviderScope() throws Exception {
        // given
        try (var s = new Scenario(null)) {
            var original = s.selection();
            var before = s.parentState();
            // when
            for (String field : List.of("root", "source", "authored", "cutoff")) {
                assertThrows(IllegalArgumentException.class, () -> s.observe(changed(original, field)), field);
                assertEquals(original, s.observe(original).pending().orElseThrow(),
                        "Each failed observation closes its provider lookup scope");
            }
            for (String field : List.of("invocation", "demand")) {
                var absent = s.observe(changed(original, field));
                assertEquals(STALE, absent.status(), field);
                assertTrue(absent.pending().isEmpty());
            }
            // then
            assertEquals(before, s.parentState());
            assertEquals(original, s.selection());
        }
    }

    @Test void terminalRejectionConsumesRequesterEvenWhenItsHeadDoesNotAdvance() throws Exception {
        // given
        long required;
        try (var calibration = new Scenario(null)) {
            var source = calibration.f.startYaml(RootedSdkFixture.resource("source.yaml"), "rcp2/source");
            assertEquals(EntryDisposition.APPLIED,
                    calibration.f.blue.processing().processNext(source).entry(calibration.source15).disposition());
            var applied = calibration.retryParent();
            assertEquals(EntryDisposition.APPLIED, applied.disposition());
            required = applied.stats().gas();
        }
        assertTrue(required > 1L);
        var policy = ContractsExecutionPolicy.exactSharedGas(required - 1L, "fixed-requester-terminal-boundary");
        try (var s = new Scenario(policy)) {
            var original = s.selection();
            var before = s.parentState();
            assertEquals(PENDING, s.observe(original).status());
            var source = s.f.startYaml(RootedSdkFixture.resource("source.yaml"), "rcp2/source");
            assertEquals(EntryDisposition.APPLIED,
                    s.f.blue.processing().processNext(source).entry(s.source15).disposition());
            assertEquals(SATISFIED, s.observe(original).status());
            // when
            var rejected = s.retryParent();
            // then
            assertEquals(EntryDisposition.GAS_LIMIT_EXCEEDED, rejected.disposition(), rejected.diagnostic().toString());
            assertEquals(s.stopped.closures().get(0).closureId(), rejected.closures().get(0).closureId(),
                    "The same frozen logical requester has reached a terminal receipt");
            assertEquals(before, s.parentState());
            assertEquals(STALE, s.observe(original).status(), "Unchanged epoch/BlueId/graph is insufficient after consumption");
            assertTrue(s.f.blue.advanced().sourceHistoryPrerequisites(s.parent).isEmpty());
            assertEquals(STALE, s.observe(original).status());
        }
    }

    @Test void observationShapeCannotConfuseAnEmptyPendingSelectionWithSatisfaction() {
        // given
        Optional<SourceHistoryPrerequisite> absent = Optional.empty();
        // when
        org.junit.jupiter.api.function.Executable pendingWithoutSelection =
                () -> new SourceHistoryPrerequisiteObservation(PENDING, absent);
        org.junit.jupiter.api.function.Executable missingStatus =
                () -> new SourceHistoryPrerequisiteObservation(null, absent);
        // then
        assertThrows(IllegalArgumentException.class, pendingWithoutSelection);
        assertThrows(NullPointerException.class, missingStatus);
    }

    private static SourceHistoryPrerequisite changed(SourceHistoryPrerequisite p, String field) {
        return new SourceHistoryPrerequisite(p.selectionIdentity(),
                field.equals("root") ? p.sourceDocumentId() : p.requestingRoot(),
                field.equals("invocation") ? "unknown-invocation" : p.requestingInvocationIdentity(),
                field.equals("demand") ? "unknown-demand" : p.demandIdentity(),
                field.equals("source") ? p.requestingRoot() : p.sourceDocumentId(),
                field.equals("authored") ? "changed-authored-identity" : p.authoredBlueId(),
                field.equals("cutoff") ? ExternalOrderKey.of(List.of(21L)) : p.cutoffExclusive(),
                p.kind(), p.sourceEpoch(), p.sourceBlueId(), p.workIdentity(), p.entryBlueId(),
                p.journalRevision(), p.routeGeneration(), p.sourceSurfaceIdentity(), p.diagnostic());
    }

    private static final class Scenario implements AutoCloseable {
        final RootedSdkFixture f = new RootedSdkFixture();
        final DocumentHandle parent;
        final EntryHandle source15, attachment;
        final EntryResult stopped;
        final ContractsExecutionPolicy policy;

        Scenario(ContractsExecutionPolicy policy) throws Exception {
            this.policy = policy;
            String parentYaml = RootedSdkFixture.resource("parent.yaml");
            parent = f.startYaml(parentYaml, "rcp2/parent");
            var authored = f.blue.values().yaml(RootedSdkFixture.resource("source.yaml"));
            f.exact.put(authored.blueId(), authored.json());
            f.timelines.put("rcp2/source", f.blue.timelines().register("rcp2/source", "alice"));
            source15 = f.appendReference(authored.blueId(), "rcp2/source", "setCounter", 15, "counterValue: 5", false);
            attachment = f.append(parent, "rcp2/parent", "attach", 20, "child:\n  blueId: " + authored.blueId());
            stopped = retryParent();
            assertEquals(EntryDisposition.NEEDS_RESOURCES, stopped.disposition(), stopped.diagnostic().toString());
        }

        SourceHistoryPrerequisite selection() {
            var values = f.blue.advanced().sourceHistoryPrerequisites(parent);
            assertEquals(1, values.size(), values.toString());
            return values.get(0);
        }
        SourceHistoryPrerequisiteObservation observe(SourceHistoryPrerequisite p) {
            var control = CoordinationTestControl.attach(f.blue.advanced().rawEngine());
            long processorNanos = control.metricsSnapshot().phaseNanos().getOrDefault("contracts.closure.processor", 0L);
            assertTrue(processorNanos > 0L, "The original real PROCESS established this non-vacuous timer");
            try { return f.blue.advanced().observeSourceHistoryPrerequisite(p); }
            finally {
                assertEquals(processorNanos,
                        control.metricsSnapshot().phaseNanos().getOrDefault("contracts.closure.processor", 0L),
                        "Observation must not invoke PROCESS, including on exceptional paths");
            }
        }
        EntryResult retryParent() {
            return policy == null ? f.blue.processing().processNext(parent).entry(attachment)
                    : f.blue.advanced().process(parent, attachment, policy).entry(attachment);
        }
        List<?> parentState() { return List.of(parent.snapshot().epoch(), parent.snapshot().blueId(), f.history(parent)); }
        @Override public void close() { f.close(); }
    }
}
