# Processing Event Binding and Terminate Processing

Status: proposed architecture and delivery specification

Audience: maintainers of `blue-language-java`, `blue-coordination-java`,
`blue-repository-java`, and the Blue Contracts and Coordination specifications

## Purpose

This document defines two changes that must be delivered in order:

1. expose the immutable Processing Event for the current PROCESS run to
   Coordination BEX as `$binding:processingEvent`;
2. execute `Coordination/Terminate Processing` as a terminal Sequential
   Workflow step through the processor's existing graceful-termination API.

Task 1 must be implemented, released, consumed, and green before Task 2 starts.
The tasks must use separate pull requests and releases. Task 2's Mandate
acceptance test depends on Task 1 because Mandate lifecycle timestamps are read
from the Processing Event.

Before Task 1 starts, `blue.repo:blue-repo-java:3.0.0-rc.4` must be published.
It must contain:

- the generated `Coordination/Terminate Processing` model;
- the Mandate BEX program using `$binding:processingEvent` and
  `processingEventTimestamp`;
- no `triggeringEntry` or `triggeringEvent` alias.

PayNote and Payment response migration, feeder-side Mandate validation-program
execution, and consumer adoption of the new Message/Response hierarchy are out
of scope for these two tasks.

## Naming and Layer Boundaries

Blue Contracts 1.0 already defines the input to
`PROCESS(document, event)` as the **Processing Event**. The input is a generic,
read-only Blue node. It is not necessarily a Coordination Timeline Entry.

The following names are normative:

| Layer | Name | Meaning |
| --- | --- | --- |
| Blue Contracts | Processing Event | Original generic event supplied to one PROCESS invocation. |
| `blue-language-java` execution field | `processEventSource` | Read-only `Node` reference retained for that invocation. |
| `ProcessorExecutionContext` | `hasProcessEvent()` | O(1) presence check that does not freeze the event. |
| `ProcessorExecutionContext` | `frozenProcessEvent()` | Lazily created immutable snapshot of the Processing Event. |
| Coordination BEX host | `$binding:processingEvent` | BEX-visible immutable Processing Event. |
| Handler/BEX current event | `$event`, `$binding:event`, `event()` | Current channelized payload delivered to this handler. |

`processEventSource` and `processingEvent` are not two domain concepts. The
former is an implementation field name; the latter is the Blue Contracts noun
and the public BEX binding. No universal `triggeringEntry` or
`triggeringEvent` name is introduced.

`$event` must remain unchanged. It can be the original Processing Event, an
external channel's adapted payload, a processor lifecycle event, or a triggered
event. `$binding:processingEvent` remains the original PROCESS input across all
of those handler deliveries.

An explicit INITIALIZE invocation has no Processing Event and exposes BEX
`undefined`. Initialization performed as part of a PROCESS invocation uses the
same execution object, so its handlers can access that PROCESS invocation's
Processing Event even while their current `$event` is
`Document Processing Initiated`.

## Current Architecture and Gap

`ProcessorEngine` creates one `Execution` per INITIALIZE or PROCESS call.
`ProcessorExecutionContext.event()` holds only the current handler payload.
Every context receives a clone of that payload. Triggered events are cloned into
the scope queue and later become the current event of another handler.

The original Processing Event is currently passed into
`ScopeExecutor.processExternalEvent(...)` and is not retained by `Execution`.
After a direct handler emits a Message, later triggered handlers cannot recover
the original event.

`BexWorkflowContextFactory` currently exposes:

```text
$event
$binding:event
$binding:currentContract
$binding:steps
```

For example:

```text
PROCESS(document, Timeline Entry(timestamp = 7000001))
  -> requestMandateTermination
  -> emits Mandate Termination Requested
  -> applyMandateTermination
```

In `applyMandateTermination`, `$event` is the emitted
`Mandate Termination Requested` Message. The Timeline Entry timestamp is no
longer available. The missing value is execution-scoped host context, not a
field that should be copied into every emitted Message.

The termination half has a different gap. Core already provides
`ProcessorExecutionContext.terminateGracefully(reason)` and buffers that effect
after gas, patches, and emissions. `TerminationService` already owns markers,
lifecycle events, scope finalization, and root-run exit. Coordination only lacks
a workflow-step adapter and a generic way to stop later steps in the same
Sequential Workflow.

## Performance, Immutability, and Gas

The Processing Event may be large and the binding is expected to be read
rarely. The implementation must therefore have these costs:

```text
binding unused: O(1) retained Node reference, no event-sized copy
first binding read: O(size of Processing Event) immutable snapshot
later reads in the same PROCESS run: O(1) snapshot reuse
```

