# End-to-end scenario cards

> **Status:** PROVISIONAL / EXPERIMENTAL · **Revision:** 15.12
> [Invariants](02-invariants-and-workload.md) · [Validation](11-validation-plan.md) · [Run manifest](16-scenario-run-manifest.md)

## How to use these cards

Each card specifies Goal, Given/When, Core result, Durable post-state, Forbidden results, Overlays
and Budgets. The machine-readable manifest expands the named subcases and maps each to exactly one
A–T owner. A card is a requirement, not a report that it already passes.

Default physical overlays are warm/cold cache, page sizes 1/2/default, one/two workers, JVM restart
and commit-ACK loss. Not every Cartesian combination is needed: use exhaustive schedules for tiny
semantic counterexamples, then risk-based pairwise overlays for larger fixtures. Pin the semantic
input and independently expected invocation boundaries before varying the physical schedule.
See [18](18-independent-lineage-processing.md) for the independent-lineage boundary and
[17](17-causal-processing-model.md) for the causal rules. Connectivity does not create an atomic
operation, a shared gas budget or a global processing barrier. The 53 schedule families and nine
named subcases remain; mandatory r15.4 fixture variants in [16](16-scenario-run-manifest.md) change
their expectations where the old connected-invocation model conflicted with the target.
The selected [processing kernel](22-processing-kernel.md) has semantic precedence; [20](20-decision-traces.md)
supplies its explicit traces. The EQ1–EQ8 variants below refine existing families. All added witnesses
are **PLANNED**; a hand-derived expected trace is not an executed result.

## Selected kernel witness cards

Each compact row inherits its named family's fault/physical overlays and complete card below. Core
expectations are hand-derived before running either implementation. Budgets count logical work
separately from physical reads/retries; exact gas comes from the reviewed manifest, not a guessed tariff.

| Goal / Given and When | Expected core result | Durable post-state / Forbidden | Schedules and budgets |
|---|---|---|---|
| **Observation:** one input queues E1,E2; Parent E1 emits P1, Source E2 emits F2. | FIFO delivers P1 before F2; intermediate document reads follow the recorded observation boundaries. | One complete result per owned operation; no preloaded final event batch or invented internal-event epochs. | D/N, EQ1–EQ5; split evidence reads, reverse workers; measure frames, decode and logical replay work. |
| **Aliases:** /left and /right point to Source0; a source patch advances to1. | /left's synchronous update reads /right0; the later event sees1 after both updates. Retiring /right prevents overwriting a replacement. | Exact placement/cursor/lifecycle delta; no all-pins barrier or per-page operation split. | C/D/N, EQ7; page1, changed handler, rollback; vary placements independently of consumers. |
| **Origin:** same X and E15; Orders attach FROM_NOW@T10, FROM_NOW@T20 or FULL_HISTORY. | X retains canonical FULL_HISTORY including E15; only observer selection/initial view differs. Separate source80/parent20 limits90 both pass cold/warm. | One source initialization authority/gas settlement, distinct parent results; no first-promoter history or cache discount. | A/C, EQ6; reversed promoters, failed creator, eviction; count source executions versus settlements. |
| **Creator:** a handler adds /b then reads it before historical catch-up. | Synchronous initialization supplies the selected initial view; no fabricated caught-up shadow. Later source epochs execute separately. | Creator rollback/create-then-retire publishes no orphan; committed attachment owns its fixed-cut lane. | A/E/N, EQ7; missing history, cold/warm, restart; one complete budget per actual operation. |
| **Failure:** r1 fails at consumer0, then r2 source1→2 is due. | One r2 operation aligns0→1 then replays1→2; early r2 event reads1, intervening local read reads0. | Whole r2 success or rollback; terminal failures remain exact, no page/range abort or fake r1 epoch/events. | E/F, EQ8; page1/2/large, live/historical, ACK loss; charge alignment once per new semantic operation. |
| **Feedback:** existing A↔B performs finite work; separately introduces or removes a return edge. | One SCC-owned gas/rollback scope; monotone in-operation expansion; new emissions can exhaust gas. | Both existing members publish atomically or neither; no fresh-meter hops or passive infinite route expansion. | J/N; vertex-simple route multiplicity, merge/split, demand/restart; meter before allocating paths. |
| **Publication:** no-delivery, independent source, and coupled existing A↔B. | Zero, one and many typed lineage projections respectively. | One exact reconciliation authority for all appends; no omitted member, wrong predecessor or fake no-delivery business stream. | G/K; DB cuts, lost ACK, reversed publisher; bounded successor queries. |
| **Timeline direction:** Order embeds Agreement with TO/TA; then aggregate embeds10000 Orders. | Stalled TO cannot block Agreement/TA. Real aggregate fan-in requires all relevant prefixes. | Durable indexed readiness/wakes; no reverse-observer scope expansion or incomplete membership proof. | D/M; quiet member, supply-before-wait, restart; initial build separate from incremental merge/query rows. |

## A — Independent Root admission and initial history

- **Goal:** prove admission handoff and historical observations without live/replay duplication.
- **Given/When:** materialize canonical source history through admission cut C; select observer
  attachment history with FULL_HISTORY, FROM_FRONTIER or FROM_NOW. Include unsettled
  below-C input, persisted at/after-C input, no-recipient causes, initial failure and operational
  hold. For initial source history let Agreement S start at 0, become 1 at t10, Parent P read it at
  t20, then S become 2 at t30; admit P at C40 with the initial S embedded. Contrast a dynamic
  attachment at t40. Include one external cause directly matching both S and P, fresh/nested
  children, reuse of identical authored child, stale/forged absence and ownership substitutions.
  Separate a direct `/child/counter` read from an event-maintained local counter: the direct t20
  read must see 1. For attachment at T1 before B becomes 5 at T5, a direct T3 read sees initial 0,
  while the local event counter remains unset. Keep the T1/T10 attachment variants' complete ordered
  original-entry sequence, including both T3 and T7 observations, exactly once.
  Add a T10 child-state change with no matching parent-update event: at T20 the direct child read
  sees 1 while the parent's initially unset reflected value remains unset. Authenticate the actual
  source transition; this is not permission to synthesize a state-only receipt.
  Also reconstruct a parent whose E20 removes or retargets a still-pending B occurrence: B@10 is
  due before E20, B@30 is not. Cover removal and replacement separately, including nested obligations.
