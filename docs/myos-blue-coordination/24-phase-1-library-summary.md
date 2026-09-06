# Phase1 — what changed in the libraries

> Baseline implementation handoff, 2026-09-06. Subsequent review repairs are tracked separately.
> [Verification record](implementation/phase-1-2-readiness.md) · [Phase3 plan](25-phase-3-integration-plan.md)

See [pre-Phase3 review remediation](implementation/pre-phase-3-review-remediation.md) for the
authorized follow-up corrections and their verification. This summary describes the committed
baseline, not a claim that its tests covered those later counterexamples.

## Phase1 outcome

Coordination can describe and execute the next justified piece of document processing without
requiring the host to retain the entire resolved graph in memory. The host supplies exact evidence,
stores verified results, and schedules further work. It does not implement a second interpretation
of Blue's workflow, event, ordering or gas rules.

For one Agreement and many Orders, this means:

1. Agreement processes its input and produces an authenticated result describing the observations
   needed by its consumers, not just its final counter and a list of public events.
2. Its independent result can become durable without waiting for all Orders.
3. Each Order processes the appropriate source result at its own justified history position, using
   the exact source views required by that processing.
4. A real returning dependency can instead require one atomically owned group with shared gas and
   rollback. Sharing a read-only source alone does not create that group.

These are the selected r15.12 semantics. The linked verification record distinguishes actual
execution witnesses, regression results and remaining Phase3 proof obligations.

## Changes by responsibility

| Area | High-level change | Why it matters |
|---|---|---|
| Coordination boundary | Explicit selection, exact evidence acquisition, verified results and missing-evidence outcomes; a result can contain separately owned operations in dependency order. | Persistence and scheduling can be external without letting the host choose business semantics. |
| Source reuse | Retained, authenticated observation programs capture intermediate views, patches, event delivery and required nested observations. Consumers reuse that evidence. | Reusing Agreement must preserve what an Order observes during processing, not merely its final value. |
| History and attachment | Canonical source initialization is independent of first physical creation; observer attachment selects its own history/frontier. Historical imports retain their own progress. | Cold versus warm storage, worker order and late materialization cannot choose a different history or gas charge. |
| Ownership, cycles and gas | Independent source/consumer boundaries coexist with actual feedback groups; tentative dynamic joins have deterministic admission and complete invalidation on rejection. | No accidental global transaction for 1000 Orders, and no infinite feedback loop escaping its gas limit through fresh receipt hops. |
| Failure and recovery | Library-certified semantic failures are distinct from missing data, capacity holds, I/O and unexpected implementation failures. Handled failures and successful views have separate continuity. | A retry cannot turn an operational problem into a consumed business input; a later legitimate input can follow a deterministic failed operation. |
| Language/Contracts | The owning interpreter exposes the required managed observation, exact-view, routing, initialization and settlement behavior. Existing ordinary/in-memory capabilities remain. | The integration does not approximate core semantics in an SQL adapter. |
| BEX | Failure classification preserves unknown/operational causes instead of wrapping them into deterministic failures; provisional gas ledgers have an explicit nonsettling abandonment path. | An unclassified computation or adapter fault must not consume an input or settle semantic gas. I/O/cancellation cases are defensive boundary tests, not database access or cancellation added to BEX. Existing exact loading/cache capabilities are reused; this was not a new cache implementation. |
| Durable evidence | Receipts, state/checkpoint authority and source programs can be verified and restored cold, including sparse managed bodies and cyclic proof headers. | A host restart does not require serialization of a private runtime object graph. |

## What changed relative to the implementation plan?

The comparison baseline is the authorized [r15.12 execution plan](23-first-implementation-execution-plan.md),
not earlier review drafts. The overall architecture, ownership split and three-phase sequence remain.
Independent source commits, canonical initialization, genuine feedback atomicity and external state
were planned changes, not new choices made while implementing.

Implementation did require more precise mechanisms than the explanatory API sketch:

- **More than one publication from one evaluation.** Prerequisite and consumer operations need
  dependency-ordered, separately atomic publication. A container of prepared operations is not
  one global transaction. A genuinely owned group is still atomic.
