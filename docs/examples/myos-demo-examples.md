# Executable MyOS demo examples

This source set is the product-facing integration layer for Coordination. It
uses the real current Language, Contracts, BEX, Repository, Timeline Channel,
Mandate, fragmentation, indexed-delivery, and ProcessingEngine APIs, but it
does not duplicate their protocol conformance suites.

Run the focused examples and their same-run evidence gate with:

```bash
./gradlew --offline --no-daemon \
  coordinationExamplesVerification \
  -PtestJfr=false
```

## Authoring rule

Every Blue document and Timeline Entry is authored as readable YAML in a Java
text block:

```java
String document = """
        name: Counter
        counter: 0
        contracts:
          ownerChannel:
            type: Coordination/Timeline Channel
            timeline:
              type: MyOS/MyOS Timeline
              timelineId: examples/counter/alice
            actor:
              type: MyOS/Principal Actor
              accountId: alice
        """;
```

Do not construct authored Blue content with mutable `Node` builders. Runtime
`Node` values appear only at the parsing, identity, processing, and assertion
boundaries.

## Included stories

| Example | Business proof |
|---|---|
| Counter basics | One attributed operation changes one managed Root epoch. |
| Shared counter | One immutable Timeline Entry is processed independently by two Root sessions. |
| Embedded counter | A child operation changes the child and an ancestor observes the child event. |
| Dynamic activation | A newly attached child does not process the creating event or old history; its first later entry initializes and processes it. |
| Operation Mandate | A bounded agent call is allowed, an out-of-policy call is withheld, and termination revokes future authority. |
| Vet visit | Maya requests a PUPPS visit and PUPPS confirms it through shared participant Timelines. |
| PawStart Full Plan | Agreement and PayNote attachment, mandate-authorized scheduling, confirmation, normal completion, cancellation/refund, no-show, low-satisfaction adjustment, and mandate termination. |
| Wadowice hotel and dinner | A complete PayNote is attached, two authorizations are shared across Root sessions, Hotel and Restaurant Product conditions are attached, capture occurs only after both confirmations, and normal/refund/discount/late-cancel branches are exercised. |

The PawStart and Wadowice flows are deliberately business-shaped. Wadowice
uses one living cross-business order whose payment can be captured only after
both provider components confirm; it is not a coupon simulation. The suite
does not claim a separate Vicky flow that is absent from the executable source
catalog.

## Runtime shape

`MyOsDemoRuntime` owns an isolated in-memory engine environment per test. A
single immutable `CoordinationTestRuntime` kernel is shared across the dedicated
test JVM, avoiding repeated Repository, mapper, BEX, and processor construction.
Every mutable session, fragment inventory, subscription snapshot, checkpoint,
and outbox remains isolated.

Timeline authoring is target-free and runtime-owned. `append(timeline,
operation)` first prepares an immutable candidate without advancing the
Timeline cursor or timestamp sequence, then admits the canonical event, and
only afterward publishes the journal row, cursor, timestamp, authored-entry
map, and derived indexes. A failed admission therefore leaves every visible
Timeline surface unchanged, and retrying produces the same timestamp and
BlueId as a fresh runtime. Event admission is content-addressed and idempotent;
if a later host publication were to fail, a verified but unreferenced immutable
body may remain for host garbage collection, but it is not processable because
processing requires the canonical journal row and matching inventory identity.

Timeline delivery uses the persisted subscription snapshot. The demo feeder:

1. compares exact `timeline` and `actor` header BlueIds;
2. supplies every matching active occurrence key in canonical order;
3. invokes the indexed ProcessingEngine lane;
4. requires zero forbidden reads and zero fallback reads.

This is intentionally not a whole-Root compatibility scan.

The same Timeline and actor can legitimately occur at several embedded scope
paths. The feeder therefore never stops at the first Channel match: it keeps
the persisted snapshot's canonical order and supplies every matching
occurrence. The Wadowice Restaurant confirmation, for example, is offered to
both the Restaurant Product and the Restaurant condition inside its PayNote.
Likewise, one immutable entry can be processed independently by several Root
sessions; each Root retains its own epoch, checkpoint, fragments, and CAS.

Operation Mandate eligibility remains feeder-owned. The feeder derives history
completeness from the exact append-only Timeline prefix it owns, evaluates the
current Mandate and target documents, and withholds ineligible entries before
PROCESS. The engine is never asked to reinterpret an unauthorized request.

Public events are asserted at the Root boundary because child emissions are
causal inputs to their ancestors, not automatically public output. Only events
returned by the authoritative Root PROCESS invocation belong to the public
result.

## Performance contract

Business tests do not assert wall-clock time. They assert deterministic work:

- exact selected scope paths;
- no forbidden provider demand;
- no fallback to a complete Root;
- a nonempty request-local bundle for real processing;
- strictly fewer loaded fragments than the complete Wadowice inventory for the
  shared Restaurant confirmation entry.

A separate JMH campaign may measure throughput, but machine noise is not part
of business semantics.

## Java versions

The library remains Java 8. The examples use Java 17 only in the dedicated
`myosDemoTest` source set so that documents can use text blocks and support
records. No Java 17 class is published in the Coordination JAR.

## Playground handoff

The same verification invocation writes
`build/reports/myos-demo-examples/documents.json`. It records every document's
source and canonical-input identities, participant Timeline and actor IDs,
direct embedded paths, and Java source constant. Playground should import this
catalog only after `final.json` reports `workingReady: true`; it should then
test persistence, ingestion, HTTP/UI behavior, and import/export without
reimplementing Coordination semantics.

## Current semantic boundary

These examples freeze the current release behavior. A child added by event `E`
does not process `E`, activates after commit, and does not automatically replay
history before `E`. Historical embedded-document catch-up belongs to the next
specification and library iteration.

## Migration from walkthrough-style tests

`CounterWalkthroughTest`-style application tests are intentionally not copied
into Coordination. They mix Repository construction, mutable `Node` authoring,
whole-Root planning, console output, timing, and application plumbing in one
debug transcript. Here the readable YAML is the source, the feeder derives the
complete indexed candidate set from persisted subscriptions, the real engine
owns planning and commit, and each `should...` test asserts one business
outcome. Playground can therefore reuse these documents while keeping its own
persistence, HTTP, UI, and browser tests at the application boundary.
