# Selected processing kernel

> **Design baseline:** 15.12 · **Status:** synchronized with Phase1 implementation; general durable integration pending
> [Examples](20-decision-traces.md) · [Semantic laws](21-semantic-equivalence-and-source-reuse.md) · [Timelines](15-myos-timeline-foundation.md)

This chapter closes the previously open algorithm choices. It has precedence over earlier candidate
mechanisms in this package. It defines one target model, not another specification profile. Changes
to the inspected baseline Contracts/Coordination are explicit below. Phase1/2 passed the scoped
[readiness checks](implementation/phase-1-2-readiness.md); [24](24-phase-1-library-summary.md) records
implementation refinements and [25](25-phase-3-integration-plan.md) the unimplemented general integration.
The kernel remains the semantic contract, not a claim that every catalog scenario has been proved.

## 1. The unit of progress

An operation consumes one canonically selected cause/reaction position or one canonical initialization.
Its cause can include its own direct external delivery and the complete converging upstream projections
due at that same position; these are not separate operations merely because they arrive as different
receipts. It owns a complete synchronous workflow, with one gas limit and one terminal result. Internal
events, SQL pages, physical retries and observation-program frames are not operations or epochs. A
successful result publishes the actual per-lineage epoch changes required by Contracts; failure
publishes no successful business epoch. Independent observers have different operations.

The next observable action inside an operation is selected by this priority, never by host scheduling:

1. Finish the current synchronous continuation: the current patch, its required Document Update
   deliveries, initialization/lifecycle work and recursively caused synchronous continuations.
2. Continue the current handler result in its declared order: each patch completes before the next;
   the result's buffered events are admitted only at the existing Contracts emission boundary.
3. Finish the remaining deliveries of the currently dequeued event, Triggered scope first and then
   its frozen containing scopes in the routing order below. Newly emitted events go to the FIFO tail.
4. Only when that work finishes, dequeue the next FIFO occurrence. Finalize only when all required
   local work is exhausted. A portable resource need returns no partially committed operation.

These are the existing local continuation/FIFO laws, now also used to interpret retained source work.
They are not a new host event scheduler. An eager logical reference and the lazy implementation use
this same rule; independent test traces additionally constrain it against ordinary PROCESS behavior.

Handler execution and application of its returned effects are distinct boundaries. In particular,
Coordination's Sequential Workflow is one handler: successive Compute steps may read their shared
WorkingDocument patch preview, but that preview does not run initialization, Document Update
callbacks or FIFO delivery. Those effects begin after successful handler return. A subsequent
handler/actual continuation sees the applied effects. Do not flush each Compute step into the
processor merely to make a managed embedding appear earlier than it would under these rules.

## 2. Select the next operation, then its complete owned scope

Use the document's exact semantic predecessor, occurrence lifecycle and handled-input/disposition
positions. Derive its **directed dependency closure**: follow documents it embeds, never every document
that embeds it. Close earlier relevant topology work before declaring membership/readiness. Obtain
complete normalized Timeline prefixes for precisely that membership. Select by exact microseconds,
then canonical entry BlueId text; retained reactions additionally retain their causal predecessor
and attachment position. A later operation cannot overtake an unresolved earlier relevant one.

A cached source result cannot select its own earlier input using its later topology. Unchanged root
Timeline channels are insufficient: adding or retiring an embedded child can change the directed
Timeline membership without changing those root channels. Reconstruct the authenticated predecessor
cut when the retained operation changes active outgoing references or routing headers. A physical
after-head is reusable only where it proves the same selection cut; otherwise request exact evidence.

Before entering an external operation, merge the relevant external and managed obligations at that
causal cut. A source operation and its required reaction precede the dependent document's later read.
For an embedding already present in an authored document reconstructed with FULL_HISTORY, merge
source reactions with that document's original inputs chronologically: sourceT10, parentT20 read,
sourceT30. Do not drain the entire source history through the physical admission cutoff before
parentT20. The later-attachment lane below must not replace this initial chronological merge.

A new attachment created at K importing historical E keeps E as provenance but places its new
reaction after the attachment, not retroactively at E. Its catch-up lane is ordered by source
receipt predecessor; all obligations belonging to its fixed logical attachment cut are terminally
accounted for before that lane releases later external work. K includes its exact canonical cause
and intra-operation activation position, not only a microsecond timestamp. A source tail or completed
receipt observed by a worker is not that logical cut and cannot expose later source frames early.

**Owned workflow scope:** use the strongly connected component of directed managed dependencies at
the operation's logical cut. A singleton without a returning dependency is the normal case. Relevant
acyclic descendants supply exact source evidence; ancestors outside the component are independent
consumers. Choosing the complete component is deliberately conservative: it avoids a speculative
claim that a particular handler will not exercise an existing return path. Passive representation
rebinds retain their narrower, explicitly authenticated finalization rule.

For S→Q, Q→P, P→S, Q's earlier topology work must be known before S can be treated as independent.
The host maintains indexed component/dependency evidence; it does not scan all reverse observers
for every source step. Missing evidence waits. A stale graph is not an independence certificate.

