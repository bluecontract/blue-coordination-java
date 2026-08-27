# Retained managed-epoch catch-up

This page specifies the retained managed-epoch profile implemented by the
`3.0.0-rc.5` Coordination source. The profile is staged, unpublished, and
non-production. It is not part of the published `3.0.0-rc.4` Maven Central
artifact and this page is not release evidence.

The source profile consumes an invocation-owned immutable Maven stage of Blue
Language/Contracts `3.1.0-rc.23`. That stage is the exact published
`3.1.0-rc.22` baseline plus the additive managed-transition receipt surface
needed here. Coordination does not use Maven Local, a sibling composite, or a
mutable checkout at execution time.

## What is retained

Every managed lineage has one stable `DocumentId` and a sequence of exact
states:

| Position | Meaning | Catch-up after attachment |
| --- | --- | --- |
| authored initial (`-1`) | Exact authored value before INITIALIZE. It is a selector/cursor sentinel, never a receipt epoch or committed revision. | Apply initialization receipt epoch `0`, then every later retained epoch through the admitted frontier. |
| initialized epoch zero (`0`) | Exact result of the one original INITIALIZE invocation. | Apply epochs `1..frontier`. |
| retained epoch (`e < current`) | One immutable committed source revision. | Apply epochs `e + 1..frontier`. |
| current head | The source lineage's exact committed head. | Activate the occurrence without a historical plan. |

The source document is never initialized again. None of its Timeline entries,
Operations, local handlers, PROCESS work, or public outbox is replayed.
Catch-up applies immutable source epoch receipts through the target occurrence
by invoking ordinary closure processing with a typed managed-revision cause.
The imported source event is not republished; only a new event emitted by the
consumer Root is public now.

Epoch position matters independently of state identity. A transition that
emits Root events while leaving the state BlueId unchanged is retained as an
`EVENT_ONLY` epoch. Equal event values remain separate occurrences, and a state
BlueId that appears at several epochs does not collapse those epochs.

## Exact matching and explicit selection

When an operation installs an exact value at an effective `Process Embedded`
path, Coordination resolves the Contracts occurrence demand against the
indexed managed lineage history:

1. a match to the selected lineage's current exact state is current and opens
   no catch-up plan;
2. otherwise one unique authored-initial, epoch-zero, or retained-epoch match
   selects that exact position;
3. a BlueId matching more than one managed lineage is
   `AMBIGUOUS_MANAGED_LINEAGE`;
4. a BlueId occurring at more than one non-current position in the selected
   lineage is `AMBIGUOUS_MANAGED_EPOCH`; and
5. an initialized or progressed value with no retained lineage proof is not
   accepted as a new authored document.

Ordinary unambiguous values need no host-authored binding array or selector.
For an ambiguous repeated state, bind all four facts with
`ManagedEpochSelector`: source `DocumentId`, source epoch, expected source
BlueId, and the target occurrence path. The selector is captured against the
operation target head before journal append, or against a new static authored
Root before atomic admission, and must be consumed by exactly that occurrence
demand.

```java
EntryResult result = blue.operations()
        .on(consumer)
        .from(ownerTimeline)
        .call("attach")
        .through("ownerChannel")
        .request(request -> request.exact("child", retainedValue))
        .selectManagedEpoch(
                "/child", sourceId, retainedEpoch, retainedValue.blueId())
        .execute();
```

The same selector can disambiguate a static authored graph without introducing
binding arrays:

```java
ClosureHandle closure = blue.documents().admitStaticProcessEmbedded(
        rootYaml,
        List.of(ManagedEpochSelector.exact(
                sourceId, retainedEpoch, retainedValue.blueId(), "/child")));
```

The selector list is canonicalized and included in the admission publication
identity. A missing or unused path rejects the admission atomically.

A selector disambiguates retained evidence; it does not create history,
override an exact mismatch, or authorize a different source lineage.

## Complete source epoch receipts

