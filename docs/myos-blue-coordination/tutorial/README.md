# How the proposed processing model works

> **Design baseline:** 15.12 · **Status:** synchronized with Phase1/2 handoff, 2026-09-06

This is a beginner's guide to the proposed MyOS + Coordination proof of concept. It explains the
logical behavior of the integrated POC, not a claim that the whole application is already running. You do not need to
know the Java API, the database schema, or the specification vocabulary.

The libraries and PostgreSQL host foundation are implemented and have passed their scoped tests.
The next phase connects them into the general MyOS processing path and measures the result.
The default MyOS app still uses the old path; today's real-library/database bridges cover selected
cases, not every story below. [The implementation summary](../24-phase-1-library-summary.md) and
[Phase3 plan](../25-phase-3-integration-plan.md) give the exact boundary. A diagram is an explanation,
not a test report.

We follow one small story: an Order uses an Agreement. Then we add sharing, missing data, history,
and failures, one at a time. Each chapter is a few minutes long.

The selected design is summarized step by step in [chapter14](14-selected-rules.md).
[Chapter06](06-time-and-order.md) distinguishes
Timeline inputs from internal events; [chapter10](10-crashes-and-resume.md) explains selected
gas-failure recovery; [chapter04](04-one-agreement-many-orders.md) shows how maintained database
indexes find and schedule dependent Orders. One Agreement may have1k/10k/100k Orders: its independent
commit does not wait for all reactions, and their total completion time may grow with their count.

The goal remains **Agreement computes once; independent Orders reuse its processing**. Loading from
PostgreSQL must preserve the observations, event order and semantic gas required by document rules.
A stored final value alone cannot always do that. [Chapter 13](13-equivalence-and-reuse.md) shows the
small examples that exposed this gap and distinguishes physical reuse from changing the algorithm.
Independent publication also changes ownership and rollback relative to the old combined Root; it is
not silently assumed to be a storage-only change. The controlling requirements are in
[the selected processing kernel](../22-processing-kernel.md).

## Choose a reading path

**The short route:** read 1–4, then14, then13 and5–6/10–12. Chapter13 explains the central correctness
question before introducing recovery details.

**The complete route:** read all fourteen chapters. Chapters7–9 explain
the harder history and cycle cases. Their rules guide both existing library tests and the remaining
end-to-end validation; read the verification record for actual passing coverage.

| Chapter | One question it answers |
|---|---|
| [1. One Order](01-one-order.md) | How does an incoming request become a stored change? |
| [2. Document or content?](02-document-or-content.md) | When does an embedded value have its own identity and history? |
| [3. One change, separate commits](03-one-connected-change.md) | How can the source be committed while an Order still waits? |
| [4. One Agreement, many Orders](04-one-agreement-many-orders.md) | What work can we avoid repeating? |
| [5. Read only what is needed](05-read-only-what-is-needed.md) | How can processing work without loading everything? |
| [6. Time and order](06-time-and-order.md) | Why can an available event still have to wait? |
| [7. Joining an existing history](07-joining-an-existing-history.md) | How does an Order catch up with an Agreement? |
| [8. Starting from the beginning](08-starting-from-the-beginning.md) | How do source history and Order history fit together? |
| [9. Cycles and feedback](09-cycles-and-feedback.md) | What if a reaction changes the original source? |
| [10. Crashes and resume](10-crashes-and-resume.md) | What survives a failure, and what must run again? |
| [11. From database to notification](11-from-database-to-notification.md) | How do nested events survive forwarding and external publication? |
| [12. What the POC must prove](12-what-the-poc-must-prove.md) | How will we know the model works? |
| [13. Same behavior, less repeated work](13-equivalence-and-reuse.md) | Why are a final snapshot and event list sometimes insufficient? |
| [14. The selected rules, step by step](14-selected-rules.md) | What exactly happens at initialization, attachment, failure and recovery? |

## Four names, four jobs

- **Timeline:** a durable record of external inputs, with precise timestamps and ordering evidence.
- **MyOS:** the application that receives inputs, reads and writes PostgreSQL, runs work, and recovers
  after failures.
- **Coordination:** the library deciding which document work is allowed next and what must finish
  before later work can run.
- **Contracts:** the processing engine called by Coordination to apply document rules. It owns the
  order of reactions inside one processing operation.

Coordination and Contracts are libraries used by MyOS, not necessarily separate network services.

## How to read the pictures

Every diagram says what its arrows mean. **“Order contains Agreement” is a relationship, not an
instruction to process Order first.** Pictures of processing instead show actions in execution order.

`Order A`, `Agreement`, and short value labels are readable nicknames. Real identities are exact
BlueIds, not these labels. Before/after tables show logical database contents, not a proposed SQL schema.

One distinction matters throughout: **semantic time** is the input order the documents must observe.
**Wall-clock time** is when a worker happens to do the work. A restart may change the second; it must
not change the first.

For engineering detail after the tutorial, use the [architecture map](../README.md). This tutorial
explains the integrated model; full application correctness and performance still need Phase3 proof.
