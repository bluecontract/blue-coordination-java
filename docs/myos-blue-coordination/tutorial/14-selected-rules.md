# 14. The selected rules, step by step

> **Revision:** 15.12 · **Status:** selected design; examples are not executed test results

[Tutorial map](README.md) · [Previous: same behavior, less repeated work](13-equivalence-and-reuse.md)

This chapter gives the short answers selected for the POC. The engineering definition is
[the processing kernel](../22-processing-kernel.md). Phase1 implements the library rules; Phase2
supplies the host foundation. The general integrated path and its measurements remain Phase3 work.

## 1. Agreement does not begin when the first Order appears

Agreement starts with counter0. Its Timeline contains a change to5 at T15.

1. Agreement's authored value and fixed processing environment define its initialization/history.
2. If MyOS first calculates it at T10, it later processes T15.
3. If MyOS first reconstructs it at T20, it reconstructs the same T15 change.
4. Which Order or worker first requested Agreement does not change that history.

An Order's history choice is separate:

| Order's choice | What the Order receives |
|---|---|
| FULL_HISTORY | The selected earlier Agreement history, processed in order. |
| FROM_NOW at logical T20 | The exact Agreement view at that boundary, then later changes; not T15 as an old event. |
| FROM_FRONTIER | The explicitly selected boundary and following history. |

“Now” is the accepted logical boundary, not the worker's clock. If the required exact view is not
available yet, MyOS waits for it. It does not substitute whichever version is cached.

Source initialization and each Order's consumption have separate budgets, cold or warm. If source
work costs80 and parent work20 with separate limits90, both fit. A cache cannot change that answer.
The canonical source charge is settled once when its result becomes authoritative; rebuilding its
content does not settle it again. A failed Order cannot erase an already authoritative Agreement.

## 2. Creating an embedding does not secretly finish its whole history

Order creates `/agreement` with an authored-from-origin history selection.

1. Initialization and the selected initial view become available inside that creating operation.
2. A permitted direct read sees that installed view, for example counter0.
3. Order's field `counterB`, maintained by reactions to old Agreement events, is still unset.
4. After the creator commits, the selected historical source epochs run as ordered operations.
5. When the T15 event is processed, its reaction sets `counterB=5`.

The creator never waits for its own commit and never sees a pretend caught-up `counterB`. A
FROM_NOW@T20 attachment instead installs its declared boundary view5, without replaying T15's event.
Already frozen events do not acquire a new recipient merely because the handler added another path.

## 3. Two placements can briefly show different views inside one operation

Order has `/left` and `/right`, both pointing to Agreement0. Agreement changes to1.

1. Update `/left` to1 and complete its synchronous update reactions.
2. A `/left` update handler reading `/right` sees0.
3. Update `/right` to1 and complete its update reactions.
4. A subsequent event, after both updates, sees1 through either path unless authored work changed it.

All of this belongs to one complete Order operation. A database page cannot split it. If step1
removes/replaces `/right`, step3 must not overwrite that replacement as though it were the old path.

The source stores a record of its observable steps, not only its final state. Orders reuse those
steps while running their own reactions in the proper queue order. This is why a result may be
calculated once without losing intermediate observations or replaying the source's business code.

## 4. Gas failure finishes an attempt, not the document's life

Agreement has r0=0, r1=1 and r2=2. Order's r1 reaction exceeds gas.

| Point | Order's actual embedded view | What is recorded |
|---|---:|---|
| Before r1 | 0 | Earlier successful state. |
| After failed r1 | 0 | Terminal gas failure; no new successful Order epoch. |
| Inside r2: alignment | 1, still tentative | Ordinary containing update0→1, including its reactions. |
| Inside r2: source steps | 2, still tentative | r2's own1→2 work and original events. |
| After successful r2 | 2 | One complete successful Order result. |

Alignment and r2 share one budget and rollback. If either fails, the authoritative view remains0.
An r2 event before its first update reads1. A separate local request between r1 and r2 reads0.

