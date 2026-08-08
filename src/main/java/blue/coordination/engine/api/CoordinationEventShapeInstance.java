package blue.coordination.engine.api;

import blue.language.model.Node;
import blue.language.snapshot.FrozenNode;

import java.util.Objects;

/** First-seen exact event plus its already verified canonical admission. */
public final class CoordinationEventShapeInstance {
    private final CoordinationVerifiedEventAdmission admission;
    private final int changedLocalFragmentCount;
    private final int reusedLocalFragmentCount;

    CoordinationEventShapeInstance(
            CoordinationVerifiedEventAdmission admission,
            int changedLocalFragmentCount,
            int reusedLocalFragmentCount) {
        this.admission = Objects.requireNonNull(admission, "admission");
        if (changedLocalFragmentCount <= 0) {
            throw new IllegalArgumentException(
                    "changedLocalFragmentCount must be positive");
        }
        if (reusedLocalFragmentCount < 0) {
            throw new IllegalArgumentException(
                    "reusedLocalFragmentCount must be non-negative");
        }
        this.changedLocalFragmentCount = changedLocalFragmentCount;
        this.reusedLocalFragmentCount = reusedLocalFragmentCount;
    }

    public String eventBlueId() {
        return admission.key().eventBlueId();
    }

    public Node exactEvent() {
        return admission.exactEvent();
    }

    public FrozenNode frozenExactEvent() {
        return admission.frozenExactEvent();
    }

    public CoordinationVerifiedEventAdmission admission() {
        return admission;
    }

    public int changedLocalFragmentCount() {
        return changedLocalFragmentCount;
    }

    public int reusedLocalFragmentCount() {
        return reusedLocalFragmentCount;
    }
}