- **Observation evidence is richer than an event list.** Actual callbacks can read intermediate
  source states and topology. Entry positions, patch effects, borrowed observations and selected
  exact views must remain authenticated. Replacing them with the latest source snapshot is wrong.
- **Creation is a processing site, not an ambient rewind.** An existing alias can observe a later
  source state while a new placement observes initialization. Placement-specific context and
  identities preserve both without changing the source's canonical history.
- **Retirement preserves the view actually selected at retirement.** It is not necessarily the
  invocation's input view or the final physical source head. The runtime now retains the necessary
  private authority rather than trusting presentation-only transition metadata.
- **Equal content cannot silently change an established target.** An authenticated prior view of
  an existing lineage wins over another lineage's coincidentally equal current content. A concrete
  regression demonstrated the wrong retarget; the repair preserves both selected-view identity
  and genuine different-target changes without depending on member enumeration order. The
  counterexample uses the legacy SDK's explicit IDs; it does not introduce arbitrary host-assigned
  lineage IDs into the new POC or change its canonical authored-document identity rule.
- **Failure evidence must retain each consumer's actual source view.** Two consumers can have
  different successful pins after failed observations. Later alignment cannot use one shared
  ambient source pin or advance an unrelated Timeline cursor.
- **Ordinary exception handling was too broad.** Recognized deterministic faults now have explicit
  owning types; unknown exceptions cannot silently become a semantic `RUNTIME_FATAL` settlement.
- **Fresh termination needed owning-runtime support.** The inherited non-admission path did not
  execute it. The existing lifecycle now supports real terminal publication and cold observation,
  including exact marker timing, rollback and authenticated identities for work cut off by
  termination. A skipped delivery is not represented as an executed workflow step.
- **Routing needed an explicit coordinate comparator and incremental accounting.** Tests exposed
  accidental ordering by occurrence hash. The implementation was corrected before accepting
  updated gas/receipt expectations. Logical route work is charged before allocating more work.

These refinements changed code and the concrete boundary, but do not grant a host freedom to alter
determinism. Actual tests distinguish buffered workflow previews from installed source views and
frozen event membership from the channel surface consulted at each delivery.

The local fixture refresh is documented in the Language worktree's
`tools/local-poc-fixture-deltas.md`. Changes are individually justified; old golden values are not
automatically authoritative after an intentional semantic change, and newly generated values are
not automatically correct. No published package, alternate specification profile or migration of
existing user data was introduced.

## What is not yet proved by Phase1

- The complete MyOS application path is not wired to the new graph adapter yet.
- Sparse managed bodies work, but one selected source program and its borrowed evidence DAG are
  currently decoded within physical budgets. This is not a fully streaming action interpreter.
- Sparse execution still requires the complete authenticated directed metadata/read cut relevant
  to the selected action. Avoiding body residency does not permit guessing missing topology or
  Timeline completeness; unrelated reverse-only observers are not part of that directed cut.
- Recording intermediate source observations can allocate significant memory. Transport byte
  limits alone do not bound capture-time peak memory; Phase3 must measure both.
- Full graph throughput, source latency under fanout, consumer lag, SQL/WAL cost and cache sizing
  require the integrated POC. A million-row metadata index probe is not that measurement.
- The final affected regressions and PostgreSQL handshake passed. Earlier failures and their
  reviewed repairs remain visible in the linked record; this is not a claim that every historical
  test suite was rerun after every edit or that all integrated scenarios have been proved.
- The implementation evidence currently consists of executable tests, JUnit reports, cold receipt/
  PostgreSQL witnesses and reviewed fixture artifacts. A complete preregistered scenario-manifest
  archive covering every catalog row has not been produced. Do not label these results formal
  acceptance of all G1/G2 or large-scale scenarios; Phase3's decisive runs must retain that archive.

Further library changes based on Phase3 evidence are expected. Readiness to integrate does not mean
Coordination is finished, production-ready, released or API-frozen.
