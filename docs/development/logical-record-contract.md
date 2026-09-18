# Logical record persistence contract

This is the first contract increment for concurrent durable stages. The old
descriptor storage path is still active. Passing these contract tests does not
establish runtime family coverage, PostgreSQL correctness, or S2/D2 acceptance.

The public symbols are `api.storage.CoordinationRecordStore`,
`CoordinationRecords` and `CoordinationRecordAttempt`. They contain no internal
engine, SQL, host callback or SDK handle. Existing immutable objects remain
behind `CoordinationImmutableObjectStore`. No lower library changes are needed.

`Address` names one opaque host namespace and non-reusable execution instance.
`Key` names a closed family, encoded scope and encoded key. Keys are ordered by
unsigned bytes within each family/scope; library adapters must preserve their
semantic comparator when producing those bytes. Host code must never decode
private AVL frames or merge stale family descriptors.

`ReadScope` provides one coherent lazy snapshot, including complete half-open
range queries. A bounded query that cannot complete throws; it cannot return a
partial page or claim absence. `CoordinationRecordAttempt` tracks the original
point and predicate observations and overlays its own pending mutations.
Its `first(range)` uses a coherent first-row hint followed by a complete
prefix condition through the selected key. It verifies the hint against that
prefix, incorporates pending insertions/deletions, and conditions the full range
only when no live member exists. Later members do not become decision-relevant
conditions merely because they share an index. PostgreSQL overrides the default
exhaustive hint with an indexed first-row query.
Point absence has revision zero only when a key has never existed. Deleted keys
retain increasing tombstone revisions, preventing delete/recreate ABA.

Preparation canonicalizes a closed packet and closes the snapshot. Illegal
reuse and cross-thread use fail. A read/prepare failure retires the attempt.
Callers must discard an attempt whose semantic invocation failed; catching that
failure and preparing its partial writes is outside the lifecycle contract.
Immutable dependencies may be prewritten separately, with no semantic reentry.

The packet digest binds its namespace, instance, stable logical publication ID,
every point and query condition, runtime and host mutations, immutable body
digests/lengths, and exact result/continuation bytes. The bounded versioned codec
rejects noncanonical order, duplicate keys/predicates/artifacts, incoherent
overlapping observations, unconditioned mutations, malformed counts and tails.
Changing an owner/control condition or result changes the digest. The host
remains responsible for authority; a well-formed packet is not authorization.

Publication must validate all predicates and install writes in one transaction
against every writer, including host control transitions. Same-row CAS alone
does not protect disjoint-write skew. An existing identical identity resolves
before its now-obsolete conditions are checked; different content under that
identity is an integrity error. A lost response requires reconciliation that
excludes an original transaction still able to commit. An absent ledger row is
insufficient. Unknown outcome prohibits new semantic execution.

The PostgreSQL implementation will use read-only repeatable-read snapshots for
attempts, closed before publication, and short serializable validation/write
transactions. Every relevant writer must participate. Serialization failures
retry only the same packet; a fresh validation then either succeeds or observes
a genuine conflict. Publication-specific reconciliation fences must be acquired
before the resolving transaction's snapshot. They must not be held while Blue
executes. PostgreSQL documents these isolation and lock lifetimes in its
[transaction isolation](https://www.postgresql.org/docs/current/transaction-iso.html)
and [advisory locking](https://www.postgresql.org/docs/current/explicit-locking.html#ADVISORY-LOCKS)
references. Actual overlap, false-conflict and resource-bound tests remain required.

`CoordinationRecordContractTest` exercises immutable ownership and digest
coverage, inconsistent predicate rejection, tombstones, blind-write rejection,
every truncated encoding, physical decoder limits, read-your-writes tracking,
scope release on failure, and thread isolation. These are contract controls,
not fabricated runtime or application qualification scenarios.

The source shape ceiling is updated to the measured source count for these
three new public types. API boundary, shape and artifact gates remain enabled.
The private runtime family adapters and public stage result/selection lifecycle
are separate outstanding increments; this port must not be advertised as a
complete consumer integration until those are wired and qualified.

Insert-once physical facts use the closed `OBJECT_PROOF` and `OBJECT_MEMBER`
families. Packet format 2 includes sorted immutable facts in the authenticated
payload; packets without facts retain format 1. Equal concurrent retention is
idempotent, different bytes are an integrity failure, and every writer must
prevent replacement or deletion. Cache-only reads do not observe semantic
absence; explicit point/range observations still retain their strict conditions.
Object entries are deliberately mutable: reference-to-body upgrades and exact
representation selection still require ordinary conditions. Shared object-entry
selection is not yet a qualified false-conflict-free integration.

SDK logical maps share a `LogicalPointStorage` attempt binding. This is an
explicit internal cross-package bridge (alongside `InsertionOrderedStorage`),
not an added application semantic API. All five closed SDK families preserve
point payloads, exact owner validation and bounded identity pins. Selecting the
complete SDK map is a complete predicate, whereas ordinary execution uses only
selected keys. The logical path enumerates canonical encoded keys and has no
shared insertion counter. SDK iteration is used by metadata export/legacy
restoration; it does not schedule work. The legacy descriptor path preserves
its previous insertion-order behavior. Neither path silently converts to the
other. Actual SDK execution/cold point restoration and independent Timeline
map writes in both publication orders are tested; complete engine control and
application stage assembly are still separate work.

Engine pending draft/selection, source pending/submitted/completed and suspended
feeder/rejection maps now have logical bindings. Payloads retain their exact
codec, original processor evidence, pinned identities and read-time validation.
Source selection uses flattened requesting-owner memberships instead of a
global pending scan. Membership additions invalidate a matching empty-root
observation; unrelated roots remain independent. The logical feeder maps have
canonical encoded-key diagnostic iteration; normal feeder selection uses exact
lane points. Feeder terminal/frontier control and the full engine remain to be
assembled before these bindings are application-qualified.