Every committed managed source revision retained as a semantic epoch in this
profile carries a self-verifying `ManagedEpochReceipt`. The generic
`DocumentRevision.managedEpochReceipt()` accessor returns an `Optional`; for
these retained source revisions its value must be present. Absence is missing
evidence, not permission to reconstruct a receipt. The receipt's canonical
identity binds:

- source `DocumentId`, epoch, revision kind, before BlueId, and complete exact
  after-document;
- original cause identity and, when present, the exact source Timeline Entry
  and canonical `ExternalOrderKey`;
- the Contracts managed-transition receipt identity and closure commit
  companion identity;
- the complete ordered, duplicate-preserving Root event occurrence sequence;
  and
- the transition's admitted processing gas.

Each `ManagedEventOccurrence` retains its transition-local ordinal,
invocation-global occurrence ordinal, source document, Contracts occurrence
identity, exact event and event BlueId, and whether the source Root was public.
This is the complete Root sequence, not the public-event projection. Catch-up
must not reconstruct it from `DocumentSnapshot.publicEvents()`.

The present revision receipt has the same identity as the corresponding audit
receipt. SDK callers may inspect source evidence with
`advanced().auditManagedEpoch(sourceId, epoch)`,
`auditManagedEpochs(sourceId)`, or
`auditManagedEpochReceipt(receiptIdentity)`.

### Cyclic successor proof boundary

A source epoch whose `afterBlueId` is a cyclic member identity (`MASTER#n`)
requires two independently retained inputs: the immutable epoch receipt with
its exact after-document, and the authenticated complete `CyclicSetProof` for
that successor component. The proof is provider-completeness evidence, not a
field folded into the epoch receipt or its identity. Coordination opens it by
the exact successor member BlueId and passes a defensive copy into the ordinary
Contracts managed-revision invocation. Contracts verifies the claimed member,
exact body, and complete placeholder set before processing.

The same rule applies when a successful eventless application creates a new
`EMBEDDED_REVISION_APPLICATION` epoch whose before and after cyclic BlueIds are
equal. That epoch is durable source evidence for a later occurrence only while
the matching complete cyclic proof is also available.

Proof acquisition has three closed pre-PROCESS failures:

- `NOT_FOUND` publishes `WAITING_FOR_HISTORY` with
  `MANAGED_EPOCH_CYCLIC_PROOF_MISSING`;
- `UNAVAILABLE` publishes `WAITING_FOR_HISTORY` with
  `MANAGED_EPOCH_CYCLIC_PROOF_UNAVAILABLE`; and
- `INVALID_EVIDENCE` publishes `BLOCKED` with
  `MANAGED_EPOCH_CYCLIC_PROOF_INVALID`.

All three retain the same plan cursor and frontier and publish no consumer head,
history, epoch receipt, or application receipt. The in-memory
`restartFromStores()` seam preserves this result only because the same live
whole-object/proof store survives. It is not evidence that a new process can
reconstruct provider completeness.

## Occurrence plans, cursors, and barriers

A historical attachment creates one immutable
`ManagedOccurrenceCatchUpPlan` per occurrence activation generation. Two paths
to the same source therefore have separate cursors and plans while reading the
same immutable source receipts. A detach/re-add or retarget creates a new
generation; it never reuses a retired generation's cursor.

Detach cancels incomplete plans, including `BLOCKED` plans, for that exact
generation. A `COMPLETE` plan's status and snapshot identity remain unchanged
immutable historical audit.

The plan records the admitted source position, `nextSourceEpoch`,
`requiredThroughSourceEpoch`, source and consumer identities, occurrence path
and identity, generation, cause, and typed status. Its stable plan identity is
separate from its changing progress snapshot identity.

All plans introduced for one consumer by one exact graph-changing cause belong
to a `ManagedCatchUpBarrier`. The barrier is complete only when every member
plan is `COMPLETE` or deterministically
`CANCELLED_OCCURRENCE_RETIRED`. `WAITING_FOR_HISTORY` and `BLOCKED` are visible
barrier states, not readiness.

