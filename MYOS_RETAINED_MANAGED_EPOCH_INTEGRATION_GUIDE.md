# MyOS retained managed-epoch integration guide

## Status and round boundary

This is the exact downstream integration guide for the retained managed-epoch
catch-up candidate. It is deliberately a guide, not a MyOS implementation.

MyOS Mini is **not modified in this round**. The read-only source characterized
for this guide is:

```text
/private/tmp/myos-simple-exact-content-ux-ebf65df
branch: feat/exact-content-ux
HEAD:   420e552f075149bf459722124f953ce3c8c57669
status: clean
```

The user's dirty primary worktree remains custody evidence only:

```text
/Users/piotr/data/myos-simple
branch: feat/dynamic-contract-evolution-milestone
HEAD:   ebf65df6bb58495766dab4dbbb71984f204f2904
```

Nothing in this guide authorizes editing, resetting, cleaning, stashing, or
switching that primary worktree.

The characterized MyOS branch consumes the immutable published baseline:

```text
blue.language:3.1.0-rc.22
blue.coordination:3.0.0-rc.4
```

`build.gradle.kts:22-35` and `gradle.lockfile:4-13` pin the exact graph.
`settings.gradle.kts:21-46` gives `blue.language` and `blue.coordination`
exclusive Maven Central routing. The protected runtime hashes include:

```text
blue.coordination:blue-coordination-java:3.0.0-rc.4
sha256:a8dc99f5bcc62902496ee3bdb5e8f085b78d9781e7928ef20f5a83b65581cd34

blue.language:blue-contracts-core:3.1.0-rc.22
sha256:7c5abb3c87273f640dcc9754d43c1a6f5f1fcb85605ed496d35c2514304031d6
```

The full published verification is retained outside source at
`build/reports/dependencies/published-blue.json`; it records exclusive Maven
Central origin, no Maven Local, no composite substitution, and the complete
protected-artifact hashes.

## Semantic boundary

The MyOS integration must preserve all of these statements:

- Catch-up consumes immutable committed managed epoch receipts. It is not
  Timeline-provider catch-up.
- It never replays a source Timeline Entry, source Operation, source local
  handler, source initialization, or source public outbox as catch-up work.
- It does not retroactively reconstruct the consumer in the past. It applies
  retained source transitions to the consumer now.
- One source lineage remains one `DocumentId` and one managed session.
- Each active Process Embedded occurrence generation has its own cursor.
- One source receipt may be applied through many occurrence-specific cursors.
- State replacement and ordered event delivery run through the ordinary
  Contracts closure/cyclic processor. MyOS never patches a parent document.
- Equal event values remain distinct occurrences. Public events are never used
  as a substitute for the complete Root-boundary sequence.
- A cyclic source after-state is usable only with a separately retained,
  authenticated complete cyclic-set proof for its exact member body. The proof
  is provider-completeness evidence, not part of the managed epoch receipt.
- The source's original public event is not republished during catch-up. Only a
  new event explicitly emitted by the consumer is public now.
- Normal reads use the ready head. The committed head is an audit/admin view.

## Candidate SDK contract used by MyOS

The retained managed-epoch candidate binds this guide to the following public
SDK names. If any name changes before the candidate receipt is frozen, this
guide and the MyOS compile characterization must be rebound together.

### Source receipt and revision

`blue.coordination.sdk.DocumentRevision.managedEpochReceipt()` returns an
`Optional<ManagedEpochReceipt>`. A present value authenticates one retained
managed semantic epoch; the generic revision type does not promise managed
epoch evidence for every possible revision. MyOS must require the value to be
present before treating a revision as complete managed-epoch evidence. Absence
is unavailable evidence, not permission to reconstruct a receipt from the
revision. A present receipt carries:

```text
receiptIdentity
documentId
epoch
kind
beforeBlueId (absent for initialization; present for later transitions)
afterBlueId
exact after document
originalCauseIdentity
source Timeline entry and SDK-native `SourceOrder` when present
Contracts transition-receipt identity
commit-companion identity
complete ordered ManagedEventOccurrence values
processing gas
```

Each `ManagedEventOccurrence` carries `managedEventIdentity`, `ordinal`,
`eventOccurrenceOrdinal`, `sourceDocumentId`, `eventOccurrenceIdentity`,
`eventBlueId`, `exactEvent`, and `publicAtSource`. An event-only successful
transition is an ordinary contiguous managed epoch even when
`beforeBlueId.equals(afterBlueId())`.

The advanced audit surface used for persistence and recovery is:

```java
Optional<ManagedEpochReceipt> one = blue.advanced()
        .auditManagedEpoch(documentId, epoch);

List<ManagedEpochReceipt> history = blue.advanced()
        .auditManagedEpochs(documentId);

Optional<ManagedEpochReceipt> byIdentity = blue.advanced()
        .auditManagedEpochReceipt(receiptIdentity);
```

When a revision's optional receipt is present, that receipt and the receipt
returned from advanced audit must carry the same `receiptIdentity`. Both
`DocumentRevision.before()` and `ManagedEpochReceipt.beforeBlueId()` are empty
for initialization. The authored pre-initialization identity used for retained
matching is separate lineage evidence; it is not a receipt before-state.

### Cyclic source after-state evidence

When `ManagedEpochReceipt.afterBlueId()` is a cyclic member identity
(`MASTER#n`), MyOS must durably retain and restore two different artifacts:

```text
the complete immutable managed epoch receipt and exact after-document
the authenticated complete CyclicSetProof for that successor component
```

The proof remains in the exact/provider-completeness store. Do not serialize it
into the receipt, selector, command payload, or a consumer patch. Before the
work becomes runnable, the replacement runtime must be able to open the proof
by the exact successor member BlueId and verify the claimed member, resolved
body, and complete placeholder set. This also applies to a same-BlueId,
eventless `EMBEDDED_REVISION_APPLICATION` epoch when that epoch later serves as
a source.

Persist these closed acquisition outcomes and codes without parsing diagnostic
text:

