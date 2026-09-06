# Final implementation review — 2026-09-06

> Reviewed: Language / Contracts, BEX, Coordination, MyOS Simple durable foundation,
> their integration boundary, tests and current design documentation.
> **Outcome: five confirmed P1 findings remain open. This is not a clean readiness sign-off.**
> This review changes documentation only; it does not implement the proposed repairs or begin Phase3.

## Executive conclusion

The selected architecture remains a useful POC foundation: database-independent computation,
authenticated source histories, independent observers, genuinely coupled atomic groups, and a
durable host. I found no reason here to replace that model. However, the implementation still has
two reproducible violations of its central cache/schedule-independence requirement, one authored
failure-classification gap, and two host liveness defects.

The most important common cause is **asymmetric producer admission**. Restoring a source result
checks its original context, while computing an independent source inside another document's
attempt can bypass that context. Checking more receipt fields alone will not fix this. Fresh
execution, historical reconstruction and retained-result substitution need one consistent rule
for the source's independently authorized policy and original attachment choices.

The earlier passing regressions remain real evidence for their executed cases. They do not cover
the combinations below, and therefore do not establish the broader readiness claim. Keep the
three-phase plan, repair R1–R5 before accepting the affected Phase3 paths, and measure the separate
depth/scaling concerns during the POC. No new specification profile, migration mechanism, gas
tariff or general interpreter is proposed.

## 1. Exact scope and evidence

All four remediation commits were committed, pushed, and checked against the remote branch heads
before this review was packaged. Later documentation-only commits on Coordination do not change
the implementation inspected here.

| Repository | Branch | Reviewed implementation commit |
|---|---|---|
| Language / Contracts | `codex/coordination-external-state-poc` | `7dd28cbe917688b1bfb16e7ca9488a5f6a085389` |
| BEX | `codex/coordination-external-state-poc` | `95ba24fa9bf78930daa690ac1885214b0a4da8a9` |
| Coordination | `codex/coordination-external-state-poc` | `b08b8e2f9d08e185cc7eb3500d2199d4f71921cc` |
| MyOS Simple | `feat/coordination-with-external-state` | `701f70b2aaba3c796a047648551a9fecd5bd915f` |

The review covered the implementation relative to the libraries' `next` and MyOS's `main`
baselines, not only the last remediation diff. Parallel code-review tracks covered the libraries,
Coordination, and host; the coordinating reviewer checked cross-layer contracts, documentation,
the evidence, and independently repeated the library counterexamples and depth probe.

New diagnostics used the current built artifacts and existing compiled test fixtures, without
rebuilding repositories or rerunning all suites. Host probes used real PostgreSQL 17.10 and actual
store methods in an isolated, subsequently removed schema. R4 uses controlled SQL scheduling;
R5 does not modify the tested SQL. Neither host probe is a general Coordination application test.

Runnable Java witnesses, captured output, commands and limitations are in the separate
[evidence directory](final-implementation-review-2026-09-06/README.md).
They demonstrate defects, not passing acceptance tests. The pre-existing untracked MyOS example
`src/main/resources/examples/managed/multiplicity/request.yaml` was left untouched and excluded
from commits. Raw build/test archives and database files were not published.

## 2. Findings at a glance

P1 means a correctness or automatic-progress defect to repair before accepting the dependent
integration path. It does not mean that every workload fails. R6 is a separate P2 scale-readiness
issue, not a newly introduced semantic regression.

| ID | Priority / owner | Demonstrated consequence | Evidence |
|---|---|---|---|
| R1 | P1 · Coordination / Contracts | A source succeeds under an observer's larger gas budget when its authentic operation fails. Cache availability changes the result. | Actual Core execution; fresh/cached comparison. |
| R2 | P1 · Coordination / Contracts | Historical reconstruction through A changes X's original frontier choice; X observes 5 instead of 0. | Actual history execution; original and cached controls. |
| R3 | P1 · Language / BEX boundary | Pure malformed computed schema repeatedly escapes as an operational failure instead of settling the operation. | Actual Compute → BEX → Contracts → Core. |
| R4 | P1 · MyOS recovery | A retention/recovery race leaves a valid retained attempt outside automatic reconciliation. | Instrumented real PostgreSQL schedule. |
| R5 | P1 · MyOS maintenance | One capacity-blocked prefix prevents an independent healthy root from materializing work. | Unmodified store APIs, three cold attempts. |
| R6 | P2 · Contracts graph traversal | Deep acyclic graphs overflow the JVM stack despite having only singleton components. | Direct partitioner probe; pre-existing recursion. |

