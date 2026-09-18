package blue.coordination.internal;

import blue.language.processor.closure.ClosureProcessResult;
import blue.language.processor.closure.ClosureProcessResultStorageCodec;
import blue.language.processor.closure.ClosureProcessResultStorageCodec.FrameKey;
import blue.language.processor.closure.ClosureProcessResultStorageCodec.VerifiedFrame;
import blue.language.provider.CyclicSetProof;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Complete immutable frames only; all validation remains in the final Language codec.
 * Its FORMAT 2 verified-handle contract permits exact-frame reuse across byte caps,
 * provided the frame fits the requesting cap and has the identical depth profile.
 * Other artifact families retain their own limits; this does not relax them.
 */
final class StoredClosureResultCodec {
    private final ClosureProcessResultStorageCodec results;
    private final Runnable coldDecodeObserver, coldEncodeObserver;
    private final boolean uncached;

    StoredClosureResultCodec(int maximumBytes, int maximumDepth, RootedStorageCache cache) {
        this(maximumBytes, maximumDepth, cache, null, null);
    }

    /** Observes actual full decode calls and public encoder fallbacks, never substitutes validation. */
    StoredClosureResultCodec(int maximumBytes, int maximumDepth, RootedStorageCache cache,
            Runnable fullDecodeObserver, Runnable fullEncodeObserver) {
        coldDecodeObserver = fullDecodeObserver; coldEncodeObserver = fullEncodeObserver; uncached = cache == null;
        String family = ClosureProcessResultStorageCodec.FORMAT + "/verified-frame/" + maximumDepth;
        results = cache == null ? new ClosureProcessResultStorageCodec(maximumBytes, maximumDepth)
                : new ClosureProcessResultStorageCodec(maximumBytes, maximumDepth, new ClosureProcessResultStorageCodec.Reuse() {
                    public VerifiedFrame getOrDecode(FrameKey key, Supplier<VerifiedFrame> fullyVerifiedDecode) {
                        return cache.decodeCanonical(family, key.bytes(), ignored -> {
                            observe(coldDecodeObserver);
                            return fullyVerifiedDecode.get();
                        }, frame -> frame.key().bytes(), frame -> frame.result(), frame -> frame.key().bytes().length);
                    }
                    public VerifiedFrame findEncoded(ClosureProcessResult value, int bytes, int depth) {
                        VerifiedFrame frame = cache.canonicalValue(family, value);
                        if (frame == null) observe(coldEncodeObserver);
                        return frame;
                    }
                });
    }

    /** Nested evidence uses this same configured final codec, not an independent raw decoder. */
    ClosureProcessResultStorageCodec configured() { return results; }

    byte[] encode(ClosureProcessResult value) {
        if (uncached) observe(coldEncodeObserver);
        return results.encode(value);
    }

    /** Language retains original proof evidence; the non-null compatibility lookup is never consulted. */
    byte[] encode(ClosureProcessResult value, Function<String, CyclicSetProof> externalProofs) {
        Objects.requireNonNull(externalProofs, "externalProofs");
        return encode(value);
    }

    ClosureProcessResult decode(byte[] bytes) {
        if (uncached) observe(coldDecodeObserver);
        return results.decode(bytes);
    }

    private static void observe(Runnable observer) { if (observer != null) observer.run(); }
}
