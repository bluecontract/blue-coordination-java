# Blue Coordination Java

Blue Coordination is a deterministic Java 17 runtime for managed Blue
documents. It keeps ordinary values whole, cuts only effective `Process
Embedded` document boundaries, stores each exact Timeline Entry once, and lets
the environment select canonical processing order across the resulting
document graph.

## Install

```groovy
repositories {
    maven { url = uri('/absolute/path/to/blue-sdk-staged-repository') }
}

dependencies {
    implementation 'blue.coordination:blue-coordination-java:3.0.0-rc.3'
}
```

`3.0.0-rc.3` is currently a local-only SDK freeze candidate. It is staged into
an explicit file repository and is not published to Maven Central or Maven
Local. The artifact is compiled with `--release 17`. Version 3 is a breaking
API reset; the removed 2.x planning, fragmentation, session-store, and
fast-path APIs are not shimmed.

## Counter quickstart

```java
import blue.coordination.sdk.BlueCoordination;
import blue.coordination.sdk.ManagedClosure;
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
terminal failure leaves no partial document or topology mutation. Imported
state (`ManagedDocumentDraft.atEpoch(...)`) and historical occurrence
activation remain unsupported and fail closed.

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

```bash
./gradlew clean test
./gradlew releaseCheck
./gradlew sdkFreezePrepublicationCheck \
  -PblueDependencyMode=staged-artifact \
  -PblueStagingRepository=/absolute/path/to/blue-sdk-staged-repository
./gradlew stageSdkFreezeCandidate \
  -PblueDependencyMode=staged-artifact \
  -PblueStagingRepository=/absolute/path/to/blue-sdk-staged-repository
./gradlew verifySdkStagedDependencyGraph \
  verifySdkStagedCandidateRepository \
  verifyExtractedSdkConsumer sdkFreezeArtifactCheck \
  -PblueDependencyMode=staged-artifact \
  -PblueStagingRepository=/absolute/path/to/blue-sdk-staged-repository
```

The normal implementation build uses local composite substitution so the
complete Language runtime and Contracts module graph resolves from
`../blue-language-java` by default, together with the adjacent BEX and
Repository checkouts. Override the Language checkout with
`-PblueLanguageCompositePath=/absolute/path/to/blue-language-java`. The
canonical specification and fixture inputs resolve separately from
`../blue-spec/latest`; override that clean checkout with
`-PblueSpecRoot=/absolute/path/to/blue-spec/latest`. Source-archive smoke tests
forward the same path into the extracted build.

The SDK freeze lane stages the coordinated prerequisites in exact order—
Language, then BEX and Repository against that Language, then Coordination—
into one explicit file repository. `staged-artifact` disables sibling
composite substitution and Maven Local, consumes real POM and Gradle module
metadata, and verifies the exact candidate graph. These tasks do not upload,
publish remotely, push commits, or create tags. See the
[release procedure](docs/development/releasing.md) for the complete commands.

The historical published-artifact lane remains explicit and isolated.
Resolution and source-API compatibility are separate claims:

```bash
./gradlew verifyPublishedDependencyIsolation dependencyPreflight \
  -PblueDependencyMode=published-artifact
./gradlew verifyPublishedArtifactDependencies \
  -PblueDependencyMode=published-artifact
```

Those commands describe the older remote-coordinate lane and are not part of
the local-only rc.3 freeze. Do not infer remote availability from the SDK
staged repository.

`releaseCheck` owns the library's complete verification surface: unit tests,
compact-engine integration tests, tests compiled against the built JAR, and
realistic convergence scenarios. It does not read or execute `../blue-basic`.
That sibling is retained only as a historical performance/metrics laboratory.

Start with [START-HERE.md](START-HERE.md), then see the compact architecture,
managed `Process Embedded` semantics, catch-up rules, performance
interpretation, and limitations under `docs/`.

## Historical release-candidate evidence

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

- [Build and test](docs/development/build-and-test.md)
- [Test strategy](docs/development/test-strategy.md)
- [Initialization causality](docs/semantics/initialization-causality.md)
- [Shared NBA Game lifecycle](docs/examples/nba-shared-game-lifecycle.md)
- [Five-occurrence Playground API example](docs/examples/playground-five-occurrence.md)
- [Canonical RC evidence report](docs/releases/3.0.0-rc.1-test-report.md)
- [Public API](docs/reference/public-api.md)
- [SDK migration and ownership ledger](docs/reference/sdk-migration-and-ownership.md)
- [Metrics](docs/reference/metrics.md)
- [Failure and retry model](docs/operations/failure-model.md)
- [Contributing](CONTRIBUTING.md)
- [Security policy](SECURITY.md)
- [Changelog](CHANGELOG.md)
