# Managed Process Embedded documents

The only document-dependency model is the effective `Process Embedded` graph
derived from `paths` and `collectionPaths`. Ordinary nested maps, lists, and
large values remain inline unless that effective contract establishes a process
boundary. The runtime does not add a second authored link protocol, target set,
wave model, consistency mode, or SCC planner.

## Authored SDK boundary

Applications enumerate every initially known managed lineage in one
`ManagedClosure` and bind every effective occurrence with
`bindOccurrence(sourceAlias, canonicalPath, targetAlias)`. An authored bound
slot may be absent; compilation installs the preliminary managed reference. If
the slot already contains a materialized value or reference, it must agree
exactly with the named target. Every effective concrete occurrence requires one
complete, unique binding.

Several paths may bind to the same target alias when several occurrences share
one stable lineage. Independent lineages require different `DocumentId` values
even when their current BlueIds are equal. Existing cycles belong in the same
initial closure; callers provide finite member/path lineage evidence, never
SCCs or cyclic proofs.

An operation may add a genuinely new `FROM_NOW` lineage with one exact
`ManagedDocumentDraft`, matching `request.managed(...)` evidence, and every
effective `expectOccurrence(...)` path. That rc.3 lane is not general imported
history or dynamic multi-member cyclic admission. Its operation target must be
independently processable and non-cyclic, and its effective catalog cannot cross
a cyclic-set member. See the
[SDK developer guide](../guides/developer-guide.md) for the complete workflow.

The draft's exact initial value must contain `/documentId` equal to the draft's
stable `DocumentId`. Every expectation in one call is sourced by the current
operation target; there is no source-draft alias for declaring a new
child-of-a-new-child edge in that same call. Grow a nested acyclic topology in
sequential applied operations, or admit an already interdependent topology as
one initial closure.

Every processable document has a stable `DocumentId` and immutable epoch
history. A state BlueId identifies one exact state, not the continuing history.
Each selected embedded managed document is processed separately from its own
exact state, using the ordinary document-processing function with that document
as Root. A document is completely unaware of documents that contain it: no
parent identity, containing path, or reverse-containment graph is ambient
processor input. Different DocumentIds remain independent even when their
current state BlueIds are equal.

The immutable `ManagedOccurrenceInventory` retains complete Contracts-owned
occurrence rows, including inactive reservations. Contracts remains the only
authority that derives or verifies occurrence, binding, component, invocation,
and exact-state identities. Active rows project into the SCC/condensation view;
all authoritative rows define the connected affected cohort so removal and a
later reactivation cannot silently disconnect durable closure state.

Cycles do not select a different Coordination mode. Acyclic documents and
documents in cyclic components use the same document-processing function and
the same Contracts closure execution context. Contracts schedules active SCCs
and validates convergence; Coordination does not add a recursive parent walk,
wave mode, or second cyclic scheduler.

MyOS may retain one ordered feeder/window for a public Root over the union of
the Root Timeline and all active embedded Timelines. That wider source surface
does not merge document execution. Exact direct deliveries select affected
documents, connected closure cohorts execute independently, and a
`NeedsResources` result prevents overtaking only in its Root lane. Disconnected
public Roots may continue.

A committing connected-closure result publishes atomically through a
copy-on-write store swap. The transaction fences every selected document's
durable head and epoch plus the relevant graph generations, and publishes
occurrences, components, subscriptions, routes, checkpoints, outbox, and the
idempotency receipt together. Per-document durable heads and epochs therefore
fit directly; a containing document is not the durability owner of an embedded
document.

Activation policy is admission metadata, not another field on the canonical
`Process Embedded` contract. At the normal rc.3 SDK boundary, top-level
document/closure admission supports `FROM_NOW`, full-history, and exact-frontier
policies, while an operation-created occurrence supports only a new `FROM_NOW`
lineage. Attach-current and passive-snapshot remain vocabulary for broader
host/temporal profiles and are rejected by current high-level admission. A
document with no effective contracts and no processable descendants remains
ordinary content unless explicitly admitted as a managed process.

Removing an active occurrence preserves history and atomically replaces its row
with one inactive same-lineage successor at exactly generation plus one. That
successor has fresh Contracts-derived occurrence and binding identities and is
output-only for the removing invocation. A later invocation may reactivate the
committed successor with the retained lineage's current exact state without
another generation or occurrence-identity change. Same-invocation
remove-then-re-add and different-lineage retarget remain unsupported. Broader
known-historical-state catch-up and divergent fork selection belong to the
legacy temporal compatibility model below, not the rc.3 managed-draft lane. The
inventory's active projection supports SCCs without changing the ordinary
per-document processing contract.

## Legacy compatibility profile

The earlier `DefaultCoordinationEngine.create()` profile represents containment
with `EmbeddingBinding`, per-occurrence `EmbeddedEpochCursor` values, and a
private parent/path `EmbeddedEpochInput`. It commits a child and its parents as
separate document-local transitions and rejects cycles. Those mechanisms remain
available for compatibility, but they are not the Contracts 1.0 processing or
publication model described above.
