package blue.coordination.internal;

import blue.coordination.api.storage.CoordinationImmutableObjectStore;
import blue.coordination.api.storage.CoordinationObjectStorageException;
import blue.coordination.sdk.ActivationPolicy;
import blue.language.processor.closure.ClosureProcessResultStorageCodec;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class StoredClosureReceiptReferenceCodecTest {
    private static final int MAX = 32 * 1024 * 1024;
    private static final String PAYLOAD = "blue-coordination/publication-receipt-storage/1";
    private static final String REFERENCE = "blue-coordination/publication-closure-reference/1";
    private static final DocumentSessionStorage.Limits SESSION_LIMITS = new DocumentSessionStorage.Limits(MAX, 256, 256L * 1024 * 1024);
    private static final PersistentMapStorage.Limits MAP_LIMITS = new PersistentMapStorage.Limits(40 * 1024 * 1024, 4096, MAX, 4096, 8);
    private static List<ContractsClosurePublicationReceipt> receipts;

    @Test void controlledWarmReferenceUsesOwnerAuthenticatedCertificateWithoutReadingPayloadAgain() {
        try (var binding = new Binding(new Bytes()); var cache = new RootedStorageCache(256L * 1024 * 1024, 8, 256L * 1024 * 1024)) {
            String family = "test/complete-publication/" + MAX;
            int[] decodes = {0};
            var payloads = new PersistentMapCodec<ContractsClosurePublicationReceipt>() {
                public String identity() { return binding.legacy.identity(); }
                public ContractsClosurePublicationReceipt prepareForStorage(ContractsClosurePublicationReceipt value) {
                    return binding.legacy.prepareForStorage(value);
                }
                public byte[] encode(ContractsClosurePublicationReceipt value) {
                    byte[] retained = cache.canonicalEncoding(family, value);
                    return retained == null ? binding.legacy.encode(value) : retained;
                }
                public ContractsClosurePublicationReceipt decode(byte[] frame) {
                    return cache.decodeCanonical(family, frame, bytes -> {
                        decodes[0]++; return binding.legacy.decode(bytes);
                    }, binding.legacy::encode);
                }
            };
            var controlled = RootedEngineStorage.controlledNamespace(binding.bytes);
            StoredClosureReceiptReferenceCodec.RetainedCanonical proof = (value, digest, length) ->
                    cache.hasCanonicalEncoding(family, value, digest, length);
            byte[] descriptor;
            try (var references = new StoredClosureReceiptReferenceCodec(controlled, MAX, payloads, proof)) {
                descriptor = references.prepareEncoding(receipts.get(0)).consume(references);
                var first = references.decode(descriptor); int reads = binding.bytes.reads.size();
                int coldDecodes = decodes[0];
                for (int n = 0; n < 100; n++) {
                    assertSame(first, references.decode(descriptor));
                    assertArrayEquals(descriptor, references.encode(first));
                }
                assertEquals(reads, binding.bytes.reads.size(), "100 warm reference reads must transfer zero payload bytes");
                assertEquals(coldDecodes, decodes[0]);
                cache.clear(); references.decode(descriptor);
                assertTrue(binding.bytes.reads.size() > reads); assertEquals(coldDecodes + 1, decodes[0]);
            }
            int reads = binding.bytes.reads.size();
            try (var newOwner = new StoredClosureReceiptReferenceCodec(controlled, MAX, payloads, proof)) {
                newOwner.decode(descriptor); assertTrue(binding.bytes.reads.size() > reads,
                        "The shared artifact does not authenticate storage in a new owner");
            }
            try (var strict = new StoredClosureReceiptReferenceCodec(binding.bytes, MAX, payloads, proof)) {
                strict.decode(descriptor); reads = binding.bytes.reads.size(); strict.decode(descriptor);
                assertTrue(binding.bytes.reads.size() > reads, "Raw/untrusted reads keep full integrity checks");
            }
            binding.bytes.records.clear();
            try (var newOwner = new StoredClosureReceiptReferenceCodec(controlled, MAX, payloads, proof)) {
                assertThrows(CoordinationObjectStorageException.class, () -> newOwner.decode(descriptor));
            }
        }
    }

    @BeforeAll static void realCompletedReceipts() throws Exception {
        var completed = new ArrayList<ContractsClosurePublicationReceipt>();
        try (var fixture = new DocumentSessionStorageTest.Fixture()) {
            var source = fixture.start(DocumentSessionStorageTest.resource("source.yaml")
                    + "\npadding: " + "x".repeat(4096) + "\n", "rcp2/source", ActivationPolicy.fromNow());
            for (int index = 0; index < 7; index++) {
                var entry = fixture.append(source, "rcp2/source", "tick");
                var drain = fixture.engine.processRootInput(source.id(), fixture.engine.auditTimelineEntry(entry.blueId()).orElseThrow());
                var attempt = drain.contractsAttemptsFor(entry.blueId()).get(0);
                var receipt = fixture.engine.documents().closurePublicationReceipt(attempt.publicationIdentity()).orElseThrow();
                assertTrue(receipt.commits());
                assertEquals(index + 1L, source.snapshot().longAt("/counter"));
                completed.add(receipt);
            }
        }
        receipts = completed.stream().sorted(Comparator.comparing(ContractsClosurePublicationReceipt::publicationIdentity)).toList();
    }

    @Test void rebranchAndRotationsCopySmallReferencesNotLargeUnchangedReceipts() throws Exception {
        var bytes = new Bytes(); byte[] root;
        try (var binding = new Binding(bytes)) {
            var map = binding.indexes.openClosures(null);
            for (var receipt : receipts) map = map.put(receipt.publicationIdentity(), receipt).map();
            assertEquals(receipts.size(), map.size());
            var retained = bytes.receiptWrites();
            assertEquals(receipts.size(), retained.size());
            assertTrue(retained.values().stream().allMatch(count -> count == 1), "One payload PUT per newly inserted receipt");
            assertTrue(bytes.records.values().stream().filter(StoredClosureReceiptReferenceCodecTest::isReceipt)
                    .allMatch(value -> value.length > 4096), "Fixture exercises genuinely large valid receipts");
            var nodes = closureNodes(bytes);
            assertTrue(nodes.size() > receipts.size(), "Sorted insertion must rewrite ancestors and rotate");
            assertTrue(nodes.stream().allMatch(value -> value.length < 1024), "Rebranched PMN1 nodes contain only small references");
            bytes.reads.clear();
            assertTrue(map.containsKeyWithoutValue(receipts.get(3).publicationIdentity()));
            assertFalse(bytes.reads.stream().anyMatch(retained::containsKey), "Structural membership never loads receipt payloads");
            map = map.remove(receipts.get(3).publicationIdentity()).map();
            assertEquals(retained, bytes.receiptWrites(), "Deletion/rebalancing retains no unchanged receipt payload again");
            assertFalse(bytes.reads.stream().anyMatch(retained::containsKey), "Structural deletion never decodes receipts");
            assertEquals(receipts.size() - 1, map.size());
            root = map.storedRootDescriptor();
        }
        try (var cold = new Binding(bytes.copy())) {
            int before = cold.bytes.writes;
            var map = cold.indexes.openClosures(root);
            assertEquals(receipts.size() - 1, map.size());
            for (var receipt : receipts) {
                if (receipt == receipts.get(3)) assertNull(map.get(receipt.publicationIdentity()));
                else assertReceipt(receipt, map.get(receipt.publicationIdentity()), cold);
            }
            assertEquals(before, cold.bytes.writes, "Cold open and canonical receipt checks do not write");
        }
    }

    @Test void descriptorAndColdCanonicalReadsKeepExactReceiptAndPerformNoWrites() {
        var bytes = new Bytes(); byte[] descriptor, payload;
        var original = receipts.get(0);
        try (var binding = new Binding(bytes)) {
            binding.references.prepareForStorage(original);
            descriptor = binding.references.encode(original);
            payload = binding.legacy.encode(original);
            assertTrue(descriptor.length < 256);
            assertTrue(bytes.records.containsKey(PersistentMapStorage.digest(payload)));
            int before = bytes.writes;
            assertArrayEquals(descriptor, binding.references.encode(original));
            assertEquals(before, bytes.writes);
        }
        try (var cold = new Binding(bytes.copy())) {
            int before = cold.bytes.writes;
            var restored = cold.references.decode(descriptor);
            assertNotSame(original, restored);
            assertReceipt(original, restored, cold);
            assertArrayEquals(payload, cold.legacy.encode(restored));
            assertArrayEquals(descriptor, cold.references.encode(restored));
            assertEquals(before, cold.bytes.writes);
        }
    }

    @Test void missingCorruptAndWrongLengthPayloadsFailBeforeReturningAReceipt() {
        var bytes = new Bytes(); byte[] descriptor;
        try (var binding = new Binding(bytes)) {
            binding.references.prepareForStorage(receipts.get(0));
            descriptor = binding.references.encode(receipts.get(0));
        }
        var reference = reference(descriptor);
        byte[] original = bytes.records.remove(reference.address());
        try (var cold = new Binding(bytes)) {
            assertThrows(CoordinationObjectStorageException.class, () -> cold.references.decode(descriptor));
            bytes.records.put(reference.address(), original.clone());
            bytes.records.get(reference.address())[0] ^= 1;
            assertThrows(CoordinationObjectStorageException.class, () -> cold.references.decode(descriptor));
            bytes.records.put(reference.address(), original);
            byte[] wrongLength = descriptor(reference.address(), reference.length() + 1, REFERENCE);
            assertThrows(CoordinationObjectStorageException.class, () -> cold.references.decode(wrongLength));
            assertReceipt(receipts.get(0), cold.references.decode(descriptor), cold);
        }
    }

    @Test void malformedForeignOrOversizedReferencesFailAndDoNotWrite() {
        var bytes = new Bytes(); byte[] original;
        try (var binding = new Binding(bytes)) {
            binding.references.prepareForStorage(receipts.get(0));
            original = binding.references.encode(receipts.get(0));
            var reference = reference(original);
            int before = bytes.writes;
            for (byte[] value : List.of(Arrays.copyOf(original, original.length - 1), Arrays.copyOf(original, original.length + 1),
                    descriptor(reference.address(), 0, REFERENCE), descriptor(reference.address(), -1, REFERENCE),
                    descriptor(reference.address(), MAX + 1, REFERENCE), descriptor(reference.address(), reference.length(), "foreign"))) {
                assertThrows(CoordinationObjectStorageException.class, () -> binding.references.decode(value));
            }
            assertThrows(CoordinationObjectStorageException.class, () -> binding.references.decode(new byte[257]));
            byte[] wrongKind = SessionStorageWire.encode(256, out -> out.text("not-a-publication-receipt"));
            String address = PersistentMapStorage.digest(wrongKind); bytes.records.put(address, wrongKind);
            assertThrows(CoordinationObjectStorageException.class,
                    () -> binding.references.decode(descriptor(address, wrongKind.length, REFERENCE)));
            assertEquals(before, bytes.writes);
        }
    }

    @Test void selectedReceiptReadChecksOriginalPayloadBoundNotOnlySmallDescriptor() {
        var bytes = new Bytes(); byte[] descriptor;
        try (var original = new Binding(bytes)) {
            original.references.prepareForStorage(receipts.get(0));
            descriptor = original.references.encode(receipts.get(0));
        }
        int length = reference(descriptor).length();
        try (var binding = new Binding(bytes); var bounded = new StoredClosureReceiptReferenceCodec(bytes, length - 1, binding.legacy)) {
            int reads = bytes.reads.size(), writes = bytes.writes;
            assertThrows(CoordinationObjectStorageException.class, () -> bounded.decode(descriptor));
            assertEquals(reads, bytes.reads.size(), "Reject forged/oversized row length before point materialization");
            assertThrows(CoordinationObjectStorageException.class, () -> bounded.encode(receipts.get(0)));
            assertEquals(writes, bytes.writes);
        }
    }

    @Test void payloadWriteFailureOrBadAcknowledgmentLeavesOriginalIndexRootUnchanged() {
        var bytes = new Bytes();
        try (var binding = new Binding(bytes)) {
            var original = binding.indexes.openClosures(null).put(receipts.get(0).publicationIdentity(), receipts.get(0)).map();
            byte[] root = original.storedRootDescriptor();
            bytes.failReceiptWrite = true;
            assertThrows(CoordinationObjectStorageException.class, () -> original.put(receipts.get(1).publicationIdentity(), receipts.get(1)));
            bytes.failReceiptWrite = false; bytes.badReceiptAcknowledgment = true;
            assertThrows(CoordinationObjectStorageException.class, () -> original.put(receipts.get(1).publicationIdentity(), receipts.get(1)));
            bytes.badReceiptAcknowledgment = false;
            assertArrayEquals(root, original.storedRootDescriptor());
            assertNull(original.get(receipts.get(1).publicationIdentity()));
            var next = original.put(receipts.get(1).publicationIdentity(), receipts.get(1)).map();
            assertEquals(2, next.size());
            assertReceipt(receipts.get(0), original.get(receipts.get(0).publicationIdentity()), binding);
            assertReceipt(receipts.get(1), next.get(receipts.get(1).publicationIdentity()), binding);
        }
    }

    @Test void oldInlineClosureBindingRejectsExplicitlyWithoutPayloadConversion() {
        var bytes = new Bytes(); byte[] oldRoot;
        try (var binding = new Binding(bytes)) {
            var codecs = new StoreIndexCodecs(bytes, MAP_LIMITS);
            var old = codecs.binding("publication/closure", EmbeddingBinding.TEXT_ORDER, codecs.text, binding.legacy).open(null);
            oldRoot = old.put(receipts.get(0).publicationIdentity(), receipts.get(0)).map().storedRootDescriptor();
        }
        try (var fresh = new Binding(bytes.copy())) {
            int writes = fresh.bytes.writes;
            var failure = assertThrows(CoordinationObjectStorageException.class, () -> fresh.indexes.openClosures(oldRoot));
            assertTrue(failure.getMessage().contains("Physical map binding mismatch"));
            assertEquals(writes, fresh.bytes.writes);
        }
    }

    @Test void descriptorCopiesAndClosedLifecycleDoNotLendWriteOrPublicationAuthority() {
        var bytes = new Bytes();
        try (var binding = new Binding(bytes)) {
            var value = receipts.get(0);
            byte[] unretained = binding.references.encode(value);
            assertEquals(0, bytes.writes, "Pure encoding is not retention");
            assertThrows(CoordinationObjectStorageException.class, () -> binding.references.decode(unretained));
            binding.references.prepareForStorage(value);
            byte[] expected = binding.references.encode(value), mutated = binding.references.encode(value);
            mutated[0] ^= 1;
            assertArrayEquals(expected, binding.references.encode(value));
            byte[] supplied = expected.clone();
            bytes.onNextGet = () -> supplied[0] ^= 1;
            var restored = binding.references.decode(supplied);
            assertArrayEquals(expected, binding.references.encode(restored), "Only owned validated descriptor bytes enter the one-value memo");
            binding.references.close();
            assertThrows(CoordinationObjectStorageException.class, () -> binding.references.encode(restored));
            assertThrows(CoordinationObjectStorageException.class, () -> binding.references.decode(expected));
            assertThrows(CoordinationObjectStorageException.class, () -> binding.references.prepareForStorage(value));
        }
    }

    @Test void preparationDoesNotCertifyIdentityButCanonicalDecodeCanReuseItsOwnedDescriptor() {
        var bytes = new Bytes();
        try (var binding = new Binding(bytes)) {
            int[] encodes = {0}; boolean[] rejectEncoding = {false};
            var delegate = new PersistentMapCodec<ContractsClosurePublicationReceipt>() {
                public String identity() { return binding.legacy.identity(); }
                public ContractsClosurePublicationReceipt prepareForStorage(ContractsClosurePublicationReceipt value) {
                    return binding.legacy.prepareForStorage(value);
                }
                public byte[] encode(ContractsClosurePublicationReceipt value) {
                    encodes[0]++;
                    if (rejectEncoding[0]) throw new CoordinationObjectStorageException("Encoder validation now rejects this value");
                    return binding.legacy.encode(value);
                }
                public ContractsClosurePublicationReceipt decode(byte[] encoded) { return binding.legacy.decode(encoded); }
            };
            try (var references = new StoredClosureReceiptReferenceCodec(bytes, MAX, delegate)) {
                var fresh = receipts.get(0);
                references.prepareForStorage(fresh);
                assertEquals(1, encodes[0]);
                rejectEncoding[0] = true;
                assertThrows(CoordinationObjectStorageException.class, () -> references.encode(fresh));
                assertEquals(2, encodes[0], "A successful PUT cannot suppress fresh value validation");
                rejectEncoding[0] = false;
                byte[] descriptor = references.encode(fresh);
                var restored = references.decode(descriptor);
                assertNotSame(fresh, restored);
                int before = encodes[0], writes = bytes.writes;
                rejectEncoding[0] = true;
                assertArrayEquals(descriptor, references.encode(restored));
                assertEquals(before, encodes[0], "Only the exact successfully decoded identity reuses its descriptor");
                assertThrows(CoordinationObjectStorageException.class, () -> references.encode(fresh));
                assertEquals(before + 1, encodes[0], "Semantic equality does not grant decoded identity provenance");
                assertEquals(writes, bytes.writes, "Encoding checks and their failures never write");
            }
        }
    }

    @Test void oneShotMapInsertionEncodesFreshPayloadOnceAndMatchesTheFormerSplitPath() {
        var fresh = receipts.get(0); byte[][] roots = new byte[2][], payloads = new byte[2][];
        for (int variant = 0; variant < 2; variant++) {
            try (var binding = new Binding(new Bytes())) {
                int[] freshEncodes = {0};
                var counted = new PersistentMapCodec<ContractsClosurePublicationReceipt>() {
                    public String identity() { return binding.legacy.identity(); }
                    public ContractsClosurePublicationReceipt prepareForStorage(ContractsClosurePublicationReceipt value) {
                        return binding.legacy.prepareForStorage(value);
                    }
                    public byte[] encode(ContractsClosurePublicationReceipt value) {
                        if (value == fresh) freshEncodes[0]++;
                        return binding.legacy.encode(value);
                    }
                    public ContractsClosurePublicationReceipt decode(byte[] bytes) { return binding.legacy.decode(bytes); }
                };
                try (var references = new StoredClosureReceiptReferenceCodec(binding.bytes, MAX, counted)) {
                    // Variant zero deliberately exercises the previous prepare-then-encode mutation path.
                    PersistentMapCodec<ContractsClosurePublicationReceipt> selected = variant == 1 ? references : new PersistentMapCodec<>() {
                        public String identity() { return references.identity(); }
                        public ContractsClosurePublicationReceipt prepareForStorage(ContractsClosurePublicationReceipt value) {
                            return references.prepareForStorage(value);
                        }
                        public byte[] encode(ContractsClosurePublicationReceipt value) { return references.encode(value); }
                        public ContractsClosurePublicationReceipt decode(byte[] bytes) { return references.decode(bytes); }
                    };
                    var codes = new StoreIndexCodecs(binding.bytes, MAP_LIMITS);
                    var map = codes.binding("publication/closure", EmbeddingBinding.TEXT_ORDER, codes.text, selected).open(null);
                    var stored = map.put(fresh.publicationIdentity(), fresh).map();
                    assertEquals(variant == 1 ? 1 : 2, freshEncodes[0], "Count the actual fresh payload encoder, not a cache wrapper");
                    assertEquals(1, binding.bytes.receiptWrites().size());
                    assertEquals(1, binding.bytes.receiptWrites().values().iterator().next().intValue());
                    roots[variant] = stored.storedRootDescriptor();
                    payloads[variant] = binding.bytes.records.get(binding.bytes.receiptWrites().keySet().iterator().next()).clone();
                    assertReceipt(fresh, stored.get(fresh.publicationIdentity()), binding);
                    int before = freshEncodes[0], writes = binding.bytes.writes;
                    references.encode(fresh);
                    assertEquals(before + 1, freshEncodes[0], "A later pure encode does not reuse the one-shot preparation");
                    assertEquals(writes, binding.bytes.writes); assertNull(map.get(fresh.publicationIdentity()));
                }
            }
        }
        assertArrayEquals(roots[0], roots[1], "The private physical descriptor and tree shape are unchanged");
        assertArrayEquals(payloads[0], payloads[1], "Both paths retain the exact complete receipt bytes");
    }

    @Test void preparedReceiptFrameSurvivesLaterFreshEncoderChangesWithoutGrantingIdentityReuse() {
        var fresh = receipts.get(0); boolean[] rejectFresh = {false};
        try (var binding = new Binding(new Bytes())) {
            var payloads = new PersistentMapCodec<ContractsClosurePublicationReceipt>() {
                public String identity() { return binding.legacy.identity(); }
                public ContractsClosurePublicationReceipt prepareForStorage(ContractsClosurePublicationReceipt value) {
                    return binding.legacy.prepareForStorage(value);
                }
                public byte[] encode(ContractsClosurePublicationReceipt value) {
                    if (value == fresh && rejectFresh[0]) throw new CoordinationObjectStorageException("Fresh encoder changed after snapshot");
                    return binding.legacy.encode(value);
                }
                public ContractsClosurePublicationReceipt decode(byte[] bytes) { return binding.legacy.decode(bytes); }
            };
            try (var references = new StoredClosureReceiptReferenceCodec(binding.bytes, MAX, payloads)) {
                var prepared = references.prepareEncoding(fresh);
                rejectFresh[0] = true;
                byte[] descriptor = prepared.consume(references);
                var restored = references.decode(descriptor);
                assertReceipt(fresh, restored, binding);
                assertArrayEquals(descriptor, references.encode(restored));
                int writes = binding.bytes.writes;
                assertThrows(CoordinationObjectStorageException.class, () -> references.encode(fresh));
                assertThrows(IllegalStateException.class, () -> prepared.consume(references));
                assertEquals(writes, binding.bytes.writes);
            }
        }
    }

    @Test void productionPreparationEncodesTheCompletePublicationOnceRatherThanDiscardingItsFirstFrame() {
        var fresh = receipts.get(0);
        byte[][] roots = new byte[2][], retainedPayloads = new byte[2][];
        for (int variant = 0; variant < 2; variant++) {
            int[] fullPublicationEncodes = {0};
            try (var binding = new Binding(new Bytes(), () -> fullPublicationEncodes[0]++)) {
                PersistentOrderedMap<String, ContractsClosurePublicationReceipt> rows;
                if (variant == 0) {
                    // Previous production behavior: dependency preparation completely serializes
                    // the receipt, discards the bytes, and the reference layer serializes it again.
                    var codec = new PublicationReceiptStorageCodec(MAX, 256);
                    var split = new PersistentMapCodec<ContractsClosurePublicationReceipt>() {
                        public String identity() { return binding.legacy.identity(); }
                        public ContractsClosurePublicationReceipt prepareForStorage(ContractsClosurePublicationReceipt value) {
                            fullPublicationEncodes[0]++;
                            codec.encodePublication(value, binding.scope::retainView);
                            if (value.commits()) binding.results.retain(value.attempt().processResult());
                            return value;
                        }
                        public byte[] encode(ContractsClosurePublicationReceipt value) {
                            if (value == fresh) fullPublicationEncodes[0]++;
                            return codec.encodePublication(value, binding.sessions::viewAddress);
                        }
                        public ContractsClosurePublicationReceipt decode(byte[] bytes) {
                            return codec.decodePublication(bytes, binding.scope);
                        }
                    };
                    try (var references = new StoredClosureReceiptReferenceCodec(binding.bytes, MAX, split)) {
                        var codecs = new StoreIndexCodecs(binding.bytes, MAP_LIMITS);
                        rows = codecs.binding("publication/closure", EmbeddingBinding.TEXT_ORDER, codecs.text, references)
                                .open(null).put(fresh.publicationIdentity(), fresh).map();
                        roots[variant] = rows.storedRootDescriptor();
                    }
                } else {
                    rows = binding.indexes.openClosures(null).put(fresh.publicationIdentity(), fresh).map();
                    roots[variant] = rows.storedRootDescriptor();
                }
                assertEquals(variant == 0 ? 2 : 1, fullPublicationEncodes[0],
                        "Count fresh-value publication encodes, including dependency preparation, not cold read checks");
                assertEquals(1, binding.bytes.receiptWrites().size());
                String address = binding.bytes.receiptWrites().keySet().iterator().next();
                retainedPayloads[variant] = binding.bytes.records.get(address).clone();
                int writes = binding.bytes.writes;
                try (var cold = new Binding(binding.bytes)) {
                    assertReceipt(fresh, cold.indexes.openClosures(roots[variant]).get(fresh.publicationIdentity()), cold);
                }
                assertEquals(writes, binding.bytes.writes, "Cold canonical validation remains read-only");
            }
        }
        assertArrayEquals(retainedPayloads[0], retainedPayloads[1], "Exact original result/evidence bytes are unchanged");
        assertArrayEquals(roots[0], roots[1], "Physical references and tree identities are unchanged");
    }

    private static void assertReceipt(ContractsClosurePublicationReceipt expected, ContractsClosurePublicationReceipt actual, Binding binding) {
        assertNotNull(actual);
        assertEquals(expected.publicationIdentity(), actual.publicationIdentity());
        assertEquals(expected.documentIds(), actual.documentIds());
        assertEquals(expected.automaticRetryCount(), actual.automaticRetryCount());
        assertEquals(expected.commits(), actual.commits());
        var codec = new ClosureProcessResultStorageCodec(MAX, 256);
        assertArrayEquals(codec.encode(expected.attempt().processResult()), codec.encode(actual.attempt().processResult()),
                "Preserve complete result, gas, events, checkpoints and historical evidence");
        assertArrayEquals(binding.legacy.encode(expected), binding.legacy.encode(actual));
    }

    private static List<byte[]> closureNodes(Bytes bytes) throws Exception {
        var nodes = new ArrayList<byte[]>();
        for (byte[] value : bytes.records.values()) {
            if (value.length < 4 || ByteBuffer.wrap(value).getInt() != 0x504d4e31) continue;
            try (var input = new DataInputStream(new ByteArrayInputStream(value))) {
                input.readInt(); field(input); field(input);
                String type = new String(field(input), StandardCharsets.UTF_8);
                if (!type.equals("blue-coordination/publication-index-row/closure/2")) continue;
                field(input); byte[] reference = field(input);
                assertTrue(reference.length < 256);
                nodes.add(value);
            }
        }
        return nodes;
    }

    private static byte[] field(DataInputStream input) throws Exception { return input.readNBytes(input.readInt()); }
    private record Reference(String address, int length) { }
    private static Reference reference(byte[] bytes) {
        return SessionStorageWire.decode(bytes, 256, in -> {
            assertEquals(REFERENCE, in.text(in.remaining()));
            return new Reference(HexFormat.of().formatHex(in.bytes(32)), in.integer());
        });
    }
    private static byte[] descriptor(String address, int length, String format) {
        return SessionStorageWire.encode(256, out -> {
            out.text(format); out.bytes(HexFormat.of().parseHex(address)); out.integer(length);
        });
    }
    private static boolean isReceipt(byte[] bytes) {
        int length = PAYLOAD.length() * 2;
        return bytes.length >= length + 4 && ByteBuffer.wrap(bytes).getInt() == length
                && PAYLOAD.equals(new String(bytes, 4, length, StandardCharsets.UTF_16BE));
    }

    private static final class Binding implements AutoCloseable {
        final Bytes bytes;
        final DocumentSessionStorage sessions;
        final DocumentSessionStorage.OpenScope scope;
        final StoredResultRows results;
        final StoredPublicationIndexes indexes;
        final PersistentMapCodec<ContractsClosurePublicationReceipt> legacy;
        final StoredClosureReceiptReferenceCodec references;
        Binding(Bytes bytes) { this(bytes, null); }
        Binding(Bytes bytes, Runnable enclosingEncodeObserver) {
            this.bytes = bytes;
            sessions = new DocumentSessionStorage(bytes, SESSION_LIMITS); scope = sessions.openScope();
            results = new StoredResultRows(bytes, SESSION_LIMITS);
            indexes = new StoredPublicationIndexes(bytes, MAP_LIMITS, sessions, scope, results, 256, null,
                    new StoredPublicationReceiptReuse(), enclosingEncodeObserver);
            var codec = new PublicationReceiptStorageCodec(MAX, 256);
            legacy = new PersistentMapCodec<>() {
                public String identity() { return "blue-coordination/publication-index-row/closure/1"; }
                public ContractsClosurePublicationReceipt prepareForStorage(ContractsClosurePublicationReceipt value) {
                    codec.encodePublication(value, sessions::retainView);
                    if (value.commits()) results.retain(value.attempt().processResult());
                    return value;
                }
                public byte[] encode(ContractsClosurePublicationReceipt value) { return codec.encodePublication(value, sessions::viewAddress); }
                public ContractsClosurePublicationReceipt decode(byte[] encoded) { return codec.decodePublication(encoded, scope); }
            };
            references = new StoredClosureReceiptReferenceCodec(bytes, MAX, legacy);
        }
        public void close() { references.close(); indexes.close(); scope.close(); results.close(); }
    }

    private static final class Bytes implements CoordinationImmutableObjectStore {
        final Map<String, byte[]> records = new LinkedHashMap<>();
        final Map<String, Integer> puts = new LinkedHashMap<>();
        final List<String> reads = new ArrayList<>();
        int writes;
        boolean failReceiptWrite, badReceiptAcknowledgment;
        Runnable onNextGet;
        public byte[] putIfAbsent(String key, byte[] bytes) {
            writes++; puts.merge(key, 1, Integer::sum);
            if (failReceiptWrite && isReceipt(bytes)) throw new CoordinationObjectStorageException("Injected receipt write failure");
            byte[] old = records.putIfAbsent(key, bytes.clone());
            if (old != null && !Arrays.equals(old, bytes)) throw new CoordinationObjectStorageException("Conflicting immutable bytes");
            if (badReceiptAcknowledgment && isReceipt(bytes)) return new byte[]{0};
            return (old == null ? bytes : old).clone();
        }
        public Optional<byte[]> get(String key, int maximumBytes) {
            reads.add(key);
            var action = onNextGet; onNextGet = null; if (action != null) action.run();
            byte[] value = records.get(key);
            if (value != null && value.length > maximumBytes) throw new CoordinationObjectStorageException("Selected object exceeds byte bound");
            return Optional.ofNullable(value == null ? null : value.clone());
        }
        Map<String, Integer> receiptWrites() {
            return puts.entrySet().stream().filter(entry -> isReceipt(Objects.requireNonNull(records.get(entry.getKey()))))
                    .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        }
        Bytes copy() { var copy = new Bytes(); records.forEach((key, value) -> copy.records.put(key, value.clone())); return copy; }
    }
}
