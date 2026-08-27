# Historical and retained catch-up

This page separates three temporal surfaces that must not be conflated:

1. the published `3.0.0-rc.4` Contracts SDK behavior;
2. the staged, unpublished `3.0.0-rc.5` retained managed-epoch source
   profile; and
3. the earlier low-level compatibility model retained for migration history.

The rc.5 profile is non-production and is not present in the Maven Central
rc.4 JAR. Its normative source semantics are in
[Retained managed-epoch catch-up](retained-managed-epoch-catch-up.md).

## Published rc.4 Contracts SDK

Top-level document and closure admission supports full-history and
exact-frontier eligibility over retained in-memory provider evidence.
Admission publishes initialized epoch-zero documents as `READY`; it does not
drain historical work inside `admit(...)`. Call `processing().drain()` after
admission to process eligible retained Timeline entries. History already past
the environment's completed frontier is not resurrected by a later admission.

Operation-created managed documents are narrower. They support only genuinely
new `FROM_NOW` lineages supplied as exact `ManagedDocumentDraft` evidence. The
published rc.4 draft API does not attach an independently existing historical
document, import a caller-authored draft epoch, or apply an already processed
source lineage's missing epochs through a new occurrence.

Those statements remain the historical rc.4 artifact boundary. The source
work described below does not retroactively add APIs or behavior to rc.4.

## Staged rc.5 retained managed-epoch profile

The rc.5 source consumes an invocation-owned immutable Blue
Language/Contracts `3.1.0-rc.23` Maven stage. That stage starts from the exact
published `3.1.0-rc.22` baseline and adds complete managed-transition receipts.
It is manifest-pinned on every build invocation; Maven Local, sibling composite
substitution, mutable checkout input, and remote fallback for `blue.language`
are forbidden.

### Selection positions

When an operation places an exact value at an effective `Process Embedded`
path, Coordination can match four positions in an already managed source
lineage:

| Position | Meaning | Missing suffix applied to the consumer occurrence |
| --- | --- | --- |
| authored initial (`-1`) | exact value before the one original INITIALIZE | epoch `0` through the admitted source frontier |
| initialized epoch zero (`0`) | exact result of that original INITIALIZE | epoch `1` through the frontier |
| retained epoch (`e < current`) | one immutable committed source revision | epoch `e + 1` through the frontier |
| current head | exact current source state | none; activation is already current |

One unambiguous match is automatic. If a BlueId names more than one lineage,
the operation fails with `AMBIGUOUS_MANAGED_LINEAGE`. If one selected lineage
has the same non-current BlueId at several epochs, it fails with
`AMBIGUOUS_MANAGED_EPOCH` unless the caller supplies a `ManagedEpochSelector`
with the source `DocumentId`, source epoch, expected BlueId, and target path.
The selector disambiguates retained proof; it cannot invent a source history.

### Immutable source evidence, not source replay

Every committed source revision has one self-verifying `ManagedEpochReceipt`.
It binds document and epoch, before/after identity, complete after-document,
original cause and source order, Contracts transition and platform commit
identities, admitted processing gas, and the complete ordered Root event
occurrence sequence.

Event occurrence identity is independent of event value identity. Equal event
values remain separate occurrences, and a transition with equal before/after
BlueIds remains a real `EVENT_ONLY` epoch. Catch-up must not reconstruct this
evidence from the public-event projection.

The source document is never initialized again, never receives a replayed
Timeline entry or Operation/handler/PROCESS call, never republishes its public
outbox, and never advances because another document catches up. Each missing
immutable source receipt is instead supplied as a typed managed-revision cause
to ordinary closure processing for the consumer occurrence. Bounded cycles use
the same Contracts cyclic processor and gas/convergence rules; Coordination
does not create a second parent-recursion or SCC engine.

### Plans, occurrence cursors, and readiness

One historical occurrence activation opens one
`ManagedOccurrenceCatchUpPlan`. Its cursor is specific to the consumer,
canonical path, and activation generation. Two paths to the same source and
several parent documents therefore advance independently while sharing the
same immutable source receipts. Detach/re-add or retarget creates a new
generation and never reuses the retired plan's cursor.

