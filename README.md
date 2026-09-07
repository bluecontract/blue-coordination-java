# Blue Coordination Java

Blue Coordination is a deterministic Java 17 runtime for managed Blue
documents. It keeps ordinary values whole, cuts only effective `Process
Embedded` document boundaries, stores each exact Timeline Entry once, and lets
the environment select canonical processing order across the resulting
document graph.

## Install

```groovy
repositories {
    mavenCentral()
}

dependencies {
    implementation 'blue.coordination:blue-coordination-java:3.0.0-rc.6'
}
```

`3.0.0-rc.6` is the bounded external-pilot candidate. Its required published
dependency tuple is Language `3.1.0-rc.24`, BEX `1.1.0-rc.5`, and Repository
`3.0.0-rc.22` from Maven Central. Coordination is compiled with `--release 17`.
It is not a stable or production
release. Version 3 is a breaking API reset; the removed 2.x planning,
fragmentation, session-store, and fast-path APIs are not shimmed.

Rc.6 adds authenticated historical representation positions to retained
managed-epoch catch-up. It retains proof-aware exact-node resolution,
reference-transparent SDK execution, authoritative fair-lane audit, a
non-mutating host journal availability hint, and targeted one-selection
processing for host-preleased durable execution. Unsupported nested authored
lineage creation during retained catch-up returns a typed unpublished attempt
and atomically blocks only its exact plan/barrier without partial publication.

For application development, follow the
[complete SDK developer guide](docs/guides/developer-guide.md). It covers both
processing an existing document/closure with a complete Timeline Entry and
evolving an initially known closure through several Timelines, including
cycles and operation-created managed documents. The
[documentation index](docs/README.md) separates application guides, API
reference, semantics, internals, and historical release evidence.

## Counter quickstart

```java
import blue.coordination.sdk.BlueCoordination;
import blue.coordination.sdk.ManagedDocument;

try (BlueCoordination blue = BlueCoordination.inMemory()) {
    var alice = blue.timelines().local("alice");
    var bob = blue.timelines().local("bob");
    var counter = blue.documents().admit(
            ManagedDocument.yaml("counter", counterYaml)
                    .publicRoot()
                    .fromNow());

    var plusThree = blue.operations().on(counter)
            .from(alice)
            .call("increment")
            .through("aliceChannel")
            .requestYaml("amount: 3")
            .execute();
    var minusOne = blue.operations().on(counter)
            .from(bob)
            .call("decrement")
            .through("bobChannel")
            .requestYaml("amount: 1")
            .execute();

    assert plusThree.applied();
    assert minusOne.applied();
    assert counter.snapshot().longAt("/counter") == 2L;
}
```

## Contracts 1.0 is the SDK default

`BlueCoordination.inMemory()` always creates the Contracts 1.0 profile and
pins the exact release identities bundled in the Coordination JAR. Ordinary
applications do not pass specification hashes, construct closure proofs, or
predeclare public Root IDs. Public Roots are authorized when an authored
document or closure is admitted.

The SDK compiles a complete authored cyclic closure without introducing a
second graph:

```java
var closure = blue.documents().admit(
        ManagedClosure.builder()
                .document("a", yamlA)
                .document("b", yamlB)
                .bindOccurrence("a", "/b", "b")
                .bindOccurrence("b", "/a", "a")
                .publicRoot("a")
                .fromNow()
                .build());
```

Each occurrence binding is stable managed-lineage evidence for an effective
`Process Embedded` path. The compiler verifies the authored catalog and exact
target value, then delegates finalization and complete-proof verification to
the pinned Language/Contracts implementation.

`submit()` appends only. `execute()` appends and canonically drains through the
submitted entry, including earlier eligible work. Explicit broadcast entries
use `blue.events()`; a valid broadcast accepted by no Channel returns
`NO_MATCH`. A missing exact operation target returns `REJECTED` with a stable
diagnostic instead of becoming a broadcast.

## Advanced and legacy compatibility

The older `blue.coordination.api.CoordinationEngine` surface remains an
advanced host-integration and migration boundary. Its
`inMemoryContracts10(...)` factory requires explicit release identities and its
raw closure admission accepts low-level proof values. Its `inMemory()` factory
retains the earlier acyclic compatibility profile; it is not the default SDK
semantics. New applications should not start there.