```text
NOT_FOUND         -> WAITING_FOR_HISTORY / MANAGED_EPOCH_CYCLIC_PROOF_MISSING
UNAVAILABLE       -> WAITING_FOR_HISTORY / MANAGED_EPOCH_CYCLIC_PROOF_UNAVAILABLE
INVALID_EVIDENCE  -> BLOCKED             / MANAGED_EPOCH_CYCLIC_PROOF_INVALID
```

All three are pre-PROCESS evidence results. They advance no cursor or frontier,
publish no consumer head/history/receipt/application, and must survive replay
as the same typed plan/barrier wait. Coordination's in-memory
`restartFromStores()` fixture only reconstructs control state around a surviving
proof store; it is not a fresh-process proof-restoration test.

### State selection

Ordinary inline values, pure references, and verified partial materializations
carry no caller-authored binding array. Coordination resolves their exact
BlueId automatically as current, authored initial, initialized epoch zero,
unique retained epoch, new authored initial, or a typed failure.

Only repeated or otherwise ambiguous retained state uses
`ManagedEpochSelector`:

```java
OperationCall call = blue.operations()
        .on(consumer)
        .from(timeline)
        .call(operation)
        .through(channel)
        .requestYaml(requestYaml)
        .selectManagedEpoch(ManagedEpochSelector.exact(
                sourceDocumentId,
                sourceEpoch,
                expectedSourceBlueId,
                targetPath));
```

`OperationCall` retains a list of selectors, so one closure may disambiguate
several target paths. A selector binds the stable source `DocumentId`, source
epoch, expected source-state BlueId, and exact target path. It is advanced
evidence, not an authored document field.

MyOS may persist the candidate's committed
`ManagedSurfaceEvidence.ResolutionKind` without reinterpretation:

```text
CURRENT_EXISTING
EXISTING_AUTHORED_INITIAL
EXISTING_INITIALIZED_EPOCH_ZERO
EXISTING_RETAINED_EPOCH
NEW_AUTHORED
```

An already-managed `CURRENT_EXISTING`, `EXISTING_AUTHORED_INITIAL`,
`EXISTING_INITIALIZED_EPOCH_ZERO`, or `EXISTING_RETAINED_EPOCH` lineage may be
resolved at a nested path while another retained catch-up application is
running. `NEW_AUTHORED` is supported only by an ordinary authored publication;
creating a new managed lineage from inside retained catch-up is not supported.

`MISSING_EXACT_CONTENT`, `AMBIGUOUS_MANAGED_LINEAGE`,
`AMBIGUOUS_MANAGED_EPOCH`, and `UNPROVEN_MANAGED_HISTORY` are fail-closed
diagnostic codes, not successful `ResolutionKind` values. MyOS stores them as
typed command/wait diagnostics and must not manufacture a committed
resolution row for them.

### Plan, barrier, cursor, and readiness audit

MyOS persists the typed immutable `ManagedOccurrenceCatchUpPlan` and
`ManagedCatchUpBarrier` values returned by Coordination. It does not infer
either value from document bodies.

The exact candidate audit calls are:

```java
Optional<blue.coordination.api.ManagedOccurrenceCatchUpPlan> plan = blue.advanced()
        .auditManagedCatchUpPlan(planIdentity);

List<blue.coordination.api.ManagedOccurrenceCatchUpPlan> plans = blue.advanced()
        .auditManagedCatchUpPlans(consumerDocumentId);

Optional<blue.coordination.api.ManagedCatchUpBarrier> barrier = blue.advanced()
        .auditManagedCatchUpBarrier(barrierIdentity);

Optional<blue.coordination.api.ManagedEpochApplicationWork> work = blue.advanced()
        .auditManagedEpochApplicationWork(workIdentity);

Optional<blue.coordination.api.ManagedEpochApplicationReceipt> application = blue.advanced()
        .auditManagedEpochApplicationReceipt(applicationReceiptIdentity);

Optional<blue.coordination.api.ManagedDocumentReadiness> readiness = blue.advanced()
        .auditManagedDocumentReadiness(consumerDocumentId);
```

The receipt/event/selector/attempt values used by ordinary host integration are
SDK-owned. These plan, barrier, work, application-receipt, and readiness values
are immutable public `blue.coordination.api` audit types returned deliberately
through the advanced boundary; MyOS must not import `blue.coordination.internal`
or Contracts processor implementation classes.

`planIdentity()` and `barrierIdentity()` identify stable definitions and remain
the durable lookup/foreign keys. They deliberately exclude mutable progress,
frontier, membership, status, and wait fields. Persist each value's
`snapshotIdentity()` as the authenticator for its current exact image: a plan
snapshot binds its cursor/frontier/status/waits, and a barrier snapshot binds
its canonical plan membership/status/waits. Never recalculate or replace a
stable identity when only its snapshot changes.

There is no separate path-based plan audit method in this candidate. To find
one occurrence, read `auditManagedCatchUpPlans(consumerDocumentId)` and select
the row whose `targetPath()` and `activationGeneration()` equal the already
persisted `OccurrenceSlot` key. Zero or more than one matching live row is a
verification failure.

`ManagedDocumentReadiness` is authoritative for:

```text
committed head epoch and BlueId
ready head epoch and BlueId
SessionStatus
typed wait code and message
active barrier identities
```

Normal `DocumentHandle.snapshot()` remains ready-only. MyOS may use
`auditManagedDocumentReadiness` for projection/recovery, but must not replace
normal product reads with the committed value. When exact content is required,
the committed value comes from `blue.advanced().auditDocument(documentId)` and
the ready value comes from `DocumentHandle.snapshot()`; each BlueId must equal
the corresponding readiness head before persistence.

### Bounded advancement

One MyOS `APPLY_MANAGED_EPOCH` execution uses the public bounded SDK call:

```java
DrainResult drained = blue.processing().drain(
        new blue.coordination.sdk.DrainBudget(
                maxCommittedTransitions,
                maxSelectedEntries));
```