A topology change is admitted before its mutation. A new one-way dependency may require exact
same-origin work/evidence, but does not by itself merge gas or rollback. A change that would establish
returning execution dependencies uses the atomic-scope admission rule below. Missing evidence waits
with no publication; dependencies within tentative work are not waits for its own commit. Earlier
independent work needed to establish a member's pre-state remains an external prerequisite.

Successfully admitted ownership is monotone within a valid canonical attempt, including a later detach:
do not refund work, remove rollback ownership or reset gas after admission. A rejected admission
never acquires the other group. Speculative worker footprints are not admission authority. Overlapping
work cannot commit from incompatible cuts; affected proposals are fenced, unrelated observers are not.
An attempt relying on an unfinished independent producer is conditional. Invalidating that entire
attempt after its producer fails is not selectively shrinking a valid group; see the rule below.

### 2.1 Dynamic joining for the same origin

Freeze direct-seed order from exact pre-origin topology and the canonical seed comparator, including
delivery addresses. Dynamic edges cannot reorder it. Compute the relevant projection through indexed
membership/evidence; this is not a requirement to enumerate or execute all platform recipients.

Suppose E directly targets initially independent A and B. A creates A→B; B may later attempt B→A.
Use exact canonical prefixes, including synchronous updates and FIFO, not worker-completed results:

1. Include newly relevant pending same-origin producers in readiness. Check exact predecessors and
   tentative dependencies, not only committed SCCs. Hold publication until relevant work is closed.
   If A reads B before B's direct seed, it reads the exact pre-seed view, not B's final database value.
2. Keep independent groups on their own fixed semantic meters. First contact A→B is not ownership
   admission. Agreement targeted by E remains independent of1000 Orders that merely attach to it.
   A genuinely independent source result is reusable cold/warm; physical absence is not independence.
3. Before accepting a topology mutation that would join returning same-origin execution dependencies,
   identify all distinct current groups from the canonical prefix. The first POC requires one identical
   frozen execution policy/limit L across these groups; never choose a limit from the first worker,
   account allowance, minimum/maximum policy or cache. An incompatible policy is outside the supported
   join input and is rejected before mutation, not silently converted to another budget.
4. Charge the deterministic admission-check cost c to the initiating group's existing meter before
   the check's work. If this local charge cannot fit, return ordinary GAS_LIMIT_EXCEEDED for that
   current group; no join occurs. Otherwise sum the already admitted gas of the distinct groups,
   including c exactly once. An existing group is not counted again for each member or edge.
   Use overflow-safe exact arithmetic for the comparison/diagnostic; prospective group sums are
   not settled safe-integer gas counters and cannot wrap or clamp into an accepted join.
5. If the sum fits L, admit the mutation and ownership together. Unite those exact canonical ledgers,
   with no refund, reset or extra allowance; remaining budget is L minus that sum. Subsequent failure
   rolls back every member of this admitted group, even if a later detach removes the cycle.
6. If the sum exceeds L, reject the mutation BEFORE ownership changes. Fail only the current group
   attempting it with deterministic RUNTIME_FATAL category AtomicScopeGasAdmissionFailure (an implemented
   owning-library category). Record its actually admitted gas and required-sum/limit diagnostic, not fictitious
   local usage L, an over-limit joined ledger or a portable attemptedWork ledger. Other independent
   groups retain their authority and finish only after accounting for this failure. No rejected edge
   exists in history.

For L100, A60/B30 and c1: admit at91, remaining9; later exhaustion rolls back A+B. For A60/B50 and c1:
B fails with its actual51 gas and required-scope111/limit100; A is not acquired by that rejection.
If already joined AB attempts to add C, apply the same rule to current groups AB and C. Rejection
rolls back initiating AB, not C; accepted joining followed by failure rolls back ABC.

The admission decision is made at its canonical attempted site, not recalculated from the graph left
after rollback. If B produced tentative updates to which independent A reacted before B's rejection,
invalidate the complete dependent attempt and reconstruct its still-due work against B's authenticated
failure. Invalidation includes topology admissions into third-party groups, meters, candidate success
or failure dispositions and outputs. None can survive as published effects or settled gas. Retain the
canonical attempted prefix explaining B's failure; do not retry B against the cheaper post-cleanup
state. No partial A/B business state is public.

Admission evidence binds the independent-producer assumptions under which its canonical attempt
executed. Before publication, every assumption is either validated against the producer's complete
authoritative outcome, or internalized by an accepted join into the same owned operation. A failed
external assumption invalidates the entire dependent attempt and transitively dependent attempts;
restore their still-due own seeds from exact predecessors. This is not a terminal disposition for
those seeds, an extra gas settlement, or re-execution of previously committed work. Canonical seed
identities remain stable; invalid candidate result/admission wrappers never become authority.

Example: initial A→B and C→A, direct seeds B,A,C, limit100. B spends10 then patches. A's reaction
spends60+c1 creating A→C, so conditional AC is admitted at61. B spends40+c1 more and attempts B→A:
B51+AC61=112 rejects B alone. Invalidate conditional AC, including its61 gas and A→C admission;
A/C's still-due own E seeds (for example5 each) are reconstructed without the failed B reaction.
B retains its actual51 and required112/100 diagnostic; AC61 is explanatory attempted-context evidence,
not an AC settlement. If B had joined ABC successfully, B would be internal and later failure would
roll back ABC together. Also invalidate a dependent tentative gas-failure outcome: B's failed update
cannot terminally consume A's own still-due input as if B had supplied a successful source revision.

