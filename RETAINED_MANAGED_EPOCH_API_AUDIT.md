# Retained managed-epoch API audit

Audit status: COMPLETE

Audit completed at: 2026-08-26T16:57:57Z

This Phase-0 audit was completed before any production Coordination
implementation for retained managed-epoch catch-up. The only changes in the
Coordination worktree at this checkpoint are this Markdown audit and its JSON
companion. MyOS was inspected read-only and remains unchanged. Maven Local was
not used.

## Scope and decision

The exact published baselines audited were:

| Layer | Published coordinate |
| --- | --- |
| Contracts | blue.language:blue-contracts-core:3.1.0-rc.22 |
| Coordination | blue.coordination:blue-coordination-java:3.0.0-rc.4 |

The expected Contracts gap is present. The published Contracts API exposes the
ordered public Root event subset and aggregate closure gas, but does not expose
an authenticated, per-managed-document transition receipt containing the
complete ordered duplicate-preserving Root event occurrence sequence,
including events from non-public managed Roots. ManagedRevisionCause
authenticates a state transition but cannot deliver the exact retained source
event occurrences.

Therefore the minimal additive blue-contracts-core transition-receipt change
described by the round prompt is required before Coordination can implement
authoritative retained managed-epoch receipts and occurrence catch-up.

No Blue Language model/core/mapping, BlueId, BEX, or Repository semantic change
is indicated by this audit.

## Repository custody

### Blue Language and Contracts

| Property | Value |
| --- | --- |
| Path | /Users/piotr/data/blue-language-java |
| Remote | git@github.com:bluecontract/blue-language-java.git |
| Audited branch | feat/dynamic-contract-evolution-milestone |
| Audited HEAD | cf8f4242b41e25d67606a5dfd58a7e5ba61f39bf |
| Semantic source checkpoint | 5a57bb8; later audited commits contain receipt documentation only |
| Status at audit | clean |

The inspected published rc.22 source files byte-match the corresponding clean
source checkout at the audited HEAD.

### Coordination

| Property | Value |
| --- | --- |
| Primary path | /Users/piotr/data/blue-contract-java |
| Implementation worktree | /private/tmp/blue-coordination-retained-catch-up-rc4 |
| Remote | git@github.com:bluecontract/blue-coordination-java.git |
| Primary audited branch | feat/dynamic-contract-evolution-rc4 |
| Implementation branch | feat/retained-managed-epoch-catch-up |
| Audited HEAD | acf02f3ce7c141e4fc9eabb6f19dacde471e30f4 |
| rc.4 release-preparation commit | 7719eaac57a9c5729fa7257ed66a6dc7be01ce21 |
| Status before audit artifacts | clean |
| Tags | no rc.4 tag; v3.0.0-rc.1 is the only local 3.0.0 RC tag |

The HEAD differs from the rc.4 release-preparation commit only in tests and
golden fixtures. Its src/main/java and src/main/resources trees are identical
to that release commit. All 184 corresponding main source/resource files also
match the published 3.0.0-rc.4 sources JAR byte-for-byte.

### MyOS Mini downstream characterization

The dirty primary worktree was preserved:

| Property | Value |
| --- | --- |
| Primary path | /Users/piotr/data/myos-simple |
| Branch | feat/dynamic-contract-evolution-milestone |
| HEAD | ebf65df6bb58495766dab4dbbb71984f204f2904 |
| Status | pre-existing untracked files only; not edited |

The exact published-stack target inspected read-only was:

| Property | Value |
| --- | --- |
| Path | /private/tmp/myos-simple-exact-content-ux-ebf65df |
| Branch | feat/exact-content-ux |
| HEAD | 420e552f075149bf459722124f953ce3c8c57669 |
| Status | clean |

The primary branch's tracked build still targets older coordinates, so it was
not used as evidence for the published rc.22/rc.4 downstream state. The clean
feat/exact-content-ux worktree is the characterized published-stack source.

## Published artifact verification