For occurrence catch-up, one managed epoch application consumes one committed
transition unit. The normal worker uses a budget of one committed transition.
`maxSelectedEntries` remains positive and is retained in the command payload so
replay uses exactly the same bound. A paused result means deterministic work
remains; a blocked result must carry typed plan/barrier evidence and a typed
diagnostic.

Inspect `drained.managedEpochApplicationAttempts()` on every call, not only
`managedEpochApplications()`. A non-published attempt is the exact immutable
Contracts failure evidence for that work item; persist its work identity and
diagnostic with the failed command attempt, but do not create an application
receipt or advance the cursor. Published attempts must agree exactly with the
corresponding application receipt. A replayed published attempt proves that
Coordination reconciled an already committed application without another
PROCESS call.

Each attempt also exposes `automaticRetryCount()` and the typed
`managedOccurrenceResolutionIssues()` list. Every issue is bound to one
`ProcessorAttempt.resourceDemands()` entry by `demandIdentity()` and carries a
closed `ResolutionStatus`:

```text
MISSING_EXACT_CONTENT
AMBIGUOUS_MANAGED_LINEAGE
AMBIGUOUS_MANAGED_EPOCH
UNPROVEN_MANAGED_HISTORY
EXACT_STATE_MISMATCH
INVALID_AUTHORED_DOCUMENT
```

Persist the enum and demand identity as authority; retain `diagnostic()` only
for operator display. Do not derive a status from that text. The retry count is
the number of automatic occurrence-resolution expansions already consumed by
that one frozen application attempt, not permission for the host to invent
another source transition. A suspended attempt may carry several issues, and
MyOS must retain the complete set before deciding whether its durable owner is
ready to resume.

The same closed matching classification appears on ordinary
`EntryResult.closures()` through each `ClosureResult.ResourceDemand`'s
`managedResolutionStatus()` and `managedResolutionDiagnostic()`, with the
closure's `automaticRetryCount()`. The existing command blocker should consume
that surface for operation/static-admission waits and the managed-application
attempt surface for catch-up work. Both paths converge on the same durable
resource-demand rows and exact-content wake transaction.

## Exact-content catalog and several demanded BlueIds

Retained catch-up does not add a second content-delivery protocol. Reuse the
existing MyOS exact-content catalog and typed resource-demand wake path:

1. The general Content Add flow accepts a file, drag-and-drop, or pasted
   YAML/JSON through `POST /api/v1/content`. The server parses the complete
   value, calculates its authoritative BlueId, and retains it immutably. The
   user never types an asserted BlueId in that flow.
2. A demand-specific resolver shows the missing expected BlueId and may use
   `PUT /api/v1/content/{blueId}`. The path is verification evidence only: the
   calculated identity must equal it before anything wakes.
3. Register retained exact content with the replacement Coordination runtime
   before replay. Newly retained content is registered through the same
   runtime registry after the database transaction succeeds.
4. Reconcile every `MISSING_EXACT_CONTENT` issue with its matching typed
   resource demand. One closure may name several BlueIds at several occurrence
   paths; store and satisfy those demands independently.
5. Retaining one value satisfies every matching open demand transactionally,
   but an owner is requeued only when all of its exact-content demands are
   satisfied. A partially satisfied closure publishes no semantic state.
6. Resume the same accepted command and canonical work identity. Never push an
   event body, replacement occurrence, or document patch directly into the
   process. Coordination reruns the ordinary bounded demand/resolution loop and
   Contracts performs the ordinary affected-closure/cyclic processing.

Preserve the existing `/content` catalog as the human entry point: list the
server-derived BlueIds, open one immutable value, add by file/drop/paste, and
delete only through the existing dependency/reset safety checks. Catch-up
integration must not turn that catalog into a per-process push form or require
the operator to invent an identity.

Ambiguity, unproven history, selector mismatch, and invalid authored content
are not solved by uploading bytes under a requested identity. They remain typed
fail-closed evidence and require corrected authored input or an exact
`ManagedEpochSelector` in a new authorized operation. When several historical
occurrences are resolved in one closure, each path owns a plan/cursor and the
single graph-changing cause owns their aggregate barrier.

## Exact MyOS source map

