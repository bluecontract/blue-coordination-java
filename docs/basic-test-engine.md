# Compact `basicTest` Coordination engine

## Purpose and boundary

The implementation in
`src/basicTest/java/blue/coordination/basic/engine` is a focused, in-memory
Coordination host around the frozen public Language, Contracts, BEX, and
Repository APIs. It is separate from the older `src/main` engine and from
`myosDemoTest`.

Its complete operation is intentionally compact:

```text
one exact Timeline Entry
    -> select autonomous document Roots
    -> call frozen Contracts once per selected Root
    -> commit exact document revisions
    -> synchronize Process Embedded document sessions
```

The design is one journal, one route index, one session per autonomous
document, one frozen call per selected Root, one revision stream per document,
and one cursor per parent link.

## Core invariants

1. A request and its Timeline Entry are exact whole Blue objects.
2. The entry points to the request by BlueId and is journalled once.
3. Requests, Timeline Entries, and ordinary nested values are never split.
4. Only effective `Process Embedded` boundaries create autonomous documents.
5. Every autonomous process has one stable `DocumentId` and one session.
6. Routing uses operation, channel, Timeline, and actor.
7. Every selected autonomous Root crosses frozen Contracts exactly once.
8. Existing child source operations are not replayed during parent catch-up.
9. Each parent link applies child revisions through its own cursor.
10. Pre-publication rollback restores documents, graph, receipts, journal, and
    logical clock together.

## Identity model

`DocumentId` identifies the stable real-world process or session. It remains
constant while the document changes. A BlueId identifies one exact immutable
value or state; revisions of one document normally have different BlueIds.
Content deduplication by BlueId must therefore never merge sessions that have
different `DocumentId` values.

History also has two independent order domains. `sourceOrderKey` preserves the
original Timeline order. `rootApplicationOrder` records the later contiguous
order in which a particular parent integrated revisions. `CatchUpCause` binds
those later applications to the attachment that caused catch-up.

## Components

| Component | Responsibility |
|---|---|
| `BasicCoordinationEngine` | Synchronized facade and transaction boundary |
| `FrozenBlueRuntime` | Frozen API adapter, initialization, delivery, PROCESS |
| `WholeObjectStore` | Immutable, content-addressed whole values keyed by BlueId |
| `WholeRequestEntryFactory` | Exact requests and structurally shared Timeline Entries |
| `InMemoryTimelineJournal` | Single ordered journal and immutable frontier |
| `OperationRouteIndex` | Direct operation/channel/Timeline/actor lookup |
| `BasicDocumentProcessor` | Admission, one frozen transition, companion verification |
| `EmbeddedOnlyLayoutBuilder` | Autonomous boundary discovery and structural sharing |
| `EmbeddedGraphCoordinator` | Links, catch-up plans, cursors, nesting, propagation |
| `InMemoryDocumentStore` | One mutable session record per stable `DocumentId` |

## Storage and fragmentation policy

`WholeObjectStore` retains a canonical semantic representation, an optional
provider representation, and purpose metadata for each exact BlueId. Equal
immutable values are stored once. The provider may use verified pure
references, but API reads and revision history retain the complete semantic
value.

An ordinary nested document remains inside its parent. A `Process Embedded`
child is retained as one whole object and represented by an exact reference in
the parent's physical shell. No request field, workflow body, list item,
ordinary field, request, or Timeline Entry becomes a fragment.

## Start, append, route, and dispatch

Starting a document parses and preprocesses the authored YAML, resolves its
exact snapshot, invokes frozen Contracts initialization, builds the
embedded-only layout, performs one initial owned-subscription projection,
creates epoch zero, and compiles routing rows. Top-level `start` rejects a Root
that already contains active embedded children; attachment must establish the
cause and historical cutoff explicitly.

Append is storage only. It retains or reuses one exact whole request, reuses a
compiled event template, replaces only the timestamp, predecessor, and request
reference using structural sharing, retains one exact Timeline Entry, and
appends one journal record. It does not run a document.

Dispatch performs a direct route-index lookup, skips already committed
delivery receipts, requires selected sessions to be ready, snapshots the
transactional state, prepares one transition per selected Root, commits each
at its expected epoch, reconciles embedded links and child revisions, and only
then publishes receipts.

## Frozen PROCESS boundary and companion

For each selected Root, `BasicDocumentProcessor` calls
`processForPlatformCommit` exactly once with the current processing Root, a
pure reference to the event, the current epoch and order key, and the retained
active subscriptions. Frozen Contracts returns the processing result and one
exact `PlatformCommitCompanion`.

The host verifies the companion's Root, event, epoch, order, commit decision,
and subscription delta. There is one initial subscription projection and zero
complete post-PROCESS projections. Dynamic parent route membership fails
closed.

