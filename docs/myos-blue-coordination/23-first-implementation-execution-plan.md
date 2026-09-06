# First implementation: parallel library and host delivery

> **Revision:** 15.12 · **Status:** Phase1/2 integration readiness verified; Phase3 implementation not yet started

## Objective and scope

The user has authorized implementation and automated verification of the full first-POC library
scope and target-shaped MyOS Simple host. Two GPT-6 Astra Extra High implementation agents work in
parallel; the supervising agent owns design corrections, boundary coordination, implementation
review and corrective follow-ups. The objective is **readiness to begin Phase3**, not a production
release or a claim that integrated correctness/performance has already passed.

[22](22-processing-kernel.md) is algorithm authority, [09](09-scenarios.md)/[11](11-validation-plan.md)
define proof obligations, and [10](10-poc-delivery-plan.md) retains the three-phase scope. r15.12 adds
whole conditional-attempt invalidation and per-producer Entry-site gap alignment, and clarifies
outbox transport order. These are implementation requirements, not deferred optional enhancements.

## Workspaces and ownership

| Track | Workspace and branch | Starting local commit |
|---|---|---|
| Coordination | `Blue/worktrees/coordination-external-state/blue-coordination-java`, `codex/coordination-external-state-poc` from `next` | `20fca9fd9934f367612c27348b8b6532d4029672` |
| Language/Contracts, owned by library track | Sibling `blue-language-java`, same branch from `next` | `be22602` |
| BEX, owned by library track | Sibling `blue-bex-java`, same branch from `next` | `4269191` |
| MyOS Simple | `Blue/myos-simple`, existing `feat/coordination-with-external-state` equal to local `main` at start | `2c4651335f9da0ce7a05a4b55f75c56b31a732c3` |

Paths are relative to `/Users/kamil/Documents/Projects`. The original Coordination design checkout
and its pre-existing build/test experiments remain untouched by runtime implementation. Preserve
MyOS Simple's untracked examples. No remote freshness, push, release or migration is implied.
Use local Gradle composite builds or isolated task-specific artifacts: never overwrite a published
RC coordinate in shared Maven-local state. Retain the old in-memory capabilities and existing MyOS
mode; the new durable profile must not secretly retain authoritative RAM state.

## One coherent scope per agent, three internal checkpoints

The checkpoints order work and enable focused review. They are not separate reduced acceptance
profiles, repetitive full-suite gates, or permission to stop after scaffolding.

| Library track | Deliverable | Focused proof |
|---|---|---|
| L1 — Owning semantic runtime | Actual continuation/FIFO source recording and replay, stable execution identities, exact views and typed semantic-failure boundary | EQ observation traces, operational-vs-semantic fault injection, source/consumer independence |
| L2 — Complete graph behavior | Initialization/history/attachment, composed producer reactions, scopes/admission, conditional-attempt invalidation, failed-gap Entry alignment, cycles and deterministic gas | Chronology, cold/warm/reversed schedules, AC61+B51 rejection, multi-producer gap, required finite/looping positives |
| L3 — External-state integration boundary | Minimal usable evaluate/evidence/result interface over real library execution; bounded source evidence, complete deltas/obligations, instrumentation | Actual-library contract tests, missing-evidence/restart, full impacted regression, handoff artifacts |

L1 and L2 may interleave where an end-to-end semantic slice requires it. Do not clone the full Java
documentation sketch into public APIs. Keep runtime semantics in the owning libraries; a model-only
simulator or host workaround is not the implementation. The supervisor may take a bounded separable
repair, such as exception classification, with explicit file ownership to avoid concurrent edits.

| MyOS host track | Deliverable | Focused proof |
|---|---|---|
| H1 — PostgreSQL and Timeline authority | Runnable isolated host profile, immutable exact content, real durable lineage/state infrastructure and exact typed Timeline append/guarantee/idempotency | Real PostgreSQL tests, strict microseconds/ties/endpoint bounds, duplicate requests, restart |
| H2 — Durable indexed execution mechanics | Temporal dependency/activation, ready/reverse-wait work, fenced atomic plans, uncertainty reconciliation, registration/backfill/live handoff, outbox | Lost-ACK/crash races, coherent-index audit, late registration, level-triggered wakeup, tenant isolation |
| H3 — Readiness and bounded operation | Cold-restart harness, fair bounded workers/pages/quotas, query-plan evidence, metrics and thin actual-library adapter smoke | Real DB fault suite, representative scale/query plans, one read→evaluate→commit→publish→restart handshake |