| MyOS surface | Current source evidence | Required candidate integration |
|---|---|---|
| `ManagedEpochReceiptRecord` | `src/main/java/blue/myos/mini/persistence/ManagedEpochReceiptRecord.java:6-31` | Map every typed `ManagedEpochReceipt` field. Do not accept a host-supplied identity or a receipt reconstructed from a document revision. |
| `ManagedEpochEventRecord` | `src/main/java/blue/myos/mini/persistence/ManagedEpochEventRecord.java:6-24` | Persist every ordered `ManagedEventOccurrence`, including its Coordination-owned `managedEventIdentity`, source `DocumentId`, Contracts occurrence identity, exact event, and `publicAtSource`. Equal event values remain separate rows. |
| Receipt/event DDL | `src/main/resources/db/migration/V2__retain_managed_epoch_evidence.sql:1-42` | Add typed identity/binding columns and verified-evidence state; retain the `(session_id, epoch, ordinal)` order. |
| `ManagedEpochReceiptRepository` | `.../persistence/ManagedEpochReceiptRepository.java:11-89` | Add exact-equality upsert/audit methods. A conflicting existing identity or body is fatal; never silently replace it. |
| `ManagedEpochEventRepository` | `.../persistence/ManagedEpochEventRepository.java:11-86` | Replace an epoch only inside the same transaction as its verified receipt and document epoch. Validate exact count/order before commit. |
| `ManagedEpochEvidenceMapper` | `.../projection/ManagedEpochEvidenceMapper.java:5-24` | Replace the hard-coded unavailable capability only after the candidate types compile and typed receipt verification succeeds. |
| `ManagedEpochEvidenceBackfill` | `.../projection/ManagedEpochEvidenceBackfill.java:28-55` | Audit typed SDK receipts after runtime replay; populate them transactionally; mark the new marker complete only after exact verification. |
| Exact-content catalog | `.../command/ExactContentService.java`; `ExactContentRetentionService.java`; `ExactContentRuntimeRegistry.java`; `.../web/api/ContentApiController.java` | Keep server-derived BlueIds for ordinary add, expected-BlueId verification for demand resolution, immutable retain-or-equal behavior, runtime registration, and list/detail/delete UX. Do not add a catch-up-only upload endpoint. |
| Typed demand ownership | `.../command/CoordinationResourceBlocker.java`; `ResourceDemandService.java`; `.../persistence/ResourceDemandRepository.java` | Reconcile every managed-application `ManagedOccurrenceResolutionIssue` with its SDK resource demand, retain the complete demand set, and requeue the same command/work only after all exact-content demands are satisfied. Branch on `ResolutionStatus`, never diagnostic text. |
| `ManagedStateIndexService` | `.../projection/ManagedStateIndexService.java:39-132,169-226` | Keep it as a deterministic host projection. Coordination's typed matcher is semantic authority. Add same-lineage repeated-epoch ambiguity checks; do not treat several matching epochs as one unique selection. |
| State-index DDL | `V6__managed_state_heads_and_processing_work.sql:31-57` | Preserve the four existing match kinds and epoch `-1` sentinel. Index event-only epochs even when the state BlueId repeats. |
| `DocumentSessionRecord` | `.../persistence/DocumentSessionRecord.java:7-52` | Map typed committed/ready heads, status, wait reason, graph generation, and active barrier/work ownership. Preserve `current == committed` only as the legacy alias. |
| `DocumentSessionRepository` | `.../persistence/DocumentSessionRepository.java:147-249` | Use `updateCommittedHead` at attachment/application commit and `promoteReadyHead` only after all active barriers are terminal. |
| `OccurrenceSlotRecord` | `.../persistence/OccurrenceSlotRecord.java:6-15` | Keep path, activation generation, child lineage, and active/retired lifecycle. Do not place mutable catch-up progress in this row. |
| Occurrence DDL/repository | `V1__create_myos_mini_schema.sql:116-137`; `OccurrenceSlotRepository.java:27-189` | Link new plans to the exact occurrence composite key. Detach retires the generation and cancels only that generation's plan. |
| `ProcessingWorkKind.APPLY_MANAGED_EPOCH` | `.../persistence/ProcessingWorkKind.java:3-10` | Enqueue one exact plan/source-receipt/occurrence application. Do not enqueue source Timeline processing. |
| `ProcessingWorkRecord` and repository | `.../persistence/ProcessingWorkRecord.java:7-31`; `ProcessingWorkRepository.java:36-178,192-267,372-458,532-565` | Bind barrier, plan, source receipt, occurrence generation, expected committed head, and graph generation. Preserve semantic-key exact-definition checks and lease CAS. |
| `HostSafeProcessingWorkRunner` | `.../processing/HostSafeProcessingWorkRunner.java:10-56` | Replace `HOST_PROCESSING_CAPABILITY_GAP` only for a fully typed `APPLY_MANAGED_EPOCH` path. Other unsupported work continues to wait fail-closed. |
| `ProjectionSynchronizer` | `.../projection/ProjectionSynchronizer.java:122-143,463-515` | Persist revision, complete receipt/events, plan/barrier/cursor/readiness, and work result from typed audit in one transaction. Stop using `revision.publicEvents()` as the only event evidence. |
| `RuntimeProjectionVerifier` | `.../command/RuntimeProjectionVerifier.java:500-599,671-723` | Verify contiguous receipts, event multiplicity, plan/cursor continuity, occurrence generation, barrier membership, work ownership, and committed/ready heads against the replayed SDK. |
| `RuntimeRebuilder` | `.../command/RuntimeRebuilder.java:78-122` | Replay the accepted command ledger, reconstruct accepted bounded drain steps, audit commit companions/plans/cursors, run evidence backfill, then open the write barrier. |
| `CoordinationCommandWorker` | `.../command/CoordinationCommandWorker.java:94-105,229-289,403-439` | Recover leases, materialize deterministic bounded drain commands, and use existing rebuild/requeue handling for an uncertain runtime/DB boundary. |
| `CommandFinalizer` | `.../command/CommandFinalizer.java:15-53` | Extend the existing transaction to commit plan/cursor/application evidence and work completion together with projections and command status. |
| Operation request transport | `CommandPayloads.java:94-159,226-230`; `ApiRequests.java:104-143` | Ordinary requests carry exact content only. Add a typed advanced selector transport; do not expose Contracts binding arrays. Retain legacy payload interpretation by retained-evidence schema version. |
| Existing feature fence | `CommandApplicationService.java:254-273`; `SemanticCommandExecutor.java:727-746` | Remove `MANAGED_EXISTING_OCCURRENCE_UNAVAILABLE` only for the candidate's typed automatic/selector path. Keep malformed or legacy unsupported shapes rejected. |
| `ApiProjectionReader` | `.../web/api/ApiProjectionReader.java:337-468,516-539,587-608` | Report verified receipts/events and typed plan/barrier/cursor evidence. Keep `current` sourced from ready and `committed` separate. |
| REST endpoints | `DocumentApiController.java:144-223`; `ProcessingApiController.java:50-125` | Extend epoch/relationship views and add catch-up audit endpoints. Keep advancement command/idempotency semantics. |
| Operator UI | `src/main/resources/static/js/operator.js:715-757,917-925,1284-1313,1371-1394`; `src/main/resources/templates/operator.html:155-205` | Show exact plan progress, barrier, cursor, verified event occurrences, wait reason, and committed/ready lag. Never label legacy evidence complete. |
| Operation availability | `OperationCatalogService.java:78-114` | Continue disabling direct consumer operations until ready equals committed and no active barrier remains. |

## Additive Flyway migrations

The future MyOS integration branch starts after the current `V9`. Use new
additive migrations; do not rewrite `V1`-`V9`.

### `V10__verify_complete_managed_epoch_evidence.sql`

Extend `mini_managed_epoch_receipt` with:

```text
document_id
contracts_transition_receipt_identity
commit_companion_identity
source_order_json
receipt_schema_version
complete_evidence BOOLEAN NOT NULL DEFAULT FALSE
```

