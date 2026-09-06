# Pre-Phase3 review remediation

> Authorized follow-up, 2026-09-06. **Scoped repair regression gate passed; subsequent findings below remain open.**
> This work repairs the Phase1/2 boundary; it does not start the general Phase3 adapter.
> F05 and complete F07 interval selection remain explicit Phase3 work, as scoped below.

**Subsequent review:** the [final implementation review](../review/final-implementation-review-2026-09-06.md)
finds five open defects on the committed repaired candidate, including incomplete fresh-source
handling for F02/F04 and remaining schema validation for F03. The passing tests below are preserved
as scoped evidence, not a claim that these newly exercised combinations pass.

## Baseline and scope

The independent code review inspected Coordination `1b93c01`, Language `8623491`, BEX `0b9eac1`
and MyOS Simple `5a5d34d`. These match the published working branches. The previous
[readiness record](phase-1-2-readiness.md) remains evidence for that candidate, not a passing
result for these newly identified combinations or subsequent repairs.

Keep the selected processing kernel and [Phase3 slices](../25-phase-3-integration-plan.md).
The repairs must preserve source independence, exact chronological observations, independent
consumer gas scopes, genuinely coupled atomic groups, and operational failures that consume nothing.
No release, migration, general multi-provider implementation or new interpreter belongs here.

## Disposition and ownership

| Finding | Accepted scope | Owner and acceptance point |
|---|---|---|
| F01: lost typed history needs | Preserve the complete immutable need through history preparation and portable restoration. | Coordination / Language, before historical acquisition is accepted. |
| F02: lost historical frontier/selection context | Preserve all evidence and authenticate the original source input's choices; never adopt the importing observer's choices. | Coordination / Language, before history reconstruction is accepted. |
| F03: BEX output validation and premature integer narrowing | Repair the actual error producers and legal large-number operations, retaining unknown-fault protection. | BEX plus actual Contracts/Compute regression. |
| F04: inconsistent source-result compatibility | Centralize compatibility and verify the producer's expected basis, not equality with the consumer's budget. | Language / Coordination, before either metadata or execution progress. |
| F05: exact Timeline translation | Enforce the already documented descriptor/proof/entry mapping through one actual adapter boundary. | Phase3A; not a new multi-provider project. |
| F06: committed publication has no capacity release | Configurable physical budgets, coherent admission envelope, and retry of the retained stream head. | MyOS foundation, before Phase3A publication/restart acceptance. |
| F07: lifetime-history fanout amplification | Establish the temporal projection contract and lifecycle scan high-water; implement complete indexed range selection before scale acceptance. | MyOS foundation preparation, then Phase3B discovery integration. |
| F08: Timeline maintenance starvation | Durable bounded service turns per Timeline for both maintenance queues. | MyOS foundation, before Phase3A pump acceptance. |

The current remediation implements F01–F04 and F06/F08. F07's foundation preparation now captures
a source-fenced high-water to avoid replaying old lifecycle change-log rows after the initial scan.
This **does not close F07** while each new receipt still scans all lifetime occurrences or staged
rows. F05 and the complete temporal selector are explicit integration work.

## Contracts that the repairs must preserve

### Original source choices are logical input, not retry configuration

If A observes X, A's FROM_* choice selects A's observation history only. If X's historical input
creates an attachment to Y, reconstruction needs X's own original choice for that input.

Every historical external step must identify its original admission, including an explicit empty
selection. Bind it to source, canonical source basis, exact cause and semantic predecessor. Missing
required authority is a named need, not implicit FULL_HISTORY. Intrinsic initialization keeps its
fixed source rule. A hash of caller-provided selections is not proof that those were the admitted
choices. The original authority must be established independently of the reconstruction attempt;
the first worker or importing observer cannot choose a replacement root. Retain and verify the
identity across a cold restart without persisting an interpreter continuation.
Multi-owner input records must respect the original relevant scope. Adding an unrelated reverse
observer to the record cannot make its state/predecessor a prerequisite for processing the source.
Every genuinely co-owned member's recorded basis must match the common invocation context, not
just the member through which reconstruction was requested. Independent read dependencies are not
co-owned members and may retain their own producer policies.

### Producer policy is not consumer policy

The existing tests deliberately allow a source and observer to have different gas budgets. Preserve
this positive contract. Authenticate a source result against its independently expected canonical
source basis, including the producer policy and complete required environment. Managed lanes already
name a source basis; external acquisition needs equivalent authority. Deriving the expected value
from the offered receipt itself is circular validation. Missing authority must not advance a cursor.
Apply the rule to success/failure substitution and metadata-only paths as well as actual execution.

### Physical publication capacity does not change semantic results

A committed batch remains the same operation and event set during a capacity hold. Increase supported
publication capacity and retry the retained stream head, including after a process restart. Do not
requeue its already committed semantic work or reset its meter. The sink must deduplicate repeated
delivery, including an acknowledgement lost after applying the batch. A blocked stream head cannot
be bypassed by its successors; unrelated streams can continue. Avoid spinning on unchanged capacity.

