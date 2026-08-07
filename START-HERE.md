# Start here

This repository is the Coordination layer of a local four-repository stack.
It owns Timeline and Channel behavior, workflows, BEX hosting, subscription
projection, indexed planning, and physical fragmentation. It does not own
generic contract processing; that remains in `../blue-language-java`.

## Repository map

```text
src/main/java/blue/coordination/processor/
  CoordinationProcessors.java       immutable registration facade
  CoordinationDeliveryPlanning.java subscription and delivery facades
  CoordinationDocumentSplitter.java physical fragment graph
  CoordinationFragmentReconstructor.java exact reconstruction
  CoordinationSubscription*.java    persistent projection values
  workflow/                          sequential workflow execution
  bex/                               modular BEX host boundary

src/main/java/blue/coordination/engine/
  CoordinationProcessingEngine.java storage-neutral session facade
  api/                               immutable plans and transition values
  spi/                               fragment, session, bundle, and memo stores
  memory/                            in-memory reference adapters
  internal/                          request-local and transition planners

src/test/java/blue/coordination/processor/
  CoordinationComplexEmbeddedDeterminismFlagshipTest.java
  CoordinationDocumentSplitter*Test.java
  CoordinationSubscription*Test.java
  LatestLanguageArchitectureTest.java

docs/architecture/                   semantic and host boundaries
docs/engine/                         engine and storage-host integration
docs/guides/                         extension and migration guides
docs/examples/                       executable scenario documentation
tools/                               deterministic evidence generators
```

## First successful path

Begin by proving that the exact sibling inputs are the ones the source was
compiled against:

```bash
./gradlew --offline --no-daemon verifyLatestBlueSiblingInputs -PtestJfr=false
```

Then run one focused splitter test. Its fixture builds an authored Root,
obtains Language's effective fragmentation catalog, cuts exact embedded roots
and workflow bodies, and verifies canonical reconstruction:

```bash
./gradlew --offline --no-daemon test \
  --tests 'blue.coordination.processor.CoordinationDocumentSplitterTest' \
  -PtestJfr=false
```

Run the flagship only after that focused path is green:

```bash
./gradlew --offline --no-daemon test \
  --tests 'blue.coordination.processor.CoordinationComplexEmbeddedDeterminismFlagshipTest' \
  -PtestJfr=false
```

Those tests are the executable source for the examples; the documentation
does not carry an independent implementation.

## Runtime ownership

Create and close services in this order:

```text
exact NodeProvider
  -> BlueLanguage
  -> Coordination-configured ContractProcessorRegistry
  -> BlueContracts
  -> process or prepare exact evidence
  -> close BlueContracts
  -> close BlueLanguage
```

Hosted BEX borrows the same `BlueLanguage`. A subscription store, revision
allocator, checkpoint store, and outbox remain application-owned.

## Read next

1. [Runtime registration](docs/architecture/runtime-registration.md)
2. [One-Root processing](docs/architecture/one-root-processing.md)
3. [Embedded collections](docs/architecture/embedded-collections.md)
4. [Fragmentation and reconstruction](docs/architecture/fragmentation-and-reconstruction.md)
5. [Nested agreement example](docs/examples/nested-agreement-lesson-cancellation.md)
6. [Temporary quality exceptions](docs/architecture/quality-exceptions.md)
7. [Processing engine](docs/engine/start-here.md)

The engine guide covers the public per-invocation PROCESS boundary, successful
commit path, request-local locality evidence, and storage contracts. Do not
infer a green engine or a public-RC claim from the presence of the facade: use
the same-run engine report and strict 32-run flagship gate. Immutable
Repository blockers remain a separate release lane.

The former lower-layer gap is now a
[resolved public API boundary](docs/architecture/latest-language-public-api-gap.md).
Use the listed `BlueContracts` services directly. Their absence is no longer
an accepted stop condition, and Coordination code must not move into
`blue.language.*` to reach package-private state.