With two sources, alignment is not an all-references-first stage. If Root still has A0/B0, its
own early Handler reads0/0. At A's first recorded Entry, align A0→1 and process A1→2; both update
callbacks still see B0. B aligns only at B's Entry. These actions and any emitted events keep their
normal queue sites. [Chapter10](10-crashes-and-resume.md#what-if-two-sources-need-alignment) walks
through this case; evidence-loading order cannot change it.

We do not replay r1's source events or resume its failed queue. Alignment can nevertheless invoke
the same update handler as **new r2 work**. It may fail again at1, even if r2 would eventually end at2.
Later canonically due detach/repair can proceed after earlier operations have terminal outcomes.

The first failure does not discard the remainder of an import page or range. Later successful
operations can have business effects. A fixed attachment lane finishes when its required operations
are accounted for, including failures; it does not report all-success if some failed. A missing
provider response or missing content is different: it remains a wait, not a consumed failure.

The same terminal-progress rule applies to a library-certified deterministic `RUNTIME_FATAL`,
including a rejected atomic-scope gas admission. An arbitrary exception, timeout, cancellation or
unknown commit is not such a result and cannot consume an import. Failed initialization still
provides no usable initialized source.

Gas belongs to one logical operation, not an entire import history. A failed operation creates no
successful epoch; a coupled operation can advance several documents. MyOS account/document quotas
are separate: if the account has 50 left, an operation needing 80 under its fixed limit of 100 waits.
It does not fail semantically under an invented limit of 50. A pause preserves pending work, and a
retry of unfinished work does not reset its logical meter.

A document can generate an endless series of individually finite operations, such as repeatedly
reattaching and importing history. MyOS can pause that series; the POC adds no inherited cross-operation
gas allowance. Pausing does not authorize a later repair entry to skip unresolved earlier work.
General interruption/cancellation of that work is outside the first POC, unlike ordinary recovery
after an earlier operation has reached its defined terminal outcome.

## 5. Dependencies have a direction

Order embeds Agreement. Order uses Timeline TO; Agreement uses TA.

- Order needs the relevant Agreement/TA history before its dependent work can run.
- Agreement does not need TO merely because Order watches it.
- If TO is unavailable, Agreement can keep processing its own complete TA inputs.

Now Portfolio actually embeds10000 Orders. Those are real forward dependencies, so Portfolio needs
their relevant completeness. MyOS maintains indexed membership and shared progress summaries to
avoid rescanning10000 documents per entry. Correctness does not permit dropping a quiet member.

If Agreement also embeds Order, there is a returning path. That strongly connected group owns one
complete workflow, shared gas and rollback. Creating a new return path first requires the library's
gas-admission check; it is not a later fresh-budget job.

There are two different cycle-creation tests. The existing dynamic case starts with A→B and adds
B→A. The new case starts with unrelated A and B receiving the same entry: A adds A→B, which alone
keeps them independent. Before B can add the reverse edge, check their canonical gas prefixes:

- With a fixed limit of 100, A having spent 60 and B having spent 30 can join for an illustrative
  admission charge of 1. The joined operation has 9 left; later exhaustion rolls back both.
- With A at 60 and B at 50, B first pays the check's cost of 1, then the prospective total 111 cannot
  fit. Reject the join before installing B→A. B fails with the certified runtime reason
  `AtomicScopeGasAdmissionFailure` and 51 actually admitted gas; A may commit after accounting for
  that failure. Do not charge 111, invent 100 units of usage or retroactively roll A back.

The canonical execution order decides this—not the first worker or a cached result. An admitted
join stays admitted through the attempt; earlier execution/event identities are not renamed when
the final atomic scope is settled. The same entry alone does not join independent documents, and
one-way observing Orders remain independent. A genuinely later cycle does not reopen earlier commits.
If an entire tentative attempt relied on a producer that ultimately failed, discard that whole
conditional attempt, including any join, gas or failure status; do not subtract selected patches.
For conditional AC61 plus B51, B keeps its112/100 rejection while AC does not settle. A/C's genuinely
required own seeds can then run from committed state for5 each against B's failure. Nothing already
published is undone, and B's diagnostic is not recalculated from those new independent totals.
See [chapter 9](09-cycles-and-feedback.md) and the [kernel](../22-processing-kernel.md) for this boundary.

One original event does not travel around a cycle forever: its route never repeats a DocumentId,
including the emitter. A's E can reach B; B's new F can reach A. New emissions are distinct work under
the same gas meter, so a genuine business loop still exhausts gas and rolls back.

## 6. What MyOS commits and schedules

MyOS uses consistent indexes to find due work; Coordination determines its legal order and result.

| Kind of completed work | Business lineages in its atomic database publication |
|---|---|
| Proved no-delivery bookkeeping | Zero: no invented business operation or epoch. |
| Independent Agreement update | Normally Agreement alone. |
| Genuine coupled A/B workflow | Every required existing member, together. |

The receipt describes every required state/progress/publication change. It cannot record half a
coupled success. Independent Orders are scheduled from durable discovery information afterward;
Agreement does not wait for all their rows or results. External notifications may be delivered later.

For1,000,10,000 or100,000 Orders, real reactions and total propagation time can grow. MyOS must keep
memory, transactions and concurrency bounded, schedule users fairly, and resume without lost work.
This is a scalability requirement, not a promise that every size finishes in a fixed few seconds.
