# Phase3 — integrate, prove and measure the PostgreSQL POC

> Plan, 2026-09-06. Phase3 implementation is not yet authorized by this document.
> [Phase1 summary](24-phase-1-library-summary.md) · [Readiness](implementation/phase-1-2-readiness.md)

The authorized [pre-Phase3 review repairs](implementation/pre-phase-3-review-remediation.md)
precede acceptance of the dependent paths below. They preserve these slices and the processing
kernel; they do not create another specification profile or start the general adapter.

## Target

Run the real Coordination processing path inside MyOS Simple, with PostgreSQL as durable authority,
exact lazy reads and bounded workers. Demonstrate correct settled histories first, then measure
the cost and scalability of that implementation. This phase should produce the next evidence-based
iteration plan, not a claim that Coordination is now finished.

Use the same local library branches and `feat/coordination-with-external-state`. Keep the existing
demonstrator available as a separate mode; do not silently switch its semantics or reuse published
RC coordinates for development artifacts. No release, legacy-data migration, multi-provider product
or production-HA project is part of this phase.

## Entry conditions

- Close R1–R5 from the [final implementation review](review/final-implementation-review-2026-09-06.md)
  before accepting their dependent integration paths: original producer context on fresh execution,
  typed schema failures, retained-attempt recovery and blocked-prefix isolation. Track their
  implementation in [the R1–R5 repair record](implementation/final-review-remediation.md).
  The user explicitly deferred R6 stack/depth work; it is not a gate for this repair round.
- Include the [N1–N5 follow-up corrections](implementation/post-remediation-fixes.md): independent
  initialization/frontier authority and its settlement binding, target metadata fences, cyclic
  schema output validation, discovery retry isolation and retained-work release protection.
- Close Phase1/2's known correctness failures and final regression/adapter handshake.
- Verify the review repairs on the changed candidate: lossless historical needs and original-input
  choices, producer-basis compatibility, real BEX validation/operator controls, retained-publication
  capacity release and fair Timeline maintenance. Earlier baseline passes do not cover these cases.
- Retain the Phase2 M1/M2 witness: M1 commits with exact next selection known while M2's execution
  body is unavailable; a fresh JVM resumes M2 without reapplying M1. Missing selection authority
  remains a separate hold. Do not substitute the narrower missing-fragment restore test.
- Record the exact commits, dirty patches, resolved local dependencies, gas/semantic configuration,
  PostgreSQL schema and reproducible start/test commands. Old test results are reusable only for
  unchanged code/configuration; they are not current end-to-end results.
- Select the initial scenario rows and independently specify their small expected traces before
  running them. Use the [run-manifest protocol](16-scenario-run-manifest.md) for decisive runs;
  ordinary exploratory tests do not need a new acceptance package each time.

## Four delivery slices, one integration

Slices are implementation checkpoints, not new specification variants. Do not build a disposable
adapter for each slice or run every repository's full suite after every change.

### 3A — One complete application path

Install the real adapter in the durable host and provide a repeatable local entry point that accepts
a document and Timeline inputs, drains bounded work, exposes outcomes and survives a cold restart.
Reuse the existing PostgreSQL stores, work fences, provider and outbox. The default legacy UI/HTTP
path is not evidence that this new path is wired.
Currently no provider is installed through `ServiceLoader`; the isolated smoke tests inject the
canonical-initialization adapter directly. Wiring an actual runnable provider is therefore real
3A work, not an already completed bootstrap detail.

Implement the adapter's complete result dispatch, initially exercised with a simple counter:

- Exact immutable input/evidence loading and verification; named needs register durable waits.
- Preserve complete typed needs through portable storage and cold restore. Source reconstruction
  uses authenticated original source-input selections, including explicit empty selections, not
  the importing observer's policy or a default chosen because evidence is missing.
- Translate exact Timeline descriptor, actor, provider proof and complete entry through one verified
  adapter boundary. Same-type descriptors sharing a locator are not interchangeable. This check
  also covers empty prefixes/completeness before any metadata or handled-cursor progress; retain
  lawful channel-pattern semantics instead of requiring every pattern to equal a full descriptor.