## 3. Confirmed implementation defects

### R1 — Fresh source execution can use the observer's gas policy

**Given:** Source S has its own authorized external-operation limit of 5,000. Its initialization
has already succeeded under that same policy, using 1,250 gas. Observer A has a larger limit of
100,000. Both paths use the same exact source predecessor, complete Timeline cut and E15 input.
The evidence explicitly names S's expected producer basis; this is not a missing-policy default.

**When / actual:**

1. Execute S independently: its expensive computation produces `GAS_LIMIT_EXCEEDED`, gas 5,000.
2. Evaluate A without that retained source result: the fresh S group instead succeeds at gas 9,859,
   using A's policy, followed by A's successful operation at gas 348.
3. Evaluate the same A cut with the authentic retained S failure: Core returns `MetadataProgress`.

The source's status, operation identity, logical gas and successful history now depend on whether
its result was materialized. This is a real violation of the selected model, not an optimization
tradeoff or a requirement that every independent document use the same budget.

**Cause:** [Core's same-origin call][core-call] supplies the consumer invocation to
[fresh-group construction][fresh-policy]. [Producer-context verification][source-context] is used
for retained substitutions, but the independently supplied expected basis does not prevent a
fresh producer from executing under the wrong policy.

**Required correction:** establish the source's authorized context before either branch. Execute
fresh under that context, or return a named need for separately prepared source authority/result
when the current call cannot do so. Do not silently replace a producer policy with the consumer's,
and do not prohibit the legitimate different-budget retained-result case.

**Acceptance:** compare complete source and observer outcomes across fresh, retained-success,
retained-failure and cold-restored paths; equal and unequal producer/consumer limits; and explicit
missing or mismatched authority. Require equal histories, identities, gas and terminal disposition
for the same logical inputs. A genuine joined group must still obey its separate common-policy
admission rule.

### R2 — Reconstructing a parent can omit a descendant's original attachment choices

**Given:** A embeds X. X's own original E30 input creates an attachment to Y with
`FROM_FRONTIER(F5)`. Y's counter is 0 at F5 and 5 at its later head. A and X have separate original
input admissions. The witness includes complete E10/E30 prefixes and consumes the earlier E10
metadata in both histories before E30.

**When / actual:**

1. Reconstruct X from its own original admission: X succeeds with counter 0, gas 832,
   operation `555bc14f…`.
2. Reconstruct A with A's legitimate original empty attachment admission, without X's result:
   Core freshly executes independent X without requiring X's original admission. X succeeds with
   counter 5, gas 852, operation `02aa5e75…`.
3. Supply the authentic X result to the same A reconstruction: the cached path consumes
   `555bc14f…` and produces only A's group.

This is not a disagreement over FULL_HISTORY versus FROM_FRONTIER semantics. An already selected
source input is reconstructed differently depending on the route used to obtain it.

**Cause:** [original-admission verification][original-admission] covers the requested source's
original owned group, then its attachment policy reaches [execution of all fresh groups][core-call].
Independent descendants are correctly not declared co-owners, but are consequently left without
their own equivalent original-admission gate.

**Required correction:** before executing each fresh independent producer, obtain and authenticate
that producer's original input context, including explicit empty selections. Missing original
authority must suspend; a parent admission or current read view cannot supply replacement choices.
Do not expand the parent's admission to all reverse observers or make the source depend on them.

**Acceptance:** standalone X versus X reconstructed through A, with fresh/warm/cold receipts;
FULL_HISTORY, FROM_FRONTIER and FROM_* alternatives supported by the API; nonempty and explicit
empty original selections; missing root versus missing record; and a deeper chain where the
missing authority belongs to a grandchild. Compare source results as well as final parent state.

**Relationship to R1:** implement one source-authority decision shared by fresh execution and
substitution. The two witnesses must stay separate: R1 fails with distinct budgets; R2 fails even
with equal budgets and concerns a different logical input field.

### R3 — Invalid computed schema scalars become permanently retrying operational failures

**Given:** A valid initialized document accepts a Timeline entry. Its actual Compute first buffers
`counter = 99`, then dynamically constructs an event whose schema has a malformed scalar value.
The executed keys are `required`, `uniqueItems`, `minItems`, `maxLength` and `minimum`, each set to
the Text value `"bogus"`.

**Actual:** all five cases escape `UnclassifiedProcessingException`, and identical retries repeat
the failure. Boolean getters produce generic `IllegalArgumentException` through structural-key
construction; count/numeric getters do so during canonicalization. No terminal prepared operation
is returned. There is no injected provider outage, cancellation or implementation fault here:
the defect is caused entirely by authored output.

**Expected:** a typed deterministic authored-output failure, settled according to the selected
operation's runtime-gas rule, with its buffered patch rolled back and no invalid event published.
The existing malformed-BlueId integration control demonstrates that intended behavior and passes
on these same artifacts.

**Cause:** [BEX's schema writer][schema-writer] accepts the converted value without the necessary
keyword-kind validation. Language's schema getter/propagation path still emits generic conversion
exceptions. They reach [the structural-key boundary][output-key] or
[the narrowed failure classifier][output-classifier]. Narrowing unknown-fault classification was
correct, but conversion of the actual pure authored-error producers is incomplete.

**Required correction:** validate scalar kind, exactness and applicable numeric constraints at
the owning pure schema-conversion boundaries and emit typed semantic errors. Do not restore a
blanket `IllegalArgumentException`/`ArithmeticException` catch: real host faults must remain
operational and nonconsuming.

**Acceptance:** a table over the supported schema vocabulary: valid scalar, wrong kind, applicable
range violation, fractional count, nested schema and legal exact-reference controls. Execute
representative rows through real Compute, asserting gas, rollback, no invalid output, repeated
deterministic disposition and progress to the next input. Retain cancellation/provider/unknown
fault negative controls that must not become semantic failures.

### R4 — Lease recovery can orphan a retained attempt across generations

**Given:** A worker holds the work-row lock while retaining a prepared plan. Its lease expires.
Recovery checks for an expired lease with no retained plan. Retention inserts plan records but
does not update the work tuple.

**Controlled schedule:** recovery establishes its statement snapshot before retention commits,
then acquires the work-row lock after that commit. The statement still sees no retained plan.
It requeues the work and increments its generation.

**Observed durable result:** work is generation 2 / READY, while its noninvalidated `plan_work`
remains generation 1. Retrying the same CommitKey fails with
`Earlier attempt requires reconciliation`. The generation-joined automatic reconciliation query
finds zero candidates. Explicit reconciliation with the known old CommitKey repairs the fixture;
this is an automatic-recovery liveness defect, not demonstrated irreversible loss or double commit.

**Cause:** [recovery's eligibility check and lock][lease-recovery] share one statement, while
[retention][retain] only inserts plan rows under the work lock.
[Automatic recovery][automatic-recovery] subsequently joins matching generations.
PostgreSQL Read Committed statements have their own snapshots; obtaining a row lock does not
give all related-table reads a new statement snapshot.
[PostgreSQL 17 transaction isolation](https://www.postgresql.org/docs/17/transaction-iso.html#XACT-READ-COMMITTED).

**Evidence qualification:** the probe uses the actual methods and preserves every original SQL
predicate, including `FOR UPDATE SKIP LOCKED`. A semantically true advisory-lock InitPlan controls
the narrow snapshot-before-commit / row-lock-after-commit schedule. This is not an uninstrumented
stress reproduction. A test that merely blocks recovery on the retained work row would be wrong:
`SKIP LOCKED` skips that row. The identified risk is the different schedule described above.

**Required correction:** lock the candidate work row, then recheck retained-plan eligibility in a
separate statement with a fresh snapshot while retaining that lock, or provide an equivalently
sound atomic protocol. Retention and recovery must synchronize on the same work authority.

**Acceptance:** retain-before-recovery, recovery-before-retain, the controlled snapshot handoff,
SKIP LOCKED contention, and a fresh host restart. Every surviving retained attempt must either
commit or be discoverably reconciled; retry must not remain stranded on an undiscoverable plan.

### R5 — A blocked prefix monopolizes maintenance ahead of healthy roots

**Given:** P is the oldest ready-prefix queue root. Its proof requires 2,203 bytes. Independent Q's
proof requires 1,213 bytes and fits the configured capacity of 1,229 bytes.

**Actual:** direct cold reading of Q succeeds. Three cold `materializeReady(1,1)` calls nevertheless
select P and throw a capacity `Hold`. Neither root's cursor or scheduling turn changes, and Q's
work is never created. A larger root batch does not isolate the failure: throwing on P exits the
loop before Q.

**Cause:** [root selection and proof/payload loading][prefix-ready] happen before the transaction
that advances the service turn. The operational failure is neither recorded as a root-local hold
nor isolated from unrelated roots. It also aborts later maintenance families in
[the host recovery sequence][maintenance-sequence].

The READY path was executed through unmodified storage APIs with real committed prefix authority.
Wait, fanout and temporal materializers have analogous pre-turn loading paths; those analogous
failure cases were inspected, not executed. No semantic corruption was observed.

**Required correction:** retain a root-local operational hold/backoff, rotate or exclude that root
without moving its semantic cursor, and continue bounded service of eligible independent roots.
A capacity repair must resume the same retained authority. Do not reevaluate committed results,
skip an input, or make semantic gas depend on physical payload capacity.

**Acceptance:** oldest blocked P plus healthy Q for each maintenance family; repeated cold starts;
capacity release and exact resumption of P; more than one blocked root; fairness while queues
grow. Verify that a failure in one maintenance family does not indefinitely prevent the others.
The earlier publication-stream capacity fix and fair Timeline maintenance tests concern different
queues and do not cover this case.

## 4. Performance, complexity and remaining integration proof

### R6 — Deep graph traversal needs an operational-depth strategy

The [recursive partitioner][recursive-scc] succeeds for acyclic chains of 1,000, 2,000 and 4,000
members but throws `StackOverflowError` at 8,000 and 10,000 in the default JVM used for the probe.
Each component has one member, so a cyclic-component member cap does not explain this failure.
The precise threshold is JVM/stack-dependent. Existing 10,000-node controls cover independent
vertices or shallow stars, not this depth.

This recursion predates the POC; the newer efficient component ordering repairs another cost.
Prefer an iterative traversal. An explicitly documented, early physical depth/capacity boundary
is a narrower fallback if necessary; it must not be reported as a deterministic semantic failure
or consume the input. Add a deep-chain control before claiming broad deep-nesting support. This
need not block the first small-graph Phase3A path, but it belongs in the Phase3 scale acceptance.

### Other code-observed cost risks, not newly proven failures

- `ClosureExecutionSession` is 6,547 lines at the reviewed commit. Size is not itself a defect,
  but fresh/reconstructed/cached admission paths now demonstrably diverge. Extracting their shared
  authority decision is justified; a broad interpreter rewrite before the POC is not.
- Tentative finalization still clones bodies, rebuilds/partitions graphs and scans bindings.
  Codec transport limits do not by themselves bound the resident capture graph or repeated
  canonicalization cost. Independently decoded capabilities may also repeat subtree work. These
  are profiling targets, not measured throughput regressions in this review.
- Computing Agreement once is a valuable saving; processing reactions in N Orders still requires
  N logical consumers. Measure source commit latency separately from queue drain/fanout cost.
  No constant-time completion promise for 100,000 consumers should be inferred.
- Existing large-table probes establish access paths, not end-to-end throughput, contention
  behavior, memory stability or fair progress under blocked work. Measure rows examined, bytes
  loaded/decoded, source evaluations, queue age, retained evidence size and working-set memory.

### Deliberately unfinished work — not new implementation defects

- F05: authenticated full Timeline descriptor/proof/entry translation into the actual Core
  adapter is Phase3A. The naive legacy demonstrator is not evidence that this path is integrated.
- F07: the complete temporal/history-entitlement selector is Phase3B. The initial high-water
  improvement does not remove all lifetime-occurrence scans. Current-index-only routing would
  still be wrong for historical attachment entitlement.
- General runtime result dispatch, managed imports and SCC result mapping remain Phase3 work.
  The shipped real-library adapter currently covers single-lineage acyclic initialization;
  source-history/failure/import and M1/M2 bridges are test-only.
- Stored joined-plan tests do not yet prove actual Coordination SCC execution through the general
  multi-owner PostgreSQL adapter. Conversely, library SCC tests do not establish durable mapping.
- The current source-fence capacity of 1,000 is an explicit practical limit to confront in fan-in
  testing, not proof of a functioning 10,000-input Agreement path. The required completeness rule
  still cannot omit inputs because a graph is large.

## 5. Coverage assessment and highest-value additions

| Invariant / boundary | Existing evidence worth retaining | Missing combination to add |
|---|---|---|
| Independent source identity, history and gas | Canonical history, source receipts, different-policy retained controls | R1/R2: original producer context on every fresh and reconstructed route. |
| Exact ordering and observation positions | Chronology, source observation programs, lane continuity, co-owned projection controls | General host adapter with delayed/cold multi-source execution and actual SCC mapping. |
| Terminal semantic failure versus operational wait | Gas rollback, malformed BlueId, unknown-fault tests | R3: complete schema-value families through actual Compute; no accidental fault reclassification. |
| Atomic durability and retry identity | Fences, retained plans, reconcile, ambiguous-commit and restart controls | R4: concurrent retention versus recovery statement snapshots. |
| Independent work can progress | Healthy-root rotation, Timeline turns, publication retry | R5: capacity/poison root isolation before service-turn update. |
| Graph scale and bounded physical work | Large shallow graphs and SQL access-path probes | R6 deep chains; lazy large bodies; mixed hot/cold DAGs; saturated fanout queues. |
| Database-independent library API | Architecture guards and actual API reference compilation | Keep this boundary during fixes; PostgreSQL tests remain exclusively host integration. |

For each new scenario retain the short card: purpose, Given/When, expected Core result, durable
post-state, forbidden results, fault/scheduling overlays and measured budgets. Prefer pairs that
must agree under a transformation—cold versus warm, standalone source versus observed source,
pre-restart versus post-restart—over numerous unrelated example documents. Use tutorial/MyOS
examples where they exercise these properties, not as a substitute for controlled traces.

Existing final-candidate records inspected: 70 external Coordination tests, 161 affected Contracts
tests, 36 guards, 157 BEX tests, four additional original-selection controls, 75 PostgreSQL host
foundation tests and seven actual-library/PostgreSQL smoke tests, all with zero reported
failures/errors/skips. These groups overlap in scope and are not an all-catalog coverage count.
The earlier broader 565-Contracts run belongs to a preceding candidate and is not relabeled as
a full run on the final artifact. This review did not rerun these suites; it added the diagnostic
counterexamples recorded alongside the report.

## 6. What this review does not recommend

- Do not merge all source/observer work into one transaction to hide R1/R2. That would sacrifice
  source independence and the Agreement fanout model without fixing original-input authority.
- Do not require equal budgets for independent EXTERNAL producers and consumers. Enforce the
  originally authorized policy; apply common-policy rules only where semantic coupling requires it.
- Do not convert all generic runtime exceptions into consumable failures. Repair known pure
  authored-error producers while keeping infrastructure and implementation faults nonconsuming.
- Do not use a larger JVM stack, larger host budget or faster retries as the sole correctness
  repair. They can postpone a physical limit but do not establish admission or fairness rules.
- Do not call every lower-layer omission a defect. Managed reaction lane/origin checks were found
  at Coordination's adjacent boundary. Portable-capacity/evidence exceptions are not universally
  required to become gas failures. A speculative schema-reference asymmetry was excluded because
  its owning rule and harmful result were not established.

## 7. Focused next implementation round

1. **R1 + R2 together:** centralize per-producer original context before fresh or retained work.
   Keep both reproductions and their positive controls. This may require extending the source
   context/need boundary, but not replacing the execution model.
2. **R3:** complete the typed authored-schema conversion boundary; run its small full-stack matrix
   alongside the existing operational-fault controls.
3. **R4 + R5 in parallel in MyOS:** repair the retention/recovery protocol and root-local maintenance
   holds; add controlled PostgreSQL races and healthy-root progress tests.
4. Run focused tests while editing, then one affected library regression and one host regression
   on the stable candidate. Refresh the actual-library/PostgreSQL handshake against those exact
   artifacts once. Do not repeatedly run all repositories' suites after each local change.
5. Record the new exact commits and evidence, then proceed with Phase3A/3B/3C/3D as planned.
   Put R6 and measured graph/capture/fanout costs in the explicit scale work. Expect further
   evidence-based iteration after the general adapter exists.

This is a bounded repair round with concrete exit cases, not another open-ended design freeze.
The review establishes neither absence of all remaining defects nor production readiness.

[core-call]: https://github.com/bluecontract/blue-coordination-java/blob/b08b8e2f9d08e185cc7eb3500d2199d4f71921cc/src/main/java/blue/coordination/external/CoordinationCore.java#L695
[original-admission]: https://github.com/bluecontract/blue-coordination-java/blob/b08b8e2f9d08e185cc7eb3500d2199d4f71921cc/src/main/java/blue/coordination/external/CoordinationCore.java#L565
[fresh-policy]: https://github.com/bluecontract/blue-language-java/blob/7dd28cbe917688b1bfb16e7ca9488a5f6a085389/blue-contracts-core/src/main/java/blue/language/processor/closure/ClosureExecutionSession.java#L252
[source-context]: https://github.com/bluecontract/blue-language-java/blob/7dd28cbe917688b1bfb16e7ca9488a5f6a085389/blue-contracts-core/src/main/java/blue/language/processor/closure/ClosureExecutionSession.java#L525
[schema-writer]: https://github.com/bluecontract/blue-bex-java/blob/95ba24fa9bf78930daa690ac1885214b0a4da8a9/blue-bex-core/src/main/java/blue/bex/value/BexBlueNodeWriter.java#L385
[output-key]: https://github.com/bluecontract/blue-language-java/blob/7dd28cbe917688b1bfb16e7ca9488a5f6a085389/blue-contracts-core/src/main/java/blue/language/processor/SemanticOutputBoundary.java#L103
[output-classifier]: https://github.com/bluecontract/blue-language-java/blob/7dd28cbe917688b1bfb16e7ca9488a5f6a085389/blue-contracts-core/src/main/java/blue/language/processor/SemanticOutputBoundary.java#L128
[lease-recovery]: https://github.com/bluecontract/myos-simple/blob/701f70b2aaba3c796a047648551a9fecd5bd915f/src/main/java/blue/myos/mini/durable/PostgresWorkStore.java#L193
[retain]: https://github.com/bluecontract/myos-simple/blob/701f70b2aaba3c796a047648551a9fecd5bd915f/src/main/java/blue/myos/mini/durable/PostgresCommitStore.java#L64
[automatic-recovery]: https://github.com/bluecontract/myos-simple/blob/701f70b2aaba3c796a047648551a9fecd5bd915f/src/main/java/blue/myos/mini/durable/DurableHost.java#L69
[prefix-ready]: https://github.com/bluecontract/myos-simple/blob/701f70b2aaba3c796a047648551a9fecd5bd915f/src/main/java/blue/myos/mini/durable/PostgresPrefixStore.java#L343
[maintenance-sequence]: https://github.com/bluecontract/myos-simple/blob/701f70b2aaba3c796a047648551a9fecd5bd915f/src/main/java/blue/myos/mini/durable/DurableHost.java#L76
[recursive-scc]: https://github.com/bluecontract/blue-language-java/blob/7dd28cbe917688b1bfb16e7ca9488a5f6a085389/blue-contracts-core/src/main/java/blue/language/processor/closure/SccPartitioner.java#L123
