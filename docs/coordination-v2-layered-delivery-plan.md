# Coordination 1.0 layered delivery architecture

Status: implemented generic library architecture; release status is decided
only by the same-run release report.

This document describes the current architecture. It replaces the historical
Coordination V2 proposal and deliberately contains no persistence or
application design.

## Layer and ownership order

```text
blue-language-java
  Blue values, Contracts semantics, scopes, matching, updates, event FIFO,
  checkpoints, atomic rollback, provider verification, execution evidence,
  portable runtime-work boundary
        |
blue-bex-java
  deterministic BEX compilation/runtime and hosted work-session SPI
        |
blue-repository-java
  immutable generated Coordination types and fixed catalog content
        |
blue-contract-java
  concrete Timeline Channels, source/target routing, workflows, hosted BEX,
  Mandate helpers, subscription projection, indexed planning, fragmentation
```

The build uses only the adjacent Language, BEX, and Repository source
checkouts. Coordination does not copy generic contract processing or patch
generated catalog types.

Host-owned concerns remain outside all four semantic layers:

- durable subscription and fragment storage;
- Timeline Provider networking and completeness evidence;
- global ordering and revision allocation;
- managed-Root compare-and-swap;
- cross-document scheduling;
- authorization policy and Mandate history storage;
- outbox publication.

## The two-input semantic boundary

The authoritative semantic operation remains:

```text
PROCESS(exact Root, exact Event)
```

Verified delivery evidence, an external delivery plan, a subscription
snapshot, a fragment inventory, and a preparation result are deterministic
implementation evidence bound to those inputs. They are neither Blue content
nor an additional semantic input.

One PROCESS owns one Root transition. Embedded scopes are owned inside that
Root. Only Root-emitted events are public. Failure rolls back the Root,
Root-public events, and source checkpoints as one result.

## Registration is architecture-neutral

`CoordinationProcessors.configure(...)` and
`CoordinationProcessors.registerWith(...)` install only runtime semantics.
They do not install a delivery-plan deriver and therefore do not silently
select a whole-Root persistence strategy.

The host chooses one of two explicit modes.

### Compatibility mode

```text
CoordinationDeliveryPlanning.currentRootCompatibility(processor or blue)
```

This installs the deterministic current-Root deriver. It is useful when a host
can afford to derive the complete effective external Channel surface for each
event. It is a compatibility architecture, not historical activation-state
reconstruction.

### Indexed mode

```text
CoordinationDeliveryPlanning.subscriptionProjector(processor)
CoordinationDeliveryPlanning.indexed(processor)
```

Indexed mode separates Root-transition projection from event-time planning:

```text
admitted Root revision
        |
        v
projectCurrent / projectUpdate(changed paths)
        |
        v
immutable CoordinationSubscriptionSnapshot
        |
        +---- host persists/indexes occurrence keys ----+
                                                      |
exact Event + ordered index candidates                |
        |                                             |
        +------------------------+--------------------+
                                 v
                  CoordinationIndexedDeliveryPlanner
                                 |
                                 v
               verified evidence + canonical plan
```

The host owns persistence and lookup. Coordination remains the semantic
authority: the indexed planner verifies the snapshot, exact provider
evidence, canonical candidate set, order, revision, activation frontier, and
complete Channel acceptance.

## Subscription projection layer

`CoordinationSubscriptionSnapshot` is an immutable, canonically ordered,
scalar/list/map value. Its digest binds:

- schema/projection version;
- Language/Contracts runtime registry identity;
- Coordination runtime registry identity, including the exact BlueIds of
  every explicitly registered Timeline Channel subtype;
- subscription projection algorithm identity;
- Root BlueId and host revision;
- activation frontier;
- active occurrence headers and exact dependency snapshots;
- Process Embedded route topology and pruned scopes.

Each `CoordinationSubscriptionOccurrence` identifies one scope-path/raw-key
occurrence and retains exact scope/header/type/domain/source-contribution
evidence, ordered subscription keys, dependencies, and its activation
interval. Executable bodies and provider transport state are excluded.