An SDK owner exposes the same low-level engine deliberately through
`blue.advanced().rawEngine()`. Custom exact release identities are likewise an
advanced option:

```java
try (BlueCoordination blue = BlueCoordination.builder()
        .release(languageSpecificationIdentity, contractsSpecificationIdentity)
        .build()) {
    var raw = blue.advanced().rawEngine();
}
```

The SDK admits new managed lineages produced by an operation when the caller
supplies the exact initial value with `request.managed(...)`, binds every
effective occurrence with `expectOccurrence(...)`, and selects `fromNow`
activation. One draft can bind several occurrences without duplicating the
lineage. The runtime verifies the request fields, occurrence paths, exact
values, and complete affected closure before one atomic publication; a
terminal failure leaves no partial document or topology mutation. In rc.6,
imported draft state (`ManagedDocumentDraft.atEpoch(...)`) remains unsupported
and fails closed. A distinct retained path provides automatic exact matching
for existing current, authored-initial,
epoch-zero, and retained states. Historical matches apply immutable source
receipts through occurrence-specific plans and barriers; repeated historical
BlueIds require `OperationCall.selectManagedEpoch(...)`. See
[Retained managed-epoch catch-up](docs/semantics/retained-managed-epoch-catch-up.md).

Operational tooling can inspect a retained occurrence without exposing graph
internals through
`blue.advanced().auditManagedOccurrence(sourceId, occurrencePath)`. The
returned `ManagedOccurrenceAudit` reports the target `DocumentId`, activation
generation, and active/inactive state.

`Operation.exact(...)` and `CoordinationEngine.referenceRequest(...)` expose the
optimized whole-object request path without YAML reserialization. For a
provider-supplied exact Timeline Entry, use `appendTimelineEntry(Node)`; append
never names document recipients and never invokes PROCESS. `drain()` derives
targets from the active Channel index and processes canonical work to
quiescence. Latency-sensitive hosts can call `drain(new DrainBudget(...))` and
resume a paused receipt at deterministic PROCESS boundaries; this bounds work,
not the duration of one non-preemptible frozen call. `document(id)` is READY-only
by design, while `auditDocument(id)` explicitly exposes intermediate committed
state to operational tooling.

## Build and verification

The rc.6 release requires verification through the Maven-Central-only
artifact lane:

```bash
./gradlew --no-daemon dependencyPreflight
./gradlew --no-daemon --no-build-cache clean releaseCheck \
  -PtestJavaVersion=17
./gradlew --no-daemon --no-build-cache verifyRcReadiness \
  -PtestJavaVersion=17
```

`releaseCheck` owns the complete verification surface: unit tests, compact-
engine integration tests, tests compiled against the built JAR, realistic
convergence scenarios, publication metadata, dependency isolation, source
archive extraction, and documentation. Every `@Test` follows one meaningful
lowercase `// given`, `// when`, `// then` sequence, enforced by
`verifyTestArchitecture`.

`dependencyPreflight` resolves the exact conflict-free Blue graph from Maven
Central. The build and published POM retain the Repository and BEX transitive
exclusions and directly own the complete Language rc.24 graph. Local
composites and Maven Local are rejected.

The public rc.6 release lane uses the published-artifact mode by default. An
invocation-owned immutable Contracts stage remains available only for
development-candidate handoffs; when using it, pin its absolute repository and
manifest identity explicitly:

```bash
./gradlew --no-daemon --no-build-cache clean releaseCheck \
  -PtestJavaVersion=17 \
  -PblueDependencyMode=immutable-staged-contracts \
  -PblueContractsVersion=3.1.0-rc.24 \
  -PblueContractsRepository=/absolute/path/to/invocation-owned/contracts-repository \
  -PblueContractsManifestSha256=sha256:<64-lowercase-hex>
```

The stage must be outside this source tree and immutable. Exclusive repository
routing prevents fallback for `blue.language`; Maven Local and composite
substitution remain forbidden. This is the required invocation shape, not a
substitute for the published-artifact release lane.

