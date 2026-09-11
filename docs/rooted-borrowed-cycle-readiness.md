# Borrowed cycle: classify a new occurrence at its logical activation boundary

## Problem and exact example

The unchanged MyOS commerce documents form `Agreement -> Order <-> Payment`.
Agreement is an independent observer: evaluating its forward view must not
publish independent Order or Payment heads.

The reduced original public prefix admits Order and Agreement, attaches Order
epoch 0 to Agreement, admits Payment with the same Order epoch-0 reference, and
submits Order's `attachPayment`. Processing Order first publishes its own
Order/Payment cycle. When Agreement processes that **same entry**, its logical
activation boundary still selects the original Payment epoch 0.

The uncorrected resolver instead classifies the supplied Payment epoch 0 as
historical relative to the physically newer Payment head. The read-expansion
adapter correctly retains Payment epoch 0 at the original logical boundary, but
creates a new pending occurrence at cursor 0 against that same terminal
epoch-0 anchor. The resulting local history has neither a representation
successor nor an epoch-1 source receipt before the boundary. It cannot invent a
0-to-0 history application. Agreement becomes committed at epoch 2 but READY
only through epoch 1, with global selection NONE, before and after scheduler
reconstruction.

The original red output is evidence of the defect, not the corrected oracle:

- Agreement lineage: `EVvLMNHBdoccndkoYszjykKcTG4iVVP5Tj36r7iDmoHh`.
- Original Order epoch 0: `2xaPPKNPahtSFatjULiiwEBRq2AMjjLsy7WBBds7GGLg`.
- Attach-Order entry: `8oYVKXfbWvqzEEoKWzT4XnrTQQLpj1ehTC3U1yPjhkb5`.
- Attach-Payment entry: `7a8jT8RAWLd7uYMKjDAW5RmYLnQnygF42B2scxN3nxWQ`.
- Frozen Payment epoch 0: `ANGwUqG4S9xui7RVuTvW7U3zdFWmx3g1DnMjP59rG6T`.
- Later physical Payment epoch 1: `AZfFBGRW4byFfcbYcTvMXRAh1j22KRo9KBuKvfCe2QAy#0`.
- Defective Agreement output: `5TcuuWZQgvi7DnPdFPRSN9QN2hPNPUpTs1A6s7W5QGVX`.

The [compact red receipt](rooted-borrowed-cycle-red-receipt.json) records the
one-test/one-failure XML and byte-identical preserved diagnostic source. Both
are archived outside the worktree at
`/Users/kamil/Documents/Projects/Blue/rooted-borrowed-cycle-evidence.mNeDA4`.
The original host observation also retained the later business operations;
this SDK correction does not claim that full HTTP scenario has passed.

## Normative boundary and minimal correction

The pinned rooted specification is unchanged. RCP-HISTORY-01 defines
current-at-activation relative to the logical activation boundary, not the
worker's latest head. RCP-CAUSE-01 freezes that anchor: processing a source
first cannot convert the same LIVE activation into a historical import.
RCP-SCOPE-04 and RCP-OWN-02/04 keep borrowed calculation separate from independent
publication authority.

`RootedAttachmentCapture.classifyAtBoundary` applies only to a **new path**
with no occurrence row in the frozen input. Before the adapter constructs the
prospective row and accumulates resolution evidence, it can replace the
physical-head historical classification with CURRENT_EXISTING only when:

1. The selected source view matches the actual retained pre-boundary publication
   by invocation, input/output-companion, complete snapshot/retained-snapshot
   identities, and logical boundary. This is not Java object identity.
2. Its exact initialized source epoch and BlueId match the selected value.
3. The numbered epoch receipt and complete transition receipt match the source,
   epoch, BlueId and the view's producer invocation, and the view binds the
   exact published head and retained publication prefix.

An actual later publication at the **same** external input fails the strict
pre-boundary position test. A same-epoch representation tail is not its numbered
anchor: matching the integer epoch, or even an endpoint BlueId, is insufficient.

