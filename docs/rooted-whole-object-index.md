# Exact object index integration

Problem: the exact-body adapter already reads one content-addressed record, but a
fresh engine still needs persisted point indexes for BlueId → representation,
cyclic master → proof, and cyclic master → retained provider members. Rebuilding
those indexes from all bodies would defeat cold startup.

`StoredWholeObjectIndex` binds three immutable AVL roots and nested per-master
member indexes beneath the existing `WholeObjectStorage`. Opening selects only
metadata. A lookup reads the selected record and preserves the separate canonical,
provider, and cyclic-proof representations. Known-but-missing bytes are a storage
failure, not a missing document or a semantic rejection.

Staging an attempt's exact-body delta returns a new index view. Earlier views stay
unchanged, including when a later immutable prewrite fails. The host must publish
the selected descriptors together with the owning operation's other state and
fences; these roots are not a global publication token. No Timeline, identity,
gas, or rooted-processing rule changes.

Example: a reference-only body is upgraded to a complete representation. The new
view reads the complete body under the same BlueId; an earlier pinned view still
reads the original reference. A cyclic member still resolves through its original
provider body and complete master proof after reopening from bytes.

Verification: `StoredWholeObjectIndexTest` plus the unchanged
`WholeObjectStorageTest` pass with Javadoc on the pinned Language 8542285 / BEX
ab72af1 / Catalog 0b68744 development tuple. Controls cover an unrelated object
population, a cyclic provider, reference upgrade, missing selected bytes, foreign
membership, and a failed index prewrite. This qualifies the object-index binding,
not the complete engine/SDK/PostgreSQL path.
