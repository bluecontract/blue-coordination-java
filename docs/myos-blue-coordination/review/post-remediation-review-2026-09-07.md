# Post-remediation implementation review — 2026-09-07

> Review of the current local Language/Contracts, BEX, Coordination and MyOS Simple candidate.
> Started 2026-09-06; completed 2026-09-07. Review only: no implementation fixes or Phase3 work.
> **Five confirmed findings: one P1 and four P2. No clean integration-readiness sign-off.**

## Conclusion

The selected processing model remains a sound direction for the POC. This review did not establish
a need to replace its operation boundaries, independent source histories, continuation/FIFO rules,
gas model or external-persistence architecture. It also did not reproduce a new successful commit
with a different settled history or gas solely because a worker ran first or a cache was warm.
That is a bounded review result, not a proof of every possible graph.

There are, however, five concrete implementation gaps. The largest is inconsistent application of
independent **producer policy**: external source reuse supports it, while initialization and frontier
installation still require the observer's policy. Other gaps concern metadata publication fences,
one special BEX output branch, and two host progress paths. Each has an executable counterexample
with a positive control. The core issues are inconsistent checks across entry points, not five new
algorithm choices that need another specification debate.

The original R1–R5 counterexamples have been repaired; their [repair record](../implementation/final-review-remediation.md)
and passing tests remain valid for those cases. The findings below concern other combinations or
adjacent paths. R6 stack/depth work remains explicitly deferred. The general Phase3 adapter, F05/F07
integration obligations and large-scale measurements are still planned, not newly discovered defects.

## 1. Candidate and method

This is the same uncommitted local candidate that passed the previous 1154-check gate, not merely
the earlier pushed commits. No release, fetch, branch switch, commit or push was performed for this
review. Unrelated user changes, including MyOS's untracked example, were preserved.

| Repository | Branch | Base commit beneath the reviewed local repairs |
|---|---|---|
| Coordination | `codex/coordination-external-state-poc` | `0e3057325b3e0b756e47f30bb2cc31441ed2fe40` |
| Language / Contracts | `codex/coordination-external-state-poc` | `7dd28cbe917688b1bfb16e7ca9488a5f6a085389` |
| BEX | `codex/coordination-external-state-poc` | `95ba24fa9bf78930daa690ac1885214b0a4da8a9` |
| MyOS Simple | `feat/coordination-with-external-state` | `701f70b2aaba3c796a047648551a9fecd5bd915f` |

The prior local archive `build/readiness-evidence/r1-r5-final-20260906-ZiROHS/` records base commits,
source patches/new files and exact library jars. This review checked the actual artifacts against
that archive. Its diagnostics compile into separate ignored directories; they do not alter the
library or host test suites. PostgreSQL probes use isolated schemas removed by their fixtures.

Review tracks covered the full external Coordination package, critical Language/Contracts/BEX
execution and evidence paths, and the durable host/storage/adapter. Reviewers rotated away from
their previous implementation ownership. The coordinating review checked the selected kernel and
documentation, independently repeated all five counterexamples, and challenged their assumptions.
Standalone probes, commands, positive/negative controls and limitations are in the
[evidence index](post-remediation-review-2026-09-06/README.md).

## 2. Findings at a glance

P1 is a required functional contract that currently rejects a valid independent processing case.
P2 identifies a narrower correctness/progress or exposed-API defect; it does not imply every normal
workload fails. Repair the affected paths before relying on them during Phase3.

| ID | Priority | Finding | Demonstrated boundary |
|---|---|---|---|
| N1 | P1 | Initialization and FROM_FRONTIER installation still require the observer's gas policy. | Actual Core: valid independent source rejected, hot and cold initialization plus actual frontier creation. |
| N2 | P2 | Early metadata-only results omit supplied owner-attributed CAS fences. | Actual Core result loses a valid supplied fence; no stale SQL commit claimed. |
| N3 | P2 | Transient cyclic references bypass the BEX output rule through `schema.blueId`. | Actual Compute repeatedly escapes operationally instead of consuming an authored failure. |
| N4 | P2 | A held discovery item can starve other fanouts and backfills. | Actual PostgreSQL recovery, with a supported reduced proof budget and a healthy smaller root. |
| N5 | P2 | Public work release can orphan a retained attempt across generations. | Actual PostgreSQL retain/release/recovery; current `DurableHost.step` does not call this method. |

