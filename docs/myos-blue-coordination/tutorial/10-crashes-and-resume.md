# 10. What survives a crash?

> **Semantics:** r15.12 · **Status:** library and storage foundation verified; general application integration is Phase3 · **Updated:** 2026-09-06

[Previous: cycles and feedback](09-cycles-and-feedback.md) · [Tutorial map](README.md) · [Next: notifications](11-from-database-to-notification.md)

Return to the ordinary approval example. Agreement commits active, then Order consumes the retained
receipt and commits ready to proceed. These are separate complete invocations, not halves of one call.

The libraries and PostgreSQL recovery primitives now implement these boundaries. The full default
MyOS application does not yet use the general adapter. Read the story below as the integration
contract, supported by actual library tests and small PostgreSQL witnesses—not as a claim that
every Agreement/Order application is already deployed. [The readiness record](../implementation/phase-1-2-readiness.md)
distinguishes those evidence levels.

## Before and after each commit are different worlds

| Crash point | What is authoritative in PostgreSQL? | What recovery does |
|---|---|---|
| After recording the request | input and unfinished source work | process the source |
| After tentative Agreement change, before source commit | old Agreement; no new source receipt | restart that source invocation |
| After Agreement commit, before Order begins | new Agreement, retained receipt and durable discovery authority; old Order | discover/resume the required Order import, without rerunning Agreement |
| During tentative Order reaction | committed Agreement; old Order and unadvanced cursor | restart only the Order invocation |
| After Order commit | Agreement and Order histories; consumed cursor and any outgoing obligations | reuse the result, never append a duplicate reaction |
| During an uncertain database response | that local commit's outcome is unknown to the worker | reconcile its exact durable completion before deciding to rerun |

“I did not receive a success response, therefore nothing committed” is unsafe. The database may
have committed and lost the response. The completion record identifies the exact local operation.
No failed consumer attempt rolls back a source receipt or another consumer's committed result.

## Catch-up is ordinary durable progress

An already connected Order can be behind by two source receipts. A newly attached Order can also
owe historical processing, selected under its attachment policy. Both preserve exact progress and
save only complete logical results with their cursor and outgoing obligations. The example below
assumes the reference permits two separate operations; a storage receipt cannot create that boundary.
Their different activation/history selection must not be confused.

If the first import committed and the second was tentative, recovery keeps the first and restarts
only the second. No private Contracts stack, half-used gas meter or partly delivered queue is saved.
The source history is not recalculated to recover either consumer step.

This boundary has a real combined test. M1 is the first historical reaction; M2 is the next one.
M2's exact selection is known, but a fragment needed to execute it is missing. M1 still commits its
state, cursor and publication record. A separate JVM restores that commit, rejects corrupt M2 data,
receives the original fragment and completes M2 once. It neither repeats M1 nor advances an unrelated
Timeline cursor. In a separate control, missing evidence needed to choose the current operation
keeps it waiting: "missing execution data for later work" is not "we do not know what comes next."

The healthy path should run the next eligible action promptly with a useful warm working set.
Independent Orders may continue while one waits. Durable local boundaries make this safe; they
are not a reason to insert deliberate long pauses. The POC measures commit overhead and total
fan-out completion separately from the first source commit. A large fan-out may take longer as
required work grows; bounded memory, transactions and concurrency plus fair work selection protect
other documents. Millions of unrelated documents must not turn every wakeup into a system-wide scan.

## Waiting also needs recovery

Suppose processing needs exact content X:

1. A worker looks for X and does not find it.
2. Another task stores X before the worker has registered its wait.
3. The worker records “waiting for X”.

If recovery relies only on a one-time notification, the work may sleep forever. MyOS therefore
maintains durable indexed needs and ready work, atomically closes the register/recheck race and uses
indexed dependency wakeups. Bounded reconciliation of the relevant unfinished work recovers from
lost notifications; it is not repeated scanning of every document or every blocked user document.
The same principle covers a retained source receipt whose consumer discovery has not yet finished:
an in-memory fan-out task is not the durable authority. The host maintains consistent relationship
and work indexes; its concrete queues, partitioning and optimization choices may vary.

The foundation has these bounded recheck methods; the continuously running service that calls them
together with ingress and publication is Phase3 wiring. A capacity problem is also recorded durably,
with a repair/release condition. Immediately reclaiming the same impossible work is a busy loop,
not recovery. Replenishing a quota does not magically repair missing evidence or a different limit.

## Unavailable is not invalid

A missing provider response, timeout or cancelled worker pauses the affected work. It neither means
the Order's rule failed nor permits that Order to skip due history. Independent lineages can still
progress when their own causal prerequisites are proved.

