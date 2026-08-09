# Blue Coordination Java

Blue Coordination is a deterministic Java 17 runtime for autonomous Blue
documents. It keeps ordinary values whole, cuts only effective `Process
Embedded` document boundaries, journals each exact Timeline Entry once, and
executes one frozen Contracts call per selected autonomous root.

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
    engine.appendAndDispatch(
            alice, Operation.yaml("increment", "aliceChannel", "amount: 3"));
    engine.appendAndDispatch(
            bob, Operation.yaml("decrement", "bobChannel", "amount: 1"));

    long value = ((java.math.BigInteger) engine.document(counter)
            .valueAt("/counter").copyNode().getValue()).longValueExact();
    assert value == 2L;
}
```

`Operation.exact(...)` and `CoordinationEngine.referenceRequest(...)` expose the
optimized whole-object request path without YAML reserialization.

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
autonomous-document semantics, catch-up rules, performance interpretation, and
limitations under `docs/`.

## Release-candidate status

The source and local semantic gates target `3.0.0-rc.1`. Publication is
fail-closed until Repository `3.0.0-rc.19` and BEX `1.1.0-rc.3` are available as
published Maven artifacts. See the [RC readiness note](docs/releases/3.0.0-rc.1.md)
and [release procedure](docs/development/releasing.md).

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
