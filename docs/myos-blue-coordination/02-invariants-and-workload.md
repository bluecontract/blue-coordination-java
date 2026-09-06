# Invariants and workload model

> **Status:** PROVISIONAL / EXPERIMENTAL · **Revision:** 15.12
> [Independent lineages](18-independent-lineage-processing.md) · [Causal model](17-causal-processing-model.md) · [Scenario cards](09-scenarios.md)

## The non-negotiable result

For the same authored managed graph, external causes, admission/attachment inputs and exact
semantic environment, physical execution must produce the same settled ordered histories,
events, gas, checkpoints, statuses and BlueIds. Paging, caches, workers, database latency,
retries and outages may change elapsed time and physical work, never that result.

Three comparisons must stay separate: the same invocation with warm/cold materialization; the same
logical scope/event behavior under ordinary and managed processing; and an explicitly revised
source/consumer ownership boundary. The last may change legacy combined rollback/InvocationIds,
but cannot excuse a cache-dependent result or dropped ancestor handler. Define the mapping and
hand-derived expected trace independently of both adapters; see [21](21-semantic-equivalence-and-source-reuse.md).

<a id="section-3"></a>
## Core invariant register

| Invariant | Consequence and owner |
|---|---|
| Exact identity is defined by Language, not JSON spelling, SQL row ID or structural similarity. | Verify supported exact values, types, constructors and cyclic proofs before use; immutable content is not mutable business state. |
| The POC DocumentId is the initial authored exact BlueId, including channels. | Identical initial authored content denotes one lineage. A host allocation/work slot cannot mint a different lineage. |
| Stable lineage identity differs from evolving exact state. | Cyclic member identities/proofs are component-derived; they do not replace DocumentId. |
| Epochs represent complete WORK revisions. | Preserve the current Contracts same-epoch finalizer/initialization exceptions; a host revision detects mutable changes even when the semantic epoch does not advance. |
| Occurrence, binding, generation and activation interval are different facts. | Preserve creation evidence and current exact binding; never redirect a frozen delivery to a different lineage. Removal/reactivation follow current constructor rules. |
| Authored Process Embedded relations define the managed graph. | Forward/reverse indexes and SCC metadata are complete derived projections, not alternate graph authority. |
| Component generation changes only for its specified membership/internal-edge changes. | Host concurrency revisions cannot substitute for Contracts graph/component generations. Rollback changes neither semantic state nor generation. |
| Timeline identity is the exact Timeline value, including provider type. | Raw timelineId is not a universal key. Reuse the MyOS provider foundation without copying its older document feeder. |
| One Timeline has strictly increasing provider-assigned microseconds and a predecessor chain. | Equal time is legal across Timelines, not within one Timeline. Append and exclusive guarantee advancement serialize correctly. |
| Canonical external order is (timestampMicros, exact entry BlueId text). | Timestamp is the priority; the validated canonical text totalizes ties. Never use arrival order, provider priority or SQL collation. |
| Selection requires complete evidence beyond the candidate across its required directed dependency Timeline set. | No quiet-provider guess, synthetic entry, skipped real input or unknown relevant Timeline exclusion. An unavailable proof causes a hold; unrelated platform documents do not join the set. |
| Local deliveries and semantic scope are exact; global discovery can be incremental. | A source commits retained receipt plus durable delivery basis without enumerating all Orders. Transactionally maintained temporal indexes establish eligible work through scoped queries; any optional all-complete report additionally needs complete coverage. No current-only-index shortcut or routine full-history reconstruction. |
| Efficient work selection is part of the host contract. | Atomically maintain topology/dependency indexes, ready work and reverse waits with their authoritative changes. Use bounded source/lineage/dependency queries, not scans of all platform or tenant documents. Coordination validates semantic eligibility; host scheduling cannot change it. |
| Current execution facts are not the immutable intent. | A legal earlier disjoint commit may change host/graph revisions. Re-read and prove scoped consistency; do not require an obsolete global snapshot forever. |
| Ordinary managed observers process independently from their source. | Agreement may commit before all Orders and advance while one-way observers lag. Each local operation owns its gas/rollback; a failed Order cannot undo source or siblings. Current live closure grouping must change in the owning libraries. |
| Live and historical consumers reuse retained source results. | Each defined consumer operation has its own complete result/gas/commit. Lag is not new attachment; observed exact version/cursor differs from authoritative source head. |
| Source-event provenance is not the monotonic order of lineage applications. | `sourceOrder` may repeat or decrease across later epochs. Use contiguous receipt positions and predecessor/representation proofs for continuity; derive historical visibility from authenticated producing applications and the accepted boundary, never a bare `sourceOrder <= cutoff` filter. |
| FULL_HISTORY must preserve chronological observations. | Initial source history is interleaved with the parent's own past where required; importing all source future before a parent past read is invalid. Same-original-cause grouping remains a mandatory Phase-1 proof. |
| A direct historical child read and an event-derived parent field are distinct observations. | Test both at the same original cause; a correct copied value cannot hide resolving the child to its ambient future head. Every selected original target event must occur exactly once in order, even when adjacent observations are equal. |
| Historical removal/retargeting is an operation, not permission to drain future source history. | Apply it at its authorized original cause; retire only obsolete occurrence-owned work, preserve prior commits/independent obligations and derive a replacement's exact new lifecycle. Proposed first-scope behavior requires a reviewed Contracts reconciliation rule, not a global CATCHING_UP exemption. |
| Dynamic attachment has its own target semantics. | Do not substitute initial-history replay rules for attachment-time import, or vice versa. Activation follows exact source epoch/binding rules. |
| An external cutoff excludes unrelated later input, not effects caused by the current root. | Track authenticated same-root source progress by producer receipt/predecessor; no blanket source-write ban and no ambient-future absorption. |
| The next action is justified by committed predecessors and operation-specific prerequisites. | No universal all-managed-first rule; no internal dependency wait whose only releaser is forbidden by another invented phase rule. Foreign or failed obligations are not bypassed. |
| Cyclic deliveries are real work. | Actual workflow feedback within one logical operation retains one atomic scope and shared gas; never create fresh invocations per hop to evade exhaustion. Do not dedupe distinct emitted occurrences. |
| Dynamic same-origin coupling is admitted before the returning edge. | Freeze pre-origin seed order. First touch adds readiness, not ownership. Charge the check locally; join distinct group ledgers only if their sum fits the identical frozen limit. Otherwise fail only the initiating owned group before mutation. Stable seed-local events never change identity on join; no retroactive shared-meter replay. Fence all old proposals; see22 §2.1. |
| Every historical revision is authenticated. | Event-bearing and supported eventless same-state receipts require their complete source application/result/companion/gas evidence. Never replace history with an unproved latest snapshot. |
| A stable application can have several attempts but one winning settlement. | Settlement is create-only and records its winning InvocationId. Retry identity is not a cause-only dedupe key; policy/environment changes cannot collide silently. |
| NeedsResources has no settled processor effects. | Discard tentative queues, gas and mutations; persist only acquisition/work intent and restart the invocation. Evidence growth may change its closed InvocationId without duplicating the application. |
| Missing/unavailable does not mean empty. | Only verified exact material can satisfy a need. Host timeout, quota or cancellation never fabricates a semantic status. |
| Semantic failure and operational failure/hold are different. | Recognized deterministic RUNTIME_FATAL can terminally consume managed work like gas failure; timeout, IO, cancellation and unknown internal exceptions cannot. Review exception classification before accepting this law. No failed successful-view cursor, fabricated epoch or rollback of independent source work; see22 §6.2. |
| Host quotas do not change semantic gas or chronological eligibility. | Needed80/limit100/allowance50 means pause. No cross-operation semantic fuel is added. An endless series of valid operations may block later repair; general interruption is outside the first POC, not a reason to skip pending input. |
| Receipt, complete result, affected state, cursor/obligations and outbox append commit atomically. | One short validated host transaction per complete invocation, not per edge/Handler/event. Immutable blobs may be staged earlier but are not settlement. |
| Successor selection authority is distinct from successor execution payload. | A complete current result may commit with exact durable successor references when its selection is proved, even if execution-only bytes for that successor are unavailable. Missing evidence needed for current correctness or selection still blocks commit. No second unowned planner is introduced. |
| Commit uncertainty is reconciled under the work fence. | Check the receipt before retry; no blind second apply or unfenced negative lookup. |
| Public events retain occurrence identity, both relevant ordinals, source and public projection fields. | Do not dedupe equal event values or republish historical source events merely because they were public at source. |
| Publication has semantic per-stream order and causal dependencies. | Publish contiguous local batches, including empty ones, under their required producer prerequisites. Global SQL commit timing never chooses semantic order or portable identities. |
| Trusted queries must be complete; fences only detect concurrent change. | Independently audit raw authored state against projections, including coherent omitted-row attacks. A recomputed hash is not a completeness proof. |
| All mutable semantic writers participate in appropriate scoped fences. | Target, occurrence, lifecycle and index reads are coherent. A short global commit mutex is only operational; unrelated sibling changes cannot enter semantic identity or cause indefinite source retries. |
| Stronger completeness evidence cannot revoke an already accepted immutable input prefix. | Later guarantees and above-boundary ingress do not repeatedly invalidate its semantic attempt. Continue checking genuine reservation, authorization and supported-scope changes under their own authority. |
| Waiting is level-triggered. | Persist the need and recheck durable facts; lost notification or supply-before-registration must not strand work. |
| Caches have no semantic authority. | Authorize first; exact keys/environment and byte/waiter caps apply. Cold restart reconstructs from durable state, not resident sessions. |
| Initialization and gas do not depend on materialization. | Canonical FULL_HISTORY source origin and source-local initialization policy are fixed under22; FROM_* selects observer history. Source80/parent20 with separate limits90 succeeds cold and warm. Idempotent successful publication settles source gas once; failed creators publish no new source authority or charge. |
| Handled input, successful source cursor and business epoch are different progress. | Terminal managed gas/recognized runtime failure keeps view/epoch. Gap evidence covers every lawful consumed outcome, including composite external-policy failure. In r2, align actual pin0→before1 through metered updates, then interpret original r2 transitions1→2; both can fail/roll back. No r1 event replay, forged receipt or repeated source execution charge. |
| Timeline entries and internal event occurrences are distinct. | Separate input operations preserve their historical views; internal E1/E2 emitted by one input share its FIFO/atomic boundary and do not create epochs per event. A frozen F1 payload1 differs from reading Parent.y2 when F1 is eventually dequeued in that same operation. |
| Final state is not a complete observation trace. | Preserve source views at actual handler boundaries, observable per-patch updates and event-admission order. Buffered patches before emissions can legitimately expose final state; triggered reactions between deliveries can expose intermediate states. Test both controls. |
| Logical delivery scope is complete before its semantic application. | Paging cannot choose handlers, gas or rollback. Derive grouping and visibility phases from the logical reference, not an all-pins-first or preload-all-events rule. Retain every occurrence route/cursor and valid frozen delivery. |
| New placements do not rewrite an already frozen original recipient set. | Initialization/installed-view observations are synchronous; selected historical operations use an ordered catch-up lane. Creator reads its actual installed view, never awaits its own commit. Pending history does not also consume live frames; rollback publishes no orphan successor. |
| Physical managed boundaries do not remove logical ancestor observations. | Preserve original event identity, frozen recipients, composed paths and actual queue order. A mandatory Parent-result relay is not proven sufficient; define authenticated observation evidence and prevent duplicate delivery without re-emitting the original. |
| Completion owner differs from original provenance. | Historical E10 imported by K100 creates new K100 obligations without reopening closed E10. Receipt identity construction must not hash itself. |
| Passive cyclic re-encoding is not business feedback. | Preserve same-epoch representation finalization/no-new-receipt rules. The triggering result/delta authenticates coupled exact-value publication; it creates no new business receipt, event/fanout or gas reset and does not group SCC business gas/rollback. |
| Independent work may progress; dependent work cannot overtake. | The one-way source and healthy Orders do not wait for a slow Order. Competing target writes and returning feedback require exact canonical order/completeness, never lease order. |
| Source history remains available to owed consumers. | Durable registration and retention close lookup/subscribe races. Empty current discovery does not authorize garbage collection or whole-cause completion. |
| Consumer topology is interpreted at semantic time. | A T20 removal excludes a later T30 receipt even when source T30 committed first physically. Retain valid earlier delivery and exact activation generations. |
| Processing and discovery frontiers prove different facts. | Discovery includes accepted but not-yet-materialized admissions/lifecycle decisions. Use incremental covered ranges and fenced history/live handoff, not a mutable-table keyset scan or all-body/source-commit barrier. |
| Host storage order is fixed and typed. | Work fence first, then unique mutation targets in the validated canonical order. No aliases, duplicates or database-dependent ordering. |