The declared supported envelope must cover admitted batches, or admission must hold before commit
until an adequate publication path exists. Raising a hard-coded constant alone is not a contract.
Transport chunking, if later required, cannot split the semantic operation.

### Temporal projections are conservative candidate indexes

External order is the full `(timestampMicros, entryBlueId)` tuple. Producing operation position,
inherited reaction origin and physical SQL sequence are distinct. A scalar range may exclude a
candidate only when its library-verified meaning proves that exclusion; ambiguous equal-time cases
remain candidates for the owning semantic selector. Never filter only currently active occurrences.

Candidate coverage is the union of entitled historical imports and live observations, not just a
live lifetime interval. If K100 registers FULL_HISTORY while S has no physical tail, a later
activated canonical E10 prefix must still reach K100's import lane: its original backfill captured
zero, and comparing E10 only with `activeFrom=K100` would incorrectly omit it. The eventual range
selector must use the accepted history mode/frontier and exact occurrence authority as well.

A source-fenced lifecycle high-water can avoid replaying old changes after an initial candidate
snapshot. Preserve registration's captured-tail/backfill handoff, late earlier registrations,
pending lifecycle owners, staged-prefix activation and cold page continuation. An empty physical
page is still not proof of completed semantic discovery.

## Focused acceptance stories

Each test needs expected semantic and durable outcomes, not just an exception type or row count.
These are additions to the existing scenario families, not a second catalog.

| Story | Required result and forbidden outcome |
|---|---|
| History X creates Y and pauses for evidence | Full typed request survives portable restoration; two sites remain distinct; supplying it resumes the same result without an orphan Y. |
| Direct X execution versus reconstructed X, with frontier choices | Same selected views, operation identity, history, events and gas; no importing-observer policy leak or silent default. |
| Original admission missing or changed at restart | A named need or rejection before progress; a newly self-hashed choice cannot replace original authority. |
| Original admission projected through either co-owned member | Same operation, receipt and gas; reject an altered basis of either owner and omit no co-owned member. An unrelated reverse observer is rejected instead of becoming a predecessor dependency. |
| Dynamic invalid BlueId output | Owning deterministic rejection and correct settlement/rollback; no emitted invalid event or endless operational retry. |
| Large list index and split limit | Legal results without Java narrowing failures; retain lazy defaults, exact-list absence authority, split `-1`, and semantic metering. |
| Unknown implementation failure next to invalid authored output | Unknown faults remain operational and consume nothing; classification is not broadened by Java exception class. |
| Producer evidence with altered context | Rejected before metadata/managed/execution progress; legitimate different observer budgets remain valid. |
| Same-type Timeline alias and wrong proof/actor | Actual adapter rejects before cursor movement, including completeness without any entries. |
| Publication exceeds either injected physical budget | Hold, cold capacity increase, successful retry and lost-ACK retry; unchanged identities and one receiver effect, without another Core evaluation. |
| One live consumer, many retired generations, new source receipts | Measure all examined rows, created work, WAL and source-lock cost. A small page or reduced log replay alone is not F07 closure. |
| FULL_HISTORY registration precedes physical activation of an older source prefix | The historical candidate still reaches its entitled import lane; live activeFrom bounds cannot replace history/frontier authority. |
| Busy early-key Timeline A and ready Timeline Z | Z receives service within the declared active-Timeline turn bound, including restart, without semantic reordering. |
| Attach/input/remove share one timestamp | Full entry-ID tie order and activation identity survive conservative SQL projection; a `<T` proof is not `<=T`. |

## Execution and evidence

Libraries and independent PostgreSQL foundation work can proceed in parallel. Serialize builds
sharing included library worktrees. Use reproducing focused tests first, affected regression at a
stable checkpoint, and a final real-library/host handshake only after the artifacts stop changing.
Do not rerun every expensive suite for every edit or transfer an earlier green result to changed code.

The PostgreSQL handshake lives in **MyOS Simple's `durable-library-smoke`**, not in the libraries.
Coordination, Language and BEX remain database-independent. The test host supplies exact evidence,
stores the returned results and restarts against PostgreSQL to verify the persistence boundary.
Those seven existing thin integration cases neither introduce a database dependency into a library
nor constitute the general Phase3 graph adapter.

The initial review reproduced F03 through the actual BEX interpreter and classifiers using existing
workspace artifacts. It did not execute a complete Contracts processing invocation or a new full
regression. F01/F02/F04/F06/F07/F08 were confirmed from source boundaries; newly added executable
witnesses and their repair results must be recorded separately below.

### Verification results

The agreed repair gate passed, including the final MyOS Simple/PostgreSQL handshake against the
same library artifacts. This is not a full Phase3, scale or all-catalog acceptance claim. Entries
below distinguish predecessor candidates, final affected verification and reused unchanged evidence.

- **F02 red witness:** `CanonicalSourceAdmissionTest` failed 1/1 on the missing-admission case:
  the baseline returned a `Step` rather than waiting for original input authority. The report is
  retained in Coordination `build/readiness-evidence/review-f02-red/`. This is a recorded reproducer,
  not the result of the repaired implementation.
