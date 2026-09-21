package blue.coordination.api.storage;

import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/** Closed, detached logical-record vocabulary; values and sort keys are library encoded. */
public final class CoordinationRecords {
    private CoordinationRecords() { }

    /**
     * Families are wire names, never enum ordinals. Scoped secondary index members
     * are individual records, not replaceable whole-family AVL descriptors.
     */
    public enum Family {
        INSTANCE_BINDING, INSTANCE_IDENTITY, INSTANCE_HISTORY, INSTANCE_PROGRESS, INSTANCE_PUBLICATION, INSTANCE_OCCURRENCE,
        SESSION, GRAPH_GENERATION, SOURCE_ADMISSION, SOURCE_HISTORY, SOURCE_IDENTITY, SOURCE_POSITION,
        LINEAGE_DOCUMENT, LINEAGE_AUTHORED, LINEAGE_INITIALIZED, LINEAGE_RETAINED, LINEAGE_CURRENT,
        OCCURRENCE_PATH, OCCURRENCE_ORDERED, OCCURRENCE_ACTIVE, OCCURRENCE_ID, BINDING_ID,
        OCCURRENCE_DOCUMENT, OCCURRENCE_SOURCE, OCCURRENCE_ACTIVE_SOURCE,
        TOPOLOGY_COMPONENT, TOPOLOGY_TARGET, TOPOLOGY_SOURCE, TOPOLOGY_JOIN_MEMBER, TOPOLOGY_JOIN_ROOT,
        COMPONENT_LINEAGE, COMPONENT_STATE, COMPONENT_DOCUMENT,
        SUBSCRIPTION_SLOT, SUBSCRIPTION_IDENTITY, SUBSCRIPTION_DOCUMENT, SUBSCRIPTION_DEMAND,
        RUNTIME_OUTBOX, CHECKPOINT, PUBLICATION, ADMISSION, CLOSURE, PROVIDER_FRONTIER, APPLICATION_RESULT,
        RECEIPT_DOCUMENT, RECEIPT_IDENTITY,
        PLAN_IDENTITY, PLAN_CONSUMER, PLAN_SOURCE, PLAN_OCCURRENCE, PLAN_BARRIER, PLAN_ACTIVE_SOURCE,
        BARRIER, WORK, WORK_PENDING, WORK_APPLICATION, WORK_APPLICATION_BY_WORK, WORK_DUE,
        OBJECT_ENTRY, OBJECT_PROOF, OBJECT_MEMBER, ROUTE, ROUTE_DOCUMENT,
        ACTIVE_ROOT, ACTIVE_SURFACE, ACTIVE_MEMBERSHIP, ACTIVE_TIMELINE,
        PENDING_DRAFT, PENDING_SELECTION, SOURCE_PENDING, SOURCE_SUBMITTED, SOURCE_COMPLETED,
        FEEDER_PENDING, FEEDER_REJECTED, FEEDER_TERMINAL, FEEDER_FRONTIER,
        SDK_TIMELINE, SDK_INTENT, SDK_RESULT, SDK_ENTRY, SDK_SOURCE_RESULT,
        CONFIGURATION, TIMELINE, ROOT_SCHEDULE, MANAGED_SCHEDULE, JOURNAL_ENTRY, JOURNAL_COVERAGE,
        JOURNAL_TERMINAL, JOURNAL_FRONTIER,
        HOST_INSTANCE, HOST_OWNER, HOST_CONTROL, HOST_COMMAND, HOST_PROJECTION,
        HOST_WORK, HOST_WAIT, HOST_WAKE, HOST_OUTBOX
    }

    /** Immutable bytes with content equality and unsigned lexicographic ordering. */
    public static final class Bytes implements Comparable<Bytes> {
        private final byte[] value;
        /** Copies supplied bytes; callers cannot mutate a prepared packet. */
        public Bytes(byte[] value) { this.value = Objects.requireNonNull(value, "value").clone(); }
        /** Returns a defensive copy. */
        public byte[] copy() { return value.clone(); }
        /** Physical encoded byte count. */
        public int size() { return value.length; }
        /** Lowercase hexadecimal representation, useful for portable evidence. */
        public String hex() { return HexFormat.of().formatHex(value); }
        /** Decodes canonical lowercase hexadecimal bytes. */
        public static Bytes fromHex(String value) {
            if (value == null || !value.matches("(?:[0-9a-f]{2})*"))
                throw new IllegalArgumentException("Noncanonical hexadecimal bytes");
            return new Bytes(HexFormat.of().parseHex(value));
        }
        @Override public int compareTo(Bytes other) { return Arrays.compareUnsigned(value, other.value); }
        @Override public boolean equals(Object other) { return other instanceof Bytes b && Arrays.equals(value, b.value); }
        @Override public int hashCode() { return Arrays.hashCode(value); }
        @Override public String toString() { return hex(); }
    }

