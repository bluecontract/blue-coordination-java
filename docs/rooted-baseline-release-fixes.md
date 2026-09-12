# Rooted resident-baseline corrections

This is the fixes-only prerequisite for merging MyOS Simple's rooted baseline.
It starts at Coordination `next` commit `c0a689de8d0ccd89de94bf6316618c7760a68e2a`.
It does **not** contain the external-state POC, PostgreSQL adapters, runtime
serialization, worker recovery, or a replacement processing model.

## Current qualification candidate — 12 September

The current candidate includes the corrections below and
[terminal-time causal peer acquisition](rooted-terminal-peer-acquisition.md).
The older source pins and partial failures in the following historical section
are retained for traceability; they are not the current candidate status.

- Language adds one public evidence-construction operation,
  `ClosureEvidenceFactory.rootedWitnessSelection`, with three implementation
  files, plus the generated package's current release binding. It selects
  authenticated immutable peer witnesses for a fresh terminal operation;
  it does not change an entered invocation or turn a witness into a publisher.
- Coordination implements the corresponding terminal acquisition/selection
  algorithm. Relative to the previously sealed `1d558` runtime, that change
  touches eight production Java files and the release binding. It adds no
  Coordination public API. It preserves PROCESS, logical gas, historical
  endpoints, publication ownership and exact-input retry rules. This is an
  internal algorithm correction and a Language API addition, not merely a
  one-condition patch. Unsupported changed witness inventories remain blocked.
- This does not erase the separate CTO-confirmed **normative correction**:
  detach B and later attach C at the same path is legal. The earlier rejection
  rule in both implementation and specification was withdrawn.
- BEX and Catalog have no new source corrections in this candidate. Their
  development exports must still bind the exact selected Language artifacts.

On the sealed Language `05bb6f31` / Coordination `0f5333c8` runtime tuple,
the diamond/dormant controls pass **8/8**, including identical complete original
result JSON across schedules at 835/836/837 gas. The original MyOS fourteen-input
ring and exact document-snapshot comparison after restart pass **1/1** on MyOS
`09c24ac5`, in 801.994 seconds, with 271 replayed commands. Business expectations,
histories and the existing host heap/readiness limits are unchanged. The full
library gates and the final MyOS **1,196 product plus 62 HTTP** cases remain
required; these focused receipts do not establish baseline merge readiness.

Current qualification-only successors maintain strict source/API inventories,
generated documentation and artifact identities. Their scope and gate order are
recorded in [full-gate preparation](rooted-terminal-peer-qualification.md).
No failed or filtered gate is relabeled as a full pass. MyOS's separate H2
eager-text and bounded restart-evidence fixes do not change library semantics.

One Coordination instance serving several registered roots was already present
in baseline `c0a689de`: a resource demand holds the requesting root's lane while
independent roots can progress. `RootedSourcePrerequisiteTest` already exercises
A waiting, B progressing, then A resuming. This means interleaving calls, not
parallel PROCESS execution inside one synchronized runtime. The earlier
`observeSourceHistoryPrerequisite` API addition below improves observation of
that existing lifecycle; it does not introduce multi-root ownership.

## Historical intermediate qualification

**12 September correction:** the user confirms that the lost cyclic reaction
is a bug and reports CTO confirmation that detach-B/later-attach-C at the same
path must succeed. The earlier inactive-retarget prohibition is defective in
both specification and implementation. Its rejection-only repair below is
**superseded**, not accepted final behavior. See
[confirmed corrections and requalification scope](rooted-confirmed-behavior-corrections.md).
Earlier exact-source reports remain historical evidence, not qualification for
these corrected semantics.

The initial follow-up was isolated from the previous qualified artifacts:

- Language: `codex/legal-detached-retarget-language`, then-current source commit
  `3491c515362bda55b72c8cc0e3558ad760ea9f86`, following the sealed
  `5a103afb369b710e3bf2b1158362220607604a07` candidate based on `34e9aa2f`;
  **94/94 focused tests pass** on the runtime/specification slice `1354663`
  for the corrected rule and
  exact-input/receipt/rollback controls. The revised Contracts specification is
  `sha256:5cc29e91cd8d4aa4d3dca98214da5ceb49b2daa82554ac561260bd98ce5063b8`.
  Supported generated package rebinding, documentation and the `5a103afb`
  immutable development export are complete. The `3491c515` follow-up changes
  one stale test identity literal plus its explanatory document, not runtime
  semantics. Its incremental build executes **778/778 passing tests**; the
  new immutable Language/BEX/Catalog exports pass. Final exclusion-free
  clean/quality qualification and the complete Coordination/MyOS tuple remain pending.
- Coordination: `codex/legal-detached-retarget-coordination`, tested source
  `661423fab0b836c380e5fa207e111e8b02e1c6c2`;
  removes rejection-only source capture, keeps the original B row and binds
  each retry demand to its actual expanded input. Automatic foreign discovery
  preserves old-B preference when the supplied value belongs to B. The focused
  immutable SDK03 gate passes **48/48** against sealed Language `5a103afb` above;
  full Coordination/SDK and MyOS acceptance remain pending.
- MyOS: `codex/legal-detached-retarget-myos`, commit `2522fa3`;
  restores the original direct C attachment test, not a runtime workaround.
- Cyclic-join reproduction work remains separate. Diagnostic scheduling
  experiments are **not** release candidates or approved changes to join fences.

No final full-library or MyOS acceptance is claimed for these follow-ups yet.

The earlier `5a103afb` clean build passed **2,799/2,799 root tests**, but its
conformance module finished **181/182**, failing the stale reviewed-fixture
identity assertion; the overall clean build failed. The exact `3491c515`
incremental recovery passed 778 newly executed tests: 5 conformance, 570
Contracts, 173 Language core, 20 examples, 3 model, and 7 Java tests. These
counts are not combined into a claimed clean pass for the new source.
Evidence is retained under `legal-detached-retarget-evidence.fKnxrU/`:

- `final-language-5a103.56avBS/final-reports.tar.gz`, SHA-256
  `14847c3a8bec97c59968d89e1e22d71aa37d444144da21c15e6e7f74040d527c`;
- `incremental-language-3491.do6Ney/final-reports.tar.gz`, SHA-256
  `2fbad9a25bd9fe0ea208e0445adb7235bd7d359611b3966276756b0886b6d3a3`.

The new unsigned local exports in `development-export-3491.5EOFWA` bind exact
Language `3491c515`, unchanged BEX `ab72af14`, and Catalog `0b68744b`.
Their manifest SHA-256 values are respectively
`034aa28d1c4ef2bdc68b744cc50e86d260913513ec07daf0f43e8877f7d3a87b`,
`bb4eadd3c5ddd928184d39ca7f29e261bbaf1d7aaa61f6dd6f71a70beeaf5f6c`,
and `ce41dea8b17b62323599bfd706d7c3d33742c5bac27a9295b3af03b2330d3f13`.
All inspected POM Blue edges select that tuple. Coordination's final export,
closed four-repository audit and application qualification remain pending.

The focused Language archive is
`legal-detached-retarget-evidence.fKnxrU/language-focused-03.tar.gz`, SHA-256
`5edb19b42f5e970259be11ee89b15d053cdc6f2c99bc8a23eb1b4431c80d9b7e`.
The immutable SDK03 archive is
`legal-detached-retarget-evidence.fKnxrU/legal-retarget-sdk-03.tar.gz`, SHA-256
`5c87b670bcad5b7866c67367d78b5779d117b1811f390e7b744604159db799a4`.
Its 48 tests comprise 33 resolver controls and 15 SDK/seam cases across six
classes, with zero failures, errors or skipped cases. The focused invocation
omitted `verifyCyclicTopologyIdentityEvidence`; this is not a full inventory
or library qualification claim.
Existing fixture regeneration preserves all business outcomes and gas quantities;
identity rebinding alone is not a test of the new legal attachment. The new Java
regressions supply that behavioral coverage. The cyclic diagnostic has reached
the required A0/B1/C1 computation, real all-owner publication and resident restart
under an explicitly arranged valid schedule, with unchanged publication checks
and exact fresh-reference result/gas equality. The latest `unified-cycle-05`
gate is **4/5**: the terminal/nonterminal three-node and four-node chain
automatic controls pass, as does the complete fourteen-input SDK ring with
restart (396.525 seconds, reconnect A4/B2/C3 then final A4/B2/C4). The diamond
remains blocked at exact cross-peer owner fences despite B1/D1/C2 reactions.
General multi-interior completion and the original packaged MyOS HTTP/restart
owner remain pending; these focused results do not replace full acceptance.
The archive `legal-detached-retarget-evidence.fKnxrU/unified-cycle-05.tar.gz`
has SHA-256 `c127cc4789e07859fc4423c1e9d2cba9691d11b0f425665e70cdd5d09ae8302a`.

`A → B` below means that A embeds B. Each row identifies an observed problem,
its correction, and a small example; the linked notes retain the detailed
reproduction and the original focused evidence. Those historical receipts do
not establish a pass for this newly assembled candidate.

## What changes, and why

