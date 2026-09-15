# Historical baseline correction record

This is a review-sized extraction of the correction registry and linked records
from frozen Coordination `d220a50bb320fba67a6215ec0efdfd8ed3104928`, qualified
with frozen Language `e28ce80531663074f780367238785a5cc7f5171c` in September 2026.
The full donor registry's Git blob is
`c557f5d8da615e053720f783378e8e5b234bdce2`. This extraction retains the concrete
failure, library responsibility, alternatives, contract impact and regression
owners needed to review the remaining delta without another local checkout.
It is a historical record, not a new execution receipt or release authorization.

The [current reconciliation disposition](baseline-reconciliation-disposition.md)
is authoritative for what this candidate includes, replaces or omits against
pinned `c095f0f3321257531096289eb27a5f439c5011fb`. In particular, upstream's
Named Compute execution and root-pointer composition are already present; three
optional physical optimizations from d220 are **not retained**. Current binding
and qualification follow the [dependency rebind procedure](baseline-reconciliation-rebind.md).

## History, admission and exact source identity

| Historical failing example | Retained correction and why Coordination owns it | Rejected alternative / preserved contract | Regression owners |
| --- | --- | --- | --- |
| B has E0–E3. A's FULL_HISTORY admission selects E3 as terminal, but preparation interprets its synthetic replay-beginning marker as an E0 endpoint. Static FROM_NOW admission at an already-published entry has the same endpoint loss. | Retain admission-owned exact source publication views separately from replay start, authenticate them against the complete successful admission input and committed prefix, and reuse only at the original boundary. A host cannot repair the private admission/source association. | Do not use current heads, globally change strict-before to inclusive, skip initialization/reactions, extend to later E4, or suppress the terminal guard. Existing attachment modes, gas and public API are unchanged. | `RootedFullHistoryAdmissionFrontierTest` (authored reference/inline, E1, current E3, later E4 and same-epoch tail); `RootedStaticAdmissionCutoffTest`; existing frontier/terminal-tail controls. |
| A retains X→B after independent X has detached B. B→A prerequisite discovery jumps to newer X and falsely admits instead of waiting for A's earlier input. | Traverse the single selected source-root snapshot throughout prerequisite discovery. The SDK owns this graph selection. | No per-descendant latest-head substitution, invented prerequisite or publication-fence bypass. Keep pending-history and original-cutoff checks. | `RootedJoinPrerequisiteViewTest`: wait, unchanged A/B/X histories, exact earlier input, retry and restart. |
| B's LIVE attachment selects C at `/peers/c`; A later imports B's receipt and incorrectly reapplies that prospective selector in another scope. | Root-local retained historical work carries no prospective operation-selection plan. Its original receipt/cause and anchoring entry remain intact. | Do not erase stored selectors, relax LIVE path validation or manufacture another attachment. An entry's chronological anchor does not repeat the original operation. | `RootedLocalHistoryRecoveryTest.retainedCauseDoesNotRepeatItsOriginalOperationSelectors`, actual invalid LIVE path control. |
| An authenticated provider-authored X is serialized and compiled again as new Source, changing its admitted identity. | Pass the exact authenticated value through an internal exact-authored compiler entry. A host cannot substitute another lineage for the requested source. | Ordinary Source ingress and provider-content ingress remain distinct; retain the exact source-ID check and all authentication. No public API or processing policy change. | `RootedExactProviderAdmissionTest`: separate lineages, one admission, parent resume, later source update and source isolation. |
| During B1 import, `$document: /candidateC` copies saved canonical C0 using an expanded graph digest, producing an impossible content demand. | The BEX adapter uses the current working snapshot's canonical bytes/identity separately from resolved semantics, including after earlier workflow changes. | No stale pre-step snapshot, fake provider content, resolved-body hashing or BlueId-validation bypass. Existing BEX APIs suffice; no BEX change. | `RootedNestedDocumentValueIdentityTest`, `ScopedProcessorExecutionContextBexDocumentViewTest`: exact typed child, current read-your-writes, nested history and source isolation. |
| A reciprocal terminal receives the same exact source in different ordinary wire forms; independently normalizing one operand fails the imported-target guard. | After complete retained receipt verification, reuse the frozen source body only when both source document and exact successor BlueId match it. | Older receipts retain their own authenticated bodies. Do not compare only epoch/lineage, normalize arbitrary fields, replace physical heads or loosen the binding guard. | `RootedRetainedSourceWireTest`: matching terminal and older-source control, complete fresh result/event/receipt/trace equality, 463-gas success and 462-gas rollback. |

