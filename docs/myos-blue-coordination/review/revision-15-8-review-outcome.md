# Revision15.8: explicit processing units and an indexed, scalable host

> **Date:** 2026-09-06 · **Status:** DESIGN REVIEW; implementation requires separate user approval
> [Package](../README.md) · [Semantic authority](../21-semantic-equivalence-and-source-reuse.md) · [Retained source evidence](revision-15-7-source-evidence.md)

## Applied decisions

1. **Timeline input versus internal event.** The simple two-input/epoch example yields Root reads1
   then2. E1/E2 emitted by ONE input share the invocation FIFO: Parent can set1/emit F1, then set2/
   emit F2, before F1 is dequeued. A document read at F1 can see2 while its immutable payload remains1.
   Internal emissions do not independently create epochs. Tutorials06/13 and the paired fixtures
   now make the assumptions explicit.
2. **Selected managed gas-failure continuation.** Record GAS_LIMIT_EXCEEDED as a terminal delivery
   outcome without a successful consumer epoch/view. Next r2 uses actual rollback state, original
   contiguous source history and intervening terminal dispositions. Simple containing update0→2
   differs from immutable source-r2 event1→2. No failed r1 event replay, fake source receipt0→2 or
   repeated logical charge. If r2 also exceeds gas, later detach follows the recorded outcomes.
   Other statuses retain their exact laws; unavailable evidence remains nonterminal. Normal
   successful catch-up and intra-operation observations are not coalesced.
3. **Explicit host responsibility.** Coordination/Contracts define eligibility, order, observations,
   scope, gas and results. MyOS durably discovers, schedules and accounts every due reaction.
   Temporal-dependency, ready-work and reverse-wait indexes are maintained with their authoritative
   changes. Scoped indexed queries are the normal path, not full-platform/per-user scans or repeated
   history reconstruction. Concrete PostgreSQL access/index plans are Phase2 work.
4. **Scalable fanout rather than constant fanout latency.** Agreement commits its own complete
   operation plus resumable delivery basis without enumerating/waiting for all Orders. Required
   reactions/gas and drain time may grow with1k/10k/100k recipients. Validate bounded pages, memory,
   transactions, concurrency, fairness, tenant isolation and overload recovery, including millions
   of unrelated documents and thousands active per user.
5. **No mandatory global completion artifact.** Aggregate completion is optional nonblocking host/
   test reporting, not a Contracts operation, epoch, gas charge, global transaction or source gate.
   Durable obligations/outcomes and no-gap discovery remain mandatory. Any all-complete claim needs
   complete coverage and dispositions; an empty queue alone is insufficient.
6. **Measurements follow those responsibilities.** Acceptance freezes SOURCE_OPERATION or
   CONSUMER_OPERATION as its local completion scope. Whole-fanout duration remains optional reporting.
   Censored latency is a lower bound and cannot falsely prove a passing upper-bound percentile.

## API and evidence status

The Java-shaped sketch adds ManagedFailureContinuationEvidence, ManagedDeliveryOutcome and a small
HostWorkSelectionPort. They state required data/ownership laws, not final public constructors or a
working implementation. Existing metric IDs remain; the run-manifest measurement adds completionScope.
Different source/consumer bounds for the same metric use distinct frozen acceptance runs.

The FIFO explanation is grounded in the current local Contracts specification §§6.5–6.9 and
ScopePropagationChain.drain; InternalEventOccurrenceFifoTest contains a one-input FIFO control.
It is not a newly run three-level numeric fixture. Current ManagedEpochInvocationCapturer requires
the exact next source predecessor, and ManagedEpochApplicationExecutor rolls back a noncommitting
import without a successful application receipt. The selected failure continuation therefore
requires explicit Coordination/Contracts changes; it is not a PostgreSQL-only cursor patch.

Source origin/admission and initialization reuse/gas remain open. Minimum authenticated observation
evidence/replay, creator boundaries, placement grouping and actual coupled workflow publication
still need exact constructor/conformance proofs. This refinement does not invent their algorithms
or claim that source/consumer independence is already implemented. Real synchronous feedback keeps
its complete atomic scope/shared gas. The retained r15.2 experiment still does not pass G1.

## Verification scope

Package QA covers Markdown links/anchors/fences, JSON Schema structure, Java17 sketch compilation,
unchanged Mermaid blocks, protected source/build/test/historical artifact hashes and ZIP/manifest
read-back. These checks establish artifact consistency only. No processor tests, PostgreSQL tests,
benchmarks or production capacity measurements run as part of this documentation refinement.

Completed structural checks:45 active Markdown documents/map entries,399 local links and38 anchors;
all three Draft202012 schemas; Java17 sketch compilation with `--release 17 -Xlint:all`. All14 Mermaid
blocks are byte-identical to the previous package. The486 protected source/build/test/historical
files and the existing tracked Git patch retain their pre-edit hashes. Earlier ZIPs are preserved.

The three-phase plan remains library corrections → target-shaped MyOS Mini/PostgreSQL/Timelines →
integrated correctness and performance, followed by iterations. Only the user authorizes its start.