Re-entry reproduces this ordered admission protocol. It does NOT replay final joined members under
one retroactive meter from the first instruction: that would exhaust gas before the edge used to
discover ownership and recreate r15.10's fixed point. An instruction not reached canonically cannot
request admission; a valid attempt's admitted join cannot be revoked by reinterpreting gas. Whole
conditional-attempt invalidation above is distinct from a detach, selective refund or retry of B's
settled rejection. Untrusted worker
speculation is only an acquisition hint, never ledger or admission evidence.

Stable execution-seed and event-occurrence identities precede and survive joining (§5). The final
atomic group/settlement identity is separate; never rename earlier emissions around a newly selected
anchor. Publish only a complete verified result, admission derivation and terminal projections.
Fence every participating/superseded work proposal and settle each lane once. A stale worker cannot
publish an old independent proposal. A genuinely later entry never rewinds earlier independent commits.

The first POC may serialize overlapping candidate planning/reconciliation and re-enter canonical
execution; no distributed merge service or gas-free scan of hypothetical future handler work is needed.
Heavy evaluation/I/O stay outside short publication transactions. This is an explicit owning-library
admission/identity change implemented and tested in Phase1, not behavior inferred from the old
A-already-embeds-B example. Accepted/rejected joins, failed producer assumptions and one-way controls
are covered in the recorded library verification; Phase3 must preserve their authority across commits.

This is an explicit change from the current all-affected-closure commit boundary. The reference for
independent failure/gas is this ownership model; ordinary PROCESS remains the control for unchanged
handler, update and FIFO laws. Do not claim equality to the old global rollback/gas boundary.

## 3. Reuse a source by substituting completed actions, not by replaying a flat event list

A committing source retains a library-produced **observation program** with its complete result.
It contains only externally observable execution boundaries, not all VM instructions:

| Frame | Required contents and meaning |
|---|---|
| Entry | Exact source operation, initial view, input and environment authority. |
| Patch | Exact before/after content, original relative update path/value and its synchronous continuation site. |
| Enqueue | Original occurrence identity and payload, frozen source routing basis, enqueue site and local ordinal. |
| Delivery | Which previously enqueued occurrence/target is being handled; view changes and enqueue sites produced there. |
| Exit | Final source view and nonrecursive execution summary/gas authority; continuity covers every frame. |

Frames refer to immutable exact nodes and shared prefixes. Derive the execution summary from final
state, status, gas and produced outputs, excluding the program root and result wrappers. Exit names
that summary. Then derive the frame chain/program root; finally the complete result authenticates
the summary, program root and frame count. An Exit must not hash the complete result that contains
that same Exit. Frame predecessor/content identities authenticate bounded reads.
Verification rejects omissions, reordered frames, invented events/views and mismatching final state.
The constructor lives in Contracts/Coordination. Host-authored hashes are not producer authority.
An upstream program reference records the exact producer operation/program and frame interval at
its continuation site. It is a borrowed action, not a new emission or a copied second source result.
Composing a diamond executes each producer action cell once per logical origin; its distinct
placement/route deliveries still occur separately. Parent's genuinely new patches/events remain
Parent-owned cells. This preserves reuse recursively without flattening/copying all upstream history
into every consumer program or accidentally replaying Source twice through converging references.
Borrowed programs come from already complete upstream operations. Members executing together inside
one owned component produce direct local frames; they cannot borrow their own future result/program
root or form a cyclic evidence hash dependency.

When a consumer reaches a source action, substitute the authenticated effects of that action, then
run the consumer's own required continuation before proceeding. Do not rerun source business code.
Do not insert source events until their recorded enqueue site is reached. Source-only queued actions
still drive subsequent source frames even when the consumer has no matching handler. Consumer-created
events use the same logical FIFO. Evidence for an upstream parent is interpreted recursively, including
the parent's own new events and original ancestor observations; completed receipt order is not FIFO.

**Reaction origin is not producing-operation identity.** Its canonical preimage is the accepted
external cause or admission owner plus its exact logical reaction position. A new authored external
entry starts a new origin. Independent downstream operations inherit the origin that caused their
work while retaining their own operation identities. An upstream handler's new F has a new event
occurrence and producing operation, but remains in that inherited origin. Historical E10 imported
by a new K100 attachment uses K100 plus that selected historical operation's reaction position;
E10 remains provenance and does not move the new work back to T10. Distinct selected historical
operations remain distinct positions, not one catch-up operation.

