package blue.coordination.examples.support;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Once-stored event-inventory evidence, independent of journal row count. */
public final class MyOsEventInventoryRegistry {

    private final Map<String, String> inventoryByEntry = new LinkedHashMap<>();
    private final Map<String, String> entryByInventory = new LinkedHashMap<>();
    private long publicationVersion;

    public synchronized void record(
            String entryBlueId,
            String inventoryIdentity) {
        publish(prepareRecord(entryBlueId, inventoryIdentity));
    }

    synchronized PreparedRecord prepareRecord(
            String entryBlueId,
            String inventoryIdentity) {
        String entry = text(entryBlueId, "entryBlueId");
        String inventory = text(inventoryIdentity, "inventoryIdentity");
        String priorInventory = inventoryByEntry.get(entry);
        if (priorInventory != null && !priorInventory.equals(inventory)) {
            throw new IllegalStateException(
                    "Entry was stored with conflicting event inventories");
        }
        String priorEntry = entryByInventory.get(inventory);
        if (priorEntry != null && !priorEntry.equals(entry)) {
            throw new IllegalStateException(
                    "Inventory identity unexpectedly names two entries");
        }
        return new PreparedRecord(
                this,
                publicationVersion,
                priorInventory == null
                        ? Math.addExact(publicationVersion, 1L)
                        : publicationVersion,
                entry,
                inventory,
                priorInventory == null);
    }

    synchronized void validate(PreparedRecord record) {
        PreparedRecord checked = Objects.requireNonNull(record, "record");
        if (checked.owner != this) {
            throw new IllegalArgumentException(
                    "Prepared inventory record belongs to another registry");
        }
        if (checked.basePublicationVersion != publicationVersion) {
            throw new IllegalStateException(
                    "Prepared inventory record is stale");
        }
    }

    synchronized void publish(PreparedRecord record) {
        validate(record);
        publishPreparedUnchecked(record);
    }

    synchronized void publishPreparedUnchecked(PreparedRecord record) {
        if (!record.insert) {
            return;
        }
        inventoryByEntry.put(record.entry, record.inventory);
        entryByInventory.put(record.inventory, record.entry);
        publicationVersion = record.resultingPublicationVersion;
    }

    public synchronized int storedInventoryCount() {
        return entryByInventory.size();
    }

    public synchronized String requireInventory(String entryBlueId) {
        String value = inventoryByEntry.get(text(entryBlueId, "entryBlueId"));
        if (value == null) throw new IllegalArgumentException("Unknown entry");
        return value;
    }

    public synchronized MyOsEventInventoryRegistry copy() {
        MyOsEventInventoryRegistry result = new MyOsEventInventoryRegistry();
        result.inventoryByEntry.putAll(inventoryByEntry);
        result.entryByInventory.putAll(entryByInventory);
        result.publicationVersion = publicationVersion;
        return result;
    }

    static final class PreparedRecord {
        private final MyOsEventInventoryRegistry owner;
        private final long basePublicationVersion;
        private final long resultingPublicationVersion;
        private final String entry;
        private final String inventory;
        private final boolean insert;

        private PreparedRecord(
                MyOsEventInventoryRegistry owner,
                long basePublicationVersion,
                long resultingPublicationVersion,
                String entry,
                String inventory,
                boolean insert) {
            this.owner = Objects.requireNonNull(owner, "owner");
            this.basePublicationVersion = basePublicationVersion;
            this.resultingPublicationVersion = resultingPublicationVersion;
            this.entry = Objects.requireNonNull(entry, "entry");
            this.inventory = Objects.requireNonNull(inventory, "inventory");
            this.insert = insert;
        }
    }

    private static String text(String value, String label) {
        String checked = Objects.requireNonNull(value, label);
        if (checked.isBlank()) throw new IllegalArgumentException(label);
        return checked;
    }
}
