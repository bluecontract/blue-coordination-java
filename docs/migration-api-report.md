# Coordination release migration and public API report

This report describes the migration from the
`blue-coordination-java:2.0.0-rc.4` binary baseline to the current generic
Coordination 1.0 release candidate. It records API intent and host migration;
it is not a release-completion claim.

## Source dependency boundary

The build requires the adjacent source projects:

```text
../blue-language-java
../blue-bex-java
../blue-repository-java
```

Composite substitution resolves the Language, BEX, and Repository coordinates
to those projects. Remote resolution is excluded for their groups, including
transitive BEX-to-Language resolution. A missing or mismatched sibling fails
closed.

The only remote binary used by the API process is the read-only Coordination
baseline consumed by `binaryCompatibilityCheck`. Repository definitions are
read through `BlueRepository.latest()` and generated types; Coordination does
not copy, repair, alias, or regenerate catalog content.

## Registration behavior change

The most important host-visible change is architectural:

```text
before
  Coordination runtime registration also installed a complete-current-Root
  delivery-plan deriver

now
  registration installs runtime semantics only
  the host selects compatibility or indexed planning explicitly
```

Existing registration entry points remain:

```java
CoordinationProcessors.contracts(language);
CoordinationProcessors.contracts(language, options);
CoordinationProcessors.configure(builder);
CoordinationProcessors.configure(builder, options);
```

They register concrete Channels, Handlers, workflows, steps, runtime gas, and
BEX integration. They do not install an `ExternalDeliveryPlanDeriver`.

### Compatibility migration

A host that intentionally accepts complete-current-Root scanning must add:

```java
ExternalDeliveryPlanDeriver deriver =
        CoordinationDeliveryPlanning.currentRootCompatibilityDeriver(
                contracts,
                rootRevision,
                eventOrderKey,
                completeActiveIntervals);
```

The host supplies the same revision, event order, and complete retained
active interval surface used by indexed delivery.

This mode derives current occurrences as compatibility evidence. It is not a
substitute for durable activation history.

### Indexed migration

A host that persists/indexes subscriptions uses:

```java
CoordinationSubscriptionProjector projector =
        CoordinationDeliveryPlanning.subscriptionProjector(
                processor, contracts);
CoordinationIndexedDeliveryPlanner planner =
        CoordinationDeliveryPlanning.indexed(processor, contracts);
```

No persistence implementation or index schema is part of this library.

## New subscription projection API

The additive public surface is:

```text
CoordinationSubscriptionProjector
CoordinationSubscriptionSnapshot
CoordinationSubscriptionOccurrence
CoordinationSubscriptionUpdate
```

Initial projection:

```java
CoordinationSubscriptionSnapshot snapshot =
        projector.projectCurrent(
                exactRoot,
                rootRevision,
                activationFrontier);
```

Incremental projection:

```java
CoordinationSubscriptionUpdate update =
        projector.projectUpdate(
                previousSnapshot,
                exactNewRoot,
                newRootRevision,
                transitionOrderKey,
                changedPaths);
```

The changed-path overload is the indexed path. The overload without
`changedPaths` intentionally marks the whole Root changed.

Snapshots are immutable, canonical, digest-bearing, and runtime/Root/revision
bound. `toMap()` emits application-neutral scalar/list/map data;
`rehydrate(...)` verifies schema, canonical ordering, dependencies, intervals,
and digest. Snapshots contain Channel headers and dependency evidence but not
executable bodies or provider transport state.

Updates expose added, retired, and unchanged occurrences plus the resulting
snapshot. Header, domain, type, or subscription changes are retire plus add.
Retained occurrences preserve their activation interval.

Host migration requirements:

1. allocate strictly increasing Root revisions and transition order keys;
2. persist the complete map value and digest atomically with the indexed
   occurrence keys;
3. supply exact changed paths for incremental projection;
4. replace, rather than mutate, persisted snapshot values;
5. treat any rehydration or runtime-identity failure as invalid evidence.

## New indexed planning and preparation API

The additive planning surface is:

```text
CoordinationIndexedDeliveryPlanner
CoordinationPreparedDelivery
CoordinationDeliveryDiagnostic
CoordinationSemanticDemandBoundary
CoordinationProcessingPreparation
```

Planner invocation:

```java
CoordinationPreparedDelivery prepared =
        planner.prepare(
                rootBlueId,
                eventBlueId,
                activeSnapshot,
                orderedCandidateOccurrenceKeys,
                exactProvider,
                rootRevision,
                eventOrderKey);
```

The candidate collection is exact and ordered. It is not a permissive
false-positive superset. The planner rejects duplicates, omissions, extras,
unknown/stale occurrences, ordering drift, revision/order drift, identity
drift, and invalid provider evidence before returning Language-verifiable
execution evidence.