`toMap()` and `rehydrate(...)` provide application-neutral persistence.
Rehydration recomputes canonical identity and rejects drift.

Initial projection performs one complete admission. Incremental projection
delegates generic changed-branch and dependency-closure validation to Language.
The resulting `CoordinationSubscriptionUpdate` separates:

```text
added
retired
unchanged
```

Retyping or changing a domain/header is retire plus add. An unchanged
occurrence retains its activation interval. Removal and later re-addition
starts a new interval. The compatibility update overload marks `/` changed;
indexed hosts should provide exact changed paths.

Opaque cyclic members never become projected scopes.

## Indexed event-planning layer

The planner accepts exact Root/Event identities, an active snapshot, an exact
ordered candidate list, an exact provider, Root revision, and event order.
The index contract is exact rather than a false-positive superset.

The planner fails closed on:

- duplicate, omitted, extra, or wrongly ordered candidates;
- stale or unknown occurrences;
- wrong Root, revision, or nonadvancing order;
- snapshot schema, digest, algorithm, or runtime identity drift;
- provider misses, unavailability, or invalid evidence;
- header mutation or complete-acceptance disagreement.

`CoordinationPreparedDelivery` contains:

- exact Root and Event references;
- `VerifiedExecutionEvidence`;
- canonical `ExternalDeliveryPlan` and identity;
- snapshot identity and selected occurrence order;
- source delivery diagnostics;
- source checkpoint domains and subjects;
- effective same-scope routed target headers;
- logical-delivery keys;
- selected scope-chain and required-seed identities;
- deterministic prefetch suggestions;
- `CoordinationSemanticDemandBoundary`.

The demand boundary describes locality. It permits selected Root-to-scope
chains, source/target headers, selected bodies, runtime-reached reactive
bodies, and values read in selected scopes while rejecting unrelated siblings
and unselected bodies. It does not bypass Language verification or authorize
PROCESS.

## Source-owned routing layer

Operation Request routing preserves separate source and target roles:

```text
external source Channel
  complete acceptance
  attribution and payload
  freshness
  checkpoint domain and subject
  activation interval

same-scope target Channel
  selected by Operation Request.channel
  immutable Handler-dispatch header
  not externally evaluated
  not source-checkpointed
```

A target cannot create external eligibility. Equivalent fresh sources may
coalesce only when payload, target, and logical-delivery identities agree.
Each source still retains its own checkpoint, and all participating
checkpoints commit only after total success. Stale sources are excluded before
coalescing.

## Workflow and hosted-execution layer

Sequential Workflow preserves declared order:

- Update Document uses Language patch semantics;
- Trigger Event uses Language event delivery;
- Terminate Processing derives its cause from the fixed type and retains only
  optional `reason`;
- Compute resolves an exact Compute Definition and crosses the
  processor-owned semantic-output boundary once.

Each Compute invocation uses the Language-owned parent runtime-work boundary
and the released BEX hosted ledger SPI. Coordination, Contracts, and BEX own
disjoint named counters. Work is charged before execution. Exhaustion keeps
the admitted trace prefix, excludes the rejected charge, performs no later
work, and rolls back semantic effects.

## Canonical physical-fragment layer

`CoordinationDocumentSplitter` derives embedded-root and executable-body
boundaries from Language's effective inheritance-aware fragmentation catalog.
Event and document splitting share the physical profile:

```text
blue.coordination/fragmentation/canonical-direct-node/1.0
```

The same BlueId therefore has the same canonical direct-node fragment bytes
regardless of where it was encountered. Semantic cut occurrences are metadata
and never justify storing another physical body under the same profile and
BlueId.

The edge schema
`blue.coordination/fragment-edge-occurrence/1.0` records every direct edge:

- inventory Root kind and BlueId;
- owner node BlueId and optional scope path;
- absolute and owner-relative pointer;
- child BlueId and edge kind;
- authored pure reference versus splitter-created reference;
- applicable Handler effective type, body field, and ordered source
  contributions.

