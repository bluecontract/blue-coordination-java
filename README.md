# Blue Coordination Java

Blue Coordination is a deterministic Java 17 runtime for managed Blue
documents. It keeps ordinary values whole, cuts only effective `Process
Embedded` document boundaries, stores each exact Timeline Entry once, and lets
the environment select canonical processing order across the resulting
document graph.

## Install

```groovy
dependencies {
    implementation 'blue.coordination:blue-coordination-java:3.0.0-rc.1'
}
```

The artifact is compiled with `--release 17`. Version 3 is a breaking API reset;
the removed 2.x planning, fragmentation, session-store, and fast-path APIs are
not shimmed.

## Counter quickstart

```java
import blue.coordination.api.CoordinationEngine;
import blue.coordination.api.DocumentId;
import blue.coordination.api.Operation;

try (CoordinationEngine engine = CoordinationEngine.inMemory()) {
    var alice = engine.registerTimeline("counter/alice", "alice");
    var bob = engine.registerTimeline("counter/bob", "bob");
    var counter = DocumentId.of("counter");

    engine.startDocument(counter, counterYaml);
    engine.append(
            alice, Operation.yaml("increment", "aliceChannel", "amount: 3"));
    engine.append(
            bob, Operation.yaml("decrement", "bobChannel", "amount: 1"));

    var receipt = engine.drain();
    assert receipt.quiescent();

    long value = ((java.math.BigInteger) engine.document(counter)
            .valueAt("/counter").copyNode().getValue()).longValueExact();
    assert value == 2L;
}
```

## Contracts 1.0 opt-in

Contracts hosts bind the exact final specification artifacts and public Root
lineages explicitly:

```java
import blue.coordination.api.Contracts10Configuration;
import blue.coordination.api.CoordinationEngine;
import blue.coordination.api.DocumentId;

var configuration = new Contracts10Configuration(
        finalBlueLanguageSpecificationSha256,
        finalContractsSpecificationSha256,
        java.util.Set.of(DocumentId.of("public-root")));

try (CoordinationEngine engine =
        CoordinationEngine.inMemoryContracts10(configuration)) {
    // Register the public Root and embedded source Timelines.
}
```

Both identity variables must contain lowercase `sha256:` identities of the
actual final artifacts; the engine supplies no digest placeholder. This path
uses independent per-document Contracts closure execution, connected atomic
publication, and Root-lane feeder progress. `CoordinationEngine.inMemory()`
remains the earlier acyclic Process Embedded compatibility profile.

Contracts-mode `startDocument(...)` intentionally remains fail-closed because
a singleton start cannot authenticate a multi-member or cyclic closure. The
explicit `admitContractsClosure(input, policy, verifiedFrontier)` boundary
executes the caller-supplied typed `ADMIT_CLOSURE` input and atomically installs
every member when all lineages are new. Its receipt retains the exact Contracts
attempt and durable publication identity. `NeedsResources` and rejected
attempts mutate no Coordination state, while an exact retry reconciles the
durable receipt without executing Contracts again. Mixed existing/new closure
admission remains fail-closed until complete existing-head fences can be
proved; the engine never falls back to the legacy child/parent admission path.

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
./gradlew stageRelease -PblueDependencyMode=published-artifact
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

The published-artifact lane remains explicit and isolated. Resolution and
source-API compatibility are separate claims:

```bash
./gradlew verifyPublishedDependencyIsolation dependencyPreflight \
  -PblueDependencyMode=published-artifact
./gradlew verifyPublishedArtifactDependencies \
  -PblueDependencyMode=published-artifact
```

The first command proves that external coordinates resolve without sibling
substitution. The second also compiles this source tree and therefore remains
red until compatible Contracts 1.0 and BEX exact-capability artifacts are
published. Until then, the local composite is the supported implementation path
for the Contracts-enabled source tree. `verifyExtractedSourceArchive` can still
prove that the source ZIP configures in isolated published mode; its receipt
marks focused tests `NOT_EXECUTED` and does not claim artifact compatibility.

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
- [Metrics](docs/reference/metrics.md)
- [Failure and retry model](docs/operations/failure-model.md)
- [Contributing](CONTRIBUTING.md)
- [Security policy](SECURITY.md)
- [Changelog](CHANGELOG.md)