Identity-bearing platform counters/epochs/gas/timestamps/ordinals use their declared nonnegative
canonical JSON safe-range domain. Only their documented sentinels, such as
`ManagedRevisionCause.fromEpoch=-1`, are permitted. Signed declaration `order` and authored Blue
content retain their own current domains: `order=-1` is a normal value, not that sentinel.
Strings, exponents, fractions, overflow and timestamp rounding are not alternate counter encodings.
The exact Contracts environment, including all defined limits, is frozen for an invocation;
host budgets cannot silently replace it.

### Ownership

Language/Contracts defines exact processing, queues, gas, occurrences, graph finalization and results.
Coordination chooses eligible causes/actions and projects their atomic semantic effects.
MyOS supplies authenticated Timeline facts, authorization, complete indexed durable reads, work
discovery/scheduling and fenced commits. It owns delivery completion or exact terminal failure for
every due consumer, without requiring a global fanout transaction or aggregate terminal receipt.
Metrics and stack provenance are observability, not semantic inputs or specification profiles.

<a id="section-5"></a>
The [semantic corrections](21-semantic-equivalence-and-source-reuse.md) and diagnostic
[decision traces](20-decision-traces.md) govern these controls. A safety hold does not establish
required recovery, chronological observation or source-reuse equivalence.

