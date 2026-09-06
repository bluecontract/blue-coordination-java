# Storage and local transaction protocol

> **Status:** Phase1/2 foundation implemented; general Phase3 integration planned · **Semantics:** r15.12 · **Updated:** 2026-09-06  
> [Independent-lineage authority](18-independent-lineage-processing.md) · [Decision traces](20-decision-traces.md) · [Recovery](07-lazy-loading-and-recovery.md) · [Concurrency](08-concurrency-partitioning-security.md)

## 10. Logical durable state

PostgreSQL is the only mutable authority on the durable path. The adapter is trusted to execute
complete scoped queries and atomic transactions; this is not a Byzantine proof database. Coordination
owns semantic selection, temporal routing and dependency rules under
[22](22-processing-kernel.md), with the independent-lineage model in [18](18-independent-lineage-processing.md).
The same revised semantics must also work with the in-memory adapter.

The library boundary and PostgreSQL primitives below are implemented in the isolated library
worktrees and MyOS Simple's `blue.myos.mini.durable` package. The unchanged host pack passed 66/66;
the final actual-library/PostgreSQL handshake passed 7/7, including fresh-JVM M1/M2 recovery.
See the [readiness record](implementation/phase-1-2-readiness.md) for exact evidence and limits.
These results do not make the default MyOS application a graph-capable durable host.

Only the single-lineage acyclic `CanonicalInitializationAdapter` is connected to the real host loop
in the isolated smoke module. Prefix, failure and managed-import cases use explicit test bridges.
The general adapter, runnable ingress/recovery/discovery/publication pumps and application profile
remain [Phase3 work](25-phase-3-integration-plan.md). The transaction rules below remain obligations
of that integration; SQL must not imitate the interpreter or split an atomic group into fragments.

### Implemented storage boundary

`PostgresCommitStore` accepts a complete `HostModel.Plan` and a mandatory owning-result verifier.
It retains the exact attempt before publication, then applies its work/control fences and all
permitted projections atomically. The verifier—not a plan hash or an HTTP caller—authenticates
semantic ownership and completeness. `PostgresWorkStore`, `PostgresDiscoveryStore`,
`PostgresPrefixStore`, `PostgresTimelineStore` and `PostgresOutbox` supply the corresponding scoped
indexes and recovery primitives in the fresh `V1__durable_authority.sql` schema. This is not a
migration of the old application's H2/RAM state.

Normal external evaluation returns `CoordinationCore.PreparedOperations`: a dependency-ordered
list of independently atomic `PreparedGroupOperation` values. Each group maps to its own plan and
receipt; a feedback group can own several lineages sharing that receipt. The wrapper is not one
`CommitKey`. `DurableHost.Complete` currently carries one plan; durable continuation between these
group publications is part of the general Phase3 adapter, not an already deployed batch dispatcher.

| Logical state | Storage rule |
|---|---|
| Exact objects, revisions, complete result/source evidence | Immutable, identity-verified; retained for live, replay and recovery consumers |
| Initialization candidate evidence | Authenticated canonical source-local initialization/FULL_HISTORY evidence under fixed source meter; preparation grants no publication or settlement authority |
| Lineage state/history and semantic predecessor | Local authoritative sequence, not domain commit order |
| Handled external-input progress | Separate from successful state epochs and imported-source cursors; exact operation-kind projection |
| Occurrence lifecycle and source cursor | Identity/generation plus temporal activation/retirement and exact last applied receipt |
| Temporal subscriptions and discovery coverage | Transactionally maintained authoritative temporal indexes/predicates/frontiers; not a best-effort cache of today's links |
| Source receipt and fanout basis | Atomically published; enough durable authority to discover required recipients later |
| Semantic-observation / representation evidence | Authenticated observation-program frames, exact views, continuation/enqueue sites, provenance/frozen recipients and gas; implement22's constructors; representation-only deltas create no business receipt |
| Fanout page progress and local delivery work | Bounded recoverable scans; stable occurrence/receipt/reaction-position keys, later proved local scope |
| Causal dependencies and outcome accounting | Explicit completion owner separate from source provenance; normalized readiness and exact failure/open-work accounting |
| Timeline ingress/completeness and admission handoff | Exact input and retained coverage, with distinct scoped ordering controls |
| Work, demands, waiters and reconciliation | Indexed ready work and reverse dependency waiters, operational ownership and level-triggered recovery |
| Local outbox streams and commit receipts | Atomic publication authority and independent per-stream acknowledgement |

