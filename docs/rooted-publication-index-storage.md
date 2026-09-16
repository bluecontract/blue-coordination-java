# Selected publication indexes and original result rows

## Problem and boundary

The exact receipt codecs are not themselves durable maps. Publication state has
three separate roots: generic membership, typed admission, and typed closure
receipts. Feeder declared-birth rejections have a fourth independent root. Outbox
and checkpoint logs also need a stable way to retain the *original* Language row
capability after the producer closes, without searching all publication receipts.

`StoredPublicationIndexes` binds those four existing value types to the qualified
persistent map primitive and exact receipt codecs. It does not publish their roots,
install a StoreState, select work, or turn one realm-wide map into independent
publication authority. The caller supplies the pinned roots and one shared
`DocumentSessionStorage.OpenScope`; all referenced historical views use that scope.
Working-value projection and final root assembly remain the owner’s responsibility.

The map value codec cannot see the AVL key. Therefore the owning selected-value
projection calls `checkedAdmission`, `checkedClosure`, or `checkedRejection` with
the actual key. These point checks retain the existing exact-key, generic ledger,
admission/closure exclusivity, and durable PUBLISHED-admission requirements.
Generic-only receipts remain permitted by the existing compatibility model. The
enclosing StoreState/session association checks are not replaced by these bindings.

## Explicit prepare, pure encode and read

`prepareForStorage` prewrites only the selected receipt’s immutable view dependencies
and, for a genuine committing publication/admission, registers its original result.
Rejected-but-committing host expectation results do not populate published row
membership. `encode` and `decode` never write. Pure `viewAddress(view)` reuses the
same exact view codec and content digest as `retainView`; it adds no second view
cache or authority. This deliberately repeats bounded physical encoding rather
than introducing speculative memoization. It is not an O(1) view-address lookup.

The enclosing writer stages publication maps before new outbox/checkpoint chunks.
The qualified persistent-map/log staging primitives preserve untouched handles;
this component does not scan existing receipt maps or old log prefixes to find
new row owners. A failed prewrite leaves any returned old root unchanged. Immutable
unreferenced prewrites can exist, but they are not a committed publication.

## Bounded original-result identity scope

`StoredResultRows` is an explicitly closeable per-selected-runtime or writer scope,
not a global cache. It retains the exact supplied original result, verifies immutable
write acknowledgment, and records actual object-identity membership of its ordered
public event and checkpoint lists. Scope charging uses complete encoded result
bytes plus fixed per-member bookkeeping, including duplicate object constructions;
the record limit and aggregate scope limit fail noncommitting, with no eviction or
guessing of an owner. These are physical transport/accounting bounds, not a claim
of byte-exact Java heap weighing or a new protocol limit.

Log codecs encode an exact result address, kind, and position. Cold read loads only
that named result, checks its digest and record bound, decodes with the existing
provider-free Language codec, and returns the actual member from that decoded
result. Canonical re-encoding uses the same scoped member association and performs
no write. Original unregistered public lookalikes and manual staged rows without
an owning result fail closed. Repeated reads in one open scope reuse immutable
captured evidence; a fresh scope rechecks selected physical bytes. Closing retires
all associations and makes later result operations fail noncommitting.

The immutable store authenticates pinned addresses. Checksums and these mappings
do not independently authenticate BEX, grant publication authority, or replace
normal external verification. No provider, PROCESS, journal replay, current-head
recapture, or public raw-evidence factory is introduced.

## Proof scope

`StoredPublicationIndexesTest` retains actual static admission, parent/source
publications and a genuine declared wrong-birth rejection, then opens fresh byte
wrappers after producer close. It checks shared historical view identity, exact
event/checkpoint member reuse, zero writes on read/re-encode, selected-key and
sibling-ledger checks, wrong map domains, missing/corrupt selected results, bounded
scope/acknowledgment/closed-scope failures, old-root preservation after failed
staging, and rejection plan/demand identity. A corrupt *referenced* parent history
is unread when selecting the independent source receipt but fails when selected.
These tests qualify the bindings, not complete engine/SDK installation, atomic
root publication, or a PostgreSQL fresh-process gate.

The adjacent compact evidence manifest records the exact final source-frozen
focused command, upstream immutable tuple, outcomes and external archive hashes.
