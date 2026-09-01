package blue.coordination.sdk;

import blue.coordination.api.DocumentId;
import blue.coordination.api.ProcessingDrainReceipt;
import blue.coordination.internal.DefaultCoordinationEngine;
import blue.language.processor.closure.ClosureAttemptResult;
import blue.language.processor.closure.ManagedOccurrenceEvidenceDemand;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** SDK mapping proofs for retained managed-application resolution failures. */
final class SdkManagedEpochApplicationResolutionEvidenceTest {

    @Test
    void mapsMissingExactContentWithAutomaticRetryCount() {
        // given
        String diagnostic = "missing exact content with opaque host detail";

        // when
        ManagedEpochApplicationAttempt projected = mapAttempt(
                blue.coordination.api.ManagedEpochApplicationAttempt
                        .ResolutionStatus.MISSING_EXACT_CONTENT,
                diagnostic,
                2L);

        // then
        assertEquals(2L, projected.automaticRetryCount());
        assertEquals(
                Optional.of(ManagedEpochApplicationAttempt
                        .AutomaticResolutionStopReason.UNRESOLVED_DEMANDS),
                projected.automaticResolutionStopReason());
        assertEquals(1, projected.attempt().resourceDemands().size());
        assertEquals(1, projected.managedOccurrenceResolutionIssues().size());
        ManagedEpochApplicationAttempt.ManagedOccurrenceResolutionIssue issue =
                projected.managedOccurrenceResolutionIssues().get(0);
        assertEquals(
                projected.attempt().resourceDemands().get(0).demandIdentity(),
                issue.demandIdentity());
        assertEquals(
                ManagedEpochApplicationAttempt.ResolutionStatus
                        .MISSING_EXACT_CONTENT,
                issue.status());
        assertEquals(diagnostic, issue.diagnostic());
    }

    @Test
    void mapsExpansionLimitAndRepeatedProgressWithoutIssueText() {
        // given
        var expansionLimit = blue.coordination.api
                .ManagedEpochApplicationAttempt
                .AutomaticResolutionStopReason.EXPANSION_LIMIT;
        var repeatedProgress = blue.coordination.api
                .ManagedEpochApplicationAttempt
                .AutomaticResolutionStopReason.REPEATED_PROGRESS;

        // when
        ManagedEpochApplicationAttempt limited = mapStop(
                expansionLimit, 4L);
        ManagedEpochApplicationAttempt repeated = mapStop(
                repeatedProgress, 2L);

        // then
        assertEquals(
                Optional.of(ManagedEpochApplicationAttempt
                        .AutomaticResolutionStopReason.EXPANSION_LIMIT),
                limited.automaticResolutionStopReason());
        assertEquals(
                Optional.of(ManagedEpochApplicationAttempt
                        .AutomaticResolutionStopReason.REPEATED_PROGRESS),
                repeated.automaticResolutionStopReason());
        assertEquals(4L, limited.automaticRetryCount());
        assertEquals(2L, repeated.automaticRetryCount());
        assertEquals(List.of(), limited.managedOccurrenceResolutionIssues());
        assertEquals(List.of(), repeated.managedOccurrenceResolutionIssues());
    }

    @Test
    void mapsAmbiguousEpochWithoutInterpretingDiagnosticText() {
        // given
        String diagnostic = "two retained matches; wording is not an enum";

        // when
        ManagedEpochApplicationAttempt projected = mapAttempt(
                blue.coordination.api.ManagedEpochApplicationAttempt
                        .ResolutionStatus.AMBIGUOUS_MANAGED_EPOCH,
                diagnostic,
                0L);

        // then
        assertEquals(0L, projected.automaticRetryCount());
        ManagedEpochApplicationAttempt.ManagedOccurrenceResolutionIssue issue =
                projected.managedOccurrenceResolutionIssues().get(0);
        assertEquals(
                ManagedEpochApplicationAttempt.ResolutionStatus
                        .AMBIGUOUS_MANAGED_EPOCH,
                issue.status());
        assertEquals(diagnostic, issue.diagnostic());
    }

    private static ManagedEpochApplicationAttempt mapAttempt(
            blue.coordination.api.ManagedEpochApplicationAttempt
                    .ResolutionStatus status,
            String diagnostic,
            long automaticRetryCount) {
        return mapAttempt(
                Optional.of(blue.coordination.api
                        .ManagedEpochApplicationAttempt
                        .AutomaticResolutionStopReason.UNRESOLVED_DEMANDS),
                status,
                diagnostic,
                automaticRetryCount);
    }

    private static ManagedEpochApplicationAttempt mapStop(
            blue.coordination.api.ManagedEpochApplicationAttempt
                    .AutomaticResolutionStopReason stopReason,
            long automaticRetryCount) {
        return mapAttempt(
                Optional.of(stopReason), null, null, automaticRetryCount);
    }

    private static ManagedEpochApplicationAttempt mapAttempt(
            Optional<blue.coordination.api.ManagedEpochApplicationAttempt
                    .AutomaticResolutionStopReason> stopReason,
            blue.coordination.api.ManagedEpochApplicationAttempt
                    .ResolutionStatus status,
            String diagnostic,
            long automaticRetryCount) {
        DocumentId source = DocumentId.of("retained-source");
        DocumentId consumer = DocumentId.of("catch-up-consumer");
        ManagedOccurrenceEvidenceDemand demand =
                ManagedOccurrenceEvidenceDemand.derived(
                        hash('1'),
                        hash('2'),
                        0L,
                        new blue.language.processor.closure.DocumentId(
                                consumer.value()),
                        "/peer",
                        hash('3'),
                        hash('4'),
                        0L);
        ClosureAttemptResult suspended = ClosureAttemptResult.needsResources(
                List.of(demand));
        blue.coordination.api.ManagedEpochApplicationWork work =
                blue.coordination.api.ManagedEpochApplicationWork.identified(
                        hash('5'),
                        hash('6'),
                        hash('7'),
                        source,
                        1L,
                        consumer,
                        hash('8'),
                        "/peer",
                        1L,
                        0L,
                        blue.coordination.api.ExactValue.verified(
                                new blue.language.model.Node().value(
                                        "consumer-before"))
                                .blueId(),
                        0L);
        blue.coordination.api.ManagedEpochApplicationAttempt retained =
                new blue.coordination.api.ManagedEpochApplicationAttempt(
                        work,
                        suspended,
                        false,
                        false,
                        Optional.empty(),
                        automaticRetryCount,
                        stopReason,
                        status == null
                                ? List.of()
                                : List.of(new blue.coordination.api
                                        .ManagedEpochApplicationAttempt
                                        .ManagedOccurrenceResolutionIssue(
                                                demand.demandIdentity(),
                                                status,
                                                diagnostic)));
        ProcessingDrainReceipt receipt = new ProcessingDrainReceipt(
                List.of(),
                Map.of(),
                Map.of(),
                null,
                false,
                false,
                0L,
                0L,
                List.of(),
                List.of(retained));

        Object owner = new Object();
        try (SdkCoordinationRuntime runtime = SdkCoordinationRuntime.create(
                owner, null, null, ExactNodeProvider.empty(), false)) {
            DefaultCoordinationEngine engine =
                    (DefaultCoordinationEngine) runtime.engine();
            return new SdkDrainResultMapper(runtime, engine)
                    .map(receipt)
                    .managedEpochApplicationAttempts()
                    .get(0);
        }
    }

    private static String hash(char digit) {
        return "sha256:" + String.valueOf(digit).repeat(64);
    }
}