Every durable key binds authenticated tenant/environment/domain. The concrete host uses scoped
keys and fixed-family/canonical-key control ordering, with `COLLATE "C"` for SQL text identities.
Reject aliases and duplicate targets before operation identity construction; database collation
does not replace the library's semantic comparator.

Persist immutable definitions once and small cursor/dependency deltas thereafter. Neither each
source commit nor each consumer commit serializes the complete application vector, completed causal
DAG or remaining global suffix. A whole-cause auditor may reconstruct more state, but that cost is
not copied into every local identity or transaction.

### 10.0.1 Trusted completeness and a concrete host fence

Core owns predicates and semantic validation; the adapter supplies complete coherent results. A
self-hash of returned rows cannot reveal a consistently omitted matching row. Audit indexed answers
against independent raw-state reconstruction in tests, including coherent omissions and recomputed
hashes; reconstruction is not the normal production lookup path.

Use scoped row versions, absence generations and authoritative temporal-predicate/frontier fences.
Every writer whose mutation overlaps a protected predicate participates in the same transaction
discipline. Cover source/consumer predecessors, occurrence generation, retained-history membership,
registration/replay handoff, configured scope and authorization as applicable. Exact immutable read
pins do not require the source's latest head to remain unchanged.

There is no universal expected domain semantic revision or portable global graph head. An optional
short global SQL lock may serialize physical writes in the first adapter, but it must not select
semantic order or invalidate an independent prepared Order solely because another Order committed.
The concrete scoped predicate/index protocol must pass the race tests; replacing a global revision
with unchecked row reads would reintroduce phantoms and missed registrations.

All writers follow one common lock order: all participating work keys, including primary and joined
work, in canonical key order; then unique scoped control and write targets in fixed family/canonical-key
order. A worker's own row has no first-lock privilege. Publication, invalidation and reconciliation
use the same order. No Contracts execution, network acquisition or N-consumer scan runs while write
locks are held. Current auth/reservation controls remain distinct from immutable input evidence and
operational lease/resource state.

An accepted completeness/normalized-ingress prefix remains sufficient under stronger proofs or
unrelated tail extension. CAS used to accept a newer coverage frontier does not revoke the older
prefix. Validate retained prefix authority and genuine current controls; do not require producer
quiescence or treat incomplete local ingress as an accepted prefix.

### 10.0.2 Indexed work selection is a host contract

MyOS is responsible for executing every due reaction, or persisting its specified terminal failure,
from Coordination's semantic predicates and validated incremental lifecycle/progress effects.
Dependency indexes are authoritative transactional structures, not eventually repaired caches.
Committing a document/lifecycle change also commits the matching index changes and sufficient durable
work/wakeup authority. No committed change may fall between state, routing indexes and scheduling.

The target includes millions of stored documents, thousands of active documents per user and
1,000/10,000/100,000 Orders sharing one Agreement. Phase2 implements the corresponding indexed
storage access paths. Its corpus probes one million dormant lineages, 8,000 ready documents for
one account, 100,000 occurrences and 10,000 relevant Timeline members. Those are metadata/query-plan
witnesses, not execution of that many real Orders. This table states the integration contract:

| Access pattern | Required bounded indexed lookup / atomic effect |
|---|---|
| Source published | Scoped source identity, activation/retirement position and lifecycle coverage select the next recipient page; receipt plus resumable fanout basis commit without enumerating N recipients |
| Occurrence attached, removed or rebound | Atomically update temporal source-to-consumer membership, occurrence generation/cursor and registration/backfill/retirement work |
| Consumer can advance | Scoped ready-work index selects a bounded set of candidates; Coordination validates its exact next semantic action, so queue priority never selects business order |
| Source prefix, resource or dependency becomes available | Reverse dependency/waiter index selects only affected work, with durable resumable wake progress and work-generation fencing |
| Lease expires or wake notification is lost | Indexed due leases/needs recover bounded candidates; no scan of all documents, or every document belonging to a user |
| Consumer terminates | State/outcome, permitted progress, dependency/readiness updates and newly owned work commit atomically |
| Reporting is requested | Read indexed coverage and terminal/open outcomes separately; no report completion transaction is required for an independent semantic commit |

Leading scope keys, temporal predicates, partial ready/due indexes, paging and row-version/predicate
fences must be chosen and checked together in the target-shaped host design. Validate actual query
plans, rows examined, lock contention and wake amplification with large unrelated/dormant populations
and thousands of active documents for one user. A current membership index may be supplemented by
temporal rows, durable lifecycle positions and unresolved-reservation/frontier indexes; all are
transactionally coherent. Unprocessed earlier topology is an explicit incomplete semantic frontier,
not tolerated index corruption. Normal operation does not reconstruct document histories to route.

