# Logical runtime maps

`PersistentOrderedMap` now has an internal logical-record backing in addition to
its resident and immutable AVL backings. The new backing uses library-owned
ordered keys and the existing typed value codecs. It does not expose, merge or
publish private AVL frames through the host.

A `LogicalRecordContext` owns one tracked attempt. Each map is an immutable view
with its own persistent tentative overlay. Reads add exact point or range
conditions; creating a branch does not install publication writes. Only the
explicitly selected final maps encode changes. After all maps have been selected,
`flush()` installs their collected mutations into the tracked attempt. Conflicting
final selections and codec failures retire the attempt. The engine must also
retire the scope after a failed semantic invocation.

Point lookup and membership do not enumerate the catalog. Minimum, successor and
lazy range traversal capture only visited prefixes; an earlier tentative row
bounds the underlying query before a later persisted row. Complete enumeration,
size, maximum and clearing intentionally capture complete membership. Maximum
currently uses complete enumeration and must not be substituted for a selective
suffix lookup in a large shared runtime family.

Working value projections preserve the session owner's lazy hydration behavior.
Membership need not hydrate a session; staging re-encodes changed working values
only. Existing descriptor-backed behavior and its structure tests remain intact.

`StoredDocumentIndexes` provides logical primary bindings for session addresses,
per-document graph generations and lineage documents. Session bodies, histories
and retained Language results remain immutable authenticated artifacts. Ordered
text uses strict UTF-8 (Unicode scalar order), signed integers flip their sign
bit, and tuple components escape zero and terminate explicitly. Value codecs
remain canonical and bounded on reads and preparation.

This is an incremental runtime migration. The complete engine/SDK assembly and
secondary families are not switched yet. The primary-family cold test uses real
processed sessions and compares complete retained Language result bytes after
both publication orders, but it is not an application S2/D2/H qualification.
Artifact dependency collection and owner/control completion remain responsibilities
of the forthcoming engine assembly. Unit tests use a coherent in-memory fixture;
PostgreSQL and application acceptance remain separate required gates.

## Flattened secondary memberships

`LogicalRecordBuckets` stores each secondary member under a strict encoded
owner prefix and inner key. There is no mutable bucket descriptor or shared
insertion counter. A virtual empty bucket is a valid view: obtaining it does not
assert membership; reading a member, enumerating it or testing emptiness records
the corresponding condition. Outer enumeration contains only nonempty buckets.
The nested-map callers migrated here treat absent and empty buckets identically;
legacy resident/AVL callers retain their existing null behavior.

Lineage mutations and occurrence, topology and subscription member removals keep
logical empty views without testing the rest of the bucket just to decide its
physical representation. Explicit whole-bucket replacement/removal still guards
complete membership. Selected buckets are checked against their exact attempt,
family, scope and owner prefix, including empty views.

All five lineage families, eight occurrence families, five topology/join families,
three component-state families and four subscription families have explicit
logical bindings. Together with session and graph-generation primaries this is
27 family bindings. Full runtime assembly is still pending; this count does not
claim the remainder of the family inventory is migrated. The tests include two
writers inserting into the same previously absent bucket, last-member removal
versus insertion, precise query conflicts, foreign bucket rejection, shared-target
occurrence memberships, and cold exact results from actual rooted execution.

## Catch-up and receipt evidence

Catch-up plan membership is flattened through the existing `IdBucket` facade.
The active-source count is a derived view of current source memberships and plan
statuses: independent consumers do not update a shared counter. Exact plan
crosslink validation checks the individual memberships; only a query that needs
a source count reads the complete source bucket.

Due-work keys preserve the published Language `ExternalOrderKey` comparator,
including arbitrary signed integers, text, mixed scalar kinds and tuple prefixes.
`PersistentMinimumMap` uses the tracked logical map for cold points and selective
minimum/successor reads. Completing a later work item does not fence an earlier
selected minimum.

Logical receipt storage separates numbered `(document, epoch)` evidence from the
mutable `(document)` head cursor. A lazy `DocumentHistory` loads the cursor only
when a query or mutation requires it. Exact historical evidence validates the
numbered row and receipt identity without reading the current source head.
Selected document read checks preserve this lazy boundary. Appending, rebinding
or validating a missing in-range receipt still reads the appropriate head.
Legacy descriptor histories retain their existing framing and behavior.

These bindings remain part of the ongoing migration. They do not yet establish a
complete engine publication or application stage execution path.

Publication memberships, admissions, closures, provider frontiers, application
results and declared rejection rows now have explicit typed logical bindings.
Route rows are keyed by route plus document: replacement validates only that
document's contribution, while selection validates complete relevant membership.
Route generations remain attempt-local guards on the logical path. Active source
Timelines use independent `(timeline, root)` membership records and derive counts
only on query. Reverse document/root memberships likewise update individual
members. Cold tests compare route deltas/results to the resident implementation,
exercise both independent publication orders and reject new matching membership.
The full engine/SDK assembly remains a separate required integration.

Logical catch-up restoration does not enumerate all barriers or retain a mutable
active-barrier count. Global diagnostic queries derive the count; optimization
callers use a conservative hint and then inspect their reachable consumer
barriers. Explicit-root stage processing anchors fairness to its requested root
without enumerating the session catalog. Legacy convenience processing retains
its previous global fairness behavior.

`StoredDocumentStore.openLogical` now assembles all document families and their
existing read-validation projections without descriptors. Opening performs no
record reads. `stageLogical` selects current changes and leaves final flush and
publication to the enclosing attempt. Occurrence/component generation scalars
remain local guards; durable per-document generation records supply conditions.
Topology membership validation occurs at the selected logical member, including
key enumeration, so virtual empty buckets do not assert an existing owner.

Logical outbox/checkpoint evidence has owner-scoped append positions and counters.
Diagnostic enumeration groups owners canonically and preserves append order
within each owner; tentative and cold views use the same order. Cross-owner
diagnostic order is not a processing selector or a global commit sequence.
Rows retain their exact original result/member references. Checkpoints use the
first canonical processor-derived result owner as their append domain; events
use their public Root. Complete enumeration validates owner/tail coverage.

`LogicalDocumentStoreTest` executes actual admission, cold rooted processing,
parent/source catch-up and independent prepared stages in both publication
orders. Its fixture replaces the document state in a resident engine; it is
explicitly not full engine/SDK restoration or application S2/D2/H qualification.