Phase2 may use controlled verified-result fixtures to test storage independently while Phase1 is
unfinished. Those are host mechanics tests, not semantic evidence. H3's thin actual-library handshake
is the first joint readiness check, not the broad Phase3 integration. Replacing H2 with PostgreSQL
while keeping a full resident authoritative Coordination runtime does not pass H1–H3.

## Early shared boundary, no duplicate semantic implementation

Agree the minimal contract early: immutable work intent and exact evidence in; typed missing needs
or a complete library-verified result out. The result carries exact zero/one/many lineage changes,
input dispositions, retained observation evidence, obligations and publication authority. The host
supplies indexed directed membership, next-input/source evidence and compare-and-set persistence;
it never chooses semantic FIFO, scope admission, failure classification or source-history identity.

Coordinate package/types and dependency wiring directly between agents. Reference records may
change while retaining their laws. The actual adapter must reject missing/extra lineage projections,
wrong predecessors, conditional candidate outcomes and unknown evidence. A separate app/bootstrap
entrypoint must make the PostgreSQL-authoritative host usable, not merely a standalone JDBC utility.

## Efficient test policy

1. Inspect existing suites and establish one recorded baseline for the relevant modules. Do not
   repeatedly run all repositories just to reproduce a known baseline failure.
2. After each meaningful change, run the closest real unit/contract tests, including its negative
   control. Incremental compile and selected test classes are the default; avoid unconditional clean.
3. At L/H checkpoints, run the affected fast regression pack. Retain commands, duration, exact
   revision/patch identity, test counts, failures and skips. Identical unchanged results can be reused.
4. At each track's final candidate, run the full impacted-module regression once. Unchanged modules
   need only their relevant compatibility/build checks. After a localized fix, rerun affected tests;
   broaden again when shared behavior, registry, build or test configuration changed.
5. Coordinate heavy Gradle/PostgreSQL/scale jobs to avoid host contention. Cap forks/workers, reuse
   build caches and task-specific database infrastructure. No blanket disabling or weakening tests
   to turn a result green. Infrastructure failures remain distinct from test failures.
   Serialize all builds that use the same included library worktrees: concurrent compilation can
   replace JARs while another JVM is testing them. Independent MyOS RC/database tests may run in
   parallel because they do not overwrite those library outputs.
6. Run the thin shared adapter smoke once the candidate contracts match. Full graph workloads,
   all applicable end-to-end fault permutations and decisive latency/throughput comparisons belong
   to Phase3. Its expected future iterations do not waive current library or host correctness tests.

No test number is a substitute for the capability matrix. UnsupportedScope cannot pass a required
positive. Record omissions as incomplete, not successful. Determinism tests compare histories,
observations, identities, gas and terminal outcomes, not merely final counters. Performance reports
separate necessary N reactions from source recomputation, repeated decoding/copying and restart cost.

## Supervision and completion gate

The supervisor reviews real diffs and the highest-risk traces at checkpoints, routes cross-track
issues promptly, and requests fixes in the owning layer. Design changes discovered in code are
documented with the failing example; no worker-specific interpretation is silently accepted.

Ready for Phase3 means both tracks provide:

- a code-to-test capability matrix covering required library/host scope and explicit limitations;
- reproducible local dependency/build/start/test commands and compatible adapter types;
- targeted and final regression reports with exact failed/skipped/not-run work visible;
- real PostgreSQL authority, cold restart, bounded discovery/fault evidence and a thin real-library
  handshake; no RAM-only or mocked semantic substitute;
- supervisor-reviewed fixes and no unresolved known correctness blocker in the claimed slice;
- a Phase3 entry checklist: install local libraries, wire the full processing path, execute examples
  and correctness overlays, then measure source latency, consumer lag/throughput, memory/IO/WAL,
  large fanout/fanin and tenant fairness.

These readiness conditions are now documented in the [verification record](implementation/phase-1-2-readiness.md).
Phase3 will almost certainly generate further Coordination/host iterations. No production-readiness claim,
API freeze, publication or deployment beyond this local POC follows from the present authorization.

## Implementation checkpoint — 2026-09-06

Phase1/2 are verified for starting Phase3 in the documented scope; this is not Phase3 acceptance.
Runtime work was uncommitted during this verification; its subsequent
[source commits](implementation/source-commits.md) preserve the tested implementation. Focused test counts below overlap
and must not be summed into a regression total. The [library summary](24-phase-1-library-summary.md)
and [Phase3 plan](25-phase-3-integration-plan.md) form the handoff.