| Area | Reproduction / previous result | Correction and boundary |
| --- | --- | --- |
| [Transport-only selection](rooted-transport-selection.md) | Remove an operation and then broadcast a request for it. Selection audit returned `NONE` while drain could consume the request, causing `PROCESSING_SELECTION_MISMATCH`. | Use the same completion-eligibility predicate for audit and execution. The request can terminate as `NO_MATCH` without a document epoch or PROCESS gas; blocked PROCESS work is not skipped. |
| [Static admission cutoff](rooted-static-admission-cutoff.md) | An explicitly `FROM_NOW` static admission freezes already managed B1 as the end of its catch-up interval, but later source preparation selects B0 as that end. | Retain the authenticated terminal cutoff, not replace the initial source position: initialization, successors and required reactions still run. This internal-command MyOS reproduction is distinct from ordinary public start's `importFullHistory`; no blanket strict-before-to-inclusive Timeline change. |
| [Bounded selection across calls](rooted-sliced-selection.md) | A imports B1 while B2 and independent C work remain. Repeated small drains could repeatedly select a failing owner or disagree with audit. | Retain the scheduling state across calls and permit independent owners to progress. Failed historical work remains blocking for its own root; observation is read-only and physical failure does not consume work. |
| [Source-prerequisite observation](rooted-source-prerequisite-observation.md) | A requests B's earlier history; another worker completes B before A inspects its descriptor. No remaining source actions alone cannot distinguish completion from a stale request. | Add `AdvancedCoordination.observeSourceHistoryPrerequisite(...)` and `SourceHistoryPrerequisiteObservation`: `PENDING` with an exact refreshed descriptor, `SATISFIED`, or `STALE`. Validate the original requesting authority and cutoff. This query neither publishes B nor authorizes stale execution. |
| [Inactive retarget — superseded](rooted-inactive-retarget-evidence.md) | A detaches B, then installs C at the same path. The old spec and our previous tests required rejection; the confirmed intended result is successful C attachment. | Replace the prohibition and rejection-only solution with a legal processor-owned new binding/history transition. Preserve original-input and exact-source authentication. Reassess Language/Coordination special cases and the MyOS workaround; old rejection passes do not qualify the corrected result. |
| [Canonical BEX document values](rooted-canonical-document-view.md) | During A's import of B1, a workflow copies a saved exact C0 from `$document` into a child slot. An expanded representation supplied the wrong exact identity and triggered an unsatisfiable content demand. | Read canonical values from the current working document while keeping resolved lookup separate. Do not substitute a stale pre-step document. No BEX source change. |
| [Retained selector scope](rooted-retained-selector-scope.md) | A → B; B attaches C at `/peers/c` using a LIVE attachment selection. A later imports B's retained result and incorrectly reapplies B's prospective selector in another scope. | Do not reapply the prospective selector to local retained historical work. Invalid LIVE selections are still rejected. |
| [One selected prerequisite view](rooted-selected-source-prerequisite-view.md) | A retains X → B while X's independent head has already detached B. Discovering prerequisites for B → A could mix those two X views and miss A's required earlier input. | Walk one frozen selected source view throughout discovery, without jumping to independently newer heads. |
| [Terminal cyclic joins](rooted-terminal-join-eligibility.md) | A → B, then B → A. Unrelated scheduling of A's later LIVE work between B's historical import and terminal join could invalidate the exact same-epoch representation needed to finish. | Fence the exact prospective cycle/join dependency at its barrier; preserve earlier prerequisites and independent unrelated work. Keep exact publication/CAS checks. The new candidate index is an in-memory derived selection index, not reverse-parent closure expansion or a durable store. |
| [Frozen retained source wire](rooted-retained-source-wire.md) | A reciprocal attachment receives a receipt and frozen source with the same BlueId, but normalizing one operand differently makes historical application reject at the imported-target guard. | Reuse the authenticated frozen wire only after the complete receipt and exact source/successor identity checks match. Older receipts retain their own body. Keep the successful 463-gas case and 462-gas rollback control. |
| [Exact provider admission identity](rooted-exact-provider-source-admission.md) | A provider returns an already authenticated exact authored X. Compiling it again as a new `Source` changes its identity during admission. | Compile that exact authored value without changing its admitted identity. Normal `Source` construction and different-lineage checks are unchanged. |
| [FULL_HISTORY selected endpoint](rooted-full-history-admission-frontier.md) | B reaches E3; A starts with B's authored value or exact E1. The selected endpoint is E3, but later preparation chooses E0 using the FULL_HISTORY replay-beginning marker. The exact terminal guard rejects this mismatch. | Retain the source publication authenticated by the successful admission. Preserve the starting position, E0/ordered successor reactions and frozen E3 endpoint; later E4 remains LIVE. Required implementation-conformance correction; no new admission policy or weakened guard. |
| [Borrowed-cycle activation position](rooted-borrowed-cycle-readiness.md) | Agreement observes Order attaching Payment0. If Order first publishes its own Order/Payment cycle, the later Agreement calculation classifies Payment0 against the physical Payment1 head and creates an impossible pending interval 0-to-0; Agreement stops at committed 2 / READY 1. | Candidate correction: classify only a new path against its authenticated numbered logical activation position. Existing reservations/cursors, genuinely older selections and distinct same-epoch representations stay historical. No READY override, source-head publication or gas policy change. All six controls, including negatives/gas boundaries, now pass in the combined SDK gate; original HTTP qualification remains required. |
| [Terminal-successor planning context](rooted-ring-chord-successor-reproduction.md) | A → B → C → A; A adds another saved-authored C occurrence. Import reaches C7, but the planner omits its required same-epoch representation successor, which execution correctly rejects. | Use the same exact staged/committed consumer view in planning and capture. Bind the staged view to the actual result, original boundary, derived owners and receipt-backed head epochs. Preserve the exact terminal target, proof checks and CAS guards. Both SDK methods and selected adjacent/restart controls pass; original MyOS qualification remains required. |
| [Exact-input eligibility memo](rooted-eligibility-cache.md) | The 54 negative BEX operations terminate correctly, but fresh MyOS replay exceeds the existing 120-second restart deadline. CPU samples show repeated delivery classification and body resolution. | Physical optimization: reuse only a complete successful comparison mask for identical exact representations and ordered deliveries. Failures remain uncached; canonical selection, PROCESS, logical gas and publication checks remain unchanged. The later combined host/library candidate passes all 54 operations and restart under the unchanged deadline; this is not an isolated memo-only speedup measurement or a mandatory semantic repair. |
| [Managed drain gas reporting](rooted-managed-drain-gas-reporting.md) | A registered managed application exposes correct positive gas in its exact typed result, but aggregate SDK drain gas omits that execution; MyOS command summaries inherit the incorrect total. | Correct the SDK-owned this-call summary from actual completed, non-replayed attempts in its three exclusive execution lanes; do not add a MyOS-only workaround or charge result/receipt projections twice. Retained gas, trace, receipts, tariff and limits are unchanged. Retrieving an already-published result adds zero new gas; fresh PROCESS during MyOS reconstruction still counts fully. Structural counters/order/opened-document metrics keep their existing scope. The isolated four-test owner and adjacent eight mapping controls pass; full gates and runtime port remain pending. |
| [Owned-revision drain budget](rooted-managed-drain-gas-reporting.md#separate-owned-revision-count-correction) | Actual three-node joint results retain 3 owned PROCESS transition receipts, and the chain retains 4, while the managed drain branch reports 1. This undercounts the existing between-invocation transition budget. | Reuse the external/root-local path's `RootedResultScope.processTransitionCount`, with the same published/non-replayed guard. Count actual owned transition receipts, including same-epoch representation changes when they have such receipts, not owners, applications or gas. A host-only correction cannot repair the engine's own budget decision. The three exact regressions change from red in unified-cycle-04 to pass in unified-cycle-05, with original business/gas/trace oracles unchanged. The full05 batch is 4/5 because the separate diamond join remains blocked. |
| [Terminal causal peer acquisition](rooted-terminal-peer-acquisition.md) | After required local work, B23 still carries immutable D5 while independently completed D23 carries B5; A17/C21 agree. Exact owner/CAS checks correctly reject the stale joint view. Earlier original-stage peer selection also produced different complete failure traces at the same 835-gas limit. | Keep original LIVE selection historical. At a fresh registered terminal, authenticate same-cause peer prefixes and select immutable witness primaries through the new Language factory, preserving calculating owners and old full source proofs. Do not replace a retry input or rerun/charge completed peer work. Unsupported inventory changes remain blocked. The eight diamond/dormant controls and MyOS's original ring/restart now pass; full gates remain required. |

## Necessity review for the post-d038 additions

Every library correction must identify the actual failure, existing normative
rule, why a host-only repair would not suffice, rejected alternatives, and
positive/negative regression evidence. A test becoming green is not by itself
authority to change processing semantics.

The 11 September independent traceability audit covers all 15 included
Coordination groups and both Language runtime groups. Each maps to a concrete
reproduction, library-owned boundary, stated impact and named controls.
The eligibility memo remains optional performance work, not a required semantic
repair. No persistence or witness-routing change is hidden in this fixes-only
range. The provider test-owner name and stale borrowed-cycle status found in
that audit are corrected. External report:
`rooted-baseline-release-review.VamIZv/registry-completeness-cf359-34e9.md`,
SHA-256 `5ade51082666668c9b18c4423a087bba56a293f32834cc3fbf9107584318d424`.
This is a necessity/traceability review, not substitute full-suite acceptance.
Its inactive-retarget conclusion used the then-selected specification. The
12 September confirmed correction withdraws that behavioral justification;
the affected implementation must now be reworked and requalified.

- **FULL_HISTORY is a library defect:** RCP-SCOPE-06 and RCP-CAUSE-01/02 require
  the selected logical position and endpoint to survive later physical work.
  MyOS cannot repair the private SDK admission/source-view association by
  polling differently. Switching attachment mode, substituting current E3 or
  suppressing its events would change the requested input; removing the exact
  terminal check would conceal the mismatch. The patch changes two private
  files and preserves exact publication/prefix validation. Six new SDK cases
  pass, including independent fresh materialized-result/gas comparisons. The
  identical test on unmodified d038 fails four cases and passes both unaffected
  controls; the original HTTP owner on new artifacts remains required.
- **The memo is conditional performance work:** RCP-ORDER-06 and RCP-CAUSE-03
  constrain cache/cold equivalence but do not mandate this particular cache.
  The comparison is internal to Coordination; a host cannot supply its cached
  result through an existing API. A different replay/index design remains an
  alternative. Eighteen focused controls pass, including direct reuse and
  cold-result equality. The original 54-operation restart subsequently passes
  on sealed ef3965/4fdec artifacts with unchanged inputs, gas and deadline;
  host code also changed, so this does not isolate the memo's speedup. The
  final rebound tuple still needs complete acceptance. No claim of semantic
  necessity, full heap bounds or scaling is made from these results. The extra
  disposable cache can retain an estimated 64 MiB;
  journal scans and temporary key allocation remain.

- **Borrowed-cycle classification is a library defect:** RCP-HISTORY-01 and
  RCP-CAUSE-01 require the original logical activation anchor, regardless of
  which source worker ran first. The exact public input reproduces the stall
  through the SDK without MyOS. A host cannot repair this private occurrence
  classification by changing queue order without leaving behavior dependent
  on scheduling. The two-file candidate (+93 lines) proves exact numbered
  publication membership before reclassification; it does not skip a history
  application, replace a borrowed primary or publish an independent source.
  READY normalization, latest-head substitution and blanket equal-epoch cursor
  clearing are rejected. Observer-first/source-first full-result parity and
  the second-path variant pass; all 15 selected adjacent recovery/representation/
  join controls pass. The two initial harness mistakes were corrected: the
  genuine older-position negative and real adapter G-1/G/G+1 publication checks
  now pass, with independent source isolation, exact cold-reference gas and
  retained-result replay. The expanded six-case run is 5/6: its additional
  same-epoch fixture instead produces different numbered epochs and therefore
  does not establish the intended negative. The invalid same-epoch setup
  was replaced with a real checkpoint-only R → P → S calculation and
  passes separately in run 04. All six now pass together in the combined 44-case
  gate; the original HTTP owner remains required. No public API, admission policy, ownership or gas rule
  changes.

The separate ring/chord terminal-successor failure is now reproduced on unchanged
d038 by `RootedRingChordSuccessorReproductionTest`, without HTTP or SQL. All three
authored identities and all five original entry identities/timestamps match.
For the original A-imports-C7 work `sha256:a779025e685336968a7d73387e33bfc0685636e1f6132c5f158519d8a931cd7e`,
planning omits the terminal successor while execution's authenticated captured
root requires it. The unchanged guard rejects the mismatch. RCP-HISTORY-01/04
and RCP-CAUSE-01/02 require exact positional evidence in both stages; MyOS cannot
add the missing private source context to the library-issued work identity.

The four-file candidate (+70 net lines) shares the existing source-view selection
with the planner, including its in-transaction staged result. That proposal is
not declared committed: only the exact producer result, original boundary,
complete derived-owner set and receipt-backed epochs can contribute its single
prospective prefix step. Execution still requires actual durable membership.
Physical latest-head fallback, omitted-tail acceptance and unbounded endpoint
extension are rejected alternatives. The original SDK reproduction now passes,
with planner/capture successor identity agreement and source-map/CAS negatives.
Both methods, their explicit all-three-READY/restart controls and selected adjacent
regressions now pass in the combined 44-case gate. Full MyOS qualification remains
required. No public
API, protocol, publication ownership or logical-gas change is introduced. The
red and first green source/XML archives are `library-ring-chord-negative-02.tar.gz`
and `library-ring-chord-candidate-01.tar.gz` in the external focused-evidence set.

## Dependency and API impact

### Additional Language correction: imported events with deferred activation

**Status: causal component red/green and 62 adjacent tests pass; full SDK/MyOS
qualification pending.**
In the ring case, A imports C8 with a required later representation tail. C8's
event changes A. Reencoding C's active reference to A changes C's exact BlueId
but performs no C-local work. Language incorrectly numbers C9 because its
existing finalizer-only imported-event exception requires completed activation,
which the tail deliberately defers.

The isolated Language candidate changes one private predicate: retain the
existing activated case, and additionally accept a reconciled numbered
`ManagedRevisionCause` with an authenticated `successorRepresentationCause`.
Every existing exact-event, import-depth, non-self target, local-change and
complete finalizer-delta check remains. §7.5a.1 preserves ordinary numbered
receipt reactions during deferred activation; the maintained same-epoch
component-rebind test establishes the existing imported-event exception.
Contracts §5.7.1 does not separately spell out that maintained imported-event
exception. The justification combines this existing conformance precedent with
§7.5a.1; it is not a claim that the prose explicitly enumerates the new predicate.
There is no new admission, activation, ownership or gas policy. The correction
does intentionally remove the spurious numbered epoch; source runtime identity
must be rebound and the new exact artifact set requalified.

Why the library: MyOS cannot repair a misclassified Contracts epoch by changing
scheduling or renumbering an identity-bound result without invalidating its
transition/companion identities. Broadly exempting every reconciled history step
would also change older nonterminal imports; removing terminal activation guards
or introducing cursor workarounds would address the later symptom. These
alternatives are rejected. Genuine C-local mutation remains epoch-advancing.

The same four component methods run on unchanged 2ce production give 3/4;
only the carrier's finalizer-only case fails (expected source epoch 2, actual3).
With this predicate correction and byte-identical tests, all four pass (2.850
JUnit seconds). The paired ordinary import and actual source-local mutation
controls pass before and after. Authoritative Language detail is
`docs/rooted-imported-successor-source-epoch.md` on the isolated
`codex/rooted-imported-successor-source-epoch` branch.

In `rooted-language-release-evidence.IUktLY`, the complete red archive has SHA-256
`263bee169ea989300d13c864631d8c42e0f2936119780e80bb717cb92973f185`;
the complete green archive has SHA-256
`a9ca47bd82d51c94e8fac87c4e3e20e5ea6958c6a35a7a0fcbfc0e5649882be4`.
The production file SHA-256 is
`1d4192f994be44cc1b7d5de00bb28ecf1f4f95fe0ea989400f842fd987e3df55`.
These are source-local results, not a released library or proof that all fourteen
ring inputs now pass. The private +85-line Coordination accommodation stays out
of the candidate while the source repair is tested against unchanged ef3965.

The correction is committed on the next-derived isolated Language branch as
`535230e7b6ab4ffcf95f79a795d373504e9da755`. The subsequent complete two-owner gate
passes **62/62** (46 FullLifecycleAdmission, 16 ManagedCheckpointSettlementOwnership),
zero failures/errors/skips. It includes an older/no-carrier import whose
finalizer-only source change must still advance and measured G−1 failure after
receipt/event work, with literal rollback and deterministic retry. Complete
archive SHA-256:
`dbd2fcbdde0b8fdab8be05b0d1d32f268118f5a8466f9ff101565381a84b64d1`.
The runtime commit is followed by supported source-identity rebinding. Actual
regeneration changes only the containing release manifest among 383 package
files; strict cumulative classification passes with one changed file and zero
unexpected changes. All six exact-pair/mutation controls pass. New Contracts
release: `sha256:b36546b546c706aad52120afae93dec38211e66e0be596ff0946e54dfe4884ab`.
Independent review verified all 741 runtime hashes against the exact source and
all 382 unchanged fixture/package files. The old C-EVO fallback warnings are
identical on unchanged 806536 and 2ce inputs, not new fixture drift; no generic
classifier policy was broadened. The final clean development artifact set is
now exported as recorded below; complete acceptance remains pending.
BEX and Catalog need no code correction, but their development exports must bind
the new Language artifact explicitly rather than silently reuse old 2ce POMs.

The [final Language identity binding](rooted-final-language-binding.md) records
the matching SDK profile, manifest and input-metadata change. It does not
replace semantic golden outputs or weaken the dependency authentication guard.

The existing Language branch contains two changes built for the old
inactive-retarget rejection: preserving/classifying the reserved occurrence and
resolving its selected historical value. Their rejection-only purpose is now
superseded; retain or adapt only the parts needed by a legal C attachment and
independent authentication invariants. A later head's hash still does not prove
an earlier value. The separate Language PR needs the corresponding correction
before it can be presented as the final candidate.

There are no BEX or Repository source corrections in this set. Local
development exports of those unchanged sources bind the exact Language
candidate; this is not a requirement to republish unchanged packages when
Coordination explicitly owns the final published Language dependency graph.

The observation method/value is an **additive public API change**. The bounded
cross-call scheduling and join eligibility corrections also affect selection
behavior; this package must not be described as documentation-only or solely
private refactoring. It does not choose new attachment, birth, history,
publication, or logical-gas policies.

## Engineering guard update

The new source-shape limits exactly match the measured candidate:

| Guard | Previous | Candidate | Reason |
| --- | ---: | ---: | --- |
| Production source files | 245 | 251 | Original helpers/observation API value plus the private eligibility memo. |
| Production lines | 72,540 | 73,475 | Original fixes +617; memo +150; FULL_HISTORY endpoint +5; logical-anchor classification +93; terminal context +70. |
| Public API/SDK source types | 86 | 87 | `SourceHistoryPrerequisiteObservation`. |

The per-retained-source file limit remains 1,166 lines. These are repository
size guards, **not** the managed-document, gas, or other protocol limits. Test
Given/When/Then comments are aligned with the current branch's architecture
gate; existing assertions and semantic fixtures remain mandatory.

## Verification and landing

### Successor tuple: former C8 rejection removed; reconnect still exceeds time budget

The sealed Language `34e9aa2f` / Coordination `cf35914e` / MyOS `c27ef0c`
tuple passes all four immutable-export checks and the independent 72-file,
transitive runtime-hash audit. BEX and Catalog code remain unchanged. Language
package regeneration preserves all fixture results and gas oracles; 480/480
generated conformance cases pass. None of this replaces full acceptance.

The full SDK attempt reaches and publishes C8 at source epoch 8, without C9,
then fails in a new diagnostic assertion that confused the enclosing managed
chain receipt with its inner Contracts transition receipt. That test-only
correction is isolated at `9bda552`; it retains independent validation of both
receipt identities and all four cursor fields. No runtime fix is justified by
that assertion failure.

The corrected diagnostic runs in `sdk-full-ring-03` with production bytes
unchanged from cf359. The complete C8 proof now passes, including its exact
source epoch, receipt, imported event and gas checks. Inputs 1–12 pass. Reconnect
input13 consumes 85 selected turns, reaches selection `NONE` and all three roots
are READY with the intended active topology. The next assertion fails: expected
observed counts `[A4, B2, C3]`, actual `[A4, B1, C3]` (340.004 JUnit seconds).
This is **not** an exhausted turn budget or evidence of full acceptance. The
original Python scenario also expects the additional B reaction. Whether the
library misses a required rooted reaction or the oracle confuses a local view
with independent publication must be established before changing either. No
semantic correction or expected-value replacement is authorized by this result
alone. Input14 and restart remain unqualified.

The test-only `sdk-full-ring-04-forensics` rerun retains that failure (338.557
JUnit seconds) but checks C's exact newly emitted token before the count oracle.
Those token/receipt/source assertions pass. At reconnect selection 7, C imports
A4→A5 and emits the token addressed to B. C is the sole entry/publication owner;
B is an immutable historical witness at both topology boundaries. The actual
Contracts trace contains C's containing-reference update and embedded-event
reaction, not a B delivery. The later terminal join owns the live A/B/C cycle
but emits no new token. This does **not** justify delivering into an immutable
witness or restoring reverse-parent publication. The remaining question is
whether B's own required calculation was preserved and selected; the historical
Python count expectation is not replaced without resolving that question.

The separate minimal acyclic B→C→A reduction passes on unchanged cf359 code in
both source-first and B-first schedules (2/2, 20.036 JUnit seconds). C imports
A's earlier token and emits one new token to B; B's own calculation increments
once without publishing independent C/A. Each retained result matches a fresh
materialized execution in full output/event/receipt/checkpoint/companion and
gas/trace identity; restart preserves histories without duplicate reactions.
This establishes the basic forwarding/isolation path, not the ring's historical
B-witness/primary-B overlap or the correctness of every selected obligation.

A separate four-input reduction adds A→B before A's token, so C's later
attachment closes B→C→A→B. Its first comparison exposed a **test-reference gap**:
the older materialized helper discarded the rooted binding, changing owner and
witness roles. A new test-only fresh-runtime helper preserves the exact input
and rejects omitted retry context; no library runtime was changed. With that
reference, C-first completes with `[A0,B1,C1]` and exact result/gas parity.
B-first reaches publication rejection (`captured C6`, independently current
`C0`, graph generations 3 versus 1), before the final result assertions.
`witness-forwarding-02` is therefore 1/2 passing, 30.247 JUnit seconds. This is
under investigation, not an approved relaxation of the publication fence.
Unlike the full ring, this reduction imports A's token at its terminal receipt;
it does not settle the full ring's earlier, nonterminal-event obligation.

The [five-input discriminator](rooted-cyclic-join-live-obligation.md) now adds
one actual eventless A250 step before attachment300. It reproduces the missing
B reaction in 18.730 seconds: `[A0,B0,C1]`, versus `[A0,B1,C1]` without A250.
Exact C token, READY, topology and fresh-rooted result/gas comparisons pass.
B's actual pre-join selection is LIVE300; after the co-owned join it selects
no work without having run its own LIVE300 calculation. This is a concrete
join/progress concern, not proof that immutable-witness routing is wrong.
`witness-forwarding-03` totals 3 methods, 1 pass / 2 failures, 47.118 seconds;
its B-first conflict leaves heads/history unchanged and C's own work blocked.
The user has since confirmed this as a bug: the required B reaction must run;
the remaining task is its correct implementation, not a behavioral decision.
The public-only unmodified-RC9 comparison now reaches
the same outcome with published Language RC25/BEX RC6/Catalog RC22, exact same
inputs and no candidate runtime edits: terminal passes, nonterminal fails at
`[0,0,1]`, 2 methods / 1 failure, 19.433 JUnit seconds. The behavior therefore
predates these candidate corrections; the repair is not yet implemented.

The exclusion-free Language `34e9aa2f` clean build passes in 18m54s, with
3,750 JUnit tests across 415 suites and no failures/errors/skips. Its archived
clean marker binds `[clean, build]`, no exclusions, the exact commit/epoch and
the independently recomputed 2,998-file source identity
`sha256:a5ce138ec812d5a85266cc6247dcfe1caa4a9f8d0cb282b1b9495e0b204f2133`.
The subsequent maintained quality/RC invocation stopped after 7m05s because
the launcher omitted `releaseVersion` and selected the rejected default
`3.1.0-rc.25-SNAPSHOT`. This is a launch configuration failure, not a runtime
correction or authority to relax the release guard. Its complete failed report
archive is preserved. The maintained clean marker is source/epoch-bound and
does not bind project version; a quality-only retry with the exact development
version subsequently passes `finalQualityVerify rcVerify` in 2m45s, with
218 tasks (142 executed, 76 up-to-date), no exclusions and unchanged source.
Final quality is eligible with zero blockers; the new immutable development
repository and independent artifact smoke validate the exact34e9 coordinates.
The artifact manifest `5dfc0e43…` equals the previously sealed export; no tuple
change is needed. Archive `quality-dev-34e9.yJHMEy/final-reports.tar.gz` SHA-256
`6113c3ff54effdf60205139776ed68f8a6d66bd98dca993aa5101f8f67cf4865`.
This qualifies the local DEVELOPMENT build, not a remote release or complete
MyOS acceptance. The earlier candidate's clean build is not reused.
The final aggregate reports 4,144 executions: the clean 3,750 plus 394 executions
across five dedicated regression lanes. These are not 4,144 distinct freshly
executed clean-build tests. The separate fixture package remains 480/480
(185 Language, 295 Contracts). Independent audit rehashes all 2,998 source
entries, artifact checksums and the complete archived receipt/report selection.
Final independent audit:
`rooted-successor-language-evidence.GlIOBx/FINAL-LANGUAGE-34E9-INDEPENDENT-PASS.md`,
SHA-256 `1b4bedd4f39eea18a16dd309723178ef7264d6b9d7570a7984d667536b9c2e89`.

The original, unchanged MyOS HTTP owner independently passes the previously
failing duplicate attachment and detaches, but fails at operation13 (reconnect)
on its existing 600-second readiness deadline. The batch is 1/1 failed,
1027.265 seconds (1022.091 JUnit seconds); source/dependency receipts are exact
and unchanged. C was still advancing through retained history, last observed
58/87 positions. Operation14 and restart were not reached. This is a concrete
performance acceptance failure, not permission to increase deadlines or change
the import's observable result. Later H2 closed-channel errors occur during
shutdown and are not established as its cause.

A read-only thread sample and source inspection identify repeated complete
representation-proof reconstruction in `ManagedRepresentationHistory.at()`:
`verifyCause` builds the same chain twice and `verifySuccessor` three times,
including full cyclic input validation. Frozen target clipping occurs after
full chain construction; the sample does not establish target chasing. A
separate candidate may reuse already-verified evidence **within one call**, while
preserving every durable membership, exact target/head, ownership and supplied
proof comparison. It is not yet accepted or measured. No cross-call cache,
new API, publication/gas policy or relaxed guard is part of that investigation.

The isolated in-call refactor now passes all 13 focused controls (3 terminal-tail,
2 ring/chord, 6 checkpoint safety, 2 historical representation), with no failed
or skipped methods. The source-shape and test-architecture guards pass. New
controls corrupt detached supplied proof bytes while keeping the claimed work
identity, and require the same original authority checks to reject them. The
production delta is one private file, +13 measured lines; it is still outside
the central runtime and **not yet shown to remove the HTTP timeout**. The source
line-count guard adjustment is a repository size check, not a protocol/gas limit.
The identical complete terminal-tail owner passes 3/3 on unchanged cf359 code
as well. This is paired validation evidence, not proof of an application speedup.
Independent review additionally confirms the current safety assumptions:
engine-serialized calls, privately owned store and immutable retained evidence.
The removed reads were not atomic freshness barriers. This is not authority
for future cross-call reuse or for a host that bypasses those boundaries.

External evidence under `rooted-successor-language-evidence.GlIOBx`:

| Archive | SHA-256 |
| --- | --- |
| `successor-immutable-bundle.tar.gz` | `aa9c48d7e373467456c0a17f7a0972632b77ecc405416313cd13a3233c8217fc` |
| `sdk-full-ring-02-evidence.tar.gz` | `155b1e1ce3d2d50ba29ac433fbcdd510f5d56ec8da497cdc1667cd8ad1e22954` |
| `sdk-full-ring-03-evidence.tar.gz` | `f3c3df1f307e0df671021a9d93bb0338624bc7fd0661122c822342e8469f7872` |
| `sdk-full-ring-04-forensics-evidence.tar.gz` | `51553a8ee262d3d65a9796dda0be9ee598500faae36c6fea974810958e8bbf94` |
| `myos-full-ring-01-evidence.tar.gz` | `0a6f18db805397596aee2742512b9c1da9d4dced4eadb8c5fd19392fb1f1d8f1` |
| `proof-reuse-focused-01-evidence.tar.gz` | `4cad3da5fb01252947d74c5e1924b6ea6886e09510c9a4d1ff1bd38e9b201495` |
| `proof-reuse-base-01-evidence.tar.gz` | `ec73814f0edd8bafb013d79cccac72835bd192aefccf0975124d667046ee9d48` |
| `import-forwarding-01-evidence.tar.gz` | `0e122ec6a190776a06ec2e45b92d273f6dd1bbef5a276e810398e9ff839fc4b5` |
| `witness-forwarding-01-evidence.tar.gz` | `24a40da4a1b15ec10229b75d395616d4f90fd80d7fc3b0bed7665acaebede409` |
| `witness-forwarding-02-evidence.tar.gz` | `92c24e7651b102343e4637306e9448515692cc63c3e86d0f70038c8199c9c472` |
| `witness-forwarding-03-evidence.tar.gz` | `5121a93c8179bcd8a617b774b57a9f655f02bdfac326b19c35d7e06103766a5c` |
| `upstream-forwarding-01-build-evidence.tar.gz` (consumer compile error, no tests) | `27626ce243d760876ac2439f7b8abaceca86252798348b95a7f4832eea6a3680` |
| `upstream-forwarding-02-evidence.tar.gz` (unchanged RC9, terminal pass / nonterminal count failure) | `a9658fb0680a2d806871a2fff36b33b1d0d78efdc643ca14cfe6791a44e1118b` |

The preceding application failures and focused passes below retain their own
artifact bindings. No library is ready to merge.

### Current qualification and open investigation

The runtime candidate in this worktree is `ef3965bd87b39f3dcb047aaaf0e6a5db3a285137`.
The sealed MyOS `4fdec4f` / Coordination `ef3965b` application batches now provide
the following later evidence; references above to original HTTP qualification
being pending describe the preceding SDK stage:

- FULL_HISTORY: all three original public HTTP attachment parameters pass.
- Borrowed-cycle position: the original full Commerce group14 plus restart passes.
- Eligibility memo: all 54 BEX programs and fresh replay pass with the unchanged
  deadline, exact input identities and total gas 36,715. Replay of 56 commands
  takes 73.311 seconds. Host code also changed, so this is not a memo-only
  speedup ratio. Direct memo controls separately prove reuse and cold equality.
- Ring/chord: the first five inputs pass, but the seventh input adding a second
  C occurrence still fails. The full fourteen-input owner is not qualified.

Across focused candidates, 18/19 originally failing invocations have later passes;
neither complete MyOS suite has passed on one final tuple. These are resident
baseline results, not external-state performance or E2E acceptance.

**F12 — investigate before adding another library correction.** The exact
seven original inputs reproduce the MyOS failure directly on unchanged ef3965
SDK code. During import of C8 into A49, the staged result contains numbered C9
and conflicts with the occurrence's frozen null-next representation cursor.
A private three-file Coordination accommodation (+85 lines) then completed a
25/26 selected test run: it passes the first guard and strict prefix/consumer
controls, but fails later when terminal C8 cannot activate against selected C9.
It is **not included in this candidate** and is not an accepted fix.

The existing Language activation equality guard is consistent with the
numbered-revision rule. Removing it, accepting a stranded inactive null-next
cursor, rewinding C9, or skipping a real C effect is not justified. An earlier
possible source defect must be tested: successor-bearing historical work
deliberately defers activation, while the existing imported-event finalizer-only
source-epoch exception requires completed activation. The maintained
`FullLifecycleAdmissionTest.managedRevisionEventAcceptsAuthenticatedSameEpochComponentRebind`
covers that exception without a successor. Exact C9 local/finalizer evidence
and a paired successor test must establish whether that omission applies.
The subsequent diagnostic04 run confirms the relevant shape: the C8 import has
two work occurrences, a containing-reference update and its exact imported
event, both targeting A. C has zero local work and zero new source events;
its only changes at both finalization boundaries are `/peers/a/blueId`.
Nevertheless, the result numbers C as epoch 9. The later terminal guard then
observes precisely the predicted inactive C8/current C9 mismatch. This supports
the source-classification diagnosis, not a new source history policy.
The paired Language regression and causal red/green test subsequently qualified
the private correction, with the 62-method adjacent result recorded above.
The full fourteen-input SDK test is now included without the experimental
Coordination accommodation; it must pass against the rebound Language before
this is treated as a complete MyOS repair.

Evidence in `rooted-focused-followup-evidence.jtpYIX`:

| Archive | SHA-256 |
| --- | --- |
| `myos-library-packaged-01.tar.gz` (2/3, ring still red) | `53248afb9339d4055ac226425956bd1bd43c90932923e395bd1c633cdb90a120` |
| `myos-library-http-01.tar.gz` (3/3) | `fee5905e8d296c921b65173f79de5d3e4982cdba55fb9f43e4b49d5552bd68e5` |
| `library-duplicate-occurrence-red-01.tar.gz` (unchanged SDK red) | `72eb82528e3905a3b9d48821371e64cf481dad511510290a41a41ac760563da8` |
| `library-duplicate-occurrence-candidate-03-complete.tar.gz` (25/26) | `15358ba82e865c7980d5ec29f7263cf3347bf84045dad1c8b3137a706c2601fd` |
| `library-duplicate-occurrence-diagnostic-04-complete.tar.gz` (1/1 expected diagnostic failure) | `2d8fe81214a4421ee48723862871935e80b1d69bfcf64a269c4e1a60ad094800` |

Language `2ce3e66b` separately passes one clean build with 3,745 fresh JUnit
invocations and zero failures/errors/skips. Its subsequent quality/RC gate stops
on a stale generated empty-sentinel source-location audit; release qualification
is not green. Reviewing that generated report is separate from the suspected
runtime defect above. No library is ready to merge.

### Earlier SDK qualification and required final gates

Latest source-local gate: [44/44 combined history controls](rooted-history-combined-qualification.md),
zero failures/errors/skips, plus passing source-shape and test-architecture guards.
The archived bytes and unchanged exact upstream dependencies are recorded there.
The first per-owner runs below remain historical evidence, not failures of this
later combined gate. Full MyOS and clean library release gates are still required.

The original frozen MyOS `fe6f3ad` / Coordination `d0382bbc` / Language `2ce3e66b`
full runs completed red: product 1,123/1,136 and independent HTTP 56/62. Their
evidence is preserved; later focused passes do not erase these results.

Post-d038 focused evidence is source-local, not a sealed release receipt:
`focused-library-followups-02` passed all 18 memo controls and 23 adjacent
controls, while four new FULL_HISTORY reference-harness cases failed for
missing exact successor resources. After correcting only that harness,
`focused-library-followups-03` passed all six FULL_HISTORY cases; its separate
commerce fixture mismatch still made the combined run red. Run 04 then
reproduced the genuine commerce stall with all original input pins verified.
See each linked note for exact scope and archives. Neither library is ready
to merge on these partial results.

`focused-library-commerce-candidate-02` subsequently completed 18/20: all 15
existing adjacent controls and three new commerce controls passed. The two
new test-harness failures are retained in its source/result archive. That red
batch is not relabelled green by its passing subset.

`focused-library-commerce-candidate-03` completed 11/12: five commerce cases
including real gas-boundary publication, plus all six FULL_HISTORY cases, pass.
The extra same-epoch fixture fails its own precondition (local epoch 1 versus
source epoch 0), not the classifier assertion; it must be corrected. The archive
retains this failure. Cheap source-shape and test-architecture guards also pass;
they are not semantic or release qualification.

The source-local combined gate has passed. The next application gate will use
newly exported, clean, commit-bound immutable development artifacts. Prior focused and MyOS source
suite passes explain why these fixes were selected, but are not a substitute
for this candidate's complete gate. In particular, the published default
Language RC does not yet contain the retarget fix.

Before either library PR is merged, the complete resident MyOS baseline must
pass acceptance against one frozen, coherent set of immutable development
artifacts built from the exact proposed library sources. This includes the
full product acceptance aggregate and the independent public HTTP scenario
suite. Focused corrections and an interrupted run do not satisfy this gate;
the existing assertions and deadlines remain unchanged. GitHub publication
permissions do not prevent this local pre-merge verification.

Only after that gate and the required library checks pass does the landing
order become Language review/release, Coordination's exact published Language
binding and release checks, then MyOS's published-dependency qualification.
The later published-artifact check supplements, rather than replaces, the
pre-merge end-to-end evidence. Maintainer approval owns merging and release.
The persistence branch remains separate until this resident baseline is settled.