## Activation, joins and publication ownership

| Historical failing example | Retained correction and why Coordination owns it | Rejected alternative / preserved contract | Regression owners |
| --- | --- | --- | --- |
| Agreement observes Order attaching Payment0 after Order/Payment already published their own cycle. Comparing Payment0 with physical Payment1 creates impossible pending history 0→0. | Classify only a new path against its authenticated numbered publication at the original logical activation boundary. Require complete source body/epoch, source receipt, producer invocation and committed prefix. | Existing rows, reservations, pending cursors and genuinely older or distinct same-epoch views remain historical. No READY override, owner expansion, latest-head substitution or blanket equal-epoch clearing. | `RootedBorrowedCycleReadinessTest`: observer/source-first equality, second path, same-boundary rejection, older and same-epoch negatives, actual G−1/G/G+1 adapter controls. |
| In A→B→C→A, adding another saved C occurrence yields C7 work without its required same-epoch successor; execution correctly rejects the incomplete work. | Planning and capture share the exact staged/committed consumer view. A staged view must belong to this result, original boundary, complete derived owners and receipt-backed epochs; execution still requires durable membership. | Do not accept omitted tails, extend frozen endpoints, treat a staged view as already committed, or read current independent heads. No new API, cutoff, ownership or gas rule. | `RootedRingChordSuccessorReproductionTest`, `RootedDuplicateOccurrenceHistoryReproductionTest`: original input pins, exact successor, wrong result/owner/body/epoch and stale-capture negatives, all-root READY/restart. |
| A's related LIVE observation overtakes B's pending reciprocal terminal, changes A's representation and causes the correct owned-head CAS to reject. | Validate exact prospective return-cycle dependencies and preserve their original barrier until required terminal work completes. A derived candidate index locates views; exact retained plans and views supply authority. | Do not freeze one-way sources, side branches or incoming observers. Earlier prerequisites and independent roots remain eligible; do not loosen CAS or replace the frozen view. | `RootedJoinEligibilityTest`, `RootedTerminalOwnerFenceTest`, joined scheduling/publication/restart and pending-view controls. |
| A cyclic co-owned join consumes progress while B has not executed its own required original receiving work; B's reaction is lost. | Join scheduling requires every receiving owner's earlier and same-cause work to complete, while preserving the original LIVE input and causal endpoint. | Do not route a new event into an immutable witness, re-emit old tokens, advance progress without its calculation or force one physical schedule. | `RootedHistoricalWitnessForwardingTest`, `RootedImportedEventForwardingTest`, `RootedAutomaticJoinSchedulingTest`, `RootedJoinPrefixInterleavingTest`, original SCC-entrypoint controls. |
| A fresh diamond terminal retains immutable D5 while independently completed D23 carries the needed same-cause prefix. Replacing peers earlier also changes an 835-gas failure trace. | Authenticate eligible peer terminals and use Language's `rootedWitnessSelection` only to construct a fresh terminal operation. Retain calculating primaries, old complete source proofs, exact registered work/cause and complete peer publication evidence. | Never replace an entered invocation/retry, frozen source, pending consumer or calculating owner. Unsupported inventory changes remain blocked. All current-head, publication ownership, receipt and CAS checks still run. | `RootedDiamondPeerSchedulingTest`, `RootedDormantPeerReconnectTest`, terminal safety/publication controls: complete 835/836/837 results, late G−1 rollback, retry, original cause and frozen-source negatives, exact reactions and restart. |
| A detaches B, then a later operation attaches C at the same path. Earlier spec/runtime required rejection. | Preserve the explicitly approved legal C attachment using the reserved generation and fresh C occurrence/binding identities. Coordination retains original inactive B input and binds automatic retry evidence to the actual demand; PROCESS owns the new binding. | This is the one approved normative amendment. The stale rejection-only proposal is withdrawn. Keep same-invocation retirement, exact historical selection, immutable input and rollback; import C history only. | `RootedDetachedAuthoredRetargetTest`, `RootedRetargetInputTest`, resolver/authored-source and frozen-historical controls; corresponding Language legal-retarget owners. |

