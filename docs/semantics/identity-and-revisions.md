# Identity and revisions

Every retained whole value has a verified BlueId. The normal SDK represents it
as `ExactBlueValue`; the low-level API uses `ExactValue`. Exact values are
immutable content evidence and do not carry a `BlueCoordination` owner token.
Timeline, document, entry, and managed-draft handles are owner-bound; using one
with another runtime fails instead of silently crossing environments.

The normal `blue.coordination.sdk.DocumentSnapshot` exposes stable
`DocumentId`, epoch, READY status, exact current value, current BlueId, latest
public events, and scalar JSON Pointer helpers. Physical objects, embedded
layout, topology generations, proofs, and routing evidence are intentionally
absent from the application snapshot. Low-level host/audit snapshots expose
additional diagnostic evidence only after an explicit advanced API choice.

`DocumentId` and state BlueId are deliberately different. One `DocumentId` has
an ordered epoch history with potentially many state BlueIds. Different
DocumentIds remain independent even when their exact current state has the same
BlueId. At the current SDK boundary, initially known members are admitted as one
all-new `ManagedClosure`, operation-created drafts are new `FROM_NOW` lineages,
and a retained inactive same-lineage occurrence may be reactivated with the
known current exact state. General attachment of an independently existing or
historical lineage, historical catch-up from an operation result, and divergent
fork selection are not normal rc.3 SDK capabilities.

The normal SDK `DocumentRevision` records document identity, epoch, semantic
revision kind, before/after exact values, optional source Timeline Entry,
emitted public events, and processing gas. Initialization has no source entry;
a Timeline revision always has one. Low-level retained revisions additionally
carry root application order and catch-up-cause evidence for host diagnostics.

One Contracts-owned managed occurrence row names a source DocumentId/path,
target DocumentId, activation generation, binding policy, exact expected target
state, active status, and nullable historical cursor. Its occurrence identity
is stable across same-lineage exact-state churn; its binding identity changes
with the expected target BlueId. `ManagedOccurrenceInventory` retains active
and inactive rows and delegates all identity derivation and assertion checking
to Contracts. Removing an active row allocates its inactive same-lineage
successor at generation plus one. Later re-add activates that committed row
without incrementing again or reusing the retired occurrence/checkpoint
lineage. Failed transitions publish no generation.

The retained legacy temporal coordinator additionally has an
`EmbeddedEpochCursor` and can characterize broader current/historical attachment
and catch-up. Those cursor semantics are compatibility evidence, not an
operation-created draft feature of `BlueCoordination.inMemory()`.

The journal owns global and per-Timeline sequence numbers. Failed append parsing
does not consume either sequence or logical time. Failed top-level admission
does not publish a document, route, object, or metric fact.