The input is already a `Node`; retaining the reference is cheap. Creating a
`FrozenNode` is not free: it traverses the graph and computes immutable node
state. That work must be lazy and memoized once per `Execution`.

This relies on the existing Blue Contracts rule that the caller treats the
Processing Event as read-only for the synchronous PROCESS call. Core handlers
must never receive the retained mutable reference. They continue receiving
their existing cloned or immutable views. Supporting concurrent caller
mutation would require an eager snapshot at the PROCESS boundary and would
defeat the required unused-binding cost.

The lazy BEX adapter is an implementation detail, not a new BEX language
feature. A package-private `DeferredBexValue` in `blue-coordination-java` may
delegate the `BexValue` interface to a memoized supplier. It must materialize
only when a BEX operation actually reads the binding. Merely constructing a BEX
context must not call `frozenProcessEvent()`.

No gas rule changes:

- reading `$binding:processingEvent` pays the existing deterministic binding
  read charge;
- pointer reads and output operations retain their existing charges;
- lazy snapshot construction is host implementation work and adds no gas;
- no size-based binding charge is added;
- eager and deferred representations of the same value must produce identical
  BEX results, output-size charges, gas totals, and gas-exhaustion boundaries.

Adding a new charge would require a BEX specification and conformance change.
These tasks explicitly do not do that. Determinism is preserved because the
same immutable Processing Event produces the same BEX value and all charged
language operations are unchanged.

## Coordination V2 Prerequisites and Adjacent Work

The RC 4 model upgrade is breaking. `blue-coordination-java` cannot consume it
correctly while retaining V1 Timeline and Operation Request behavior. The
compatibility migration should be a separate prerequisite pull request so the
two tasks in this document remain reviewable.

### Timeline Runtime Migration

The compatibility migration must:

- match a `Timeline Channel` using both its required `timeline` and `actor`
  against the Timeline Entry's required `timeline` and `actor`;
- define equality as semantic Blue node identity, including equality between a
  pure BlueId reference and the equivalent materialized value;
- parse required `sequence` as `BigInteger`;
- use strict sequence increase for same-timeline recency;
- retain full Processing Event identity for exact duplicate detection;
- stop using timestamp for same-timeline checkpoint recency;
- retain timestamp for cross-timeline ordering/completeness and domain
  timestamps;
- accept equal timestamps when sequence increases;
- reject malformed entries without running handlers or advancing checkpoints;
- restore optional `Timeline Entry.source` attribution and make a configured
  source constraint fail when source is absent.

The same-timeline decision is:

```text
same full event identity as checkpoint -> duplicate
current.sequence > checkpoint.sequence -> newer
current.sequence <= checkpoint.sequence -> stale or equivocation
timestamp equality -> irrelevant to same-timeline recency
```

Timeline Providers remain responsible for authenticating actor/source
attribution, assigning strictly increasing sequence values, validating
`prevEntry`, and providing completeness/finality guarantees.

### Composite and All Timelines Semantics

`Composite Timeline Channel` and `All Timelines Channel` do not have an actor or
timeline of their own.

- A Composite explicitly references child Timeline Channel keys.
- All Timelines discovers same-scope Timeline Channels.
- Each child owns its timeline and actor binding.
- The union delegates eligibility to its children.
- If several children accept one entry, handlers bound to the union key receive
  one logical delivery, selected in deterministic `(order, key)` order.
- The union is its own external channel candidate and owns its own checkpoint.
- A child used directly elsewhere owns a separate checkpoint. Its checkpoint
  must not suppress or advance the union checkpoint.

A handler bound directly to a child and a handler bound to the union are
different subscriptions and may both run. The one-delivery rule only prevents
duplicate delivery to the same union handler set when multiple children match.

### Operation Request Version Eligibility

`requireExactDocumentVersion` belongs to the feeder because it requires the
current target state before PROCESS begins.

When the flag is true, the feeder must require `request.document`, compare it
with the exact current target Processing Document state using the platform's
defined semantic Blue equality, and withhold the Timeline Entry on mismatch or
absence. When false or absent, the feeder may pass the entry according to its
normal target-version eligibility policy.

The processor must not compare the request with the initialization marker. That
marker identifies initial processing state, not necessarily the current target
state. Existing `allowNewerVersion` logic and its initialization-marker
comparison must be removed rather than renamed.

### Cross-Channel Operation Dispatch

An Operation Request may be accepted through one source Timeline Channel while
naming a different effective operation channel. Current core dispatch cannot do
this: `ContractLoader` indexes handlers by their declared channel and
`ChannelRunner` invokes only handlers indexed under the accepting channel.