### N1 — Producer policy separation does not cover initialization and frontier installation

**Given:** Source S successfully initializes under its own limit of 5000, using 1248 gas. An
independent parent has limit 100000 and embeds S. There is no return edge or common atomic group.
The evidence explicitly supplies S's expected producer basis and authenticated source result.

**Actual:**

| Source evidence | Parent outcome |
|---|---|
| Same policy as parent, initialization in memory | SUCCESS, 1518 gas. |
| Same policy, initialization restored from receipt | SUCCESS, 1518 gas. |
| Independent 5000 policy, initialization in memory | `IllegalArgumentException`: different environment or execution policy. |
| Independent 5000 policy, restored initialization | The same rejection. |
| Same policy, actual FROM_FRONTIER creation | SUCCESS, 607 gas. |
| Independent 5000 policy, authentic complete frontier | `IllegalArgumentException`: different environment or fixed policy. |

**Expected:** verify the source against its independently admitted producer policy, then charge the
parent's own observation/installation work to the parent. The source must not be rebuilt under the
parent's budget. The identical-policy restriction in [kernel §2.1](../22-processing-kernel.md)
belongs to a genuine newly joined execution group, not an immutable independent history/view.

**Cause:** [SourceInitialization:128](/Users/kamil/Documents/Projects/Blue/worktrees/coordination-external-state/blue-language-java/blue-contracts-core/src/main/java/blue/language/processor/closure/SourceInitialization.java:128)
compares retained producer policy with the argument supplied by the importing call.
[Core:508](/Users/kamil/Documents/Projects/Blue/worktrees/coordination-external-state/blue-coordination-java/src/main/java/blue/coordination/external/CoordinationCore.java:508)
passes the consumer policy. The same pattern remains in Contracts initialization substitution and
prospective attachment offering. [SourceFrontierView:89](/Users/kamil/Documents/Projects/Blue/worktrees/coordination-external-state/blue-language-java/blue-contracts-core/src/main/java/blue/language/processor/closure/SourceFrontierView.java:89)
repeats it for exact frontier installation, reached by Core's actual creation path.

**Scope:** this rejects valid work; the probes do not show incorrect source gas settlement or a
corrupt committed history. Nested borrowed initialization has the corresponding policy check by
inspection, but was not separately reproduced as another failure.

**Correction:** apply one independent-producer verification rule to external programs, failures,
initializations, frontier views and borrowed initialization evidence. Preserve complete environment,
source ownership, predecessor and placement checks. Missing authority must request evidence;
contradictory authority must reject. Merely deleting the policy comparisons would be unsafe.

**Regression card:** same/different producer policy × hot/cold initialization × initial/runtime
attachment/frontier; add missing/wrong basis negatives, no source-handler reexecution, parent gas
parity for equivalent evidence, and a genuine returning-join common-policy control.

Evidence: [initialization probe](post-remediation-review-2026-09-06/cross-layer/InitializationPolicyProbe.java)
and [frontier probe](post-remediation-review-2026-09-06/cross-layer/FrontierPolicyProbe.java).

### N2 — Metadata progress drops owner-attributed publication authority

**Given:** An initialized document ignores the next exact Entry because its actor does not match.
`EvaluationEvidence.operationFences` contains the target's valid CAS fence; the legacy global
`fences` list is empty. This is an accepted input shape, not a malformed owner map.

**Actual:** Core returns input-consuming `MetadataProgress` with zero fences. Copying the same
fence redundantly into the legacy list returns one fence. Both results identify the same Entry.
The publication proposal thus loses authority supplied through the documented attributed API.

**Cause:** early metadata exits at [Core:661](/Users/kamil/Documents/Projects/Blue/worktrees/coordination-external-state/blue-coordination-java/src/main/java/blue/coordination/external/CoordinationCore.java:661)
and lines 685/743 copy only `evidence.fences()`. The later `PreparedOperations.targetProgress`
correctly uses the target's `operationFences` entry. The API does not require callers to duplicate
attributed fences into the legacy global field.

**Impact qualification:** a stale database commit was not demonstrated. A host might independently
reconstruct the required guard; an adapter trusting the returned proposal would not receive it.
The result is nonetheless inconsistent at the library boundary intended to drive publication.

