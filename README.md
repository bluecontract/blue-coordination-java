# Blue Coordination Java

Java processors for executable Blue Coordination repository contracts.

This library lets a Java application process Blue documents that declare
Coordination contracts in their `contracts` map: operations, sequential
workflows, update steps, compute steps, triggered events, composite channels,
embedded scopes, and checkpoints.

The processor is deterministic. Given the same initialized document and the
same ordered input events, it produces the same canonical output document,
Root event sequence, gas accounting, status, and diagnostic.

## Install

Gradle:

```groovy
repositories {
    mavenCentral()
}

dependencies {
    implementation "blue.coordination:blue-coordination-java:2.0.0-rc.4"
}
```

The project targets Java 8-compatible bytecode, builds with JDK 25, runs tests
on Java 8, and depends on:

```groovy
api "blue.language:blue-language-java:3.1.0-rc.18"
api "blue.repo:blue-repo-java:3.0.0-rc.10"
api "blue.bex:blue-bex-java:1.1.0-rc.2"
```

## Contracts 1.0 Development Build

This worktree can compile against the sibling Language runtime:

```bash
./gradlew test -PuseLocalBlueLanguage=true
```

The property substitutes every direct and transitive
`blue.language:blue-language-java` dependency with `../blue-language-java` and
fails configuration if that sibling checkout is absent.

The selective-processing development baseline is the exact Language commit
`0a6a40d18578df784f674148d1e8b6a4319bfe49`
(`feat: support fragmented processing and logical delivery`). It adds verified
pure-reference Root/Event admission, event-scoped exact-reference
materialization, lazy executable-body demand, and generic logical-delivery
routing. Coordination is wired to those generic functions so Timeline source
eligibility and checkpoints can remain separate from the effective Operation
Request handler channel.

That local Language commit is not yet a working cross-channel PROCESS baseline.
During verified Phase-B classification it rebuilds an External Channel bundle
containing only the accepting source. Consequently the documented
`ExternalChannelFunctionContext.membersByEffectiveType(...)` call cannot see a
same-scope peer target that was visible during header evaluation. The focused
Coordination regression currently has 7 tests: 4 fallback/evidence cases pass
and the 3 valid peer-routing cases remain red at that Language boundary. The
tests deliberately retain the required target validation; they do not route an
unknown target optimistically.

There is a second generic API boundary for the full requested target rule:
the current function context exposes External Channel peers, while the
Coordination requirement accepts any effective same-scope Channel. Until
Language supplies a Phase-B view of the header-declared dependency surface and
a read-only same-scope Channel lookup, this worktree must not be described as a
complete Operation Request routing implementation.

The immutable request parser also recognizes a bare Operation Request, but the
production external functions currently accept Timeline Entries through
Timeline, Composite Timeline, and All Timelines source channels. A bare request
therefore has no production source-classification path yet; that case is
reachable only through custom kernel plumbing.

The migration is still not release-ready. The supplied handoff contains the
final Language and generic Contracts registries, but no final Coordination
registry. In particular, `blue-repo-java:3.0.0-rc.10` still has the preview
`Terminate Processing` shape without the required application `cause`.
The published dependency remains `blue-language-java:3.1.0-rc.18`; the commit
above is a local development input, not a released artifact identity.

Two other development limitations remain:

- ordinary Timeline Channels still use a conservative type-wide preselection
  key, so 1,025 otherwise valid channels can exceed the Contracts
  1,024-occurrence preselection bound;
- the current BEX dependency does not expose a manifest-bound named-counter
  stream, so Compute fails closed instead of submitting BEX's legacy aggregate
  `gasUsed` value.

Two historical processor-delay fixtures still contain preview `lastEvents`
checkpoint records. Their final checkpoint subjects are derivable, but their
domains are not: the required final Coordination type and source-contribution
identities were not supplied. The focused fixture test removes those stale
records before processing.

## Register Processors

Most applications should register the Coordination processor set on a
repository-configured `Blue` instance:

```java
import blue.coordination.processor.CoordinationProcessors;
import blue.language.Blue;
import blue.repo.BlueRepository;

BlueRepository repository = BlueRepository.latest();
Blue blue = new Blue()
        .nodeProvider(repository.nodeProvider())
        .typeClassResolver(repository.typeClassResolver());

CoordinationProcessors.registerWith(blue);
```

For direct `DocumentProcessor` construction:

```java
import blue.coordination.processor.CoordinationProcessors;
import blue.language.processor.DocumentProcessor;

DocumentProcessor processor =
        CoordinationProcessors.configure(DocumentProcessor.builder())
                .build();
```

`CoordinationProcessors` registers Timeline, Composite Timeline, and All
Timelines channel processors. Timeline providers remain responsible for feeding
authenticated, ordered Timeline Entries; the processors enforce the channel's
timeline and actor identity and strict timestamp-based checkpoint semantics.