- **Core result:** admission respects the two-sided cutoff handoff. Initial FULL_HISTORY records
  1 at P's t20 read, not latest 2. Independent traces define same-original-cause grouping and gas.
  Dynamic attachment imports according to its attachment policy, not an invented replay of P's
  whole past. Only owned prerequisites block their dependent history action; there is no blanket
  rule to drain every managed action before every historical entry.
  Eligibility is relative to E20's exact scope and historical position, not a demand that the old
  occurrence finish its entire admission-window suffix through B@30. The E20 result retires precisely
  the removed occurrence/generation's no-longer-applicable suffix; a replacement creates only its
  independently justified new-occurrence obligations. No future B event is imported early or later
  delivered through the retired occurrence.
- **Durable post-state:** one admission settlement; reservation/readiness remain until owned
  work completes. Initial failure installs no Root. Later failure retains prior committed steps
  and exact ownership-specific blockers. Operational hold releases nothing. Child creation is atomic.
  Removal/retargeting records exact suffix retirement and replacement ownership in the same complete
  invocation commit, without deleting B's source history, another consumer's work or still-applicable
  nested obligations. Earlier committed imports are not undone.
- **Forbidden:** duplicate live/replay delivery, future state in a past read, fabricated Timeline
  entry, feeder advance from target replay, arbitrary readiness bypass or absence-as-new shortcut.
- **Overlays:** every admission/replay commit cut, reversed proofs/claims, fresh JVM; all three
  chronological schedules and same-cause grouping run first as tiny hand-worked library tests.
  Direct-read, complete-entry-sequence, nested reconstruction, removal and retargeting are mandatory
  fixture variants of the existing schedules in [16](16-scenario-run-manifest.md), not optional examples.
- **Budgets:** bounded selected history pages and seconds-scale small admission; report required
  chronological invocations separately from physical retries.

## B — Historical replay target isolation

- **Goal:** one cause can be applied independently to several later targets without collision.
- **Given/When:** E settled live for R1; admit R2, finish its admission, then admit R3 in the same
  domain with E in history. Include retry, changed occurrence evidence and shared descendants.
  Simultaneous R2/R3 requests are a separate contention control: one reservation owner, the other
  busy/stale, never two active admission workflows. Test current target-bearing keys first.
- **Core result:** R2/R3 each receive their intended application once; R1 receives no replay duplicate.
- **Durable post-state:** separate create-only target application settlements, winning InvocationIds,
  and unchanged unrelated source/consumer input positions.
- **Forbidden:** cause-only dedupe, domain-wide replay recipients or shared gas/rollback across calls.
- **Overlays:** two workers/ACK loss for the same authorized application, evidence replan and
  admission contention. Cross-domain parallelism is separate. Reversing admission order changes
  semantic input and requires its own oracle, not an assumed cross-order equality.
- **Budgets:** no unrelated Root scan; count actual invocations rather than assuming one universal shape.

## C — Independent lineage applications and stable intent

- **Goal:** prove semantic target selection without an all-recipient atomic partition.
- **Given/When:** E matches R1/R2; one commits while the other is slow. Both may independently attach
  the same authored child, including an already READY neutral X. Add/remove a Channel or embedding
  at its semantic position while the source is physically ahead; vary the registration/scan race.
  Include multiple incoming paths and coherent omitted incidence. Queue one action before a legal
  unrelated commit, then execute it with fresh scoped facts.
- **Core result:** one shared lineage initialization, independent local application/gas/failure
  scopes and deterministic per-lineage histories. A read-only shared child does not require a merge
  or unsupported-join rejection. Bind each delivery to its source receipt and exact target occurrence;
  derive activation intervals and relevant completeness frontiers rather than freezing stale current
  recipients. Same-cause source/direct-parent ordering is independently specified. Feedback/shared
  writes/cyclic identities follow the selected directed-dependency SCC and monotone expansion rule
  in [22](22-processing-kernel.md), not an all-reverse-observer transaction or handler-specific guess.
- **Durable post-state:** source receipt and recoverable fan-out discovery basis; bounded discovery
  progress and create-only per-target obligations. Each local result commits with its cursor and
  output obligations. Discovery closure needs complete semantic membership evidence.
- **Forbidden:** global source/consumer lockstep, stale-index recipient omission, duplicate shared
  initialization, physical commit order in semantic identities, or arbitrary global gas regrouping.
- **Overlays:** reversed queries/claims, graph mutation between steps, fresh JVM, two workers.
- **Required negative control:** a parent attempts to write through `/agreement/...` into the
  authoritative managed source. Local observer processing must not mutate it implicitly. Without
  a separately reviewed supported nonlocal scope, hold/reject before publishing tentative effects;
  do not fabricate a Contracts fatal status to represent the scope decision.
- **Budgets:** bounded recipient metadata pages; no all-parent body preload; local control growth
  measured at 1/10/100 independent applications and source progress independent of a slow consumer.

**Required initialization contrast (A/C, EQ6):** hold authored X, complete environment and fixed source
policy constant; vary warm/cold/evicted/concurrent preparation and promotion. X has one source-local
canonical initialization and FULL_HISTORY basis, independent of the introducing Order. Source result,
event identities and logical gas match; reconstruction never settles source gas again. A retained
later view is valid selected historical evidence, not permission to initialize X differently.

Use the illustrative source80 and parent20, each limit90: both pass cold/warm under separate fixed
logical scopes. This explicitly differs from the current creator-combined invocation; it is not a
cache discount or a claim that old SDK ownership already matches. Derive actual test charges from
the selected manifest, not these illustrative numbers. Canonical preparation alone publishes no
authority; successful promotion settles source once, while a failed creator publishes no orphan.

FROM_NOW@T10, FROM_NOW@T20 and FROM_FRONTIER select observer history/initial views, not X's source
basis. With E15, X always retains E15; the T20 FROM_NOW observer does not receive E15 as a historical
event. Its initial view is the exact declared boundary view, not the cached latest head. Authored
FULL_HISTORY starts from initialization and consumes its selected epochs chronologically. Reverse
promoter order and delay either attachment; first INSERT cannot choose the source result. A failed
introducer cannot erase previously authoritative X, and incompatible environments are not cache variants.

## D — One Agreement, many Orders

- **Goal:** prove the main graph optimization.
- **Given/When:** N=1/10/100/1000, plus Phase-2/3 scaling at 10000/100000, in
  LIVE_INDEPENDENT_FANOUT and RETAINED_HISTORY_CATCH_UP.
  Include one slow, unavailable or gas-failing Order; Agreement advances T10=1, T20=2 while an
  Order's T15 read must see 1. Compare delayed consumption with a semantically later attachment.
