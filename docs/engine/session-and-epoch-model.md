# Session and epoch model

The engine separates three identities that are easy to conflate:

- `DocumentSessionId` is a stable, non-blank host identity. It names one
  independently managed lifecycle and is never derived from content.
- a Root BlueId identifies immutable Root content at one state;
- an epoch is the monotonically increasing Root-revision number within one
  session.

Two sessions may start with the same Root BlueId. Their immutable bodies can be
deduplicated in the fragment store, but the sessions remain independent. An
event delivered to one cannot advance the other.

## Authoritative current snapshot

`ManagedDocumentSnapshot` is the compact current record. It contains:

- the session ID;
- initial and current Root BlueIds;
- current epoch;
- engine environment identity;
- committed external-order frontier;
- body-free fragment-inventory identity;
- current subscription snapshot;
- lifecycle status, `ACTIVE` or `REMOVED`.

The host should update this record only through the `CoordinationSessionStore`
admission, commit, and removal operations. A plan is current only while its
epoch, Root, subscription digest, and inventory identity all equal this
authoritative record.

## Epoch zero and transition epochs

Successful creation writes both the current snapshot and a
`DocumentEpochSnapshot` for epoch zero. Epoch zero has no prior Root, causing
event, or event-order key. Its inventory and subscription identities prove the
admitted starting state.

A completed PROCESS that commits a new Root creates exactly one next epoch.
Its immutable historical receipt records:

- new and prior Root BlueIds;
- causing event BlueId and external order;
- resulting fragment-inventory and subscription identities;
- Root-level emitted event BlueIds in order;
- total gas and the transition identity.

The resulting epoch must be `expectedEpoch + 1`; an engine transition cannot
skip or rewrite epochs. `engine.epoch(sessionId, epoch)` returns a required
historical receipt or fails if it is absent.

<!-- compile-example:HistoricalEpochReadExample -->
```java
package docs.engine.examples;

import blue.coordination.engine.CoordinationProcessingEngine;
import blue.coordination.engine.api.DocumentEpochSnapshot;
import blue.coordination.engine.api.DocumentSessionId;

public final class HistoricalEpochReadExample {
    private HistoricalEpochReadExample() {
    }

    public static DocumentEpochSnapshot read(
            CoordinationProcessingEngine engine,
            DocumentSessionId sessionId,
            long epoch) {
        return engine.epoch(sessionId, epoch);
    }
}
```

## Progress without a Root revision

Not every completed PROCESS commits a new Root. For a noncommitting result, the
Root BlueId and epoch remain unchanged, and no new epoch snapshot exists. The
authoritative session commit may still advance the external-order frontier and
record terminal event progress. This prevents the same rejected or otherwise
terminal event from being treated as unprocessed while preserving the meaning
of an epoch as a Root revision.

Accordingly, do not use epoch alone as the event-delivery cursor. Persist the
committed frontier and terminal progress in the same session-store transaction
as the resulting session.

## Ordering and concurrency

The host supplies canonical `ExternalOrderKey` tuples. Planning rejects a key
that does not compare strictly after the session's committed frontier. The
request may also carry an `expectedEpoch`; when present, it must equal the
current epoch.

Execution creates a proposal, not a lock. Concurrent proposals may share the
same expected epoch and Root. The CAS also binds the committed frontier,
fragment inventory, subscription digest, environment, and initial document,
so exactly one different transition can win even when neither proposal changes
the Root. Retrying the same transition identity is idempotent and returns
`ALREADY_COMMITTED`; a different stale proposal returns `CONFLICT`.

## Removal

`removeDocument(sessionId, expectedEpoch)` is revision-bound. It returns
`REMOVED`, `ALREADY_REMOVED`, `NOT_FOUND`, or `CONFLICT`. Removal changes the
session lifecycle to `REMOVED`, after which new processing commits conflict.
It does not delete epoch history or immutable fragments. Physical retention and
garbage collection are host policies and need their own reachability and audit
rules.

## Environment identity is part of state

Environment identity prevents persisted state created under one Language
version/registry, Contracts registration/gas package, Coordination
registration, BEX runtime/gas manifest, provider evidence domain, ordering
policy, subscription policy, fragment profile, or quota manifest from being
processed as though it belonged to another. Construction also rejects
`BlueContracts` and `DocumentProcessor` inputs whose public Language runtime
fingerprints differ. Treat an environment change as an explicit migration or
new session decision. Do not update the persisted value merely to bypass the
check.
