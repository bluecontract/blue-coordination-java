# Planning and prefetch

Planning turns one already ordered event and one current session into immutable
delivery evidence. Physical prefetch can reduce reads, but it is not allowed to
change selected occurrences, PROCESS output, status, emitted events, or gas.

## Process request

`ProcessRequest` binds:

- `DocumentSessionId`;
- optional expected epoch;
- exact event (or a materializable pure reference);
- host-supplied `ExternalOrderKey`;
- `DeliveryPlanningMode`;
- ordered indexed occurrence keys, when applicable;
- `PrefetchPolicy`;
- whether the convenience `processAndCommit` path is permitted.

Compatibility mode rejects nonempty indexed candidates. Indexed mode accepts
the exact ordered candidate list supplied by the host's durable subscription
index. In both modes the event order must be strictly after the committed
frontier.

<!-- compile-example:PlanExecuteCommitExample -->
```java
package docs.engine.examples;

import blue.coordination.engine.CoordinationProcessingEngine;
import blue.coordination.engine.api.CommitOutcome;
import blue.coordination.engine.api.CoordinationProcessingPlan;
import blue.coordination.engine.api.CoordinationTransition;
import blue.coordination.engine.api.DeliveryPlanningMode;
import blue.coordination.engine.api.DocumentSessionId;
import blue.coordination.engine.api.PrefetchPolicy;
import blue.coordination.engine.api.ProcessRequest;
import blue.language.model.Node;
import blue.language.processor.ExternalOrderKey;

import java.util.Collections;

public final class PlanExecuteCommitExample {
    private PlanExecuteCommitExample() {
    }

    public static CommitOutcome processIndexed(
            CoordinationProcessingEngine engine,
            DocumentSessionId sessionId,
            long expectedEpoch,
            Node exactEvent,
            ExternalOrderKey eventOrder,
            String occurrenceKey) {
        ProcessRequest request = new ProcessRequest(
                sessionId,
                Long.valueOf(expectedEpoch),
                exactEvent,
                eventOrder,
                DeliveryPlanningMode.INDEXED,
                Collections.singletonList(occurrenceKey),
                PrefetchPolicy.BALANCED,
                true);
        CoordinationProcessingPlan plan = engine.plan(request);
        CoordinationTransition transition = engine.execute(plan);
        return engine.commit(transition);
    }
}
```

Keeping the three calls explicit lets a host inspect the plan, record metrics,
or place the final CAS inside its transaction orchestration. It does not make a
plan durable across state changes: `execute` and `commit` both recheck that the
planned epoch, Root, subscription digest, and inventory are still current.

The indexed convenience path uses the same plan/execute/commit implementation:

<!-- compile-example:IndexedProcessAndCommitExample -->
```java
package docs.engine.examples;

import blue.coordination.engine.CoordinationProcessingEngine;
import blue.coordination.engine.api.CommitOutcome;
import blue.coordination.engine.api.DeliveryPlanningMode;
import blue.coordination.engine.api.DocumentSessionId;
import blue.coordination.engine.api.PrefetchPolicy;
import blue.coordination.engine.api.ProcessRequest;
import blue.language.model.Node;
import blue.language.processor.ExternalOrderKey;

import java.util.List;

public final class IndexedProcessAndCommitExample {
    private IndexedProcessAndCommitExample() {
    }

    public static CommitOutcome process(
            CoordinationProcessingEngine engine,
            DocumentSessionId sessionId,
            long expectedEpoch,
            Node exactEvent,
            ExternalOrderKey eventOrder,
            List<String> orderedOccurrenceKeys) {
        ProcessRequest request = new ProcessRequest(
                sessionId,
                Long.valueOf(expectedEpoch),
                exactEvent,
                eventOrder,
                DeliveryPlanningMode.INDEXED,
                orderedOccurrenceKeys,
                PrefetchPolicy.BALANCED,
                true);
        return engine.processAndCommit(request);
    }
}
```

## Delivery planning modes

`INDEXED` is the production locality lane. The host queries its subscription
index and passes exact occurrence keys in deterministic order. The planner
validates and prepares only that evidence; it does not scan all sessions and it
does not discover autonomous documents.

`CURRENT_ROOT_COMPATIBILITY` reconstructs the complete current Root and derives
delivery from it. It is useful for migration, reference behavior, and tests,
but its full-Root materialization is not the desired large-document locality
path.

The resulting `CoordinationProcessingPlan` includes pure Root and event
references, prepared delivery evidence, Root and event inventories, mandatory
seed fragments, preferred prefetch identities, the semantic demand boundary,
and a plan identity. Planning does not advance the session.

## Prefetch policies

- `MINIMUM_BYTES` requests only required seed identities.
- `BALANCED` adds the planner's preferred prefetch identities.
- `MINIMUM_ROUND_TRIPS` also adds metadata along selected scope chains and all
  event fragments.

The bundle loader always adds required seeds, intersects preferences with the
allowed exact closure, and performs the initial batch. The reference in-memory
loader uses one `readAll` call and constructs a request-local provider. The
allowed closure includes required seeds, event fragments, and the selected
Root metadata, edges, children, and source contributions.

A custom loader may choose another physical batching strategy, but it must
return a `LoadedProcessingBundle` with exact diagnostics and must never broaden
semantic delivery. A preferred identity is a performance hint, not permission
to resolve arbitrary content.

## One PROCESS invocation

`execute` calls the public
`BlueContracts.processForPlatformCommit` exactly once. It does not
replay PROCESS to derive traces, deltas, or gas evidence. The returned
`PlatformProcessingResult` and commit companion bind the semantic result,
subscription delta, expected Root, event, order, and commit behavior.

The engine constructs one immutable `PlatformProcessInvocation` from exactly
`plan.preparedDelivery().deliveryPlan()` and
`loadedBundle.exactProvider()`. Contracts therefore performs semantic reads
through the same request-local provider whose physical diagnostics the
transition retains. Before PROCESS, the engine verifies the current session,
epoch, Root, subscription digest, environment, request Root/event, provider,
and prepared plan bindings. Afterwards it verifies the
`PlatformCommitCompanion` Root, revision, event, order, commit decision, and
subscription delta.

This public per-call boundary replaces the former construction-time-deriver
limitation. Coordination does not install a mutable deriver, private bridge,
or thread-local provider. A red pure-reference or fragmented run is now a
runtime correctness failure and remains red in the same-run flagship report;
it must not be reclassified as an absent API or hidden behind an inline
control.

An optional `CoordinationTransitionMemoStore` may cache the complete exact
transition. Its key binds session, current Root, event, delivery evidence,
environment, and gas schedule. Memoizing only child work is unsafe because
Root-level output, gas, subscriptions, and outbox evidence are part of the one
PROCESS result.

## Diagnostics

`LocalityDiagnostics` reports requested and backend-loaded identities, batch
count, fallback reads, loaded bytes, prefetched-but-unused identities,
causally-selected identities, and forbidden reads. These are nonportable
physical observations. They are evidence for comparing policies, not inputs to
semantic decisions.

Measure indexed and compatibility modes separately. A smaller read set is not
a correctness result unless both paths produce the same deterministic semantic
transition for the same valid delivery evidence.

For every completed transition these fields come from the exact provider
passed in `PlatformProcessInvocation`, so they are authoritative physical
observations for that request. A failed invocation has no completed-transition
diagnostics and cannot publish expected zero-read or locality results.