| Artifact | SHA-256 |
| --- | --- |
| blue-contracts-core-3.1.0-rc.22.jar | 7c5abb3c87273f640dcc9754d43c1a6f5f1fcb85605ed496d35c2514304031d6 |
| blue-contracts-core-3.1.0-rc.22-sources.jar | d678b8f462933bc1358f8439c09bf756d3bea6bba2ba5fa9aa13b5b0394c5477 |
| blue-coordination-java-3.0.0-rc.4.jar | a8dc99f5bcc62902496ee3bdb5e8f085b78d9781e7928ef20f5a83b65581cd34 |
| blue-coordination-java-3.0.0-rc.4-sources.jar | 4222b8a40196e7afe1e3eff13694ae253ca0d3b634b6a475e8e9c96f18da9600 |
| blue-coordination-java-3.0.0-rc.4.module | 6d207a7482539b19dd241b37c8f60550d46db905ca00a7d85eaa6c7044712253 |
| blue-coordination-java-3.0.0-rc.4.pom | 41d329a4527a3512d65fdb5b29515df64582bf2a9bc2f639b8b19253d912f461 |
| blue-language-core-3.1.0-rc.22.jar | 8d7167254a39132e7a494561ed966748918c138c08f0967ccc3e841edba0b1f0 |
| blue-language-ipfs-3.1.0-rc.22.jar | bec7355f39a109c4fe6dfc5f9970232dc0a75cd8e5b4ab055abc311314d24c8e |
| blue-language-java-3.1.0-rc.22.jar | 0de1584be094515ddd27938819464dc024a993c7eb06e4145cac129ad5bbfed0 |
| blue-language-mapping-3.1.0-rc.22.jar | d9141d5c611bde7eb6a21bce3dc4bc0df7d8167f013eeaef2a365dd0a6af329b |
| blue-language-model-3.1.0-rc.22.jar | ef55be8331147442b858474add4782489d993568effe30202a9c4a8b014d5bd8 |

The MyOS published target pins these versions and runtime hashes in
build.gradle.kts lines 22-52, constrains the direct graph at lines 72-85, and
locks it in gradle.lockfile lines 4-13. settings.gradle.kts lines 8-19 reject
obsolete local-stage properties; lines 21-46 route blue.language and
blue.coordination exclusively to Maven Central and exclude those groups from
the general repository.

The upcoming implementation stages must be invocation-owned and immutable.
They must not use Maven Local and must prevent staged-group remote fallback.

## Contracts rc.22 audit

### Capability matrix

| Required fact | Published rc.22 result |
| --- | --- |
| Before and after exact document BlueIds | Present in resulting documents and presentation evidence |
| Complete ordered Root event occurrences per managed transition | Missing |
| Duplicate event occurrence identities | Preserved only for the public projection |
| Events emitted by non-public managed Roots | Not exposed in the terminal result |
| Per-transition processing gas | Missing; only closure aggregate gas/trace is exposed |
| Authenticated transition receipt identity | Missing |
| Transition-receipt aggregate identity | Missing |
| Commit-companion binding to complete transition receipts | Missing |
| Event-only unchanged-BlueId transition receipt | Missing |
| Managed revision exact retained event delivery | Missing |

### Exact source evidence

- ClosureProcessResult.java lines 15-42 exposes resulting documents,
  PublicEventOccurrence values, total gas/gas trace, platform commit companion,
  and DocumentTransitionEvidence. Lines 190-220 explicitly introduce the
  transition evidence as non-identity-bearing presentation evidence.
- DocumentTransitionEvidence.java lines 20-26 explicitly says the value is not
  part of invocation, document, component, closure, companion, or result
  identity and must not authorize or reconstruct processing. Its fields at
  lines 30-38 contain state/type/patch evidence, not complete events, original
  cause, per-transition gas, or an authenticated receipt identity.
- LocalDocumentStepResult.java lines 20-30 retains ordered emitted event Nodes
  transiently for one local step and gas-before/gas-after. Lines 169-175 expose
  only optional non-identity presentation evidence to the assembler.