This is separate from the Processing Event binding. Before cross-channel
Operation Mandates are claimed as supported, a dedicated routed-delivery design
must be implemented and proven end to end.

Brief `blue-language-java` scope:

- extend `ChannelDelivery` with an optional effective handler channel key and a
  deterministic logical-delivery key;
- keep checkpoint identity, newness, and persistence on the accepting source
  channel unless the channel explicitly supplies another valid checkpoint key;
- resolve the effective channel in the same scope and reject missing,
  unsupported, or cross-scope targets;
- use the effective channel only for handler discovery and
  `HandlerMatchContext.channelKey()`;
- deduplicate the same logical routed delivery when several eligible source
  channels route the same Processing Event to the same effective channel;
- preserve deterministic candidate order, termination behavior, replay, and
  existing non-routed delivery behavior;
- charge each source candidate/checkpoint operation as today, but charge and
  execute the routed handler set only once.

Brief Blue Contracts specification scope:

- define accepting/source channel, checkpoint channel, and effective handler
  channel as distinct concepts;
- define who may request routing and how same-scope target validation works;
- define handler context semantics, logical-delivery identity, deduplication,
  checkpoint success, replay, ordering, failure, and gas behavior;
- add conformance fixtures for direct delivery, cross-channel delivery,
  multiple matching source channels, stale source checkpoints, unknown target,
  target-handler failure, and replay.

The implementation and end-to-end proof may precede specification
consolidation. After the behavior works, Blue Contracts conformance text and a
versioned Coordination 2.0 specification must capture the proven contract
before a stable V2 release. Coordination 1.0 remains historical and must not be
silently rewritten to describe V2.

---

# Task 1: Expose `$binding:processingEvent`

## Goal

Every Coordination Compute step executed during one PROCESS run can access the
immutable original Processing Event through `$binding:processingEvent`, no
matter whether the current handler was reached directly, through initialization,
through one or more triggered events, through a bridge, or in an embedded
scope. Existing `$event` behavior remains unchanged.

## Design

### `blue-language-java`

Add execution-scoped Processing Event retention to `ProcessorEngine.Execution`.

Recommended shape:

```java
private final Node processEventSource;       // nullable for INITIALIZE
private volatile FrozenNode frozenProcessEvent;
private volatile RuntimeException processEventFreezeFailure;
private volatile boolean processEventFrozen;
```

Construction rules:

- both PROCESS entry points pass their original preprocessed event to the
  `Execution` constructor;
- both explicit INITIALIZE entry points pass `null`;
- implicit initialization during PROCESS reuses the PROCESS `Execution` and
  therefore its Processing Event;
- `processEventSource` is never exposed as a mutable public value.

Add narrow accessors:

```java
public boolean hasProcessEvent();
public FrozenNode frozenProcessEvent();
```

`ProcessorExecutionContext.hasProcessEvent()` delegates to `Execution` and must
not freeze. `ProcessorExecutionContext.frozenProcessEvent()` delegates to one
thread-safe, memoized snapshot operation. It returns `null` for explicit
INITIALIZE. Multiple contexts and multiple calls in one PROCESS run return the
same `FrozenNode` instance.

The memoization must distinguish "not initialized" from the valid null result.
It must not retry a failed snapshot indefinitely. A synchronized slow path or
an equivalent clearly correct state machine is preferred over clever lock-free
code because PROCESS is synchronous and snapshot initialization happens at most
once.

Do not store this context in `ScopeRuntimeContext`, a triggered-event queue,
document properties, a `ThreadLocal`, or static state. It belongs to one
`Execution`, spans all scopes in that execution, and must disappear with the
PROCESS result.

The following behavior must not change:

- `ProcessorExecutionContext.event()`;
- channel evaluation or handler matching;
- channelized payload adaptation;
- triggered queues and bridge payloads;
- checkpoint subjects and event identity;
- PROCESS/INITIALIZE public method signatures;
- gas accounting and processing results.

### `blue-coordination-java`

Add a package-private, host-side `DeferredBexValue` if required to preserve
laziness. It wraps `Supplier<BexValue>`, memoizes one non-null value, normalizes
a null supplier result to BEX `undefined`, and delegates the complete
`BexValue` interface.

Important wrapper rules:

- no supplier call in the constructor;
- first semantic method call evaluates exactly once;
- `at(emptyPath)` returns the materialized delegate value, not another wrapper;
- subsequent reads reuse the same delegate;
- concurrent first access does not evaluate twice;
- a supplier failure is stable and is not repeatedly retried;
- equality, conversion, key iteration, scalar reads, list reads, and node
  conversion are behaviorally identical to the eager delegate.