Index structure, partitioning, queue layout and batching remain host decisions. Complete selection,
incremental maintenance, bounded resource use and no lost work are integration obligations. An
initial globally serialized adapter may serve a correctness baseline, not establish scalability.
Required fanout is O(number of due reactions); unrelated platform cardinality must not add a global
or per-user scan to each local step. No claim of constant total processing time for N reactions is made.

## 10.1 Timeline ingress and admission

Timeline ingress atomically stores the exact immutable entry/cause seed and durable discovery work.
Guarantee-only ingress may advance coverage without fabricating an entry. Neither freezes recipients
from the currently materialized graph. Canonical exact Timeline/entry identity, strict per-Timeline
microseconds, predecessor continuity and complete exact directed relevant-Timeline coverage remain
mandatory. Trusted provider authorization is not a domain-wide concrete completeness roster; see [15](15-myos-timeline-foundation.md).

An admission reservation owns the admitted lineage and its affected registration/replay handoff.
It does not make all source and sibling progress wait for the admitted consumer's complete catch-up.
Fence genuinely overlapping initialization, historical/live cutoff and subscription registration
so a cause is neither missed nor processed twice for that occurrence. Reuse an existing identical
authored lineage; do not create another ID to bypass overlap.

A domain-level lock/slot may serialize reservation registration as an initial host simplification,
but cannot remain a semantic barrier on independent source/consumer work for the entire workflow.
The scoped storage handoff and both race orders are covered by host tests; their general mapping
from actual library attachment results remains an integrated Phase3 obligation.
Initial failure performs only ownership-authorized cleanup and retains the failure receipt; an
operational hold does not release a handoff or publish a partially admitted lineage.

Canonical source origin is source-local initialization plus FULL_HISTORY under the frozen environment
and source execution policy. FROM_NOW/FROM_FRONTIER select observer history and initial view, not
another source birth. The owning libraries now implement this distinction; the old SDK composition
is not its authority and a SQL enum cannot replace it. Promotion time and parent identity cannot truncate source history.

Independent source initialization and parent consumption have separate fixed meters even when cold:
source80 and parent20 under their limits90 both pass cold/warm. Source gas settles once when canonical
authority is published; rebuild does not resettle it. Parent logical import/update/handler costs are
cache-independent. Real coupled initialization keeps shared-workflow gas. Retained authority survives
physical eviction; losing authority is data loss, not proof that X never existed.

The conceptual initialization candidate is non-authoritative until scoped promotion; no prepared
result has an authoritative lineage/receipt/fanout/outbox or gas settlement. The implemented
`SourceInitialization`, accepted installation/view capabilities and typed operation receipts retain
the owning facts. The creation-publication companion described by [22](22-processing-kernel.md)
is a semantic obligation, not permission to serialize a private runtime object graph or assume
every conceptual record name is an exported Java type.

Successful authorized creation atomically publishes its introducing result and all required newly
created source results, settlements, exact states, initial cursors/registrations/obligations and source
batches under owner predecessor and child absence/history/policy fences. Preserve the complete
transitive unpublished set for nested/shared initialization; do not publish a cached X while omitting
its required Y or replay Y initialization already consumed by X. Owner failure publishes no orphan.
Matching authoritative sources are not overwritten or reborn; reuse preserves the selected operation's
logical costs. No general transaction-group API is introduced.

Cold attachment selecting an existing historical cut, such as FROM_NOW at T20 with source input E15,
may need a canonical prefix rather than initialization alone. Build canonical source-prefix evidence in
bounded immutable staging steps: canonical basis, exact through-position and operation count, complete
operation/settlement/observation-program root, index-projection root and append-range authority. Include
failed source inputs' exact terminal dispositions without inventing successful epochs. Staging is not
semantic publication. The introducing SUCCESS transaction activates that exact prefix root under scoped
CAS, together with its own result, rather than inserting an unbounded history in one transaction.
Every temporal, ready-work and outbox query gates staged rows/ranges on the authoritative activation
root; its indexed coverage and projection completeness are verified before activation, not repaired by
untracked background catch-up. A failed creator activates nothing. Matching authoritative prefixes are
reused; overlapping promotions reconcile canonical prefix authority without duplicate source gas.
The trusted OutboxPort verifies each staged append's range membership against the immutable append
root and activating commit receipt before returning it. Section10.6 defines the bounded read contract.