An owned consumer scope produces one complete operation for an origin/position, composing all
required upstream projections and any own direct external delivery. In the diamond where Root
embeds Source directly and also through Parent, Root does not process Source's receipt and then
Parent's derived receipt as two independent reactions to the same origin. It interprets their
matching causal sites together, preserving both valid original-event routes and Parent's new F.
If the same original entry directly targets Source and Parent, Parent likewise combines its frozen
direct seed with the retained Source projection in one operation/meter. Preserve the existing exact
seed order through BOOT delivery sites; do not assume that every child projection runs before every
direct seed or substitute the source's final head. Independent receipt arrival cannot decide order,
epoch count or gas ownership. The full required producer set is derived from relevant dependencies
and frozen routes; it excludes unrelated observers.

A terminally failed independent producer contributes its authenticated rollback view and disposition,
not its tentative observation program or newly emitted events. For Source0→1, failed Parent reaction
and Root embedding both at /s and /p, Root composes successful Source evidence with unchanged Parent.
If Parent's retained /s pin is0, Root may read /s=1 and /p/s=0. A valid frozen original Source event
still reaches Root through its distinct direct/ancestor routes; Parent failure does not turn it into
a fabricated Parent emission or remove it merely because Parent produced no successful result.
Root must not patch through its unchanged Parent pin to invent a Parent-after1 revision. Parent's
tentative F is absent. This is the selected independent-failure behavior, not parity with the old
whole-closure rollback. A nonterminal missing Parent result still blocks the dependent Root operation.
Likewise, termination/cut-off of an independent observer controls that observer's work; it cannot
stop the already independently owned Source FIFO or healthy sibling operations. Existing synchronous
cut-off rules still apply inside each operation's actual owned workflow scope.

The enqueue site names BOOT (initial synchronous work) or the dequeued occurrence and its frozen
delivery address, followed by the ordered synchronous-continuation address and local emission ordinal.
It is a causal address, not a global SQL/source-event ordinal. The interpreter reconstructs FIFO by
performing those admissions. It must not lexicographically sort causal addresses as a replacement
for the queue. Independent irrelevant observer branches can be omitted only because they cannot
write, emit back into, or change reads of this operation; a returning branch belongs to owned scope.

Example: BOOT admits E1,E2. E1's Parent delivery admits P1; E2's Source delivery admits F2. After
E1 the queue is E2,P1; after E2 it is P1,F2. Preloading F2 from Source's final receipt is forbidden.
For E1→x1 and E2→x2, Parent reads1 then2. If Parent emits F1/F2 in those handlers, a later Root sees
the already processed Parent view2 while handling F1/F2, although F1's frozen payload can still be1.
Separate external source operations remain separate: their corresponding Root reactions read1 then2.

Gas is logical work: source computation is charged to its producing operation; each consumer pays
its own verified-import, view/update/routing/handler/finalization work under the reviewed gas manifest.
Physical cache hits do not remove those charges. Do not recharge producer business execution at
every consumer. An operation inside an owned feedback component shares one meter; evidence does not
turn its internal feedback into independently metered imports.

## 4. Placements, routing and creation

Order distinct placements by the existing containing-binding comparator: source/consumer DocumentId,
portable-text source path, numeric activation generation, then portable-text occurrence identity.
Within one consumer the document coordinate is equal. For composed equal-depth routes, compare the
ordered sequence of those binding coordinates lexicographically, never hash-map or SQL page order.
For each source patch, visit the currently eligible
placements in that order. Update one placement and finish its synchronous containing continuation
before the next. Thus a /left update handler reads /right's old view0 before /right advances to1.
A subsequent E delivered after both updates reads1 unless a handler changed that state. Preserve
nested original update paths; whole-reference replacement cannot erase a /child/x watcher.

Revalidate a later placement before starting its update. If an earlier continuation retired or
retargeted it, do not patch the replacement as the old occurrence; record the old occurrence's exact
lifecycle outcome. Once an event is enqueued its source/ancestor occurrence identities, activation
membership and authored routes are frozen, not its receivers' event-channel definitions. At each
FIFO delivery, classify Triggered/Embedded channels from that receiver's canonical execution view
at that point. This is the ordinary Contracts rule (`ScopePropagationChain.deliverTriggered` and
`deliverEmbedded` refresh the contract bundle at delivery). It is never a read of the host's newest
committed head. A preceding queued handler may therefore change the channels matching a later event,
but cannot add a newly activated occurrence to that event's frozen recipient set. Later retirement
does not silently retarget or cancel valid admitted work: follow the exact target-invalid/failure law.
Document-update dispatch is a different boundary: its accepted mutation uses a frozen pre-mutation
contract surface (`FrozenDispatchContext`), so a new watcher cannot observe the update creating it.
Freeze occurrence membership at enqueue, not at SQL page enumeration.

One inherited reaction origin at one causal position produces one complete reaction per owned
consumer scope, including all its eligible placements, converging producer projections and own
direct seed where applicable. Direct and transitive routes of that same origin are composed before
completing that reaction. They are not independent flat receipt imports; required upstream projections
may be read lazily. Missing Parent evidence can block Root, not Source; a terminal Parent failure
contributes the explicit rollback projection defined above instead of an endless wait.

For acyclic nesting, an original event reaches all its containing authored scopes, nearest first;
equal-depth branches use canonical occurrence-path order. Distinct alias paths remain distinct
deliveries even when they reach the same lineage. A parent re-emission is a new occurrence.