## Counter Document

This is a complete executable Blue document. The contracts are the program:

```yaml
name: Counter
counter: 0
contracts:
  ownerChannel:
    type: Coordination/Timeline Channel
    timeline:
      type: Coordination/Timeline
      timelineId: counter-demo
    actor:
      type: MyOS/MyOS Principal Actor
      accountId: counter-demo

  increment:
    type: Coordination/Sequential Workflow Operation
    channel: ownerChannel
    request:
      type: Integer
    steps:
      - name: IncrementAndEmit
        type: Coordination/Compute
        do:
          - $let:
              name: nextCounter
              expr:
                $add:
                  - $document: /counter
                  - $binding:
                      name: event
                      path: /message/request
          - $appendChange:
              op: replace
              path: /counter
              val:
                $var: nextCounter
          - $appendEvent:
              type: Coordination/Chat Message
              message:
                $concat:
                  - Counter is now
                  - " "
                  - $text:
                      $var: nextCounter
          - $return:
              changeset:
                $changeset: true
              events:
                $events: true
```

An input event for that channel looks like this:

```yaml
type: Coordination/Timeline Entry
timeline:
  type: Coordination/Timeline
  timelineId: counter-demo
timestamp: 1
actor:
  type: MyOS/MyOS Principal Actor
  accountId: counter-demo
message:
  type: Coordination/Operation Request
  operation: increment
  channel: ownerChannel
  request: 5
```

With a Contracts 1.0-compatible BEX runtime adapter, processing changes
`/counter` to `5`, emits a chat message, and records the Timeline ordering
subject so duplicates do not run twice.

The request's required `channel` is its effective same-scope handler channel.
The Timeline Channel that accepts the entry still owns source eligibility and
checkpointing. Coordination computes Language's generic
`handlerChannelKey` and `logicalDeliveryKey` outputs without evaluating or
checkpointing the target as a second external source. The development-kernel
gate described above currently prevents a valid peer target from surviving
verified Phase-B classification.

## Routed Operation Requests

The intended routed Operation Request model has two channel roles:

- the **source channel** accepts the exact Timeline Entry, authenticates its
  timeline and actor, determines freshness, preserves the complete original
  attribution, and owns its checkpoint;
- `Operation Request.channel` identifies the **effective handler channel** in
  the same scope. Its operation handlers are discovered, but the target channel
  is not re-evaluated as another external occurrence.

Once the Language gate is corrected, fresh accepted sources that resolve to the
same target, operation, and exact payload form one logical delivery. The target
operation executes once and every participating source retains its own
checkpoint. A stale source never piggybacks on a fresh one. Failure, application
termination, or gas exhaustion commits none of the grouped source checkpoints.
Unknown targets, non-Channel targets, and malformed routing fields preserve
ordinary source-channel delivery; a known target with an unknown operation
runs no handler but may still advance the accepted source checkpoint.

Mandate and feeder eligibility stays outside PROCESS. A feeder observes the
Root and transitively declared embedded external channels, establishes
Timeline completeness and ordering, and derives eligible source occurrences.
It may apply Mandate/direct eligibility before constructing revision-bound
`VerifiedExecutionEvidence`. The processor revalidates that immutable evidence;
it does not query a Mandate database or replace the source Timeline attribution
with target-channel data.

## Processing Model

Input:

1. one Blue document;
2. a delivered event, usually a timeline entry or a lifecycle/triggered event.

Output:

1. one canonical Blue document;
2. zero or more ordered Root events;
3. total gas usage;
4. one completed processing status;
5. an optional structured diagnostic.

Processors operate on canonical snapshots instead of process-local mutable
state. You can serialize a processed document, load it again, and continue
processing from the same resolved state.

`VerifiedExecutionEvidence` is revision-bound environment evidence, not a third
semantic PROCESS argument. The semantic inputs remain exactly Root and Event.

## Selective Fragmentation

`CoordinationDocumentSplitter` prepares ordinary content-addressed Blue
fragments without executing contracts or deriving a different delivery plan.
`splitDocument` retains immutable dispatch headers and cuts only:

- exact embedded roots declared by a directly authored
  `Process Embedded.paths`;
- executable-body fields declared by the handler's exact registered runtime
  type, including the `steps` bodies of Sequential Workflow Operation, Chat
  Workflow Operation, and Sequential Workflow.

Each parent edge becomes a pure reference to the same exact child or body
BlueId, so the fragmented Root and fully inline Root have the same BlueId.
Application subtrees are not cut merely because they contain a `contracts`
property. Handler channel, operation, order, request/event patterns, and body
BlueId remain available without inspecting the body. `splitEvent` uses
Language's exact direct-node fragments for ordinary graphs and an
identity-equivalent shallow form when a Coordination event retains an external
cyclic-member type reference.