`BexWorkflowContextFactory.create(...)` adds:

```java
BexValue processingEvent = processorContext.hasProcessEvent()
        ? DeferredBexValue.memoized(() ->
                BexValues.frozen(processorContext.frozenProcessEvent()))
        : BexValues.undefined();

builder.binding("processingEvent", processingEvent);
```

The exact code may follow local builder style, but these semantics are fixed.
Do not bind the current event as a fallback. Do not hardcode the BlueId of
`Coordination/Timeline Entry`. The binding is generic and complete.

The RC 4 Mandate program may validate its own expected shape by checking that
`processingEvent` is an object and `processingEvent/timestamp` is an integer.
Specific Mandate logic may interpret that timestamp as a Timeline Entry
timestamp. The base host binding itself remains type-neutral.

### API and Specification Impact

`blue-language-java` receives a small public context API addition, but no new
PROCESS overload and no Coordination dependency. `blue-coordination-java`
defines the BEX binding convention.

No BEX syntax, operator, type-matching rule, or gas schedule changes. The base
BEX specification does not need an update. Blue Contracts already defines the
Processing Event and its read-only nature; implementation documentation should
state that the new context accessors preserve that original input.

## Implementation Plan

1. Pin and verify published `blue-repo-java:3.0.0-rc.4` content.
2. Add `processEventSource` to all `ProcessorEngine.Execution` construction
   paths and set it only for PROCESS.
3. Implement lazy, memoized freezing in `Execution`.
4. Add `hasProcessEvent()` and `frozenProcessEvent()` delegates to
   `ProcessorExecutionContext` with Javadoc that distinguishes the Processing
   Event from the current event.
5. Add focused core tests before changing Coordination.
6. Add and exhaustively test the package-private deferred BEX adapter.
7. Add `$binding:processingEvent` in `BexWorkflowContextFactory`.
8. Add direct, triggered, embedded, bridge, and implicit-initialization binding
   tests in Coordination.
9. Add the real generated Mandate lifecycle fixture using timestamp `7000001`.
10. Run performance/allocation checks, targeted tests, and full builds.
11. Release `blue-language-java`, then consume that exact release in
    `blue-coordination-java`; do not rely on composite-build substitution for
    the final verification.

## Test Plan

### Fixtures

Use small builders for execution-context unit tests and readable YAML fixtures
for full workflows:

```text
src/test/resources/coordination/processing-event/
  direct-read.document.blue.yaml
  direct-read.event.blue.yaml
  triggered-read.document.blue.yaml
  triggered-read.event.blue.yaml
  multi-hop-read.document.blue.yaml
  embedded-read.document.blue.yaml
  mandate-lifecycle.document.blue.yaml
  mandate-lifecycle.event.blue.yaml
```

Every fixture must make the distinction observable. Use different values in the
root Processing Event and current emitted Message so a test cannot pass by
accidentally reading `$event`.

### `blue-language-java` Cases

1. `initializeHasNoProcessEvent`
   - Explicit INITIALIZE reports false and returns null.

2. `processRetainsOriginalProcessEvent`
   - PROCESS reports true and returns an immutable snapshot equal to the input.

3. `implicitInitializationSharesProcessEvent`
   - A lifecycle handler during first PROCESS sees the same root event.

4. `directAndTriggeredContextsShareProcessEvent`
   - Direct and triggered handlers have different current events but the same
     frozen Processing Event instance.

5. `multiHopTriggeredContextsShareProcessEvent`
   - Two or more emitted-event hops preserve the original value.

6. `embeddedScopesShareProcessEvent`
   - Root and nested scope contexts see the same root input.

7. `bridgedHandlersShareProcessEvent`
   - Bridge payload semantics remain unchanged while causal context survives.

8. `channelAdaptationDoesNotReplaceProcessEvent`
   - A channel projection becomes `event()`; the retained value remains the
     original input.

9. `currentEventMutationCannotMutateProcessEvent`
   - Mutating a handler-local event copy does not alter the frozen root value.

10. `processRunsDoNotLeakContext`
    - Two PROCESS calls on the same processor expose their own input only.

11. `frozenProcessEventIsMemoized`
    - Repeated access returns the same instance and one snapshot operation.

12. `unusedProcessEventDoesNotFreeze`
    - A processing run with no accessor use performs no snapshot. Verify with a
      narrow package-private test seam or allocation instrumentation, not timing
      alone.

13. `snapshotFailureIsStable`
    - If test injection forces snapshot failure, later reads fail consistently
      and do not repeatedly traverse.

### `blue-coordination-java` Cases

