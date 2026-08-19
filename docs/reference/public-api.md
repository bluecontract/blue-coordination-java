# Public API reference

The supported application boundary is the small set of types in
`blue.coordination.api`. Full signatures and contracts are in the generated
Javadocs.

## Lifecycle and commands

- `CoordinationEngine` creates the in-memory environment and owns resources.
- `Timeline` identifies one authenticated append-only stream.
- `Operation` describes an operation/channel and either YAML or an `ExactValue`
  request.
- `DocumentId` identifies one continuing managed document history; a state
  BlueId identifies one exact immutable state within that history.
- `ActivationMode` names supported embedded-document temporal behavior.
- `startDocument(..., AdmissionPolicy, verifiedFrontier)` selects top-level
  `FULL_HISTORY`, `FROM_FRONTIER`, or `FROM_NOW` behavior in the legacy
  profile. Contracts mode rejects this singleton boundary.
- `admitContractsClosure(input, policy, verifiedFrontier)` is the Contracts 1.0
  multi-document admission boundary. The caller supplies one exact typed
  `ADMIT_CLOSURE` invocation whose operation, environment, configured policy,
  public Roots, member graph, and proofs are verified by Contracts. The bounded
  1.0 lane atomically admits all members only when every lineage is new; mixed
  existing/new membership fails closed.
- `appendTimelineEntry(Node)` validates and stores one externally supplied exact
  entry without routing or PROCESS. `append` and `appendAt` are convenience
  builders with the same append/process separation.
- `drain()` selects canonical work to quiescence; `drainThrough(cutoff)` stops at
  an inclusive upper bound without skipping earlier eligible work.
- `drain(new DrainBudget(processCommits, selectedEntries))` pauses only at a
  deterministic safe boundary. Its receipt reports `paused()` and the exact
  frozen PROCESS transitions committed by that call; a later drain resumes the
  retained entry frame without repeating them.
- `document(id)` returns only a coherent `READY` snapshot. Operational audit and
  recovery tooling can use `auditDocument(id)` to inspect committed
  `CATCHING_UP` or `BLOCKED` state deliberately.

The caller never supplies document recipients and cannot select an exact entry
to process ahead of earlier eligible work. `routeTargetCount` is diagnostic; it
uses canonical journal evidence, reports only targets expressible by the pinned
provider model, and does not process the entry.

## Embedded admission evidence

The three-argument `configureEmbeddedAdmission(childId, mode, frontier)` is a
convenience default for future occurrences of that child. When attachment
identity matters, append the attachment entry first, then register the
occurrence-specific overload before draining it. That plan binds the parent
DocumentId, canonical absolute occurrence path, child DocumentId, supplied
state BlueId, optional exact child epoch, activation mode, verified frontier,
completeness-proof identity, and expected attachment-entry BlueId.

Occurrence plans take precedence over the child default and are consumed only
with successful graph publication. A failed publication restores the plan for
an exact retry. If the same exact child-state BlueId occurs at more than one
committed epoch, omitting `admittedEpoch` fails closed; content identity alone
cannot choose temporal position.

`DrainBudget` limits selected canonical entries and committed frozen PROCESS
transitions, not elapsed time. One frozen PROCESS invocation is atomic and
non-preemptible, and epoch-zero INITIALIZE work performed by an attachment is
outside the PROCESS-commit count. Use `elapsedNanos()` for observed duration,
not as evidence of a deadline guarantee.

## Target derivation and upstream boundary

The environment derives recipients from exact active subscription intervals.
Scalar Timeline Channels, Composite Timeline Channels, and the frozen
same-scope All Timelines family are supported. The caller never supplies a
recipient set.

Repository-native `OperationRequest.document` targeting is supported as a
separate feature. With `requireExactDocumentVersion: true`, only a candidate at
that exact current state is eligible. With a false or absent flag, any retained
known epoch of that candidate is eligible. An absent document leaves routing
unrestricted.

The pinned generic Timeline Entry model has no universal literal `documentId`
target. That is an optional generalized-profile capability, not a blocker for
environment-derived routing; concrete Channel/message profiles may define exact
target derivation and must continue to fail closed when their evidence is
missing.

The pinned provider boundary separately has no general Mandate-state resolver
for per-target eligibility. This RC does not infer or simulate authority;
authority-bearing `onBehalfOf` entries fail closed. Exact provider-backed
Mandate resolution remains an upstream blocker.

## Immutable results

- `TimelineEntry` is the exact journaled event.
- `TimelineAppendReceipt` proves exact journal admission.
- `ProcessingDrainReceipt` reports environment-selected entry order and groups
  `DocumentDispatchOutcome` values by entry. For a bounded call, it contains
  only work committed by that call, even when it pauses or resumes an older
  entry frame. `quiescent()`, `paused()`, and `blocked()` distinguish completion,
  a caller-selected work boundary, and unavailable prerequisite evidence.
- `DocumentSnapshot` is current state plus readiness/frontier evidence.
- `DocumentRevision` is one immutable state transition with provenance.
- `ExactValue` retains verified content identity and frozen form.
- `ContractsClosureAdmissionReceipt` retains the exact
  `ClosureAttemptResult`, a framed host publication identity, canonical admitted
  `DocumentId` list, and `NOT_PUBLISHED`, `PUBLISHED`, or
  `ALREADY_PUBLISHED` outcome. `NeedsResources` and rejection are
  `NOT_PUBLISHED` with no durable mutation. An exact retry returns the original
  attempt as `ALREADY_PUBLISHED` and reconciles missing route-cache rows.
- `CoordinationMetrics` exposes cumulative phase timers, work counters and
  gauges.

## Failures

`CoordinationException` carries a stable `CoordinationErrorCode` plus immutable
details. Invalid identities, missing/not-ready documents, unavailable or
invalid history evidence, route misses, frozen processing failures, atomic
commit failures and ownership violations are explicit.

## Dependency surface

The POM exposes `blue-contracts-core`, `blue-bex-core` and
`blue-bex-contracts` at compile scope because public API values and processor
signatures expose their types. Repository and Bouncy Castle remain
runtime-scoped implementation dependencies. All coordinates are exact and
dependency locked.

`blue.coordination.processor` is an advanced semantic integration surface used
to assemble the retained Contracts/BEX processors. It is documented in the
Javadoc JAR, but ordinary applications should start at `CoordinationEngine`.
`blue.coordination.internal` is never an application API and may change between
release candidates.
