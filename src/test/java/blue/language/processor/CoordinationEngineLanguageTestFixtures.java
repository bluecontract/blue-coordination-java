package blue.language.processor;

/** Test-only access to package-scoped platform result construction. */
public final class CoordinationEngineLanguageTestFixtures {

    private CoordinationEngineLanguageTestFixtures() {
    }

    public static PlatformProcessingResult platformResult(
            VerifiedExecutionEvidence evidence,
            DocumentProcessingResult processResult) {
        PlatformCommitCompanion companion = PlatformCommitCompanion.of(
                evidence,
                processResult,
                SubscriptionDelta.empty());
        return new PlatformProcessingResult(processResult, companion);
    }
}