The resulting Root and Timeline Entry can both be passed to PROCESS as pure
references:

```java
CoordinationDocumentSplitter splitter = new CoordinationDocumentSplitter();
CoordinationDocumentSplitter.SplitGraph documentGraph =
        splitter.splitDocument(exactRoot);
CoordinationDocumentSplitter.SplitGraph eventGraph =
        splitter.splitEvent(exactTimelineEntry);

NodeProvider fragments = new SequentialNodeProvider(
        documentGraph.provider(),
        eventGraph.provider(),
        repository.nodeProvider());

CoordinationDocumentSplitter.PreparedProcessingInput input =
        splitter.prepareForProcessing(
                documentGraph.rootBlueId(),
                eventGraph.rootBlueId(),
                evidence,
                fragments);

Blue blue = new Blue()
        .nodeProvider(input.provider())
        .typeClassResolver(repository.typeClassResolver());
CoordinationProcessors.registerWith(blue);

DocumentProcessingResult result =
        blue.getDocumentProcessor().processDocument(
                input.document(), input.event(), input.evidence());
```

The example's `evidence` is supplied by the feeder boundary described above.
The splitter checks that it is bound to the exact Root and Event identities and
installs no new routing argument. Preparation does not fetch either fragment;
the returned verifying provider validates BlueId evidence lazily on PROCESS
demand.

The physical-locality tests prove that a Root-only selection demands no
embedded child merely because it is declared. For a selected deep leaf, they
prove that the prepared fragment allow-list can be restricted to the
Root-to-leaf scope chain and selected/causally allowed bodies. Those tests are
structural provider-demand proofs; they do not substitute for the still-missing
deep semantic processing matrix or ultra-complex causal fixture.

The current no-embedding semantic matrix is one JUnit case containing eight
actual `DocumentProcessor` invocations over inline, referenced, direct-fragment,
and cold/warm representations. It uses a deterministic static test Handler and
a fragment-aware adapter; it is not the required Alice-source/Bob-target
production Operation Request fixture, and it does not cover one-fragment or
batched providers.

The current splitter discovers authored contract entries and their exact direct
runtime types. Its public input does not yet provide an effective resolved
contract view, so a `Process Embedded` declaration or executable handler body
that exists only through type inheritance is not cut by this implementation.
That effective-contract case remains an explicit design/API gap.

Fragmented and inline representations must have identical status, resulting
Root value and BlueId, ordered Root events, gas, conformance trace, and source
checkpoints. Provider tests distinguish semantic demands from optional
allow-listed backend prefetch and fail immediately on a forbidden demand.
Cache warmth, batching, and fragment iteration order are physical concerns and
must not change semantics. An unavailable selected body remains unavailable
and retryable; an incomplete provider view must never turn unknown content into
a semantic no-match.

Only events explicitly emitted at Root appear in `ProcessResult.events`.
Descendant events may drive local and ancestor reactions, but are not
automatically published. The deterministic deep-fixture design and required
evidence are documented in
[`docs/fragmented-processing-ultra-complex-walkthrough.md`](docs/fragmented-processing-ultra-complex-walkthrough.md).

## Supported Contracts

This library provides executable behavior for:

- `Coordination/All Timelines Channel`;
- `Coordination/Composite Timeline Channel`;
- `Coordination/Chat Workflow Operation`;
- `Coordination/Sequential Workflow`;
- `Coordination/Sequential Workflow Operation`;
- `Coordination/Compute`;
- `Coordination/Update Document`;
- `Coordination/Trigger Event`.

It also registers `Coordination/Operation` as a non-executable declaration
type for operation-shaped contracts.

The underlying `blue-language-java` runtime provides base behavior used by
Coordination documents:

- `Document Update Channel`;
- `Embedded Node Channel`;
- `Process Embedded`;
- `Channel Event Checkpoint`;
- `Lifecycle Event Channel`;
- `Triggered Event Channel`;
- initialized and terminated markers;
- scope boundaries, patch application, snapshots, gas, and checkpointing.

## BEX In Workflows

`Coordination/Compute` is the BEX execution surface. A Compute step applies a
returned `changeset` directly and emits returned `events` directly, so dynamic
patches and events do not need follow-up Update Document or Trigger Event
steps.

Common workflow bindings:

- `$binding` for `event`, the current `document`, and named step results;
- `$document` for the current document view;
- `$currentContract` for the active workflow contract;
- `$appendChange` and `$changeset` for accumulated patch operations;
- `$appendEvent` and `$events` for accumulated emitted events.

