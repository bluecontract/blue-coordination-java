# Executable complex embedded Coordination walkthrough

This document maps the supplied complex embedded-processing determinism
walkthrough to
`CoordinationComplexEmbeddedDeterminismFlagshipTest`. The test expresses only
Coordination/Contracts PROCESS behavior. Feeder CAS, generation state, outbox,
global scheduling, and child-session commit orchestration stay outside the
fixture.

## Graph

```text
Root
├── large unrelated Root siblings and decoy bodies
└── Emb1
    ├── large unrelated Emb1 siblings and decoy bodies
    └── Emb2
        ├── large unrelated Emb2 siblings and decoy bodies
        └── Emb3
            └── large unrelated Emb3 siblings and decoy bodies
```

Each active scope declares:

- one selected external operation;
- unselected external operations with large bodies;
- one local Triggered Event handler;
- one Document Update handler;
- one direct-child Embedded Node handler where a child exists;
- decoy handlers that must never be selected.

The fixture keeps unrelated content larger than the selected closure. That
makes provider-demand assertions meaningful: a passing run must not obtain good
locality merely because the whole graph is small.

## Verified input

One exact Timeline Entry is admitted through explicit revision-bound
`VerifiedExecutionEvidence`. External occurrences are ordered deeper first:

```text
/emb1/emb2/emb3 | timeline
/emb1/emb2      | timeline
/emb1           | timeline
/               | timeline
```

The evidence carries only environment facts independently derivable from the
Root, Event, registered external-channel functions, and canonical ordering. It
does not add an application target, event, patch, or third semantic input.

## Causal chain

The asserted handler/effect trace proves:

1. Emb3 accepts the external entry and records the pulse.
2. Emb3 reacts to its update, emits A, emits one exact
   `identical-occurrence` event twice, and handles A locally.
3. Both equal event occurrences are enqueued, dequeued, and delivered
   independently at Emb3, Emb2, Emb1, and Root. Equal BlueIds do not collapse
   two queue occurrences into one.
4. Emb2 observes the direct-child update/event, records A, emits B, and handles
   B locally.
5. Emb1 observes the direct-child update/event, records B, emits C, and handles
   C locally.
6. Root observes the direct-child update/event and records C.
7. Direct external occurrences at Emb2, Emb1, and Root run in the verified
   deeper-first order without changing the internal FIFO.

Every state transition, handler selection, patch effect, enqueue/dequeue,
delivery, checkpoint write, and gas entry is compared with an exact expected
projection. The Compute step at Emb3, Emb2, Emb1, and Root captures both the
complete original Timeline Entry and its timestamp. Each captured value is
compared with the original causal Event by complete serialized value and by
BlueId. Cold siblings retain their original BlueIds.

## Root-only public events

Two independent variants run:

```text
descendants-only:
  Emb3, Emb2, and Emb1 emit internal events
  Root emits nothing
  ProcessResult.events == []

Root-D1-D2:
  the same descendant chain executes
  Root emits D1 then D2
  ProcessResult.events == [D1, D2]
```

Descendant events are visible to the synchronous rooted reaction graph but are
not automatically published. Only explicit Root emissions appear in
`ProcessResult.events`.

## Representation/provider matrix

Each public-event variant executes sixteen combinations spanning:

- fully inline Root and Event;
- pure-reference Root and Event;
- partial expansion;
- ordinary fragmented Root and Event;
- cold and warm provider caches;
- one-fragment-at-a-time and bounded-batch delivery.

Across both variants the fixture is designed to perform 32 PROCESS runs once
lower-layer provider verification succeeds, and requires identical:

```text
status
complete resulting Root serialized value
resulting Root BlueId, compared independently from the value
Root event values, BlueIds, and order
total gas
exact named gas trace
handler/effect/event/checkpoint trace
semantic demands
checkpoint state
selected executable-body BlueIds
canonical byte count for every selected executable-body BlueId
```

Physical provider calls and bytes may vary by provider mode. Semantic demands
may not. Strict providers fail on any forbidden identity; the expected
forbidden-demand count is zero. The executable fixture measures canonical JSON
bytes for every stored fragment, proves that the large forbidden decoys
dominate stored bytes, and records requested and backend-loaded bytes for every
run. Selected-body evidence is a sorted identity-to-canonical-byte projection,
so two variants cannot hide a body substitution behind an equal aggregate byte
count.

## Fragment construction

The flagship uses ordinary Blue content-addressed fragments for its declared
Root, embedded scopes, Events, and executable bodies. The fragment graph is
constructed explicitly from the fixture’s authored cuts so the flagship tests
PROCESS representation parity independently from the splitter. Splitter
catalog/effective-body/cyclic/locality behavior is proved by its own focused
suites.

This separation is intentional:

```text
splitter tests  -> preparation representation is exact
flagship test   -> PROCESS semantics are invariant across exact representations
```

Neither side authorizes provider evidence or changes application semantics.

## Observed report

The successful focused test pair writes:

```text
build/reports/coordination-flagship/trace.md
```

An order-independent `@AfterAll` writer derives the file from the two observed
baseline `ProcessingDebugResult` values and the
metrics from all 32 runs across both public-event variants. It contains one
observed trace section for descendants-only and one for Root D1,D2, followed by
a combined 32-row representation/provider table. Each section includes the
exact external-delivery, handler, effect, event, checkpoint, gas, semantic
demand, requested-provider, backend-loaded, forbidden-identity, and stored-byte
projections. It also records the sorted selected-body BlueIds, every selected
body’s canonical byte count, their aggregate canonical bytes, and the selected
body/byte totals for every matrix row. The event streams retain both equal
`identical-occurrence` entries. No expected-only prose is copied into the
report as if it were execution evidence.

Run:

```bash
./gradlew coordinationFlagshipTest \
  --offline --no-daemon -PtestJfr=false
```

## Evidence boundary

The Markdown report is release evidence only when the focused test above
finishes successfully and writes both observed variant baselines in that same
run. A stale report, a partially executed matrix, or prose in this document
cannot substitute for execution. Any current local-composite blocker belongs
in `docs/final-coordination-implementation-blockers.md`, not as a permanent
claim in this walkthrough.