A second new path can target a source already present in the frozen input.
That source is never substituted. Its exact wire body, epoch, initialized and
terminated flags, component generation, complete outgoing bindings, and
component lineage/state must match the authenticated numbered boundary anchor.
Public-root presentation is deliberately not compared: the same exact source
is owned in its independent publication and borrowed in the receiving root.

Existing rows—including inactive reservations, pending cursors and retarget
generations—remain untouched. Authored epoch -1 remains authored history.
Actual older selections and distinct local/representation states remain
historical. Normal Contracts activation, repartition, cycle finalization,
ownership derivation and gas accounting still execute; no host READY override
or fabricated application is introduced.

Rejected shortcuts: filter every equal-epoch pending row; mark READY on an empty
work list; use the latest physical head; clear a representation cursor; rewrite
the frozen original row; grant independent source ownership; or relax Language
input/receipt guards. Each would erase an existing distinction rather than fix
the classification that created the impossible interval.

## Regression scope

`RootedBorrowedCycleReadinessTest` declares six maintained controls. An all-six
pass from one frozen combined batch is still pending:

- Exact original inputs in observer-first and source-first schedules, complete
  Agreement output and history equality, full Contracts result identities,
  documents, binding identities, events, checkpoints, receipts and ordered gas
  trace. Independent source heads/history are captured **before** Agreement.
- A real later source publication at the same input is rejected as an anchor;
  reconstruction of the same authenticated anchor remains eligible.
- A genuinely older epoch-0 selection at a later boundary keeps its historical
  cursor. Agreement first settles its original entry and reaches READY; a new
  Agreement path then requests the saved Payment epoch 0 while the authenticated
  independent boundary contains Payment epoch 1. Only the real suspended
  attempt's classification is inspected; it is not published.
- A shared source already borrowed through another path has the same two-order
  behavior. Its frozen primary remains unchanged.
- A separate three-level graph `R -> P -> S` uses the existing unmatched-event
  checkpoint-only pattern from `RootedEligibilitySelectionTest`: only R
  processes S's event, so borrowed P advances its checkpoint representation
  while independent P and S retain their complete original heads/history.
  P's local and independent numbered epochs must both remain exactly 0, with
  distinct BlueIds. A fresh R path emits a real P demand. The test deliberately
  constructs only an adversarial historical classifier discriminator around
  that exact demand; it does not label that discriminator as a processor or
  resolver output. Equal genuine epochs must not promote it to current.
- The real adapter captures the original entry under G-1, G and G+1 on separate
  SDK fixtures, performs normal automatic resolution and retains the actual
  terminal input. G-1 rolls back all owner/source state without events,
  checkpoints or receipts; G and G+1 retain the successful output and charged
  gas. Each exact retained input is also executed in a fresh physical
  materialization with its original rooted authority and cause resources. The
  same frozen obligation replays after scheduler reconstruction without new
  state/history. Different policies are not claimed to have identical
  invocation-bound work IDs.

The original two schedules currently assert exact result identities, without
normalizing operation IDs or dropping fields. Any context-dependent difference
must be explained before changing that oracle.

Required focused companions remain the existing
`RootedLocalHistoryRecoveryTest` (real receiver-gas failure, retained work and
independent source isolation), `RootedTerminalTailSdkBoundaryTest` (authentic
same-epoch/intermediate position guards), `RootedJoinEligibilityTest` (true
pending return cycles and earlier source prerequisites), and
`RootedMultipleHistoricalOccurrencesTest` (distinct saved-state suffixes).
The same-epoch locally advanced control passed its separate one-test run, as
recorded below; that does not replace a combined gate.

## Recorded qualification and harness corrections

The preliminary two-test run passed both exact original-prefix and shared-path
schedule comparisons. The subsequent candidate-02 run completed **18/20**:
all 15 adjacent controls and three commerce controls passed. The original-prefix,
shared-path and same-boundary/reconstructed-anchor tests passed; the two remaining
commerce harnesses failed. These overlapping runs are not added together, and
candidate-02 remains a red batch.

The source/result archive is
`/Users/kamil/Documents/Projects/Blue/rooted-focused-followup-evidence.jtpYIX/library-commerce-candidate-02.tar.gz`,
SHA-256 `3e94a0e06fc1ff1d7b6e9c0119d8c8e25e6d21c02ef3530b6f560aaa6e862656`.

