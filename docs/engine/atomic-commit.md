# Atomic commit

The engine deliberately separates deterministic execution from authoritative
state advancement. `execute(plan)` returns a `CoordinationTransition` and does
not mutate the session. `commit(transition)` admits immutable physical output
and then asks the session store to apply one compact revision-bound CAS.

“Atomic commit” in the API name refers to the session-store transaction encoded
by `CoordinationAtomicCommitPlan`. It does not claim that an arbitrary fragment
database and session database participate in one distributed transaction.

## What the commit plan binds

The immutable plan contains:

- session ID, expected epoch, Root, initial document, environment, committed
  frontier, fragment inventory, and subscription snapshot;
- resulting epoch and Root;
- event BlueId and external order;
- the exact `DocumentProcessingResult` and `PlatformCommitCompanion`;
- fragment and subscription transitions;
- ordered Root outbox event BlueIds;
- transition identity;
- resulting current session;
- an optional resulting epoch receipt.

Construction validates those relationships. The companion must agree with the
expected Root, event, order, and Root-commit decision. A committing result must
advance exactly one epoch and its calculated document BlueId must be the
resulting Root. Root outbox IDs must exactly equal the PROCESS result's Root
events. An epoch receipt exists if and only if PROCESS committed a Root.

## Engine commit sequence

For a current transition, the engine performs this sequence:

1. Validate every static plan/result/commit binding, then preflight the current
   session lifecycle, epoch, Root, frontier, inventory, and subscriptions. The
   session store remains the
   authoritative validator of the complete CAS proposal.
2. Return the session store's `ALREADY_COMMITTED` or `CONFLICT` decision before
   immutable output writes when the preflight already proves this proposal
   cannot win.
3. If the result has new bodies, verify and admit only that delta with one
   all-or-nothing `putAllIfAbsent` batch; then read every winner back.
4. Idempotently persist the resulting body-free fragment inventory.
5. Invoke `CoordinationSessionStore.commit` exactly once with the compact
   authoritative plan.

Steps 2 and 3 happen before the session CAS. A concurrent CAS loser can
therefore leave verified content-addressed bodies and an inventory that no
current session references. This is safe because those writes are immutable
and idempotent, but it is not rollback. Retention or garbage collection is a
separate host concern.

## Session-store atomicity

Within `CoordinationSessionStore.commit`, current-session replacement, optional
epoch insertion, Root outbox append, terminal progress, and transition
idempotency must commit as one transaction. The condition is the active
session plus the exact expected epoch, Root, initial document, environment,
committed frontier, fragment inventory, and subscription snapshot. Epoch and
Root alone are insufficient because a progress-only commit intentionally
preserves both.

The status meanings are:

- `COMMITTED`: this call won and applied the proposal;
- `ALREADY_COMMITTED`: the identical session/transition identity committed
  earlier, so the retry is successful;
- `CONFLICT`: the expected state is no longer current or active.

Applications should treat both first two statuses as committed and should not
publish a second outbox copy on `ALREADY_COMMITTED`.

An ambiguous host retry repeats the exact immutable transition, not a rebuilt
plan with a patched revision:

<!-- compile-example:CasRetryExample -->
```java
package docs.engine.examples;

import blue.coordination.engine.CoordinationProcessingEngine;
import blue.coordination.engine.api.CommitOutcome;
import blue.coordination.engine.api.CommitStatus;
import blue.coordination.engine.api.CoordinationTransition;

public final class CasRetryExample {
    private CasRetryExample() {
    }

    public static CommitOutcome retryExactTransition(
            CoordinationProcessingEngine engine,
            CoordinationTransition transition) {
        CommitOutcome outcome = engine.commit(transition);
        if (outcome.status() != CommitStatus.COMMITTED
                && outcome.status() != CommitStatus.ALREADY_COMMITTED) {
            throw new IllegalStateException(
                    "The exact transition lost its session CAS");
        }
        return outcome;
    }
}
```

## Root commits and progress-only commits

A Root-committing PROCESS installs the new Root inventory, advances epoch by
one, updates subscriptions, writes an epoch receipt, and appends exactly the
Root `ProcessResult.events` to the Root outbox.

A noncommitting PROCESS keeps the Root, inventory, subscriptions, and epoch
unchanged. It still advances the committed frontier and terminal progress in
the authoritative CAS. The prior frontier is part of that CAS, so only one of
two competing progress-only proposals from the same snapshot can win. It
writes no new epoch receipt. This distinction keeps retry state durable
without pretending a Root revision occurred.

## Crash and retry reasoning

- Crash before fragment admission: retry execution or commit; no authoritative
  session state changed.
- Crash after immutable admission or inventory persistence but before the
  session CAS: retry the same transition. Fragment operations are idempotent.
- Ambiguous session commit result: retry the same transition identity. A
  correct store returns `ALREADY_COMMITTED` if it previously won.
- Different transition wins first: the stale proposal returns `CONFLICT`; plan
  again from the authoritative session.

Never resolve a conflict by changing the expected epoch or Root inside the old
plan. Replanning is required because delivery evidence, subscription state,
fragment deltas, gas, and outbox output were all bound to the earlier state.

## Observer and memo boundaries

Lifecycle observers are failure-isolated and non-semantic; observer failure
cannot roll back or alter a result. Whole-transition memoization can avoid
repeat deterministic work only for the exact key. Neither observer delivery
nor memo storage replaces session-store idempotency or a durable Root outbox.