Extend `mini_managed_epoch_event` with:

```text
managed_event_identity
source_document_id
public_at_source
complete_evidence BOOLEAN NOT NULL DEFAULT FALSE
```

The migration must permit old optional rows to remain explicitly unverified.
Add checks so `complete_evidence = TRUE` requires every candidate-owned identity
and event flag. Add a foreign key from receipt `document_id` to the unique
`mini_document_session.document_id`, and indexes for:

```text
(document_id, epoch)
receipt_identity
(session_id, epoch, ordinal)
managed_event_identity
event_occurrence_identity
```

Create a fresh marker:

```text
backfill_key = managed_epoch_evidence_v2
status = PENDING
```

Do not mark it complete in SQL. Do not reinterpret the existing
`managed_epoch_evidence_v1` marker as proof of the new schema.

Separately extend the existing exact-content/provider schema, or add a dedicated
provider-evidence table, so a cyclic member body and its canonical complete
placeholder-set proof are retained together under the cyclic master/member
identity. Record a proof schema version and verified/completeness state. A
managed epoch receipt foreign key may point to that provider evidence by exact
after-BlueId, but the proof payload must not be copied into or treated as part of
the receipt identity. Legacy exact-content rows without complete cyclic proof
remain unavailable for cyclic catch-up until verified backfill succeeds.

### `V11__retain_occurrence_catch_up_plans.sql`

Create `mini_managed_catch_up_barrier`:

```text
barrier_identity                 primary key
consumer_session_id              foreign key
caused_by_identity
attachment_committed_epoch
snapshot_identity                verifies current membership/status/waits
status                           closed candidate vocabulary
wait_code
wait_message
exact_barrier_json               current authenticated snapshot
created_at
updated_at
```

Create `mini_managed_occurrence_catch_up_plan`:

```text
plan_identity                    primary key
barrier_identity                 foreign key
consumer_session_id              foreign key
consumer_document_id
target_occurrence_identity
target_path
activation_generation
source_session_id                foreign key
source_document_id
admitted_source_epoch            -1 or later
admitted_source_blue_id
caused_by_identity
snapshot_identity                verifies current cursor/frontier/status/waits
exact_plan_json                  current authenticated snapshot
created_at
updated_at
```

The target occurrence foreign key is the composite:

```text
(consumer_session_id, target_path, activation_generation)
    -> mini_occurrence_slot(parent_session_id, absolute_path,
                            activation_generation)
```

Create `mini_managed_occurrence_catch_up_cursor`:

```text
plan_identity                    primary/foreign key
next_source_epoch
required_through_source_epoch
last_applied_source_epoch
last_source_receipt_identity
expected_target_blue_id
status
wait_code
wait_message
progress_version                 optimistic CAS counter
updated_at
```

The cursor permits `next_source_epoch = 0` after authored initial `-1`.
That `-1` is only the authored-initial selector/cursor sentinel: it is never a
durable managed epoch receipt or application `source_epoch`. The first real
source receipt is epoch `0`, and every persisted receipt epoch is non-negative.
`required_through_source_epoch` may grow under live extension but never shrink.
`last_applied_source_epoch` and receipt identity change together.

Update the plan row's `snapshot_identity` and `exact_plan_json` atomically with
its normalized cursor fields. Update the barrier row's `snapshot_identity` and
`exact_barrier_json` atomically with canonical plan membership and barrier
status/waits. On every read, recompute both snapshot identities from the typed
SDK values and reject a mismatched JSON image or normalized column set. A
frontier extension, cursor advance, membership extension, or status/wait change
must change the relevant snapshot identity without changing the stable plan or
barrier identity.

Create `mini_managed_epoch_application` for immutable response-loss and audit
evidence:

```text
work_identity                    primary key
application_receipt_identity     unique
plan_identity                    foreign key
barrier_identity                 foreign key
source_receipt_identity          foreign key
source_document_id
source_epoch
contracts_invocation_identity
contracts_result_identity
consumer_session_id              foreign key
consumer_document_id
target_occurrence_identity
target_path
activation_generation
consumer_before_epoch
consumer_before_blue_id
consumer_after_epoch
consumer_after_blue_id
consumer_revision_receipt_identity
commit_companion_identity
resulting_source_cursor
exact_application_json
created_at
```

Enforce uniqueness for `(plan_identity, source_receipt_identity)` and for the
candidate's canonical work identity and application receipt identity. The five
receipt-only identity/progress fields map directly from
`ManagedEpochApplicationReceipt`; an application row is immutable.

Add locality indexes:

```text
barriers by consumer and nonterminal status
plans by consumer
plans by source
plan by target occurrence identity/generation
cursor by nonterminal status
applications by consumer epoch
applications by source receipt identity
```

No query used by catch-up may scan all document sessions or all receipts.

### `V12__bind_managed_epoch_processing_work.sql`

Add nullable compatibility columns to `mini_processing_work`:

```text
barrier_identity
catch_up_plan_identity
source_receipt_identity
activation_generation
execution_command_id
```

For new `APPLY_MANAGED_EPOCH` rows, require all five fields plus the already
existing expected document epoch, expected graph generation, occurrence
identity, child DocumentId, and child epoch. Old rows remain readable under
their retained-evidence schema version and are never silently upgraded.

Keep unpublished/suspended attempt evidence in the owning command/work result,
including the canonical work identity, exact processor attempt, automatic retry
count, and ordered typed resolution issues. Normalize each exact-content issue
through the existing `mini_resource_demand` ownership rows. Do not insert a
`mini_managed_epoch_application` row until Coordination returns a published
application receipt; a suspended or rolled-back attempt is not an application.

## Evidence mapper and backfill

`ManagedEpochEvidenceMapper` must remain fail-closed by default. Its candidate
implementation maps only typed SDK objects and performs these checks before it
can report complete evidence:

1. The receipt `DocumentId` equals the retained session's exact authored
   `DocumentId`.
2. Epochs are contiguous from zero, including event-only same-BlueId epochs.
3. Initialization has no before state; every later before BlueId equals the
   prior receipt's after BlueId.