    /** Stable host execution domain; document instances coexist inside this address. */
    public record Address(String namespace, String instance) {
        /** Rejects empty identities and invalid Unicode before encoding. */
        public Address { namespace = text(namespace); instance = text(instance); }
    }

    /** Complete typed identity; ordering inside a scope is unsigned encoded-key order. */
    public record Key(Family family, Bytes scope, Bytes key) implements Comparable<Key> {
        /** Owns immutable identity components. */
        public Key { Objects.requireNonNull(family); Objects.requireNonNull(scope); Objects.requireNonNull(key); }
        @Override public int compareTo(Key other) {
            int c = family.name().compareTo(other.family.name());
            if (c == 0) c = scope.compareTo(other.scope);
            return c == 0 ? key.compareTo(other.key) : c;
        }
    }

    /** Null content is a tombstone; revision zero is reserved for never-seen absence. */
    public record Value(long revision, Bytes content) {
        /** Rejects impossible revision/value combinations. */
        public Value {
            if (revision < 0 || (revision == 0 && content != null))
                throw new IllegalArgumentException("Invalid logical record revision");
        }
        /** Whether a live value is present. */
        public boolean present() { return content != null; }
        /** Never-seen absence; deletion uses a positive revision instead. */
        public static Value absent() { return new Value(0, null); }
    }

    /** One live member of a complete query response. */
    public record Row(Key key, Value value) {
        /** Tombstones cannot masquerade as live query members. */
        public Row {
            Objects.requireNonNull(key); Objects.requireNonNull(value);
            if (!value.present()) throw new IllegalArgumentException("Query row must be live");
        }
    }

    /** Half-open [lower, upper) predicate; null bounds mean unbounded in this exact scope. */
    public record Range(Family family, Bytes scope, Bytes lower, Bytes upper) {
        /** Rejects reversed or empty ranges. */
        public Range {
            Objects.requireNonNull(family); Objects.requireNonNull(scope);
            if (lower != null && upper != null && lower.compareTo(upper) >= 0)
                throw new IllegalArgumentException("Invalid query bounds");
        }
        /** Whether an exact key belongs to this predicate. */
        public boolean contains(Key candidate) {
            return candidate.family() == family && candidate.scope().equals(scope)
                    && (lower == null || candidate.key().compareTo(lower) >= 0)
                    && (upper == null || candidate.key().compareTo(upper) < 0);
        }
    }

    /** Exact decision-relevant point observation, including absent/tombstoned keys. */
    public record Point(Key key, Value expected) {
        /** Requires detached key and expected value. */
        public Point { Objects.requireNonNull(key); Objects.requireNonNull(expected); }
    }

    /** Complete predicate observation, not merely the rows of a limited first page. */
    public record Query(Range range, List<Row> expected) {
        /** Rejects unordered, repeated, tombstoned or out-of-predicate members. */
        public Query {
            Objects.requireNonNull(range); expected = List.copyOf(expected);
            Key previous = null;
            for (var row : expected) {
                if (!range.contains(row.key()) || (previous != null && previous.compareTo(row.key()) >= 0))
                    throw new IllegalArgumentException("Noncanonical complete query response");
                previous = row.key();
            }
        }
    }

    /** Replacement value, or deletion. Every mutation requires an exact point condition. */
    public record Mutation(Key key, Bytes content) {
        /** Null content means delete while preserving/incrementing the revision. */
        public Mutation { Objects.requireNonNull(key); }
    }

    /** Closed cache/index families whose identities can be inserted once and never changed or deleted. */
    public static boolean immutableFamily(Family family) {
        return family == Family.OBJECT_PROOF || family == Family.OBJECT_MEMBER;
    }

