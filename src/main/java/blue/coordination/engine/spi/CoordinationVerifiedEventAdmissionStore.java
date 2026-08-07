package blue.coordination.engine.spi;

import blue.coordination.engine.api.CoordinationVerifiedEventAdmission;
import blue.coordination.engine.memory.CoordinationEventAdmissionReceipt;

/**
 * Optional trusted fast path for atomic content-addressed event admission.
 * Stores without this extension continue through the portable verifier path.
 */
public interface CoordinationVerifiedEventAdmissionStore
        extends CoordinationFragmentStore {

    CoordinationEventAdmissionReceipt admitVerifiedEvent(
            CoordinationVerifiedEventAdmission admission);
}