The concrete `PostgresPrefixStore` stages at most 128 rows per page and enforces byte budgets.
Sealing must finish before preflight reads the final head or dependency-source summary. Activation
publishes the exact root and final pointers plus bounded registration cuts, currently at most 1,000
explicitly fenced dependency sources and subject to the overall plan/control limits. It does not
promote every staged row in one transaction. A larger required scope holds operationally; the host
must not omit dependencies, split a semantic group or claim 10,000-source activation is already proved.
An empty discovery-tail row may be reused under its zero-tail fence without deleting reservations;
existing published lineage/receipt/stream authority cannot be overwritten. Activated prefixes support
later ordinary verified live operations through the same authority, not a second source head.

CommitReceipt retains typed initial-lineage projection authority for every permitted source batch in
that transaction. Source events keep their original emitter/identity and are not relabeled parent events.
Database creation authority is not a reverse outbox-ACK dependency. Standalone authorized admission
needs no fictitious parent. Canonical constructors/accounting cannot be chosen by the physical winner.

## 10.2 Source publication and deferred recipient discovery

The source's complete local invocation commits its exact state/result, local settlement, authenticated
source receipt, outgoing event occurrences, required semantic-observation evidence and DurableFanoutBasis in one short transaction. It also
writes local dependency/accounting changes, outbox/tail and CommitReceipt. A crash after this commit
cannot leave an authoritative source revision with no discoverable delivery obligation.

The immutable basis carries the completion owner of this new work separately from source provenance.
K100 importing E10 may create a new source receipt/fanout owned by K100; E10 remains closed. Derive the
producing transition seed from already-derived operation/InvocationId and predecessor before receipt/
origin wrappers; a wrapper must not contain a hash of itself.

The fanout basis identifies the retained source receipt/range and temporal discovery scope. It is
not a complete recipient list and does not claim membership closure at source commit. No requirement
to load N Order bodies or create all application definitions belongs in that transaction, even at
100,000 recipients. Indexed temporal membership and lifecycle coverage drive later bounded expansion.

The ordinary and activated-prefix source readers use one explicit record contract:

| Field | Meaning |
|---|---|
| `operationId` | Semantic group operation; null only for a metadata checkpoint |
| `receiptKey` | Authenticated operation-receipt content key, or distinct metadata-checkpoint key |
| `publicationCommitKey` | Actual ordinary or prefix-activation transaction that published the record |
| `prefixRecordKey` | Optional immutable staged-history provenance; never a physical commit key |
| `programKey` / `failureKey` | Exactly the success program or typed failure capability selected by the explicit evidence kind |

`SUCCESS`, `FAILURE` and staged `METADATA` are separate shapes. A handled source failure retains
its semantic operation and authenticated failure capability, with unchanged successful state/epoch
and no successful program. Metadata advances only justified bookkeeping, preserving the semantic
predecessor and creating no business operation, gas or append. Source operation uniqueness is
`(scope, source, operationId)`: co-owned lineages may share one group receipt, but the same source
operation cannot acquire a second sequence. Never infer any identity's role from SHA-like spelling.

A bounded discovery step registers exact consumer/occurrence/source-receipt obligations and its scan
progress atomically. Unique logical delivery keys deduplicate overlapping scans and replay handoff.
Keys also bind activation and logical reaction position; different positions do not collapse.
Physical pages, batch sizes and retries never define semantic application grouping or identity.
The host-only materialization cap bounds one step; total N greater than the cap proceeds by paging.
Registration does not freeze the consumer's current head as the predecessor of future queued work.
Canonical local selection derives that predecessor-bound application when it becomes eligible.
Retained coverage uses indexed references/deltas, not a full ever-growing range list on each page.

Before consumer publication, verify the complete selected owned scope, including placements beyond
the loaded page. One source operation/reaction position has one complete reaction per owned consumer
scope. Update placements by canonical authored path, finishing each synchronous continuation before
the next; preserve occurrence cursors, revalidation and gas/rollback boundary. Pages cannot choose it.

Retain authenticated observable updates, exact intermediate views where observable, event admission/
order, original provenance, frozen recipient validity and logical gas. These immutable values may be
stored without publishing intermediate authoritative heads. The compact evidence constructor and
consumer realization implement the selected observation program under [22](22-processing-kernel.md);
a self-hashed final state/event list is insufficient. Preserve atomic publication of the complete
result, not atomic invisibility of every intermediate view to handlers inside that result.

