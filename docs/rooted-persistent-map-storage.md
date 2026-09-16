# Lazy immutable point-map component

## Problem and example

Cold per-document recovery is not useful if reaching one session first decodes every other session. The existing resident persistent AVL map already defines deterministic key ordering, tree rotations and logical comparison/copy counters. Its physical backing must preserve those decisions while opening only the selected path.

For example, changing key 42 in a 4,095-row map must retain the original immutable root, copy its AVL path, and leave every unrelated value uninterpreted. A missing selected child record is a noncommitting storage failure, not evidence that key 42 is absent.

## Minimal solution and provenance

This slice selectively ports `PersistentMapStorage`, `PersistentMapCodec`, the storage-backed node adaptation of `PersistentOrderedMap`, and their focused physical fixtures from `current-runtime-durable-coordination` commit `a2577c73ee8a07154ca11cc8bbed3ff5a94d382f`. It does not port its engine/store descriptor, old session format, SDK mode, or scheduling code. The resident AVL algorithm and its original tests remain the logical reference.

The private `stored` factory accepts library-owned key/value codecs and an ordering identity, the immutable byte SPI, physical limits, and an optional exact map-root descriptor. The descriptor binds codec/ordering versions and one content-addressed root with size/height metadata. Opening it authenticates only that root. Null selects an empty map; a serialized empty descriptor still validates its binding. The descriptor is an immutable value, not a mutable global runtime root.

Point access checks complete SHA-addressed node bytes, physical bounds, inherited ordering boundaries, parent/child dimensions and canonical selected key/value encodings. Unselected values remain bounded opaque frames. Rebranching copies those already authenticated frames without decoding their payloads; this does not bless an invalid value, which still fails when selected. Mutation publishes no directory pointer: a failed later prewrite can leave unreachable nodes, while the caller's old descriptor remains intact.

The same AVL comparisons and copied-node counts are returned in resident and stored modes. Node reads, cache misses, physical byte checks and canonical decoding do not add semantic gas, transition budget, or eligible work. The lazy inclusive-lower/exclusive-upper range retains only its traversal path and advances only after a complete successful read. Each operation's bounded physical node cache is cleared in `finally`, including failure. Codec methods are library-owned and must be pure except the explicit mutation-only immutable dependency preparation hook.

## Bounds and deliberate exclusions

Limits bound each node, key, value, descriptor and the number of cached node frames. A point mutation retains O(log n) traversal/copy work and does not hold the whole map's values. Range traversal retains O(log n) path state; its output depends on how many rows the caller requests. Existing exhaustive `keys`, `values`, `entries`, and structural-audit helpers remain explicitly exhaustive. The optional resident-to-stored shape copy is likewise a full-map operation, not a bounded cold-open claim.

Stored exhaustive helpers cap only their initial list capacity, instead of preallocating the entire count asserted by an unvisited root. A 32 MiB child-JVM control supplies a hash-consistent root declaring `Integer.MAX_VALUE` rows and verifies that all three helpers reject the selected child's inconsistent dimensions noncommittingly, rather than first allocating the asserted output. Actual complete traversal/output can still use O(n) memory; no logical row limit or skipped row is introduced. Resident initial capacity, ordering, and work counters are unchanged.

No mutable bucket directory, revision counter, read/absence/predicate conflict protocol, realm-wide CAS, publication lock or provider recovery is introduced. Independent descriptors in tests prove immutable isolation only, not concurrent semantic publication. Independent bucket revisions and exact point/absence/phantom controls must qualify at the owning publication boundary before any current coarse generation guard can be replaced. This component is not full runtime or fresh-SDK recovery.

## Qualification

Native gate 72715 passed **21/21 tests plus Javadoc** in 13s on the coherent Language bd09c281 / unchanged BEX ab72af14 / catalog 0b68744b event-storage tuple. Source-file hash before and after was `f64181ab6c2b63d1d7971ca22c0473bcc575fcd904e321758e6b4b9fa483af4c`; only this qualification paragraph changed afterward. The sixteen stored-map controls and five unchanged resident map/view/minimum controls had zero failures, errors or skips. Initial gate 58739 had already passed 20/20 before the additional malformed-count hardening/control.

Controls cover cold randomized resident equivalence, same exact work counters under cache eviction, selected-only get/range/rebranch, empty/absent branches, inherited order and codec binding, corrupt/missing/oversized/unavailable bytes, failed path prewrites, range retry position, separate immutable descriptors, a 32 MiB malformed-count JVM, and an actual fresh child JVM continuing from byte files. No PostgreSQL or broad runtime gate was run for this independently bounded primitive.
