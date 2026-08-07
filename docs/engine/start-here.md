# Coordination processing engine: start here

The Coordination processing engine is a storage-neutral host facade over the
deterministic Contracts processor. It manages many independent Root-document
sessions, plans delivery to owned embedded occurrences, runs exactly one public
platform-commit PROCESS call, and proposes a compact authoritative commit. The
generic document-processing rules remain in `blue-language-java`; this layer
adds session identity, ordering, fragment locality, subscription projection,
and host persistence boundaries.

This is documentation for the current engine implementation surface. It is not
a declaration that Coordination is a public release candidate. The exact
immutable Repository required-closure blockers are tracked separately and must
not be inferred to be resolved from these guides. Cross-session fan-out remains
a host protocol rather than a core-engine operation; the experimental myOS
source set demonstrates such a protocol without widening this API.

## Read in this order

1. [Session and epoch model](session-and-epoch-model.md)
2. [Admission and attachment](admission-and-attachment.md)
3. [Fragment-store SPI](fragment-store-spi.md)
4. [Session-store SPI](session-store-spi.md)
5. [Planning and prefetch](planning-and-prefetch.md)
6. [Atomic commit](atomic-commit.md)
7. [In-memory demo](in-memory-demo.md)
8. [Database host integration](database-host-integration.md)
9. [Owned occurrences versus autonomous documents](owned-occurrences-vs-autonomous-documents.md)
10. [Performance evidence](performance-evidence.md)

## Host responsibilities

The host supplies:

- current immutable `BlueContracts` and `DocumentProcessor` generations with
  the Coordination runtime registered;
- a `CoordinationFragmentStore` for exact immutable bodies and body-free
  inventories;
- a `CoordinationSessionStore` for session, epoch, progress, and outbox state;
- a stable `DocumentSessionId` and a strictly increasing
  `ExternalOrderKey` for each delivered event;
- indexed occurrence candidates when using `DeliveryPlanningMode.INDEXED`;
- durable transaction, retry, observability, retention, and backup policy.

The engine validates that the runtime generations are current, the fragment
profile is exact, and the persisted session belongs to the same derived
environment. It does not synthesize session identity from a Root BlueId, order
events on the host's behalf, or fan one event out to other sessions.

## Smallest production-shaped composition

The following is a complete compilation unit. The caller owns the supplied
services unless `transferRuntimeOwnership(true)` is selected.

<!-- compile-example:EngineBootstrapExample -->
```java
package docs.engine.examples;

import blue.coordination.engine.CoordinationProcessingEngine;
import blue.coordination.engine.spi.CoordinationFragmentStore;
import blue.coordination.engine.spi.CoordinationProcessingBundleLoader;
import blue.coordination.engine.spi.CoordinationSessionStore;
import blue.language.processor.BlueContracts;
import blue.language.processor.DocumentProcessor;

public final class EngineBootstrapExample {
    private EngineBootstrapExample() {
    }

    public static CoordinationProcessingEngine create(
            BlueContracts contracts,
            DocumentProcessor processor,
            CoordinationFragmentStore fragments,
            CoordinationSessionStore sessions,
            CoordinationProcessingBundleLoader bundles) {
        return CoordinationProcessingEngine.builder()
                .contracts(contracts)
                .documentProcessor(processor)
                .fragmentStore(fragments)
                .sessionStore(sessions)
                .bundleLoader(bundles)
                .transferRuntimeOwnership(false)
                .build();
    }
}
```

The default engine environment identity binds the Language version and
canonical registry, the complete Contracts processor registration inventory
and gas package, the Coordination registration, the BEX runtime registry and
gas manifest, provider evidence domain, order and subscription policies,
fragmentation and edge schemas, and quota manifest. A durable host should
persist that identity with each session and
treat a mismatch as a migration boundary, not silently rewrite it.

## One-event flow

For one session, the normal flow is:

1. `addDocument` materializes the exact input, fragments and verifies it,
   projects subscriptions, and asks the session store to admit epoch zero.
2. `plan` checks the active session, expected epoch, environment, and event
   frontier; it then builds exact delivery evidence and a physical prefetch
   preference. Planning does not mutate session state.
3. `execute` loads a request-local bundle and invokes
   `BlueContracts.processForPlatformCommit` exactly once with an immutable
   `PlatformProcessInvocation`. That invocation carries the plan's exact
   prepared delivery plan and the loaded bundle's exact provider. Execution
   returns an immutable `CoordinationTransition`; it still has not advanced
   the session.
4. `commit` admits any new immutable result fragments, persists their
   inventory, and performs one revision-bound session-store CAS.

The current public Contracts boundary accepts this per-invocation evidence;
the engine does not install a mutable construction-time deriver, thread-local
provider, private bridge, or compatibility shim. It validates the invocation
and returned commit companion against the session, epoch, Root, event,
subscription digest, revision, and order before proposing a commit. See
[planning and prefetch](planning-and-prefetch.md#one-process-invocation).

API availability is not a green-status claim. The generated engine report is
the authority for whether the same invocation completed the basic engine,
10×10 locality campaign, storage TCK, and exact 32-run repository-independent
flagship. Immutable Repository failures are listed separately as release
blockers and do not become engine blockers.

`processAndCommit` is the convenience form of steps 2–4 and requires a
`ProcessRequest` whose `commit` flag is `true`. For hosts that need inspection
or transaction orchestration, keep the plan/execute/commit steps explicit.

## Scope boundaries

One engine instance may manage many sessions and may physically deduplicate
equal immutable fragment bytes between them. Nevertheless, every PROCESS and
every session CAS concerns exactly one `DocumentSessionId`. Equal Root BlueIds
do not merge sessions, epochs, frontiers, subscriptions, outboxes, or removal
state. See [owned occurrences versus autonomous documents](owned-occurrences-vs-autonomous-documents.md)
before designing child-document routing.