- Complete repository/type registration during cold startup. The smoke work exposed that a nested
  Principal Actor can otherwise decode as a generic Actor and lose its account constraint. Retain
  a cold-start authorization regression through the actual application entry point.
- Initialization and ordinary input processing; metadata-only progress has no invented epoch,
  business operation, gas charge or successful source program.
- Separate fenced plans for dependency-ordered prepared operations. Each genuinely owned group
  maps to one atomic plan containing all its lineage effects, not one plan per changed document.
- Verified state, history, successful/handled cursors, receipts, obligations and outbox in the
  corresponding transaction; no semantic execution or provider wait inside an SQL transaction.
- Cold receipt restoration, uncertain-commit reconciliation and idempotent publication.
- Do not ordinary-release work owned by a retained plan. The host rejects it until full-plan
  reconciliation; keep all joined work members discoverable together.
- Exercise both publication-read and sink-payload capacity holds through a real release: retry the
  retained stream head after supported capacity increases, without new semantic work or gas. Wire
  non-spinning blocked-head handling and keep unrelated streams progressing. Admission and the
  supported publication envelope must agree; page limits cannot make an admitted batch unreleasable.
- Runnable recovery, discovery and outbox pumps. The current bootstrap performs one recovery/drain
  pass; it is not yet a continuously operating application service. Persist the continuation
  between independently committed prepared groups: the existing host completion commits one plan,
  not a wrapper transaction containing all groups.
- Use bounded fair service per Timeline in both evidence-maintenance queues. Ordinary work-claim
  fairness is not proof of maintenance progress under a sustained earlier-key backlog.
- Preserve item-local discovery Holds/backoff across pump turns and cold restart. One oversized
  source proof must not monopolize fanout/backfill service for independently serviceable items.

**Exit:** an actual create → append → process → publish → stop → restart → append sequence passes
through the runnable profile. The host contains no second interpreter and no authoritative runtime
hidden in a singleton or cache. No new UI is required beyond a small usable API/CLI/test entry point.

### 3B — Graph processing through that same path

Connect the library's directed topology, next-step selection and exact dependency evidence to the
existing indexed selectors, source history, occurrence registrations and import lanes. Extend the
3A adapter rather than replacing it.

Preserve these boundaries explicitly:

- Source history, semantic operation order, physical storage sequence and consumer import progress
  are different concepts. An import cannot advance an unrelated ordinary Timeline cursor.
- Dispatch ordinary `MetadataProgress` and managed-lane progress separately. Target metadata may
  coexist with independently prepared producer operations; it cannot erase those publications or
  invent a target operation. Preserve source operation ID, receipt-content key, physical publication
  commit key and optional prefix-record key as distinct fields, never inferred from hash spelling.
- Registration and retirement come from accepted library results, including canonical initialization,
  attachment policy/frontier, temporal history and prefix/live handoff. Discovery is not permission
  to execute a reaction early.
- Source commit retains its receipt and recoverable fanout basis without enumerating all consumers.
  Recipient pages and consumer work remain bounded and independently schedulable.
- Missing exact bodies, historical read cuts, checkpoint domains and source programs produce their
  named needs; a physically newer head cannot substitute for them.
- Semantic failed source/consumer operations retain verified failure capabilities and actual
  consumer/source-specific successful pins. Operational failures consume nothing.
- Verify producer success/failure, used initialization/borrowed-init evidence and frontier views
  against independently expected canonical source bases before metadata or execution progress.
  Producer and observer budgets may legitimately differ; copying
  the observer's policy into producer verification is not the compatibility rule.
- Genuine feedback and conditional joins use the library's complete ownership/rollback result.
  The host does not infer an atomic group from graph reachability or changed bytes.

Start with one Agreement and two Orders, then a chain, aliases, dynamic attachment and a finite
cycle. Add staged history/prefix mapping through the same verified receipt boundary. The bounded
test-only smoke bridges must not become a production semantic shortcut.

Before optimizing discovery, specify the full library-position-to-SQL projection. External order
uses `(timestampMicros, entryBlueId)`; producing position, inherited reaction origin, source SQL
sequence and occurrence generation have different roles. Conservative scalar bounds may over-select
ambiguous equal-time candidates but may not omit an entitled one. Preserve the original authority
for final eligibility. Test attach/input/remove at equal timestamps with different entry IDs.