- **F01/F02/F04 focused integration repair:** 24/24 tests passed in 37 seconds: nine source-history,
  four frontier/FULL_HISTORY, five Core, two observed-receipt, two original-admission and two Await
  codec tests. Controls include direct-versus-reconstructed operation/gas parity, portable typed
  needs, one-head cold resume, different producer/consumer budgets, and authority required before
  stale-header evidence can establish an empty complete cut. Evidence is retained in Coordination
  `build/readiness-evidence/review-history-context-green/`. The later stable affected-library
  regression and host handshake are recorded separately; this focused result does not imply them.
- **Final stable library candidate:** all 70 external Coordination, 161 affected Contracts,
  36 architecture/style/static guards and 157 BEX tests passed, with zero failures/errors/skips,
  in an 84-second combined run. Four additional original-selection substitution controls then
  passed on the same production artifacts. These cover actual co-owned A/B projection symmetry,
  every co-owner's admitted basis, nested external producers with different policies, and an actual
  invalid Compute output with deterministic gas settlement, rollback and no invalid emitted event.
  The public API baseline diff passed with no removals; the two new supported helpers were added
  to module/API inventories without replacing the released baseline. Commands, XML, API diff and
  artifact hashes are retained in Coordination `build/readiness-evidence/review-final-libraries/`.
  The preceding broader candidate passed all 565 Contracts tests; its external run exposed one
  fixture missing the newly required producer basis. Its topology assertions were preserved when
  supplying that known context. That broader result is archived separately and is not relabeled
  as the final artifact after the last narrow compatibility correction.
- **F03 focused BEX repair:** 157/157 tests passed across five classes: nine authored-input
  boundary tests, 134 operator conformance controls, six structured-evidence tests, three unknown
  failure-boundary tests and five identity-validation tests. No classifier relaxation or golden
  update was used. Large-number controls compare complete gas traces; unavailable exact-list
  evidence cannot become a default value. Evidence is retained in BEX
  `build/readiness-evidence/f03-focused-20260906-vmm0Vo/`, including command, XML totals, source/test
  classes and BEX artifact hashes. No contemporaneous Language artifact snapshot was retained for
  this run; later Language hashes must not be attributed to it. The final stable library run above
  refreshed all 157 tests and passed the real Contracts/Compute settlement/rollback integration.
- **F06/F08 and partial F07 PostgreSQL repair:** `./gradlew durableHostTest --max-workers=2`
  passed 75/75 (43 host and 32 prefix tests), zero failures/errors/skips, in 47 seconds. This
  followed nine focused cases and a controlled retry-insert/ACK race. Independent review found
  and the owner repaired dependency-proof holds attributed to the wrong stream, stale retry
  insertion racing ACK cleanup, and completed-proof replay creating empty maintenance work.
  XML/HTML and compiled input evidence are retained in MyOS Simple
  `build/readiness-evidence/remediation-host-75-20260906-Z6T5f5/`. The full compiled input archive
  digest is `dbedc50e73ba60bd866621183074dc9d7656ed02af13711a2dfcbffa93f9fa5f`.
  These independent foundation tests did not rebuild the isolated libraries; the updated actual
  library/PostgreSQL handshake is recorded separately below. F07 remains partial, with its initial
  lifetime occurrence scan explicitly open.
- **Final MyOS Simple/library handshake:** `../gradlew test --max-workers=2 --console=plain`
  in MyOS Simple's `durable-library-smoke` passed 7/7, zero failures/errors/skips, in 27 seconds.
  Its actual M1/M2 cold-resume case took 8.436 seconds; this is a test measurement, not an SLA.
  Original empty source choices are admitted separately before replay, missing roots/records
  produce named waits, and a retained source prefix resumes with one head read. Every included
  library compiler/JAR task stayed up to date. Worker classpath, hashes, copies of the tested JARs,
  compiled/source inputs and XML/HTML are retained in MyOS Simple
  `durable-library-smoke/build/readiness-evidence/remediation-seven-20260906-pTE4eo/`.
  Its Coordination, Contracts and BEX artifacts match the final library gate exactly.
- **Documentation checks:** all relative file links were checked; the actual API usage reference
  `reference-api/00-poc-minimal-boundary.java` compiled successfully with `javac -proc:none` against
  the final library classpath, excluding host, PostgreSQL, JDBC-pool and test-runner artifacts.
  This checks signatures, not a runnable host implementation.

## Handoff boundary

At the scoped remediation gate, F01–F04 and F06/F08 passed the recorded checks. F07's lifecycle high-water preparation is
implemented; complete indexed temporal recipient selection remains Phase3B work. F05's actual
Timeline translation belongs to Phase3A. The [Phase3 plan](../25-phase-3-integration-plan.md)
contains both acceptance obligations. No general adapter or release was performed in this
remediation. Verification ran before committing; the repairs were subsequently committed and
pushed as recorded in the [source commit map](source-commits.md). The final review above adds open
findings and supersedes a broad readiness interpretation of this earlier handoff.