**Correction:** centralize metadata result construction and preserve the target's metadata
publication authority consistently, with explicit legacy compatibility or a missing-authority need.
Do not union every independent source's mutable-head fence: metadata names one target lineage,
and immutable source results are dependencies, not a reason to revoke source independence.

**Regression card:** ignored input with attributed-only fences; failed-source-only early metadata;
late target progress alongside source operations; exact returned fence set; unrelated source-head
advance must not add accidental fencing. Preserve zero semantic gas/epoch for metadata-only work.

Evidence: [witness and stdout](post-remediation-review-2026-09-06/coordination/REPRODUCING.md).

### N3 — The special schema-reference branch bypasses the transient cyclic-reference rule

**Given:** Compute constructs an object dynamically with `$objectSet`. Its reference is a hash of
ordinary text followed by `#0`; no cyclic-set proof or carried exact-value capability exists.
The document first buffers `counter = 99`, then emits the constructed value. Inputs E15 and E16
are available in order.

**Actual:** placing this reference under ordinary `nested` or `type` produces deterministic
`RUNTIME_FATAL / CONSUMED`, zero events and counter 0 on repeated E15 evaluation. Putting the same
transient reference under `schema.blueId` instead repeatedly escapes
`UnclassifiedProcessingException` caused by missing invented cyclic content. E15 has no consumable
outcome, so the selector cannot progress to E16. The preceding patch is still rolled back.

Standalone BEX also admits this schema form through `$nodeBlueId`, `$appendEvent` and `$appendChange`,
where the ordinary-field and type controls reject it.

**Cause:** [BexBlueNodeWriter:350](/Users/kamil/Documents/Projects/Blue/worktrees/coordination-external-state/blue-bex-java/blue-bex-core/src/main/java/blue/bex/value/BexBlueNodeWriter.java:350)
validates schema-reference syntax but omits the transient cyclic-member restriction present in
the ordinary reference branch. This contradicts the existing
[BEX output contract](/Users/kamil/Documents/Projects/Blue/worktrees/coordination-external-state/blue-bex-java/docs/blue-output-boundary.md:51).

**Scope:** an output-rule and failure-classification hole, not evidence of a forged cyclic body
or invalid durable commit. A legitimate ordinary schema reference may require missing external
evidence; that must remain noncommitting. Carried exact schemas must remain supported.

The probe's E16 targets the same workflow; it demonstrates that selection cannot pass the unresolved
E15, not an executed successful repair at E16. A valid-next-input control belongs in the regression.

**Correction:** apply the existing transient-reference restriction to this special branch before
resolution, without broadening generic exception catches or changing decimal arithmetic.

**Regression card:** ordinary field/type/schema × identity/event/patch output × transient versus
carried exact reference. Execute representative cases through actual Compute; assert terminal
classification, rollback, no invalid event, stable repeated result and progress to the later input.

Evidence: [standalone and actual Compute probes](post-remediation-review-2026-09-06/contracts/report.md).

### N4 — Discovery fairness is incomplete after prefix-maintenance isolation

**Given:** P and Q already have committed prefix roots and materialized fanout rows. They were
prepared with the default 4 MiB proof capacity. A fresh host uses a valid smaller capacity of
1300 bytes: P needs 2257 bytes; Q needs 1268. This explicit restart/reconfiguration assumption
changes physical capacity, not document history or gas.

**Actual:** three `recover(100)` calls repeatedly select P and throw its proof-capacity Hold.
Q receives no delivery; P remains immediately due. Direct `fanoutPage(Q, 1, 1)` under the same
capacity succeeds and creates Q's delivery. Roots and fanout cursors remain unchanged during the
held calls; only the two original commits exist and no semantic evaluator/recommit is invoked.

**Cause:** [PostgresDiscoveryStore:302](/Users/kamil/Documents/Projects/Blue/myos-simple/src/main/java/blue/myos/mini/durable/PostgresDiscoveryStore.java:302)
aborts the discovery family at its first held item. The failing read precedes `fanoutPage`'s
progress transaction, so `next_attempt` is not moved. R5 now isolates the four prefix queues and
other maintenance families, but not fanout/backfill items inside this discovery family.

**Expected:** P remains held; Q and independent backfills receive bounded service. A recurring
Phase3 pump alone would repeatedly select the same oldest held P. Raising capacity or explicitly
servicing Q is a workaround, not per-item fairness.