New placements synchronously install canonical initialization or the selected initial view and
lifecycle effects without joining earlier frozen events. Their historical epochs use the ordered
fixed-cut attachment lane and its durable intent after successful creation; rollback leaves none.
For S → P → Root recursively interpret upstream programs, preserving P views and S/P original
identities/actual enqueue sites. No flattened Parent relay or forced final-P view substitutes for
that work. Vertex-simple per-route cycle handling and placement multiplicity follow chapter 22.

Lost ACK from page registration reconciles its expected/next progress and exact created delivery
keys under the same scoped fence. A confirmed matching transaction returns its original result;
changed progress alone is not proof that this page's complete registrations exist. A stale attempt
cannot later add incompatible work. Recovery may reuse the ordinary work/receipt authority; it does
not rerun source business processing or introduce semantic commits per physical page.

Source-ahead topology is a required case: Agreement@30 may be durable before an Order processes its
own attachment/removal@20. An index of only current committed links does not prove that earlier
topology decisions have finished. The authoritative temporal index therefore also exposes relevant
consumer/lifecycle frontiers and unresolved work. Candidates may be queued provisionally, but local
application waits for exact temporal membership, consumer progress and canonical dependency evidence.
Normal selection uses these maintained indexes, not document-history scans. Reporting complete fanout
requires certified coverage through the cut and accounted dispositions for every required reaction;
the optional aggregate report never gates source publication or independently ready consumers.

Later admissions have their own replay ownership. A genuinely later admission cannot reopen an
already proved earlier live recipient set merely by reconstructing authored history from the past.
The discovery/lifecycle authority must distinguish that case from an earlier eligible registration
whose materialization was delayed.

## 10.3 No-gap registration, replay and retirement

The first PostgreSQL adapter uses one short scoped metadata transaction. Capture retained-source log
tail h and atomically record:

1. Occurrence identity/activation and immutable semantic history-selection basis.
2. A durable selected-history backfill obligation through h.
3. Continuing source-tail acquisition after h.
4. An append-only registration/lifecycle change entry and durable discovery wake basis.

Source append and registration participate in the same scoped source-tail/index authority, in the
common lock order. If append commits first, the receipt is covered by backfill; if registration
commits first, tail acquisition covers it. Overlapping scans deduplicate the same logical delivery.
Read heavy bodies and execute Contracts outside this transaction.

h is a physical acquisition boundary, not the activation timestamp, a source processing frontier,
historical cutoff or portable operation/receipt identity. If source@30 is ahead of attachment@20,
backfill may acquire a future receipt but cannot execute it before semantic eligibility. The semantic
selection basis and source position/causal visibility remain independently checked.

A late registration can appear behind an already scanned key. Discovery therefore follows the
append-only lifecycle change log or an equivalent scoped range-generation protocol as well as the
bounded recipient scan. Plain keyset pagination over mutable current subscriptions is insufficient.
Persist small progress/reference deltas, not the full scanned prefix. Exercise publication-first,
registration-first, lost ACK, restart and late insertion behind the scan key.

Registration/membership completeness and consumer processing progress are distinct frontiers. To
prove eligibility at a position, close the relevant earlier lifecycle decisions; do not require the
very delivery being selected to complete first. Membership closure additionally covers the possible
earlier eligible registrations, not just known subscriptions. Whole-fanout closure may lag sufficient
eligibility evidence for an individual consumer; neither frontier is inferred from an empty page.

Removal/rebind tombstones obsolete future inter-operation work for the old activation under its exact
semantic position. It does not delete/rewrite an unbounded future work suffix in that transaction;
later selection validates the tombstone/generation and bounded cleanup is operational. Preserve
valid earlier and already-frozen local deliveries under Contracts' per-event validity rules, plus
foreign/nested independent work. E1 retirement does not justify blanket-dropping E2 or forcing an
invalid E2 activation. Cursor deltas follow the actual successful local result. A replacement has its own identity,
activation and history basis. Source history is not rewound; future source events are not imported
early to make a pending occurrence removable. These lifecycle rules are implemented and tested in
the owning libraries; the general adapter must map their exact deltas, not manipulate a private queue.

Keep retained source history in the first POC. Production GC is out of scope, but dropping required
revisions is not: open discovery, live/replay cursors, lifecycle coverage or recovery can retain
evidence after the source and many healthy consumers have advanced.

## 10.4 Prepare and commit one local operation