- ClosureExecutionState.java lines 18-29 retains PublicEventOccurrence values,
  gas trace, epoch-advance documents, and presentation evidence, but has no
  complete per-managed-document Root event receipt.
- ClosureExecutionSession.java lines 1465-1501 assigns every Root event a
  deterministic global ordinal, occurrence identity, and exact body. Lines
  1480-1491 add only public-root occurrences to publicEvents; non-public events
  remain transient in the internal event queue.
- PublicEventOccurrence.java lines 13-55 preserves order and duplicate
  occurrence identities, but only for that public projection.
- ClosureSuccessResultAssembler.java lines 58-115 passes the presentation
  evidence separately. Lines 128-137 advance the built-in epoch only when the
  exact document BlueId changes, so an event-only unchanged-state transition
  has no retained epoch receipt.
- ClosureCommitCompanion.java lines 23-40 binds resulting document deltas,
  publicEventsIdentity, and gasTraceIdentity. Its identity construction at
  lines 152-200 does not bind a complete managed-transition receipt set.
- ManagedRevisionCause.java lines 10-18 carries source/target document and
  epoch/state evidence plus a source revision receipt identity, but no complete
  source Root event occurrence sequence.
- ClosureIdentityService.java lines 97-106 and
  ClosureInvocationVerifier.java lines 150-166 authenticate the managed source
  document, from/to epochs, before/after states, and original cause only.
- The managed-revision path in ClosureExecutionSession.java lines 319-369
  replaces occurrence state but has no source-event injection/delivery path.

### Contracts conclusion

The required change is additive and belongs in blue-contracts-core:

1. Add an immutable authenticated per-document transition receipt.
2. Preserve all ordered Root event occurrences with distinct occurrence
   identities, including non-public managed Roots and equal duplicate events.
3. Bind before/after states, exact after document, cause, per-transition gas,
   events, and commit companion/aggregate receipt identity.
4. Return receipts only for accepted committed transitions; rollback returns
   none.
5. Create a receipt for event-only semantic transitions with an unchanged
   BlueId.
6. Extend managed revision input verification and execution to deliver those
   exact source event occurrences through one exact target occurrence.
7. Preserve the existing public-event subset and public outbox semantics.

## Coordination rc.4 audit

### Revision and history surfaces

- api/DocumentRevision.java lines 13-26 stores document/epoch/order,
  before/after values, Timeline causality, a legacy CatchUpCause, public-facing
  emitted Nodes, and gas. Kinds at lines 177-187 are INITIALIZATION,
  TIMELINE_ENTRY, EMBEDDED_REVISION_APPLICATION, and CATCH_UP_COMPLETED.
  CatchUpCause at lines 189-207 is legacy attachment evidence, not a complete
  managed source transition receipt.
- sdk/DocumentRevision.java lines 9-30 mirrors those kinds and exposes only
  List<PublicEvent>.
- sdk/DocumentHandle.java lines 7-20 exposes READY-only snapshot, complete
  committed history, and exact current state. Mapping is in
  SdkCoordinationRuntime.java lines 579-600, 936-974, and 1155-1178.
- sdk/DocumentSnapshot.java lines 9-27 is a READY-only record. The low-level
  api/DocumentSnapshot.java lines 14-27 has one epoch/status/layout view rather
  than separate committed and ready projections.

Existing record components must not be changed incompatibly. New receipt,
readiness, and audit values should be additive.

### Session readiness and state history

- DocumentSession.java lines 21-35 already stores contiguous revisions,
  transition-receipt identities, state epochs, current epoch, readyEpoch,
  graphPublishedEpoch, and SessionStatus.
- Lines 107-123 define local readiness; lines 145-173 expose contiguous
  revision history; lines 189-212 handle receipt idempotency and exact epoch
  admission.
- Lines 214-246 implement CATCHING_UP, BLOCKED, graph-published, and ready
  transitions. Lines 267-313 commit the next contiguous revision.
- StateEpochs at lines 315-360 indexes authored initial as epoch -1 and detects
  repeated-state ambiguity.