The result exposes the canonical delivery plan and identity, exact Root/Event
references, snapshot identity, source occurrences, checkpoint evidence,
routed targets, logical-delivery keys, selected scope chains, required seeds,
prefetch suggestions, and the semantic-demand classifier. It exposes no
mutable runtime contract.

`CoordinationProcessingPreparation.combine(...)` combines an already prepared
delivery with already generated document and event split graphs. This
high-level handoff does not perform planning, splitting, storage,
authorization, scheduling, or PROCESS execution.

## Source versus target routing migration

Operation Request routing now has an explicit two-role diagnostic model:

```text
source external Channel
  acceptance, attribution, payload, freshness, activation interval,
  checkpoint domain and checkpoint subject

target same-scope Channel
  immutable Handler-dispatch header selected by Operation Request.channel,
  not an external source and not checkpointed as one
```

Hosts must index source occurrences. They must not create a second external
delivery occurrence for a selected target. Equivalent fresh source
occurrences may share one logical Handler delivery, but each source retains
its checkpoint and all checkpoints commit only after total success.

## Canonical splitter API

`CoordinationDocumentSplitter` remains the public splitter façade, with these
stable physical identities:

```text
blue.coordination/fragmentation/canonical-direct-node/1.0
blue.coordination/fragment-edge-occurrence/1.0
```

Additive public storage/diagnostic surfaces include:

```text
CoordinationDocumentSplitter.SplitGraph
CoordinationDocumentSplitter.FragmentRoot
CoordinationDocumentSplitter.EdgeOccurrence
CoordinationFragmentReconstructor
CoordinationFragmentAdmissionVerifier
```

Every exact node is stored in one canonical direct-node representation for
one `(profile, BlueId)`. Edge occurrence metadata records every physical
direct edge and distinguishes authored references from splitter-created
collapses, including repeated occurrences of one child at different pointers.

Host migration requirements:

1. key immutable fragments by profile and exact BlueId;
2. persist the complete fragment-root and edge-occurrence inventory;
3. on concurrent admission, re-read and verify the winning canonical bytes;
4. treat equal duplicate admission as idempotent;
5. treat different bytes for the same key as fatal evidence failure;
6. use `SplitGraph.reconstruct()` or
   `CoordinationFragmentReconstructor.reconstruct(...)` for diagnostic
   round-trip verification;
7. never treat reconstruction as part of PROCESS.

The splitter retains effective Process Embedded and registered executable-body
cuts, inheritance-aware source descriptors, and lazy reference-backed bodies.
It does not select a delivery or authorize evidence.

## Cyclic migration rule

An authored cyclic member edge remains an opaque exact reference. It is not a
subscription scope or a local child fragment. Complete cyclic-set proof is
required before member content can be served.

Hosts must not:

- expand `MASTER#index` during projection or splitting;
- process a pure cyclic member as a top-level value;
- configure Process Embedded through an opaque member edge;
- patch below that edge;
- fabricate a member fragment during reconstruction.

Whole-edge replacement remains valid. Inline object cycles and physical
fragment cycles fail.

## Fixed Repository evidence migration

`FixedRepositoryBoundSourceProvider` is the internal compatibility adapter for
the immutable generated catalog. It uses Language's bound-source verification
and binds exact source/runtime/provider identities. It preserves typed misses,
unavailability, and invalid evidence.

The release audit is:

```text
provider mode: BOUND_SOURCE_CONTENT
definitions: 1,107
cyclic sets: 10
cyclic members: 27
required: 1,107 verified and 0 failed
```

The audit output is
`build/reports/coordination-release/fixed-repository.json`. The final release
report keeps catalog audit and manifest compatibility visible separately. A
missing audit, one failed definition, or a manifest mismatch blocks release.

The durable baseline records the pre-edit dependency-evidence failures. It is
historical evidence, not a substitute for a post-edit same-run audit.

## Portable gas and host quotas

Portable Coordination gas remains PROCESS evidence:

- counter names and weights come from `coordination-gas-1.0.yaml`;
- charges use Language's runtime-work boundary;
- Coordination does not charge work already owned by Contracts or BEX;
- admitted trace order is deterministic and the rejected charge is absent.

Nonportable preparation work uses
`CoordinationHostQuotaSession` and
`coordination-host-quotas-1.0.yaml`. Explicit overloads bound supported
projection, candidate-validation/prefetch, splitter, and Mandate preparation.
These counters never enter `PROCESS.totalGas` and do not alter semantic
results.

Persistence, provider byte counts, network calls, and cache operations are
host telemetry and must not be converted into portable gas.

## Exact-content binary compatibility

The binary compatibility report compares class descriptors with
`2.0.0-rc.4`. Three descriptors whose dependency types still exist are
retained as deprecated, behavior-preserving overloads:

```text
BexProcessingMetrics.addBexMetrics(BexMetrics)
BexWorkflowContextFactory.create(StepExecutionContext, long)
BexWorkflowContextFactory.currentContractBinding(StepExecutionContext)
```

