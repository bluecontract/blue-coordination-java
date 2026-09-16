package blue.coordination.internal;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Counts complete decoder calls for actual authenticated receipts, not an elapsed-time proxy. */
final class StoredPublicationReceiptReuseTest {
    @Test void onlySuccessfulCanonicalDecodesAreReusedAndEncodeReturnsDetachedBytes() throws Exception {
        try (var f = new Fixture(); var reuse = new StoredPublicationReceiptReuse()) {
            var supplied = f.encoded.get(0).clone();
            var first = reuse.decode(supplied, f::decode);
            supplied[0] ^= 1;
            assertSame(first, reuse.decode(f.encoded.get(0), f::decode));
            assertEquals(1, f.decodes.get());
            byte[] cached = reuse.encode(first, ignored -> { fail("Exact verified object should reuse canonical bytes"); return null; });
            assertArrayEquals(f.encoded.get(0), cached);
            cached[0] ^= 1;
            assertArrayEquals(f.encoded.get(0), reuse.encode(first, ignored -> null));
            var separate = f.decode(f.encoded.get(0));
            var encodes = new AtomicInteger();
            assertArrayEquals(f.encoded.get(0), reuse.encode(separate, value -> {
                encodes.incrementAndGet(); return f.codec.encodePublication(value, f.scope::addressOf);
            }));
            assertEquals(1, encodes.get(), "Value equality is not authenticated object provenance");
            assertFalse(reuse.contains(separate));
            var malformed = Arrays.copyOf(f.encoded.get(0), f.encoded.get(0).length + 1);
            int before = f.decodes.get();
            assertThrows(RuntimeException.class, () -> reuse.decode(malformed, f::decode));
            assertThrows(RuntimeException.class, () -> reuse.decode(malformed, f::decode));
            assertEquals(before + 2, f.decodes.get(), "Failures must not create a positive or negative cache entry");
            assertSame(first, reuse.decode(f.encoded.get(0), f::decode));
            assertEquals(1, reuse.retainedEntries());
        }
    }

    @Test void entryAndByteEvictionRetireDeepReceiptIdentityAndOversizeStillDecodes() throws Exception {
        try (var f = new Fixture()) {
            long largest = Math.max(f.encoded.get(0).length, f.encoded.get(1).length);
            for (var reuse : List.of(new StoredPublicationReceiptReuse(1, Long.MAX_VALUE),
                    new StoredPublicationReceiptReuse(4, largest))) {
                try (reuse) {
                    var first = reuse.decode(f.encoded.get(0), f::decode);
                    Object epoch = reuse.retentionEpoch();
                    var second = reuse.decode(f.encoded.get(1), f::decode);
                    assertNotSame(epoch, reuse.retentionEpoch());
                    assertFalse(reuse.contains(first)); assertTrue(reuse.contains(second));
                    assertEquals(1, reuse.retainedEntries());
                    assertEquals(f.encoded.get(1).length, reuse.retainedEncodedBytes());
                    var reread = reuse.decode(f.encoded.get(0), f::decode);
                    assertNotSame(first, reread);
                    assertEquals(first.publicationIdentity(), reread.publicationIdentity());
                }
            }
            for (var reuse : List.of(new StoredPublicationReceiptReuse(0, Long.MAX_VALUE),
                    new StoredPublicationReceiptReuse(1, f.encoded.get(0).length - 1L))) {
                try (reuse) {
                    var first = reuse.decode(f.encoded.get(0), f::decode);
                    assertNotSame(first, reuse.decode(f.encoded.get(0), f::decode));
                    assertEquals(0, reuse.retainedEntries()); assertEquals(0, reuse.retainedEncodedBytes());
                }
            }
        }
    }

    @Test void closingScopeDropsAllRetainedStateAndCannotLeakIdentityIntoAnotherOwner() throws Exception {
        try (var f = new Fixture()) {
            var original = new StoredPublicationReceiptReuse();
            var first = original.decode(f.encoded.get(0), f::decode); Object epoch = original.retentionEpoch();
            original.close();
            assertEquals(0, original.retainedEntries()); assertEquals(0, original.retainedEncodedBytes());
            assertNotSame(epoch, original.retentionEpoch()); assertFalse(original.contains(first));
            assertThrows(IllegalStateException.class, () -> original.decode(f.encoded.get(0), f::decode));
            assertThrows(IllegalStateException.class, () -> original.encode(first, ignored -> null));
            try (var next = new StoredPublicationReceiptReuse()) {
                var restored = next.decode(f.encoded.get(0), f::decode);
                assertNotSame(first, restored); assertEquals(first.publicationIdentity(), restored.publicationIdentity());
            }
        }
    }

    private static final class Fixture implements AutoCloseable {
        private static final int MAX = 32 * 1024 * 1024;
        final ManagedRepresentationVerificationMemoTest.Scenario scenario = new ManagedRepresentationVerificationMemoTest.Scenario();
        final DocumentSessionStorage sessions = new DocumentSessionStorage(new DocumentSessionStorageTest.Bytes(),
                new DocumentSessionStorage.Limits(MAX, 256, 256L * 1024 * 1024));
        final DocumentSessionStorage.OpenScope scope = sessions.openScope();
        final PublicationReceiptStorageCodec codec = new PublicationReceiptStorageCodec(MAX, 256);
        final List<byte[]> encoded = new ArrayList<>();
        final AtomicInteger decodes = new AtomicInteger();
        Fixture() throws Exception {
            for (var transition : scenario.chain().transitions())
                encoded.add(codec.encodePublication(scenario.publication(transition), sessions::retainView));
            assertEquals(2, encoded.size()); assertFalse(Arrays.equals(encoded.get(0), encoded.get(1)));
        }
        ContractsClosurePublicationReceipt decode(byte[] bytes) { decodes.incrementAndGet(); return codec.decodePublication(bytes, scope); }
        public void close() { scope.close(); scenario.close(); }
    }
}
