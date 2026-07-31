package blue.coordination.processor.bex;

import blue.language.snapshot.FrozenNode;

/**
 * Optional diagnostic observer for the original Processing Event identity
 * exposed by Coordination workflows and hosted BEX Compute programs.
 *
 * <p>The observed node is the immutable snapshot returned by Language's
 * {@code ProcessorExecutionContext.frozenProcessEvent()} boundary. The
 * exposed BlueId is the identity that the Coordination boundary supplies to
 * the corresponding consumer. Production execution installs no observer by
 * default and therefore performs no diagnostic snapshot or identity work.</p>
 */
public interface ProcessingEventIdentityObserver {

    /**
     * Records one exact Processing Event exposure.
     *
     * @param processingEvent immutable Language-owned Processing Event snapshot
     * @param exposedBlueId exact identity exposed at the boundary
     * @param boundary Coordination boundary that exposed the value
     */
    void observe(
            FrozenNode processingEvent,
            String exposedBlueId,
            Boundary boundary);

    /** Coordination boundaries that can expose the original Processing Event. */
    enum Boundary {
        /** The selected Sequential Workflow handler invocation. */
        WORKFLOW,
        /** The hosted BEX {@code $processingEvent} binding. */
        BEX_BINDING
    }
}