A coordinated unpublished candidate instead uses
`immutable-development-contracts` with exact commit-bound Language, BEX, and
Coordination versions plus both immutable repository manifest identities. The
complete `releaseCheck` replays those same pins inside the extracted source
archive; it never substitutes the published Language or BEX versions. This is
candidate-verification evidence only. `verifyRcReadiness`, `stageRelease`, and
JReleaser remain restricted to the published rc.6 lane.

Once those gates genuinely pass on a clean committed checkout, use the
[immutable Coordination handoff](docs/development/immutable-staged-coordination.md)
to export and consumer-test an invocation-owned development Maven stage. A staged
handoff is not a public release.

The rc.6 release workflow runs the same gates, stages signed artifacts,
publishes through JReleaser, and pushes its tag only after publication
succeeds. See the [release procedure](docs/development/releasing.md) and
[rc.6 release decision](docs/releases/3.0.0-rc.6.md).

`releaseCheck` does not read or execute `../blue-basic`. That sibling is
retained only as a historical performance/metrics laboratory.

Start with [START-HERE.md](START-HERE.md), continue with the
[SDK developer guide](docs/guides/developer-guide.md), and use the
[documentation index](docs/README.md) to find architecture, managed
`Process Embedded` semantics, catch-up rules, operational behavior, and
limitations.
## Historical release-candidate evidence

The current release authority is the
[3.0.0-rc.6 decision](docs/releases/3.0.0-rc.6.md). The documents below are
retained evidence for rc.1 and are not reused as current artifact hashes.

The retained 3.0.0-rc.1 report covers the earlier Round 10.1 Process Embedded
temporal profile, Round 11 readiness closure, and Round 12 initialization
lifecycle and dynamic-activation proofs. It does not cover the current
Contracts 1.0 implementation. Its release status was split
into temporal architecture, in-memory engine, provider, Mandate, latency, and
public-RC evidence. The generic Timeline Entry's missing universal literal
`documentId` is an optional profile capability; exact provider-backed Mandate
eligibility remains outside this in-memory profile. The exact 3.0.0-rc.1 release
policy permits workflow publication with
`PASS_WITH_KNOWN_PERFORMANCE_LIMITATION`: the retained Round 13 campaign failed
append and Coordination-host hard p95 gates, and no latency pass is claimed.
The exception requires explicit workflow opt-in, cannot apply to a stable
release, and preserves every Java, correctness, structural, consumer, artifact,
source-archive, published-dependency, metadata, checksum, and signature gate.
See the [canonical RC report](docs/releases/3.0.0-rc.1-test-report.md),
[RC readiness note](docs/releases/3.0.0-rc.1.md), and
[release procedure](docs/development/releasing.md).

Developer references:

- [Documentation index](docs/README.md)
- [SDK developer guide](docs/guides/developer-guide.md)
- [Build and test](docs/development/build-and-test.md)
- [Test strategy](docs/development/test-strategy.md)
- [Initialization causality](docs/semantics/initialization-causality.md)
- [Retained managed-epoch catch-up](docs/semantics/retained-managed-epoch-catch-up.md)
- [MyOS retained managed-epoch integration guide](MYOS_RETAINED_MANAGED_EPOCH_INTEGRATION_GUIDE.md)
- [Shared NBA Game lifecycle](docs/examples/nba-shared-game-lifecycle.md)
- [Five-occurrence Playground API example](docs/examples/playground-five-occurrence.md)
- [3.0.0-rc.6 release decision](docs/releases/3.0.0-rc.6.md)
- [3.0.0-rc.5 historical release decision](docs/releases/3.0.0-rc.5.md)
- [3.0.0-rc.4 historical release decision](docs/releases/3.0.0-rc.4.md)
- [Canonical RC evidence report](docs/releases/3.0.0-rc.1-test-report.md)
- [Public API](docs/reference/public-api.md)
- [SDK migration and ownership ledger](docs/reference/sdk-migration-and-ownership.md)
- [Metrics](docs/reference/metrics.md)
- [Failure and retry model](docs/operations/failure-model.md)
- [Contributing](CONTRIBUTING.md)
- [Security policy](SECURITY.md)
- [Changelog](CHANGELOG.md)