The graph-changing consumer transition commits before the suffix is applied.
While its barrier is active, the consumer has separate committed and ready
heads and remains non-READY, normally with session status `CATCHING_UP`.
`WAITING_FOR_HISTORY` and `BLOCKED` classify plan/barrier evidence; their typed
diagnostic is also present in the readiness audit. Ordinary
`snapshot()`/`document()` reads expose only the last READY head. Operational
tools can inspect the committed head and active barriers with
`advanced().auditDocument(id)` and
`advanced().auditManagedDocumentReadiness(id)`. Promotion to READY occurs only
after the barrier completes; partially caught-up state is never presented as
application-ready.

## Ordering, bounded drain, and live extension

The attaching Timeline Entry is journaled and its graph change commits before
managed epoch applications are scheduled. The due index orders work from
retained evidence: barrier cause order, source receipt order, source
`DocumentId`/epoch, consumer `DocumentId`, path, and activation generation.
Call submission order is not an alternate replay order.

An active plan is a moving barrier until it reaches a quiescent source
frontier. If the source commits a later genuine epoch while catch-up is open,
that commit atomically extends `requiredThroughSourceEpoch`. The consumer must
apply the extension before becoming READY. A direct consumer entry cannot
overtake its active barrier, while unrelated document lanes may continue.

Candidate selection expands through the impacted consumer's plan index. A
`WAITING_FOR_HISTORY` or `BLOCKED` barrier sibling suppresses new due work only
for that consumer; independent consumers remain eligible. This uses consumer-
local indexes and never scans every plan.

`processing().drain(new DrainBudget(maxTransitions, maxEntries))` pauses only
between selected entries or committed PROCESS transitions. The plan cursor and
application receipts are durable within the in-memory store, so a later drain
continues at the exact next epoch. One frozen INITIALIZE or PROCESS call is
never preempted to satisfy a budget.

The existing scheduler retains a fair external-versus-managed lane turn. If
both lanes remain runnable, repeated `DrainBudget(1, 1)` calls give bounded
progress to genuine source commits and to the catch-up cursor. If the managed
lane is empty because evidence waits or blocks, the external lane proceeds. A
finite source stream therefore eventually reaches READY after it stops; an
unbounded stream keeps extending the frontier and remains truthfully
`CATCHING_UP`. Direct work for that consumer remains behind the barrier.

## Application and cycles

For each due epoch, Coordination verifies the public receipt against the exact
Contracts transition receipt before PROCESS. It then supplies the typed
managed-revision cause to the ordinary closure processor. Resulting consumer
revisions, complete epoch evidence, occurrence changes, plan cursor,
application receipt, readiness/barrier state, routes, checkpoints, and outbox
changes publish through one fenced store transaction.

A handler in that application may introduce another managed occurrence. Its
typed resource demand runs through the same bounded automatic exact-resolution
and retry loop used by ordinary closure publication. If the resolved target is
an existing historical lineage, its occurrence plan extends the application's
owning barrier in the same transaction as the consumer revision, original
cursor advance, graph/components, and epoch/application receipts. Unresolved or
ambiguous evidence publishes neither the occurrence nor a barrier extension.
This candidate supports that nested existing-lineage case. It does not support
creating a genuinely new authored lineage from a nested handler during catch-up;
that case remains fail-closed.

Catch-up does not introduce a parent-recursion engine or a second cyclic
scheduler. If the affected managed closure is cyclic, Contracts uses its
ordinary closure/cyclic processing and normal gas/convergence limits. The
source epoch receipt remains immutable input; applying it through one or many
occurrences never reinitializes, advances, or reprocesses the source document.

