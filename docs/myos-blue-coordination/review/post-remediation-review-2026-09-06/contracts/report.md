# Final independent Language/Contracts and BEX review

Review date: 2026-09-06. Review only: no implementation, test-source edits,
commits, branch changes, or Gradle runs by this reviewer. Standalone diagnostics
compile only the Java files in this directory and use the frozen PG7 library
classpath. The parent is running a separate expanded regression.

## Findings

### P1 — immutable initialization/frontier imports still inherit the observer's policy

This is the same cross-context admission finding independently reproduced by
the parent, not a second defect to count separately.

Language-owned locations:

- `/Users/kamil/Documents/Projects/Blue/worktrees/coordination-external-state/blue-language-java/blue-contracts-core/src/main/java/blue/language/processor/closure/SourceInitialization.java:128`
- `/Users/kamil/Documents/Projects/Blue/worktrees/coordination-external-state/blue-language-java/blue-contracts-core/src/main/java/blue/language/processor/closure/ClosureExecutionSession.java:591`
- `/Users/kamil/Documents/Projects/Blue/worktrees/coordination-external-state/blue-language-java/blue-contracts-core/src/main/java/blue/language/processor/closure/ClosureExecutionSession.java:600`
- `/Users/kamil/Documents/Projects/Blue/worktrees/coordination-external-state/blue-language-java/blue-contracts-core/src/main/java/blue/language/processor/closure/SourceFrontierView.java:89`
- `/Users/kamil/Documents/Projects/Blue/worktrees/coordination-external-state/blue-language-java/blue-contracts-core/src/main/java/blue/language/processor/closure/ClosureExecutionSession.java:744`

`verifySourceBasis` compares the complete retained producer policy to the policy
passed by the importing invocation. Both initialization substitution and runtime
attachment offering pass `input.executionPolicy()`. `SourceFrontierView` makes
the same comparison in `verifyInvocation`; borrowed ADMISSION programs retain
the same-policy check even inside an otherwise independently authenticated
EXTERNAL producer program.

Expected: immutable source preparation or historical state is validated against
its authenticated producer basis. The consumer's budget governs its own work.
Only genuine newly owned feedback/group joins require a common live policy.
Missing producer authority must remain a Need, and contradictory authority must
be rejected; it must not be inferred from the offered program.

Actual: otherwise valid independent evidence with a different producer budget
is rejected before successful interpretation. Parent's actual Core probe uses
a successful 5,000-budget source initialization (1,248 admitted gas), followed by
a 100,000-budget parent. Both hot and cold evidence reject with the explicit
policy-mismatch exception; the same-policy parent succeeds (1,518 gas).

Evidence strength: direct source inspection here plus the parent's actual Core
hot/cold reproducer in `../cross-layer/InitializationPolicyProbe.java`.
The frontier and nested-initializer call sites above establish the associated
coverage needed; do not describe every nested variant as separately executed.

Bounded correction/test: apply independent producer-basis validation consistently
to initialization/frontier imports and their retained borrowed initialization
programs. Preserve environment, exact predecessor/placement, original choice,
and owned-member checks. Add different-policy hot/cold initialization and actual
attachment/frontier positives; retain wrong/missing producer-basis negatives,
source-handler non-reexecution assertions, and genuine same-policy feedback
join controls. Do not merely remove the existing policy guards.

### P2 — transient cyclic references bypass the BEX output rule through `schema`

Primary location:

`/Users/kamil/Documents/Projects/Blue/worktrees/coordination-external-state/blue-bex-java/blue-bex-core/src/main/java/blue/bex/value/BexBlueNodeWriter.java:350`

The ordinary transient `blueId` branch at lines 136–144 rejects a `MASTER#index`
reference without an exact-value capability. The separate `schema.blueId`
branch at lines 350–358 invokes syntax validation but omits that rejection.
This contradicts the rule and negative controls in:

- `/Users/kamil/Documents/Projects/Blue/worktrees/coordination-external-state/blue-bex-java/docs/blue-output-boundary.md:51`
- `/Users/kamil/Documents/Projects/Blue/worktrees/coordination-external-state/blue-bex-java/src/test/java/blue/bex/output/BexSemanticIdentityIntegrationTest.java:554`

Reproduction: create a transient reference using `$objectSet`, with an ordinary
hash of the text `nonexistent-set` followed by `#0`. No cyclic set, exact handle,
custom provider response, or complete cyclic proof is supplied. Place it under
`nested`, `type`, or `schema`, then use the actual BEX interpreter.

Observed standalone result (`bex-cyclic-schema.txt`):

- `nested` and `type`: deterministic `cannot counterfeit` rejection;
- `schema`: `$nodeBlueId` succeeds, `$appendEvent` returns one admitted event,
  and `$appendChange` returns one admitted patch.

The actual initialized Core/Compute probe (`compute-cyclic-schema.txt`) starts
with a valid source, buffers `counter = 99`, then emits that dynamic value.
Two ordered inputs and two retries are supplied:

- `nested` and `type`: `RUNTIME_FATAL`, `CONSUMED`, zero events, counter remains 0;
- `schema`: `UnclassifiedProcessingException`, caused by missing invented
  `MASTER#0` provider content, on both retries. Counter remains 0, but no terminal
  result exists to consume the first input and proceed to the second.

Expected: apply the same deterministic authored-output rejection before the
semantic identity/provider boundary. A valid carried exact schema remains
allowed. An ordinary, valid noncyclic schema reference may still require its
real provider evidence; this finding does not justify classifying genuine
missing evidence as a semantic failure.

Impact: output-contract inconsistency and an avoidable permanent operational
retry dead end for this input. **No invalid durable commit or forged cyclic body
was observed.** Standalone BEX admits the reference; hosted Compute fails safely
with respect to state, but in the wrong outcome category.

Evidence strength: executable actual-interpreter and actual Core/Compute probes
against the frozen artifacts, with ordinary-field and type controls. Sources,
launchers, and stdout are retained in this directory.

Bounded correction/test: reuse the transient cyclic-reference restriction for
the transient schema-reference branch only. Cover root/nested schema with
`$nodeBlueId`, event, and patch; verify actual Compute consumes the deterministic
failure, rolls back the preceding patch, emits nothing, and permits the next
input. Keep carried exact-schema and ordinary schema-reference acquisition
positive controls.

## Additional executed diagnostic: 920 lifecycle gas cutoffs

`TerminationGasSweep.java` reuses the unchanged compiled
`FreshTerminationObservationTest` fixtures and actual `BlueClosureContracts`.
For each owner, it derives every distinct positive admitted-charge prefix from
the baseline and reruns with that exact local cap. It checks no unexpected
exception/Need escapes and every failed group's BlueId, epoch, and termination
flag equal the authoritative input.

| Actual fixture mode | Cutoffs | Escapes/rollback mismatches |
| --- | ---: | ---: |
| Source terminates | 215 | 0 |
| Two accepted direct channels | 220 | 0 |
| Joined lifecycle failure | 90 | 0 |
| Observer terminates | 196 | 0 |
| Two preaccepted embedded channels | 199 | 0 |
| Total | 920 | 0 |

These are additional targeted adversarial runs, not 920 new independent JUnit
tests or a proof of every cold replay/group topology combination. The original
JUnit suite and final PG7 results remain separate evidence.

## Areas reviewed without another confirmed finding

- Same-origin attempt dependency recording, feedback gas-group joining,
  reservations, reverse invalidation, exact predecessor restoration, queued
  own-seed reconstruction, and deferred marker failure ownership.
- Retained observation entry/action ordering, reference-placement boundaries,
  real lifecycle requests, accepted-but-skipped work identities, request/attempt
  fences, and interpretation from a committed terminal after-head.
- Source program/failure original-choice capture, cold codecs, complete producer
  environment/basis checks for EXTERNAL borrowed children, and the R1/R2 strict
  fresh-owner authority path. The previously reported alternate-cached-choice
  issue is addressed; no new counterexample was found in those repairs.
- Frozen node/source-program codecs: shared read/write budgets and fragment
  deduplication, exact digest checks, materialized-copy accounting, closure of
  demand records, original selection maps, and skipped-work validation.
- BEX authored numeric/index repair, schema scalar classification, output
  admission, exact/transient capability distinction, and hosted ledger
  finalization for deterministic, unavailable, and unclassified failures.

## Remaining review/test/performance gaps

- The gas sweep varies local limits over five concrete execution shapes. It
  does not sweep all shared caps, multi-way dynamic joins with open child
  ledgers, every reconstruction topology, or all cold program combinations.
- No new large-width performance benchmark was run. Dependency invalidation
  still scans live attempts and their dependency maps; independent duplicate
  program objects can trigger full digest re-encoding. These are measurement
  targets, not established complexity regressions from this review.
- Malformed authored schema coverage is stronger after R3, but the uncovered
  special schema-reference branch demonstrates that field/boundary cross-product
  testing remains incomplete. Do not infer complete export safety from the
  existing scalar keyword matrix alone.
- R6 recursive-depth/stack work is expressly excluded. General Phase 3 host
  adapters and planned Timeline/interval selection are not library findings.

## Reproduction commands

From the Coordination implementation worktree:

```sh
ruby docs/myos-blue-coordination/review/post-remediation-review-2026-09-06/contracts/run-termination-gas-sweep.rb
ruby docs/myos-blue-coordination/review/post-remediation-review-2026-09-06/contracts/run-bex-cyclic-schema.rb
ruby docs/myos-blue-coordination/review/post-remediation-review-2026-09-06/contracts/run-compute-cyclic-schema.rb
```

The launchers read the retained actual PG7 worker classpath from
`/Users/kamil/Documents/Projects/Blue/myos-simple/durable-library-smoke/build/readiness-evidence/final-r1-r5-seven-20260906-eOCSTM/worker-classpath.txt`.
All nine included JARs matched the parent's frozen final gate manifest when
that handshake was archived. The diagnostic sources alone are compiled into
the ignored `compiled` subdirectory; no library outputs are rebuilt.