Language also needs two source-owned corrections for those scenarios. A finalizer-only
reference change during a numbered imported event with an authenticated deferred
successor must preserve the source epoch; genuine source-local work still advances
it. Separately, immutable A11→D2 proof context must remain intact when another
authenticated witness supplies D14. Coordination cannot repair authenticated
receipt epochs or rewrite immutable witness proofs. The candidate retains the
Language implementations and their adjacent exact identity/gas/ownership controls;
it does not include the discarded Coordination cursor accommodation.

## Selection and accounting

| Historical failing example | Retained correction and why Coordination owns it | Rejected alternative / preserved contract | Regression owners |
| --- | --- | --- | --- |
| An operation is removed, then its broadcast is consumable as NO_MATCH, but audit returns NONE and journal drain rejects a mismatch. | Share actual unreported terminal-transport eligibility between audit and completion. | A blocked root still blocks transport. No fabricated host NO_MATCH, broad NONE bypass, PROCESS, epoch or gas charge. Preserve once-only completion and cutoff. | `RootedTransportSelectionTest`, existing journal-cutoff and blocked-history controls. |
| Repeated short drains retry one gas-failed historical owner while independent work waits, or audit/admission changes the selected fair lane. | Retain canonical fair turns, owner rounds and exact-work failure isolation across calls; only actual execution advances the schedule. | Do not skip that owner's failed history or later same-root work, treat physical failure as consumed work, or make audits mutating. | `RootedSlicedSelectionTest`, local retained failure/retry, owner rotation, exact fresh result/gas and restart. |
| Future LIVE T300 repeatedly wins unrestricted audit while Journal-through-T200 cannot execute it or B's retained A2. | A bounded Journal-only call yields its scheduling turn to the eligible retained work already within that cutoff, without executing it. | Do not widen T200, execute unauthorized T300, erase failure isolation, spin no-progress commands or treat zero transitions as completion. | `RootedJournalCutoffFairnessTest`: exact A2 exposure, zero-write/gas yield, selection persistence, ordered final history and untouched future input. |
| A managed attempt reports positive exact gas but aggregate drain gas is zero. | Sum actual completed non-replayed attempts once across exclusive external, local-retained and registered-managed lanes. | Do not sum result/receipt projections twice or deduplicate real failed retries by identity. Retained replay adds no new call gas; original result gas remains. | `RootedManagedDrainGasReportingTest`: actual success, replay/suspension mapping, two gas failures and mixed lanes; existing SDK mapper controls. |
| A joint application contains three owned PROCESS transition receipts but drain budget counts one. | Use the existing receipt-derived `RootedResultScope.processTransitionCount` for published non-replayed managed work, matching other lanes. | Count actual owned receipts, including eligible same-epoch changes; not owners, numbered epoch increments or applications. Keep atomic invocation and between-invocation budget semantics. A host-only summary cannot fix the engine budget. | `RootedAutomaticJoinSchedulingTest`: three/four actual transition receipts with original business, trace, gas and restart oracles. |

## Integration queries

`AdvancedCoordination.observeSourceHistoryPrerequisite(previouslyEmitted)` and
`SourceHistoryPrerequisiteObservation` are additive public contracts. An empty
remaining-action list previously conflated satisfaction with stale/missing
request authority. Observation checks the original root, invocation, demand,
source lineage, authored identity, cutoff and retained owner/terminal fences:

- `PENDING` returns the exact refreshed current descriptor, including explicit WAIT.
- `SATISFIED` requires retained current requesting correlation and complete required
  source history below the frozen cutoff.
- `STALE` covers absent correlation, changed owners or a consumed requester,
  including terminal rejection without a changed document head.

Unknown authority is not success; altered logical authority rejects. The query
does not execute, publish or authorize stale work, and provider lookup scope closes
on success and exceptions. `RootedSourcePrerequisiteObservationTest` retains
satisfied/pending/stale/forged/terminal cases plus provider-scope and actual PROCESS
timer controls; existing prerequisite and discovery suites retain compatibility.