1. `processingEventBindingReadsCompleteRootInput`
   - Read timeline, sequence, timestamp, actor, source, and message fields.

2. `currentEventAndProcessingEventRemainDistinct`
   - In a triggered handler, `$event` is the Message and `processingEvent` is
     the original Timeline Entry.

3. `processingEventSurvivesMultipleTriggeredHops`
   - Every hop reads timestamp `7000001`.

4. `explicitInitializationExposesUndefined`
   - Kind checks and pointer reads return BEX `undefined`, not current event.

5. `implicitInitializationExposesProcessEvent`
   - Initialization workflow during PROCESS reads the root input.

6. `nonTimelineProcessingEventIsSupported`
   - A generic scalar/list/object event can be exposed without type checks or
     hardcoded BlueIds in the host.

7. `missingTimestampRemainsUndefined`
   - Host does not invent or coerce a timestamp.

8. `invalidTimestampKindIsRejectedByMandateGuard`
   - Mandate lifecycle logic does not mutate timestamped state.

9. `deferredValueDoesNotEvaluateWhenBindingIsUnused`
   - A Compute program that never references the binding leaves the supplier at
     zero calls.

10. `deferredValueEvaluatesOnceAcrossRepeatedReads`
    - Multiple pointer reads and conversions call the supplier once.

11. `deferredValueDelegatesEveryBexValueShape`
    - Undefined, null, scalar, list, object, pointer, keys, size, `toNode`, and
      `toSimple` behavior matches the eager value.

12. `deferredValueMemoizesFailure`
    - A failing supplier is not retried and preserves the same deterministic
      failure category/message.

13. `deferredAndEagerBindingsUseIdenticalGas`
    - Run identical programs and compare value, changeset, events, gas, and the
      exact gas-exhaustion boundary.

14. `largeUnusedProcessingEventHasConstantBindingOverhead`
    - Compare small and large valid events using allocation counters or JMH;
      context creation must not scale with event size.

15. `largeUsedProcessingEventFreezesOnce`
    - Cost may scale once with event size but not with handler or pointer-read
      count.

16. `mandateLifecycleUsesProcessingEventTimestamp`
    - The generated RC 4 program records `7000001` through a triggered workflow.

### Artifact and Regression Cases

- The generated `TerminateProcessing` class resolves from RC 4.
- Embedded repository resources contain `processingEvent` and
  `processingEventTimestamp`.
- Product code and generated resources contain no `triggeringEntry` or
  `triggeringEvent` alias.
- Existing `$event`, `$binding:event`, steps, document view, and
  `currentContract` tests remain unchanged and green.
- Existing channel, checkpoint, triggered-event, bridge, initialization, gas,
  and termination suites remain green.
- Changed branches have 100% branch coverage; repository-wide coverage does not
  decrease.

## Code Review Hints

Reviewers should verify:

- Processing Event state is owned by `Execution`, not a scope or handler;
- PROCESS and INITIALIZE constructors set presence correctly;
- `hasProcessEvent()` cannot trigger a graph traversal;
- freezing is lazy, once per run, thread-safe, and never exposes mutable input;
- `$event` and channelized payload behavior did not change;
- the deferred adapter is host-local and does not change BEX semantics;
- no new gas charge or hidden size traversal occurs during context creation;
- no Timeline Entry BlueId or Coordination class appears in core language code;
- explicit INITIALIZE returns undefined while implicit initialization in PROCESS
  retains the root event;
- tests would fail if the implementation substituted current `$event`;
- final verification consumes released artifacts rather than sibling source.

## Strict Definition of Done

Task 1 is complete only when:

- [ ] RC 4 is published and contains the corrected Mandate BEX and generated
      Terminate Processing model.
- [ ] `blue-language-java` exposes exactly `hasProcessEvent()` and
      `frozenProcessEvent()` with the documented semantics.
- [ ] The execution field is named `processEventSource` and remains generic.
- [ ] No `triggeringEntry` or `triggeringEvent` API/binding alias exists.
- [ ] Unused access retains only O(1) state and performs no event-sized copy.
- [ ] Used access freezes at most once per PROCESS run.
- [ ] Direct, initialization, triggered, multi-hop, bridge, embedded-scope,
      mutation-isolation, and cross-run cases pass.
- [ ] `$binding:processingEvent` is available in every Coordination Compute
      context during PROCESS and undefined during explicit INITIALIZE.
- [ ] `$event` behavior is unchanged and regression-tested.
- [ ] Deferred/eager BEX results and gas are identical.
- [ ] No BEX specification, operator, gas schedule, or conformance rule changed.
- [ ] The real generated Mandate fixture records the root timestamp.
- [ ] Targeted tests and full clean builds pass in both Java repositories.
- [ ] `blue-coordination-java` consumes the released language artifact and RC 4
      with local substitution disabled for the final gate.
