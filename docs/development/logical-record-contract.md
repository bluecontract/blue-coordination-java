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

The complete logical SDK assembly is now available through
`RootedCoordinationStorage.Configuration`, `openLogical` and `LogicalScope`.
It binds document, object, route, source, pending, SDK and engine control families
to one caller-owned attempt. Stable configuration is checked on open; clocks,
Timeline registrations, source terminal/frontier and scheduling records are
selected lazily. `stage()` flushes all selected families and retires the runtime;
the host subsequently prepares and publishes the attempt. Closing a stored plan
map releases its view rather than clearing durable plans. The old descriptor
assembly remains supported and cannot silently export a logical scope.

Root fairness uses per-owner turn, completed-history round and isolation records in the logical
path. A multi-root scheduler chooses the eligible root with the fewest completed
history rounds, with canonical order breaking ties. Completing one owner never
clears another owner's fairness record. The resident global-round convenience
policy remains unchanged. Legacy
whole-journal processing and authored timestamps still select their global
control records when actually used. Logical opening does not select those
records. Root stages use an explicit included-owner scope for history selection, capture
and publication validation. Each owner's complete plan membership and exact
pending/due points establish canonical local order without consulting unrelated
sessions or a global due prefix. Terminal joins preserve this scope too. The
legacy whole-runtime drain retains its exclusion-based scheduling API.
Submitted source continuation format 3 records included owners; unchanged
exclusion-based packets retain exact format 2. Both decode canonically.

`controlledRepository(...).openLogical(...)` carries the existing explicit
library-writer origin contract across records and immutable artifacts. The host
must enforce this boundary for every writer. It preserves indexed history
invariants without a global receipt rebuild at each cold open. The strict raw
factory retains complete import verification. Neither factory confers host
publication authority.

`LogicalCoordinationStorageTest` compares exact resident/cold entry results,
heads and receipt identities, publishes two complete independent live stages
prepared from one snapshot in both orders, publishes retained parent history
alongside unrelated live work in both orders, and resumes a suspended parent after
cold source admission and idempotent admission replay. Every retained stage in
the continuation test closes its SDK/engine owner. Its bounded selected-map
capacity is 2048 because provider-backed retained-state probes select empty
logical buckets too; capacity exhaustion still retires an attempt. These tests
use the separately coherent host journal fixture. A library-owned logical
journal, owner/topology stage envelope and real Mini executor/finalizer remain
required before any S2/D2/H application acceptance claim.

Logical SDK opening wraps the immutable store in an attempt-confined dependency
tracker. Every consumed or retained object is digest/length checked and becomes
a mandatory artifact in the prepared packet, including when the caller passes
an empty artifact list to `prepare`. The existing controlled-writer capability
is preserved through this wrapper. `CoordinationRecordAttempt.requireArtifact`
coalesces identical requirements and retires an attempt on conflicting lengths.
The publisher must validate these artifacts atomically with the record packet;
missing bytes must not become a successful publication with dangling references.

The SDK `openLogical` overload without a host journal now assembles the library's
journal in the same logical attempt. Exact entries, append positions, Timeline
positions/heads, external-order positions and coverage/control are library-owned
records; acceptance becomes visible only when the enclosing packet commits.
Discarding an attempt exposes no accepted input. Cold reads retain exact original
rows, duplicate detection, predecessors, ordering, rollback and availability.
The explicit host-journal overload remains available for coherent external stores.

This increment preserves the existing global append sequence/revision semantics:
competing input acceptance may conflict on that authoritative control. It does
not yet narrow root selection or historical completeness to scoped journal
predicates. Those reader changes, atomic application ingress/finalization, and
real application concurrency qualification are still required.

Logical root stages select the exact active forward source Timelines from the
root's retained view, plus its retained causal entry by external-order point.
Peer acquisition resolves each peer's own input scope. Library-owned journal
point verification checks complete exact indexes/predecessors without reading
the global append frontier. Legacy host journals retain their strict state checks.

Logical source discovery uses complete per-Timeline external-order predicates
strictly below the original parent cutoff. Its `sourceSurfaceIdentity` starts
with `scoped:sha256:` and binds the original source surface, cutoff, source set,
and exact accepted prefix; `journalRevision` is zero in this explicitly scoped
mode. Fresh selection recomputes the fingerprint and validates the same logical
read set. Availability is separately conditioned. Unrelated or post-cutoff
appends leave this proof valid; relevant inserted input or unavailable history
rejects publication. This changes operational proof identity, not Blue input,
ordering, gas or result bytes. Previous logical journals without the new complete
index marker use conservative point/state validation; their next relevant append
populates the missing Timeline index without changing accepted events.

