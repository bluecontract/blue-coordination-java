package blue.coordination.processor.bex;

import blue.bex.value.BexValue;
import blue.bex.value.BexValues;
import blue.language.processor.ExactEventIdentityEvidence;
import blue.language.processor.registry.RuntimeBlueIds;
import blue.language.snapshot.FrozenNode;
import java.util.Collections;

/** Preserves an embedded bridge's exact edge and its admitted occurrence content. */
final class BexHandlerEventBinding {
    private BexHandlerEventBinding() { }

    static BexValue bind(FrozenNode handler, ExactEventIdentityEvidence occurrence) {
        FrozenNode type = handler.getType();
        FrozenNode reference = handler.getProperties() == null
                ? null : handler.getProperties().get("event");
        if (occurrence == null || type == null
                || !RuntimeBlueIds.EMBEDDED_EVENT_DELIVERY.equals(type.getReferenceBlueId())
                || reference == null || !reference.isReferenceOnly()
                || !reference.getReferenceBlueId().equals(occurrence.eventBlueId())
                || occurrence.frozenEvent().isReferenceOnly()) {
            return BexValues.frozen(handler);
        }
        // Contracts has already authenticated this occurrence. Its content is
        // invocation input and need not exist in an external provider. Keep the
        // canonical wrapper/reference intact; supply the body only as the
        // semantic cursor used by BEX member reads.
        BexValue payload = BexValues.exact(occurrence.frozenEvent(),
                occurrence.frozenEvent(), occurrence.eventBlueId());
        return BexValues.admittedExact(handler, handler.blueId(),
                BexValues.map(Collections.singletonMap("event", payload)));
    }
}