Before taking write locks, acquire the exact local predecessor, readonly source pins, occurrence
cursors and complete local reaction group, relevant original-input/receipt successor evidence and declared dependencies. Core proves
no earlier relevant noncommuting input is missing. Complete Timeline evidence does not prove the
source has processed the required dependency prefix.

The same original cause may target source and parent. Follow22's canonical seed/dependency ordering
and observation-program FIFO, not receipt-completion or page order. Before installing an edge that
would join unresolved groups into actual feedback, the library performs atomic scope admission.
Use their exact canonical admitted gas prefixes under the same fixed semantic policy/limit L;
incompatible policies are rejected as unsupported join input before mutation. First charge the
canonical admission-check cost c to the initiating group's meter before doing the check's work.
If c does not fit locally, ordinary GAS_LIMIT_EXCEEDED fails that current group and no join occurs.
Otherwise sum the admitted prefixes of distinct current groups, including c exactly once. If that
sum fits L, the groups share one continuing meter and irrevocable rollback/settlement ownership.
A later detach cannot undo admission. Only already admitted canonical work is included, never
cache-dependent or speculative attempt work. Read-only first access or a one-way edge does not join.

If the checked sum exceeds L, the joining edge is not installed and only the currently initiating
group fails with typed deterministic RUNTIME_FATAL category AtomicScopeGasAdmissionFailure. Retain
its real admitted gas prefix; do not fabricate GAS_LIMIT_EXCEEDED or gas used equal to L. Other unjoined groups
retain independent authority after accounting for that failure. Discard the failed group's tentative
effects in any independent consumer and reconstruct that consumer against the authenticated failure;
do not revise the canonical attempted-prefix admission decision after rollback. Missing exact
scope/prefix evidence waits rather than guessing.
Replay reproduces this admission decision at the edge's canonical site; it does not retroactively
run all earlier work under one global meter. Stable execution/event seed identities remain distinct
from the final settlement-group authority. A CAS winner cannot choose any of these rules.

One transaction checks:

1. Every participating work generation and exact execution/settlement-group/configuration authority.
2. Current scoped write/absence/predicate, registration, reservation and authorization fences.
3. Local semantic predecessor, complete reaction-group membership/cursors and satisfied dependency/selection evidence.
4. Settlement absence or the exact existing receipt.
5. Full result, operation kind, owned scope, exact local/fanout/dependency projection and permitted progress law.

It then writes the complete local result, settlement, authoritative local heads/cursors, new source
receipt/fanout basis where applicable, dependency/accounting delta, local next work, outbox/tail and
receipt atomically. In the proved independent readonly-observer case, the retained source is a pin,
not another WORK member. Genuine same-core feedback can own source writes and retains its full scope.
Cross-lineage writes without a proved library scope/order hold before publication.

Check any creation/semantic-observation/representation evidence against its actual owning result and scoped
authority. Representation-only finalization may require coupled exact component publication, but
keeps business epochs/source-receipt positions unchanged and creates no business receipt/event/fanout
or gas reset. It is an authenticated projection in the triggering operation's delta, not a new
business application. Preserve actual finalization charges and reject unproved mixed-cut components.

Complete M1 does not require execution-only M2 bytes or every future recipient. Commit exact remaining
authority with M1. Evidence needed for M1's own result, temporal membership or genuine prerequisite
still blocks it; no post-commit unowned planner guesses semantics.

## 10.5 Local failure and aggregate outcome

A deterministic failure records exact status/gas/diagnosis with its actual rollback. The verifier
matches the operation kind and typed receipt/lane progress to the selected action/result:

| Operation | Allowed terminal projection |
|---|---|
| Original external input or its historical replay | The frozen input disposition may consume the handled input; a recognized failure may publish a typed failure receipt, but no successful state epoch or success program |
| Managed receipt import, live or historical | GAS_LIMIT_EXCEEDED or verified deterministic semantic RUNTIME_FATAL: retain the failed group's pre-state and successful source cursors; persist its exact terminal delivery outcome, with no fabricated consumer epoch |
| Composite external+managed operation | Its frozen external-input disposition consumes all selected lanes together or blocks all; a consumed failure retains exact status, rollback views and one shared settlement |
| Initialization | On failure publish no initialized lineage; retain exact failure and perform only authorized admission cleanup |
| Proved no-delivery/history miss | Only exact routing/selection bookkeeping; no fictitious invocation/result/gas |

