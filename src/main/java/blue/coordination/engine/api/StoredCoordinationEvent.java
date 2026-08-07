package blue.coordination.engine.api;

import blue.language.processor.ExternalOrderKey;

import java.util.Objects;

/** Verified event graph admitted once and reusable across session plans. */
public final class StoredCoordinationEvent {

    private final String eventBlueId;
    private final String fragmentInventoryIdentity;
    private final ExternalOrderKey orderKey;

    public StoredCoordinationEvent(
            String eventBlueId,
            String fragmentInventoryIdentity,
            ExternalOrderKey orderKey) {
        this.eventBlueId = requireText(eventBlueId, "eventBlueId");
        this.fragmentInventoryIdentity = requireText(
                fragmentInventoryIdentity, "fragmentInventoryIdentity");
        this.orderKey = Objects.requireNonNull(orderKey, "orderKey");
    }

    public String eventBlueId() {
        return eventBlueId;
    }

    public String fragmentInventoryIdentity() {
        return fragmentInventoryIdentity;
    }

    public ExternalOrderKey orderKey() {
        return orderKey;
    }

    private static String requireText(String value, String name) {
        String checked = Objects.requireNonNull(value, name);
        if (checked.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be empty");
        }
        return checked;
    }
}

