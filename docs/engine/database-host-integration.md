# Database host integration

A production host normally maps Coordination onto two persistence roles:

1. a content-addressed fragment store for immutable bodies and body-free
   inventories;
2. a transactional session store for compact authoritative session, epoch,
   progress, idempotency, and Root-outbox state.

They may share one database, but their semantics remain distinct. The engine's
observable write shape is one immutable fragment-body batch write (when there
are new bodies), one idempotent inventory write, and one compact authoritative
session CAS. The API does not require or claim a distributed transaction across
the two roles.

## Wiring host adapters

The adapters implement the public SPIs; the engine does not require a specific
database library.

<!-- compile-example:DatabaseHostWiringExample -->
```java
package docs.engine.examples;

import blue.coordination.engine.CoordinationProcessingEngine;
import blue.coordination.engine.spi.CoordinationFragmentStore;
import blue.coordination.engine.spi.CoordinationProcessingBundleLoader;
import blue.coordination.engine.spi.CoordinationSessionStore;
import blue.language.processor.BlueContracts;
import blue.language.processor.DocumentProcessor;

public final class DatabaseHostWiringExample {
    private DatabaseHostWiringExample() {
    }

    public static CoordinationProcessingEngine wire(
            BlueContracts contracts,
            DocumentProcessor processor,
            CoordinationFragmentStore databaseFragments,
            CoordinationSessionStore databaseSessions,
            CoordinationProcessingBundleLoader databaseBundles) {
        return CoordinationProcessingEngine.builder()
                .contracts(contracts)
                .documentProcessor(processor)
                .fragmentStore(databaseFragments)
                .sessionStore(databaseSessions)
                .bundleLoader(databaseBundles)
                .providerEvidenceDomain("coordination-primary-v1")
                .externalOrderPolicyIdentity("host-total-order-v1")
                .transferRuntimeOwnership(false)
                .build();
    }
}
```

Persist the resulting `engine.environmentIdentity()` with every admitted
session. Provider-domain and order-policy strings are versioned semantic
identities, not deployment labels to change on each restart.

The bundle loader must return the exact provider used for that request. Its
provider implements `CoordinationLocalityDiagnosticsProvider`, and the engine
passes it in `PlatformProcessInvocation` together with the plan's exact
delivery plan. A durable adapter may use one transaction/multi-get followed by
bounded dynamic fallback waves, but it must report the actual requested and
backend-loaded identities, batch/fallback counts, loaded bytes, unused
prefetches, causal selections, and forbidden reads.

## Suggested fragment schema

One possible relational mapping is:

```sql
fragment_body(
  profile_id, blue_id, canonical_bytes, physical_digest,
  primary key (profile_id, blue_id)
)

fragment_inventory(
  inventory_id primary key, profile_id, schema_id,
  root_blue_id, closed_inventory_payload
)
```

`putAllIfAbsent` is one immutable fragment batch transaction:

1. calculate and validate every proposed identity before the transaction;
2. lock/read every existing `(profile_id, blue_id)` winner in a stable order;
3. compare canonical bytes for all existing winners;
4. on any conflict, roll back without inserting any member;
5. insert all missing members;
6. commit;
7. return winners through exact reads so the verifier can check them again.

Database “insert ignore” by itself is insufficient because it does not prove
that an existing winner has the same canonical bytes. A multi-row operation
that can partially succeed on a conflict also violates the SPI.

`putInventory` follows body admission. Store the exact closed `toMap()` shape
or an equivalently closed encoding and enforce idempotence by inventory
identity. `requireInventory` must rehydrate and recompute identity; do not trust
only a database key. Inventories contain identities and graph records, not body
blobs.

## Suggested session schema

One possible mapping is:

```sql
managed_session(
  session_id primary key, status, initial_root_id, current_root_id,
  current_epoch, environment_id, committed_frontier,
  inventory_id, subscription_snapshot
)

document_epoch(
  session_id, epoch, root_id, prior_root_id, event_id, event_order,
  inventory_id, subscription_id, root_event_ids, total_gas, transition_id,
  primary key (session_id, epoch)
)

committed_transition(
  session_id, transition_id, outcome_payload,
  primary key (session_id, transition_id)
)

root_outbox(
  session_id, transition_id, ordinal, event_blue_id, publish_state,
  primary key (session_id, transition_id, ordinal)
)

terminal_progress(
  session_id, transition_id, event_blue_id, event_order,
  primary key (session_id, transition_id)
)
```

Normalize subscriptions and event-order tuples if the host needs indexed
queries, but retain an exact closed representation. Integer and text order-key
components must not be collapsed into locale-sensitive strings.

## The compact authoritative CAS

In one database transaction:

1. look up `(session_id, transition_identity)` and return
   `ALREADY_COMMITTED` if present;
2. conditionally lock or update the `ACTIVE` session matching both expected
   epoch and expected Root;
3. return `CONFLICT` if no row matches;
4. persist the resulting session;
5. insert the optional epoch receipt;
6. append ordered Root-outbox rows;
7. insert terminal progress and committed-transition evidence;
8. commit.

Checking transition idempotency first is essential for an ambiguous retry: the
session already advanced, so a CAS-only check would incorrectly report a
conflict. Uniqueness constraints should make duplicate epoch, transition, and
outbox insertion fail closed inside the transaction.

## Failure windows

The immutable fragment batch and inventory are written before the session CAS.
If the process crashes in that window or loses the CAS, those immutable records
may remain unreferenced. Do not attempt an unsafe compensating delete. A retry
can reuse them, and a separate reachability-based collector can eventually
handle them under host retention policy.

If the CAS commit result is ambiguous, repeat the same transition identity. If
a different proposal has won, re-read the current session and re-plan; never
patch the expected revision in the old commit plan.

## Adapter acceptance tests

Run the same store-contract behavior as the in-memory adapters, including:

- concurrent identical fragment admission and conflicting-winner rollback;
- inventory round trip and unknown-field/tamper rejection;
- epoch-zero creation and current/historical attachment;
- same-transition retry versus different stale-transition conflict;
- Root commit and progress-only commit transaction shapes;
- Root-outbox ordering and exactly-once row identity;
- expected-epoch removal and commit-after-removal conflict;
- restart recovery with the same environment identity.

Add database-specific fault injection around every transaction boundary. A
happy-path integration test does not establish the crash and retry contract.
