package blue.coordination.internal;

import java.nio.file.Files;
import java.nio.file.Path;

/** Fresh JVM reads only immutable files and the explicitly supplied physical root. */
public final class StoredPersistentMapRestartMain {
    public static void main(String[] args) throws Exception {
        FileCoordinationObjectStore store = new FileCoordinationObjectStore(Path.of(args[0]), 4096);
        var before = StoredMapFixtures.open(store, Files.readAllBytes(Path.of(args[1])));
        if (args.length == 4 && "malformed-size".equals(args[3])) {
            if (before.size() != Integer.MAX_VALUE) throw new AssertionError("Forged count was not exercised");
            for (Runnable traversal : java.util.List.<Runnable>of(before::keys, before::values, before::entries)) {
                try {
                    traversal.run();
                    throw new AssertionError("Malformed child dimensions were accepted");
                } catch (blue.coordination.api.storage.CoordinationObjectStorageException expected) {
                    if (!expected.getMessage().contains("authenticated parent dimensions")) throw expected;
                }
            }
            System.out.println("MALFORMED_COUNT_REJECTED");
            return;
        }
        if (before.size() != 127 || !"value-63".equals(before.get(63))) {
            throw new AssertionError("Cold root changed");
        }
        var after = before.put(63, "child-updated").map().remove(3).map();
        if (!"value-63".equals(before.get(63)) || before.get(3) == null) {
            throw new AssertionError("Old physical root changed");
        }
        Files.write(Path.of(args[2]), after.storedRootDescriptor());
        System.out.println("COLD_MAP_OK reads=" + store.reads);
    }
    private StoredPersistentMapRestartMain() { }
}