For cyclic containment, freeze **vertex-simple authored routes per original event occurrence**:
no DocumentId, including the emitter, may repeat on one route. The emitter receives its own Triggered
handling once; transporting its original event around a cycle does not deliver that same occurrence
back to the emitter. Each eligible route prefix owns its containing delivery. Distinct alias paths
remain distinct even when they end at the same receiver; there is no receiver-global visited set.

This extends finite authored ancestor routing without creating an infinite passive walk. In A↔B,
A's E reaches B; if B emits F, F is a new occurrence and reaches A. Every new handler emission starts
fresh routes under the same owned workflow gas meter, even for a payload equal to E. Genuine business
feedback can therefore continue until quiescence or gas exhaustion and full rollback. The route rule
never deduplicates equal payloads or suppresses newly caused occurrences.
Route expansion/dispatch is metered incrementally before allocating further work; exponential
numbers of distinct valid paths cannot bypass the operation's gas and operational memory limits.
This cyclic route rule is an explicit owning-library extension, not a claim of an existing ordinary
infinite-tree reference. Test its exact multiplicity separately from business-loop termination.

Creating /b during /a's handler does not add /b to a previously frozen event. Canonical initialization,
its selected initial view and required synchronous lifecycle/update observations are available before
a subsequent permitted creator read. Existing historical source epochs are selected into an ordered
attachment catch-up lane, not silently executed as one creator epoch. Before that lane runs, a read
sees the actually installed initial view and parent state, never a fabricated caught-up shadow field.
An occurrence with pending selected historical work does not simultaneously receive live source
patches/events: its durable activation and history/live handoff give each eligible observation one
place. In particular, /b must not first advance from later frames of the current source operation and
then replay those frames again through its new history lane. Earlier /a deliveries are not replayed.

Here, a **permitted creator read** is after the actual creating patch boundary, not a speculative
read inside the still-running handler that buffered the patch. For example, a creator with an exact
inactive `/child` reference to B10 requests FROM_FRONTIER at F, whose view is B5. Its first Compute
buffers Process Embedded activation; a second Compute in that same handler still previews the
authored B10 reference. After handler return applies activation, a later ordered handler reads B5.
Both readings are deterministic and must survive cold replay. FULL_HISTORY obeys the same boundary:
a pre-application preview sees authored content; the later handler sees completed init0.

The next selected source operation is a separate catch-up operation; unavailable history/provider
completeness suspends that lane. A creator read is an ordinary read of the specified installed view,
not an implicit await of catch-up. A workflow that requires the later converged state must express
that use in a subsequent operation released after the lane; it cannot suspend its creator waiting
for its own commit. FROM_NOW installs the exact view at its accepted activation boundary and starts
its live suffix there, not at the physical source head. This is an explicit managed-library contract:
initialization is synchronous; historical convergence is staged, while source operation/epoch
boundaries and already-frozen recipients remain intact.

## 5. Canonical origin: source history is not born at the first Order

DocumentId is the exact initial authored BlueId (existing cyclic identity rules included). In this
integration, its source-history basis is canonical initialization plus FULL_HISTORY under the frozen
semantic environment and source execution policy. Physical admission/materialization time and the
introducing Order are absent from the basis. X with E15 has E15 in its reconstructed history whether
Order A attaches at T10 or Order B first materializes it at T20. Initial source lifecycle execution
uses a source-local context, without an ambient creator or creator processing event.
For an authored cyclic component, the canonical admission anchor is its minimum DocumentId under
the existing portable DocumentId comparator. Materializing that same component through another
member cannot choose a different initialization cause; all original members and exact component
predecessors still participate in invocation authority. A singleton keeps its own DocumentId anchor.

This explicitly changes current NEW_AUTHORED/temporal-admission composition for the target integration:
FROM_NOW and FROM_FRONTIER select **observer attachment history**, not a different authoritative
history for the same X. FULL_HISTORY selects all eligible source history. A FROM_NOW observer atT20
does not receive E15 as its own historical event, but does not erase E15 from X. Its initial installed
view is the source view at its authorized attachment boundary; absence of that exact view waits.
An authored-from-origin attachment instead installs the canonical initialized view and consumes its
selected historical epochs separately. Mode, cutoff and initial view are explicit semantic inputs,
never chosen from row existence. All Timeline input clocks/cutoffs remain exact, not wall-clock NOW.

Derive domain-tagged canonical constructor preimages in this order:

1. source basis = authored DocumentId + complete environment + fixed source execution policy +
   canonical full-history rule identity;
2. initialization execution seed = source basis + INITIALIZATION; input uses the authored initial
   value and canonical source-local context;
3. external/reaction execution seed = operation kind + canonical cause/reaction origin + seed lineage's
   exact semantic predecessor + attachment/placement selection when applicable + environment/policy;
4. emitted occurrence = stable producing execution seed + producing lineage + local emission continuation address
   + local emission ordinal + event BlueId. Routing/delivery identity additionally binds the receiving
   occurrence/activation and composed route. Unrelated observers and physical commit order are absent;