4. The exact after document computes the receipt's after BlueId and equals the
   corresponding `DocumentRevision.after()` value.
5. The receipt identity, every `managedEventIdentity`, Contracts transition
   identity, and commit-companion identity all verify. The receipt identity
   authenticates the complete ordered event list; the public API does not
   expose a separate emitted-events aggregate identity.
6. Event ordinals are contiguous. Duplicate equal values remain separate rows
   with separate managed-event and Contracts occurrence identities.
7. The SDK public-event subset is exactly derivable from events marked
   `publicAtSource`; it is not allowed to add missing complete events.
8. Advanced audit by `(DocumentId, epoch)` and by receipt identity returns the
   same immutable value.
9. A rolled-back transition has no document epoch, receipt, or event row.

`ManagedEpochEvidenceBackfill.backfillIfRequired` runs only after the
replacement Coordination runtime has replayed successfully and while MyOS's
runtime write barrier is still closed. In one database transaction it:

1. calls `auditManagedEpochs` for each retained managed session;
2. verifies the complete sequence with the mapper;
3. inserts or exact-equality-confirms the receipt and event rows;
4. verifies there are no extra verified rows;
5. verifies the state index, document epochs, and committed head agree;
6. changes `managed_epoch_evidence_v2` from `PENDING` to `COMPLETE`.

Any missing receipt, conflicting existing row, invalid identity, event-count
difference, or SDK audit failure rolls back the transaction. The marker remains
`PENDING`, `completeEvidenceAvailable()` remains false, and
`RuntimeRebuilder` keeps the runtime unavailable/read-only. Legacy receipt rows
may be displayed as legacy evidence, but cannot drive state matching, catch-up,
or readiness.

## Managed-state matching

Continue to rebuild `mini_managed_state_index` from authored initial identity,
verified receipts, and the committed head:

```text
AUTHORED_INITIAL          epoch -1
INITIALIZED_EPOCH_ZERO    epoch 0
RETAINED_EPOCH            every verified epoch
CURRENT_HEAD              the current committed epoch
```

Do not use `ManagedStateIndexService.resolve` as semantic authority. Its
current implementation distinguishes several sessions but treats several
matching epochs in one session as one `UNIQUE` lineage result. The future
service must preserve all matching rows and map Coordination's typed outcome.

Selection precedence remains:

1. verify explicit stable `DocumentId` evidence;
2. reject cross-lineage ambiguity;
3. prefer the unique current state even if that BlueId occurred historically;
4. otherwise evaluate authored initial, epoch zero, and retained epochs;
5. select one unique retained epoch;
6. require `ManagedEpochSelector` for repeated matching historical epochs;
7. create a new lineage only for a complete pre-initialization value in an
   ordinary authored publication, never from inside retained catch-up;
8. reject unknown initialized/progressed state as unproven history.

Missing exact content goes through MyOS's existing exact-content demand/wake
path before lineage selection. Inline/reference/materialized representations
that resolve to the same exact BlueId must produce the same result.

## Attachment transaction

Suppose consumer A is ready at epoch 12 and an accepted operation adds source B
at retained epoch 5 while B is current at epoch 10.

The existing `CommandFinalizer.applied` transaction must atomically persist:

```text
A committed head at the attachment revision
A ready head still at epoch 12
A status CATCHING_UP
the exact occurrence slot and activation generation
the ManagedOccurrenceCatchUpPlan
the ManagedCatchUpBarrier and membership
the initial cursor (next=6, requiredThrough=10)
one APPLY_MANAGED_EPOCH work item for B6 through this occurrence
the APPLIED attachment command and replay evidence
```

No catch-up-required attachment may expose the new committed document as the
normal current document. If no catch-up is required, committed and ready may
advance together and no nonterminal plan is retained.

An operation adding several historical children creates or extends one barrier
and persists every required plan in that same transaction. The next work item
is selected by Coordination's canonical order, not database identity order.

## One managed epoch application transaction

One `APPLY_MANAGED_EPOCH` row names exactly one source receipt and one target
occurrence generation. Before invoking Coordination, the worker verifies:

```text
plan/barrier are nonterminal
occurrence generation is active
source receipt exists and is complete
cyclic source successor has separately verified complete provider proof
source epoch equals cursor.nextSourceEpoch
source before BlueId equals cursor.expectedTargetBlueId
consumer committed head and graph generation equal work preconditions
work definition equals its canonical semantic key
```

The worker must not mutate the in-memory Coordination runtime outside the
accepted command ledger. It first materializes or reuses a deterministic
`DRAIN_PROCESSING` command whose idempotency key is derived from the canonical
work identity. Extend `CommandPayloads.Drain` to retain:

```text
maxCommittedTransitions
maxSelectedEntries
expectedWorkIdentity
expectedPlanIdentity
expectedSourceReceiptIdentity
```

`SemanticCommandExecutor.drain` passes the retained bounds to
`blue.processing().drain(new DrainBudget(...))`. On success,
`CommandFinalizer.applied` atomically persists:

```text
the consumer DocumentRevision and complete consumer receipt/events
the immutable managed epoch application receipt
the occurrence cursor advance
any live extension of requiredThroughSourceEpoch
the next work item, if one is due
the barrier/plan statuses
the consumer committed head
the ready-head promotion only if every barrier member is terminal
the original processing work COMPLETE result
the bounded DRAIN_PROCESSING command APPLIED result/replay evidence
```

The source receipt, source head, source epoch, source PROCESS count, source
initialization count, and source public outbox do not change.

On gas, portable, runtime, receipt, or continuity failure, the consumer head,
cursor, plan position, and work completion all remain unchanged. Typed waiting
evidence maps to `WAITING_FOR_HISTORY`; invalid/tampered evidence maps to
`BLOCKED`. A gas/runtime rollback remains retryable and is returned as a
non-published `ManagedEpochApplicationAttempt`; a later retry uses the same
receipt, canonical work identity, and processor policy. Do not persist a
terminal plan status merely because one processor attempt rolled back.