One suspended application may report several missing exact values for several
occurrence paths. `ManagedEpochApplicationAttempt` retains the automatic retry
count and a typed `ManagedOccurrenceResolutionIssue` for each unresolved
resource demand, joined by exact demand identity. A host may register each
verified immutable value with the exact-node provider and resume the same
canonical work. It must not push content, events, bindings, or a document patch
directly into the process. No semantic state publishes until ordinary closure
processing resolves the complete required set. Ambiguous lineage/epoch,
unproven history, selector mismatch, and invalid-authored statuses remain
fail-closed; they are not reclassified as missing uploadable content.

### Same-epoch component representation rebind

Ordinary source history is immutable even when cyclic finalization changes a
current component representation. The supported positive boundary is a finite
two-member cycle formed by one retained application: the consumer advances on
the ordinary revision lane, while the distinct source member may be finalized
indirectly into the shared cyclic representation at its unchanged local epoch.
Coordination accepts that representation-only update only inside the active
managed-application transaction when the exact Contracts result and commit
companion authenticate it, the before/after representation BlueIds differ, no
Root event is emitted, and the route surface is unchanged.

The transaction updates the source's current component representation,
component state, lineage current BlueId, and continuation evidence together. It
does not append, replace, or reinterpret a source revision, source epoch,
managed epoch receipt, or source event. This is not authority to rewrite
arbitrary indirect peers: merging a consumer with an already cyclic,
multi-member source component fails closed when the result would require a
source member's semantic epoch to advance or be reinterpreted. A direct target,
eventful transition, wrong invocation, missing receipt/companion proof,
malformed component evidence, or larger merge outside the proven
representation-only boundary remains a strict rejection with no partial
publication. This exception does not weaken BlueId, cyclic proof, BEX, or
Repository semantics.

## Failure, retry, and response loss

Evidence is fail-closed before PROCESS:

- a missing required source epoch becomes `WAITING_FOR_HISTORY` with
  `MANAGED_EPOCH_RECEIPT_MISSING`;
- a missing or temporarily unavailable complete cyclic successor proof becomes
  `WAITING_FOR_HISTORY` with `MANAGED_EPOCH_CYCLIC_PROOF_MISSING` or
  `MANAGED_EPOCH_CYCLIC_PROOF_UNAVAILABLE`;
- an available but invalid cyclic successor proof becomes `BLOCKED` with
  `MANAGED_EPOCH_CYCLIC_PROOF_INVALID`;
- a missing Contracts transition or a wrong document, epoch, before/after
  BlueId, original cause, event occurrence, gas, or receipt identity becomes
  `BLOCKED` with a typed code; and
- for every evidence-failure case the selected due row is removed, the plan and
  barrier publish the same cursor, and consumer heads do not change. The
  immutable registered work row remains as audit evidence. Other consumer lanes
  remain eligible in the same drain.

When missing evidence is repaired, the exact same unapplied work identity
atomically regains both its pending-plan and due-index entries. The immutable
registry row is not replaced, and partial or mismatched index state fails
closed. A waiting or blocked sibling continues to gate other work for the same
consumer.

A non-committing Contracts attempt publishes no consumer revision, cursor, or
application receipt. The exact work remains retryable and a failed consumer is
excluded for the rest of that drain so independent consumers can progress.

`DrainResult.managedEpochApplications()` reports only applications newly
committed by that drain. `managedEpochApplicationAttempts()` retains the typed
processor attempt for committed, rolled-back, suspended, and replayed work. Its
`automaticRetryCount()` and `managedOccurrenceResolutionIssues()` are typed
host-persistable progress; branch on each issue's `ResolutionStatus`, not its
diagnostic text.

On success, `ManagedEpochApplicationReceipt` binds the work, plan, source
receipt, Contracts invocation/result, commit companion, committed consumer
revision, and resulting source cursor. If the store commits and route
publication then loses its response, retry/reconciliation finds that receipt,
rebuilds the route material, and does not call PROCESS again. Control-plane
reconstruction in the same live in-memory engine has the same rule.