Plans created by one graph-changing cause belong to one
`ManagedCatchUpBarrier`. The consumer graph change commits before the suffix
applications, so the consumer temporarily has different committed and READY
heads. While any plan remains active, ordinary `snapshot()` and `document()`
reads expose only the last READY head. Host diagnostics may inspect the
committed head, readiness, plans, and barriers through `AdvancedCoordination`.
The consumer becomes READY only after every live plan reaches its required
frontier or an incomplete plan is deterministically cancelled because its
occurrence retired. Completed plan snapshots remain unchanged historical audit.

An open plan has a moving frontier. A genuine new source epoch committed while
catch-up is active extends `requiredThroughSourceEpoch` atomically. The
consumer must apply that epoch before promotion to READY, and a direct consumer
Timeline entry cannot overtake the barrier. Unrelated lanes may still progress.

### Bounded continuation, failure, and response loss

`processing().drain(new DrainBudget(...))` can pause between selected entries
or committed PROCESS transitions. The next source cursor and application
receipts remain in the live in-memory store, so continuation begins at the
exact next epoch. The budget cannot preempt one frozen PROCESS or INITIALIZE
invocation and is not a wall-clock timeout.

The retained external/managed lane turn prevents a continuing finite source
stream from starving catch-up under repeated `DrainBudget(1, 1)` calls. Both the
source frontier and cursor advance; once finite traffic stops the consumer
eventually becomes READY. An unbounded stream remains `CATCHING_UP`, and direct
consumer work remains behind the barrier.

A missing required receipt produces `WAITING_FOR_HISTORY`; malformed or
mismatched receipt/transition evidence produces `BLOCKED`. Both retain the
same cursor and publish no consumer state. A non-committing processor result
likewise publishes no consumer revision, cursor, or application receipt. One
failing consumer is excluded for the rest of that drain so independent
consumers can continue.

A processor-suspended application may expose several exact-content resource
demands. Its immutable attempt retains the bounded automatic retry count and a
typed resolution status for each demand identity. A host may register verified
content and retry the same work; it does not push content directly into the
consumer. Ambiguity and selector mismatch remain semantic failures rather than
missing-content waits.

On success, `ManagedEpochApplicationReceipt` binds the work, plan, immutable
source receipt, Contracts invocation/result, commit companion, committed
consumer revision, and resulting cursor. If publication commits and response
delivery is lost, reconciliation finds that receipt and does not call PROCESS
again.

This is durability only inside one surviving in-memory engine. A fresh
`BlueCoordination.inMemory()` process has none of the journal, document
history, receipts, plans, barriers, cursors, graph/work indexes, routes/outbox,
or provider-completeness state. Cross-process recovery remains unsupported
until a durable host persists and validates that whole atomic boundary.

## Earlier low-level compatibility model

The earlier temporal coordinator remains migration history, not additional
`BlueCoordination.inMemory()` capability and not the implementation contract
for rc.5.

That model attached one exact parent occurrence at an exclusive canonical
cutoff `T`. A verified frontier `F` selected `F < entry < T`; the attachment
entry itself was not delivered to the activated child. Its completeness
identity covered binding generation, active subscription intervals, source
contributions, checkpoint domains, dependency evidence, Channel membership,
and compiled routes rather than an unrelated business-state BlueId.

Several children introduced by one parent transition shared an extendable
barrier. Initialization and historical work were ordered by source order,
depth, canonical path, `DocumentId`, and epoch. A parent became READY only when
children were initialized or terminated, eligible intervals were proven
complete, nested barriers completed, child epochs were terminally processed,
and occurrence cursors agreed through the cutoff.

Its work-bounded continuation retained the open entry/frame, graph generation,
barrier, document-local cursors, and committed receipts. Its bundled provider
proved only a revisioned in-memory journal and distinguished eligible,
complete/empty, unavailable, and invalid evidence. Reconstruction around those
surviving stores was not fresh-process recovery. These historical claims remain
useful for migration analysis but do not extend the published rc.4 SDK or grant
release authority to rc.5.
