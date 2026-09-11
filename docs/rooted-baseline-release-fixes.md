# Rooted resident-baseline corrections

This is the fixes-only prerequisite for merging MyOS Simple's rooted baseline.
It starts at Coordination `next` commit `c0a689de8d0ccd89de94bf6316618c7760a68e2a`.
It does **not** contain the external-state POC, PostgreSQL adapters, runtime
serialization, worker recovery, or a replacement processing model.

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
| [Inactive retarget](rooted-inactive-retarget-evidence.md) | A detaches B from a reserved slot, then tries to install C there. The attempt could lose its original input or repeatedly demand evidence instead of reaching Contracts' deterministic rejection. | Preserve the B reservation and frozen C selection; authenticate the historical value needed for the rejection. Legal B reactivation, active retargets, and fresh-path attachments remain supported. Requires the separate Language correction below. |
| [Canonical BEX document values](rooted-canonical-document-view.md) | During A's import of B1, a workflow copies a saved exact C0 from `$document` into a child slot. An expanded representation supplied the wrong exact identity and triggered an unsatisfiable content demand. | Read canonical values from the current working document while keeping resolved lookup separate. Do not substitute a stale pre-step document. No BEX source change. |
| [Retained selector scope](rooted-retained-selector-scope.md) | A → B; B attaches C at `/peers/c` using a LIVE attachment selection. A later imports B's retained result and incorrectly reapplies B's prospective selector in another scope. | Do not reapply the prospective selector to local retained historical work. Invalid LIVE selections are still rejected. |
| [One selected prerequisite view](rooted-selected-source-prerequisite-view.md) | A retains X → B while X's independent head has already detached B. Discovering prerequisites for B → A could mix those two X views and miss A's required earlier input. | Walk one frozen selected source view throughout discovery, without jumping to independently newer heads. |
| [Terminal cyclic joins](rooted-terminal-join-eligibility.md) | A → B, then B → A. Unrelated scheduling of A's later LIVE work between B's historical import and terminal join could invalidate the exact same-epoch representation needed to finish. | Fence the exact prospective cycle/join dependency at its barrier; preserve earlier prerequisites and independent unrelated work. Keep exact publication/CAS checks. The new candidate index is an in-memory derived selection index, not reverse-parent closure expansion or a durable store. |
| [Frozen retained source wire](rooted-retained-source-wire.md) | A reciprocal attachment receives a receipt and frozen source with the same BlueId, but normalizing one operand differently makes historical application reject at the imported-target guard. | Reuse the authenticated frozen wire only after the complete receipt and exact source/successor identity checks match. Older receipts retain their own body. Keep the successful 463-gas case and 462-gas rollback control. |
| [Exact provider admission identity](rooted-exact-provider-source-admission.md) | A provider returns an already authenticated exact authored X. Compiling it again as a new `Source` changes its identity during admission. | Compile that exact authored value without changing its admitted identity. Normal `Source` construction and different-lineage checks are unchanged. |
| [FULL_HISTORY selected endpoint](rooted-full-history-admission-frontier.md) | B reaches E3; A starts with B's authored value or exact E1. The selected endpoint is E3, but later preparation chooses E0 using the FULL_HISTORY replay-beginning marker. The exact terminal guard rejects this mismatch. | Retain the source publication authenticated by the successful admission. Preserve the starting position, E0/ordered successor reactions and frozen E3 endpoint; later E4 remains LIVE. Required implementation-conformance correction; no new admission policy or weakened guard. |
| [Borrowed-cycle activation position](rooted-borrowed-cycle-readiness.md) | Agreement observes Order attaching Payment0. If Order first publishes its own Order/Payment cycle, the later Agreement calculation classifies Payment0 against the physical Payment1 head and creates an impossible pending interval 0-to-0; Agreement stops at committed 2 / READY 1. | Candidate correction: classify only a new path against its authenticated numbered logical activation position. Existing reservations/cursors, genuinely older selections and distinct same-epoch representations stay historical. No READY override, source-head publication or gas policy change. All six controls, including negatives/gas boundaries, now pass in the combined SDK gate; original HTTP qualification remains required. |
| [Terminal-successor planning context](rooted-ring-chord-successor-reproduction.md) | A → B → C → A; A adds another saved-authored C occurrence. Import reaches C7, but the planner omits its required same-epoch representation successor, which execution correctly rejects. | Use the same exact staged/committed consumer view in planning and capture. Bind the staged view to the actual result, original boundary, derived owners and receipt-backed head epochs. Preserve the exact terminal target, proof checks and CAS guards. Both SDK methods and selected adjacent/restart controls pass; original MyOS qualification remains required. |
| [Exact-input eligibility memo](rooted-eligibility-cache.md) | The 54 negative BEX operations terminate correctly, but fresh MyOS replay exceeds the existing 120-second restart deadline. CPU samples show repeated delivery classification and body resolution. | Candidate physical optimization: reuse only a complete successful comparison mask for identical exact representations and ordered deliveries. Failures remain uncached; canonical selection, PROCESS, logical gas and publication checks remain unchanged. Its end-to-end benefit is still unverified, so this is not classified as a mandatory correctness repair. |

## Necessity review for the post-d038 additions

Every library correction must identify the actual failure, existing normative
rule, why a host-only repair would not suffice, rejected alternatives, and
positive/negative regression evidence. A test becoming green is not by itself
authority to change processing semantics.

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
  alternative. Eighteen focused controls pass, but keeping this optimization
  in the final candidate requires measuring the original restart on the new
  artifacts. No claim of necessity, full heap bounds or scaling is made from
  those unit tests. The extra disposable cache can retain an estimated 64 MiB;
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
Release metadata is deliberately still pending on this implementation commit;
only supported, reviewed source-identity rebinding may enter the final artifact.
BEX and Catalog need no code correction, but their development exports must bind
the new Language artifact explicitly rather than silently reuse old 2ce POMs.

The [final Language identity binding](rooted-final-language-binding.md) records
the matching SDK profile, manifest and input-metadata change. It does not
replace semantic golden outputs or weaken the dependency authentication guard.

Language needs two internal corrections for the inactive-retarget case: reserve
and classify the original occurrence correctly, and resolve an authenticated
historical selected value even when the frozen source head is later. A later
head's hash does not itself prove an earlier value; existing authenticated
resolution rules still apply. These changes live in the separate Language PR.

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
The paired Language regression and causal red/green test must still qualify
the repair. Run it against unchanged Coordination first; do not retain the
later accommodation if the source fix makes it unnecessary.

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