- [ ] Coverage and allocation/performance gates pass with readable evidence.
- [ ] The pull request contains no Task 2 implementation or unrelated V2 work.

---

# Task 2: Execute `Coordination/Terminate Processing`

## Entry Gate

Do not start Task 2 until Task 1 is released and consumed. The Coordination
build must already resolve RC 4 and the released Processing Event context API.
If the Mandate acceptance fixture uses a source channel different from its
effective operation channel, the routed-delivery dependency described above
must also be complete before Task 2 can satisfy its end-to-end Definition of
Done.

## Goal

The default Coordination Sequential Workflow runtime recognizes
`Coordination/Terminate Processing`, requests graceful termination of the
current processing scope, applies effects from preceding workflow steps, and
executes no later steps in the same workflow.

## Runtime Contract

For:

```yaml
steps:
  - name: Persist final state
    type: Coordination/Update Document
    changeset: ...
  - name: Emit final evidence
    type: Coordination/Trigger Event
    event: ...
  - name: Stop processing
    type: Coordination/Terminate Processing
    reason: Mandate terminated
  - name: Must not run
    type: Coordination/Update Document
    changeset: ...
```

the runtime must:

1. execute and buffer the first two steps;
2. call `ProcessorExecutionContext.terminateGracefully(...)` with the exact
   optional reason;
3. stop before `Must not run`;
4. return normally from the workflow executor;
5. let `applyBufferedEffects()` apply gas, patches, emissions, then termination;
6. let core `TerminationService` write the marker, emit the lifecycle event,
   finalize the scope, and end the root run where applicable.

Coordination must not write `/contracts/terminated`, construct core lifecycle
events, clear queues, or throw a private control-flow exception.

An event emitted by a preceding step is recorded before termination. Triggered
handlers are not guaranteed to run after the scope terminates. Work required
before shutdown must be a preceding step or a handler that already ran before
the terminating workflow. Mandate follows that rule: one workflow emits
`Mandate Terminated`; the workflow triggered by that Message then executes
Terminate Processing.

Root graceful termination returns the existing success status and terminates
the run. Nested graceful termination affects only the current embedded scope;
parent processing follows existing core semantics.

## Design

### Executor Ownership

Add:

```text
src/main/java/blue/coordination/processor/workflow/
  TerminateProcessingStepExecutor.java
```

The executor:

- implements `WorkflowStepExecutor<TerminateProcessing>`;
- supports only the generated `TerminateProcessing` model;
- calls `context.processorContext().terminateGracefully(step.getReason())`;
- returns a terminal workflow result;
- records bounded metrics in the same style as other built-in steps;
- contains no marker, lifecycle, scope, queue, or run-status logic.

Pass the reason unchanged. Core currently omits the reason property when the
value is null or empty and preserves non-empty text exactly. Coordination must
not trim, normalize, or reinterpret it.

### Generic Terminal Workflow Control

Buffering termination alone is insufficient because the current runner would
continue iterating over later steps. Extend `WorkflowStepResult` with explicit,
generic control:

```java
public static WorkflowStepResult stopWorkflow();
public boolean stopsWorkflow();
```

`none()` and both `value(...)` factories remain non-terminal. The runner first
records any returned step value using existing behavior, then breaks when
`stopsWorkflow()` is true.

Do not use null, a magic value, an exception, a class-name check, or a direct
`instanceof TerminateProcessing` branch in the runner loop. Executors declare
workflow outcome; the runner implements generic control.

### Registration and Diagnostics

- Register `TerminateProcessingStepExecutor` exactly once in every default
  executor factory.
- Pass the shared `BexProcessingMetrics` instance.
- Extend unsupported-step naming to report
  `Coordination/Terminate Processing`.
- Custom executor lists remain complete replacements. If they omit this
  executor, the runner must fail clearly rather than silently adding defaults.

### Metrics

Add bounded aggregate metrics consistent with existing step metrics:

```text
terminateProcessingStepsExecuted
terminateProcessingStepNanos
```

Wire increment/add methods, getters, snapshots, and constructor copying. Do not
add reason, scope, actor, Mandate, or document identifiers as metric labels.

### Core Boundary

No `blue-language-java` termination change belongs in Task 2. If the executor
cannot be implemented solely with the public `terminateGracefully` method and
normal workflow return, stop and raise an architecture blocker. Modifying
`TerminationService`, `ContractEffectBuffer`, termination markers, lifecycle
event construction, or root/nested semantics would indicate a layer violation.

## Implementation Plan

