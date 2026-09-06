# Deterministic catch-up and admission

> **Status:** REQUIRED PHASE-1 CORRECTION / EXECUTABLE PROOF · **Revision:** 15.12  
> [Independent lineages](18-independent-lineage-processing.md) · [Causal model](17-causal-processing-model.md) · [Equivalence](14-managed-graph-equivalence-repair.md)

## Decision

This chapter retains the selected chronology contract and its diagnostic scenarios. Phase1 implements
the library corrections; the [handoff](24-phase-1-library-summary.md) and
[verification record](implementation/phase-1-2-readiness.md) distinguish executed controls from the
larger catalog. Phase3 still needs the general durable admission/import mapping. Requirements below
are not blanket claims that every scenario has already run end to end.

Freeze the selected operation's external input and historical basis, not every source head.
Agreement commits independently and observing Orders consume retained receipts through their own
histories/cursors. A local operation cannot overtake earlier relevant input or dependencies; there
is no all-Orders completion barrier for the one-way source or independent siblings.
References below to a prefix mean a lineage history or authenticated causal predecessors, never a
global sequence chosen by sibling commit timing. Section18 defines the revised boundary.

Each defined consumer receipt operation has independent gas, rollback and commit. Its grouping is
semantic, not chosen by batch/page size. No private Contracts queue is persisted. Phase1 changes the
baseline active-parent closure grouping and inactive-only catch-up admission so live lag and
historical import use the same retained-result principle. [19](19-semantic-edge-contracts.md)
describes the edge contracts. [22](22-processing-kernel.md) selects one inherited origin/position per
owned scope, composing converging projections, own direct seed, complete placements and route cursors.

## Initial history and later attachment are different

FULL_HISTORY reconstruction of an initially embedded source is chronological:

| Original cause | Source S | Parent P |
|---|---|---|
| Initial | count=0 | initially embeds S |
| t10 | count becomes 1 | receives the required historical consequences |
| t20 | still 1 at this point | copies embedded count into observed |
| t30 | count becomes 2 | receives the required later consequences |

When P is admitted under C40, observed must be 1, not 2. Importing the entire S history through C40
before P's t20 event violates this requirement. A chronological replay cursor and the due causal
obligations jointly determine the next action.

The oracle must distinguish a direct historical child read from a parent value populated by events.
In the A/B fixture, A reads `/child/counter` through the exact historical managed occurrence; reading
only A's `/counterB` cannot prove that lazy resolution chose the correct B state. Check both where
the authored handlers maintain a shadow value, and include a child-state change without a matching
shadow-update event so a correct shadow cannot hide a future-state read. Give each its own expected
result; the direct value and the unchanged shadow need not be equal.

Record the complete ordered original A entry identities and per-entry dispositions, not only a final
counter or one selected observation. Every applicable entry must have the independently expected
target application/delivery count; non-applicable entries have explicit cursor-only dispositions.
Managed imports remain separately identifiable. Omitting, duplicating or reordering an original
observation cannot pass because later assignments happen to restore the expected final value.

If one original cause targeted both S and P,22 composes P's own direct seed and S's retained
projection in one P operation/meter. Preserve exact BOOT seed sites and caused-work FIFO through the
observation interpreter, not a universal source-receipts-parent batching phase. Phase1 must verify
that chosen rule independently; current executed evidence is recorded in the readiness report.

An attachment introduced at later cause T is different: its exact selected historical state and
boundary define the import for that new occurrence. That import and its permitted consequences must
settle before later dependent work. Neither policy uses the source head observed at worker arrival.

For example, B emits `CounterChanged(5)` at T5, and A has an Embedded Event handler that sets
`counterB` from that event. Compare two different semantic inputs:

| Relationship history | Required A history |
|---|---|
| A attaches B at T10 and imports the retained T5 event | `counterB` is unset before T10; it becomes 5 when the attachment's catch-up applies the event |
| A attaches B at T1, before B's T5 change | `counterB` becomes 5 as a consequence of the T5 event through the existing relationship |

The final value is 5 in both cases, but the correct histories differ. Import preserves the original
T5 event and source-order provenance; it does not insert A's reaction retroactively into A's T5
history. T10 names the attachment's causal position, not a replacement timestamp on the managed
cause and not one atomic invocation containing the entire catch-up. These examples assume the
emitted event and matching handler; loading B's value alone does not implicitly assign `counterB`.

Physical reconstruction time is separate. Replaying an attachment that semantically occurred at T1
after the host has already received T10 does not turn it into a T10 attachment. Replay must recover
the second history, not select a policy from the worker's start time.

## Failure progress is operation-specific