- **Core result:** Agreement commits its transition once, without executing all Orders. Every
  entitled occurrence imports the exact retained transition in canonical local order. Healthy
  Orders and later source transitions progress despite another Order's lag or local failure.
- **Durable post-state:** committed source receipt plus durable recoverable fan-out obligation;
  independent Order result+cursor+output-obligation transactions. A failed Order cannot roll back
  Agreement or siblings; it remains an exact local failure and prevents an all-success aggregate.
- **Forbidden:** N source computations, omitted/duplicated entitled occurrences, latest-state reads,
  consumer-count-dependent source gas/identity, all-Orders atomic commit or RAM preload.
- **Overlays:** crash after source commit before discovery and after 17 of 1000 Order commits;
  reverse workers, page/batch=1, cold restart, duplicate/lost hints and ACK loss. Already committed
  source and Order prefixes survive; only each uncommitted local attempt is replayed.
- **Phase-1 minimum:** the source plus two Orders is a mandatory CORE fixture under
  `SOURCE-RECEIPT-EVENT-PROJECTION` and `SIBLING-MANAGED-FAILURE-ROOT-WIDE-FINALIZATION`: prove source
  commit before either Order, exact historical views, and a healthy Order progressing beside a
  gas-failing sibling. N=1000 integration/performance coverage does not replace this library gate.
- **Budgets:** source logical transitions and tentative/closed/replay calls separately; fan-out,
  parent work and total delivery time may legitimately grow with N. Independently budget source
  commit latency, recipient-page/transaction/materialization peaks, consumer throughput/lag and
  fair service to unrelated users. No all-N seconds-scale target or source-side enumeration barrier.