A deterministic Contracts failure preserves that invocation's exact result and rollback. It does
not undo earlier commits, count as successful consumer processing, or silently erase obligations.
Whole-cause completion depends on accounted causal work and a closed delivery frontier, not the order
in which workers finish or an empty local queue. Such aggregate reporting is host/test functionality,
not a required additional Contracts operation, global commit receipt or barrier for Agreement.

## A gas failure is not permanent document death

An existing SDK test describes a useful external-input control: `startLoop` exhausts gas and leaves
the business state and epochs unchanged. A separate `detach` input breaks the loop; a new
`startLoop` input then succeeds. The failed invocation stays failed, but it does not permanently
disable the documents. The affected SDK/library regressions now verify this control; it is not
permission for the host to manufacture a repair or silently skip due inputs.

The important distinction is **handled input versus successful state change**. MyOS can retain a
terminal failure result, keep business state and epoch unchanged, and move on to later canonically
ordered inputs. Those later inputs can repair the document. The failed operation did not secretly
succeed, and retrying its identical gas-exhausting computation is not the repair.

For managed history, separate two records: the last successfully imported source view and the last
delivery terminally handled, including failure. The implemented library continuation is:

1. Agreement has independently committed r1:0→1. Order's r1 reaction exceeds gas. Keep Order at its
   real r0/value 0 rollback state with no new epoch; durably record r1's failed delivery disposition.
2. Agreement commits r2:1→2. Coordination verifies its original contiguous r0→r1→r2 history and r1's
   disposition, then applies r2 from Order's actual rollback state.
3. Inside the one r2 operation, align Order's actual reference0 to r2's exact before-value1 through
   an ordinary containing update; then replay r2's1→2 work. Both phases share one gas and rollback.
   Original r1 events are not replayed, but alignment can cause new Order update reactions. An early
   r2 event reads1; an earlier local request between r1 and r2 still reads actual0.
4. If r2 succeeds, publish its complete result and successful view. If it also exceeds gas, retain
   rollback state again and account for r2. A later D30 detach then runs when canonically due.

Normal successful catch-up still preserves its operation boundaries, and r2 retains all required
intra-operation observations. D30 cannot erase unresolved earlier work or overtake r2 if r2 is merely
waiting for data. No consumer failure undoes an independently committed Agreement; a genuinely
coupled cycle instead retains its own complete shared-gas rollback scope.
Alignment can fail again when moving to1 triggers the same faulty handler, even if r2 would finish
at2. Continue canonical selection and record each outcome; do not abandon a SQL page/history range
after its first failure. Later successful operations may have business effects. A fixed attachment
lane finishes when its required steps are terminal, reporting failures honestly rather than claiming
all-success. Missing evidence is still a wait, not a shortcut to later work.
This selected failure rule applies to `GAS_LIMIT_EXCEEDED` and library-certified deterministic
`RUNTIME_FATAL`. An arbitrary exception, timeout, cancellation or unknown commit does not qualify
and cannot consume an import. Failed initialization still provides no usable initialized source.
Other statuses retain their exact operation-kind disposition; they are not implicitly consumed.

Actual managed-import and cold-receipt tests now cover this path separately from the live external
`detach` control. The general MyOS importer still has to map that authority. If two consumers had
different last successful views of Agreement, each retains its own view after failure; one shared
latest Agreement value cannot stand in for both. A source failure has its own verified failure
record, not a pretend successful program. Metadata-only progress is different again: it creates no
business operation or gas charge.

## What if two sources need alignment?

Root embeds A and B. Both sources reached1, but Root's earlier reaction failed, leaving its own
references at0/0. Their next shared input changes both sources1→2. Suppose the fixed logical order
is Root's own direct Handler, then A's steps, then B's steps:

1. Root's early Handler reads0/0: nothing has aligned yet.
2. When A's recorded steps begin, align A0→1, including Root's update reaction. That reaction sees B0.
3. Apply A's1→2 step. Root's next A-update reaction still sees B0.
4. Only when B's recorded steps begin does B align0→1 and then advance1→2.

We do not first bring every reference up to1. That would change Root's reads and possibly its
decisions. Alignment happens at that producer's first canonical **Entry** site, inside the same
operation and queue—not when MyOS happens to load its evidence. If A/B alignment emits GA/GB and
their normal steps emit EA/EB, the simple no-other-emissions queue is GA,EA,GB,EB. A shared source
reached through a diamond is not executed twice, while its distinct eligible placements still get
their own updates. A placement removed or replaced earlier cannot receive the old alignment.

**Check your understanding:** after Agreement commits, must recovering Order A run Agreement's
approval rule again? No. It consumes the retained source receipt and retries only its own unfinished work.
