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
