# Selected rooted document point indexes

## Problem and example

A restored session alone does not restore lineage resolution or graph-generation fences. A FROM_NOW parent also retains the exact source publication view chosen at admission. Opening parent and source independently must share that immutable view, while repeated reads of one selected mutable session must not create replacement objects or lose in-progress state.

Same-epoch rooted representation changes are another important distinction: the current lineage BlueId changes, but numbered retained epochs do not. Rebuilding indexes from a latest BlueId or reinserting rows in a new tree order would discard that evidence or change the existing logical lookup/copy counters.

## First index component

`StoredDocumentIndexes` and `StoreIndexCodecs` are private read/retention components. They cover immutable document-ID/session-address rows, the five current lineage index families (document, authored, initialized, retained and current), and per-document graph generations. Nested lineage buckets retain their own immutable AVL descriptors. Closed factories preserve the original tree shapes and exact mutation counters; normal lineage and graph-update algorithms are unchanged.

Each family descriptor is supplied separately by the owning directory's pinned read view. No combined descriptor is serialized. The small in-memory collection used by tests is not a publication object. Descriptors alone do not prove a coherent physical snapshot: the eventual host binding must supply their same-view slot/version evidence. No generic head-CAS API is added here.

The explicit resident-partition retention helper maps session values to immutable addresses without changing its tree shape. It traverses exactly the supplied partition and is not a default startup transform. Cold open authenticates the selected map roots and subsequently opens only selected paths. A future independently publishable directory must partition roots by owner/bucket and protect predicates; placing one sessions AVL root in one realm-wide CAS slot would still serialize all writers. This patch does not do that or remove existing coarse generation guards.

One bounded `Owner` uses one session/view scope. Initial reads validate complete session-to-lineage evidence, current rooted graph generation, authored/initialized/current reverse membership and every retained epoch's reverse membership before registering a working session. Repeated reads return that same mutable session and verify the original pinned address/lineage/generation and reverse memberships; they do not compare its in-progress head to the original persisted head. It never evicts that object to fit another session, and rejects changed retained authority for the same selected document. Working index changes must be retained and published together separately; a fresh physical snapshot needs a fresh owner. Session addresses bind their document IDs. Selected lineage-match buckets verify their row keys and primary lineage correspondence without loading other session bodies. Missing selected bytes or inconsistent rows fail noncommittingly, not as semantic document absence.

The first component still materializes the selected session history and each selected lineage metadata row, with explicit byte/session/view limits. Complete reverse validation is proportional to that selected history and its index paths, not constant work. Matching buckets return all matches, as before; their size is not relabeled bounded fanout. No processor/provider execution, write-on-read, or changed logical counters is introduced.

## Remaining full-store boundary

This is not a complete `StoreState`, engine installation path or SDK recovery mode. The current occurrence, component, pending-join, subscription, receipt, catch-up and ordered log families follow separately. Full terminal provenance requires the original Language invocation envelope; standalone supplied transition receipts require lossless Language-owned event/receipt storage, not event re-admission. Cross-family publication, selected read/absence/predicate fences and the existing cold/runtime validation boundary remain required before this component is wired into an engine.

## Focused controls

Final source-stable gate 18025 passed 18 tests plus Javadoc in 22s on the immutable Language bd09 / BEX ab72 / Catalog 0b tuple: five index controls, eight unchanged lineage controls, three unchanged AVL controls, and two actual graph-generation methods in `ClosureSubscriptionInventoryTest`. Before/after source SHA256 was `a6902a138b8b6ea3e47ce4bbb93311dbcec25dd77a48478ce5994fbd9ab9d3d9`; only this qualification paragraph changed afterward. Tests use actual rooted source/parent admissions and same-epoch checkpoint representations, reject reads of six unrelated session records, compare exact resident/stored mutation counters and old-root observations, verify bounded-owner identity, and reject mixed/missing/foreign/generation rows, malformed recurrent lineage and selected physical failure. The earlier 15-test feedback gate did not include the graph-generation methods and is not the final proof.

Review control 22037 reproduced a repeated-read failure after committing the actual SDK-produced next revision into the selected working session: the old implementation compared its new lineage to the retained epoch-zero row. The correction separates original validated authority from working state without relaxing first-read bytes or mixed-root rejection. Its control also publishes the corresponding explicit session/lineage/generation updates to a fresh owner, while the original owner rejects those changed inputs and a separate old owner still reads epoch zero.