Close F07 by using complete indexed temporal candidates and a source-fenced lifecycle high-water,
including staged-prefix rows and late registration/backfill. Avoid per-receipt scans of all retired
activations. Initial high-water preparation alone removes old log replay, not the remaining lifetime
occurrence scan. Require a small churn witness before larger scale runs; do not substitute a
`currently_active` predicate or infer discovery closure from an empty page.
The candidate range must cover historical imports plus live lifetime: K100's FULL_HISTORY
registration with physical source tail zero still requires E10 when that canonical prefix is
activated later. Filtering E10 solely against live `activeFrom=K100` would lose that obligation.

**Exit:** the small graph story set below passes through the general adapter with both warm and
cold PostgreSQL reads. Test-only library fixtures are no longer dependencies of the runnable path.

### 3C — Durable faults and adversarial schedules

Exercise the graph adapter at real boundaries: after preparation, before commit, after commit but
before ACK, between independent source/consumer commits, during paged discovery and publication.
Kill and restart a separate JVM, not merely reconstruct an object in the same process.

Pair ordinary host fault tests with integrated witnesses for lost/duplicate delivery, stale workers,
source/registration races, missing content, delayed Timeline completeness, failed import gaps and
operational quota pause/resume. Validate committed authority directly, not only queue counters or
the same indexes used to select work. Use small raw-history audit fixtures independently of the
large index/performance corpus.

Include three independently prepared groups with crashes between their commits; a producer failure
alongside target metadata progress; and a multi-publication history record with one publication
omitted or substituted. Exact continuation must retain prior independent commits and reject broken
linkage, without turning physical prefix/commit keys into semantic operation identities.

**Exit:** perturbing the physical schedule changes attempts/latency only, not the settled semantic
history, gas or identities. No partial owned operation publishes; earlier independent commits
survive. Required work is recovered without a lucky notification. Unsupported scope is visible and
does not count as a passing required positive.

### 3D — Representative workloads, examples and measurement

Select useful examples from `blue-tutorial` and MyOS Simple only after inspecting their actual
semantics. Record each as reused, adapted, excluded with a reason, or failing with an owning issue.
Do not assume old examples work or change a correctness oracle merely to make an example pass.

Run correctness on small graphs first. Calibrate a named local environment, preregister performance
budgets, then run the chosen scale cells. A performance regression never justifies changing history,
event ordering, gas ownership or input eligibility.

**Exit:** reproducible correctness, failure and performance reports, explicit failed/inconclusive
rows, and a prioritized next-iteration list assigned to Language/BEX, Coordination or MyOS storage/
scheduling. A correct but out-of-budget run remains a performance failure.

## Readable integrated story set

These rows map existing [scenario cards](09-scenarios.md) and [validation obligations](11-validation-plan.md)
onto the real host; they do not replace or silently narrow that catalog. Each selected row gets a
short card: purpose, Given/When, hand-authored expected trace, durable post-state, forbidden outcomes,
fault overlays and measured budgets. Mandatory kernel witnesses remain mandatory.