5. final atomic operation/settlement = canonical selected execution seeds and exact predecessors plus
   the verified accepted scope-admission sequence and environment/policy. An independent operation is
   the singleton case. Admission evidence names seed-local prefixes, not final result/settlement IDs;
6. transition/result/program/publication wrappers derive from these earlier identities and their
   complete contents. No identity includes a wrapper containing itself. Final group/settlement IDs
   are not exposed to running business code or used to decide an earlier admission.

The exact canonical codec and hash/type constructors belong to the libraries; the preimages above
are the selected fields, not permission for the host to discard fields from current InvocationId.
For an initially coupled scope, its exact component/predecessors participate in seed authority.
Dynamic admission preserves earlier seed-local execution/occurrence identities while forming one
final atomic settlement. r15.10's retroactive re-anchoring of emissions is withdrawn. Abandoned
attempts cannot publish even when their seed-local identities equal a later canonical replay.

Compute canonical initialization once or reuse its authenticated candidate. Preparation grants no
authoritative lineage or gas settlement. A successful introducing operation can atomically publish
the complete required successful candidate set and its own result; failure or create-then-retire
publishes no orphan new source. Standalone authorized source admission uses the same canonical result.
Already authoritative X is verified/reused, never erased by a failed later Order. Concurrent promoters
of X publish the same source operation/result once; disagreement is an integrity/configuration error,
not a choice made by the first INSERT. Parent-owned attachment/history remains distinct in each case.

The selected initialization view is local to that observation. If an existing alias points to B's
epoch5, a new FULL_HISTORY placement may observe B's init0 without rewinding B or changing the old
alias's reads or eligibility. Source work/patch/completion identities stay original. The consumer's
initialization observation additionally binds the original source site, creator execution seed,
actual creator patch site and occurrence identity. Two placements, including two created in one
operation, therefore remain distinct without inventing a second source history or using worker IDs.
The accepted initialization record is a retained observation fact, not proof that a separate source
commit already exists: a later retirement keeps the fact needed for replay but cannot authorize a
surviving import lane or orphan source publication.

A cold FROM_NOW@T20 may need initialization plus the canonical source-operation prefix through E15,
not just epoch0. Prepare that exact prefix, including unsuccessful handled inputs and their gas/
dispositions, in bounded immutable staging steps. Each step uses the same selected source operation
and constructor as authoritative execution; staging creates no public history or settlement.
PreparedSourcePrefixEvidence binds the operation/result/program chain, successful tail, completeness,
indexed projections and source append range. A short successful-parent transaction activates the
complete verified prefix root and parent result together, rather than inserting an unbounded history
inside one transaction. All public queries/outbox reads are gated by that activation authority;
required temporal/work indexes are ready before activation, not repaired eventually afterwards.
Already authoritative matching prefix sections are reused; overlap uses exact canonical-prefix CAS
and never settles an operation twice. A failed parent leaves only non-authoritative reclaimable
staging. This is publication of prepared exact history, not retroactively moving its causal positions.

Canonical source initialization and parent consumption have separate fixed gas scopes even on a cold
host. For source80 and parent20 with each limit90, both pass cold and warm; there is no cold combined100
versus warm20. This is an explicit ownership change, not equality to the old combined invocation.
Source gas is settled once when its canonical result gains authority. Rebuilding retained history
cannot settle it again; parent logical import/verification/reaction charges are identical cold/warm.
This defines processing gas, not a new user billing policy. Host CPU/IO quotas cover unsuccessful
preparation and repeated physical attempts separately. Failed canonical initialization has no usable
initialized result and cannot become successful through a cache miss or a different promoter budget.

## 6. Import failure: consume one operation, not an arbitrary range

Progress is a set of exact input lanes, not one external-or-managed scalar. When one operation
combines its own direct external seed with upstream imports at the same origin, its external-input
disposition policy governs the complete terminal result: consuming that result accounts for all
selected external and managed lanes together; a blocking result consumes none. A non-successful
consumed result retains every business rollback view and records each exact delivery's actual status,
never a successful import. The operation has one settlement/gas charge, not one per lane. The selected
pure-managed rule below also permits recognized semantic RUNTIME_FATAL; other statuses are not
universally consumable.

Select the same terminal GAS_LIMIT_EXCEEDED and recognized deterministic RUNTIME_FATAL rule for live
and historical managed reactions. Classification must satisfy §6.2; an arbitrary runtime exception
cannot authorize progress. Roll back the consumer operation, retain its actual successful view/epoch,
and record the exact delivery's terminal
failure and gas. The source and independent siblings remain committed. Continue canonical selection;
do not retry identical failed semantics forever or abandon a page/range of unattempted deliveries.
An attachment lane finishes when its fixed-cut obligations are all terminal, including failures;
its status is COMPLETED_WITH_FAILURES, never all-success. Unknown commits, missing content and stale
provider completeness are nonterminal and cannot be consumed to enable later work.