- SessionStatus.java lines 4-14 has PENDING_INITIALIZATION, CATCHING_UP, READY,
  BLOCKED, and TERMINATED, but no exact waiting-for-history reason.
- DefaultCoordinationEngine.java lines 1092-1119 separates normal and audit
  reads, but snapshot construction at lines 1199-1225 always uses current
  committed state. Its Contracts readiness check at lines 1302-1335 rejects
  BLOCKED and checks durable head/component coherence but does not require
  readyEpoch == epoch or reject CATCHING_UP.

The committed/ready counters should be extended, not replaced. Normal reads
must use the ready head; advanced audit may inspect committed-but-not-ready
state. A ready layout cannot be inferred from the current layout while catch-up
is incomplete.

### Managed-state index and resolution

- ManagedLineageIndex.java lines 34-47 indexes authored initial, initialized,
  retained state by BlueId+epoch, and current state. Lines 85-160 update it
  contiguously; lines 171-192 provide lookup; lines 203-223 retain locality
  counters; lines 478-570 build lineages and retained-state values.
- ManagedOccurrenceResolver.java lines 162-177 and 272-292 query authored,
  initialized, retained, and current candidates.
- Current state wins at lines 223-239.
- Lines 240-260 deliberately fail closed for unique authored-initial,
  initialized-epoch-zero, and retained-epoch matches with
  UNPROVEN_PROGRESSED_HISTORY.
- Lines 302-314 expose ambiguity/error statuses and only two successful target
  kinds: CURRENT_EXISTING and NEW_AUTHORED.

The lineage index is already the correct local lookup foundation. Extend the
resolver with explicit existing-authored-initial, existing-epoch-zero, and
existing-retained-epoch outcomes carrying the selected epoch. Preserve current
precedence, repeated-state ambiguity, and cross-lineage ambiguity.

### Occurrence identity and pending history

- Published Contracts ManagedOccurrenceBinding.java lines 15-23 contains
  occurrenceIdentity, bindingIdentity, source address and activation
  generation, target document, expected target BlueId, active state, and
  pendingHistoricalEpoch. Lines 38-75 reject active rows with pending history.
- Coordination ManagedOccurrenceInventory.java lines 17-25 retains complete
  active/inactive rows. Lines 200-386 replace affected sources atomically.
  Lines 483-609 implement generation-safe retire, activate, and rebind rules;
  same-lineage rebind preserves pendingHistoricalEpoch.

The occurrence identity and scalar pending cursor are useful existing
mechanisms. They are not a complete plan: Coordination must add immutable plan,
required-through epoch, next cursor, barrier membership, status, and wait
reason state. The ordinary Contracts result should update bindings; the host
must not patch them out of band.

### Missing managed-revision invocation

Coordination does not construct ManagedRevisionCause. Contracts already
provides ClosureEvidenceFactory.managedRevisionCause at lines 167-212, and
ClosureInvocationInput lines 301-336 verifies inactive pending occurrence,
fromEpoch, expected before state, source child, and closure membership.

ContractsClosureAdapter.java lines 890-969 captures only external-event
invocations and constructs ExternalEventCause. CohortSelection at lines
2877-2898 requires nonempty direct deliveries, which is incompatible with a
managed-revision invocation.

Add a managed-revision capture path that invokes the ordinary closure/cyclic
processor with no direct external delivery. Do not add a separate authored
relationship or a host-side process interpreter.

### Receipt projection gap

- ContractsClosureAdapter.java lines 1860-1863 stages only public events to the
  outbox.
- Lines 1919-1937 and 2016-2032 retain only public-root events in Coordination
  revisions.
- Lines 1960 and 2010-2012 skip an unchanged result revision.
- requiresDocumentPublication at lines 2593-2615 treats an unchanged epoch and
  BlueId as no publication.
- The existing transition receipt at lines 2033-2039 is publication identity
  plus document id, not the complete authenticated source transition receipt.

Complete source occurrences must be retained separately from the public
outbox. Event-only source transitions need one contiguous Coordination managed
epoch even when beforeBlueId equals afterBlueId.

