# Implemented API reference and host protocol notes

> **Revision:** 15.12, synchronized 2026-09-06 · **Status:** documentation for the implemented local POC; not a released or frozen API

[22 — Processing kernel](../22-processing-kernel.md) owns the semantics.
[05 — API contract](../05-poc-api.md) maps them to the implemented boundary.
[24 — Library summary](../24-phase-1-library-summary.md) and the
[verification record](../implementation/phase-1-2-readiness.md) record Phase1/2 scope, evidence and limits.
[25 — Phase3 plan](../25-phase-3-integration-plan.md) describes the remaining application integration;
Phase3 has not been implemented by this documentation update.

[00-poc-minimal-boundary.java](00-poc-minimal-boundary.java) now contains **usage examples of actual
public APIs**, followed by explicitly non-executable host protocol notes. Its example helper class
is not itself a library API. It replaces the earlier standalone model of proposed records/interfaces:
those declarations were not runtime constructors, a wire schema, or proof of implementation.

## Actual public boundary

The source of truth is the local implementation in
`worktrees/coordination-external-state/blue-coordination-java/src/main/java/blue/coordination/external`
and the corresponding Language closure package. That worktree path is relative to the Blue projects
directory, not to this reference folder. The examples use these actual types:

| Responsibility | Implemented API |
|---|---|
| Stateless evaluation | `CoordinationCore(DocumentProcessor, ClosureEnvironment, ExecutionPolicy)` |
| Initialization / external input | `evaluate(WorkIntent, EvaluationEvidence)`; optional `SameOriginAttachmentPolicy` overload |
| Managed receipt import | `evaluate(ManagedImportSelection, EvaluationEvidence)` |
| Selection needs / no work | `NeedEvidence`, `Idle` |
| Metadata-only progress | `MetadataProgress`, `ManagedProgress` |
| Complete proposals | `PreparedOperation`, `PreparedOperations` containing independently atomic `PreparedGroupOperation` values |
| Canonical source history | `CanonicalSourceHistory.start / prepareNext / resume`; `Await / Step / Complete / Blocked` |
| Initialization / attachment | `SourceInitialization`, `ObserverAttachmentPlan`, `SourceFrontierSelection`, `ManagedImportLane` |
| Cold retained evidence | `OperationReceiptCodec`, `ManagedProgressReceiptCodec`, Language's `SourceObservationProgram` and evidence codecs |
| Sparse exact read cut | `AffectedClosureSnapshot`, `ReusableComponentAuthority`, `RootChannelMetadata`, `ManagedReadPin` |

There is no Core `SemanticConfiguration` constructor/getter, `prepareInitialization`,
`prepareSourcePrefixStep`, or `expandFanout`. Public-root/scope authority arrives through the
snapshot, not another constructor argument. Current Core dispositions are fixed laws, not a
configurable `FrozenExternalInputDispositionPolicy` table. Historical source preparation uses
`EXTERNAL_INPUT`; there is no `HISTORICAL_EXTERNAL_REPLAY` operation-kind constant.

## Reading the examples

The examples show evidence assembly and calls, not a runnable server or a complete persistence
adapter. Use actual verified processor/environment/policy values and authenticated read-cut
evidence. Do not copy a type name from old prose into a new host-defined substitute.

- A Core Timeline identifier is the locator string in the exact typed Timeline's `/timelineId`,
  while the PostgreSQL Timeline store uses the exact typed Timeline BlueId. Translate through
  authenticated Timeline evidence. The complete typed Timeline Entry is both `entry` and `event`;
  neither a raw locator alone nor the message payload establishes that identity.
- Source programs preserve actual intermediate observations, patch topology, enqueues, borrowed
  evidence, initialization/frontier selections and termination/cutoff authority. They are not flat
  final-state/event lists or the old sketch's `ObservationFrame` DTO hierarchy.
- A prepared group is one atomic publication unit. A `PreparedOperations` container can contain
  several dependency-ordered units, all of which must be retained. It does not permit partial
  publication of an unfinished interpreter or merge the groups' gas ledgers.
- Failed-source terminal continuity, each consumer's last successful source view, lane progress,
  business epoch and external handled position remain distinct. Use actual failure/gap/lane evidence;
  an I/O failure or unknown exception is not a consumable semantic `RUNTIME_FATAL`.
- Sparse bodies are supported only with verified complete directed metadata/read authority.
  Missing selection facts still hold selection. Missing execution bodies produce named needs;
  hydration must supply the exact selected value, never an ambient later head.

Source programs and their borrowed evidence DAG are presently decoded within physical budgets;
this is not fully streaming replay. Capture-time peak memory and integrated scale remain measurement
obligations. Missing evidence exposes no portable tentative queue, partial business result or
settled gas. Retry re-enters evaluation, not a serialized private continuation.

## Host concepts intentionally left as protocol requirements

`CommitReceipt`, `CommitUnknown`, `DurableFanoutBasis`, `HostWorkSelectionPort`,
`RecoveryPort`, and the former `OperationalBudget / SemanticConfiguration` records are explanatory
host/acceptance concepts, not additional public types supplied by the external Core.

Phase2 already supplies PostgreSQL storage/indexes, ready/reverse-wait primitives and cold recovery
witnesses in `myos-simple`. Phase3 must connect those primitives to the general graph adapter and
application pumps, and measure the resulting workload. The host requirements remain unchanged:
exact scoped fences, dependency-first publication, atomic owner projections/settlement/outbox,
no-gap registration, bounded indexed discovery, durable wakeups and exact unknown-commit
reconciliation. SQL pages, caches, physical work order or self-hashed wrappers cannot choose
semantic identities, chronological results, operation ownership or gas.

This reference is documentation, not a formal all-catalog acceptance archive or production-readiness
claim. The linked verification record, not compilation of this file, states what was actually tested.
