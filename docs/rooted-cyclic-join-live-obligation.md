# A cyclic join can remove an unexecuted LIVE obligation

Status: reproduced on both the proposed candidate and unmodified Coordination
RC9 with published Language RC25/BEX RC6/Catalog RC22. The behavior predates
our candidate corrections; its prescribed repair is not yet established.
Do not change an event oracle or relax witness/publication checks to close this.

## Five-input example

Arrows mean **embeds**. All nodes start with `observed=0`. A received addressed
token increments `observed`; `next=B` additionally emits a fresh token to B.

| Time | Action |
| --- | --- |
| 100 | B attaches authored C; settle B. |
| 150 | A attaches authored B; settle A. Graph: A → B → C. |
| 200 | A emits `{to:C, next:B}`. C does not yet contain A and cannot react. |
| 250 | A increments only its unrelated `touches` field; no event is emitted. |
| 300 | C attaches authored A and imports its history, forming C → A → B → C. |

C receives A's old token and emits a **new C token addressed to B**. That token
is present in C's committed event evidence. The original expectation is one
reaction in both C and B. Actual final counts are **A0/B0/C1**, all three roots
are READY, all three edges are active, and B selects no remaining work.

Removing only A's eventless action at250 produces **A0/B1/C1**. The distinction:
the token-bearing A epoch5 is then the terminal source receipt. With action250,
the endpoint is A6 and the same token is imported before terminal activation.
This is a difference between logical histories, not by itself a determinism
counterexample. Its effect on the already-active B receiver needs explanation.

## Exact work that disappears

The five-input diagnostic records B as eligible for the original LIVE300 before
C runs (`blocked=false`, exact entry
`HgWUG5gsFmWCS84v3C7KE995QVACy6P5oNM8J4h8fTE5`). After C's final co-owned
publication, B has neither LIVE nor historical work. B never entered its own
LIVE300 calculation; the join installed the newer selected C checkpoint.

During C's earlier import, historical B is an immutable witness, not a receiver.
Excluding it is correct; making it mutable would alter authenticated history.
Later co-ownership cannot simply replay an event into its already frozen
receiver set. Fresh execution of each identical rooted input matches all
result/event/receipt/checkpoint/companion identities and logical gas/trace.
That proves input-level reproducibility, **not** correctness of the complete
selection/join algorithm.

The longer original MyOS ring/chord/reconnect scenario shows the same shape:
84 C-entry-owner publications; C's forwarded event exists; final join owns
A/B/C but runs no B handler; expected B2, actual B1; final selection `NONE`.

## Required clarification before a runtime fix

RCP-CAUSE-01 preserves an already-live root's original LIVE obligation despite
source-first publication. RCP-PROGRESS-03 separates source progress from parent
application progress. RCP-OWN-03 requires the exact causal joining view/fence;
§8.7 says delayed notification cannot erase pending work. Conversely,
RCP-SCOPE-04 and Contracts §16.8 preserve immutable witnesses and frozen event
receivers. Neither set of rules alone specifies the missing discharge step.

Exact source: Language `34e9aa2f`,
`blue-contracts-core/src/main/resources/specifications/`:

- `rooted-checkpoint-processing-1.0-draft.md:180`:
  “For a selected root already live when an external entry becomes eligible,
  deliver that original entry as a LIVE cause against its selected channel progress.”
- The same file, line164: “Acquire its required exact causal view and
  publication fence before accepting that expansion”. Line166 permits a typed
  prerequisite/conflict, not a rewind of an independently newer head.
- The same file, line58: “Source checkpoints do not replace a parent's
  retained-application cursor.” Line229 requires delayed notification not to
  erase pending work.
- `blue-contracts-and-processor-specification-1.0.md:6458`:
  “An event/update occurrence freezes its target occurrence set when created.”

These are complementary constraints; the test exposes their interaction at
one join boundary, not a license to discard either constraint.

**May the final cyclic join discharge B's pending LIVE300 solely by installing
C's newer checkpoint, without B's calculation? If not, how must that original
obligation be retained/settled before the join; if yes, what exact evidence
establishes that its required effects were preserved?**

Changing the prerequisite comparison to include300, replaying at join, or
weakening CAS is not an established solution. In the separate B-first control,
the local join tries to acquire C6 while independent C is still C0. Publication
correctly rejects without changing any head/history; C's same-boundary work is
blocked and canonical selection returns the same B work. The recovery route
also needs verification; this is not evidence that a fence should be bypassed.

## Evidence

Candidate: Coordination `cf35914e`, Language `34e9aa2f`, no production edits in
the diagnostic branch. `RootedHistoricalWitnessForwardingTest`: 3 methods,
1 pass / 2 failures, 47.118 JUnit seconds. The terminal control passes; the
nonterminal count and B-first publication fail. Original assertions are retained.

Archive `rooted-successor-language-evidence.GlIOBx/witness-forwarding-03-evidence.tar.gz`:
`5121a93c8179bcd8a617b774b57a9f655f02bdfac326b19c35d7e06103766a5c`.
Original trace: `sdk-full-ring-04-forensics-evidence.tar.gz`,
`51553a8ee262d3d65a9796dda0be9ee598500faae36c6fea974810958e8bbf94`.
Detailed read-only analysis is retained under
`rooted-baseline-release-review.VamIZv/full-ring-reconnect-obligation-analysis.md`.

Public-only baseline control: Coordination `0a047461bbc9e97a969ae1021bd86964eadd4c8c`
(RC9), production/build/dependency configuration unchanged. The public consumer
probe uses the built baseline JAR, exact published dependency pins and identical
authored template, namespace and all four/five entry BlueIds. It uses no internal
fixtures, private owner binding, reflection or alternate processor. Two methods,
one pass / one final-count failure, 19.433 JUnit seconds: terminal `[0,1,1]`,
nonterminal `[0,0,1]`. Earlier isolation, prefix, exact event and READY/edge checks
pass. The first launcher attempt had a test-only access error (`scalarAt` is
package-private); the rerun reads the same textual fields through public `json()`.
That compile failure is archived separately, not reported as a library defect.

This independently reproduces the outcome on upstream. It does not supply
the candidate's internal before/after LIVE-selection trace, prove the B-first
conflict upstream, or by itself determine which layer must be corrected.

Upstream source/configuration/JAR/report/launcher archive:
`rooted-successor-language-evidence.GlIOBx/upstream-forwarding-02-evidence.tar.gz`,
SHA-256 `a9658fb0680a2d806871a2fff36b33b1d0d78efdc643ca14cfe6791a44e1118b`.
Consumer test source SHA-256:
`a58c14629ab700012ce08070946a083aed471e68928d03f2525efdb9083e1322`.