    /** An immutable physical lookup fact: absence or identical bytes is acceptable at publication. */
    public record ImmutableFact(Key key, Bytes content) {
        /** Only immutable object lookup families qualify; mutable runtime/host records never do. */
        public ImmutableFact {
            Objects.requireNonNull(key); Objects.requireNonNull(content);
            if (!immutableFamily(key.family())) throw new IllegalArgumentException("Not an immutable lookup family");
        }
    }

    /** Required immutable body; hosts must authenticate bytes before making it reachable. */
    public record Artifact(Bytes sha256, long bytes) {
        /** Digest is binary SHA-256 and byte length is exact. */
        public Artifact {
            Objects.requireNonNull(sha256);
            if (sha256.size() != 32 || bytes < 0) throw new IllegalArgumentException("Invalid immutable dependency");
        }
    }

    /**
     * Closed publication packet. Canonical digest covers address, stable identity,
     * all runtime/host conditions and mutations, artifact dependencies and exact
     * evidence. Contains no SDK handle, object store or semantic callback.
     */
    public static final class Publication {
        private final Address address;
        private final String id;
        private final List<Point> points;
        private final List<Query> queries;
        private final List<Mutation> mutations;
        private final List<ImmutableFact> immutableFacts;
        private final List<Artifact> artifacts;
        private final Bytes evidence;
        private final Bytes digest;

        /**
         * Creates a detached packet. Unordered input collections are normalized;
         * duplicate identities and unconditioned writes fail before publication.
         * Query/point observations must agree wherever they overlap.
         */
        public Publication(Address address, String id, Collection<Point> points,
                Collection<Query> queries, Collection<Mutation> mutations,
                Collection<Artifact> artifacts, Bytes evidence) {
            this(address, id, points, queries, mutations, List.of(), artifacts, evidence);
        }

        /** Creates a packet with insert-once physical facts in addition to strictly conditional mutations. */
        public Publication(Address address, String id, Collection<Point> points, Collection<Query> queries,
                Collection<Mutation> mutations, Collection<ImmutableFact> immutableFacts,
                Collection<Artifact> artifacts, Bytes evidence) {
            this.address = Objects.requireNonNull(address); this.id = text(id);
            this.evidence = Objects.requireNonNull(evidence);
            var byKey = new TreeMap<Key, Point>();
            for (var point : points) if (byKey.put(point.key(), point) != null)
                throw new IllegalArgumentException("Repeated point condition");
            this.points = List.copyOf(byKey.values());
            var orderedQueries = new TreeMap<Bytes, Query>();
            for (var query : queries) {
                var encoded = bytes(out -> range(out, query.range()));
                if (orderedQueries.put(encoded, query) != null) throw new IllegalArgumentException("Repeated query condition");
                for (var point : this.points) if (query.range().contains(point.key())) {
                    var row = query.expected().stream().filter(r -> r.key().equals(point.key())).findFirst();
                    if (point.expected().present() != row.isPresent()
                            || (row.isPresent() && !row.get().value().equals(point.expected())))
                        throw new IllegalArgumentException("Incoherent point/query observations");
                }
            }
            this.queries = List.copyOf(orderedQueries.values());
            for (int i = 0; i < this.queries.size(); i++) for (int j = i + 1; j < this.queries.size(); j++) {
                var left = this.queries.get(i); var right = this.queries.get(j);
                var l = left.expected().stream().filter(r -> right.range().contains(r.key())).toList();
                var r = right.expected().stream().filter(row -> left.range().contains(row.key())).toList();
                if (!l.equals(r)) throw new IllegalArgumentException("Incoherent overlapping queries");
            }
            var writes = new TreeMap<Key, Mutation>();
            for (var mutation : mutations) {
                if (immutableFamily(mutation.key().family())) throw new IllegalArgumentException("Immutable lookup facts cannot be mutated or deleted");
                var point = byKey.get(mutation.key());
                if (point == null || point.expected().revision() == Long.MAX_VALUE)
                    throw new IllegalArgumentException("Mutation lacks an incrementable point condition");
                if (writes.put(mutation.key(), mutation) != null) throw new IllegalArgumentException("Repeated mutation");
            }
            this.mutations = List.copyOf(writes.values());
            var facts = new TreeMap<Key, ImmutableFact>();
            for (var fact : immutableFacts) if (facts.put(fact.key(), fact) != null)
                throw new IllegalArgumentException("Repeated immutable fact");
            this.immutableFacts = List.copyOf(facts.values());
            var dependencies = new TreeMap<Bytes, Artifact>();
            for (var artifact : artifacts) if (dependencies.put(artifact.sha256(), artifact) != null)
                throw new IllegalArgumentException("Repeated immutable dependency");
            this.artifacts = List.copyOf(dependencies.values());
            this.digest = sha256(canonicalBytes());
        }