For a cyclic successor, distinguish a definitive proof miss and temporary
provider outage from invalid evidence. Persist the three exact codes above;
missing/unavailable waits, while invalid proof blocks. None is a Contracts
processor attempt because detection occurs before PROCESS.

A suspended automatic occurrence-resolution attempt retains its exact
`automaticRetryCount`, resource demands, and typed resolution issues with the
same rule. `MISSING_EXACT_CONTENT` opens or exact-equality-confirms every named
content demand. Ambiguity and mismatch statuses remain fail-closed and never
become synthetic content demands. The host must not parse diagnostic text or
replace the frozen application with a direct document mutation.

If evidence repair makes an already-registered, unapplied work identity
eligible again, restore its pending and due index rows without replacing its
immutable work-registry/audit record. A missing or contradictory half-index is
an invariant violation and must fail closed. A `WAITING_FOR_HISTORY` or
`BLOCKED` sibling on the same consumer/barrier suppresses newly due work for
that consumer so it cannot be overtaken; independently indexed consumers may
continue.

## Restart and response loss

### Startup order

Preserve the existing single-writer startup boundary in this exact order:

1. close the runtime write gate;
2. recover retained `CLAIMED` leases to `QUEUED`;
3. create a fresh Coordination runtime, register retained exact content, and
   restore every verified complete cyclic proof before enabling dependent work;
4. replay APPLIED commands by global sequence, including the exact bounded
   `DRAIN_PROCESSING` commands;
5. audit every managed receipt, application receipt, plan, barrier, cursor, and
   committed/ready head, plus every retained unpublished attempt and typed
   unresolved demand;
6. run complete-evidence backfill/verification;
7. rebuild and verify `mini_managed_state_index`;
8. reconcile queued/claimed/waiting work against commit-companion and
   application-receipt audit;
9. open the write gate only when every comparison succeeds;
10. kick the work loop.

Replay reconstructs a fresh in-memory runtime from accepted inputs. Within
each replay pass every source input is processed once in its original ledger
position; catch-up drain commands consume the reconstructed immutable source
receipts and do not submit those source inputs again. This becomes a valid
fresh-process guarantee only after MyOS implements and tests the separate cyclic
proof store/restoration step; the current Coordination source alone does not
provide that persistence.

### Lost response after commit

Before retrying a claimed work item, audit its canonical application identity,
source receipt, plan cursor, and commit companion:

- If the application and cursor already committed, persist/exact-equality
  confirm the projection and mark work `COMPLETE`; do not call Contracts again.
- If no application committed, the cursor must still name the same next epoch;
  retry the same bounded command.
- If the evidence is mixed or contradictory, mark the runtime unavailable and
  fail closed. Do not guess which side committed.

If the runtime committed but the database transaction did not, the existing
uncertain-projection path rebuilds from APPLIED commands. The uncommitted drain
is absent and the exact work remains retryable. If the database transaction
committed but the HTTP/worker response was lost, the APPLIED command,
application row, cursor, and completed work row prove completion.

### Detach, retarget, and re-add

Retiring an occurrence atomically marks its non-complete current plan
`CANCELLED_OCCURRENCE_RETIRED` and prevents further applications through that
generation. A `COMPLETE` plan is immutable audit evidence and its snapshot is
left unchanged. Retarget retires the old plan and creates a new plan. A later
re-add uses a fresh `activation_generation`, plan identity, and cursor. Never
migrate or reuse the retired cursor.

### Live source extension

When a new source receipt becomes committed while plans for that source are
active, update each affected cursor's `requiredThroughSourceEpoch` monotonically
and enqueue the due canonical work. The consumer cannot become ready before an
already-ordered source epoch. Use plans-by-source indexes; never scan unrelated
sessions. The single drain scheduler retains a deterministic lane turn so
repeated `DrainBudget(1, 1)` calls advance both a continuing source frontier and
its catch-up cursor without allowing direct consumer work to overtake. A finite
source stream eventually becomes `READY`; a source that continues forever
remains truthfully `CATCHING_UP`.

## REST and operator UX

Retain the existing REST resources and extend them additively:

- `GET /api/v1/documents/{sessionId}` continues to expose ready/current and
  committed heads separately, plus status, wait reason, and active barriers.
- `GET /api/v1/documents/{sessionId}/epochs` and
  `.../epochs/{epoch}` include verified complete receipt identity, transition
  and commit-companion identities, full ordered event occurrences, and the
  separate public subset.
- `GET /api/v1/documents/{sessionId}/relationships` includes each occurrence's
  activation generation, plan identity, admitted position, next/required epoch,
  and status.
- Add `GET /api/v1/documents/{sessionId}/catch-up` for the authoritative
  readiness, barriers, plans, and cursors.
- Add `GET /api/v1/catch-up/plans/{planIdentity}` and
  `GET /api/v1/catch-up/barriers/{barrierIdentity}` for exact audit.
- Existing processing-work endpoints expose the source receipt, plan/barrier,
  occurrence generation, automatic retry count, typed resolution issues,
  attempt, wait/error, and immutable result.
- `POST /api/v1/processing/drain` accepts positive retained bounds and remains
  idempotent. It does not accept a source document operation or event body.

Ordinary operation requests carry inline/pure-reference exact content. The
server does not require `existingOccurrences`. Add an explicitly advanced
selector shape only for a stable source `DocumentId`, epoch, expected BlueId,
and target path. It must compile to `ManagedEpochSelector`, never to an untyped
document field.

The operator Document view must show:

```text
READY or CATCHING_UP/BLOCKED status
ready head and committed head
barrier status and exact wait reason
one card per occurrence plan
admitted, next, and required source epochs
activation generation and source DocumentId
last applied source receipt
durable work attempt/result
```

The epoch inspector must show the complete verified event sequence in ordinal
order, including duplicate equal values, with `publicAtSource` badges. Keep the
public outbox in a separate section. `managedEpochEvidenceComplete` becomes
true only for rows verified by the v2 mapper; legacy rows remain visibly
unverified.