Historical original-input replay is an external operation; historical source-receipt import is a
managed import. Consuming a failed original input need not advance a business epoch. A failed managed
r1 cannot move its successful source cursor or establish r2's successful-view prerequisite.
Selected managed-failure continuation terminally accounts r1 after `GAS_LIMIT_EXCEEDED` or a
library-certified deterministic `RUNTIME_FATAL`, then processes r2 from actual rollback state using
authenticated source history and intervening terminal outcomes. It first aligns
the actual pin0→r2.before1 with ordinary metered update reactions, then interprets r2's original1→2
program. No failed r1 events are replayed or source0→2 receipt forged. Alignment can fail too. Both-failed
r1/r2 permit later D30 after terminal accounting; nonterminal evidence waits cannot be skipped.
Ordinary external detach/new-input recovery already exists; the managed constructor/conformance
remain planned library changes. [19](19-semantic-edge-contracts.md#2-failure-has-several-independent-progress-coordinates)
records this distinction and the current source evidence.

This includes the certified `AtomicScopeGasAdmissionFailure` reason when an actual return-edge
admission cannot fit the canonical charged prefixes into the fixed shared limit. A first one-way
contact does not join operations. Rejection leaves the proposed return edge absent and fails only
the attempting operation; another independent operation may proceed after accounting for that
terminal outcome. Once a join is admitted, subsequent failure rolls back the complete admitted
scope. Report authentic admitted gas, not a fabricated gas-limit total; [22](22-processing-kernel.md)
owns the admission law and stable execution/event identities versus atomic settlement authority.

The admission check is first charged normally to its initiating operation. Inability to afford
that charge is ordinary `GAS_LIMIT_EXCEEDED`. If the check is paid but the combined prefix cannot
fit, report `AtomicScopeGasAdmissionFailure` with the initiating operation's authentic admitted
gas, including that check; the prospective combined sum is diagnostic evidence, not another charge.

The runtime result and reason must be certified by the owning library under the exact input and
environment. A reason string, unknown exception, timeout, cancellation or uncertain commit is not
authority to consume a delivery. Other statuses retain their operation-kind rules. Failed
initialization never supplies a usable initialized source, even when its failure is terminal.

## Historical reads, removal and retargeting while catch-up is pending

After B@10 has been imported, the occurrence may still be pending because B's authoritative head
already includes B@30. A's historical E20 must nevertheless be eligible for its proved historical
read. That is operation-specific authorization, not a declaration that A is globally READY or that
all actions may use every partially caught-up document. Current Contracts preserves the pending
occurrence's exact historical read binding; current Coordination's ordinary feeder rejects cohorts
with any CATCHING_UP member (`DefaultCoordinationEngine.java:1805-1813`). The planned correction must
distinguish historical execution eligibility from that ordinary live-work gate.

Ordinary same-scope removal and retargeting belong to the intended first functional scope, not a
silent read-only subset of FULL_HISTORY. Use two variants: A's E20 removes `/child` and its effective
Process Embedded declaration, or replaces B there with an exact verified C. B@30 is retained but is
not yet due. The required successful E20 result must:

1. process E20 in its original chronological position without importing B@30 merely to activate B;
2. retire the old occurrence/activation generation and its not-yet-due historical suffix with exact
   constructor evidence, while preserving every earlier committed import and every delivery already
   validly owned by the current complete Contracts invocation;
3. leave B's authoritative state, retained receipts and other consumers' obligations intact;
4. prevent B@30 from reaching A through the retired occurrence, including via previously queued
   future catch-up work; this does not cancel in-invocation event-time frozen deliveries;
5. for retargeting, establish the exact C binding, new occurrence/activation evidence and C's selected
   historical basis at E20, never inherit B's cursor merely because the path is unchanged; and
6. commit the Contracts result and old/new obligation projection atomically. Failure retires nothing
   tentatively and retains the actual Contracts diagnosis. A later reattachment needs its own valid
   activation/basis, not resurrection of the retired work.

Phase1 implements this Contracts/Coordination correction. Historical source inspection showed that
the baseline rejected an external change to a pending historical value (`ProcessEmbeddedSurfaceReconciler.java:288-296`),
and its process-retry exception was limited to active rows or authenticated managed-revision event
sources (`ClosureInvocationVerifier.java:119-153`). Baseline removal preserved an inactive row without
a retirement transition (`ProcessEmbeddedSurfaceReconciler.java:270-280`); keeping an obsolete plan is
a historical risk, not a new failing run. Pure-read support alone did not prove either mutation
variant. Retirement/rebind constructors follow22's selected cut and frozen-work rules; do not
declare support by relaxing every readiness check or quietly downgrade the requested scope.

## Immutable basis and replaceable progress

Store an immutable CatchUpSelectionBasis once:

- accepted causal root and exact historical relationship kind;
- source and consumer DocumentIds, occurrence/path/activation;
- admitted source position and exact historical selection evidence;
- original external/admission boundary and semantic configuration.

Store normalized CatchUpTargetProgress separately:

- next contiguous source receipt position;
- currently required exact receipt target or owned following segment;
- the committed semantic advance authorizing each change;
- pending/active/retired occurrence state and terminal blockers.

The original historical basis remains immutable. Additional receipts become due only when their
authenticated producer, receipt continuity and chronological position authorize them for this occurrence. Preserve their
original Contracts cause and event identities. A later unrelated source entry in storage cannot be
promoted into the active root.

During initial chronological reconstruction, even an authentic later-original-cause receipt is not
yet due. "Owned" and "available" do not mean "visible before its chronological position."

Retained sourceOrder preserves original-event provenance; successive lineage receipts may carry
equal or decreasing values. Do not impose either strict increase or nondecrease. Receipt continuity
uses the exact lineage, contiguous retained positions/epochs, predecessor/current-representation
proof and receipt identity. Producer/committed-prefix evidence determines cutoff visibility separately.

When E changes S1 and S2 and both projections are due to C at the same inherited origin/position,
they compose into one C operation, not successive receipt-driven operations. Distinct attachment
or reaction positions can produce successive C receipts carrying the same original E provenance.
When K@100 attaches S and catch-up imports its E@10 history, a later C receipt may carry order
10. It is still an effect produced under K@100 and must not appear in a cutoff-50 reconstruction.
The selection oracle must name the expected closed receipt prefix explicitly; sourceOrder sorting
or binary search cannot establish that prefix. Preserve the original cause/order without invented
timestamps or merging distinct applications. These are named CATCHUP-K1-K2-K3 subcases.

Pair that negative cutoff oracle with a nested positive one. C emits value 1 at T10. B's initial
authored graph embeds C, and B's matching handler records `lastSeen=1` during FULL_HISTORY
reconstruction under cutoff 40. A's initial authored graph embeds that same B lineage; A has a T20
event copying `B.lastSeen` into `observed` and is reconstructed under cutoff 60. Required:
`A.observed=1`. B's later physical reconstruction/admission must not hide its reconstructed T10
history from A's T20 observation. Conversely, the K100-created attachment above must remain absent
at cutoff 50. Neither sourceOrder alone nor the producer root's admission cutoff decides both cases.

The22 kernel selects visibility from exact producing actions, relationship policy, replay cursor
and committed prefix; authorized library tests must verify it against an independently
expected contiguous receipt prefix. This is not a universal rule to backdate every effect of reconstruction: new WORK
in a pre-existing authoritative source still uses its current state and cannot be inserted before
that lineage's existing receipts. A supported feedback interaction needs its own exact trace.

Live subscription does not require continually matching the latest source head. A cursor may be
subscribed and behind; readiness is proved through the finite point needed by the next local
operation. Source history remains retained. Registration, historical selection and continuing live
delivery require one durable gap-free handoff, including concurrent source append.

The22 kernel keeps the historical lane's accepted cut fixed and follows source predecessor order.
New work caused inside the actual owned SCC is synchronous work under that same invocation/meter,
not an arbitrary extension to the worker's latest source head. Earlier independent prerequisites
remain ordered dependencies; source progress outside this cause cannot enlarge the lane. Verify
final/nonfinal activation against that rule rather than banning legitimate feedback.

## Why source feedback matters

Let S actively contain C, and let C import historical S through an inactive occurrence. A retained
S event reaches C; C emits a new event; the active containing relation delivers it back to S. An S
Handler may legitimately change S and emit another event.

Current Contracts permits such feedback inside its coupled closure. Under the revised independent
boundary, imported events still do not rerun the original source; genuine consumer emissions create
explicitly ordered receiver work. Its position cannot depend on worker speed or be inserted into
already committed source history. Cyclic exact-value finalization is a separate coupled-scope
question, not permission to include every ordinary observer in the same transaction.

Test two distinct cases:

1. Final receipt T against authoritative input epoch T: activation can complete, and later work in
   that invocation may commit a new source epoch.
2. Earlier receipt of target T changes S: the next invocation can see a source epoch beyond T.
   Merely importing the old final receipt then does not satisfy current activation rules.

These activation examples describe the current coupled implementation and identify behavior the
revised local cursor/feedback protocol must reconcile. Current frontier-extension mechanics may be
reused only where their evidence meets the new causal/chronological rules. Neither current activation
equality nor a blanket source freeze defines the target algorithm. Preserve every required new event
at its proper position exactly once.

## Next-action selection

Use the [next-action law](17-causal-processing-model.md#6-normative-next-action-law), not a universal
phase order. Coordination derives the complete relevant successors and prerequisites from current
evidence, chooses one legal action, and binds its certificate to the committed predecessor.

An R1 attachment and a later R2 application must not create the artificial cycle "R2 waits for W1,
while W1 is forbidden until R2 completes." Swapping two phase labels is not proof: early activation
may affect application grouping. Likewise, same-root/workflow ownership is not blanket permission
to process incomplete consumer state. Required-use dependencies need scope-specific justification.

Within a chronological cause, settle its required causal consequences before a later dependent cause.
Do not generalize that rule to importing all source receipts in an entire initial admission window.

A terminal commit atomically stores the complete Contracts result, application settlement, exact
cursor/target/obligation delta, next authority/work and outbox receipt. No second unowned post-commit
planner may invent a next action. Immutable definitions are referenced, not repeatedly copied into
every mutable workflow state.

Persist successor authority as exact immutable references and cursor/obligation facts, separately
from its execution payload. For NEXT-RECEIPT-PAYLOAD-UNAVAILABLE, M1 has a complete result and exact
M2 selection/header proof, but M2 body is unavailable. M1 commits once with its receipt and required
remaining obligation; M2 suspends, then verifies the acquired body and resumes without reapplying M1.
Wrong identity/body/header is rejected. Missing evidence actually required to establish M1's terminal
selection/projection can still hold M1. This is the same core-owned next-action law, not a second
post-commit planner, speculative progress, or a partial Contracts continuation.

## Evidence, failure and liveness

Managed receipt evidence is either a complete current Contracts transition receipt or the existing
authenticated eventless-application path. The latter proves same-state/zero-event application through
its owning application, complete committing result, companion, exact state, receipt identity and gas.
An identity-only/state-only wrapper without that chain is not sufficient. Initialization and event-
bearing receipts retain their exact current constructor forms.

Failure at step k preserves the committed source and successful siblings as well as earlier local
results. It retains the failed local position and affected dependency diagnosis. Healthy independent
branches remain eligible; whole-cause aggregation cannot report success with a failed consumer. Do not skip the cursor, silently mark remaining obligations complete, or apply
generic broad-gate cleanup. Exact failure ownership, terminal delivery outcome and any authorized
reservation release belong to the validated local delta. Aggregate completion is optional host/test
reporting, not a required global terminal receipt; unresolved siblings cannot disappear from reports.

A missing payload, timeout, cancellation, provider outage, unsupported scope or exhausted host budget
is an operational hold, not a Contracts failure. It neither settles the application nor changes
semantic readiness. Durable level-triggered need reconciliation closes supply/register and lost-
notification races.

Liveness assumes eventual valid evidence and fair recovery. One logical feedback operation is
bounded by its shared semantic gas/limits; do not split it into fresh-budget host hops. Only a
genuinely unbounded sequence of separately authorized future operations can need operational
pausing without a semantic terminal result.

## Semantic equivalence refinements

[22](22-processing-kernel.md) supersedes the earlier pin/relay/creation shortcuts and selects the
observation interpreter. Use actual observations, update continuations and event-admission boundaries;
do not replay only final state or impose a full-batch barrier. New placements retain frozen original
recipient rules without automatically deferring all their initialization work until after commit.

Canonical source FULL_HISTORY is independent of physical discovery. FROM_NOW is an explicit observer
selection, deliberately no longer a source-birth choice. Failed import preserves the successful view;
the next operation aligns it to its exact before-view before replay. Terminal progress and later
repair remain separate from successful state. A T20
removal excluding future T30 is not permission for T30 detach to erase already-due T20. Neither
silent skipping nor a permanent global hold is a successful recovery algorithm.

## First implementation tests

After explicit implementation approval, the planned first verification uses isolated in-process tests
of actual Coordination and Language/Contracts, before PostgreSQL or the broad MyOS adapter. Verify
the reviewed rules against hand-derived expectations and repair mismatches in the owning library.
A model-only simulation or two adapters sharing the same mistaken planner is not sufficient. The
selected22 grouping, visibility, retirement and activation rules are the expected behavior; they are
not decisions delegated to whichever implementation happens to pass first.

Use the traces in [17](17-causal-processing-model.md#8-mandatory-implementation-spike-traces):
chronological initial history, same-cause source+parent, late attachment, final/nonfinal feedback,
interacting consumers, eventless transitive observation and bounded atomic feedback. Separately
exercise operational pause of genuinely distinct future operations, not a split core loop.
Include the T10/T1 attachment pair, nested positive and K100 negative visibility oracles,
equal/decreasing provenance, a missing successor body and a queued managed action surviving a legal
disjoint-prefix topology commit.
Require direct child reads and complete original-entry order/count, plus E20 removal and retargeting
with B@30 retained and the old occurrence still pending.
Also require ordinary live source-before-consumer commits, source-ahead T20 removal versus T30
receipt, registration/append races, and source/healthy-sibling progress with one unavailable or failing
Order. Then vary paging, availability, claims, restarts and ACK loss against the eager reference of
the same revised local-operation boundaries. These are planned acceptance checks, not work authorized by this review update;
no release or migration is involved.