        /** Exact namespace and execution instance. */
        public Address address() { return address; }
        /** Stable logical stage/publication identity, never an attempt UUID. */
        public String id() { return id; }
        /** Complete point read set. */
        public List<Point> points() { return points; }
        /** Complete scoped predicate read set. */
        public List<Query> queries() { return queries; }
        /** All runtime and applicable host mutations. */
        public List<Mutation> mutations() { return mutations; }
        /** Insert-once physical lookup facts; existing different bytes are an integrity failure. */
        public List<ImmutableFact> immutableFacts() { return immutableFacts; }
        /** Exact immutable bytes required by the packet. */
        public List<Artifact> artifacts() { return artifacts; }
        /** Detached exact result/continuation evidence in a library-owned encoding. */
        public Bytes evidence() { return evidence; }
        /** SHA-256 of the canonical complete payload, including identity and address. */
        public Bytes digest() { return digest; }
        /** Portable canonical payload. No host-private SQL or AVL representation is encoded here. */
        public Bytes canonicalBytes() {
            return bytes(out -> {
                text(out, immutableFacts.isEmpty() ? "blue-coordination/logical-publication/1" : "blue-coordination/logical-publication/2");
                text(out, address.namespace()); text(out, address.instance()); text(out, id);
                out.writeInt(points.size());
                for (var p : points) { key(out, p.key()); value(out, p.expected()); }
                out.writeInt(queries.size());
                for (var q : queries) {
                    range(out, q.range()); out.writeInt(q.expected().size());
                    for (var row : q.expected()) { key(out, row.key()); value(out, row.value()); }
                }
                out.writeInt(mutations.size());
                for (var m : mutations) { key(out, m.key()); nullable(out, m.content()); }
                if (!immutableFacts.isEmpty()) {
                    out.writeInt(immutableFacts.size());
                    for (var fact : immutableFacts) { key(out, fact.key()); blob(out, fact.content()); }
                }
                out.writeInt(artifacts.size());
                for (var a : artifacts) { blob(out, a.sha256()); out.writeLong(a.bytes()); }
                blob(out, evidence);
            });
        }
    }