For a paired retire/re-add of an unchanged route, the established interval is
retained because the live companion's invocation-local checkpoint/dependency
identities fail the next frozen verifier. Round 9 additionally requires the
retired route to match the route actually established in the active map; a
matching scope/channel key alone is insufficient.

## Autonomous ownership caveat

The engine keeps three useful representations:

```text
semanticRoot   complete exact parent and child state
rootShell      identity-equivalent parent with exact child references
processingRoot parent data with autonomous child executable metadata removed
```

The processing projection prevents parent PROCESS from executing child-owned
workflows, but it is not guaranteed to preserve the semantic Root BlueId.
Round 9 tested the exact identity-preserving shell. Frozen delivery derivation
rejected it because materializing the child reference exposes the child's
external channel while that interval is correctly absent from parent
ownership.

The smallest missing frozen capability is: process this exact semantic Root
while excluding specified autonomous child-owned subscription surfaces. Until
that public boundary exists, the engine deliberately keeps
`autonomousOwnershipProjection`. Exact evidence is in
[`FROZEN_AUTONOMOUS_OWNERSHIP_GAP.md`](FROZEN_AUTONOMOUS_OWNERSHIP_GAP.md).

## Same and shared documents

When an attachment names an existing `DocumentId`, the supplied child must
have the exact original initial BlueId. The existing session and revision log
are reused; initialization and source PROCESS are not repeated. Supplying the
current processed state or a conflicting original body is rejected atomically.

The same child may be linked to multiple parents. It still has one session and
one source PROCESS per entry. Each parent has an independent link, revision
cursor, and reaction history, so one child revision may be applied once to
each parent without recomputing the child.

Detach records the relationship cursor, reattachment resumes from it, replacing
one occurrence with a different child is explicit, and a prospective cycle is
rejected before publication.

## Historical catch-up

The implemented modes are `BIRTH_AT_ATTACHMENT` and `IMPORT_FULL_HISTORY`.
`IMPORT_FROM_FRONTIER` fails closed because it needs explicit durable cursor
and provider-completeness evidence.

For an existing child, attachment verifies the original initial identity and
applies eligible immutable child revisions to the new parent without replaying
source operations. For an unseen child, the engine admits and initializes it
once, processes eligible journal entries through the immutable attachment
frontier once, applies the resulting revisions to the parent, and recursively
completes newly discovered nested children before the outer Root becomes
`READY`.

The frontier contains the global journal sequence and per-Timeline positions.
A later append with an older timestamp cannot enter an already completed
window. This historical catch-up is a Coordination extension; in this module,
the in-memory journal is the source of completeness.

For `Root -> Emb1 -> Emb2`, an Emb2 revision is applied once to Emb1 and the
resulting Emb1 revision once to Root. The NBA scenario uses the same model: one
autonomous Game history is processed once and integrated by independent host
cursors regardless of whether the Game or host existed first.

## Parent reaction and transaction semantics

A parent with `coordinationApplyEmbeddedRevision` receives one exact
processor-managed Timeline Entry and can run business logic through frozen
Contracts. Otherwise, the engine performs exact structural child-state
materialization. A receipt for every link/revision pair prevents duplicate
application.

Before publication, dispatch snapshots sessions and revisions, the embedded
graph, catch-up plans, delivery and application receipts, the journal mark,
and logical clock. Failure restores all of them and rebuilds routing from the
restored sessions. A retry after committed publication is idempotent through
the delivery receipt.

The immutable `WholeObjectStore` is intentionally outside rollback. Twenty
retries of the same deterministic failed transition stabilize immediately at
the same object count because content addressing deduplicates them. Distinct
failed results can leave unreachable immutable objects; retention is a host
policy rather than a reason to copy the complete store on every transaction.

## Performance design

The current fast path uses one long-lived frozen runtime, high-throughput Blue
caches, retained resolved snapshots and `FrozenNode` trees, memoized BlueIds,
path indexes, structural sharing, exact request reuse, cached entry templates,
one direct route lookup, one frozen invocation per selected Root, reference-only
event input, one initial subscription projection, the exact commit companion,
reusable embedded layout plans, contiguous revision lists, and one cursor per
parent link.

The result is sub-millisecond append and routing, sub-millisecond Counter host
overhead, and low tens-of-milliseconds host overhead for large processed
documents. Multi-second user-visible operations remain dominated by the frozen
Language/Contracts/BEX invocation, not by Coordination routing or storage.

## Unsupported features

The compact lane intentionally does not support dynamic parent subscription
membership, `Process Embedded` collection declarations, inferred arbitrary
history frontiers, durable provider completeness, top-level start with active
embedded children, parallel dispatch through one engine instance, distributed
transactions, or an exact-Root autonomous ownership mask. These limitations
fail closed; there is no hidden fallback planner or processor.