| Track | Verified progress | Next phase |
|---|---|---|
| MyOS Simple / Phase2 | PostgreSQL authority, exact microsecond Timeline ordering, durable work/waits/dependency indexes, fenced atomic commits, staged prefixes, outbox and cold restart. Latest full host pack: **66/66**, 44 s, no skips. Latest thin real-library smoke: **7/7**, 22 s, including cold failed-prefix continuation and M1 committed while M2 waits for exact execution data. | Recheck the thin adapter against any subsequent Phase1 boundary changes. Full application processing/examples and end-to-end performance remain Phase3. |
| Libraries / Phase1 | Actual source observation/replay, conditional atomic-group admission/rollback, independent results, historical occurrence lanes and cold receipt codecs. Creator-site initialization, nested reuse, intermediate topology, staged lazy reads, source-head/cache parity and fresh termination passed. Final Contracts, conformance, API guards and impacted Coordination regressions passed, followed by the final real-library/PG handshake. | Deploy the general MyOS adapter, verify integrated graph stories/faults and measure workloads under the [Phase3 plan](25-phase-3-integration-plan.md). Exact counts and limitations are in the [readiness record](implementation/phase-1-2-readiness.md). |

Source-initialization evidence validation distinguishes immutable preflight from acceptance at one
actual placement. Its **12/12** tests include a newer authoritative source with an exact authored
pin, prospective-row rejection, preservation of an older alias, and a canonical admission cause
independent of the first requested member of an authored cyclic component. The separate actual
interpreter tests now cover creation → synchronous init0 → creator read, cold replay, rollback,
offered-but-unused evidence, and creation followed by retirement. An existing alias at epoch 5
retains its value `77` while a new placement reads init0 value `5`, without receiving init0 again.
Two additional actual tests passed for distinct creation sites sharing one initializer and a
downstream observer replaying the creator's cold program without rerunning either producer.
Nested initialization and queued old-source/new-initialization contexts also passed actual runtime
tests. The actual creator-to-historical-lane suffix integration, final affected regression and
final-candidate host handshake have also passed.

The actual sparse-runtime witness now starts with only A's body resident and verified B/C headers.
An operation that does not read B/C completes without their bodies. A real nested read first returns
an exact B need; after hydrating B it returns an exact C need; after hydrating C it completes. Its
resulting body, operation identity, gas trace and complete source-program transport digest match the
fully resident run. This is an interpreter test, not a mocked lazy provider. It does not claim that
all large source-program fragments are already streamed lazily or that end-to-end performance has
been measured.

The owning unchanged-component authority and cold authority codec pack passed **23/23**, including
cyclic identity/proof-header preservation, body-free transport, exact hydration and tamper/foreign
state rejection. Unconsumed dependencies retain exact outgoing references without serializing an
ambient source epoch or body. Actually consumed source-operation boundaries come from authenticated
source programs/failures. Optional host read pins are excluded from canonical program inventory;
accepted initialization/frontier facts retain their required selected pins. Actual active/inactive
dependency tests compare program digests across different physical source heads and cache contents.
The latest cold metadata and receipt checks include inherited root headers, inactive candidate
exclusion from live Timeline membership, and a named need for an unaligned historical channel cut.

The creating-workflow integration tests deliberately cover both an uninitialized source and a
source already at epoch 5 through an older alias. The latter must retain its canonical head and the
old alias while the new placement observes init0. The interpreter uses a separate private source
interpretation view for this purpose; a temporary global rewind is not an acceptable
implementation. Merely offered initialization evidence cannot activate or publish a source.
Canonical source observation identities are unchanged when reused; the consumer's observation
sites are additionally qualified by its creator seed, creation site and placement. Retiring a
placement preserves the already observed initialization fact, but grants no surviving import lane.

The event-routing check also clarified an implementation-facing ambiguity in [22](22-processing-kernel.md):
queued events freeze occurrence/activation membership, while event channels are refreshed at each
canonical receiver delivery. Pre-mutation document-update dispatch retains its separate frozen
contract surface. The existing ordinary Contracts behavior is the reference for this distinction.

The next work is the documented Phase3 application integration, followed by measured iterations,
not an API freeze or a new specification profile. Its implementation awaits the user's decision.