    /** Hashes exact bytes without retaining caller-owned arrays. */
    public static Bytes sha256(Bytes bytes) {
        try { return new Bytes(MessageDigest.getInstance("SHA-256").digest(bytes.value)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    /** Physical decoder limits; exceeding one is not a semantic processing result. */
    public record DecodeLimits(int maximumPacketBytes, int maximumItems, int maximumBlobBytes) {
        /** Rejects nonpositive capacities. */
        public DecodeLimits {
            if (maximumPacketBytes < 1 || maximumItems < 1 || maximumBlobBytes < 1)
                throw new IllegalArgumentException("Invalid publication decoder limits");
        }
    }

    /**
     * Opens a portable packet under explicit physical bounds. Requires canonical
     * encoding and a closed version/family vocabulary. Bytes are never treated
     * as host authorization: the receiver must still validate every condition.
     */
    public static Publication decodePublication(Bytes encoded, DecodeLimits limits) {
        Objects.requireNonNull(encoded); Objects.requireNonNull(limits);
        if (encoded.size() > limits.maximumPacketBytes()) throw new CoordinationObjectStorageException("Oversized publication");
        try {
            var reader = new Decoder(encoded.copy(), limits);
            String format = reader.text();
            boolean withFacts = format.equals("blue-coordination/logical-publication/2");
            if (!withFacts && !format.equals("blue-coordination/logical-publication/1"))
                throw new IllegalArgumentException("Unknown publication format");
            var address = new Address(reader.text(), reader.text());
            String id = reader.text();
            var points = new ArrayList<Point>();
            for (int n = reader.count(); n > 0; n--) points.add(new Point(reader.key(), reader.value()));
            var queries = new ArrayList<Query>();
            for (int n = reader.count(); n > 0; n--) {
                var range = new Range(reader.family(), reader.blob(), reader.nullable(), reader.nullable());
                var rows = new ArrayList<Row>();
                for (int c = reader.count(); c > 0; c--) rows.add(new Row(reader.key(), reader.value()));
                queries.add(new Query(range, rows));
            }
            var mutations = new ArrayList<Mutation>();
            for (int n = reader.count(); n > 0; n--) mutations.add(new Mutation(reader.key(), reader.nullable()));
            var facts = new ArrayList<ImmutableFact>();
            if (withFacts) for (int n = reader.count(); n > 0; n--) facts.add(new ImmutableFact(reader.key(), reader.blob()));
            var artifacts = new ArrayList<Artifact>();
            for (int n = reader.count(); n > 0; n--) artifacts.add(new Artifact(reader.blob(), reader.in.readLong()));
            var publication = new Publication(address, id, points, queries, mutations, facts, artifacts, reader.blob());
            if (reader.in.available() != 0 || !publication.canonicalBytes().equals(encoded))
                throw new IllegalArgumentException("Noncanonical publication encoding");
            return publication;
        } catch (IOException | IllegalArgumentException failure) {
            throw new CoordinationObjectStorageException("Invalid logical publication", failure);
        }
    }

    private static final class Decoder {
        private final DataInputStream in;
        private final DecodeLimits limits;
        private int items;
        private Decoder(byte[] bytes, DecodeLimits limits) {
            in = new DataInputStream(new ByteArrayInputStream(bytes)); this.limits = limits;
        }
        private int count() throws IOException {
            int count = in.readInt();
            if (count < 0 || count > limits.maximumItems() - items || count > in.available())
                throw new IllegalArgumentException("Invalid bounded publication count");
            items += count; return count;
        }
        private Bytes blob() throws IOException {
            int size = in.readInt();
            if (size < 0 || size > limits.maximumBlobBytes() || size > in.available())
                throw new IllegalArgumentException("Invalid bounded publication bytes");
            return new Bytes(in.readNBytes(size));
        }
        private Bytes nullable() throws IOException {
            int marker = in.readUnsignedByte();
            if (marker > 1) throw new IllegalArgumentException("Invalid nullable marker");
            return marker == 0 ? null : blob();
        }
        private String text() throws IOException { return new String(blob().value, StandardCharsets.UTF_8); }
        private Family family() throws IOException { return Family.valueOf(text()); }
        private Key key() throws IOException { return new Key(family(), blob(), blob()); }
        private Value value() throws IOException { return new Value(in.readLong(), nullable()); }
    }

    private static String text(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Blank record identity");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (++i == value.length() || !Character.isLowSurrogate(value.charAt(i)))
                    throw new IllegalArgumentException("Invalid Unicode identity");
            } else if (Character.isLowSurrogate(c)) throw new IllegalArgumentException("Invalid Unicode identity");
        }
        return value;
    }
    @FunctionalInterface private interface Encoder { void write(DataOutputStream out) throws IOException; }
    private static Bytes bytes(Encoder encoder) {
        var buffer = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(buffer)) { encoder.write(out); }
        catch (IOException impossible) { throw new IllegalStateException(impossible); }
        return new Bytes(buffer.toByteArray());
    }
    private static void text(DataOutputStream out, String text) throws IOException {
        blob(out, new Bytes(text.getBytes(StandardCharsets.UTF_8)));
    }
    private static void blob(DataOutputStream out, Bytes bytes) throws IOException {
        out.writeInt(bytes.size()); out.write(bytes.value);
    }
    private static void nullable(DataOutputStream out, Bytes bytes) throws IOException {
        out.writeBoolean(bytes != null); if (bytes != null) blob(out, bytes);
    }
    private static void key(DataOutputStream out, Key key) throws IOException {
        text(out, key.family().name()); blob(out, key.scope()); blob(out, key.key());
    }
    private static void value(DataOutputStream out, Value value) throws IOException {
        out.writeLong(value.revision()); nullable(out, value.content());
    }
    private static void range(DataOutputStream out, Range range) throws IOException {
        text(out, range.family().name()); blob(out, range.scope()); nullable(out, range.lower()); nullable(out, range.upper());
    }
}
