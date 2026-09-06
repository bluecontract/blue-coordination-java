# Validation plan

> **Status:** PROVISIONAL / EXPERIMENTAL · **Revision:** 15.12
> [Scenario cards](09-scenarios.md) · [Causal model](17-causal-processing-model.md) · [Manifest](16-scenario-run-manifest.md)

## Evidence hierarchy

1. **Hand-worked tiny semantic traces:** independent expected observations, invocation boundaries,
   orders, gas and activation. These can falsify a shared bug in both adapters.
2. **Executable current-baseline library tests:** clarify actual behavior; a known defect is not
   promoted to a golden expectation merely because a test currently requires it.
3. **Eager/lazy differential over the same managed graph:** pin authored identities, inputs and
   admission/attachment policy. Derive expected grouping independently, not by replaying SUT decisions.
4. **PostgreSQL fault and concurrency tests:** prove the host preserves the exact library result.
5. **Integrated scaling measurements:** prove the intended optimization works in the target-shaped stack.

The reference uses the selected [processing kernel](22-processing-kernel.md), including its exact
source/consumer ownership, observation program, canonical origin and failure-alignment rules.
Current connected-closure gas, rollback and event identities cannot be
copied as the target oracle. A changed library is compared against reviewed target rules; old behavior
is evidence of the required change. Phase1/2 implementation and scoped readiness verification are
complete; [the record](implementation/phase-1-2-readiness.md) lists actual results and exclusions.
This chapter remains the broader validation contract, not a declaration that all rows have run.
The retained seven-case r15.2 chronology experiment is historical auxiliary evidence only.

Ordinary and managed processing require an explicit logical ownership/scope/identity mapping.
Deliberately autonomous ownership can change combined gas/rollback; mere physical factorization
cannot change ancestor handlers, reads or event order. EQ1–EQ5 test those shared logical laws,
rather than excluding ordinary PROCESS from the comparison. Final state alone is insufficient.

## Phase 1: semantic gates

Run isolated tests of the actual local Coordination and Contracts implementations before adding the
PostgreSQL host. In-memory adapters and controlled resource providers are appropriate here; mocked
Coordination decisions or canned Contracts results cannot establish the semantic oracle. Immutable
fixture artifacts and independently inspected committed library state/results supply Phase-1 dataset
evidence. PostgreSQL durability, transactions and restart behavior are proved separately in Phase 2/3.

### V0 — Hand-derived kernel witnesses