Successful state progress, handled-input progress and source receipt position are different durable
orders. SUCCESS alone projects successful business effects; terminal handled-delivery progress is
not permission to advance a successful import cursor. The implemented owning-library recovery contract lets
r2 follow a lawfully terminally consumed failed r1 from the actual rollback state with authenticated
continuous source history and dispositions. This includes verified semantic RUNTIME_FATAL and a
composite failure consumed under its exact external-input policy. First align consumer0→1 to r2's
exact before-view, running ordinary
synchronous update/lifecycle/gas effects, then interpret r2's1→2 program. Event-before-patch reads1;
eventless/net-zero operations retain alignment/observations. Failure anywhere rolls r2 back to0.
No failed r1 events, fabricated successful source receipt or skipped unattempted range. This uses
the owning-library evidence in [22](22-processing-kernel.md), not a SQL cursor shortcut. Source
and healthy siblings remain authoritative.

Pure-managed consumption covers GAS_LIMIT_EXCEEDED and verified deterministic semantic RUNTIME_FATAL.
The status label alone is not verification: unknown RuntimeException, host cancellation, timeout,
resource failure and implementation faults must remain operational outcomes, never consumed semantic
inputs. Failure classification and deterministic diagnostics require owning-library conformance tests;
a broad catch cannot manufacture this authority. Other statuses retain their exact operation-specific
laws. A legitimately consumed composite failure supplies continuity evidence without broadening the
pure-managed policy. Failed initialization remains unusable and cannot advance to ordinary source
processing; publish no initialized lineage and perform only authorized admission cleanup.

Core gas failure terminates its failed operation with exact rollback and original-input disposition;
do not automatically retry forever or permanently block unrelated/later eligible processing. A failed
managed r1 still cannot advance a successful receipt cursor for r2. D30 detach becomes eligible only
after earlier due r1/r2 deliveries have their lawful ordered terminal outcomes, not
because its handler does not read the source. Actual library tests prove this recovery path;
the general MyOS importer must still integrate it through the exact receipt/lane boundary.
Feedback within one core operation retains atomic scope/shared gas, not fresh-budget host fragments.

Whole-cause completion is optional aggregate reporting/test bookkeeping over discovery closure and
all required causal reactions, not a mandatory global terminal receipt, Contracts action, gas charge,
epoch, transaction or scheduling barrier. Durable per-consumer outcomes and open obligations remain
mandatory. If reporting is enabled, retain failed/open branches: a successful source receipt is not
whole-cause success, and a fully accounted failure is not successful throughput. No empty queue or
temporary lack of runnable work implies closure. A host may refresh the report after either the
last outcome or later coverage closure; report availability cannot gate independent progress.

Account each newly produced obligation under its explicit CompletionOwnerId, not originalCauseIdentity
or sourceOrder. Retained E10 imported under K100 does not reopen E10; K100 cannot finish while its new
required reactions remain open. Mere physical replay of already-completed work creates no new owner.

Outage, unproved absence, capacity pressure, cancellation and retry expiry are operational holds.
They do not settle a logical application, advance its protected cursor, retire required work or
convert a failed/unresolved branch to success. Only actual dependent scope waits on the branch.

## 10.6 Bounded per-stream outbox

A complete publication carries an exact list of typed lineage projections and stream appends. A
metadata-only no-delivery result has zero business appends. An independent operation normally has
one; a coupled operation covers every owned affected existing lineage under one result/gas/commit.
Each actual terminal lineage append may be empty. Source publication precedes consumer dependencies;
independent sibling streams have no global arrival order. Positions use each lineage's authorized
semantic predecessor, not a domain SQL sequence. Cross-stream causal dependencies are explicit.
These dependencies order distinct operations. Within one coupled result, canonical A1,B1,A2 remains
authenticated processing order, but batches A[A1,A2] and B[B1] need not reproduce that network
interleaving. Do not create cyclic same-operation batch prerequisites. Consumers/replay derive
semantic order from retained results, never notification arrival. Per-stream order remains required.

Successful promotion may add canonical initial source projections/batches in the same transaction.
The one reconciliation receipt authenticates every ordinary or initial append and its exact kind,
before/after, result and predecessor; omitted/extra projections reject. Preparation publishes nothing.
Initial source identities exclude the physical promoter; unrelated Orders are not part of that commit.

Read the exact immediate stream successor or a bounded contiguous page. Verify stream, predecessor,
receipt, stable message keys and any required causal predecessors. Tail growth cannot invalidate an
immutable next batch; a full shrinking unpublished suffix must not be reread on every send.

