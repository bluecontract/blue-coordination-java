# In-memory demo

`InMemoryCoordinationEnvironment` is a multi-session reference host for tests,
examples, and local exploration. It composes the storage-neutral engine with
thread-safe in-memory fragment and session stores. It is not a durable
production host and does not turn process memory into an outbox, recovery log,
or retention system.

The supplied `BlueContracts` and `DocumentProcessor` must already be current,
immutable, Coordination-registered services configured for the same exact
provider domain. The environment borrows them by default. It closes runtimes
only when the builder explicitly transfers ownership.

## Minimal run

<!-- compile-example:InMemoryDemoExample -->
```java
package docs.engine.examples;

import blue.coordination.engine.api.DocumentSessionId;
import blue.coordination.engine.memory.DemoTransition;
import blue.coordination.engine.memory.InMemoryCoordinationEnvironment;
import blue.language.model.Node;
import blue.language.processor.BlueContracts;
import blue.language.processor.DocumentProcessor;
import blue.language.processor.ExternalOrderKey;

public final class InMemoryDemoExample {
    private InMemoryDemoExample() {
    }

    public static DemoTransition run(
            BlueContracts contracts,
            DocumentProcessor processor,
            Node exactDocument,
            Node exactEvent,
            ExternalOrderKey eventOrder) {
        try (InMemoryCoordinationEnvironment environment =
                     InMemoryCoordinationEnvironment.builder()
                             .contracts(contracts)
                             .documentProcessor(processor)
                             .transferRuntimeOwnership(false)
                             .build()) {
            DocumentSessionId session =
                    environment.addDocument(exactDocument);
            return environment.process(session, exactEvent, eventOrder);
        }
    }
}
```

`addDocument(Node)` generates `in-memory-session-N` and a local activation
frontier. The overload accepting `DocumentSessionId` and `ExternalOrderKey`
uses normal open-or-create admission and is preferable when a demo needs stable
retry behavior.

## Processing lanes

`process` uses `CURRENT_ROOT_COMPATIBILITY` and `BALANCED` prefetch. It is the
simplest correctness reference, but it reconstructs the current Root during
planning.

`processIndexed` accepts exact ordered occurrence keys and a `PrefetchPolicy`.
Use it to exercise the production-shaped indexed delivery and selected-scope
locality path. The convenience environment performs plan, execute, and commit;
it throws if the in-memory CAS does not return a committed outcome.

Both lanes execute Contracts with a `PlatformProcessInvocation` containing
the prepared delivery plan and the loader's exact request-local provider. The
completed `CoordinationTransition` therefore exposes diagnostics from the
provider that actually served PROCESS, rather than a reconstructed estimate.

## Human-readable evidence

The returned `DemoTransition` wraps both the immutable semantic transition and
the authoritative commit outcome. Its print helpers expose:

- selected scope chains;
- backend-loaded fragments;
- causally selected workflow identities;
- Root and embedded-scope before/after identities;
- before/after epochs;
- status and total gas.

The gas helper intentionally does not replay PROCESS to manufacture a trace.
Any named trace belongs to the immutable processor observer configured by the
host.

For assertions, prefer the structured accessors:
`transition()`, `commitOutcome()`, `transition().locality()`,
`transition().fragmentTransition()`, and `transition().commitPlan()`.
Formatted output is for people, not a stable serialization contract.

## Inspecting the reference stores

`fragmentStore()` exposes physical body and inventory counts plus read
counters. Equal content in separate sessions should not increase the physical
body count. `sessionStore()` exposes reference Root-outbox and terminal-progress
lists for tests. These extra inspection methods are conveniences of the
in-memory classes, not part of the portable SPIs.

The stores defensively clone nodes, verify BlueIds and canonical bytes, perform
an all-or-nothing immutable body batch, rehydrate inventories through the
closed persistence form, and synchronize authoritative operations. They model
the required semantics, not production capacity or isolation behavior.

## Useful demo assertions

A representative embedded-collection scenario should assert all of the
following rather than only the final document:

1. epoch zero is created once and retry attaches idempotently;
2. indexed and compatibility planning select equivalent causal occurrences;
3. PROCESS is invoked once for an event;
4. only the intended owned occurrence changes;
5. the new Root advances one epoch and its inventory reconstructs exactly;
6. Root outbox events, subscription delta, total gas, and transition identity
   are stable;
7. locality diagnostics show only the permitted causal closure;
8. a second session sharing initial bytes remains unchanged;
9. retrying the exact commit is idempotent;
10. removal retains history and immutable bodies.

These are the same dimensions that a durable adapter should prove before it
replaces either in-memory SPI.
