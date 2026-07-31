# Blue Coordination Java

`blue-coordination-java` is the reusable Coordination 1.0 layer over the Blue
Language, BEX, and fixed Repository implementations. It provides concrete
Timeline-derived Channels, source-to-target Operation routing, workflows,
hosted BEX integration, Mandate eligibility helpers, indexed delivery
preparation, and deterministic physical fragmentation.

The generic Contracts engine remains in `blue-language-java`. This project
does not provide persistence, Timeline networking, cross-document scheduling,
managed-Root compare-and-swap, authorization policy, or an outbox.

Given the same exact Root, Event, verified delivery evidence, runtime
registrations, and portable gas schedule, PROCESS has one deterministic
result. Subscription snapshots, delivery plans, fragment inventories, and
preparation results are evidence bound to those semantic inputs; none is a
third semantic PROCESS input.

## Local source graph

Development and release verification require these sibling checkouts:

```text
../blue-language-java
../blue-bex-java
../blue-repository-java
```

`settings.gradle` includes all three builds and substitutes:

```text
blue.language:blue-language-java
blue.bex:blue-bex-java
blue.repo:blue-repo-java
```

Those groups are excluded from remote resolution. A missing sibling therefore
fails configuration instead of silently selecting a published artifact.
Coordination also passes the same Language checkout into the included BEX
build. Exact sibling heads are locked in
`gradle/blue-sibling-lock.properties`.

Check the local dependency boundary with:

```bash
./gradlew test \
  --tests blue.coordination.processor.LocalCompositeDependencyTest \
  --offline --no-daemon -PtestJfr=false
./gradlew verifyNestedLocalCompositeDependencies \
  --offline --no-daemon -PtestJfr=false
```

## Working/development verification

The closed working gate executes every Coordination-owned capability except
the exact probes declared in
`gradle/coordination-external-blockers.json`. It then executes every declared
probe separately and accepts it only when it passes or reproduces its exact
catalogued diagnostic:

```bash
./gradlew coordinationWorkingVerification \
  --offline --no-daemon -PtestJfr=false
```

The gate writes:

```text
build/reports/coordination-working/final.json
build/reports/coordination-working/final.md
build/reports/coordination-working/external-blockers.json
build/reports/coordination-working/dependency-lock.json
```

`workingEligible` means the local Coordination artifact is usable against the
exact locked sibling sources. It does not imply public release eligibility.
The strict release command remains fail-closed while any external probe is
blocked:

```bash
./gradlew finalCoordinationVerification \
  --offline --no-daemon -PtestJfr=false
```

## Runtime registration and delivery-planning modes

Configure a Language runtime with an exact verified provider, then register
Coordination runtime semantics:

```java
BlueRepository repository = BlueRepository.latest();
Blue blue = hostVerifiedRuntime(repository);
CoordinationProcessors.registerWith(blue);
```

`hostVerifiedRuntime` is host assembly, not a Coordination API. Its
`NodeProvider` must admit the fixed Repository through Language's
`BOUND_SOURCE_CONTENT` evidence mode and bind the exact Repository coordinate,
manifest identity, source commit, loaded artifact digest, Language release,
registry, preprocessing environment, and provider domain. Directly installing
`repository.nodeProvider()` is not a verified release configuration. The
release suite exercises the library's internal fixed-Repository adapter and
publishes its fail-closed catalog audit.

For direct builder use:

```java
DocumentProcessor processor =
        CoordinationProcessors.configure(DocumentProcessor.builder())
                .build();
```

Registration installs concrete Channel, Handler, workflow, step, gas, and BEX
semantics. It deliberately installs no external delivery-plan deriver. The
host architecture is an explicit choice.

Timeline Channel subtypes are also an explicit host choice; the default
registration contains no product-specific subtype list:

```java
CoordinationProcessors.registerTimelineSubtype(
        blue, HostTimelineChannel.class);
```

The corresponding builder overload accepts the same exact subtype class.
Language still verifies the subtype's Blue type evidence when it is used.

### Whole-current-Root compatibility

Small or transitional hosts can opt into the deterministic compatibility
deriver:

```java
CoordinationProcessors.registerWith(blue);
CoordinationDeliveryPlanning.currentRootCompatibility(blue);
```

The equivalent `DocumentProcessor` overload mutates and returns the supplied
processor. `currentRootCompatibilityDeriver(processor)` returns the deriver
without installing it. This mode derives delivery evidence by examining the
complete current Root for each event.

### Indexed planning

Hosts that maintain a subscription index use the public persistence-neutral
façades:

