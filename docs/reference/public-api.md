# Public API reference

The supported application boundary is the 16 top-level types in
`blue.coordination.api`. Full signatures and contracts are in the generated
Javadocs.

## Lifecycle and commands

- `CoordinationEngine` creates the in-memory environment and owns resources.
- `Timeline` identifies one authenticated append-only stream.
- `Operation` describes an operation/channel and either YAML or an `ExactValue`
  request.
- `DocumentId` is the stable host identity of one autonomous document.
- `ActivationMode` names supported temporal admission behavior.

## Immutable results

- `TimelineEntry` is the exact journaled event.
- `DispatchResult` and `DocumentDispatchOutcome` describe root delivery.
- `DocumentSnapshot` is current state plus readiness/frontier evidence.
- `DocumentRevision` is one immutable state transition with provenance.
- `EnvironmentFrontier` is an immutable per-Timeline append frontier.
- `ExactValue` retains verified content identity and frozen form.
- `CoordinationMetrics` exposes cumulative phase timers, work counters and
  gauges.

## Failures

`CoordinationException` carries a stable `CoordinationErrorCode` plus immutable
details. Invalid identities, missing/not-ready documents, unsupported semantics,
route misses, frozen processing failures, atomic commit failures and ownership
violations are explicit.

## Dependency surface

The POM exposes `blue-contracts-core` at compile scope because API values use
Language nodes. Repository, BEX and Bouncy Castle are runtime-scoped
implementation dependencies. All coordinates are exact and dependency locked.

`blue.coordination.processor` is an advanced semantic integration surface used
to assemble the retained Contracts/BEX processors. It is documented in the
Javadoc JAR, but ordinary applications should start at `CoordinationEngine`.
`blue.coordination.internal` is never an application API and may change between
release candidates.