Do not add a browser button that directly patches a cursor or parent document.
Continue/Retry only requeues the same typed work or submits the same idempotent
bounded drain command.

## Immutable staged dependency adoption

The published rc.22/rc.4 coordinates remain immutable. Do not overwrite or
republish them. Before a future MyOS implementation begins, obtain the exact
Contracts and Coordination candidate coordinates, repository paths, artifact
hashes, and manifest hashes from this round's final receipts.

The future MyOS integration branch should add one explicit dependency mode,
for example:

```text
blueDependencyMode=retained-managed-epoch-stage
blueContractsRepository=/absolute/invocation-owned/contracts-maven-stage
blueContractsManifestSha256=sha256:<manifest hash>
blueCoordinationRepository=/absolute/invocation-owned/coordination-maven-stage
blueCoordinationManifestSha256=sha256:<manifest hash>
```

That change is intentionally deferred; the current published-only MyOS branch
rejects these stage properties in `settings.gradle.kts:8-19`.

The new mode must:

1. require absolute regular directories and exact lowercase SHA-256 manifest
   identities;
2. verify each manifest and sidecar before dependency resolution;
3. verify every staged POM/module/JAR/source/Javadoc hash named by the manifest;
4. route `blue.language` exclusively to the Contracts/Language stage;
5. route `blue.coordination` exclusively to the Coordination stage;
6. exclude both groups from Maven Central in that mode;
7. reject Maven Local, `includeBuild`, project substitution, symlinks, and any
   remote fallback for staged groups;
8. pin the candidate versions and hashes in a dedicated dependency lock/report;
9. leave BEX and Repository on their unchanged published coordinates unless the
   final candidate receipt explicitly says otherwise;
10. consume a stage only after it is sealed; no task may write into it during a
    MyOS invocation.

For this Coordination round, any compile-only MyOS consumer check must run from
an invocation-owned temporary/extracted copy or external characterization
harness. It must not alter or commit the clean MyOS branch and must not touch
the dirty primary worktree.

After the candidates are actually published, replace the stage mode with exact
published coordinates, Maven Central hashes, and a regenerated published lock.
Publication is outside this round.

## Required downstream tests

The future MyOS integration is not complete without all of these tests:

- clean migration from the current `V1`-`V9` schema;
- legacy unverified rows remain fail-closed;
- complete receipt backfill succeeds only from typed SDK evidence;
- one suspended managed application with several missing BlueIds retains all
  demand identities/statuses, wakes after either upload without partial
  publication, and resumes only after every required value is present;
- the Content Add flow calculates the BlueId from file/drop/paste input, while
  a demand resolver verifies its displayed expected BlueId;
- authored initial `-1` is selector/cursor-only, the first applied receipt is
  epoch `0`, and no receipt/audit query accepts a negative receipt epoch;
- authored-initial catch-up applies epochs `0..current`;
- epoch-zero catch-up applies `1..current`;
- retained epoch F catch-up applies `F+1..current`;
- current state reuses the session and creates no catch-up plan;
- same historical BlueId at several epochs requires `ManagedEpochSelector`;
- same BlueId across lineages fails closed;
- event-only same-BlueId source epochs are retained and applied;
- two equal events retain two rows and cause two deliveries;
- two occurrences have independent cursors;
- several parents progress/fail independently;
- one operation adding several children uses one barrier and canonical order;
- live source extension delays readiness;
- repeated `DrainBudget(1, 1)` calls fairly advance source and catch-up work,
  preserve direct-work ordering, settle a finite stream, and keep an infinite
  stream `CATCHING_UP`;
- gas/runtime failure preserves consumer head and cursor;
- repaired evidence requeues the exact registered work by restoring both
  pending and due indexes, while inconsistent indexes fail closed;
- a waiting/blocked barrier sibling gates only the same consumer;
- response loss after commit does not repeat Contracts application;
- missing/tampered receipt blocks without skipping;
- detach/retarget/re-add uses fresh generations, cancels incomplete plans, and
  leaves completed plan snapshots unchanged;
- nested existing-lineage resolution during catch-up is supported; nested
  newly authored lineage creation during catch-up is not;
- finite/infinite cyclic cases use ordinary closure proof/gas behavior;
- cyclic source successors require separately restored complete proofs;
  NOT_FOUND and UNAVAILABLE remain typed waits, INVALID_EVIDENCE remains typed
  blocked, and all three preserve cursor/head/history/receipts with zero PROCESS
  across runtime reconstruction;
- an eventless same-state cyclic application epoch can later serve as retained
  source without reprocessing that source;
- 1,000 unrelated sessions produce zero unrelated scans;
- restart after every application boundary reconstructs identical receipts,
  plans, cursors, components, committed/ready heads, work, and UI JSON;
- executable JAR and extracted source ZIP pass the same restart scenario;
- immutable staged Contracts/Coordination hashes match the final receipts;
- Maven Local/composites/remote fallback are rejected.

## Downstream completion receipt

The later MyOS round may claim integration complete only when all of these are
true:

```text
complete managed epoch evidence is typed and verified
managed state matching is Coordination-authoritative
authored-initial, epoch-zero, and retained-epoch plans persist durably
one source receipt is applied through one occurrence-specific cursor
source processing is not invoked by catch-up
duplicate event occurrences remain distinct
consumer committed/ready heads and barriers are exact
bounded work is replayable and response-loss safe
typed unresolved demands and automatic retry counts survive restart without parsing text
cyclic successor proofs are durable provider evidence restored before work eligibility
cyclic proof miss/unavailable/invalid retain typed same-cursor wait/block evidence
authored -1 remains selector/cursor-only and every receipt epoch is non-negative
normal reads never expose committed-but-not-ready content
existing nested lineages may resolve during catch-up; newly authored nested lineages may not
REST/UI disclose receipts, plans, cursors, barriers, and waits
restart equality and packaged-JAR acceptance pass
```

Until that separate MyOS round succeeds, the truthful state remains:

```text
myosIntegrated = false
myosChanged = false (for this Coordination round)
productionReady = false
```