1. Verify Task 1 and RC 4 artifact coordinates without local substitution.
2. Add terminal state to `WorkflowStepResult` while preserving all existing
   factories and behavior.
3. Teach `SequentialWorkflowRunner` to stop after a terminal result.
4. Implement `TerminateProcessingStepExecutor` as a thin adapter.
5. Register it in every default runner factory and improve diagnostics.
6. Add metrics and immutable snapshot plumbing.
7. Add unit tests for executor behavior, generic stop control, registration,
   diagnostics, reasons, and metrics.
8. Add fixture-based root and embedded-scope integration tests.
9. Add the full generated Mandate termination acceptance fixture using
   `$binding:processingEvent/timestamp`.
10. Update README supported contracts and dependency coordinates.
11. Run targeted tests, deliberate mutation checks, coverage, and full builds.

## Test Plan

### Fixtures

```text
src/test/resources/coordination/termination/
  terminate-processing.document.blue.yaml
  terminate-processing.event.blue.yaml
  terminate-embedded-scope.document.blue.yaml
  terminate-embedded-scope.event.blue.yaml
  mandate-termination.document.blue.yaml
  mandate-termination.event.blue.yaml
```

The generic root fixture must contain a patch, an emission, Terminate
Processing, and a sentinel patch after termination. This one story proves
effect ordering and terminal control while low-level cases remain separate.

### Unit and Runner Cases

1. `supportsTerminateProcessingOnly`
   - Supports the generated model and rejects unrelated steps.

2. `requestsGracefulTerminationWithExactReason`
   - The exact non-empty reason reaches the core result.

3. `missingReasonUsesCoreOmissionSemantics`
   - Marker and lifecycle event omit reason.

4. `emptyReasonUsesCoreOmissionSemantics`
   - Empty text is passed unchanged and core omits the property.

5. `stopWorkflowPreventsFollowingSteps`
   - A sentinel executor after a terminal result never runs.

6. `nonTerminalResultsPreserveIteration`
   - `none()` and `value(...)` continue to later steps.

7. `terminalValueIsRecordedBeforeStop`
   - If generic terminal results later support a value, runner ordering remains
     defined and tested.

8. `defaultFactoriesRegisterExecutorExactlyOnce`
   - Constructor and all BEX factory variants support the step.

9. `customRunnerWithoutExecutorFailsClearly`
   - Diagnostic names `Coordination/Terminate Processing`.

10. `metricsCountOnlyExecutedTerminateSteps`
    - Counter increments once and timing is non-negative.

11. `nullStepFailsWithReadableDiagnostic`
    - Existing null-step behavior remains deterministic.

12. `invalidReasonTypeFailsBeforeExecution`
    - Type resolution/model conversion rejects non-Text content.

13. `firstTerminateStepStopsSecondTerminateStep`
    - Only the first executes and metrics increment once.

### Integration Cases

1. `appliesPrecedingPatchBeforeRootTermination`
   - Final state contains the intended pre-termination patch.

2. `recordsPrecedingEmissionBeforeTerminationLifecycle`
   - Root emissions show domain evidence before processing termination.

3. `doesNotExecuteStepsAfterTerminateProcessing`
   - Sentinel state is absent.

4. `writesGracefulTerminationMarker`
   - Cause is `graceful`; non-empty reason is exact.

5. `recordsTerminationLifecycleEvent`
   - Cause and reason agree with the marker.

6. `rootGracefulTerminationReturnsSuccess`
   - It is not reported as runtime fatal.

7. `stopsLaterHandlersAfterRootTermination`
   - A later eligible handler does not execute.

8. `terminatesCurrentEmbeddedScopeOnly`
   - Child marker exists, root marker does not, and parent processing continues.

9. `terminatedDocumentDoesNotReexecuteWorkflow`
   - A later PROCESS call does not duplicate terminal effects.

10. `preTerminationEmissionIsRecordedButConsumerDoesNotRunAfterShutdown`
    - The emitted event exists, but a queued consumer in the terminated scope
      cannot mutate state after finalization.

11. `precedingGasAndEffectsRemainApplied`
    - Termination does not discard valid work buffered earlier in the handler.

12. `terminationDuringCheckpointedExternalChannelDoesNotAdvanceCheckpoint`
    - Existing core no-checkpoint-on-termination semantics remain intact.

### Mandate End-to-End Cases

Use generated repository types and the actual RC 4 Mandate definition. Do not
copy or simplify the Mandate contracts locally.

