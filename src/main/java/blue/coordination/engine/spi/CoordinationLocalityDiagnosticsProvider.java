package blue.coordination.engine.spi;

import blue.coordination.engine.api.LocalityDiagnostics;
import blue.language.provider.NodeProvider;

/**
 * Strict invocation provider that exposes authoritative physical-read
 * diagnostics after one PROCESS attempt.
 */
public interface CoordinationLocalityDiagnosticsProvider
        extends NodeProvider {

    /**
     * Returns an immutable snapshot of every request-local provider read.
     *
     * @return diagnostics accumulated by this invocation provider
     */
    LocalityDiagnostics diagnostics();
}