## Workload and costs to prove

These are experimental scales, not production capacity promises.

| Experiment | Scales | Required observation |
|---|---|---|
| Shared Agreement | 1/10/100/1000, then 10,000/100,000 Orders | Independent source commit without enumerating N; required reactions/gas and total drain time may grow with N; bounded resources, fairness and healthy progress. |
| Platform and tenant cardinality | millions of unrelated documents; thousands active per tenant | Ready selection, reverse wakeups and temporal dependency lookup use scoped indexes; no ordinary all-platform/all-tenant scan. Validate PostgreSQL query plans and examined rows separately from returned work. |
| Selective connected DAG | 1,000 then 100,000 metadata nodes; five-lineage/six-occurrence affected spine | Report topology discovery separately from exact bodies; no hidden whole-component hydration or metadata scan. |
| Placements P in one consumer, independently of consumer count N | N=1; P=1/10/100/1000 where feasible | Complete atomic group, per-placement routing/charges, decode/hash/copy and metadata costs; resource-cap hold names an actual larger-capacity releaser, never gas splitting or unchanged retry. |
| Historical suffix | 0, 1, many receipts | Zero suffix creates no fictitious invocation; bounded next-receipt reads and exact progress. |
| Multiple applications | 1, 10, 100, 1000; bounded per-read materialization | Durable discovery plus bounded local definitions/cursors; no total fanout cap disguised as a read budget. |
| Multi-commit control state | fixed application count A=1; finite 10/100/1000 committed steps with growing owned/nested obligations | Measure cumulative control-state inspection, canonicalization, hashing, serialization, operation/receipt bytes and WAL independently of blueprint count and evidence rounds. |
| Publication backlog | B and 2B, with continuous tail appends | Bounded exact-successor/page processing; tail growth does not invalidate the selected next batch. |
| Evidence acquisition | batched and one-new-demand-per-round | Separate host copying/hash/WAL overhead from genuine processor restart/reverification. |
| Cyclic components | small cycles through current portable boundaries | Complete required SCC evidence; exact gas and rollback at the boundary. |

