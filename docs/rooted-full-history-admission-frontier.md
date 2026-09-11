# FULL_HISTORY retains its admission-selected source endpoint

## Problem and concrete example

The frozen MyOS HTTP case
`CatchUpTest.embeddedSourceHistoryDependsOnSelectedBlueId` advances a source
through E1, E2 and E3, then starts a static parent with FULL_HISTORY. Selecting
the authored source or E1 creates an interval ending at the source's selected
E3 publication. Selecting the current E3 needs no historical import.

The authored/E1 cases failed at the exact terminal-source guard:

> Numbered terminal source differs from its frozen source publication

The selected terminal source was E3, but the fallback source lookup returned E0
at `[-9007199254740991, contracts-full-history-admission, ...]`. That marker is
the beginning of FULL_HISTORY journal replay; it is not the endpoint of the
already selected source history. The exact guard correctly rejected E3 being
paired with an unrelated E0 publication. It must not be bypassed.

The frozen external witness is MyOS `fe6f3ad`, Coordination `d0382bbc` and the
final Language `2ce3e66` tuple. Its log is
`build/reports/http-scenarios/CatchUpTest/embeddedSourceHistoryDependsOnSelectedBlueId-44f2ef9b-e0a4-4493-89a7-f4dca6a16e46/host.log`,
lines 81–130 in the baseline merge-readiness worktree. This is actual HTTP
failure evidence, not a claim that the new SDK test has run.

## Minimal correction

Static publication already retains `RootedAdmissionSources` for FROM_NOW.
Retain the same exact source-publication records for FULL_HISTORY too, associated
with this admission's exact boundary marker. Each record is still checked
against the **complete successful admission input's** source epoch and BlueId
with `requirePublishedHead`, and against the actual retained source publication
prefix. The later history consumer uses those records only for the exact
matching admission boundary.

The existing `view.logicalBoundary <= frontier` check remains for bounded
admission. Only FULL_HISTORY omits that comparison: its synthetic beginning
marker cannot be compared to an already selected source publication as though
it were an upper bound. The endpoint is the immutable view authenticated by
the completed admission, not whatever source head exists when catch-up resumes.

No source body, receipt, representation position or current head is substituted.
`ManagedRepresentationHistory.terminalSuccessor`, membership checks, publication
fences and `DocumentSession.rootedViewBefore` are unchanged. No public API,
history descriptor, semantic identity formula, gas budget or specification is
changed. FROM_FRONTIER keeps its existing path; this patch does not claim to
qualify or redesign that mode.

The existing rules are RCP-SCOPE-06 (distinct selected source positions),
RCP-CAUSE-01/02 (freeze the join endpoint and preserve exact ordered positions),
and the FULL_HISTORY descriptor in the rooted specification. Extending an
interval to a later physical head, globally changing `<` to `<=`, or ignoring
the terminal epoch/BlueId mismatch would violate those rules. The private
retained map is admission evidence, not an eligibility cache or a global
source-progress freeze.

## Candidate regression controls

`RootedFullHistoryAdmissionFrontierTest` uses the actual SDK and current
`RootedSdkFixture`, without injected receipts or synthetic source positions:

- Authored reference and inline source import E0–E3, with per-step observed logs
  `[]`, `[1]`, `[1,2]`, `[1,2,3]`.
- An exact E1 reference imports E2/E3 with `[2]`, `[2,3]`.
- A current E3 reference has no historical application and keeps `[]`.
- If the source publishes E4 after parent admission but before catch-up and
  route/schedule reconstruction, the frozen interval still ends at E3. E4 is
  the subsequent LIVE step; its independent source history remains unchanged.
- A genuinely unchanged numbered epoch with two authenticated checkpoint
  representation changes retains exactly those two positions, even if a third
  is published before import/reconstruction. The third remains subsequent LIVE
  work, not an extension of the frozen historical target.

Each historical step captures its original input and calculates a fresh complete
materialized reference before publication. It compares total gas, ordered trace
identity, output closure, managed receipt set and public-event identity. Full
source receipt/body/event/entry histories remain unchanged. The tests compare
parent histories before and after `restartFromStores`; that control rebuilds
route/schedule state over retained stores and is **not** a fresh SDK/process
restoration claim. The real MyOS HTTP owner supplies that separate obligation.

Run the unchanged `RootedStaticAdmissionCutoffTest` alongside the new owner to
preserve FROM_NOW equality-frontier, later-source exclusion and same-epoch tail
controls. The existing terminal-tail and historical-reference-chain owners are
adjacent focused coverage; no whole-corpus rerun is required for initial review.

## Status

Latest: all six cases also pass in the source-stable
[44-case combined history gate](rooted-history-combined-qualification.md).
Earlier red/green receipts below retain their original scope and hashes.

All six SDK cases passed in `focused-library-followups-03` on the uncommitted
consolidated candidate. Every materialized reference receives only the selected
authenticated receipt's exact successor, request and emitted-event resources;
it does not resolve through the running SDK's latest state. The earlier four
missing-resource failures and one initial test API compilation error were
harness corrections, not red-before-green proof of the production defect.

The run's seven-case total was still red because the separate commerce fixture
did not yet match its original input. Its FULL_HISTORY XML/source archive is
`rooted-focused-followup-evidence.jtpYIX/library-focused-03.tar.gz`, SHA-256
`87b631e78ae41488fd5a625537fb0b0b2f6611a57134a2ef9d670f538dee0c65`.
The preceding adjacent controls, including the unchanged FROM_NOW owner,
passed in run 02. The two private production files add five net lines.

Direct old-code control now also ran: `focused-library-frontier-negative-01`
executes the identical six-case test file on unmodified `d0382bbc` production
code. Four cases fail (authored reference, authored inline, E1 import and the
two-position same-epoch target); both unaffected controls pass. The authored/E1
failures reach the original terminal-source mismatch. The test file SHA-256
is identical in both trees:
`7038bc0765afdf1a459501035138b5418eb38adb85c9661007446f65f6751877`.
The negative XML/source archive is
`rooted-focused-followup-evidence.jtpYIX/library-frontier-negative-01.tar.gz`,
SHA-256 `c6b09bd226c5172bd796cf765dcdb174704d68aa76b10cfd408ffc340f4a1652`.
This supplies SDK red/green evidence without changing any expectation.

Still required: rerun the original CatchUp HTTP cases using newly sealed
artifacts and complete the final library/MyOS gates. No commit, export, release
or full acceptance is claimed. Original frozen source and sealed artifacts
remain unchanged.