**Uniform alignment rule after a gap:** before interpreting the next source operation, compare the
consumer's actual successful pin with that source operation's exact before-view. If different, apply
one explicit containing-reference alignment update, with ordinary synchronous update reactions,
lifecycle effects, enqueue sites and gas, inside this new operation. Then interpret its source
observation program. Successful normal contiguous import needs no alignment. Alignment is not an
invented source receipt, successful historical import, or replay of the failed source events.
Gap continuity accepts every authenticated lawfully consumed delivery outcome: managed gas failure,
recognized semantic runtime failure, or a composite external-policy failure. Verify the original
kind, classification/policy, shared settlement and exact lanes. A nonterminal or unhandled gap rejects.

Successful observation pins are keyed by **consumer and source**, not by source alone. If coupled
consumers A and C both consume S1→2, A may still have S0 after an earlier failed import while C has S1.
A failed coupled attempt preserves those two distinct rollback pins. Its cold receipt cannot assign
A's gap to C, infer either epoch from the host's current S head, or depend on optional cached bodies.

For a composed multi-producer reaction, perform each missing-pin alignment at that producer's first
canonical Entry/consumption site, immediately before interpreting its actions at that site. There is
no global prelude aligning all sources. Entry placement follows the same frozen direct-seed and
continuation/FIFO order as normal source interpretation; SQL receipt order cannot select it. Complete
each occurrence's alignment and synchronous containing continuations in the usual placement order.
Alignment-created events enter the actual FIFO at this site. Shared producer cells are interpreted
once, but distinct eligible occurrences retain their own alignment/observations. Retirement/rebinding
during an earlier alignment revalidates later occurrences; never align a replacement as the old one.

For Root pins A0/B0 after a failed combined reaction, next origin with A1→2 then B1→2: preserve the
actual dependency-first seed order, so a direct Root seed after both sources sees2/2. At A Entry,
align A0→1 then interpret A1→2; both /a update callbacks read
B0. Only at B Entry does B align/replay. Do not expose B1 early merely because B appears in the group.
A newly installed FROM_NOW view still follows its explicit attachment rule; it does not silently
repair an existing failed-import pin. Eventless/net-zero producers use the same Entry-site rule.

For consumer0, failed r1 (source0→1), then r2 (source1→2): r2 aligns the consumer0→1, then presents
r2's own1→2 transitions. An r2 event before its first patch reads1. An eventless r2 still aligns;
net-zero r2 retains all its internal updates. If alignment or later r2 handling fails, all its effects
roll back to consumer0; only r2's terminal disposition advances. After failed r1 but before r2, an
otherwise eligible direct read of the actual consumer still sees0, not Source's independently newer1.
If the initial alignment removes/retargets the placement, normal lifecycle/frozen-delivery rules
determine remaining work; never install r2 into the replacement occurrence.

This deliberately replaces r15.8's simple0→2 shortcut. A problematic update-to1 handler may fail
again during r2 alignment even if r2 ends at2. Continued import guarantees attempted ordered progress,
not business success. Later canonically eligible detach/repair can proceed after preceding operations
have terminal outcomes. Already successful later effects are not retroactively repaired or erased.
Applications requiring a gap-free business protocol must express sequence/precondition checks in
their own logic; the platform does not pretend a failed operation ran successfully.

Stopping the whole import at its first gas failure is rejected as the platform default: it would
need a second rule abandoning all remaining due history, make range definition a semantic skip
boundary, and not solve equivalent live-import failures. The existing external-input gas→detach
recovery supported terminal progress in principle, but did not itself implement this managed alignment
law. Phase1 extends the successful-predecessor evidence explicitly; MyOS must not bypass its checks.

### 6.1 Host quotas and the first POC's repair boundary

Semantic gas limits one operation, not the number of later operations. A real synchronous feedback
loop stays in that one meter. A genuinely new attachment can instead order another import, whose
successful reaction orders another attachment: an unbounded series of individually finite operations
is possible. Do not deduplicate legitimate new activations or introduce inherited cross-operation
semantic fuel to force this series to finish in the first POC.

The host bounds account/document work, concurrent attempts, CPU/IO and resident backlog. Quota
exhaustion is an operational pause with the already committed prefix and outstanding obligations
intact; it is not GAS_LIMIT_EXCEEDED, an epoch, an import completion or permission to skip input.
For work needing80 under fixed semantic limit100, an account allowance50 means pause, not execution
with semantic limit50. Replenishment resumes the same legal work and produces the same semantic
history/gas. Physical attempts are measured separately and cannot settle logical gas twice. A custom
semantic limit must be an explicit replayable policy input, not the current host allowance.

Later repair is guaranteed eligible only once its earlier required work is terminally accounted for,
not for every nonterminating authored workflow. An endless earlier attachment lane can keep repair
ineligible even while the host safely pauses it. General semantic interruption/cancellation is outside
the first POC and is not a prerequisite for starting it. Preserve the pending authority so a future
explicitly designed mechanism can address it; neither quota expiry nor host intervention may silently
abandon it. This does not weaken the required finite gas-failure→later-detach recovery controls.

### 6.2 Semantic failure classification is an owning-library contract

RUNTIME_FATAL means a deterministic semantic failure after admission for the same exact input,
runtime/environment and policy. It does not mean server failure. Only recognized semantic failures
may produce this complete portable result, with unchanged business pre-state, no tentative events,
actual admitted gas and a deterministic category/context. AtomicScopeGasAdmissionFailure is such a
specified rejection, not an operational timeout or an assertion that the local meter reached L.