All complete cold SDK fixtures now publish journal/runtime changes together.
The remaining legacy whole-journal drain/diagnostic gateways intentionally retain
whole-journal conditions; the application must use the bounded stage gateways.

## Frozen selection and detached completed evidence

`processing().selectNextStage(root)` or `selectStage(root, acceptedInput)`
returns a single-use, thread-affine `SelectedProcessingStage`. Its immutable
`ProcessingStageContext` identifies the selected kind, exact journal/retained work
causes, owner predecessors (head, epoch, history, graph generation and closure),
and captured rooted invocation contexts. These are library-derived owners,
separate from immutable downstream witnesses. SDK/engine operations are blocked
until the token executes or the owning scope is discarded. No future selection
is performed by `execute()`. Existing `process*Stage` methods wrap this boundary.

The completed result carries that context, the complete entry/result owner union
(including split members), and graph-change invalidation. The host must condition
all owners; if the result expands authority it can discard the unpublished attempt,
acquire the complete union and retry from fresh durable state. Selection is not a
host claim, and the context does not grant publication rights. The named immutable
context is the only new core type permitted in the three SDK stage facade signatures.

`ProcessingStageStorage` canonically encodes/decodes detached exact SDK evidence
and supplies a stable selected-transition identity that excludes elapsed time and
host attempt counters. Decoding restores observations only; it does not manufacture
a runtime, selection token or authority. A caller's physical byte bound applies.
The continuation after publication is a fresh selection under the same root;
completed evidence makes no future readiness or command-terminal promise.

The direct/next-root boundary includes live, local retained, managed history and
join work selected there. Source-prerequisite stages now have a separate frozen boundary below;
application command continuation integration still requires host treatment.

A selected transition identity is not sufficient to name a durable prefix:
a waiting observation and its later completed execution can share that selection.
`ProcessingStageStorage.resultIdentity` additionally binds the exact disposition
and result, excluding only outer elapsed time. Hosts bind that result identity
to the original command and prior committed prefix; SQL retries retain the exact
prepared bytes, including their measurements.

## Frozen source stages

`AdvancedCoordination.selectSourceHistoryStage` exposes `SourceHistoryStageContext`
before ADMIT or PROCESS. It binds the complete original prerequisite, known source
owners and each existing predecessor, with explicit absence for an unadmitted
lineage. The requesting parent and immutable source witnesses are not implicit
publication owners. The scope-bound token is single-use and thread-affine; other
SDK/engine work is rejected until it executes or the owner is discarded.

`SourceHistoryStageResult` retains the complete entry/result owner union, including
newly born source members. A failed physical publication makes the owner
noncommittable and retires it. A genuine missing-resource admission remains an
explicit wait with its original processor demands. Source execution never retries
the parent or selects its next action. Reconciliation returns retained exact
source results without another ADMIT/PROCESS.

`SourceHistoryStageStorage` uses the existing closed prerequisite/admission/drain
codecs through the reviewed rooted-storage bridge. It restores observations after
owner retirement, rejects malformed/noncanonical bytes, and enforces caller byte
and depth bounds. It grants no runtime or publication authority. Tests cover
admission, LIVE below the frozen cutoff, managed history, root-local retained
history, absent-source waits, complete ownership and thread/scope retirement.

## Accepted-input cutoff selection

`ProcessingGateway.selectNextStageThrough(root, entry)` selects one causal stage
no later than that original accepted entry's full external order. The String
overload restores the accepted cutoff by point lookup in a cold owner; it never
submits or authors an entry. The context retains `inclusiveEntryBlueId`, including
for a cutoff-relative NONE or WAITING result. Earlier local/managed prerequisites
remain eligible; later journal input, join fences and retained causes do not
become obligations of the earlier command.

Logical Timeline prefix predicates exclude later entries without reading a mutable
Timeline head. A future append on the same Timeline therefore does not invalidate
an already computed cutoff packet. Hosts still condition all actual owner heads
and topology; the cutoff does not weaken genuine conflict checks.

Unbounded stage evidence retains its existing format-1 bytes. Bounded contexts use
format 2; canonical decoding binds the cutoff and preserves detached observations.
The existing five-argument context constructor and unbounded gateways remain.
Focused tests cover earlier/current/future inputs, cold cutoff lookup, closed
codec binding, and committing a held packet after a later same-Timeline append.
Measured shape: 355 production sources, 92,038 lines, 106 public API types.