These are **PLANNED**, not executed results. Use the compact witness cards in
[09](09-scenarios.md#selected-kernel-witness-cards) before differential testing. Each expected trace
is written independently of the implementation and records operation/epoch boundaries, exact views,
ordered update/event occurrences, logical gas ownership, terminal dispositions and publication scope.
They refine existing A/C/D/E/F/J/K/M/N families and EQ1–EQ8; they are not new schedule families.

| Witness | Hand-derived required distinction |
|---|---|
| Observation / EQ1–EQ5 | Source1 then2 can be observed within one operation; BOOT E1,E2 with Parent-P1 at E1 and Source-F2 at E2 yields P1 before F2. Separate source operations instead retain their separate historical views. |
| Placements / EQ7 | /left update completes while /right still has0; a later event sees1 after both updates. Retired/rebound paths are never overwritten as the old occurrence; already frozen events keep their actual dispatch law. |
| Canonical origin / EQ6 | One authored X always has canonical initialized FULL_HISTORY including E15; observer FROM_NOW@T20 omits E15 delivery, not X's history. Source80 and parent20 under separate limits90 both pass cold/warm. |
| Creator / EQ7 | A permitted creator read sees installed initialization, not a shadow field requiring staged historical catch-up. Later source epochs remain separate operations and a missing history body waits only where needed. |
| Recovery / EQ8 | Failed r1 leaves view0; r2 aligns0→1 then replays its own1→2 in one operation. An early r2 event reads1, an intervening local read reads0, and neither a page boundary nor gas failure abandons a history range. |
| SCC / J | Existing A↔B finite feedback owns both members atomically with one meter. A newly introduced return path expands that same tentative scope; true re-emission can exhaust gas, unlike passive route/representation closure. |
| Publication / K | Metadata-only no-delivery has zero business projections; independent work one; an existing coupled scope many, plus separately identified creation authority when applicable. |
| Timeline direction / M/D | Agreement's TA readiness is independent of reverse observer TO; an aggregate genuinely embedding 10000 Orders requires their relevant completeness, maintained and queried incrementally. |

Mutation controls deliberately use final-only pins, preload source events, add an all-pins barrier,
make initialization depend on the first promoter, silently align a failed view, abort an import
page, split SCC gas, omit one lineage projection, or include all reverse observers in source
readiness. Each must fail a named independent oracle. Planned traces do not become evidence merely
because two implementations agree.

Additional composed controls stay in those same families:

- `J-DYNAMIC-SAME-ORIGIN` in [09](09-scenarios.md#j-dynamic-same-origin--newly-coupled-direct-targets):
  E targets initially independent A/B whose handlers establish genuine feedback. Hand-derive the
  canonical direct-seed order and atomic-scope admission before the return-edge mutation. Accepted
  admission unifies existing gas/ownership without reset; rejected admission fails only the
  initiating current group. Freeze the decision at its canonical prefix, not a recomputed rollback
  graph. Stable seed-local event IDs are distinct from the final settlement identity. Vary physical
  schedules and retain one-way independent-source and later-entry controls. A new edge alone cannot
  retroactively merge already validly committed work.
- `J-AUTOMATIC-REATTACHMENT-CHAIN` and `L-QUOTA-PAUSE` in [09](09-scenarios.md): distinguish
  an infinite operation from indefinitely many individually successful operations. The latter
  has operational protection, not inherited cross-operation semantic fuel. The host quota oracle
  is a pause with preserved progress and no input skip; its replenished finite control must match
  uninterrupted semantic replay. These are planned witness labels within J/L, not new catalog IDs.
- One entry directly targets Source and Parent while Parent embeds Source: one Parent operation
  combines its direct seed and upstream projection. SUCCESS advances the exact lanes together;
  consumed external-policy failure records every selected lane under one settlement without a
  successful epoch; a blocking disposition consumes none. Pure-managed status rules are unchanged.
- A cold FROM_NOW@T20 needs source initialization plus E15 history. Prepare a bounded immutable
  source-operation prefix, including a variant where E15 fails terminally without an epoch. Before
  parent SUCCESS no staged history/index/outbox is visible; activation publishes the complete
  required authority atomically. Crash before/after activation, concurrent matching promotion and
  source-stream membership mutations cannot duplicate gas or lose an append. Vary prefix length
  and page size; final activation transaction/working memory must not grow with all prefix bodies.
- Reject a result/program hash cycle: Exit binds a nonrecursive execution summary, then the program
  root is derived, then the complete result. Borrowed upstream cells are interpreted once at their
  original sites while distinct alias deliveries remain distinct. A producer cannot borrow its
  own future program/result or omit a required upstream action.

### V1 — Application identity and complete values

Test R1 live / R2–R3 historical replay, disconnected target applications and stable application
settlement after occurrence-evidence replan. Mutate each execution/environment/target/cause binding
independently. Prove one winning InvocationId, not InvocationId-only or cause-only dedupe.
Cross-check current code, specification and constructors before adding a new identity.

Same-domain R2/R3 admission is sequential. Concurrent requests prove reservation contention, not
two active admissions; two-worker races apply to the same authorized application. Keep cross-domain
parallelism and deliberately changed admission-order inputs separate.

The sequential R1-live/R2-replay/R3-replay trace is the mandatory CORE subcase
`SEQUENTIAL-R1-R2-R3-REPLAY` of `SAME-APPLICATION-COMMIT-RACE`, not a test deferred to the HOST realm
schedule. It must reject a settlement-key mutation that distinguishes live from replay but omits the
replay target: R2 and R3 each settle once, and neither repeats R1. HOST subsequently repeats this
behavior through PostgreSQL and adds realm isolation and admission-contention checks.

Verify the complete current Contracts environment and all limits, exact type allowlist, ordinary
safe integers/documented sentinels, cyclic proofs, defensive copies, equality/hash, null/duplicate
rejection and serialization round trips. Compilation alone proves none of these.
Add positive signed Contract declaration-order -1/0/1 vectors through canonical encoding and cold
routing in Phase 1; repeat them through PostgreSQL in Phase 2/3. Compare with rejection of -1 in a
non-negative counter/limit/ordinal: rejecting all negative input is not conformance.
Semantic-configuration fields remain non-negative.

### V2 — Local applications and complete semantic delivery coverage

Independently derive each lineage's next action, exact input view, cause/occurrence identity and local
gas/failure boundary. A source transition commits before its independent consumers. Two parents may
reuse the same authored neutral child without merging transactions or rejecting the second attachment.
Test fresh/nested initialization, identical authored reuse and forged/stale absence. A queued action
survives unrelated progress; changing its receipt, local predecessor or occurrence is rejected.

Fan-out discovery may be paged after source commit, but must remain complete under target history lag:
source at T30 plus parent removal at T20 means no T30 delivery through that occurrence. Test attachment
registration before/during/after source commit and discovery, multiple paths, lost/duplicate hints and
coherent missing rows. Activation intervals and membership-frontier evidence close the delivery set;
an empty current index does not. Retain source receipts through delayed discovery and consumption
until all owed work is accounted for. Source commit does not load all parent payloads into memory.

Same-original-cause source and parent direct subscriptions have hand-derived local order/multiplicity,
independent of worker order. Use the selected directed-dependency SCC scope and its monotone
within-operation expansion, not all reverse observers or a handler-specific independence guess.
An unsupported implementation case is not a Contracts failure or passing conformance.
Initialization proof is separate from duplicate-row suppression: fix the complete authorized input,
history and operation ownership before warm/cold/evicted/concurrent permutations. Semantic gas,
observations and emitted identities must agree; physical source calls/cache costs may differ.
Do not mistake cold CPU cost for semantic gas, but preserve all required logical initialization
charges in the warm run. Retained reconstruction is not rebirth and first INSERT is not authority.
Distinguish non-authoritative canonical preparation from authorized source publication and parent
attachment. Standalone and creator-promoted publication use the same canonical initialization;
already authoritative sources survive unrelated parent failure.

**EQ6 — selected BIRTH law:** authored X has one canonical source-local initialization and
FULL_HISTORY basis under its frozen environment/policy. Use independently costed source80 and
parent20, each limit90: both pass cold and warm under separate logical budgets. Source gas settles
once at authorized publication, not again after eviction or a losing promoter. These illustrative
numbers define an ownership contrast, not a new processor tariff. Actual fixture gas is independently
derived from the reviewed manifest. Candidate verification alone publishes no authority; a failing
creator publishes no orphan candidate while preserving an already authoritative X.

With E15, compare observer FROM_NOW@T10, FROM_NOW@T20 and FULL_HISTORY. X's canonical history includes
E15 in every case; the observer's selected history and installed initial view differ exactly as
declared. Reverse promoter/discovery order, delay either accepted intent and rebuild evicted content.
Require identical source operation/result/event identities and source gas authority; parent histories
retain their distinct attachment inputs. First INSERT, absence and promoter budget cannot choose
source history. Different environment/policy is an explicit incompatible basis, not another cache state.

Membership coverage includes Roots accepted earlier in semantic order but not yet materialized.
Race their admission/registration handoff with source append and fan-out pages. Complete current
physical rows alone cannot close discovery if accepted lifecycle work may introduce entitlement.
Under CORE `NEW-SHARED-CHILD-PARTITION`, pair valid read-only neutral-child sharing with an attempted
write through `/agreement/...`. Without a reviewed supported nonlocal scope, no source or tentative
consumer effect publishes; the explicit scope hold/rejection is not an invented runtime-fatal result.

Before accepting G1, run a tiny source-plus-two-Orders fixture under the already-CORE
`SOURCE-RECEIPT-EVENT-PROJECTION` and `SIBLING-MANAGED-FAILURE-ROOT-WIDE-FINALIZATION` schedules.
Prove source commit before either consumer, source T10=1/T20=2 with Order@T15 seeing 1, and source/
healthy Order progress when the other Order deterministically exhausts its local gas. Retain the
failure and deny aggregate success. This mandatory semantic fixture is not deferred to the D-family
N=1000 integration/performance rows or to PostgreSQL; Phase 1 uses the revised library directly.

### V3 — Admission chronology

Before invoking the adapter under test, hand-author S0 → S1(t10), Pread(t20), S2(t30), admission C40.
Expected P observation is 1. Add same-original-cause source+parent, shared source, equal-time
different Timelines and dynamic relevant subscription surface.

Assert the ordinary parent invocation's direct `/child/counter` read at T20 equals 1, separately
from any local counter maintained by child-event handlers. A resolver that uses B@30=2 during
the parent read must fail even if the shadow counter still equals 1. For attachment at T1 with
B initially 0 and B@5=5, the direct child read at T3 must see 0 while the event-maintained counter
is still unset. These are distinct assertions, not interchangeable definitions of observation.
Include a T10 change to 1 without an event matching the parent's reflection handler. The T20 direct
read is 1 and the initially unset reflected value stays unset. The actual source receipt must still
authenticate that transition; do not replace it with an unproved snapshot. This positive contrast
prevents the test harness from silently equating the direct and event-derived oracles.

Contrast initially embedded FULL_HISTORY with later dynamic attachment; test FROM_FRONTIER/FROM_NOW
boundaries separately. Admission reservation handoff must exclude duplicate live/historical input,
respect below-C settlement and retain ownership across crash. Target replay and no-delivery cursor
decisions never advance another lineage's input cursor or erase its obligations.

Make the attachment contrast observable: B emits `CounterChanged(5)` at T5. A attaches to B at T10
and imports that history, so A's counter is unset before T10 and becomes 5 during that attachment's
catch-up. In the alternate authored history A attaches at T1, so it records 5 as a consequence of T5
through the existing relation. Both finish at 5; their exact histories differ. Physically later
reconstruction must preserve the T1/T10 distinction, not retimestamp the event or fuse catch-up calls.
Retain every original A entry identity and assert the complete ordered exactly-once subsequence:
attachment-T1, observation-T3, observation-T7; or observation-T3, observation-T7, attachment-T10.
Keep legitimate managed imports separate from this original-entry subsequence and verify their own
positions. Dropping T7 in the T10 case cannot pass merely because both observations equal unset.

Also test nested initial reconstruction: C emits value 1 at T10; initially authored B embeds C and
records `lastSeen=1` while FULL_HISTORY is reconstructed under cutoff40. Initially authored A embeds
the same B lineage and has a T20 read of B.lastSeen; reconstructing A under cutoff60 must record
`observed=1`. Pair this positive case with the K100-owned attachment negative in V4. Admission cutoff
alone cannot hide reconstructed earlier history, and old source provenance alone cannot backdate
new attachment effects. The exact producing action, relationship policy, replay cursor and receipt
prefix must justify visibility. These must run as actual isolated Coordination+Contracts tests before
PostgreSQL, under the existing initial-history and attachment schedules, not additional schedule IDs.

The retained seven executable r15.2 chronology cases are narrower evidence: they do not assert these
direct-child-read or complete original-entry-sequence controls, nor the nested and removal/retargeting
variants below. Their historical results cannot be relabeled as coverage of these requirements;
new implementation coverage must be traced to the current readiness record, not inferred from them.

### V3a — Dependency eligibility and deadlock freedom

Every selected action has scoped prerequisites, including managed reads, processing, rebinds,
required ancestors/SCCs and final writes. Demand-driven expansion revalidates them before effects
can settle. New exact content, existing unready lineage, owned historical prerequisite, foreign
obligation and terminal failed dependency are distinct cases.

Construct the old deadlock: application R1 creates W1, R2 needs W1's source, an invented phase rule
forbids W1 before R2. The proposed selector must either choose the legal releaser under proven grouping
or detect a genuine unsupported semantic case; it cannot report external provider unavailability.
Reverse evidence/claims and obtain the same selected action/blocker. Same-root ownership alone is
not blanket permission to read partially caught-up state.

Add E20 removal and retargeting of pending B during initial history under cutoff40: B@10 is due,
but B@30 must not be imported just to make E20 eligible. Derive eligibility from E20's actual scope,
historical position and required predecessor prefix. After the complete E20 result, retire precisely
the old occurrence/generation's no-longer-applicable suffix; replacement creates separately owned
obligations with its own justified selection. Assert that neither an old B@30 reaction nor its work
survives through the removed occurrence, and that earlier commits, foreign consumers and any
still-applicable nested obligations survive. A blanket "pending means every parent operation waits"
deadlocks this trace or imports future history before the operation that removes it. This is a
mandatory fixture variant of `ADMISSION-OWNED-PREREQUISITES-BEFORE-DEPENDENT-HISTORY`, not an arbitrary
readiness bypass. Scope-sensitive checks must still reject a real missing predecessor or foreign hold.

Under `ORDERED-LATER-CAUSE`, accept a complete exact coverage prefix, then independently strengthen
provider guarantees and add only above-cutoff ingress. Reprepare/restart and require the same accepted
prefix, cutoff, semantic identities and result, without a perpetual stale-proof conflict. A fresher
physical witness is not a new semantic input. Mutated covered content, incomplete normalization and
real in-scope changes remain negative controls; the test grants no blanket freshness exemption.

### V4 — Catch-up, feedback and activation

Hold unrelated post-cutoff input constant while varying drain calls/pages. Then add legitimate
same-root feedback: source S already contains C, C imports S history, its new event reaches S and
changes S. Cover the first, middle and final receipt steps, multiple consumers/sources and nested
obligations.

Assert exact selected receipt/predecessor, source epoch, pending/active binding, newly produced receipt,
cursor and next action. Final/non-final results follow reviewed independent-lineage activation rules;
identify owning-library changes, rather than copying current connected-closure behavior as the oracle.
Authenticate supported eventless same-state revisions from their owning application/result/companion/
gas; reject an unproved state-only shortcut. Zero suffix creates no invocation.

Two imported source receipts retaining E provenance can produce consecutive consumer epochs with
equal original sourceOrder only when their logical reaction/attachment positions are distinct.
Receipts converging at the same origin and position compose one consumer operation; receipt count
alone cannot add an epoch. An older E10 imported under K100 can decrease that provenance order. Neither
case is corrupt if the exact receipt chain and causal production proof are valid. A revision produced
by the K100-owned import is excluded from cutoff50 even when its original provenance is E10. Test
chain position, original origin and historical-selection authority independently, with forged
producer/cutoff controls. Merely changing strict sourceOrder increase to nondecrease is insufficient.

With M1 Complete and exact M2 header/selection authority available, withhold only M2 execution body.
M1 must commit once and create exact next authority/work; M2 may suspend, then fetch and verify its
body after restart without reapplying M1. Mutated body rejects. If the missing facts determine which
step is next rather than only its execution bytes, waiting before M1 commit remains legitimate.

Close E10, then let K100 import its retained source receipt and emit new F. Prove original provenance
E10, producing/owning operation K100, and K100's obligation/completion membership independently.
E10 must remain closed; F must not enter earlier historical selection or disappear from K100's
accounting. Original-event relay and new emission are separate identities and delivery obligations.

### V4a — Observable views, updates and complete local scope

Freeze the exact workflow, not the ambiguous phrase "0→1/E1→2/E2". Require these contrasting
hand-derived traces, mapped to existing SOURCE/EVENT-BATCH schedules:

1. **EQ1 — triggered intermediate reads.** B starts at x=0. Its external handler emits E1 and E2;
   B's triggered E1 handler sets x=1 and triggered E2 handler sets x=2. A observes B during each
   embedded-event delivery. The ordinary reference reads [1,2]; final-after replay would read [2,2].
2. **EQ2 — buffered-effects control.** One handler buffers both patches and then both emissions,
   with no intervening triggered changes. Patches execute before emissions; [2,2] can be correct.
   This control forbids inventing a snapshot at every authored emit statement.
3. **EQ3 — update-sensitive observation.** B patches x=0→1→2; A watches the nested x path and
   records [1,2]. Also test 0→1→0. Equal final business x does not erase required update callbacks
   and does not imply equal complete checkpoint/BlueId. Replacing all of B once is insufficient.
4. **EQ4 — queue admission.** B queues E1/E2. A emits P1 while handling E1; B emits F2 while
   handling E2. Under the shared logical FIFO, P1 precedes F2. Preloading a retained flat
   [E1,E2,F2] source list before A reacts incorrectly puts F2 first.

Keep two distinct controls explicit. Two external Timeline entries cause two successful source
epochs and their separately ordered Parent results; Root sees F1→1, then F2→2 even if the producer
physically ran ahead. In contrast, one invocation queues internal E1/E2; Parent handles E1 by
setting y=1 and emitting F1, then E2 by setting y=2 and emitting F2. FIFO becomes E2,F1 after E1,
then F1,F2 after E2. Root's F1/F2 handlers read y=[2,2], while F1's immutable payload can contain 1.
Preserve original E1/E2 ancestor observations as well. Do not derive epoch count from internal event
count or let physical receipt completion choose queue order. Freeze the small exact queue trace
before comparing ordinary and managed execution.

The evidence must authenticate the views, observable updates and admission boundaries that explain
these observations, not merely a final receipt hash. Define its minimal derivation/validation in the
owning library; do not assume a full processor trace or a per-event public snapshot API is necessary.

For aliases /left and /right, apply each source patch in canonical occurrence order and finish the
first placement's synchronous update continuation before the second. /left's update handler sees
/right at0; after both updates, the later event sees1 unless authored work changes it. Revalidate
each not-yet-updated occurrence; never patch a retired/rebound replacement as the old occurrence.
Complete membership beyond pages remains required;
pages, worker order and cache state cannot split one logical gas/rollback scope. Include a
second-reaction failure and valid frozen E2 after E1 retirement, with a separate later-delivery control.

**EQ7 — selected creator boundary.** An E1 handler through /a creates /b. Keep the original direct
recipient set frozen. Initialization, its selected installed view and required synchronous work are
available to permitted creator reads; existing historical source epochs form a separate ordered
attachment lane. An authored-from-origin /b read initially sees0 and its event-maintained shadow is
unset; later historical receipt1 can set it to1 in its own operation. A FROM_NOW attachment instead
installs its declared boundary view, waiting if that exact view is unavailable. Creator reads never
fabricate historical catch-up. Compare new/cached initialization, creator rollback, create-then-retire
and resource wait; publish no orphan source or historical work. Do not replay /a because /b appeared.
Add two events inside one source operation: E1 through /a creates /b; E2 is either already enqueued
or first enqueued after that activation. Freeze the precise activation view, source-operation boundary
and expected /b event sequence separately for FULL_HISTORY and FROM_NOW. A pending FULL_HISTORY
lane cannot consume a live suffix and then replay those same source observations. FROM_NOW does not
add /b to already frozen E2 recipients; a new eligible E2 enqueued after activation uses the changed
topology. Page sizes, delayed source completion and cold/warm materialization must preserve this
logical split, with neither a duplicated source operation nor an implicit creator-history epoch.

Keep a separate dispatch-surface control: enqueue E1 then E2, and let E1 replace a Triggered or
Embedded event channel on an already eligible receiver. E2 uses the replacement from the receiver's
canonical state at its delivery, while a newly activated occurrence still receives no already queued
E2. Contrast a document-update watcher added by the very mutation being dispatched: that new watcher
does not run for its own creating update. Check both against ordinary Contracts, not a host's latest
head or one blanket enqueue-frozen channel rule.

Scale P placements separately from N consumers. Measure complete scope, evidence, materialization,
gas and retries. A capacity hold publishes nothing and names a real releaser; after capacity is
supplied it resumes with unchanged semantics. Neither partial commit nor per-page gas reset is legal.

### V4b — Ordinary and managed three-level observation

**EQ5:** A embeds B, B embeds C; C emits E, B emits nothing. The ordinary PROCESS reference delivers
the original E to B and A through frozen ancestor routing, including A's composed /b/c path.
Managed factorization must preserve this logical behavior under an explicit scope/identity mapping;
a mandatory new emission by B or a mandatory committed B relay is not an established solution.
Add B emitting F and assert original occurrence versus new occurrence, actual queue order,
frozen route multiplicity, views and completion ownership. Include ordinary/managed comparison,
not just two PROCESS_CLOSURE adapters that can share the same gap.

**Failed-intermediate diamond:** R embeds S directly at /s and through P at /p/s. S changes0→1 and
emits E; P's corresponding independent reaction exceeds gas and preserves its actual view0. R waits
for P's terminal outcome, not for a successful P epoch that will never exist. R still receives valid
original S observations under the composed frozen routes; P contributes no tentative patches or new
emissions. In R's settled reaction, the direct /s read is1 and /p/s is0. Record exact route multiplicity,
ordering, gas and the failed-P dependency evidence. Source does not wait for R or P. This is selected
independent-failure semantics, not equality to the old all-connected rollback boundary. Reverse worker
order, restart after P failure and lose its ACK; none may suppress S's valid ancestor event or expose
P's rolled-back work. In the all-success companion, both reads are1.

Mutate the original identity, path, recipient set or admission order; omit A's E because B emitted
nothing; deliver E both directly and via relay; substitute final state at a required intermediate
read. Each must fail the independent oracle. These are planned fixtures, not executed evidence.

### V5 — Queues, gas, rollback and termination

In the no-earlier-G control, event-order vectors include Parent(E), Root(E), Root(F) after Parent
emits F. The V4b reference-update-G control preserves its actual earlier queue position. Cover cyclic
revisit, SCC merge/split, immediately below/at/above gas limits and every applicable terminal status.
No global visited shortcut or fresh gas per feedback hop. The actual required logical operation,
including a cross-document feedback cycle, has shared gas and atomic rollback; independent Orders
must not share a budget merely because they observe the same Agreement. Source commit before live consumer reactions
is a required positive case. A bad Order's gas or certified semantic runtime failure rolls back only that local attempt,
never committed Agreement or healthy Orders. Local gas/event/receipt identities are independent of
consumer count and physical worker commit order.

NeedsResources discards all tentative semantic effects and restarts. Availability-only versus
identity-bearing evidence growth is tested separately. A finite feedback workflow finishes under
fair recovery; an infinite loop within one logical operation reaches its shared gas/limit outcome.
Distinct valid operations, including newly requested imports from successive authored reattachments,
can form an unbounded cross-invocation sequence. A host quota pause preserves its prefix without
inventing a Contracts status; it does not imply that a later repair input can overtake that sequence.
There is no inherited cross-operation semantic fuel in the POC. The per-operation meter still
contains all caused feedback belonging to that operation; splitting such feedback into receipt
hops is not a valid way to obtain new gas.

External gas rollback and failed import have separate planned fixtures. Use the current live
`SdkAcceptanceTest.detachBreaksTheLoopAndTheLaterCallTerminates` and MyOS dynamic-cycle examples
as source evidence: failed live loop leaves business epochs/BlueIds/checkpoints unchanged, but its
input is terminally handled; a later detach and a **new** input succeed. The target fixture must
also replay that external history with the same terminal-input law. Do not claim that a retry of
the identical failed invocation succeeds or that the old example proves failed-import recovery.

**EQ8 — selected semantic-failure alignment:** source r0=0, r1@T10=1, r2@T20=2. Consumer r1 ends with
`GAS_LIMIT_EXCEEDED` or certified semantic `RUNTIME_FATAL`, retains actual view0/business epoch and
records its exact terminal disposition.
Within the next r2 operation, ordinary containing alignment0→1 precedes r2's own1→2 observation
program; all alignment/update/event work shares that one invocation's gas and rollback. Only r2's
original source events are delivered with unchanged payloads; alignment may cause new consumer
update reactions. There is no fake source r0→r2 receipt, successful consumer r1 epoch or resumed
failed private queue. Original source continuity and terminal dispositions remain independently
authenticated. Ordinary contiguous success adds no alignment when the exact before-reference matches.

Use separate controls: r2 event before its first update reads1; same-business-state/eventless r2
still aligns; net-zero r2 preserves its internal updates; a local external read@T15 before r2 reads
actual embedded0 while independently inspecting Source gives1. The local shadow field is asserted
separately. An alignment handler may emit, retire/rebind the occurrence, or exceed gas: preserve its
normal FIFO/lifecycle rules and rollback the entire r2 on failure. Test that an update-to1 loop can
make r2 fail even if its eventual2 would not loop, without silently dropping alignment.

Add a **two-producer/own-seed alignment witness**. Root embeds A/B; an earlier combined failed
reaction leaves both Root pins0 although A/B reached1. At the next shared origin both change1→2.
Freeze canonical order Root-direct, A Entry/work, B Entry/work. Root's own early read is0/0.
At A's first canonical Entry/consumption site, its0→1 alignment completes synchronously, then its
1→2 patch runs; both Root `/a` callbacks see `/b=0`. Only B's Entry admits B's alignment. Acquisition
of B's complete result early cannot expose B1 early. This explicitly rejects a global alignment
prelude, and places Entry at its semantic BOOT/continuation site rather than at header decoding.

Let alignment emit GA/GB and the respective source BOOT work emit EA/EB, with no further emissions:
the one FIFO receives GA,EA,GB,EB. Do not separately drain alignment or pre-enqueue both alignments.
Use aliases, A retiring/rebinding B before its Entry, and a diamond with borrowed producer evidence.
Interpret each shared producer cell/Entry once, preserve distinct occurrence updates/cursors, and
revalidate before each alignment. The replacement cannot inherit old-occurrence work; a failed
intermediate producer's unchanged pin is not patched through. Mutate Entry order, duplicate borrowed
alignment and move B alignment ahead of Root's own seed: each must fail its exact read/FIFO oracle.

If both r1/r2 fail, D@T30 can detach after both terminal outcomes; it cannot erase due T20 work.
Live/historical runs, page sizes1/2/larger, split evidence reads, warm/cold/eviction, restart, reversed
workers and lost ACK must preserve histories, gas and outcomes. No first-failure range abort, suffix
discard, receipt coalescing or page-defined budget is allowed. An attachment lane releases its later
dependent inputs when its fixed-cut obligations are terminal, including failures, and reports
COMPLETED_WITH_FAILURES. A canonically earlier local input is not blocked by a later source receipt.
Missing provider/content evidence remains a wait. Handled positions distinguish APPLIED from exact
terminal semantic failure dispositions; neither failure moves the successful-view cursor. Also
consume a mixed external+managed operation under its frozen external policy with another legally
terminal status, then import its next source receipt through the resulting gap. Continuity accepts
that certified earlier disposition without relabeling it as gas or broadening ordinary import laws.
Actual-library conformance is still PLANNED.

Use this independent classification matrix in live and historical imports, including alignment:

| Observed outcome | Required classification/progress |
|---|---|
| Complete owning-library `GAS_LIMIT_EXCEEDED` | Terminal semantic failure; retain rollback view and consume only its exact input lanes. |
| Certified semantic `RUNTIME_FATAL`, including `AtomicScopeGasAdmissionFailure` | Terminal semantic failure; preserve typed reason/authentic gas and the actual initiating/admitted rollback scope. |
| Cancellation, interrupted I/O, provider outage or absent evidence | Operational/unavailable; no terminal consumption, fabricated processor result or successful epoch. |
| Unexpected implementation `RuntimeException`, even with reproducible text | Unclassified implementation failure; retain pending work and diagnose. Exception class/message alone cannot certify a semantic fault. |
| A failed initialization | No initialized source or usable initialization result; ordinary import continuation cannot manufacture one. |

Forge the semantic-fault marker and substitute an operational exception at the same injection site;
the verifier/classifier must reject fabricated terminal authority. Replenish resources and resume the
operational controls with identical semantics. These are F/EQ8 variants, not new catalog/schedule IDs.

Before genuine business-feedback tests, use passive A↔B, one business update and no handler emissions.
Representation stabilization must not synthesize an endless business-epoch/receipt chain. Count
handler calls, representation finalizations, receipts and cursor advances separately. Failure to
establish the required cyclic finalization is failed conformance, not a passed gas-termination test.
Authenticate the narrow representation-only companion: the exact allowed same-epoch representation
may change without a new business receipt/event/fan-out or handled-input advance. Reject both a
generic before==after shortcut and a verifier that forbids every valid representation-only update.

For active cyclic routing, freeze vertex-simple routes with no repeated DocumentId, including the
emitter, while retaining distinct alias paths. Transporting A's original E around A↔B does not return
E to A; B's newly emitted F can reach A on its own new metered route. Require finite exact multiplicity without
re-emission, gas exhaustion with authored re-emission, and incremental charging before path allocation.
Use an existing A↔B finite workflow changing both documents and a dynamic SCC merge/split. All admitted
members share one meter/rollback, accepted ownership expands monotonically, and missing
newly required evidence publishes nothing. An unrelated observing Order remains outside the scope.

**J-DYNAMIC-SAME-ORIGIN (PLANNED):** start with disconnected direct targets A/B for the same E.
Freeze pre-origin direct-seed order and one compatible semantic processing policy/limit L. Canonical
prefixes remain independently metered until atomic-scope admission immediately before a proposed
return-edge mutation. First charge deterministic admission-check cost c to the initiating current
meter; if it cannot fit, return ordinary GAS_LIMIT_EXCEEDED before any check or join, without
charging c. Otherwise sum the distinct groups' admitted gas, including c exactly once. If that sum
fits L, accept the mutation and unify ownership/consumption without reset. Otherwise terminally fail only
the initiating current group as semantic `RUNTIME_FATAL`/`AtomicScopeGasAdmissionFailure`, retaining
authentic admitted usage and required-scope-sum/limit diagnostic. Do not report ordinary local
exhaustion, consumed over-limit joined gas or a portable attempted-work ledger; do not recompute
that decision from the graph left after rollback.

Hand-derive these variants before implementation:

- L=100, A60, B30, admission1: accept at91, remaining9; subsequent exhaustion rolls back both.
- L=100, A60, B50, admission1: reject B's attempt before coupling with actual B gas51 and required
  scope111/limit100; B fails, A remains independent and can settle after B's terminal result.
  Include exact-fit and one-unit-over controls, plus
  ordinary exhaustion before the admission site. Neither rejected case creates a B→A edge.
- Accepted AB at80, C at30: a further non-fitting join fails the initiating group only, AB or C
  depending on which owns the attempted edge. A successful further admission never grants new gas.
  Several edges/aliases into AB count its group ledger only once, including the local check cost once.
- E addresses Agreement and1000 Orders that newly attach it, with no return edges: no first-touch
  join, unchanged Agreement history/gas and independent consumer outcomes. Compare an already
  authored one-way graph, source physically ahead/behind and cold/warm caches.
- A activates B and reads0 before B's direct seed changes0→1. Physical B completion cannot expose1
  at that earlier site. A speculative B-first path reaching C adds nothing unless the canonical
  path reaches and admits it. Existing valid commits from E are never rolled back by a later E2 edge.
- B's tentative effects cause A/C to join conditionally. Their entire unpublished attempt becomes
  invalid if B fails; do not preserve its group, gas, result/status, events, topology or dispositions
  by subtracting individual effects. In the hand-derived control A/C each have genuine own E seeds
  costing5; the conditional AC prefix costs61, and B has51 including its admission check. Prospective
  112 exceeds100: reject B with actual51 and diagnostic112/100, discard the conditional AC attempt,
  then replay A/C's own seeds from exact committed pre-state against B's failure for5 each. No AC
  settlement exists and B's canonical diagnostic is not recomputed from the rebuilt totals. Stable
  own-seed IDs survive, not invalid publication wrappers. No already published result is undone.
- Also make conditional A exhaust gas before B ultimately fails. That unpublished A failure is
  invalidated with the whole conditional attempt; it cannot terminally consume A's input or survive
  as a blocker. Re-entry can successfully execute A's independently required own seed. Reverse
  workers/evidence and reject stale conditional success AND conditional failure publication.

Compare complete event/update order, stable execution-seed/local event IDs, final settlement IDs,
gas, epochs and every selected-input disposition. Joining must not reanchor earlier emissions or
reuse already-settled speculative results. Race workers, pauses, resource supply and lost ACKs;
obsolete proposals must be fenced once admission/reconciliation changes their authority. Holds are
not successful coupled results. A failure after accepted admission rolls back its entire admitted
scope, including prefixes staged before the join; a later detach cannot refund gas or shrink it.

**J-AUTOMATIC-REATTACHMENT-CHAIN (PLANNED):** alternate `/a` and `/b` FULL_HISTORY attachments to
one Source E across individually successful import operations. Hand-derive a bounded prefix,
then stop via the host quota/test harness without fabricating semantic termination. Assert each
new placement's real delivery and per-operation gas; a pending later detach must not skip the
earlier required lane. A finite N-reattachment companion resumes after quota replenishment and
matches uninterrupted replay. The unbounded case proves host protection and an explicit POC
limitation, not guaranteed in-band repair or cross-operation gas exhaustion. Test preparation
must not silently turn this witness into an ordinary same-operation feedback loop.

### V6 — Minimal boundary usability

Drive evaluate/acquire/commit/reconcile without resident runtime state using the actual types mapped
in [05](05-poc-api.md). The Java listing is explanatory reference material, not an alternative public
API. Phase1 provides the owning constructors; Phase3 must supply the general durable mapping.
A host certificate/hash is never accepted merely because
it has the right shape. Core recomputes selection/projection using complete trusted evidence.

Validate full result/gas/diagnostics/public-event fields, exact source receipts and atomic
next-obligation projection. Test zero/one/many lineage projections: no-delivery metadata creates no
business stream; an independent source projects one lineage; an existing coupled A↔B workflow
projects both with their own exact stream predecessors under one receipt. Add creation authority as
a separately identified projection. Omit/duplicate a member or substitute a predecessor: reject before
publication. Optional aggregate reporting is not a required semantic finalizer. Immutable plans are
referenced and indexed, not copied into every attempt.

## Phase 2: target-shaped host gates

### H1 — Fences, trusted queries and independent completeness

Per-lineage, occurrence and relevant range/frontier fences cover mutable semantic inputs. Test every
covered writer between read and commit. Stale in-scope preparation conflicts, while an independent
Order's commit, leases, immutable content or unrelated ingress does not invalidate all other work.
Admission/auth controls retain separate fences. Neither a global revision nor supplied-row hashes
substitute for complete semantic membership evidence.

Read complete canonical successor/scope queries from PostgreSQL. Independently rebuild expected
indexes from raw authoritative state. Drop a relevant row AND recompute every supplied digest:
the audit must still detect the omission. A fence detects concurrency, not a coherent bad query.

Use maintained historical dependency/activation indexes as the primary discovery authority, with
their applicable completeness frontiers. Commit embedding changes and required index/work updates
atomically. Ready-work and reverse-wait indexes must select exact relevant work without scanning
all platform documents or all active documents of a user. Freeze concrete DDL/queries and retain
EXPLAIN (ANALYZE, BUFFERS), rows examined, lock waits and transaction/page peaks. Compare the same
affected work in million-document corpora and with thousands active per user, including few ready
documents among many waiters. Correct index maintenance must not require full-history hot-path
reconstruction. Test both registration/commit orders, coherent omission and supply-before-wait.

**Directed Timeline scope:** let Order embed Agreement, with external Timelines TO and TA. Stall TO
while TA supplies Agreement's complete next input: Agreement must still commit; Order waits only for
its own exact required prefixes. Add 10000 unrelated reverse observers without widening Agreement's
Timeline query/scope. Then reverse the real dependency: an aggregate embeds 10000 Orders. It must
wait for a missing genuinely relevant member prefix, but maintain shared membership/frontier aggregates
and incremental candidate merging rather than rescan all 10000 members for each next entry. Restore
that one prefix and wake affected work only. Measure initial index construction separately from
unchanged-topology next-entry work; retain query plans, member rows examined, frontier updates and
ready-work wake fan-out. A fast incomplete proof is never a passing optimization.

### H2 — Atomic settlement and publication

Verify one local atomic result+business state+receipt+cursor/obligations+outbox append. Source commit
includes durable fan-out discovery authority, not synchronous consumer completion. Cover successful,
semantic non-success and Contracts-free no-delivery decisions. All owned cleanup is exact; foreign
state is untouched, sibling failures survive, and root completion sees actions created by the
current result.

Repeat V6's zero/one/many projections through PostgreSQL, including two existing lineages, not only
new child creation. Crash before commit and lose ACK after commit: one reconciliation receipt must
authenticate every required append, with no half-published SCC or invented business batch for
metadata-only no-delivery. Independent observers remain absent from the owned transaction list.

Create-only application settlement and work-fenced CommitUnknown reconciliation survive duplicate
delivery and ACK loss. Publication follows each local semantic stream and causal predecessor chain,
including empty terminal batches.
Immediate/delayed/reversed claims yield the same per-lineage and causally ordered output. Independent
physical commit order is not a semantic cross-lineage total order or an identity input.
At B/2B backlog with continuous new appends, exact next-batch validation stays bounded and does not
conflict merely because a later batch arrived.

### H3 — Crash matrix

Kill before evaluation, during processor work, after Complete/before commit, during commit,
after durable receipt/before ACK and between historical invocations. A fresh JVM either sees
the whole committed invocation or retries the whole uncommitted one. No Contracts queue/gas/
reuse-map continuation row is allowed.
Include the future-receipt-body outage after M1 Complete: recovery observes M1 committed while M2
remains unavailable, rather than repeatedly executing M1 to discover the same missing future body.
Require crash after live-source commit before the first fan-out page and after 17 of 1000 consumers
commit. Source and those 17 histories survive; restart resumes every other entitled occurrence without
source recomputation, duplicate consumer application or stale-edge delivery.

### H4 — Holds and level-triggered recovery

Supply X between the failed lookup and waiter registration. Separately crash after persisting X
before notification and lose all notifications. A bounded durable rechecker must eventually retry.
Repeat for dependency completion, not just external bytes.
Use indexed reverse dependencies and bounded durable ready-work rechecks. Drop every notification;
unrelated waiting documents must not be polled en masse to discover one satisfied prerequisite.

Timeout, cancellation, retry expiry and host cap keep semantic cursors/readiness/reservations
unchanged. Simulated week-long outage changes elapsed time only. Operational quarantine is not
semantic termination.

**L-QUOTA-PAUSE (PLANNED):** use an operation requiring 80 semantic gas with fixed limit 100 and
host account allowance 50. The host holds admission/execution rather than replacing the semantic
limit by 50 or returning GAS_LIMIT_EXCEEDED. Replenish allowance and compare exact histories,
identities, terminal results and semantic gas with a sufficiently funded control. Include an
account-wide cap, a document cap and one custom operational cap without changing the frozen
execution policy. Cover restart, duplicate claims and commit-ACK loss: physical retries may incur
host cost, but cannot duplicate semantic settlement. Distinguish reservation/release bookkeeping
from semantic gas and reconcile an uncertain commit before deciding to execute again.

Combine the quota overlay with J-AUTOMATIC-REATTACHMENT-CHAIN. At pause, preserve the exact
committed prefix, next import and later repair entry; no terminal input advance or bypass is
authorized. A bounded harness run checks containment, not completion of the infinite chain.
Replenishment must resume ordinary ordering. The POC makes no in-band-repair guarantee while
earlier required work remains self-generating; no general cancellation mechanism is implied.

### H5 — Bounded reopen and normalized progress

Restart after every step with increasing history/plan sizes. Read immutable definitions by reference
plus the needed current cursor/delta/successor, not all completed plans or full history. Measure
cold validation separately; no entire runtime rehydration, replay-all or findAll.

### H6 — Two workers and domain isolation

Competing workers cannot commit contradictory next actions for one lineage/occurrence. Independent
Orders progress in parallel in the same domain. A slow/unavailable/gas-failing Order does not strand
healthy consumers or Agreement. Test Agreement T10=1/T20=2 with Order@T15 reading1 under reversed
workers, unequal lag and restart. Lease expiry never overrides local order; separate domain cases
add data/cache/authorization isolation.

For J-DYNAMIC-SAME-ORIGIN, race pre-join proposals against the joined operation and prove their
fences reject obsolete independent publication. Reversing workers preserves the semantic seed
order, admission decision and admitted/rejected result. Rejected admission fences its initiating
failure without acquiring another group's rollback ownership. Source completion/cache state cannot
change the decision; successful canonical one-way and later-entry controls must still pass.

### H7 — Typed mutation order and codecs

One fixed typed key ordering, work fence first; reject alias/duplicate targets. Shuffle projections
and compare lock/write order. Round-trip all identity-bearing timestamps and integers without
rounding or timezone change. Wall-clock telemetry is excluded from semantic identity.

### H8 — BEX cache

Use the full engine-derived compile key and authorized environment. Test cold miss coalescing,
different tenant/environment, cancelled waiter, oversize artifact and byte/waiter caps. Get/put
LRU without a loading/coalescing seam is insufficient; cache absence cannot change semantics.

### H9 — Cost separation

One-demand-per-round tests report host copying/decoding/hashing/envelope/WAL/retained memory
separately from processor restart and full evidence verification. Immutable definitions plus deltas
should remove avoidable cumulative host work; do not falsely claim they remove Contracts retry CPU.
Measure B/2B publication, A/2A applications, topology rows and exact-body bytes independently.
Also fix A=1 while finite committed steps grow 10/100/1000 with increasing distinct owned/nested
obligations. The dataset freezes both counts; independently audit them. Across commits record
control bytes canonicalized/hashed/serialized, operation/receipt bytes, WAL, pending/owned elements
inspected and unique immutable bytes retained. Existing counters suffice, but A-scaling and
one-demand-per-round runs cannot substitute for this separate coverage axis.

## Phase 3: integrated suite and risk-based coverage

All A–T cards map to concrete rows in [16](16-scenario-run-manifest.md). Start with exact tiny vectors,
then apply pairwise physical overlays to larger cases and exhaustive failpoints at transaction
boundaries. Include negative/mutation controls so a test demonstrates it can detect its named defect.
The smaller E1a intermediate exit does not award G1. Required CORE positives, including the named
small feedback/cycle cases, remain mandatory for G1; detecting an unsupported broader case is not
permission to replace one of those positives with a hold and claim full equivalence.

Useful input examples can come from blue-tutorial or myos-simple, but only after stating which
invariant they prove. Do not require every example or trust historical expected outputs blindly.
Keep a read-only auditor for duplicate applications, receipt continuity, partial publication,
cursor skips, orphaned obligations, readiness/reservation leaks and index disagreement.

### Minimum mutation families

- Drop/add/reorder a delivery, ancestor, SCC member, relevant Timeline or exact receipt.
- Change a cause/target/policy/environment binding, epoch, activation or source provenance.
- Accept unrelated future input as owned feedback; conversely prohibit valid source feedback.
- Omit a created obligation, clean foreign ownership or finalize before siblings.
- Route through an obsolete activation interval; drop owed work because the current index is empty;
  GC receipts before delivery closure; merge independent local gas/failure scopes.
- Supply corrupt exact content, ambiguous integer/timestamp or mutable value.
- Lose notification; race every writer; misclassify an operational hold as semantic result.
- Reorder/duplicate public output or invalidate the next batch on harmless tail growth.
- Restore full-history/full-plan/full-component scan behind a lazy-looking interface.

## Performance and acceptance

Freeze corpus, input, topology, environment, offered load, samples, failpoints and numerical budgets
before decisive runs. Calibration can only tighten preregistered ceilings/floors under the explicit
rule in [16](16-scenario-run-manifest.md). Retain failures and inconclusive runs.

Performance-acceptance preflight joins every row budget to its unique M1 guardrail by metric ID and
requires identical unit, direction and threshold. Report validation repeats that join before comparing
the observation; a looser row threshold cannot override M1. Include a negative vector with M1 p99
at 2000 ms and the row budget at 60000 ms: preflight rejects it even if a 30000 ms observation would
pass the row's substituted threshold.

Report P50/P95/P99, throughput/backlog/errors, source logical applications and physical calls, parent
reactions, query rows/bytes, heap/allocation, decode/hash/serialization/WAL, commit and publication cost.
Small related groups retain their scoped seconds-scale targets. Independent Agreement commits have
their own latency targets and do not wait for all N Orders; N=1000/10000/100000 fan-out legitimately
requires N reactions/gas and increasing total time. Freeze source-operation versus consumer-operation
measurement scope before the run, as specified in [16](16-scenario-run-manifest.md). Measure consumer
eligibility latency and full source-to-consumer lag separately so prerequisite waits remain visible.
Require bounded pages, materialization, transaction size and concurrency, fair service under a large
user/fan-out and controlled backlog/drain after overload. No universal all-N completion deadline.
Vary U=1/10/100/1000 observable steps inside one invocation separately from N/P/history, separating
necessary reactions from avoidable source re-execution and repeated copying/verification of evidence.

Whole-cause reporting is optional host/test-derived observability, not a required terminal receipt,
extra Contracts operation or scheduler barrier. When reported, join complete discovery evidence and
every local obligation disposition: a 20ms source commit followed by 60s propagation is 20ms source
latency and 60s whole-cause latency, never one substituted for the other. Local successful receipts,
terminal failure accounting, discovery correctness and recovery remain mandatory without that report.
Retain failed/blocked outcomes and deadline timeouts even when eventual state is correct. Throughput
counts exact declared local completions inside the rate window, not post-window drain completions.

Censored durations are lower bounds, not exact completed samples. Include the negative control:
98 operations finish in 1 ms; two late-arriving operations are still running at age 2 ms, then finish
at 1000 ms. An observed-age p99 of 2 ms cannot prove a 10 ms upper-bound PASS. Retain the unresolved
tail and produce NOT_RUN/inconclusive when the bound cannot be decided; a proved violation is FAIL.
Observe each admitted sample long enough for its scoped threshold or continue until an exact
quantile is known. Deadline expiry never changes semantic state or authorizes dropping work.

PASS requires correct exact traces, clean invariant audit, expected fault recovery and each applicable
frozen budget. Source-unavailable or algorithm-unproved cases remain UNDECIDED, not passed.
The result is the next implementation/optimization backlog, not final production approval.