This is not a fresh-process durability claim. `BlueCoordination.inMemory()`
loses all stores on process exit. A durable host must atomically persist the
journal, exact document history, source and application receipts, plans,
barriers, cursors, graph state, work index, commit companions, routes/outbox,
and provider-completeness evidence before making a restart guarantee. For every
cyclic source after-state, that last category includes the authenticated
complete proof and its exact member body, restored before any catch-up work is
made eligible.

## Audit surface

The rc.5 source profile exposes these read-only SDK diagnostics through
`AdvancedCoordination`:

- `auditManagedOccurrence(sourceDocumentId, sourcePath)`;
- `auditManagedEpoch(documentId, epoch)` and
  `auditManagedEpochs(documentId)`;
- `auditManagedEpochReceipt(receiptIdentity)`;
- `auditManagedCatchUpPlan(planIdentity)` and
  `auditManagedCatchUpPlans(consumerDocumentId)`;
- `auditManagedCatchUpBarrier(barrierIdentity)`;
- `auditManagedEpochApplicationWork(workIdentity)`;
- `auditManagedEpochApplicationReceipt(applicationReceiptIdentity)`; and
- `auditManagedDocumentReadiness(documentId)` plus `auditDocument(documentId)`
  for committed-versus-ready inspection.

These methods expose authenticated state; they are not repair or mutation
commands.

## Explicit non-goals

This source profile does not claim:

- a published `3.0.0-rc.5` coordinate, stable API, production readiness, or
  completed release gates;
- Maven Local, composite substitution, a mutable sibling checkout, or remote
  fallback for staged `blue.language` artifacts;
- re-execution of source INITIALIZE or source Timeline entries;
- inference of a unique fork when a retained state is ambiguous;
- collapsing repeated occurrences, event-only epochs, or equal event values;
- arbitrary host-defined parent waves, recipient sets, SCCs, or replay order;
- cross-process durability, distributed scheduling, or exactly-once business
  operations; or
- changing Blue Language model/core/mapping, BlueId, BEX, or Repository
  semantics.

## Build the rc.5 source profile

Use a closed immutable Contracts Maven repository outside this source tree and
pin its manifest on every invocation:

```bash
./gradlew --no-daemon --no-build-cache clean releaseCheck \
  -PtestJavaVersion=17 \
  -PblueDependencyMode=immutable-staged-contracts \
  -PblueContractsVersion=3.1.0-rc.23 \
  -PblueContractsRepository=/absolute/path/to/invocation-owned/contracts-repository \
  -PblueContractsManifestSha256=sha256:<64-lowercase-hex>
```

The stage manifest must use `blue-staged-dependency-repository/1.0`, bind its
exact source commit and artifacts, and pass the build's checksum validation.
The command above is the required verification shape, not a statement that the
gate has been run for a particular checkout. Do not publish or deploy an rc.5
artifact without separate release authority.

After the required gates actually pass on a clean committed source tree, an
invocation may export the rc.5 JAR, POM, sources, Javadocs, checksums, and bound
manifest to a different immutable Maven repository:

```bash
./gradlew --no-daemon dynamicEvolutionCoordinationHandoff \
  -PblueDependencyMode=immutable-staged-contracts \
  -PblueContractsVersion=3.1.0-rc.23 \
  -PblueContractsRepository=/absolute/path/to/invocation-owned/contracts-repository \
  -PblueContractsManifestSha256=sha256:<64-lowercase-hex> \
  -PcoordinationSourceCommit=<exact-clean-coordination-head> \
  -PcoordinationStagedRepository=/absolute/path/to/invocation-owned/coordination-repository
```

The handoff rejects a dirty or mismatched source commit and a mutable/conflicting
target. Its isolated consumer resolves Coordination and Language exclusively
from their pinned stages and does not use Maven Local. See
[Immutable Coordination handoff](../development/immutable-staged-coordination.md).