**Correction:** durable, cursor-fenced per-item Hold/backoff for discovery fanouts and backfills,
with fair continuation to healthy items. Preserve exact root/cursor authority and unknown-failure
propagation. This is not F07's deferred historical-recipient-range algorithm.

**Regression card:** two fanouts and a backfill with one over-capacity item; cold restart; zero
cursor/gas changes for P; Q progress; no reread during backoff; exact resumption after capacity
repair; stale failed worker cannot resurrect a completed hold.

Evidence and exact capacity qualification: [PostgreSQL diagnostics](post-remediation-review-2026-09-06/host/README.md).

### N5 — Public release can detach retained-attempt authority from discoverable work

**Given:** An authorized caller retains a valid prepared plan under work generation 1, then calls
the public `release` method with that current work fence. It subsequently claims the work again
and retries the same logical CommitKey with unchanged result material.

**Actual:** work advances to generation 3 while the noninvalidated retained plan still names
generation 1. Same-key retention throws `Earlier attempt requires reconciliation`. Cold recovery
does not discover that plan; a further same-key retry fails again. Explicit reconciliation using
the known CommitKey invalidates the retained attempt, after which the retry commits successfully.

**Cause:** [PostgresWorkStore:212](/Users/kamil/Documents/Projects/Blue/myos-simple/src/main/java/blue/myos/mini/durable/PostgresWorkStore.java:212)
requeues without checking retained ownership. [DurableHost:69](/Users/kamil/Documents/Projects/Blue/myos-simple/src/main/java/blue/myos/mini/durable/DurableHost.java:69)
discovers retained attempts only through a matching current work generation. R4's repaired lease
recovery correctly protects this invariant; public release still bypasses it.

**Scope qualification:** current `DurableHost.step` never calls `release` and deliberately leaves
uncertain commits for reconciliation. This is an exposed host-API lifecycle hole, not a reproduced
failure in its ordinary worker path, duplicate commit or irreversible loss. The necessary caller
precondition is neither documented nor enforced.

**Correction:** under the work lock, reject ordinary release while retained authority owns that
generation, or perform full-set reconciliation/invalidation. Never invalidate only one member of
a joined retained plan. Normal pre-retain release must remain available.

**Regression card:** release before/after retain, same-key retry and cold discovery; joined plan
with several work fences; concurrent retention/release; no stranded active plan and no duplicated
result. Evidence: [retained release probe](post-remediation-review-2026-09-06/host/README.md).

## 3. What was checked beyond these findings

| Dimension | Review outcome and remaining boundary |
|---|---|
| Deterministic source authority | R1/R2 original-root, original-choice, unused-choice, co-owned-member and distinct-policy external paths inspected; no new counterexample there. N1 identifies the unaligned initialization/frontier paths. |
| Ordering and causality | Directed Timeline completeness, microsecond/Entry ties, predecessor cuts, distinct source operations versus events, continuation/FIFO and intermediate views reviewed. No replacement scheduling rule proposed. |
| Embedding, aliases, cycles | Canonical birth, placement-local installation, frozen routes, prospective versus active edges, same-origin return admission and rollback reviewed. R6 depth work excluded. |
| Failure and gas | Terminal versus operational outcomes, rejected joins, conditional-attempt invalidation, source gaps and exact rollback pins reviewed. N3 is the concrete uncovered authored-output branch. |
| Cold evidence and identity | Source success/failure codecs, original selections, bounded fragment acquisition, exact/cyclic identities, receipt/source/commit key separation reviewed. No new forged-authority acceptance established. |
| Publication and concurrency | Work/head/account fences, aliases, uncertain commits, atomic activation, outbox order and deduplication reviewed. N2/N5 are distinct boundary gaps; no duplicate durable commit demonstrated. |
| Timeline host | Typed descriptor/locator binding, strict timestamps and guarantees, proof continuity, quiet relevant members and per-Timeline maintenance reviewed. General semantic-to-index wiring is still Phase3. |
| Discovery and scaling | Prefix staging/activation, lifecycle intervals, fanout/backfill and retirement reviewed. N4 concerns item-local liveness. F07 complete interval selection remains a separately planned task. |
| API and quality | Current documentation checked against actual entry points. Repeated construction/verification paths explain the concrete inconsistencies; a new generic interpreter or public hierarchy is not needed. |
| Performance | No fresh throughput/SLA claim. Hot working-set reuse, shared-DAG verification cost, wide fan-in and bounded physical work require Phase3 measurements. |