```java
CoordinationSubscriptionProjector projector =
        CoordinationDeliveryPlanning.subscriptionProjector(processor);
CoordinationSubscriptionSnapshot snapshot =
        projector.projectCurrent(root, rootRevision, activationFrontier);

CoordinationIndexedDeliveryPlanner planner =
        CoordinationDeliveryPlanning.indexed(processor);
CoordinationPreparedDelivery prepared =
        planner.prepare(
                rootBlueId,
                eventBlueId,
                snapshot,
                orderedCandidateOccurrenceKeys,
                exactProvider,
                rootRevision,
                eventOrderKey);
```

The ordered candidate collection is an exact index contract. The planner
rejects duplicates, omissions, extras, stale snapshots, wrong revisions,
wrong order, runtime identity drift, and provider evidence that does not bind
to the requested Root or Event. It re-runs the registered Language
subscription and complete-acceptance functions before producing
`VerifiedExecutionEvidence` and the canonical `ExternalDeliveryPlan`.

`CoordinationPreparedDelivery` also exposes canonical source diagnostics,
checkpoint domains and subjects, effective routed targets, logical-delivery
keys, selected scope chains, required seed fragments, deterministic prefetch
suggestions, and a strict semantic-demand boundary. These values are immutable
diagnostics and evidence, not mutable runtime contracts.

## Subscription snapshots and deltas

`CoordinationSubscriptionProjector` delegates generic admission and
incremental validation to Language. `projectCurrent` performs the initial
complete projection. `projectUpdate` accepts the resulting Root revision,
strictly advancing order key, and exact changed paths so unaffected branches
can be retained without expanding executable bodies. The overload without
changed paths intentionally treats the whole Root as changed.

`CoordinationSubscriptionSnapshot` is:

- immutable and canonically ordered;
- bound to the Root BlueId, host revision, activation frontier, Language and
  Coordination runtime identities, and projection algorithm;
- identity-bearing through `digest()`;
- serializable as scalar/list/map data with `toMap()` and fail-closed
  `rehydrate(...)`;
- free of executable bodies and provider transport details;
- complete enough to retain occurrence paths, exact scope/header identities,
  source contributions, subscription keys, dependency identities, active
  intervals, Process Embedded topology, and pruned scopes.

`CoordinationSubscriptionUpdate` separates `added`, `retired`, and `unchanged`
occurrences and contains the resulting snapshot. A changed domain or header is
represented as retire plus add. Removing and later re-adding the same
occurrence begins a new activation interval.

Persistence, index layout, revision allocation, and atomic publication of a
snapshot remain host concerns.

## Timeline Channels and Operation routing

Timeline subscription projection emits bounded keys for exact Timeline and
Actor identities and uses a broad key only when richer structural matching
requires it. Complete Language matching remains authoritative. Registered
subtypes participate through verified type evidence; semantic matching is not
a concrete-class whitelist.

For an Operation Request:

```text
source external Channel
  owns acceptance, attribution, payload, freshness, checkpoint domain,
  checkpoint subject, and checkpoint commit

target same-scope Channel
  is selected by Operation Request.channel for Handler discovery
  is frozen as an immutable dispatch header
  is not externally evaluated and owns no source checkpoint
```

Equivalent fresh sources may coalesce only when their payload, target, and
logical-delivery identities agree. Every participating source retains its own
checkpoint, and none commits until the complete logical delivery succeeds. A
stale source cannot piggyback on a fresh one.

## Workflows and hosted BEX

Sequential Workflow executes exact declared steps in order over one
workflow-owned working document:

- Update Document delegates patch semantics to Language;
- Trigger Event delegates event delivery to Language;
- Terminate Processing accepts optional `reason` and derives its cause from
  the exact fixed type identity;
- Compute resolves the exact Compute Definition and uses the
  processor-owned BEX semantic-output boundary.

Compute execution uses the parent-bounded Language runtime-work session. BEX
and Coordination retain their own named counter namespaces without
double-charging Language work. Rejected charges are absent from the trace;
deterministic exhaustion retains the admitted prefix and rolls back Root
changes, Root-public events, and checkpoints.

## Canonical fragmentation and processing preparation

`CoordinationDocumentSplitter` is a physical preparation accelerator. It does
not select deliveries, authorize evidence, execute a contract, alter portable
gas, or create another semantic PROCESS input.

Its stable physical profile is:

```text
blue.coordination/fragmentation/canonical-direct-node/1.0
```

Every exact BlueId has one canonical direct-node fragment representation
within that profile, whether encountered as a document Root, event Root,
embedded scope, source contribution, or executable body. The split graph
separates physical fragments from canonically ordered edge occurrences. Edge
metadata records the owning Root and node, scope and pointers, child BlueId,
edge kind, authored-reference versus splitter-created status, and applicable
effective Handler/body/source-contribution identities.

