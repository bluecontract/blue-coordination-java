package blue.coordination.engine.fastpath;

import blue.language.model.wire.JsonPointer;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Canonical immutable active/delivery paths for one frozen delivery plan. */
public final class ActivePathSet {
    private static final char[] HEX = "0123456789abcdef".toCharArray();
    private static final String IDENTITY_VERSION =
            "blue.coordination/reference-cut/active-paths/1";

    private final List<String> paths;
    private final Set<String> exact;
    private final Set<String> enteredAncestors;
    private final String identity;

    private ActivePathSet(List<String> paths) {
        this.paths = Collections.unmodifiableList(paths);
        this.exact = Collections.unmodifiableSet(
                new LinkedHashSet<String>(paths));
        LinkedHashSet<String> ancestors = new LinkedHashSet<String>();
        for (String path : paths) {
            addAncestors(path, ancestors);
        }
        this.enteredAncestors = Collections.unmodifiableSet(ancestors);
        this.identity = identity(paths);
    }

    public static ActivePathSet of(Collection<String> supplied) {
        return new ActivePathSet(canonicalPaths(supplied));
    }

    static List<String> canonicalPaths(Collection<String> supplied) {
        Objects.requireNonNull(supplied, "supplied");
        LinkedHashSet<String> canonical = new LinkedHashSet<String>();
        canonical.add(JsonPointer.ROOT);
        for (String path : supplied) {
            canonical.add(JsonPointer.canonicalize(
                    Objects.requireNonNull(path, "path")));
        }
        List<String> ordered = new ArrayList<String>(canonical);
        ordered.sort(Comparator
                .comparingInt(ActivePathSet::depth)
                .thenComparing(Comparator.naturalOrder()));
        return Collections.unmodifiableList(ordered);
    }

    public List<String> paths() {
        return paths;
    }

    /** Stable identity of the complete canonical active-path surface. */
    public String identity() {
        return identity;
    }

    public boolean contains(String path) {
        return exact.contains(JsonPointer.canonicalize(path));
    }

    /** Whether an active path is at or below {@code ancestor}. */
    public boolean enters(String ancestor) {
        String canonical = JsonPointer.canonicalize(ancestor);
        return enteredAncestors.contains(canonical);
    }

    /** Number of distinct preindexed ancestors, useful for bounded evidence. */
    int enteredAncestorCount() {
        return enteredAncestors.size();
    }

    public static int depth(String pointer) {
        return JsonPointer.split(pointer).size();
    }

    private static void addAncestors(
            String canonicalPath,
            Set<String> destination) {
        destination.add(JsonPointer.ROOT);
        if (JsonPointer.ROOT.equals(canonicalPath)) return;
        for (int index = 1; index < canonicalPath.length(); index++) {
            if (canonicalPath.charAt(index) == '/') {
                destination.add(canonicalPath.substring(0, index));
            }
        }
        destination.add(canonicalPath);
    }

    private static String identity(List<String> canonicalPaths) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            add(digest, IDENTITY_VERSION);
            for (String path : canonicalPaths) add(digest, path);
            byte[] bytes = digest.digest();
            StringBuilder result = new StringBuilder(bytes.length * 2);
            for (byte value : bytes) {
                int unsigned = value & 0xff;
                result.append(HEX[unsigned >>> 4]);
                result.append(HEX[unsigned & 0x0f]);
            }
            return IDENTITY_VERSION + ":" + result;
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static void add(MessageDigest digest, String value) {
        byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
        digest.update((byte) (encoded.length >>> 24));
        digest.update((byte) (encoded.length >>> 16));
        digest.update((byte) (encoded.length >>> 8));
        digest.update((byte) encoded.length);
        digest.update(encoded);
    }
}