### Atomic storage, companion, and recovery

- MultiDocumentPublicationTransaction.java lines 29-38 defines one
  copy-on-write store swap. Lines 157-215 stage document and occurrence state;
  lines 450-510 stage evidence and prepare/commit; lines 512-702 build and swap
  the trusted replacement state.
- Lines 553-557 currently mark touched Contracts documents immediately
  graph-published and READY. Historical attachment must instead atomically
  publish a catch-up plan/barrier and remain non-ready.
- Lines 1256-1292 permit a contiguous revision whose before and after value are
  equal in principle. Lines 1068-1083 currently reject a staged revision for
  an unchanged Contracts result and must be adapted for authenticated
  event-only transitions.
- InMemoryDocumentStore.java lines 392-419 defines immutable StoreState; lines
  237-275 perform receipt lookup and the one state swap; lines 595-634 retain
  the exact ClosureAttemptResult and therefore the platform companion.
- requireRetainedResult at lines 961-1019 verifies a result epoch/BlueId exists
  in durable history. Coordination event-only epochs must retain an explicit
  mapping to the Contracts transition receipt instead of pretending Contracts
  changed its state epoch.
- ContractsClosurePublicationReceipt.java lines 16-102 already retains a
  terminal attempt/publication identity and reconciles response loss without
  rerunning Contracts. This is the recovery pattern for one managed catch-up
  application.

Add source epoch receipt indexes, catch-up plans/barriers, occurrence cursors,
and application receipts to StoreState and the same transaction. Consumer
revision, cursor advancement, application receipt, barrier/readiness state,
and work completion must commit together.

### Drain and budget

- ContractsJournalDrainCoordinator.java lines 16-25 scans the external journal
  only. Lines 83-140 implement bounded selection and publication while
  preserving unrelated-lane progress. There is no managed catch-up work queue.
- CoordinationEngine.java lines 137-147 exposes drain; DrainBudget at lines
  202-217 counts maximum committed transitions and selected entries.
- ProcessingGateway.java lines 13-16 exposes unbounded SDK drain.
- ProcessingDrainReceipt.java lines 13-155 already has compatibility
  constructors and paused/blocked semantics.

Add a canonically ordered managed-work source. One source receipt applied
through one exact occurrence is one normative work unit. Count it as a
committed transition and add metrics/budget fields only through compatible
surfaces. A blocked dependent lane must not stop unrelated work.

### Existing legacy mechanisms

CatchUpBarrier, EmbeddedEpochCursor, EmbeddedEpochInput, and
SequentialDrainCoordinator contain useful cursor, barrier, idempotency, and
lost-response recovery shapes. They must not be used to replay source history:
SequentialDrainCoordinator nextHistoricalStep/processHistoricalTargets at
lines 1803-1848 and 1660-1687 re-read Timeline history. The new feature must
consume immutable source epoch receipts through ordinary closure processing.

### Advanced audit gap

DefaultCoordinationEngine.ManagedOccurrenceAuditView lines 70-82,
sdk/ManagedOccurrenceAudit.java lines 7-19, and AdvancedCoordination.java lines
22-60 expose only limited occurrence/document/route/timeline audit. Add
immutable audit surfaces for managed epoch receipts, plans, occurrence cursor,
barrier/readiness, application receipts, and exact wait/block diagnostics.

## MyOS Mini read-only characterization

### Published pins and repository policy

The clean downstream target pins Language 3.1.0-rc.22 and Coordination
3.0.0-rc.4 in build.gradle.kts lines 22-35, includes direct constraints at
lines 72-85, and locks the complete graph in gradle.lockfile lines 4-13.
settings.gradle.kts lines 8-46 proves the characterized build is published-only
and excludes Maven Local/staged overrides.

### Existing tables and state model

- V6__managed_state_heads_and_processing_work.sql lines 31-57 defines
  mini_managed_state_index with AUTHORED_INITIAL at epoch -1,
  INITIALIZED_EPOCH_ZERO, RETAINED_EPOCH, and CURRENT_HEAD.