The current Contracts baseline includes portable bounds of 4096 closure documents, 16384 graph
edges, 128 cyclic members, 1024 cyclic edges, 16 MiB cyclic canonical bytes, 4096 graph changes/
expansions and 8192 work occurrences/finalizations. Bind the complete actual environment, not this
illustrative subset. A lower host cap may hold/requeue work, not change the semantic limit result.

For matched semantic input and named source-stage fault schedules, physical Agreement calls
must not grow with parent count. The uninterrupted closed-evidence control expects one; discovery
and recovery may legitimately repeat computation. Report tentative, closed and replay calls
separately. Both live and historical consumers read retained receipts without replaying the source
transition. Once Agreement commits, a consumer outage/retry cannot repeat that committed source work.
Delay or fail one Order while the source and healthy siblings continue; measure contention and
head-of-line blocking rather than accepting a hidden whole-root barrier.

The desired cost follows unique processed lineages, required deliveries/parents, touched topology,
selected Timeline pages, demanded exact content and result mutations. Indexed search may cost
O(log N); correctness can require a large fan-out or entire SCC. Do not promise constant work for
an inherently large affected closure. The locality spike must discover a sufficient scope and prove
it complete; simply omitting the global scan is not an algorithm.

The ordinary small related graph should finish in seconds with a bounded hot working set and
batched reads. This is not a graph-size-independent deadline for1000/10,000/100,000 consumers.
Source commit, consumer latency/throughput and backlog/fairness/resource bounds are distinct
acceptance dimensions; whole-fanout completion is optional derived reporting, not the universal
latency gate. Required N reactions are legitimate semantic work, unlike avoidable repeated source
execution or infrastructure copying. Freeze numerical latency, throughput and amplification budgets before acceptance
runs as described in [16](16-scenario-run-manifest.md). Measure cold starts and crash replay
separately. A correctness pass is not a performance pass.
Freeze SOURCE_OPERATION or CONSUMER_OPERATION as the acceptance measurement scope; its latency ends
at the corresponding durable local outcome, never at mere task scheduling. Optional whole-fanout
duration requires complete discovery and exact obligation outcomes and cannot be labeled local
latency. Report failures, pending/censored samples and publication lag honestly; neither an early
unrelated commit nor a later drain may artificially improve the scoped acceptance rate.

## Liveness is conditional, not another semantic shortcut

For a finite logical computation, eventual valid evidence, recovery and fair scheduling should
eventually finish it. A single logical feedback operation terminates by quiescence or its shared
semantic gas/limit outcome. The host cannot replace it with an endless chain of fresh budgets.
A genuinely unbounded sequence of separately authorized operations may be operationally paused with
its committed prefix intact; the cap never invents SUCCESS or semantic gas exhaustion. Scheduler
dependency deadlocks are defects, not legitimate business feedback.
