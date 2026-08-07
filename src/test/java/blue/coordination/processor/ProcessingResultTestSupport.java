package blue.coordination.processor;

import blue.language.Blue;
import blue.language.model.Node;
import blue.language.processor.DocumentProcessingResult;
import blue.language.processor.ProcessorDiagnostic;
import blue.language.processor.ProcessorErrorCategory;
import blue.language.processor.ProcessorStatus;
import blue.language.merge.ResolvedSnapshot;
import blue.language.identity.DirectBlueIdCalculator;

/**
 * Test-only views over the final five-field Contracts 1.0 process result.
 *
 * <p>Snapshots and resolved documents are deliberately derived out of band;
 * neither is a semantic ProcessResult field.</p>
 */
public final class ProcessingResultTestSupport {
    private ProcessingResultTestSupport() {
    }

    public static String diagnosticMessage(DocumentProcessingResult result) {
        ProcessorDiagnostic diagnostic = result != null ? result.diagnostic() : null;
        return diagnostic != null && diagnostic.message() != null
                ? diagnostic.message()
                : "";
    }

    public static ProcessorErrorCategory diagnosticCategory(
            DocumentProcessingResult result) {
        ProcessorDiagnostic diagnostic = result != null ? result.diagnostic() : null;
        return diagnostic != null ? diagnostic.category() : null;
    }

    public static boolean isCapabilityFailure(DocumentProcessingResult result) {
        return result != null && result.status() == ProcessorStatus.CAPABILITY_FAILURE;
    }

    public static String blueId(DocumentProcessingResult result) {
        return DirectBlueIdCalculator.calculateBlueId(result.document());
    }

    public static ResolvedSnapshot snapshot(Blue blue,
                                            DocumentProcessingResult result) {
        return blue.resolveToSnapshot(result.document());
    }

    public static ResolvedSnapshot snapshot(
            CoordinationTestRuntime runtime,
            DocumentProcessingResult result) {
        return runtime.resolveToSnapshot(result.document());
    }

    public static Node resolvedDocument(Blue blue,
                                        DocumentProcessingResult result) {
        return snapshot(blue, result).resolvedRoot();
    }

    public static Node resolvedDocument(
            CoordinationTestRuntime runtime,
            DocumentProcessingResult result) {
        return snapshot(runtime, result).resolvedRoot();
    }
}
