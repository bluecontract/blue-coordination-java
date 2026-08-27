# Blue Coordination documentation

The normal application entry point is `blue.coordination.sdk.BlueCoordination`.
The documentation is organized by task so application guidance, API reference,
semantic explanation, contributor internals, and historical release evidence do
not compete as different starting points.

Maven Central `3.0.0-rc.4` remains the latest published bounded-pilot artifact.
The repository version `3.0.0-rc.6` is an unpublished, staged, non-production
source profile; sections explicitly marked rc.6 describe source behavior, not
the rc.4 JAR or an authorized release.

## Start here

1. Read the [SDK developer guide](guides/developer-guide.md). It covers
   installation, the mental model, ordinary and cyclic admission, complete
   provider entries, targeted operations, several Timelines, operation-created
   managed documents, ordering, diagnostics, history, atomicity, testing, and
   production boundaries.
2. Keep the published rc.4 [public API reference](reference/public-api.md)
   nearby while writing shared code; use the retained-epoch page below for
   rc.6-only additions.
3. Read [Managed Process Embedded documents](semantics/process-embedded-documents.md)
   before designing shared lineages, repeated occurrences, or cycles.
4. Check published rc.4 [Known limitations](limitations.md) and the rc.6
   retained-epoch non-goals before selecting an operational deployment profile.

For a five-minute path, use the repository [Start here](../START-HERE.md) and
[README quickstart](../README.md).

## Application guides and examples

| Document | Use it for |
| --- | --- |
| [SDK developer guide](guides/developer-guide.md) | Canonical end-to-end application journey and decision guide. |
| [Counter](examples/counter.md) | Minimal targeted operations and READY snapshot. |
| [Five managed occurrences](examples/playground-five-occurrence.md) | Several occurrences sharing fewer new managed lineages. |
| [Shared NBA Game lifecycle](examples/nba-shared-game-lifecycle.md) | Stable lineage reused by several containing documents. |
| [NBA historical catch-up](examples/nba-catch-up.md) | Retained low-level temporal replay and parent-synchronization evidence. |
| [Large host and PayNote](examples/large-host-paynote.md) | Retained low-level whole-object/performance evidence. |

Executable public-SDK examples live in:

- [`SdkAcceptanceTest`](../src/test/java/blue/coordination/sdk/SdkAcceptanceTest.java)
  for ordinary admission, exact targeting, broadcast, cycles, detach/re-add,
  and submit/drain parity;
- [`SdkManagedDraftAcceptanceTest`](../src/test/java/blue/coordination/sdk/SdkManagedDraftAcceptanceTest.java)
  for new managed lineages, repeated occurrences, validation, and rollback;
- [`SdkRetainedManagedEpochCatchUpTest`](../src/test/java/blue/coordination/sdk/SdkRetainedManagedEpochCatchUpTest.java)
  for rc.6 authored-initial, retained, current, and duplicate-occurrence
  catch-up behavior;
- [`SdkManagedEpochSelectorAcceptanceTest`](../src/test/java/blue/coordination/sdk/SdkManagedEpochSelectorAcceptanceTest.java)
  for rc.6 explicit selection of a repeated historical BlueId;
- [`SdkDeveloperGuideTest`](../src/consumerTest/java/blue/coordination/consumer/SdkDeveloperGuideTest.java)
  for both canonical application use cases compiled against the production JAR;
- [`SdkBuiltJarConsumerTest`](../src/consumerTest/java/blue/coordination/consumer/SdkBuiltJarConsumerTest.java)
  for the minimal built-JAR Counter smoke.

## Reference

| Document | Scope |
| --- | --- |
| [Public API](reference/public-api.md) | Published rc.4 and shared SDK owners, catalogs, calls, immutable results, snapshots, and advanced boundary. |
| [SDK migration and ownership](reference/sdk-migration-and-ownership.md) | Package stability, low-level-to-SDK migration, and semantic ownership. |
| [Contracts authored admission](reference/contracts-authored-admission.md) | High-level authored closure compilation versus the expert low-level admission seam. |
| [Metrics](reference/metrics.md) | Advanced raw-engine structural and processing counters. |
| Generated Javadocs | Complete signatures for the published artifact. |

## Semantic explanations

| Document | Scope |
| --- | --- |
| [Process Embedded documents](semantics/process-embedded-documents.md) | Managed boundaries, stable occurrence lineage, source surfaces, cycles, removal, and atomic publication. |
| [Identity and revisions](semantics/identity-and-revisions.md) | `DocumentId`, BlueId, epochs, revisions, and occurrence generations. |
| [Retained managed-epoch catch-up](semantics/retained-managed-epoch-catch-up.md) | Rc.5 exact matching, complete receipts/events, occurrence plans, barriers, ordering, readiness, retry, and audit. |
| [MyOS retained managed-epoch integration guide](../MYOS_RETAINED_MANAGED_EPOCH_INTEGRATION_GUIDE.md) | Read-only downstream source map, additive persistence, exact-content wake-up, restart transactions, REST/UI projections, and immutable-stage adoption. |
| [Historical catch-up](semantics/historical-catch-up.md) | Published rc.4 Timeline-import boundary versus the earlier low-level temporal coordinator. |
| [Initialization causality](semantics/initialization-causality.md) | Why initialization is not a fabricated external entry. |
| [Failure and retry model](operations/failure-model.md) | Commit boundaries, receipts, stale work, retry, and process-crash non-claims. |

## Architecture and contribution

| Document | Audience |
| --- | --- |
| [Compact engine](architecture/compact-engine.md) | Host integrators and maintainers needing the runtime data flow. |
| [Internal design guide](development/internals.md) | Repository contributors changing SDK or engine internals. |
| [Build and test](development/build-and-test.md) | Contributors running verification. |
| [Test strategy](development/test-strategy.md) | Contributors choosing the correct suite and evidence level. |
| [Release process](development/releasing.md) | Maintainers preparing and publishing a candidate. |
| [Migration from 2.x](migration-from-2.x.md) | Applications replacing removed 2.x surfaces with the SDK. |

## Release status and historical evidence

The current published application claim remains the bounded external-pilot
[`3.0.0-rc.4` decision](releases/3.0.0-rc.4.md). It is not a stable or
production-readiness claim. The rc.6 source profile has no publication or
production authority, and its build/test gates must be evaluated separately;
documentation of a required gate is not a claim that it passed.

Documents named `3.0.0-rc.1`, Round 11, Round 12, or Round 13 are immutable or
retained historical release evidence. They explain earlier campaigns and must
not be used as the current developer starting point, current artifact hashes,
or authorization for a later release.

## Documentation ownership

Use one source for each kind of statement:

- the developer guide owns application workflow and decision guidance;
- the public API reference owns facade behavior and result vocabulary;
- semantic pages own invariants independent of a particular code walkthrough;
- limitations own unsupported behavior and deployment non-claims;
- development pages own repository contribution and release procedure; and
- release pages own artifact-bound decisions and retained evidence.

When behavior changes, update the owning page, the developer guide if the
application journey changes, and an executable test. Avoid adding a second
quickstart that silently selects `CoordinationEngine.inMemory()` or another
runtime profile.
