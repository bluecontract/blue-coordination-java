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
./gradlew clean test -PblueDependencyMode=local-composite
./gradlew releaseCheck -PblueDependencyMode=local-composite
./gradlew stageRelease -PblueDependencyMode=local-composite
```

`releaseCheck` owns the library's complete verification surface: unit tests,
compact-engine integration tests, tests compiled against the built JAR, and
realistic convergence scenarios. It does not read or execute `../blue-basic`.
That sibling is retained only as a historical performance/metrics laboratory.

Start with [START-HERE.md](START-HERE.md), then see the compact architecture,
managed `Process Embedded` semantics, catch-up rules, performance
interpretation, and limitations under `docs/`.

## Release-candidate status

The source targets `3.0.0-rc.1` with the Round 10.1 Process Embedded temporal
profile. Release readiness is fail-closed until the same-source temporal,
restart/store, scenario, locality, performance, consumer, and artifact gates in
the [RC test report](docs/releases/3.0.0-rc.1-test-report.md) have verified
results. Publication also waits for Repository `3.0.0-rc.19` and BEX
`1.1.0-rc.3` to be available as published Maven artifacts. See the
[RC readiness note](docs/releases/3.0.0-rc.1.md) and
[release procedure](docs/development/releasing.md).

Developer references:

- [Build and test](docs/development/build-and-test.md)
- [Test strategy](docs/development/test-strategy.md)
- [RC test report](docs/releases/3.0.0-rc.1-test-report.md)
- [Public API](docs/reference/public-api.md)
- [Metrics](docs/reference/metrics.md)
- [Failure and retry model](docs/operations/failure-model.md)
- [Contributing](CONTRIBUTING.md)
- [Security policy](SECURITY.md)
- [Changelog](CHANGELOG.md)