- ManagedStateIndexService.java lines 39-132 deterministically rebuilds and
  verifies the index. Lines 169-226 derive expected rows and fail closed on a
  mismatched authored DocumentId.
- Its current resolver at lines 154-166 only treats multiple session IDs as
  ambiguous; repeated same-lineage historical epochs can resolve UNIQUE. New
  typed Coordination resolution must be authoritative for epoch ambiguity.
- V2__retain_managed_epoch_evidence.sql lines 1-19 defines
  mini_managed_epoch_receipt. Lines 21-42 define
  mini_managed_epoch_event with ordered ordinal and distinct
  event_occurrence_identity, so duplicate equal events can be represented.
- ManagedEpochReceiptRecord, ManagedEpochEventRecord, and their repositories
  provide storage shapes, but no production writer populates them.
- ProjectionSynchronizer.java lines 63-101 does not depend on the receipt/event
  repositories; lines 463-515 persist only revision.publicEvents().
- ApiProjectionReader.java lines 440-468 labels any rows
  LEGACY_RETAINED_UNVERIFIED and complete=false.

These tables are schema scaffolding, not proof that complete source history is
available.

### Fail-closed evidence mapper

ManagedEpochEvidenceMapper.java lines 5-24 explicitly refuses to treat the
public subset as the complete source event stream:

- completeEvidenceAvailable always returns false.
- availabilityCode is COMPLETE_MANAGED_EPOCH_EVIDENCE_UNAVAILABLE.

ManagedEpochEvidenceBackfill.java lines 28-55 never fabricates evidence and
leaves its marker pending. V3__managed_epoch_evidence_backfill_marker.sql lines
1-14 initializes that marker to PENDING.

### Committed and ready heads

- V6__managed_state_heads_and_processing_work.sql lines 1-29 adds committed and
  ready epoch/BlueId pairs, wait metadata, graph generation, and active work.
- DocumentSessionRecord.java lines 7-52 enforces that legacy current aliases
  committed, ready pairs are complete, and ready does not exceed committed.
- DocumentSessionRepository.java lines 147-192 advances committed;
  promoteReadyHead at lines 194-218 is compare-and-swap and promotes only the
  already committed head. Lines 220-249 guard waiting state.
- RuntimeProjectionVerifier.java lines 500-599 verifies revision continuity,
  committed/ready heads, and wait/work ownership.
- ApiProjectionReader.java lines 337-435 uses ready for normal current state
  and exposes committed separately. OperationCatalogService.java lines 78-114
  withholds operations while the heads differ.

This model is suitable for retained catch-up: historical attachment advances
committed, leaves ready stable, and promotes ready only after every barrier plan
is complete.

### Scaffolding-only statuses and work

- DocumentStatus.java lines 3-18 declares WAITING_FOR_HISTORY and CATCHING_UP.
- ProcessingWorkKind.java lines 3-10 declares APPLY_MANAGED_EPOCH.
- mini_processing_work is defined at V6 lines 71-118 and has expected consumer
  epoch/graph generation, occurrence identity, child document, and child epoch.
- Occurrence slots in V1__create_myos_mini_schema.sql lines 116-137 retain path
  activation generation and lifecycle but no catch-up plan, cursor, barrier,
  or status.
- APPLY_MANAGED_EPOCH has no production executor. CATCHING_UP has no runtime
  source use. WAITING_FOR_HISTORY is accepted only by repository/verifier
  guards.
- HostSafeProcessingWorkRunner.java lines 10-47 deliberately marks every
  claimed work kind HOST_PROCESSING_CAPABILITY_GAP; it performs no Blue
  mutation and completes no managed catch-up work.

These names are truthful forward-compatible scaffolding only.

### Restart behavior

- RuntimeRebuilder.java lines 78-109 replays APPLIED commands into a replacement
  runtime, runs the fail-closed evidence backfill, verifies projections, and
  rebuilds/verifies the state index behind a write barrier. Lines 114-122 make
  the runtime unavailable/read-only on failure.
- ProcessingWorkRepository.java lines 192-218 requeues CLAIMED leases after
  restart with PROCESS_RESTARTED.