**Observation-evidence contrasts (D/N):** use [V4a](11-validation-plan.md#v4a--observable-views-updates-and-complete-local-scope).
EQ1 separates B's triggered E1/E2 updates (A reads [1,2]) from EQ2's single buffered handler
([2,2] can be correct). EQ3 records each observable 0→1→2 update, plus net-zero business 0→1→0.
EQ4 proves P1-before-F2 admission order rather than blindly preloading a flat source batch.
Final state plus own emissions is not a complete replay oracle.
Explicitly contrast two external entries creating distinct successful epochs (F1 observes 1, F2
observes 2) with two internal E1/E2 in one invocation: Parent queues F1/F2 behind E2, so later
Root F1/F2 handlers read Parent.y=[2,2]. F1's immutable payload can still contain 1. Event count is
not epoch count. Also vary U observable transitions per invocation separately from N consumers;
charge necessary reactions honestly while measuring avoidable evidence-copy/verification overhead.

**Grouping and creation (EQ7):** freeze logical recipients, not a database page. Derive alias
visibility from canonical per-placement update order: /left's synchronous update sees /right's old0;
the later event sees1 after both advance. A new /b does not join E1's already frozen targets.
Initialization and its required caused work are synchronous, while historical source epochs form
an ordered later attachment lane. Creator reads see the installed initial/boundary view, never a
shadow requiring unperformed historical reactions. Test rollback/create-then-retire and exact waits;
do not combine that history into one creator epoch or wait for the creator's own commit.
Use a two-event source operation: E1 through /a creates /b, while E2 is either already enqueued or
enqueued only after /b activation. For FULL_HISTORY, /b's pending history lane and live handoff must
give the selected source operation exactly one place; it cannot receive a live suffix and then import
the same observations again. For FROM_NOW, an already frozen E2 gains no new recipient, whereas an
eligible E2 first enqueued after activation uses the new topology. Record the exact activation view,
source-operation boundary and event list for both modes; worker progress is not the cutoff.

**Three-level observation (EQ5):** A embeds B embeds C. C's original E reaches A under ordinary
frozen-ancestor rules even when B emits nothing. Preserve its identity/composed path and actual
queue order under the managed mapping. B's own new F is distinct. A Parent-result relay is only
a candidate representation, not authority to suppress, delay or duplicate E.

**Failed-intermediate diamond (EQ5/F):** R embeds S at /s and through P at /p/s. S commits0→1 and
emits E; P's reaction fails with gas and retains0. R waits for P's terminal result, then receives the
valid original S observations: direct /s reads1 and /p/s reads0, with no tentative P updates or new
P emissions. Successful sibling P instead yields1/1. Durable R work binds that exact terminal outcome;
no absent P-success epoch or re-emission is required. Reverse workers, crash/ACK loss and cold reads
must preserve routes/order/gas. Source remains independent; count only actually owned reactions.

**Two scaling axes:** N independent Orders and P due placements inside one Order are different costs.
Exercise P=1/2/10/100 plus a preregistered larger supported value and an operational-cap control with
N=1. All placements belonging to one logical operation retain its complete gas/result/rollback scope. A host-cap hold is honest
operational nonprogress, not a semantic rejection or permission to split the group and reset gas.
It names the actual required capacity/releaser; demonstrate resumption after that capacity changes,
not endless retry under the same insufficient cap.

## E — Catch-up, causal source progress and feedback

- **Goal:** exclude unrelated future input without freezing legal same-root effects.
- **Given/When:** attach at cutoff T, require retained source receipts, then admit unrelated later
  input K3. Vary drain/page boundaries. In feedback cases S already contains C; C imports S history
  and emits a new event that reaches S through that existing relation. Exercise feedback on final
  and non-final imports, same-source/multiple-consumer and multiple-source/one-consumer obligations,
  interacting cohorts, nested work, zero suffix and authenticated eventless receipts. Two source
  receipts retaining the same E provenance can yield distinct consecutive consumer epochs only at
  distinct logical reaction/attachment positions. Converging receipts due at the same origin and
  position compose one consumer operation. Importing older E10 after K100 can yield decreasing
  provenance order. A consumer
  revision produced by the K100-owned import must not enter a cutoff-50 historical selection.
  Separately finish M1 with exact M2 identity/header/selection authority but unavailable M2 body.
- **Core result:** K3 is not absorbed into the attachment history. Exact receipts produced by the
  current root are tracked through their producer/predecessor, and activation is tested against
  actual source epoch/binding observations. No blanket source-write ban. Initial history and
  dynamic attachment are distinct. Supported eventless revisions authenticate their retained
  source application/result/companion/gas, not just an empty event list. Lineage receipt-chain
  position orders revisions; original sourceOrder is provenance, not a monotonic lineage clock or
  sufficient cutoff authority. Missing M2 execution-only bytes do not invalidate M1's complete result.
- **Durable post-state:** each selected receipt import settles with its cursor and created
  obligations atomically. Immutable selection basis is separate from derived target progress.
  Root completes only after its actual owned obligations are resolved. M1 commits and creates exact
  next authority/work while M2 suspends; resupply/restart applies M2 without reapplying M1. Corrupt M2
  body rejects. Missing evidence that actually selects M2 remains a legitimate prerequisite to commit.
- **Forbidden:** ambient-future absorption, stale-head activation, source-gate deadlock, omitted
  causal receipt, fused gas, state-only unauthenticated fallback or premature aggregate completion.
- **Overlays:** final/non-final feedback, every commit cut, reverse claims, continuous post-cutoff
  ingress, multiple plan orders. These source/activation traces are Phase-1 proof obligations.
- **Budgets:** bounded next-receipt reads; measure causal expansion and normal small-suffix latency.

**Completion ownership:** after live E10 is fully settled, a later K100 attachment imports its
retained receipt and newly emits F. E10 remains provenance, but F and its obligations belong to
K100's processing operation. Do not reopen closed E10, backdate F to T10, or lose F from K100's
completion proof. Distinguish relay of an original event from a newly emitted event.

## F — Semantic failure and dependency closure

- **Goal:** exact local rollback scope with no orphaned sibling work or readiness shortcut.
- **Given/When:** commits 0..k-1 succeed; k fails with each applicable deterministic status. Cover
  initial admission, replay and managed work, multiple plans sharing a consumer, sibling workflows
  under the same root and a later mixed cause reaching a failed embedded dependency.
- **Core result:** retain the complete status/gas/diagnostic. Roll back only invocation k.
  Project every owned failure/blocker and choose diagnostics deterministically. Block only actions
  genuinely depending on the failed local transition; healthy consumers and the committed source
  continue. A local attempt never partially commits. Temporary prerequisites, terminal local
  failures and aggregate cause accounting are different states.
- **Durable post-state:** previous commits survive, a failed managed-receipt import cannot claim a
  successful cursor advance or skip its source predecessor, foreign plans are
  untouched. Sibling authorized work drains; root-wide outcome respects its policy and failure ledger.
- **Forbidden:** compensation presented as rollback, readiness for work dependent on a failed import,
  rolling back source or healthy siblings, stranded independent work, erased failure, or a later local action overtaking
  its required failed predecessor. Aggregate failure is not a global scheduling barrier.
- **Overlays:** first/middle/last failure, reversed evidence, crash/ACK loss, outage controls.
- **Budgets:** one result/gas scope per invocation; bounded indexed ownership cleanup.

**Failure is operation-kind-specific:** a terminal failed external input can be accounted for
without business state, epoch or Contracts checkpoint advance, then permit a later valid detach
and new input. Reconstructing that same external history preserves its original terminal outcome
and ordering; historical execution alone does not turn it into a permanently pending import.
By contrast, failure importing source receipt r1 leaves the consumer's successful observed-source
cursor and reference before r1; r2 cannot run as though r1 succeeded. Retain failure separately from
business epoch and handled-input progress. Corrective work is tested against its actual scope and
prerequisites, not a blanket permanent-death rule or permission to bypass a failed dependency.
The existing live gas-loop/detach example is a seed for the first trace, not evidence that failed
managed-import recovery is already implemented.

**Required failed-import progress trace (EQ8):** source r0=0, r1@T10=1 and r2@T20=2. Consumer r1
ends with `GAS_LIMIT_EXCEEDED` or certified semantic `RUNTIME_FATAL`, retaining view0 and its
successful epoch. The next r2 operation
aligns the actual reference0→1 through an ordinary containing update, then replays its authenticated
1→2 observation program. Alignment and replay share one gas/rollback scope and create at most the
one actual successful consumer epoch. Only r2's original source events are replayed; alignment can
cause new consumer update reactions. No fake source r0→r2 receipt or successful consumer r1 cursor.

An event before r2's first update reads1; an eligible local read@T15 before r2 reads actual0.
Check shadow fields separately. Include same-business-state/eventless r2, internal net-zero updates,
alignment-driven emission/retirement/rebind and gas failure during alignment. A handler looping at1
may fail again even if r2's final2 would be harmless. D@T30 detaches after both earlier terminal
outcomes, never by erasing due T20 work. If no failures occurred, contiguous r2 adds no alignment.

**Two-producer alignment (EQ8):** Root embeds A and B. Both sources reached1, but Root's previous
combined reaction failed, leaving its actual pins0/0. The next origin changes both sources1→2.
Use the actual dependency-first comparator, with A's Entry/work before B's Entry/work; do not
artificially move Root's own seed ahead of its dependencies. A direct Root seed ordered after both
sources sees2/2. Align A only at A's first canonical Entry/consumption site: A's0→1 alignment callback
and1→2 patch callback both read B0. B aligns only when its own Entry is reached. There is no global
all-producer alignment prelude or early exposure of B1 merely because B is in the reaction group.
If A/B alignment emits GA/GB and their ordinary BOOT work emits EA/EB, the no-other-emissions control
admits GA,EA,GB,EB to the same FIFO, not GA,GB,EA,EB. Evidence/page acquisition order changes none of it.
Revalidate each placement at its site: an earlier A continuation can retire/retarget a B occurrence,
but cannot cause its replacement to receive the old alignment. In a diamond, shared producer cells
and their Entry execute once; distinct aliases retain their own eligible updates and cursors, without
duplicating an occurrence's alignment or writing through a failed intermediate producer's rollback pin.

Live and historical page1/2/larger runs, warm/cold/restart/reversed workers/ACK loss preserve exact
outcomes and gas. Do not stop/discard a range after its first terminal semantic failure: continue canonical
selection, which can select an earlier local input before a later source receipt. A fixed attachment
lane finishes after its obligations are terminal, reporting COMPLETED_WITH_FAILURES when appropriate;
later successful steps may have business effects. An unresolved provider/content wait is not terminal.
Phase1 implements this Coordination/Contracts rule; the full schedule matrix still requires its own
acceptance evidence. Handled positions distinguish APPLIED and exact terminal semantic failure dispositions;
successful-view cursors do not advance on either failure. Continuity also accepts a delivery legally
consumed under an earlier mixed external+managed operation's frozen policy, retaining that actual
status and policy evidence rather than reclassifying it as a gas failure.

**Classification control (F/EQ8):** compare recognized owning-library semantic faults (including
`AtomicScopeGasAdmissionFailure`) with cancellation, I/O/provider failures, missing evidence and an
unexpected implementation `RuntimeException`. Only the first class, authenticated as semantic
`RUNTIME_FATAL`, is terminally consumed by an ordinary managed import. Operational or unclassified
failures retain pending work and exact predecessors; a Java exception class/message is not proof of
a deterministic document fault. A failed initialization creates no initialized source and cannot be
turned into a usable initialization by the import-continuation rule. These are variants within F,
not additions to the schedule catalog.

## G — Live cause with no recipient

- **Goal:** close an eligible empty delivery without inventing a processor invocation.
- **Given/When:** complete semantic routing proves empty; separately remove a subscription at its
  applicable semantic position. Let source processing outrun target history/registration; an empty
  current index page alone cannot prove no entitled consumer. Supply stale evidence and coherent omission.
- **Core result:** exact NO_DIRECT_RECIPIENTS only from complete trusted queries; independent raw-state
  audit catches coherent omissions even when their supplied hash was recomputed.
- **Durable post-state:** one verified no-delivery disposition and exact discovery/local input
  progress, no fake empty processor application or global dependent barrier.
- **Forbidden:** absence from failed I/O or omitted index row treated as no recipient; fake gas/InvocationId.
- **Overlays:** insert/remove race, domain fence, empty page, ACK loss, index corruption.
- **Budgets:** bounded indexed routing; independent audits are measured off the hot path.
  Use transactionally maintained historical dependency indexes, not a current-only edge cache plus
  per-event history reconstruction. Prove indexed recipient/ready-work/reverse-wait queries and plans
  on million-document corpora, thousands active per user and sparse actual ready work. Coherent
  historical indexes are the intended fast path; completeness/registration races remain test cases.

## H — Historical candidate with no target delivery

- **Goal:** advance a examined target-history candidate without fake processing.
- **Given/When:** least exact candidate exists, but target routing is empty; positive control gains
  a delivery after its legitimate predecessor topology commit.
- **Core result:** cursor-only no-delivery decision, or one properly scoped application. No generic
  admission readiness exemption; required owned prerequisites and foreign blockers are checked.
- **Durable post-state:** target cursor advances once; unrelated lineage input positions do not move.
- **Forbidden:** zero-delivery gas/call, replay leakage, stale ownership or repeated settled candidate.
- **Overlays:** dynamic relevant Timeline surface, pages, crash and ACK loss.
- **Budgets:** one bounded candidate examination and durable cursor change.

## I — Missing resources and the lost-wakeup race

- **Goal:** restart safely and never strand work waiting for content already stored.
- **Given/When:** exact-node and managed-occurrence demands; one-new-item-per-round adversarial
  acquisition. Supply X after the failed read but before waiter registration; crash after storage
  before notification; lose every hint.
- **Core result:** no partial processor effects. Exact availability and changed semantic evidence
  follow their respective retry identity rules. Durable rechecking eventually notices X.
- **Durable post-state:** immutable work/need plus committed acquisition state; at most one winning
  application, no private queue/gas/continuation.
- **Forbidden:** lost wakeup, duplicate apply, treating missing as empty, cumulative host evidence
  copying/serialization presented as unavoidable processor work.
- **Overlays:** reorder supply, crash each cut, repeated demands, stale CAS, no notifications.
- **Budgets:** host delta/copy/hash/WAL and processor calls/reverification measured separately.

## J — Cycles, gas and multi-invocation feedback

- **Goal:** prove actual feedback's atomic/shared-gas boundary separately from passive cyclic
  representation and genuinely separate future operations.
- **Given/When:** self-cycle, A↔B, branching cycle, SCC merge/split and gas immediately below/at/above
  limits. Add a separate stream of newly authorized operations as the operational-pause control.
- **Core result:** caused workflow feedback stays in its required logical operation; it reaches
  quiescence or the actual shared gas/limit result. No host-created receipt hop gets fresh gas.
  Independent read-only observers do not become atomic solely through connectivity. Cyclic exact-value
  finalization and shared-write scope need concrete constructors/proofs; unsupported required
  positives cannot be counted as passing equivalence.
  Use the directed dependency SCC at the logical cut, including earlier relevant topology work.
  A return path extends ownership only after canonical atomic-scope admission; its accepted meter
  retains all existing consumption and stable execution-seed identities. A later detach does not
  shrink admitted rollback ownership. Freeze cyclic original-event routes as
  vertex-simple routes with no repeated DocumentId, including no transport echo to the emitter;
  distinct alias routes survive and a new emission starts a new metered route.
- **Durable post-state:** one complete atomic success/failure result for the logical workflow;
  no partial tentative publication. Earlier genuinely independent committed operations survive.
- **Forbidden:** global visited dedupe of distinct occurrences, free feedback, fresh budget per hop,
  infinite execution at a finite semantic gas limit, or an operational pause replacing gas failure.
- **Overlays:** page/cache/restart permutations and finite/looping controls. Operational pause applies
  only to genuinely distinct future operations, with its exact committed prefix retained.
- **Budgets:** gas equality, bounded attempt cost, required evidence and operational retry counters.

### J-DYNAMIC-SAME-ORIGIN — Newly coupled direct targets

**PLANNED witness within J; not an executed result or a new schedule-catalog ID.**

- **Goal:** distinguish deterministic execution order from atomic ownership when the same entry
  creates a genuine dependency between initially independent direct targets.
- **Given/When:** E directly targets initially independent A and B. A's handler attaches B;
  B's handler attaches A and causes feedback. Freeze the canonical direct-seed order independently
  of workers. Use one compatible fixed semantic limit L for the candidate scopes. At the proposed
  return edge, charge admission-check cost c to the initiating group's existing meter, then admit
  ownership before the topology mutation only if the distinct-group consumed sum fits L. Count c
  once. Compare local charge failure, scope rejection, admitted finite success,
  later gas failure and missing resources. One-way attachment and later-entry controls remain distinct.
- **Core result:** until an actual return-edge admission, one-way scopes remain independent. An
  accepted admission joins their staged ownership and exact consumed gas without reset/refund; a
  later detach does not shrink that admitted rollback scope. If the join cannot fit, reject the edge
  and fail only its initiating current group with certified `RUNTIME_FATAL` category
  `AtomicScopeGasAdmissionFailure`. Other groups can settle after accounting for that failure. Record
  authentic admitted gas and the required-scope-sum/limit diagnostic, not invented local exhaustion
  or a portable attempted-work ledger. If local c cannot fit, ordinary GAS_LIMIT_EXCEEDED rejects
  before checking or joining scopes; it does not charge c or acquire another group's ownership.
  Do not recompute this ordered admission decision from the final rollback graph.
  Direct seeds and caused work follow canonical order. Stable execution-seed/local occurrence IDs
  are distinct from the final settlement identity; joining never rewrites an earlier emission's ID.
  Missing evidence waits without publication. E2 cannot join or undo validly committed E operations.
  Freeze ranks from the pre-origin graph, never the discovered final SCC. In the A-before-B control,
  A activates B and immediately reads its counter before B's direct seed changes0→1: that read is0,
  not B's physically available final1. Later observations follow their actual canonical sites.
  A speculative B-first path reaching C that the canonical path does not reach adds no C ownership
  or charge. Gas exhaustion before admission creates no edge or later work.
- **Threshold witnesses:** L=100, A=60, B=30, admission=1 admits at91 with9 remaining; subsequent
  exhaustion rolls back both. A=60, B=50, admission=1 rejects B's attempted join with actual B gas51
  and required-scope111/limit100, leaves A independent and creates no B→A edge. The required sum is
  a diagnostic, not consumed joined gas. Exercise exact-fit and one-unit-over boundaries. For an
  already admitted AB group at80 and C at30, a further non-fitting admission fails its initiating group only:
  AB if AB attempts the edge, C if C does. No new budget is granted to the additional member.
  Aliases or several edges reaching the same current group must not count its gas more than once.
- **Durable post-state:** coupled success publishes owned results and selected-input settlements
  atomically; failure after accepted admission publishes no successful epoch for that entire group.
  Rejected admission does not retroactively acquire another group's rollback ownership. If a
  conditional A or AC attempt depended on B's tentative effects, B's failure invalidates that
  entire attempt: its result/status, group, gas, topology, events and dispositions cannot settle.
  Re-enter its genuine selected seeds from their exact committed pre-state against B's authenticated
  failure. Do not subtract individual B-derived patches or keep a conditional gas failure/group.
  Preserve the admission rejection at its original canonical attempted prefix; invalidating a
  dependent attempt does not recalculate B's diagnostic or undo an already published result.
  Old frozen proposals captured before the required join are fenced from independent publication.
  A resource wait retains work ownership, not a successful partial result.
- **Forbidden:** first-worker-wins order, independently committing half of the required coupled
  reaction, fresh gas for the joined member, applying E twice, accepting an obsolete pre-join
  proposal, or retrospectively rolling back E because a later entry creates an edge.
  Also forbid using a speculative footprint as admitted ownership, replaying with a retroactive
  shared budget to rediscover or undo admission, reanchoring prior emissions, reordering seeds on
  dynamic SCC discovery, or importing a later seed's final value into an earlier continuation.
- **Overlays:** reverse worker start/finish order, one/two workers, pause after each proposed
  attachment, resource supply before/after waiter registration, restart and commit-ACK loss.
  Keep the exact authored inputs fixed across schedules. Let E address Agreement and1000 Orders
  that newly attach it without return edges: every Order remains an independent consumer, no
  first-touch join changes Agreement's gas/history, and physical source completion or cache presence
  cannot choose the result. Preserve the same positive control for an already-authored one-way graph.
- **Budgets:** compare complete ordered histories, identities, gas and terminal settlements;
  count speculative restarts separately. Bounded tiny fixtures must not require global observer
  enumeration or a platform-wide same-entry transaction.

**Conditional-group invalidation:** A and C each have an independently required own E seed costing5.
B's tentative effects cause an additional reaction that admits an AC group; its canonical prefix
has spent61. B then pays its admission check and reaches51, so joining B to AC would require112
under limit100. Reject B with actual gas51 and diagnostic112/100. Because the AC attempt depended
on B's rejected effects, discard that whole unpublished attempt: no AC settlement, settled gas,
epoch or retained terminal status. Replay A's and C's own E seeds against B's failure; each costs5
and may settle independently. B's rejection and diagnostic remain unchanged. Stable own-seed IDs
survive re-entry, but an invalidated attempt cannot publish a wrapper around them. Nothing previously
published is undone. A companion has a conditional A attempt exhaust gas before B ultimately fails:
discard that conditional failure too; it cannot consume A's E or prevent its valid own-seed replay.
Reverse workers, interruption and evidence arrival, and explicitly reject retaining AC ownership,
subtracting patches/gas in place, or re-running B's rejected admission using the rebuilt A/C totals.

### J-AUTOMATIC-REATTACHMENT-CHAIN — Distinct successful operations

**PLANNED witness within J; not an executed result or a new schedule-catalog ID.**

- **Goal:** demonstrate the difference between gas-bounded work inside one operation and an
  unbounded chain of individually successful operations, including the POC's recovery limitation.
- **Given/When:** Source retains E. Importing E into A through `/a` retires `/a` and attaches
  `/b` with FULL_HISTORY; the next import retires `/b` and reattaches `/a`. Each operation succeeds
  below its fixed semantic gas limit and creates the next distinct import. Queue a later local
  detach entry D and configure a finite host account/document quota. Use a finite fixture variant
  that stops reattaching after N imports to verify eventual normal completion.
- **Core result:** each real import receives the ordinary per-operation meter; no inherited
  cross-operation semantic fuel or artificial gas failure is introduced. Host quota exhaustion
  pauses further work. D cannot overtake still-earlier required work merely because the host paused.
  The finite variant resumes to the same result after quota replenishment. The infinite variant
  has no POC guarantee of in-band repair while its earlier lane keeps generating required work.
- **Durable post-state:** retain every committed epoch and its settlement, plus the pending next
  import and later D. Pause neither rolls back the committed prefix nor marks the import completed.
- **Forbidden:** free feedback hops inside one operation disguised as this case, skipped imports,
  deduplication of genuinely new placement deliveries, fresh semantic budget on a retry of the
  same operation, or reporting host pause as repaired/terminated business state.
- **Overlays:** quota exhaustion at different operation boundaries, repeated pause/replenish,
  cold restart, page-size changes and lost ACK. Stop the test through its harness after a bounded
  prefix; that harness stop is not a semantic transition or a new cancellation feature.
- **Budgets:** bounded memory, dispatch concurrency and physical retry amplification under host
  policy; separately record each operation's gas and the committed-prefix length. No finite
  cross-operation termination claim follows from the per-operation gas limit.

**Passive-cycle control:** A↔B with one business change and no handler emissions must not create an
endless chain of representation-only business epochs or receipt imports. Separate exact cyclic
identity stabilization from genuine new business work. Record actual handler calls, representation
finalizations, receipts and cursors; report a concrete conformance failure rather than declaring
representation echo to be intentional gas-limited business feedback. Genuine feedback
still follows its reviewed metered local operation and causal rules.
An authenticated representation-only companion may change the exact representation under the
specified same-epoch rule. It creates no business epoch, business receipt, event, fan-out or handled
input advance. This narrow exception is not permission for an unauthenticated state-only import.

## K — Atomic commits, uncertain ACK and publication

- **Goal:** exactly one durable application and ordered recoverable publication.
- **Given/When:** kill before/during Contracts, after Complete, during commit and after commit before
  ACK. Include direct PROCESS, independent source and consumer steps, sibling failures, empty batches,
  action-created descendants, same-cutoff admissions and outbox backlog B/2B with continuous append.
  Explicitly cover zero business projections for no-delivery, one independent lineage and many
  already-existing lineages in a finite A↔B operation; separately add canonical creation authority.
- **Core result:** discard an uncommitted invocation; reconcile unknown commit under the work fence.
  Publish exact next terminal-chain batch, not sorted action IDs or arbitrary claim order.
- **Durable post-state:** complete result+effects+receipt+cursor/obligations+outbox are atomic.
  One reconciliation receipt authenticates the exact typed projection/append list and each stream
  predecessor. No existing member is encoded as newly created or omitted from atomic publication.
  ACK loss may resend identical sink keys; logical sink dedupe is part of the tested adapter.
- **Forbidden:** partial local-operation visibility, blind retry, duplicate next work, root completion while
  descendants remain, whole-tail revalidation per batch, or tail append invalidating the next batch.
  Different committed progress across Orders is allowed. Publication preserves semantic per-lineage
  and causal order, not an arbitrary cross-independent physical commit order.
- **Overlays:** every named cut, reversed publisher claims, DB restart, two JVMs.
- **Budgets:** reconciliation reads, bounded successor pages and linear backlog drain overhead.

## L — Provider outage and operational hold

- **Goal:** elapsed time cannot change history.
- **Given/When:** simulate a week-long outage, retry expiry, quota rejection and cancellation.
- **Core result:** no fabricated Contracts status or semantic skip.
- **Durable post-state:** committed prefix remains; local work/readiness/reservation obligations
  stay intact and can resume after evidence/recovery.
- **Forbidden:** timeout completion, dependent overtake or cleanup of an operationally held root.
- **Overlays:** admission, live and historical paths; recovery without notification.
- **Budgets:** bounded retry amplification; normal-path latency reported separately.

### L-QUOTA-PAUSE — Host allowance does not change semantic gas

**PLANNED witness within L; not an executed result or a new schedule-catalog ID.**

- **Goal:** protect host resources without making semantic results depend on account allowance.
- **Given/When:** fix a logical operation whose reviewed fixture uses 80 gas under a semantic
  limit of 100; the host account allowance is 50. Pause or deny admission under that host policy,
  replenish allowance, then execute/recover. Compare an otherwise identical run with sufficient
  allowance from the start. Account/document/custom operational limits are separate from the
  exact execution policy.
- **Core result:** insufficient host allowance causes an operational hold, not
  GAS_LIMIT_EXCEEDED and not a lowered semantic limit of 50. After replenishment the operation
  has the same result, epochs, events, identities and semantic gas as the uninterrupted control.
- **Durable post-state:** a pre-commit hold retains the pending operation and its exact semantic
  predecessor; a commit with a lost ACK is reconciled, not applied or charged semantically twice.
  Host reservation/release bookkeeping stays distinct from semantic gas settlement.
- **Forbidden:** consuming input on quota pause, advancing a dependent operation past the hold,
  reducing the invocation's semantic limit to remaining account credit, or double-settling gas
  because of physical retries.
- **Overlays:** pre-admission hold, safe abort before publication where supported by host policy,
  replenishment before/after wake registration, restart, duplicate worker and commit-ACK loss.
- **Budgets:** record operational reservations, physical attempts and semantic settlements
  separately; one logical terminal operation has one semantic settlement.

## M — Timeline order and completeness

- **Goal:** provider-assigned microsecond order independent of arrival and storage.
- **Given/When:** equal times across Timelines, illegal equality within one, append/guarantee race,
  quiet/lagging members and accepted but unnormalized input. Include assigned time ahead of wall clock.
  After accepting an exact coverage prefix/cutoff, strengthen provider guarantees and ingest only
  above-cutoff entries; repeat preparation and restart without changing that accepted input.
  Order embeds Agreement with TO/TA: stall TO while TA is complete. Then exercise a real aggregate
  embedding10000 Orders, including one genuinely missing member prefix and restoration of that prefix.
- **Core result:** timestamp then validated exact-entry text; exclusive completeness strictly beyond
  candidate from every required active Timeline. No provider-priority tie-break.
  Monotonic proof growth cannot invalidate an already accepted complete prefix, extend its cutoff,
  change its semantic identities/result or force a perpetual stale-proof loop. Conflicting evidence
  or a real in-scope semantic change remains a separate rejection/re-evaluation control.
  Agreement does not depend on reverse-observer TO and keeps progressing. The aggregate's directed
  fan-in does require its member prefixes; incremental frontier/index maintenance is not permission
  to omit a relevant Timeline. Restoring one prefix wakes precisely affected work.
- **Durable post-state:** guarantee-only advancement creates no event; predecessor/idempotency preserved.
- **Forbidden:** SQL collation, raw-ID alias, fake heartbeat entry, skipped ingress or premature proof.
- **Overlays:** reversed arrival/proofs, page-boundary restart, provider clock pressure.
- **Budgets:** indexed merge, guarantee lag and provider/normalization calls separately.
  Measure initial membership build separately from unchanged-topology next-entry work. Maintain
  shared membership/frontier aggregates and incremental merge; do not rescan10000 members per entry.
  Retain EXPLAIN/rows examined/frontier updates and work-wakeup counts as Phase-2/3 evidence.

## N — Same-graph equivalence and selective loading

- **Goal:** physical laziness changes costs, not semantics.
- **Given/When:** eager and lazy execution of the SAME authored managed graph and histories.
  Include nesting, sharing, SCCs and a connected DAG whose resolved expansion exceeds test heap,
  while the affected spine is five lineages/six occurrences. For one event E, Parent emits F while
  Root must still handle E; independently assert Parent(E), Root(E), Root(F).
- **Core result:** identical independently derived local boundaries and per-lineage histories, gas
  and identities. Discovery proves complete required delivery coverage without all-parent preload.
  Cross-lineage physical completion order is not compared as a semantic total order. Ordinary/managed
  common-law routing and observation parity under an explicit mapping is also required by EQ1–EQ5.
- **Durable post-state:** same exact histories/checkpoints/BlueIds after settlement.
- **Forbidden:** unproved ordinary/managed equality without a logical ownership/scope mapping, latest-state substitution,
  partial SCC evidence, nested F jumping E, private FIFO resume or dormant full scan.
- **Overlays:** caches/pages/restart, reversed incidence, corrupted complete-looking indexes.
- **Budgets:** topology rows, exact-body bytes, CPU and heap separately; eager/lazy ablation.

## O — Exact values and integer boundaries

- **Goal:** cross-runtime canonical identity and immutable transport values.
- **Given/When:** 0/max-safe/max+1, fraction/exponent/string/negative, documented fromEpoch=-1;
  exact timestamp Java/PostgreSQL round trip; nanoseconds, overflow, mutable arrays/collections,
  null members, duplicate keys and reordered evidence. Independently use Contract declaration
  orders -1/0/1 as valid signed values, through canonical codecs, PostgreSQL and cold routing.
- **Core result:** only canonical accepted forms; sentinels valid only in their specified field.
  Accepted values retain equality/hash/identity after construction and serialization. Declaration
  order is a signed field domain, not a negative counter or general permission for negative limits,
  epochs, timestamps or ordinals. The same negative number in a non-negative field must reject.
- **Durable post-state:** accepted exact values round-trip unchanged; rejection writes nothing.
- **Forbidden:** rounding, decimal-string fallback or observable timestamp in semantic identity.
- **Overlays:** codecs/runtimes/fresh processes, checked increment boundaries.
- **Budgets:** byte-identical constructor vectors.

## P — Cold restart, cache isolation and host fences

- **Goal:** database authority without resident document runtime.
- **Given/When:** restart between demands/commits; concurrent BEX compile misses for same full engine
  key and different environment/authorization keys; cancelled waiter and oversize artifact.
  Race every mutable semantic writer against proposal evaluation; contrast immutable acquisition,
  leases and unrelated ingress.
- **Core result:** bounded durable reconstruction; compilation coalesces within the authorized
  exact key. Scoped lineage/occurrence/frontier fences conflict on relevant changes, not independent
  sibling writes or merely operational activity.
- **Durable post-state:** same result as warm run, no serialized runtime or mid-invocation state.
- **Forbidden:** replay-all/findAll, cache authority, key alias, compile storm, unbounded waiters,
  untracked semantic writer or permanently invalidating harmless resource supply.
- **Overlays:** 0/50/100% eviction, DB restart, two JVMs, all writer families.
- **Budgets:** heap, reads, compile work, byte/waiter caps and conflict rate.

## Q — Replan and application settlement

- **Goal:** stable idempotency across changed closed invocation evidence.
- **Given/When:** A/B attempts differ by required occurrence evidence; mutate semantic environment,
  execution policy or claimed application binding independently.
- **Core result:** same unchanged application has one winning Complete; policy mismatch rejects.
  Exact Contracts environment and Coordination-only identities stay in their proper constructors.
- **Durable post-state:** one create-only application settlement recording winning InvocationId.
- **Forbidden:** InvocationId-only settlement key, cause-only dedupe, stale-fence commit or two winners.
- **Overlays:** concurrent A/B, reverse commit attempts and ACK loss.
- **Budgets:** one semantic apply; physical re-evaluation reported.

## R — Invalid and unavailable exact content

- **Goal:** resource outcomes cannot impersonate business evidence.
- **Given/When:** valid FOUND, UNAVAILABLE, absent, corrupted identity/body and cache poisoning.
- **Core result:** only verified supported material satisfies a demand; other outcomes follow
  their exact hold/validation rule without changing the business result.
- **Durable post-state:** no semantic progress on operational outcomes.
- **Forbidden:** no-recipient/empty substitution, skipped cause or leaked partial result.
- **Overlays:** provider flaps, retry expiry, corrupt object store and authorization denial.
- **Budgets:** bounded acquisition and no semantic writes on holds.

## S — Useful performance, not merely eventual completion

- **Goal:** small related graphs meet scoped seconds-scale budgets; large fan-out stays scalable
  and stable without requiring all N reactions to finish in the same fixed time.
- **Given/When:** warm/cold PostgreSQL, representative small/medium graphs, matched eager/lazy
  runs, increasing application count and operational cap+1.
  Separately hold initial application count A=1 and increase a finite nested/owned-obligation
  computation through 10/100/1000 committed steps. Freeze its distinct owned-obligation count and
  audit both counts; an A-scaling result does not cover this multi-commit workload.
- **Core result:** exact same semantics without invocation fusion or weakened rollback. Preregister
  source-operation or consumer-operation measurement scope; do not rename one as completion of all
  reactions. Optional whole-fan-out reports join discovery and obligation dispositions without a
  required global terminal receipt. Preserve failed/blocked outcomes and censored lower bounds;
  an unresolved tail cannot prove a latency PASS. Count completions only in the declared rate window.
- **Durable post-state:** complete local receipts/outboxes and per-obligation accounting plus reproducible
  measurement artifacts. Total cap+1 consumers progress through bounded pages; the cap constrains
  one materialization/read, not total fan-out. Oversized single-page requests hold without mutation.
- **Forbidden:** RAM-only acceptance, hidden quadratic prefix/plan rewrite, omitted commit or
  moving acceptance thresholds after results.
- **Overlays:** steady offered load, two JVMs, million-document corpus, thousands active per user,
  1k/10k/100k fan-out, overload/drain and unrelated-user traffic; faults measured separately from normal latency.
- **Budgets:** preregistered latency percentiles, throughput/backlog/error ceilings, physical source
  calls, indexed ready-work/recipient/reverse-wait query plans and rows/bytes, topology/body cost,
  allocation/hash/WAL and retained heap. Require bounded pages, transactions and concurrency, fair
  service and backpressure. Necessary O(N) reactions/gas are not avoidable platform amplification.
  The fixed-A series reports control-state canonicalization/hash/serialization, operation/receipt
  bytes, WAL, pending/owned elements inspected and unique immutable bytes retained across all commits.

## T — One evolving local baseline

- **Goal:** reproducible experiments without version-profile machinery.
- **Given/When:** local library/host changes invalidate earlier fixtures.
- **Core result:** review the changed semantics and rerun affected gates against that sole baseline.
- **Durable post-state:** SHAs/patches and run artifacts retained; isolated disposable state reseeded.
- **Forbidden:** runtime spec selector, compatibility bridge, migration/release gate or declaring
  the API final after one POC loop.
- **Overlays:** clean/dirty recorded checkouts, stale worker fingerprint.
- **Budgets:** reproducible reset/reseed/rerun, outside semantic identity.