`SplitGraph.reconstruct()` uses only the immutable inventory and edge metadata,
preserves authored references, verifies the final identity, and rejects
missing, unreachable, mixed-profile, or inconsistent content.
`CoordinationFragmentAdmissionVerifier` supports immutable concurrent
admission: it re-reads and verifies the winning canonical bytes, treats an
equal duplicate as idempotent, and rejects inconsistent content.

An indexed plan and independently produced document/event split graphs can be
combined without persistence:

```java
CoordinationProcessingPreparation preparation =
        CoordinationProcessingPreparation.combine(
                preparedDelivery,
                documentSplitGraph,
                eventSplitGraph);
```

The result carries exact references, verified evidence, plan and snapshot
identities, scope-chain diagnostics, fragment-profile and inventory
identities, exact edge occurrences, required seeds, prefetch suggestions, and
the semantic-demand boundary. Combining does not itself plan, split, persist,
schedule, authorize, or execute.

## Cyclic boundary

Cyclic-set member edges remain opaque exact references:

```text
MASTER#index is an opaque edge
member content requires complete cyclic-set proof
a pure cyclic member is not an independently processable top-level value
Process Embedded cannot end at or traverse an opaque member edge
a patch below the member edge fails before provider demand
whole-edge replacement remains allowed
```

Projection never promotes opaque members into subscription scopes. Splitting
does not fabricate member fragments, and reconstruction does not traverse an
opaque edge.

## Portable gas and nonportable host quotas

Portable PROCESS gas is loaded from
`coordination-gas-1.0.yaml`. Coordination charges its named counters through
Language's runtime-work boundary before work. Provider bytes, caches,
persistence, index maintenance, fragment storage, and splitter work are never
reported as portable PROCESS gas.

Preparation work is bounded separately by the manifest-backed
`CoordinationHostQuotaSession`. These invocation-local quotas are diagnostic
host limits, not consensus gas. Quota exhaustion fails deterministically and
does not add to `PROCESS.totalGas`. APIs without a supplied session use a
disabled-tracing session that still enforces the manifest limits. A host that
needs an auditable preparation trace should pass an explicit session to the
available projection, planning, splitter, and Mandate overloads.

## Fixed Repository evidence

The generated catalog is read-only. `FixedRepositoryBoundSourceProvider`
binds the Repository coordinate, version, manifest identity, source commit,
artifact hash, Language release, Contracts runtime registry, provider domain,
and `BOUND_SOURCE_CONTENT` verification mode. It preserves `NOT_FOUND`,
`UNAVAILABLE`, and `INVALID_EVIDENCE`; it does not trust an authored `blueId`,
create aliases, or patch catalog content.

`FixedRepositoryBoundSourceProviderTest` defines the complete catalog audit:

```text
1,107 definitions
10 cyclic sets
27 cyclic members
provider mode BOUND_SOURCE_CONTENT
required result: 1,107 verified, 0 failed
```

The audit writes
`build/reports/coordination-release/fixed-repository.json`. Absence of that
same-run report, any failed definition, or a manifest binding mismatch blocks
release. The durable pre-edit baseline records earlier dependency-evidence
failures; it is historical evidence and must not be presented as the current
catalog result.

## Tests and release evidence

JUnit methods use readable `should...` names and exact `// Given`,
`// When`, and `// Then` sections. Useful focused commands include:

```bash
./gradlew coordinationTimelineConformanceTest \
  --offline --no-daemon -PtestJfr=false
./gradlew coordinationRuntimeGasTest coordinationLoopSafetyTest \
  --offline --no-daemon -PtestJfr=false
./gradlew coordinationFlagshipTest localFixedRepositoryCompatibilityTest \
  --offline --no-daemon -PtestJfr=false
./gradlew coordinationClosedConformanceTest \
  --offline --no-daemon -PtestJfr=false
```

The hard release graph is:

```bash
./gradlew finalCoordinationVerification \
  --offline --no-daemon -PtestJfr=false
```

It executes same-run tests and conformance, the 32-run flagship matrix, the
516-entry trace proof, the full fixed-catalog audit, binary compatibility,
Java 8 bytecode verification, JMH evidence, API reporting, and reproducible
Coordination-owned archives. Publication and release tasks depend on this
gate.

Release evidence lives at:

```text
gradle/coordination-release-baseline.json
build/reports/coordination-release/baseline.json
build/reports/coordination-release/final.json
build/reports/coordination-release/final.md
build/reports/coordination-release/fixed-repository.json
```

The baseline source is the immutable pre-edit capture; the build copy is
restored after `clean`. The final JSON has schema
`blue.coordination/release-result/1.0` and is written for both green and red
candidates. `releaseEligible` is true only when `blockingReasons` is empty and
every required result was produced from the same exact source/dependency
state. A missing, stale, skipped, or failed result keeps
`finalCoordinationVerification` red.