The failures justified test-only corrections:

- The gas harness tried to bind a successful **expanded** snapshot to the
  original entry's rooted context. The existing exact-entry guard correctly
  rejected this. It now captures the actual original entry with each policy and
  lets the real adapter build the prospective bindings and retained authority;
  it never stamps the successful result's context onto a different input.
- The older-source harness submitted a later Order-targeted entry before
  Agreement had processed the original attach-Payment entry. That entry did not
  route in Agreement's still-old selected view, so capture correctly returned
  no invocation. It now settles the real earlier prefix and submits a fresh
  Agreement-targeted path with the saved source value.

Neither correction changes production code, relaxes an assertion, changes the
original prefix pins, or grants missing authority. Candidate-03 then passed all
five prior commerce controls, including the corrected actual adapter gas and
older-source cases. Its newly added same-epoch setup failed, so candidate-03
remains **5/6 commerce**, not a green batch. Its six separate FULL_HISTORY
controls also passed, for **11/12 overall**.

The candidate-03 source/result archive is
`/Users/kamil/Documents/Projects/Blue/rooted-focused-followup-evidence.jtpYIX/library-commerce-candidate-03.tar.gz`,
SHA-256 `b54cd516d09860f1f2d3583088e37958a171acf96eb126c40cb600149a8ab649`.

The first added same-epoch negative also failed its setup assertion in
candidate-03: commerce's borrowed cycle advanced the local **numbered** epoch,
so it was not a same-epoch witness. The strict equality assertion was retained.
Its replacement uses the genuine checkpoint-only three-level graph described
above. Candidate-04 passed that exact method **1/1**, with no skips or errors
(XML timestamp `2026-09-11T16:44:24.084Z`; test time 3.190 seconds). The finalized
XML is
`/Users/kamil/Documents/Projects/Blue/worktrees/rooted-baseline-library-followups/build/test-results/test/TEST-blue.coordination.internal.RootedBorrowedCycleReadinessTest.xml`,
SHA-256 `05bac05246a8df52329b955c88f1cd79b3d36a3b5fce1ba3400c91203ea1b5db`.
Its complete source/result archive is
`/Users/kamil/Documents/Projects/Blue/rooted-focused-followup-evidence.jtpYIX/library-commerce-candidate-04.tar.gz`,
SHA-256 `d54c190389a09e4fa5aeab2b6cb8b59930c3cf5ed461cc6976d7b8ded3989ecd`.
This run used the consolidated source, which also contained the separately
reviewed ring representation-history correction; it is not represented as a
run of the isolated +93-line donor alone.

The earlier same-epoch setup failure is not claimed as a runtime regression
or erased by the replacement's pass. Five controls passed candidate-03 and the
sixth passed candidate-04. Subsequently all six passed together in
[the combined 44-case gate](rooted-history-combined-qualification.md), with
unchanged source, all selected adjacent controls and both architecture guards.

## Source and status

The three YAML resources remain byte-for-byte copies of maintained MyOS tutorial
resources:

| File | Original tutorial file | SHA-256 |
| --- | --- | --- |
| agreement.yaml | corrected/14_agreement_order_payment/01_agreement.yaml | 8b9e5eb402492b50daf636cf1b9178f992ce768a007cf64c87162631493dc810 |
| order.yaml | original/14_agreement_order_payment/02_order.yaml | 49d23927ddc64ab3c75c52ca341b35e3e82dec1f47f581762f7e8d68a171112c |
| payment.yaml | corrected/14_agreement_order_payment/03_payment.yaml | e26f20cb34df1972d4dc72ede0deff9c6362150b6de4786a975b4b9f61e0330f |

Donor base: `d0382bbc95a1ba3fe77bd8600fa610ee3041220d`.
This document records focused evidence from distinct batches and the subsequent
combined SDK gate, not a release gate, commit or export. The isolated production delta
remains +93 lines in two files; no production code changed during the harness
repairs. Retained-store
scheduler reconstruction is not fresh-process external-state recovery. No
protocol, specification or public API has changed.
