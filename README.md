# Blue Coordination Java

Java processors for executable Blue Coordination repository contracts.

This library lets a Java application process Blue documents that declare
Coordination contracts in their `contracts` map: operations, sequential
workflows, update steps, compute steps, triggered events, composite channels,
embedded scopes, and checkpoints.

The processor is deterministic. Given the same initialized document and the
same ordered input events, it produces the same canonical output document,
triggered events, gas accounting, and output BlueId.

## Install

Gradle:

```groovy
repositories {
    mavenCentral()
}

dependencies {
    implementation "blue.coordination:blue-coordination-java:1.0.0"
}
```

The project targets Java 8-compatible bytecode, builds with JDK 25, runs tests
on Java 8, and depends on:

```groovy
api "blue.language:blue-language-java:3.1.0-rc.9"
api "blue.repo:blue-repo-java:3.0.0-rc.8"
api "blue.bex:blue-bex-java:1.1.0-rc.2"
```

## Register Processors

Most applications should register the Coordination processor set on a
repository-configured `Blue` instance:

```java
import blue.coordination.processor.CoordinationProcessors;
import blue.language.Blue;
import blue.repo.BlueRepository;

BlueRepository repository = BlueRepository.latest();
Blue blue = repository.configure(new Blue());

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

After processing, `/counter` is `5`, the workflow emits a chat message, and the
channel checkpoint records the delivered timeline entry so duplicates do not
run twice.

The request's required `channel` is its effective same-scope handler channel.
The Timeline Channel that accepts the entry still owns source eligibility and
checkpointing; routing preserves the full Timeline Entry and does not evaluate
the target channel as another external source. This V2 repository behavior is
outside the current Blue Contracts 1.0 conformance surface.

## Processing Model

Input:

1. one Blue document;
2. a delivered event, usually a timeline entry or a lifecycle/triggered event.

Output:

1. one canonical Blue document;
2. zero or more triggered events;
3. total gas usage;
4. processing metadata, including the output BlueId.

Processors operate on canonical snapshots instead of process-local mutable
state. You can serialize a processed document, load it again, and continue
processing from the same resolved state.

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

## Build And Test

Gradle runs on JDK 25 and uses a Java 8 toolchain for tests. If Java 8 is not
installed locally, Gradle can provision it through the configured Foojay
toolchain resolver.

Run tests:

```bash
./gradlew test
```

Build jars:

```bash
./gradlew build
```

Publish locally:

```bash
./gradlew publishToMavenLocal
```

## Test Coverage

Current test areas:

- processor registration;
- must-understand failures;
- test timeline provider behavior;
- composite timeline routing;
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