For an activated prepared prefix, trusted OutboxPort.readNext internally verifies every returned
batch's membership against ActivatedSourcePrefixAuthority.exactAppendRangeRoot and the exact activating
CommitReceipt. Read only the requested page and bounded immutable proof/index paths. A batch's own
hash, its ordinal falling between the range endpoints, or an activation receipt alone is insufficient.
No extra public proof DTO is required: the returned page is already verified by this trusted boundary.
Independently audit it against retained raw authority, including a substituted interior batch, wrong
activation/root, omitted predecessor and unactivated staged rows. Corrupt or unavailable membership
evidence cannot reach the sink or advance the publication cursor.

The implementation counts retained-plan/proof bytes toward the 4 MiB read budget. An oversized
first batch is an explicit hold, not an empty-success page. Prefix/live handoff uses the authoritative
tail, never `page.size() < limit`: a byte-short or dependency-blocked prefix page cannot skip to its
live suffix. Cross-operation causal dependencies are checked in both prefix-to-live directions.

Send exact local event order. The sink atomically durably accepts/deduplicates by stable key before
ACK; only then CAS the stream cursor. Competing attempts, ACK loss and crashes may resend the same
key, never create another logical event. Empty batches need no sink call. This is not exactly-once
network transmission or arbitrary downstream side effects.

Fanout scan/page progress is host bookkeeping, not a semantic terminal event stream; page boundaries
must not introduce outbox identities or extra processor applications.

## 10.7 Commit uncertainty and acquisition

CommitKey binds the selected settlement-group authority and exact local operation material, excluding
leases and diagnostics. Stable execution/event seed identities do not change when that group is
admitted. Same key/material returns the original receipt; conflicting material rejects.

Retain the exact joined apply plan by CommitKey, including all participating work fences, selected
input lanes and settlement-group authority. Durable aliases link superseded candidate/work identities
to that selected authority and its winning receipt; an old candidate must not infer noncommit merely
because its own provisional key has no receipt. Reconciliation resolves those aliases and the retained
plan before locking all participating work keys in canonical order. An absent winning receipt permits
retry only after the expected work authority is atomically invalidated, or a valid newer invalidation
is proved, so every delayed pre-join/joined proposal is fenced. CommitUnknown preserves its exact
query; neither a lost ACK nor a secondary work claimant can settle the group twice. Retain plan,
alias, receipt and invalidation authority for the POC run/recovery horizon.

Persist `WAITING` plus exact needs/work transition and reverse dependency/waiter keys. Bounded indexed
due-work and level-triggered checks recover
supply-before-register and crash-after-supply/lost-notification races. A stale waiter cannot reopen
finished work. Notifications are latency hints, not durable authority.

Operational capacity failures persist `PAUSED` with a reason and need an explicit `resumePaused`
after repair; quota replenishment releases quota pauses only. They must not release the same work
immediately into a busy loop. Reconciliation rechecks the exact retained plan key under all its work
locks before invalidating an attempt, so a stale reader cannot invalidate a replacement plan.

## 10.8 Numeric domains and performance

Check SQL BIGINT values against the field's actual canonical domain. Platform microseconds/counters/
gas use their specified safe bounds and sentinels; signed declaration order and authored content
keep their own domains. Reject rounding, fractions, overflow and alternate encodings before identity
construction. Observational commit time is excluded from semantic identity.
Timeline entries use 1..MAX_SAFE−1; the exclusive completeness boundary may reach MAX_SAFE, where
MAX_SAFE is 9_007_199_254_740_991. Reserve zero for before-first. An entry at MAX_SAFE is rejected rather
than accepted into a lane that can never obtain a strictly greater completeness bound. Observational
host timestamps and other numeric fields keep their own declared domains.

Measure source commit, per-consumer lag and optional whole-cause outcomes separately. Count source/consumer
physical calls, scoped topology/index rows, definition materialization, retries, serialized authority,
WAL, publication and recovery delay. Warm caches may improve latency, but unrelated parent count
must not force source payload hydration, semantic gas or a complete application-vector transaction.
Keep memory, transaction size and worker concurrency bounded while N grows. Use admission backpressure,
fair service and tenant isolation so a large fanout becomes controlled backlog rather than starving
unrelated work. Source latency is relative to its own required work; draining 100,000 reactions need
not take seconds. Retry/ACK/cache effects cannot add nondeterministic logical gas to those reactions.

Distributed commit, dynamic relocation, production GC, migrations and private processor continuation
remain outside this POC. Scoped fencing, temporal coverage and stable local identity are correctness
requirements now, not optional optimizations deferred to a later production design.