This distinguishes authored references, splitter-created collapses, and
several occurrences of one child identity at different pointers.

`SplitGraph.reconstruct()` expands only splitter-created edges, preserves
authored references, checks the complete inventory, and verifies the final
identity. Missing fragments, mixed profiles, unexplained edges, unreachable
content, and inconsistent inventories fail.

`CoordinationFragmentAdmissionVerifier` defines immutable storage admission:
concurrent writers may race, but the winner is re-read and byte-verified.
Equal duplicates are idempotent. Different content for one `(profile,
BlueId)` is fatal evidence failure. Provider responses remain defensive, and
warm cache state never relaxes a demand boundary.

## Generic processing preparation

`CoordinationProcessingPreparation.combine(...)` joins an already verified
indexed plan with already generated document and event split graphs. The
immutable result binds:

```text
Root/Event references
execution evidence and delivery-plan identity
subscription snapshot identity
fragmentation profile and edge schema
document/event inventory identities
document/event edge occurrences
selected scopes and source diagnostics
required seed fragments and prefetch suggestions
semantic demand boundary
```

Combining is a handoff to an arbitrary exact-provider host. It does not plan,
split, persist, schedule, authorize, or execute.

## Cyclic boundary

The physical and semantic layers share one rule:

```text
MASTER#index is opaque
member content requires complete cyclic-set proof
a pure member is not a top-level processable value
Process Embedded cannot stop at or traverse the opaque member
patching below the edge fails before provider demand
whole-edge replacement is allowed
```

Projection preserves the reference without creating a scope. Splitting does
not fabricate or fetch a member fragment. Reconstruction preserves the
authored opaque edge. A literal object or fragment cycle is rejected.

## Portable gas and preparation quotas

Portable gas is consensus-visible PROCESS evidence. Fourteen Coordination
counters are loaded from `coordination-gas-1.0.yaml` and emitted through
Language's runtime-work session. Provider transport, cache state,
fragmentation, index storage, and persistence are never portable gas.

Preparation quotas are invocation-local host diagnostics loaded from
`coordination-host-quotas-1.0.yaml`. Explicit quota sessions bound supported
projection, candidate-validation/prefetch, splitter, and Mandate preparation
overloads. They fail deterministically but never affect `PROCESS.totalGas` or
the portable trace. Storage and network policy remain outside the library.

## Fixed Repository evidence layer

The fixed catalog remains immutable. The internal bound-source provider uses
Language's released evidence model with:

```text
provider mode BOUND_SOURCE_CONTENT
exact coordinate and version
manifest identity
source commit and observed artifact hash
Language release and Contracts runtime registry
provider domain
cyclic-set proof where required
```

It preserves typed provider outcomes and never treats an authored `blueId`,
Java class name, or alias as identity proof.

The complete audit is specified as 1,107 definitions, including 10 cyclic sets
and 27 cyclic members. A green same-run result is 1,107 verified and zero
failed. That report is generated at
`build/reports/coordination-release/fixed-repository.json`; a missing report or
manifest mismatch blocks release. The historical baseline is not current
catalog evidence.

## Release evidence layer

The immutable pre-edit capture is:

```text
gradle/coordination-release-baseline.json
```

After `clean`, the release graph restores it to:

```text
build/reports/coordination-release/baseline.json
```

The hard command is:

```bash
./gradlew finalCoordinationVerification \
  --offline --no-daemon -PtestJfr=false
```

It always attempts to write:

```text
build/reports/coordination-release/final.json
build/reports/coordination-release/final.md
```

The final report contains exact source, artifact, runtime, manifest, API,
test, conformance, flagship, trace, fixed-catalog, locality, compatibility,
bytecode, and reproducibility evidence. It records blockers for a red
candidate. `finalCoordinationVerification` succeeds only when
`releaseEligible` is true and `blockingReasons` is empty in that same-run
report. Publication tasks depend on this gate.