1. `terminateMandateUsesProcessingEventTimestampAndStopsProcessing`
   - A Timeline Entry with timestamp `7000001` carries a valid termination
     Operation Request.
   - Status becomes `Mandate/Status Terminated`.
   - `terminatedAt` equals `7000001`.
   - `Mandate Termination Requested` precedes `Mandate Terminated`.
   - The final processing marker is graceful with reason
     `Mandate terminated`.

2. `terminateMandateWithoutIntegerProcessingTimestampDoesNotTerminate`
   - No terminal state, timestamp, or processing marker is produced.

3. `alreadyTerminatedMandateKeepsOriginalEvidence`
   - A later request cannot replace `terminatedAt` or duplicate terminal
     evidence.

4. `failedMandateDoesNotReachTerminateProcessing`
   - Guards suppress terminal workflow execution.

5. `terminationCorrelationSurvivesFullFlow`
   - Reason and `inResponseTo` point to the accepted Message according to the
     repository contract.

6. `crossChannelMandateTerminatesExactlyOnce`
   - Required only when the fixture's source and effective operation channels
     differ.
   - The routed handler executes once even if multiple source bindings match.
   - Correct source checkpoint semantics are preserved.

### Coverage and Quality Gates

- `TerminateProcessingStepExecutor`, terminal `WorkflowStepResult` state, and
  the runner stop branch have 100% line and branch coverage.
- Overall Coordination coverage does not decrease.
- Remove the runner break deliberately; the sentinel integration test must
  fail.
- Replace `terminateGracefully` with a no-op or fatal request deliberately;
  marker, status, and scope tests must fail.
- Tests assert domain outcomes through public processing results. Do not mock
  `TerminationService`.
- Complete workflows use YAML fixtures; narrow state-machine behavior uses
  small builders.
- Each low-level test has one behavioral reason to fail and descriptive Given,
  When, Then structure.

## Code Review Hints

Reviewers should verify:

- the executor is a thin adapter to `terminateGracefully`;
- no Coordination code writes reserved marker paths or constructs lifecycle
  events;
- termination stays buffered until the handler returns;
- all earlier step effects survive and later steps cannot add effects;
- terminal control is generic and source-compatible for current executors;
- every default runner includes the executor exactly once;
- custom runners receive no hidden fallback;
- reason text is passed unchanged;
- root and embedded-scope behavior is covered through real processing;
- checkpoint behavior on termination remains unchanged;
- metrics are bounded and all snapshots/getters are wired;
- tests use generated RC 4 models and the actual Mandate workflow;
- no Task 1, routed-delivery, or core termination refactor is mixed into the
  Task 2 pull request.

## Strict Definition of Done

Task 2 is complete only when:

- [ ] Every Task 1 Definition of Done item remains satisfied.
- [ ] Released Task 1 and RC 4 artifacts are consumed without local
      substitution.
- [ ] `TerminateProcessingStepExecutor` delegates only to
      `ProcessorExecutionContext.terminateGracefully`.
- [ ] Terminate Processing is terminal within its Sequential Workflow.
- [ ] Existing non-terminal `WorkflowStepResult` behavior is regression-tested.
- [ ] Every default runner path registers the executor exactly once.
- [ ] Missing custom-runner support fails with the qualified type name.
- [ ] Patches and emissions before termination are applied in existing core
      order.
- [ ] Root success, lifecycle, marker, and nested-scope semantics are proven.
- [ ] Null, empty, non-empty, and invalid reason cases are covered.
- [ ] Duplicate Terminate Processing steps stop at the first.
- [ ] Metrics counter, timing, getters, and snapshots are complete and tested.
- [ ] The generated Mandate fixture records the Processing Event timestamp and
      gracefully terminates.
- [ ] Cross-channel Mandate execution is proven if required by the fixture.
- [ ] Targeted tests, deliberate mutation checks, coverage, and full clean build
      pass.
- [ ] README dependencies and supported-contract documentation are current.
- [ ] No `blue-language-java` termination behavior changed.
- [ ] The pull request contains no unrelated model or consumer migration.

## Suggested Verification Commands

Exact class names may change only if the final names remain equally specific.

```bash
# Task 1: language execution context
../blue-language-java/gradlew -p ../blue-language-java test \
  --tests '*ProcessorProcessEventContextTest'

# Task 1: Coordination host binding
./gradlew test --tests '*ProcessingEventBindingTest'

# Task 2: executor and runner control
./gradlew test --tests '*TerminateProcessingStepExecutorTest'

# Task 2: Mandate acceptance
./gradlew test --tests '*MandateTerminationWorkflowTest'

# Final gate in each changed Java repository
./gradlew clean build
```

The final verification must resolve released artifacts from configured
repositories. Passing a composite build against sibling source is useful during
development but is not release evidence.