`AdvancedCoordination.auditNextRootProcessingSelection(root)` is the other additive
SDK method. Global fairness can select A's LIVE lane while explicit `processNext(B)`
needs B←C1. The host must know that exact managed work before acquiring its durable
invocation-owned lease. This query audits the existing explicit-root selector,
including local retained ownership; it does not reserve work or change global
fairness. The host serializes audit, claim and execution. `NONE` means no runnable
step, including dependency wait, and is not completeness. Changing global audit or
binding ownership after execution would violate existing contracts.
`RootedExplicitSelectionAuditTest` and local-history, blocked-transport and
SCC-entrypoint controls cover actual audit/execution identity, unchanged histories,
repeated read-only audit, LIVE/NONE and invalid handles.

## Named Compute exact presence

The merged implementation already executes inline, sibling/pointer and exact
definitions; merged canonical pointer composition solves `/` child-path handling.
The retained correction addresses another boundary: the same exact provider value
with `constants: {type: Dictionary}` or `functions: {type: Dictionary}` was rejected
by direct exact selection but incorrectly accepted when named selection erased the
field as equal to an inherited default. BEX's existing plain-name-container rule
owns rejection; no BEX syntax rule changes.

Use `WorkingDocument.sourceContributionsAt(pointer)` to distinguish explicitly
present exact maps from absent own-type defaults. `canonicalAt` cannot traverse
arbitrary verified sibling references, `resolvedAt` intentionally loses provenance,
and `SelectedExecutableBody` is confined to the executing body. The additive
Language query returns immutable current-occurrence contributions through verified
references/type ancestors without synthetic Source identities or unrelated scans.
The `$replace` fix preserves a replaced slot's inherited type contribution but
discards ordinary replaced fields; it corrects this new selector, not list resolution.

Compute's two-bit presence mask is evaluated for each invocation and added to the
existing plan key before lookup. Named and exact provider selections then reach
the same existing BEX validation. Canonicalized Source that discarded redundant
authored text remains authoritative: upstream canonical-identity/success tests
remain intact. `SdkNamedComputeDefinitionTest` retains absent/empty/malformed exact
provider cases, referenced parents, inherited/partial containers, earlier workflow
updates, escaped pointers and static type negatives. `ComputeProgramNormalizerTest`
retains exact presence, direct validation and immutable plan identity controls.

## Physical changes and evidence limits

The retained `RootedEligibilityCache` reuses one complete successful eligibility
mask only for identical selected resolved representations, exact event and ordered
delivery descriptors. Exceptions, partial comparisons and unrepresentable keys
remain uncached; a hit selects this call's delivery objects. Private entry/weight
bounds, eviction and reset preserve cold behavior and the shared comparison meter.
The historical 54-program MyOS restart exceeded 120 seconds with repeated
classification; a later combined host/library run passed in 73.311 seconds.
That ties the group to concrete baseline acceptance, but does not isolate its
speedup or make it a semantic requirement. The three eligibility owners cover
invalid/missing evidence, changed bodies, shared-meter exhaustion, ordered delivery
keys, eviction, close/restart and full cold result/gas equality.

The current candidate omits frozen immutable proof-container sharing, cross-call
`ManagedRepresentationVerificationMemo`, and early LIVE capture bounds. Hotspot
samples and combined passes alone did not establish their independent necessity.
Its unchanged upstream copies, fresh constructors and ordinary capture retain
the semantic corruption, mutation, abort, history/order and restart controls.
The [disposition](baseline-reconciliation-disposition.md#optional-optimization-omission-and-retained-semantic-coverage)
names the two omitted optimization-only methods and adapted owners explicitly.

Final frozen ring/diamond diagnostic drivers use ordinary SDK selection, preserve
original LIVE witnesses, and acquire peers only for a fresh terminal. Their full
result/history/BlueId/event/gas checks and shared finite call bounds remain. Those
test/helper adaptations do not grant a new processing policy.

## Historical qualification versus this candidate

The frozen consolidated review records d220 on Java 17 and 21 with 1,182 primary
cases per JDK, plus 54 extracted-source repeats; frozen e28 has 4,192 passing
native/specialized executions. Frozen MyOS `42f89a2c` passed 1,348 product/HTTP
executions; the later API successor ran a focused 255-case scope, not another full
suite. These counts are historical executions, not acceptance or inventory caps
for the new tuple. The new complete library, consumer and MyOS gates remain
required before any merge/release request. Published-artifact readiness is separate
from local development-artifact qualification.