The BEX metrics overload reads the immutable compatibility view through its
baseline-stable counters and delegates to the same accumulator as the current
snapshot sink. The concrete workflow-context overloads delegate to the current
`BexWorkflowStepContext` boundary.

The following baseline descriptors are intentional pre-final removals. They
depend on Language APIs deleted by the modular Language release, or on the
former mutable `Blue` registration model, and cannot be retained truthfully
without reintroducing Language-owned compatibility classes or mutable runtime
state:

```text
CoordinationProcessors.registerWith(Blue)
CoordinationProcessors.registerWith(Blue, CoordinationProcessorOptions)
CoordinationRepositoryCompatibilityNodeProvider implements blue.language.NodeProvider
CoordinationRepositoryCompatibilityNodeProvider(blue.language.NodeProvider)
CoordinationRepositoryCompatibilityNodeProvider.isInstalled(blue.language.NodeProvider)
BexProcessingMetrics implements ProcessingMetricsSink
CoordinationMerging.install(Blue)
```

Hosts migrate to `CoordinationProcessors.contracts(BlueLanguage, ...)`, the
current `blue.language.provider.NodeProvider`, `ProcessingObserver`, and
`CoordinationMerging.wrap(MergingProcessor)`. Coordination does not define
classes in a `blue.language.*` package and does not reflect into immutable
Language runtimes.

The existing pre-final ledger also retains the explicit removals of
`RepositoryTypeAliasPreprocessor`, the obsolete whole-class form of the
Repository compatibility provider, and the three legacy
`TimelineProviderSupport` descriptors. Current behavior operates through
verified processor contexts, exact source delivery evidence, and fixed
timestamp semantics.

There is no deprecated production splitter compatibility constructor.
Production code contains no public application DTO, storage adapter, or
network client.

## Retained stable responsibilities

The intended public surface is limited to:

- runtime registration and processor options;
- explicit compatibility and indexed planning choices;
- subscription projection values;
- indexed delivery and processing preparation values;
- canonical split graph, reconstruction, and admission;
- portable gas and nonportable host-quota diagnostics;
- deterministic Mandate eligibility helpers;
- supported workflow extension interfaces.

Routing internals, matcher adapters, plan caches, BEX metric fan-out, fixture
harnesses, and release-report implementation remain internal.

### Surface-minimization decisions

`BexProcessingMetrics` and the established workflow extension types remain
public because they are present in the pinned `2.0.0-rc.4` binary baseline.
This is compatibility retention, not a reason to export more metrics
implementation. The Language metrics fan-out installed by
`CoordinationProcessors` is a private nested implementation, and new workflow
gas ledgers, matchers, caches, routing helpers, and fixture collectors are not
production API.

The Processing Event identity collector used by executable conformance lives
under `src/test`; it is absent from the release JAR. Its option wiring is
package-private. The observer contract remains public only because the
baseline-public workflow runner and BEX context factory occupy distinct Java
packages and must share the same optional diagnostic callback.

No production class remains under `blue.language.*`. The former cross-package
bridges were replaced by Coordination-owned adapters that call the public
`BlueContracts` projection, indexed-delivery, current-Root, runtime-access,
fragmentation-catalog, and platform-commit services. Hosts enter through
`CoordinationContractsHost`, `CoordinationDeliveryPlanning`,
`CoordinationSubscriptionProjector`, `CoordinationIndexedDeliveryPlanner`, and
`CoordinationDocumentSplitter`. Package-integrity tests fail if a production
class returns to a Language namespace or if internal routing, cache, fan-out,
or fixture types become public.

The canonical public API digest is generated at:

```text
build/reports/coordination-release/api.json
```

`binaryCompatibilityCheck` fails on an unlisted breaking descriptor change.
`verifyJava8Bytecode` independently rejects class-file versions above Java 8.

## Verification and report migration

The durable pre-edit baseline source and restored build copy are:

```text
gradle/coordination-release-baseline.json
build/reports/coordination-release/baseline.json
```

Use the hard release command:

```bash
./gradlew finalCoordinationVerification \
  --offline --no-daemon -PtestJfr=false
```

The current report paths are:

```text
build/reports/coordination-release/final.json
build/reports/coordination-release/final.md
```

Do not consume the retired
`build/reports/coordination-final/report.{json,md}` paths.

The final JSON is written for green and red candidates. It includes exact
sources/artifacts, dynamic runtime and manifest identities, tests and failed
cases, executable conformance, flagship, repeated-counter trace, fixed
Repository manifest and nested catalog audit, locality evidence, binary/API
compatibility, Java bytecode, reproducibility, `releaseEligible`, and
`blockingReasons`.

Publication remains fail-closed: the final task succeeds only when the
same-run report says `releaseEligible: true` and has no blocking reasons.