- CoordinationCommandWorker.java lines 94-105 invokes lease recovery; lines
  229-289 and 403-439 rebuild and requeue uncertain invocation/projection work.
- CommandFinalizer.java lines 15-53 transactionally persists projections with
  the terminal APPLIED command. ProjectionSynchronizer.synchronize lines
  122-143 writes current projections.

Future catch-up restart must audit the immutable Coordination receipt and
application result, reconcile already committed cursor work, and resume the
next exact occurrence-specific epoch without rerunning source processing.

## Required downstream MyOS seams

1. Populate or migrate managed receipt/event rows only from the new typed,
   self-verifying Coordination SDK audit. Never derive complete events from
   revision.publicEvents().
2. Add explicit Contracts transition-receipt and commit-companion identities;
   the current schema has only opaque exact receipt JSON.
3. Preserve mini_document_epoch as revision/public-subset history. A same-BlueId
   event-only source transition still needs a contiguous epoch and complete
   receipt row.
4. Add a separate occurrence catch-up plan/cursor/barrier table keyed by parent
   session, path, and activation generation. Do not overload occurrence slots.
5. Store source identity, admitted/next/required epochs, plan/barrier
   identities, status, and exact wait reason.
6. Use existing committed/ready heads: historical attachment commits
   CATCHING_UP, missing evidence waits or blocks, and ready promotes only after
   the barrier is terminal.
7. Bind plan/barrier/source receipt identity/order into canonical processing
   work. Consumer revision/evidence, cursor, work completion, head/status, and
   barrier promotion require one database transaction.
8. Replace HostSafeProcessingWorkRunner's capability wait with a typed SDK
   one-step bounded managed application/drain. On response loss, audit the
   commit companion/application receipt before retry.
9. Restart must recover leases, rebuild/audit SDK state, reconcile committed
   work, and resume the exact cursor. It must never invoke source Timeline,
   operation, or initialization processing.
10. Expose verified receipts, plans, cursors, barriers, and diagnostics through
    API/operator projections while normal state reads remain on the ready head.
11. Prefer the new SDK automatic matcher for ordinary use. Retain an advanced
    explicit selector only to resolve ambiguity.

## Frozen compatibility and implementation constraints

- One managed lineage remains one stable DocumentId/session.
- The source document is never reinitialized or reprocessed.
- One immutable source epoch receipt may be applied through many occurrences.
- Each occurrence activation generation owns its own cursor and plan.
- Process Embedded remains the graph relationship.
- Catch-up uses ordinary closure/cyclic processing.
- Source events are delivered through the occurrence in exact order and
  multiplicity and are not republished merely because they are caught up.
- Current-state attachment has no historical work.
- Authored-initial, epoch-zero, and retained-epoch attachment reuse the existing
  source session.
- Repeated-state and cross-lineage ambiguity fail closed unless an explicit
  advanced selector identifies DocumentId and epoch.
- Consumer revision, cursor, application receipt, and readiness commit
  atomically.
- Public APIs evolve additively: do not add components to existing records;
  use overloads/new immutable types/default interface methods as needed.
- Coordination is currently Java 17 bytecode; the additive Contracts core API
  must remain Java-8-compatible as required by its release gates.
- No Maven Local, source copying, published-coordinate overwrite, or staged
  group remote fallback.
- MyOS remains read-only in this round.

## Phase-0 receipt

The audit proves:

- published artifacts and corresponding exact source baselines were identified
  and hashed;
- the expected Contracts complete-transition-receipt gap remains;
- existing Coordination history, lineage, occurrence, readiness, transaction,
  recovery, drain, and legacy barrier mechanisms were mapped;
- the mechanisms to extend rather than replace are identified;
- the clean published-stack MyOS persistence and restart scaffolding was
  characterized without modification;
- optional MyOS receipt/event rows and work/status names are not mistaken for
  an implemented authoritative catch-up capability;
- implementation may proceed only through an immutable staged Contracts
  artifact, with no Maven Local.