| Story | Essential observation | Existing families |
|---|---|---|
| Single counter, two original entries | Two ordered successful epochs; cold restart adds no duplicate operation/event. | C, K, P |
| Quiet Agreement, active Order; quiet Order, active Agreement | Order needs the directed source cut; reverse-only Orders cannot block Agreement. Exact microsecond ties use the library comparator. | M |
| Child behind required T | Source is 1 at T10 and 2 at T30; a parent read at T20 must see 1. Missing required history/completeness pauses the read; a later physical head cannot answer it. | A, E, L, M |
| Attach at T1 versus T10 | B emits value5 at T5. A attached at T1 reacts at T5; A attaching at T10 remains unset before attachment and then catches up. Equal final values do not imply equal histories. Original entries and separate imports remain ordered. | A, B, E |
| Initially nested A → B → C | Reconstruction and ordinary reads agree at each historical position; the outer document does not see future child state. | A, E, N |
| One Agreement, two independent Orders | Source commits once; delayed/gas-failing Order cannot roll it back or strand the healthy Order. | D, F |
| Two source epochs versus two emissions in one operation | Separate epochs give Root F1→1 and F2→2. In the reviewed same-operation FIFO control, both later handlers read 2, although F1's immutable payload may contain 1. Preserve that distinction, not merely final counters. | C, E, N |
| Aliases, diamond and retirement | Preserve occurrence multiplicity, exact selected views, composed origin and frozen routing. Retirement excludes only its obsolete future suffix. | C, E, N |
| Concurrent cold initialization | Worker/promoter order and cache eviction do not choose source history, identity or semantic gas; failed creator publishes no orphan. | A, D, K |
| Dynamic genuine feedback | Finite case is atomic; gas loop rolls back its owned group; later repair can proceed under the existing failure law. | F, J |
| Newly coupled same-origin targets | Required accepted and rejected joins, including whole conditional-attempt invalidation, match library witnesses under reversed claims/restarts. | J-DYNAMIC-SAME-ORIGIN |
| Failed import followed by another input | Retain handled failure without successful epoch; align each consumer's actual source pin at the proper Entry site; no failed-event replay. | E, F |
| Graceful termination and observer cutoff | Execute a real termination request, lifecycle handlers and terminal marker; restore the terminal source observation cold. If one independent Order terminates on E1, Agreement's later E2 and a healthy Order still finish. Preserve exact marker visibility, event order and the subsequent no-work rule for the terminated document. Synthetic terminal receipts alone do not prove this. | C, E, F; kernel22 observer cutoff |
| M1 committed, M2 missing execution data | Restart and acquire M2 without replaying M1. Missing selection evidence is a different hold. | E, I, R |
| Historical dynamic attachment needs | Full typed needs and original source-input selections survive cold storage; direct and reconstructed histories agree; changed admission or missing choices cannot select a different default. | A, E, I |
| Wrong source basis; different legitimate observer budget | Reject substituted environment/producer policy before metadata progress, but accept the correctly authenticated source with an independently budgeted observer. | E, F, K |
| Exact Timeline alias at the adapter | Same locator/type with a different descriptor, actor or proof rejects before cursor movement, including empty complete prefixes. | M, K |
| Committed publication above a physical budget | Actual capacity release and lost-ACK retry publish the retained result once logically; no second source evaluation or gas settlement. | I, K, L |
| Historical prefix to live tail | Activation, metadata/failure records, registration and live handoff lose or duplicate no entitled operation. | A, D, G, H, K |
| Large valid import chain under quota | Bounded operational pause and exact resume, with no fresh semantic policy or invented cancellation outcome. | J-AUTOMATIC-REATTACHMENT-CHAIN, L-QUOTA-PAUSE |

Use hand-worked traces plus existing actual-interpreter witnesses as the semantic oracle. Compare
the same new managed model with fully resident versus PostgreSQL/lazy evidence for exact histories,
observed values, events/order, BlueIds, checkpoints, operation identities, gas and terminal outcomes.
Two adapters sharing a bug are not independent proof. Compare the old monolithic model only where
the documented scope/identity correspondence actually applies; the old application's known bugs
or deliberately different ownership model are not a golden oracle for the new design.

## Cover combinations without an unreadable Cartesian product

Every story first runs in one simple, readable schedule. Add pairwise coverage for cache state,
worker count/order, page size, delivery duplication and restart placement. Explicitly enumerate
the dangerous combinations: dynamic join plus failure; registration plus source commit plus scan;
failed import plus aliases/retirement; cold historical evidence plus prefix/live handoff. Pairwise
coverage alone is insufficient for those named interactions.

Use deterministic seeds for generated tiny graphs and schedules. Keep a fixed seed corpus for
acceptance; shrink any failure into a short replayable trace and add it to the readable story set.
Test mutations include a wrong predecessor, missing receipt/body, altered exact bytes, omitted
recipient, stale fence and forged failure classification. Each negative must fail for its intended
reason. A hold must have a specified release condition, not just an assertion that nothing happened.

## Performance matrix

Separate logical gas from physical resource use. Keep semantic inputs/policy fixed across compared
runs; report retries and reconstruction separately from one accepted logical source transition.