### Additional adversarial controls

- **920 lifecycle local-gas cutoffs:** five real Contracts fixture shapes, including source/observer
  termination and joined lifecycle failure. Zero unexpected escaping faults/Needs or failed-state
  BlueId/epoch/termination rollback mismatches. These are diagnostic runs, not 920 new JUnit tests
  or exhaustive shared-budget/cold-replay coverage.
- **Rejected duplicate-program hypothesis:** one unrelated restored source program, two references
  to it, and two independently restored equivalent objects all produced metadata progress with zero
  further fragment reads after the provider became unavailable. The suspected lazy-read liveness
  defect was not reproduced and is not a finding.
- **Authorization policy note:** an already-authorized physical reservation can finish after
  revocation while waiting for a source lock; a new call after revocation rejects. Business commit
  has stronger fencing. Without an explicit rule forbidding completion of in-flight reservations,
  this is a policy/coverage note, not a demonstrated security vulnerability or new blocker.

## 4. Verification record

The preceding stable candidate has 1059 affected library checks, 88 PostgreSQL foundation checks
and 7 actual-library/PostgreSQL checks, all passing. This review reused their frozen Contracts/host
artifacts and added the diagnostics above rather than rerunning those same suites for every probe.

The expanded unfiltered regression completed successfully in **36m 4s**:

| Current run | Tests | Failures / errors / skipped |
|---|---:|---|
| Language root `test` task | 2497 | 0 / 0 / 0 |
| BEX conformance `test` task | 926 | 0 / 0 / 0 |
| Coordination `test` task | 691 | 0 / 0 / 0 |
| **Total** | **4114** | **0 / 0 / 0** |

These are existing tests, not 4114 new regression tests for the five findings. They overlap the
preceding affected gate and must not be added to its total as unique checks. The additional
counterexample probes and 920 gas-cutoff runs are recorded separately. The exact command was:

```sh
./gradlew :blue-language-java:test :blue-bex-java:blue-bex-conformance:test test --include-build ../blue-language-java --include-build ../blue-bex-java --max-workers=2 --console=plain --continue
```

The local archive `build/readiness-evidence/post-remediation-review-20260906-H3ZbXf/manifest.json`
records the result, XML reports, source-diff checks, diagnostic hashes and worker classpath.
All nine production library jars match the preceding stable gate. All four repositories retain
the same branches, base commits and tracked non-document diffs; new source files and the unrelated
MyOS example also remain unchanged. The package's 651 local Markdown links resolve, and
`git diff --check` passes.

The Coordination run covers its `test` task, not separate `integrationTest`, `scenarioTest` or
`consumerTest` tasks. Its slow historical fixtures are not a throughput measurement of the proposed
external-state POC.

No complete Phase3 application was exercised. In particular, neither foundation storage tests nor
the seven thin actual-library bridges prove the complete runnable graph adapter, all tutorial and
MyOS examples, F05/F07, or platform-scale throughput. Existing green results are not erased, but
must not be described as all-catalog acceptance.

## 5. Recommended next action

Do not reopen the processing algorithm solely because of these findings. Close the five bounded
implementation gaps and their contrasting regression cards, then continue the existing Phase3
plan. The largest necessary API refinement is consistent independent producer-basis admission at
the initialization/frontier boundaries; metadata and host lifecycle/fairness fixes are localized.

Use a small cross-product matrix for each repaired rule, not an ever-growing list of disconnected
examples: evidence kind × entry point × hot/cold; terminal result kind × fence representation;
work lifecycle action × retained authority; queue family × item-local Hold. Run focused controls
first and one stable affected gate afterward. Do not repeatedly run the slow complete historical
engine fixtures after every small edit.

Phase3 should then concentrate on one shared Agreement with independently scheduled Orders,
two-source/diamond composition, failure-gap alignment, dynamic attachment/return, cold resumption
and bounded work discovery. Measure source commit latency separately from total fanout completion,
and count bytes/reads/copies and rows examined, not merely page sizes or final wall time.
Large shared-DAG duplicate verification and dependency-invalidation scans are measurement targets,
not additional confirmed defects. No new migration mechanism, specification profiles, billing rule,
global source/observer transaction, general cancellation mechanism or R6 fix is requested here.