`Coordination/Update Document` accepts literal patch lists only.
`Coordination/Trigger Event` accepts literal event payloads only.
Literal payloads are not interpreted as BEX; `$`-prefixed application keys
remain exact data.
Patch operations are exact lowercase `add`, `replace`, or `remove`; `val` is
required for add/replace and must be absent for remove. Compute termination and
`Coordination/Terminate Processing` use an application-defined non-empty
`cause` plus an optional Text `reason`.

## Build And Test

Gradle runs on JDK 25 and uses a Java 8 toolchain for tests. If Java 8 is not
installed locally, Gradle can provision it through the configured Foojay
toolchain resolver.

Run tests:

```bash
./gradlew test
```

Run focused routing characterization, direct-declaration splitter regressions,
physical-locality tests, the limited no-embedding PROCESS parity matrix, and
the deterministic partial report against the exact sibling Language checkout:

```bash
./gradlew selectiveCoordinationProcessingTest \
  -PuseLocalBlueLanguage=true
```

These tests do not require Compute/BEX. With Language commit
`0a6a40d18578df784f674148d1e8b6a4319bfe49`, the task is expected to remain red
only at the enabled valid-peer routing regressions described above: the latest
run executes 55 tests, with 52 passing and 3 failing. Do not present the task as
green. Until the final Coordination registry and compatible BEX counter stream
are available, failures in named Compute/Mandate runtime suites are reported
separately from fragmentation results.

The deterministic selective-processing report schema is
[`src/test/resources/coordination/selective-processing-report.schema.json`](src/test/resources/coordination/selective-processing-report.schema.json).
Generate the current truthful partial artifact with:

```bash
./gradlew test -PuseLocalBlueLanguage=true \
  --tests blue.coordination.processor.SelectiveProcessingReportArtifactTest
```

It writes `build/reports/coordination-selective-processing/report.json` without
timestamps or machine-specific paths. The artifact records its own exact test
count and splitter smoke identities. It also records separately observed
passing no-embedding/locality evidence, the enabled routing blocker, and the
still-unexecuted embedded semantic and ultra-complex sections. It does not turn
the report mechanism into execution evidence; each section names its evidence
scope and command.

Run the focused correctness and bounded-memory suites:

```bash
./gradlew workflowPlanDifferentialTest
./gradlew complexFixtureIntegrationTest
./gradlew memoryIntegrationTest
```

Each focused task uses one worker capped at 2 GiB. Passing `-PtestJfr`
runs that focused task on the current modern Gradle JVM and records under
`build/reports/jfr/`; the normal `test` task continues to run on Java 8.

Blue Language is pinned to the released
`blue.language:blue-language-java:3.1.0-rc.18` artifact from Maven Central.

Build jars:

```bash
./gradlew build
```

Publish locally:

```bash
./gradlew publishToMavenLocal
```

Stage the artifact without writing outside this repository:

```bash
./gradlew stageLocalMaven
```

The staged Maven repository is `build/staging-deploy`.

Run JMH and generate JSON, CSV, Markdown, and environment metadata:

```bash
./gradlew jmh
./gradlew jmh -PtestJfr
```

Reports are written to `build/reports/jmh`. Generic JMH gates are deliberately
reported as `NOT_CONFIGURED`; operation-level acceptance is exercised by the
focused integration and differential suites in this repository.

Create the reproducible source archive and SHA-256 sidecar:

```bash
./gradlew sourceArchive
```

Artifacts are written to `build/distributions`. The verified performance and
correctness evidence is recorded in
[`docs/performance/complex-operations-coordination.md`](docs/performance/complex-operations-coordination.md).

## Test Coverage

Current test areas:

- processor registration;
- must-understand failures;
- test timeline provider behavior;
- composite timeline routing;
- logical source-to-effective-channel Operation Request routing;
- exact Coordination document/event fragmentation API and invariants;
- structural fragment-provider locality and reconstruction;
- operation request matching;
- sequential workflow execution;
- compute and BEX execution;
- update document batch application;
- trigger-event execution;
- runtime channels;
- repository-style Counter documents;
- snapshot round-trip stress processing.

## Project Layout

```text
src/main/java/blue/coordination/processor
  CoordinationProcessors.java
  CoordinationProcessorOptions.java
  CoordinationBexIntrinsics.java
  AllTimelinesChannelProcessor.java
  CompositeTimelineChannelProcessor.java
  ChatWorkflowOperationProcessor.java
  OperationProcessor.java
  SequentialWorkflowProcessor.java
  SequentialWorkflowOperationProcessor.java
  TimelineProviderSupport.java
  bex/
  merge/
  workflow/
```

## References

- [Blue Language Specification](https://github.com/bluecontract/blue-spec)
- [blue-js open-source processor](https://github.com/bluecontract/blue-js)
