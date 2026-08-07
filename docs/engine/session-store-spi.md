# Session-store SPI

`CoordinationSessionStore` is the compact authoritative persistence boundary.
It does not store immutable fragment bodies. It stores current session state,
immutable epoch receipts, committed event progress, Root outbox entries, and
transition idempotency evidence.

The interface has five operations:

```text
findSession(sessionId)
findEpoch(sessionId, epoch)
admit(documentAdmissionCommit)
commit(coordinationAtomicCommitPlan)
remove(sessionId, expectedEpoch)
```

`findSession` and `findEpoch` return optional values at the SPI boundary. The
engine's `session` and `epoch` convenience methods require a value and fail if
it is absent.

## Admission transaction

`admit` receives a `DocumentAdmissionCommit` whose registration, proposed
current snapshot, epoch-zero receipt, and fragment inventory are already
cross-validated. For a newly created session, a durable adapter must atomically
insert:

- the current `ManagedDocumentSnapshot` at epoch zero;
- its `DocumentEpochSnapshot` zero receipt;
- empty Root outbox and terminal-progress state, if modeled as rows;
- any idempotency/index state required by the host.

An `ATTACH_EXISTING` request against an absent session conflicts.
`CREATE_ONLY` conflicts with an existing session. The normal attach path
recognizes the same current Root or a Root in retained epoch history and
returns the authoritative current snapshot. Unknown content must not overwrite
the current session.

The reference in-memory adapter returns `VERIFIED_LINEAGE_REQUIRED` for an
unknown unclaimed state and `FORK_REQUIRED` for an unknown claimed future
state. It does not implement special `FORK_FROM_EXACT_STATE` branching. A
durable adapter must document and test any stronger fork behavior separately.

## Commit transaction

`commit` receives a fully bound `CoordinationAtomicCommitPlan`. In one local
database transaction, a durable adapter should:

1. check the idempotency key `(session_id, transition_identity)`;
2. lock or conditionally update the active session whose current epoch, Root,
   initial document, environment, committed frontier, fragment inventory, and
   subscription digest equal the plan's expected state;
3. replace the current session with `resultingSession`;
4. insert `resultingEpochSnapshot` only when it is non-null;
5. append `rootOutboxEventBlueIds` in their declared order;
6. record terminal progress for the delivered event;
7. record the committed transition identity and outcome;
8. commit all of those changes together.

The exact schema is host owned, but the atomic grouping is semantic. A crash
must not expose a new current session without its corresponding epoch receipt,
outbox entries, progress, and idempotency record.

## CAS and idempotency outcomes

- `COMMITTED` means this call applied the transition.
- `ALREADY_COMMITTED` means the same transition identity for the same session
  was applied earlier. `CommitOutcome.committed()` is true for both statuses.
- `CONFLICT` means the active session was absent, removed, or no longer matched
  the complete expected state.

Check idempotency before rejecting a retry as stale. Repeating the exact
transition after its first successful commit must return
`ALREADY_COMMITTED`, not `CONFLICT`. Conversely, never deduplicate solely by
event BlueId or Root BlueId; the engine provides the transition identity that
binds the complete proposal.

## Noncommitting PROCESS results

A valid commit plan can preserve the Root and epoch. In that case the adapter
still commits the resulting session frontier, terminal event progress, and the
transition idempotency record, but it inserts no epoch receipt and appends no
Root outbox events. The expected prior frontier must participate in the same
conditional update. This is why a CAS cannot be reduced to “update only when
the Root changes.”

## Removal transaction

`remove(id, expectedEpoch)` is conditional on the current epoch. It returns
`NOT_FOUND`, `ALREADY_REMOVED`, `CONFLICT`, or `REMOVED`. A successful removal
marks the current snapshot `REMOVED` without erasing history. Later commits for
that session conflict.

If a host supports restoration or hard deletion, those are additional host
operations, not semantics implied by this SPI.

## Persistence rules

Use exact, closed serialization for session and epoch values. In particular:

- preserve `ExternalOrderKey` component types and tuple order;
- preserve Root outbox order;
- enforce one current row per session and one receipt per `(session, epoch)`;
- make the environment identity immutable for a session;
- enforce uniqueness for `(session, transition identity)`;
- retain enough epoch history to recognize the attachment behavior the host
  claims to support.

The fragment store and session store have different consistency roles. Their
calls are deliberately sequenced but not represented as one distributed
transaction. See [atomic commit](atomic-commit.md) and
[database host integration](database-host-integration.md).