Baseline handler/BEX boundaries could wrap unclassified RuntimeException into runtime-fatal; returning
exception.getMessage() did not prove determinism. Phase1 narrows those boundaries before accepting
terminal runtime-fatal imports. Recognized authored/runtime semantic exceptions use typed
reviewed mappings. Unknown implementation exceptions, operational cancellation, I/O and host timeouts
escape as operational failures or typed resource waits, with no consumed input, epoch or semantic gas
settlement. JVM Errors are not assumed to be runtime-fatal results.

Tests separate a recognized semantic throw from cancellation/I/O/unexpected implementation exceptions
and verify portable diagnostics. External consumption policy is not permission to misclassify an
operational failure. Pure managed gas/runtime-fatal failures consume only their selected operation;
subsequent ordered imports use the same alignment law. Identical retry cannot repair either failure.
Initialization is different: failed initialization supplies no usable initialized source and cannot
be consumed into ordinary source processing or publish an initialized lineage. No generic exception-
policy or administrative cancellation framework is added.

## 7. One atomic publication may contain zero, one or many lineage projections

No-delivery is metadata-only: no fictitious processor invocation, gas settlement or business stream.
An independent operation normally projects one lineage. A real coupled operation projects every
owned affected lineage under one complete result, gas ledger and transaction. Canonical source
creation can add its separately identified initial authority to that same publication transaction.

The apply plan and receipt therefore contain an exact list of typed lineage projections and stream
appends, not a mandatory single local batch plus a special exception for new children. Each appendix
binds lineage, exact before/after or unchanged position, projection kind, result authority and stream
predecessor. Reject extra/omitted members. One transaction and one reconciliation receipt authenticate
all its appends; each stream can publish only its acknowledged immediate successor and required causal
predecessors. Atomic database publication does not imply simultaneous delivery to external sinks.
Cross-stream publication dependencies order distinct producing operations, not the network interleaving
of events inside one atomic operation. For one result with canonical A1,B1,A2, the authenticated result
retains that order, while per-lineage outbox batches may deliver A1,A2 before or after B1. Do not create
cyclic A-batch↔B-batch prerequisites to imitate the internal FIFO. Internal processing and replay use
the canonical result/program, never notification arrival order. A finer-grained external transport
contract is not required for this POC.
An activated prepared prefix authenticates its possibly long stream range by immutable membership
proof under that transaction's receipt; do not copy the full range into the commit receipt or require
one fictitious second initialization commit for its first batch.

Independent observers are not added to this list. Fan-out discovery pages remain host metadata work;
neither an empty page nor aggregate completion creates a semantic business epoch.

**An evaluation is not a publication boundary.** The implemented external core can return
`PreparedOperations`: several dependency-ordered, separately atomic owned-group operations, with
optional target metadata progress. A source result can therefore become durable before an independent
consumer result from the same evaluation. Each group retains all of its owned projections; creation
promotion retains its required atomic authority. Phase3 must persist/reconstruct the remaining work
after any intermediate commit. A single outer SQL transaction is not required, and an in-memory list
of results is not durable continuation. [05](05-poc-api.md) maps these rules to actual runtime types.

## 8. Acceptance, complexity and implementation sequence

The selected rules have hand-derived controls in20 and the validation plan. Those documents are not
test reports. Phase1 implemented/tested the kernel and constructors; Phase2 supplied PostgreSQL,
Timeline and indexed host primitives plus scoped actual-library bridges. Current executed evidence
is in the readiness record. Phase3 integrates the general path and measures it, then informs iteration.

Current limits matter: managed bodies can be demanded lazily, but complete authenticated directed
metadata/read-cut authority is still required. A selected observation program and its borrowed DAG
are decoded within physical budgets; fully streaming execution of arbitrary action frames is not
implemented. Recording intermediate observations may allocate before transport limits are checked.
Measure capture peak memory, decode/verification cost and cache reuse separately. PostgreSQL index
probes are not graph-processing throughput evidence, and large-fan-in prefix activation remains
bounded by the current host's documented fence cap. None of these limits permits semantic omission.

Measure source execution once, observation-program bytes/verification per retained frame, consumer
work, placement/path multiplicity and true feedback separately. Shared immutable nodes and verified
program caches avoid full-history copies. Source commit does not enumerate N unrelated consumers.
Directed fan-in of10,000 embedded Orders does require their relevant Timeline completeness; maintain
shared membership/frontier aggregates and incremental candidate merging rather than rescanning all
members per entry. Missing genuinely relevant proof still blocks; faster indexing cannot waive it.

Physical schedules, cold/warm state, retries, eviction, page sizes and split evidence reads must
preserve exact histories, observations, IDs, logical gas and terminal outcomes. Initial index build,
membership changes, genuine large fan-in and many valid graph paths have real costs. Meter and bound
them rather than claiming constant cost for arbitrary graphs. No further product choice is left as
a prerequisite to implementing this selected POC model; failed conformance may require a documented
correction, never a hidden host-specific interpretation.