| Axis | Initial cells and measurements |
|---|---|
| Small working graph | A preregistered 2–10-document graph with bounded event/history volume. Measure p50/p95/p99 end-to-end settle latency in seconds, cold/warm reads, commits and allocations. Set numeric acceptance budgets after calibration, before the acceptance run. |
| Shared Agreement fanout | N=1, 1000, 10000, 100000 Orders. Measure independent source commit latency, consumer throughput/lag, backlog drain, physical source evaluations, evidence reuse, SQL/WAL and other users' service. No constant-time or universal seconds-scale completion promise for N reactions. |
| Genuine fan-in | Agreement embeds 10, 1000, 10000 Orders. Measure initial membership cost and incremental merge/completeness work; withhold one relevant guarantee. Never drop a real Timeline to improve latency. |
| Dormant graph and lazy reads | Grow total graph/body size while keeping the affected/read spine fixed. Count resident bodies, exact reads, fetched/decoded/copied/hashed bytes and peak heap. Include absent unneeded bodies as a positive control. |
| Rich source operation | Vary intermediate observable updates U=1, 10, 100, 1000 separately from consumer count and history length. Measure capture-time memory as well as decoding/transport; physical budgets must produce operational holds, not semantic gas changes. |
| Long history and continuation | Vary source history and finite nested work across 10/100/1000 commits. Measure prefix/live pagination, cumulative verification, control queries, serialization and WAL; one selected action must not reload the whole history. |
| Lifecycle churn | Keep one live consumer; independently grow retired activations R and subsequent receipts M. Measure total rows examined, candidates/work created, duplicate acquisition, WAL and source-lock time. Closed old lifecycle history must not create an O(M × R) term in fresh fanout. |
| Maintenance fairness | Pre-existing selectors for busy Timeline A and ready Timeline Z; replenish A continuously with page/budget one. Bound Z's service in active-Timeline turns, including restart, rather than A's backlog size. Exercise both maintenance queues. |
| Contention and overload | Multiple active accounts, thousands of active documents per account, bounded pools/caches/workers, then load above capacity. Measure fairness, queue-age growth, backpressure and drain after overload; distinguish required consumer work from global scans or repeated source computation. |

Do not multiply every large axis in the first benchmark. Run one-factor sweeps, then a few measured
worst interactions, with explicit resource ceilings. Reuse Phase2's indexed metadata probes as
supporting evidence, not as proof of processing 100000 real documents. Record hardware, heap, pool,
cache, concurrency, dataset, warmup and repetitions; retain raw query plans, profiles and counters.

Source independence can grow retained history when an observer remains behind. Measure storage and
backlog growth under a held consumer; do not promise unbounded lag, continued source progress, full
later replay and constant retained storage simultaneously. No destructive history GC is introduced
without verified retention authority. Bounded transport/cache size also does not bound aggregate
attempt-pinned bytes, decoded DAGs or capture peaks; record those separately.

The current staged-prefix activation fences at most 1000 dependency sources. A 10000-source fan-in
row therefore tests a real integration/capacity boundary, not already demonstrated support. If it
hits that bound, report the operational hold and plan the measured host refinement; do not omit
Timelines, silently lift transaction limits or label a held run a successful performance result.

## Efficient execution and ownership

One integration owner maintains the runnable adapter and cross-slice boundary. In parallel, an
independent verification owner can prepare hand-derived cards, fault schedules and the benchmark
harness. Runtime semantic repairs stay with the owning library; SQL may not patch a wrong FIFO,
scope, source view or gas decision. Serialize builds that share included library worktrees.

After a local change, run the affected unit/adapter tests and the relevant small story. At a slice
boundary, run its impacted regression once. At the final stable candidate, run the full integrated
correctness/fault pack and only changed-library regressions, then the preregistered performance run.
Avoid clean builds, repeated unchanged scale corpora and whole-repository suites per scenario.

The final handoff must answer: which histories were proved, which faults recovered, whether source
reuse really occurred, what constrained throughput/memory, which cases remain unsupported or failed,
and what the next iteration should change. Phase3 ends with evidence and decisions, not an API freeze.
