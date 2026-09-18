package blue.coordination.internal;

import blue.coordination.api.storage.CoordinationImmutableObjectStore;
import blue.coordination.api.storage.CoordinationObjectStorageException;
import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Optional;

/** Test-only immutable files. No map nodes, root manifest, or decoded-value cache. */
final class FileCoordinationObjectStore implements CoordinationImmutableObjectStore {
    private final Path directory;
    private final int maximumBytes;
    int reads;
    FileCoordinationObjectStore(Path directory, int maximumBytes) {
        this.directory = directory;
        this.maximumBytes = maximumBytes;
        try { Files.createDirectories(directory); }
        catch (IOException failure) { throw storage(failure); }
    }
    private Path file(String digest) {
        if (digest == null || !digest.matches("[0-9a-f]{64}")) {
            throw new CoordinationObjectStorageException("Invalid fixture address");
        }
        return directory.resolve(digest);
    }
    @Override public byte[] putIfAbsent(String digest, byte[] bytes) {
        if (bytes.length > maximumBytes || !PersistentMapStorage.digest(bytes).equals(digest)) {
            throw new CoordinationObjectStorageException("Invalid fixture bytes");
        }
        Path pending = null;
        try {
            pending = Files.createTempFile(directory, "pending-", ".node");
            Files.write(pending, bytes);
            try { Files.createLink(file(digest), pending); }
            catch (FileAlreadyExistsException duplicate) { /* compare exact bytes below */ }
            byte[] retained = read(digest, maximumBytes).orElseThrow();
            if (!Arrays.equals(bytes, retained)) throw new CoordinationObjectStorageException("Fixture collision");
            return retained;
        } catch (IOException failure) { throw storage(failure); }
        finally {
            if (pending != null) {
                try { Files.deleteIfExists(pending); }
                catch (IOException failure) { throw storage(failure); }
            }
        }
    }
    @Override public Optional<byte[]> get(String digest, int maximum) {
        reads++;
        return read(digest, Math.min(maximum, maximumBytes));
    }
    private Optional<byte[]> read(String digest, int maximum) {
        try {
            Path selected = file(digest);
            if (!Files.exists(selected)) return Optional.empty();
            if (Files.size(selected) > maximum) throw new CoordinationObjectStorageException("Oversized fixture row");
            return Optional.of(Files.readAllBytes(selected));
        } catch (IOException failure) { throw storage(failure); }
    }
    private static CoordinationObjectStorageException storage(IOException failure) {
        return new CoordinationObjectStorageException("Fixture filesystem unavailable", failure);
    }
}
