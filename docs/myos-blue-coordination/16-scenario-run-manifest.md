# Scenario run manifest and report contract

> **Status:** REQUIRED BEFORE EVERY PHASE ACCEPTANCE GATE · **Revision:** 15.12  
> [Scenarios](09-scenarios.md) · [Delivery plan](10-poc-delivery-plan.md) · [Validation plan](11-validation-plan.md) · [Review gates](12-risks-and-review-gates.md)

## Purpose

This contract applies to formal scenario acceptance/calibration runs, not every library or host test.
Phase1/2 readiness used archived real test outputs and reviewed fixtures as recorded in
[the readiness report](implementation/phase-1-2-readiness.md); full catalog manifests/reports were not
produced and their formal gates are not claimed. The reporting/preflight protocol below remains a
Phase3 tooling contract, not an existing Java runtime API. The reviewed scenario snapshot must
include that causal model, the selected reference grouping/chronology rules and the concrete host
protocol. A schema-valid manifest is not evidence that those rules have been implemented or proved.

The target boundary is [18](18-independent-lineage-processing.md): source commits once, consumers
apply exact receipts independently, and discovery closes with semantic membership/completeness
proof. The baseline all-connected library boundary is change evidence, not the target oracle. Schedule
IDs retain historical names; COHORT/PARTITION/DOMAIN in an ID does not preserve obsolete connected
atomicity or global serialization.
Use [22](22-processing-kernel.md) for the selected algorithm and deliberate owning-library changes,
and [21](21-semantic-equivalence-and-source-reuse.md) for retained equivalence/source-reuse laws.
A required positive is not silently replaced by a protective hold.

The scenario catalog says what must be proved. A `ScenarioRunManifest` freezes the exact executions
chosen to prove it, and a separate `ScenarioRunReport` records what happened. This prevents a result
from changing its own test plan or silently omitting an inconvenient row.

For every decisive Phase 1, 2, or 3 run, and for the separate performance-calibration run, create:

```text
evidence/<run-id>/scenario-run-manifest.json   # before the first SUT action
evidence/<run-id>/scenario-run-report.json     # once, after the run stops
```

Both files are RFC 8785 canonical JSON and are create-once. Changing a fixture, row, seed, threshold,
environment, or artifact creates a new run ID. An interrupted run still gets a report: completed
evidence remains and every unexecuted row is `NOT_RUN`.

The normative schemas are:

- [`scenario-run-manifest.schema.json`](review/scenario-run-manifest.schema.json)
- [`scenario-run-report.schema.json`](review/scenario-run-report.schema.json)
- [`semantic-configuration.schema.json`](review/semantic-configuration.schema.json)

`manifestIdentity` is lowercase SHA-256 of the canonical manifest with only
`manifestIdentity` omitted. `reportIdentity` follows the same rule. The report separately stores
`manifestFileSha256`, the byte hash of the complete canonical manifest file including its identity.
The validator recomputes both values; it never treats a self-reported digest as proof.

## The deliberately small evidence model

Each manifest has one flat `artifacts` map:

```json
"fixture.order": {
  "sha256": "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
  "path": "evidence/run-42/inputs/order.json"
}
```

Every manifest artifact is an immutable input: fixture, expected result, semantic configuration,
reviewed scenario snapshot, build description, dirty patch, database snapshot, or measurement
protocol. Every report artifact is an observed result or raw evidence file. Keys are unique and all
paths are unique and normalized beneath `evidence/<run-id>/`; hashes are recomputed from disk. The only allowed
cross-run paths are the two files in a performance-acceptance `calibration` link. There is no package
classifier, transitive artifact index, sealed catalog, signature chain, or CI sequence protocol.

The manifest header fixes:

- run ID, creation time, approver, review reference, run kind, phase, and owned gates;
- exact Coordination, Language, BEX, MyOS Mini, and `myos-java` commits;
- a patch artifact for every dirty component and null for every clean component;
- a build artifact covering resolved dependencies, build flags, applicable DDL, and patch order;
- the semantic-configuration identity/artifact and one content-hashed snapshot of the reviewed
  scenario and validation documents;
- OS, CPU, RAM, JDK, JVM flags, JVM count, and the complete positive operational-budget record
  used by the run harness (not a runtime class named `OperationalBudget`): rows, read bytes,
  materialized bytes, materialized application definitions, read calls,
  evidence rounds, and attempt nanoseconds. `maxMaterializedApplicationDefinitions` is a positive
  per-page materialization limit, not a total-consumer limit. Scaling fixtures preregister a cap
  at least 101 and at most 2,147,483,646 for distinct 100/cap/cap+1 cells; Phase 2/3
  also fix disk, PostgreSQL, pool size, and cache budget;
- exact datasets, overlay factors/rows, failpoints, mandatory schedules, exclusions, and retention;
- every planned scenario row; and
- M0/M1 performance data only for performance runs.

All artifact keys and row references must resolve exactly once. Dataset IDs, overlay IDs, failpoint
IDs, row IDs, budget IDs within a row, and exclusion schedule IDs are unique. Missing, dangling, or
duplicate references fail preflight.

## Semantic configuration

`semanticConfigurationArtifactKey` resolves to canonical JSON conforming to the semantic-
configuration schema. Its byte hash equals `semanticConfigurationArtifactSha256` and the hash on
that artifact-map entry. This file hash is deliberately distinct from
`semanticConfigurationIdentity`, the `sha256:…` identity of the proposed manifest configuration
preimage. `SemanticConfiguration` was a reference-sketch name, not an implemented Java constructor.
The artifact carries both identities and every field needed to reconstruct
that preimage: the current Contracts `ClosureEnvironment`, canonical journal domain, trusted
Timeline authorization universe, identity-bearing external-input disposition policy, and the fixed
canonical source initialization execution policy. Exact operation-relevant Timeline membership is
per-operation evidence, not an exhaustive bootstrap list in this configuration. `configurationSchema`
is artifact metadata and is not silently added to that configuration preimage. The actual runtime
boundary uses `CoordinationCore(DocumentProcessor, ClosureEnvironment, ExecutionPolicy)` plus exact
per-operation evidence; see [05](05-poc-api.md). The harness must map and cross-check this artifact
against those real owning inputs, never substitute its envelope hash for an owning identity.

The environment has exactly the current eleven dependencies: Language specification, Contracts
specification, runtime registry, gas manifest, managed-document identity policy, managed-binding
policy, exact-node-provider domain, external-order policy, portable-limit policy, cyclic finalizer,
and cyclic-proof verifier. For every policy/domain member the artifact carries the same canonical
bytes and identity of the corresponding actual `ClosureEnvironment` member (`FrozenSemanticEnvironment`
was a sketch name); it also carries `environmentIdentity` and
`canonicalClosureEnvironment`. Preflight standard-Base64 decodes each canonical byte field,
re-encodes it byte-for-byte, verifies each member identity, parses the portable-limit projection,
cross-checks every structured field against `canonicalClosureEnvironment`, and recomputes
`environmentIdentity`. A label, local fingerprint, JSON-file hash, or duplicated subset is not a
substitute.

`externalInputDispositionPolicy` contains exactly one canonical, enum-ordered entry for every current
Contracts `ProcessorStatus`: `SUCCESS`, `NO_MATCH`, `STALE`, `TERMINATED`,
`INVALID_PROCESSING_DOCUMENT`, `CAPABILITY_FAILURE`, `RUNTIME_FATAL`, `GAS_LIMIT_EXCEEDED`,
`PORTABLE_LIMIT_EXCEEDED`, and `SUBSCRIPTION_SURFACE_INVALID`. Each maps to `CONSUME_EXTERNAL_INPUT` or
`BLOCK_EXTERNAL_INPUT`; `SUCCESS` is fixed to `CONSUME_EXTERNAL_INPUT`, while
`SUBSCRIPTION_SURFACE_INVALID` is fixed to `BLOCK_EXTERNAL_INPUT`. The
schema can represent other non-success mappings, but the implemented Core uses fixed disposition
laws, not a configurable policy table. A run against this implementation must match those exact laws;
a schema-valid alternative is not supported merely because the artifact can encode it. Missing,
duplicate, reordered, unknown, or runtime-mismatching status mapping fails preflight. `canonicalPolicy` is not an
independent authority: it is the exact canonical encoding of `canonicalStatusDispositions`.
Its bytes are standard padded Base64; decode and standard-padded re-encode must reproduce the text
exactly, decoding must reconstruct exactly that structured ten-entry mapping, and `policyIdentity`
is recomputed from those bytes. A disagreeing structured/byte pair fails preflight.
Operational conditions never appear in this policy. It governs the owning lineage's original
external input, including historical reconstruction of that same input, not arbitrary progress
after any operation. Consume means terminally account for that input, not fabricate successful
business effects, a new epoch or a successful imported-source cursor. Source/sibling independence
and aggregate failure accounting remain unchanged.

`sourceInitializationExecutionPolicy` records the actual frozen `ExecutionPolicy` input in the
manifest's descriptive schema (the old sketch called this `FrozenExecutionPolicy`):
`{assertedPolicyIdentity, canonicalExecutionPolicy}`. Standard-Base64 decoding and re-encoding must
reproduce the canonical bytes; parse them as the current complete Contracts execution policy,
recompute its identity and verify its gas-manifest binding against the environment. This policy is
fixed for canonical source-local initialization under FULL_HISTORY, not selected from the worker,
promoting parent, attachment policy or physical cache state. [22](22-processing-kernel.md#5-canonical-origin-source-history-is-not-born-at-the-first-order)
selects separate fixed gas scopes for canonical initialization and parent consumption, including
on a cold host: source80 and parent20 with each limit90 pass cold and warm. The source settlement
occurs once when its canonical result becomes authoritative; physical rebuild does not charge it
again. Parent import/reaction charges remain identical cold/warm. This is an explicit change from
old creator-combined gas, not a claim of equality to that older boundary. Candidates alone confer
no publication or gas-settlement authority; runtime conformance still requires implementation proof.

The operation kind fixes the permitted projection; it is verified with the selected action/result,
not inferred from a status name or from whether the host happens to be replaying history:

| Operation kind | Terminal non-success progress |
|---|---|
| `EXTERNAL_INPUT` | Apply the frozen external disposition. A consumed failed input retains rollback/gas/diagnostic evidence without claiming business-state/checkpoint/epoch advance. Later valid inputs may run. |
| `HISTORICAL_EXTERNAL_REPLAY` | Reconstruct the same original external terminal-input law at its historical position; do not turn a handled external gas failure into a permanently pending managed import merely because execution is historical. |
| `MANAGED_RECEIPT_IMPORT` | Selected owning-library extension for `GAS_LIMIT_EXCEEDED` and library-certified deterministic `RUNTIME_FATAL`: preserve successful observed-source view/epoch and record the exact terminal delivery. This includes certified `AtomicScopeGasAdmissionFailure`, not an arbitrary exception or reason string. The next operation first aligns the actual successful pin with its exact source before-view, then interprets its authenticated observation program. Consumer0 after failed r1 therefore observes alignment0→1 followed by r2's1→2, with original r2 event payload unchanged. Alignment has ordinary update/lifecycle/gas effects and rolls back with r2 on failure; no failed r1 events or forged source receipt. Other statuses retain their exact operation-kind laws. |
| `INITIALIZATION` | Canonical source-local initialization and FULL_HISTORY basis are independent of the promoting parent and physical admission time. Fixed source gas and parent-consumption gas are separate. Preparation grants no authority; successful promotion publishes the complete required candidate set atomically with its introducing result, while failure/create-then-retire publishes no orphan. A terminal failure is not a usable initialized source. Already authoritative X survives unrelated parent failure. |
| Authenticated representation-only update | The specified exact same-epoch representation may change; no new business receipt/event/fan-out or handled-input advance. This is not an unauthenticated state-only import. |
| Operational wait/hold | No terminal semantic input settlement or cursor advance. |

Corrective work is checked separately against its real scope, predecessor and occurrence lifecycle.
The source-chain validation rule is neither a permanent-death rule after a certified terminal failed delivery
nor permission to skip unhandled work. Imported-receipt failure cannot invoke the external disposition
mapping. If r1/r2 both exceed gas, D@T30 may run after both terminal gas dispositions; it cannot cancel owed T20
retroactively. Missing data stays an operational wait. Normal successful catch-up is not coalesced.
The corresponding runtime-fatal controls require complete owning-library status/reason, input,
result and admitted-gas authority. Timeout, cancellation, unknown commit and unclassified exceptions
must not consume an import. The same reason text without that authority is rejected.
These action-kind/projection joins are semantic validator requirements; this configuration schema
does not pretend to validate processor results it does not contain.

Finally, the planned preflight reconstructs the manifest's canonical configuration projection from the
verified environment, domain, trusted Timeline universe, external-input policy and source initialization execution policy, recomputes
`configurationIdentity` while excluding only that identity
field, and requires equality with both the artifact's `configurationIdentity` and the manifest's
`semanticConfigurationIdentity`. Thus the artifact hash proves the evidence file while the
configuration identity binds the declared run inputs; actual work retains its owning-library identities.
Neither file nor envelope identity is a surrogate for runtime invocation/result authority.

The artifact uses stable descriptive names: `canonicalJournalDomainId` is the harness's journal-domain
field; `trustedTimelineUniverse.universeIdentity` and `.canonicalAuthorizationPolicy` describe the
host's authorization inputs. These are manifest fields, not calls to sketch-only Java classes. Preflight
standard-Base64 decodes/re-encodes the policy, validates its supported provider/type/exact-identity and
scope authorization rules, and recomputes the declared canonical policy/projection identity while
cross-checking the actual runtime authorization inputs. It is not a concrete
Timeline roster or a completeness proof. New authorized same-provider Timelines require no reset.

Each selected operation separately binds exact directed relevant Timeline membership, its topology/
lifecycle basis and accepted complete normalized prefixes. Verify canonical members, derivation and
coverage against that basis; reject a stale/smaller set after a relevant topology change, or a whole-
domain substitute introducing unrelated observer barriers. Shared indexed frontier aggregates may
reference accepted immutable member evidence without copying the full vector per operation, but may
never overstate completeness or omit a relevant member. These are explicit representation mappings,
not extra configuration fields. The encoder must use the reviewed exact constructor preimage, not hash
the artifact's property names as though it were that constructor. Host semantic revisions and SQL
fences are excluded entirely. Artifact `/1` labels name this local evidence format, not alternative
Contracts/Coordination specification profiles.

Every semantic-configuration integer, including portable limits, is a
canonical JSON integer in `0..9007199254740991`. A Java `long` is only storage and does not widen
that configuration domain. Decimal strings, fractions, exponent spellings, negative configuration
values, and values above the safe integer maximum fail canonical validation. Constructor-specific
fixture integers retain their own Contracts domains; for example, the permitted managed-revision
transition from epoch `-1` to `0` remains a required positive cause vector.

Operation-relevant Timeline members are canonical Blue Language Base58 text, duplicate-free, and sorted by Unicode
code-point order—equivalently unsigned UTF-8 for Base58 ASCII. Equal-microsecond entries are ordered
by `(timestampMicros, exactTimelineEntryBlueId)` using that text order, never decoded digest bytes,
provider data, arrival order, SQL collation, or worker. The required adversarial pair is:

```text
12a9cENnvCr1nkS83tJfY5mVaDWYufpycEXYCniEGSVL
12acoogmnJbwnf6do3MRmxzuFmR6W6QWEknpGqFCbr1
```

Text order places the first value first; decoded-byte order reverses it.

The current Contracts gas manifest is the only tariff authority. The POC adds no route tariff.
`closureWorkOccurrenceEnqueued` is admitted once; the host neither re-prices nor double-charges it.
Contracts then orders DEQUEUE, delivery, semantic, and runtime charges. The exact Contracts execution
policy is part of each fixture that invokes Contracts and its gas-manifest identity must match this
semantic configuration.

## Planned scenario rows

Every `rows[]` item is a short executable scenario card:

- `rowId`, owning A–T `scenarioId`, owning gates, goal, and a readable Given/When summary;
- one exact `scheduleId` and one content-hashed Given/When `fixtureArtifactKey`;
- `subcaseId` for one of the nine required refinements below, bound to its existing schedule;
- readable expected core-result, semantic-trace, and durable-post-state summaries, plus the three
  exact oracle artifacts;
- stable forbidden-outcome IDs defined by the reviewed scenario snapshot;
- dataset IDs, overlay ID, failpoint IDs, and seed;
- complete budget definitions, or an explicit nonempty `budgetNotApplicableReason`; and
- a frozen measurement cell for performance rows, otherwise null.

Every row's `gateIds` is exactly the manifest's ordered `ownedGates` array. Rows do not invent a
schedule-to-gate mapping: all evidence and every approved exclusion in one run apply to every gate
that run owns. Calibration owns no gate, so every calibration row has `gateIds: []`.

The prose A–T card is the owning behavior family; a named schedule is a frozen subcase/overlay of
that card. Neither supplies implicit row fields. Every row repeats all card fields above, including
an unchanged/no-write durable expectation where appropriate. `NONE` is an explicit overlay level,
an empty forbidden set is invalid, and an empty budget set is valid only with a specific reason.

Expected and observed values use the canonical formats defined by the reviewed scenario snapshot.
Comparisons cover value, identity, multiplicity and order—not prose summaries. `oracleKind` is
`HAND_AUTHORED_EXACT`, `EAGER_MANAGED_REFERENCE` or `INDEPENDENT_GRAPH`. Eager/lazy comparisons use
the same authored managed graph, admission/attachment intent, causes, independently derived local
invocation scopes, policies
and environment. Converting ordinary inline content into a new managed lineage is not that oracle.
The reference does not call the Coordination orchestration path under test. Reusing Contracts tests
integration equivalence, not independent engine correctness: small hand-derived traces and current
constructor/gas conformance provide that separate check. Tutorial/MyOS examples must be converted
into explicit rows; their origin never grants a pass.

`overlayFactors` lists the allowed levels. Every overlay supplies exactly one allowed value for every
factor. The selected overlays must cover every allowed factor pair; mandatory adversarial schedules
remain explicit rows and cannot be replaced by pairwise generation. An exclusion names its schedule,
reason, and approver; an exclusion is visible evidence, not an implicit skip.

## Mandatory schedules

The runner does not choose schedule applicability. The following matrix is the complete function
from `(runKind, phase, ownedGates)` to one mandatory-schedule profile. Any tuple absent from the
matrix is invalid. Gate arrays use the shown order; `G5` is an iteration decision, not a run-owned
acceptance gate, and therefore never appears in a manifest.

| `runKind` | `phase` | Exact permitted `ownedGates` | Derived profile |
|---|---|---|---|
| `FUNCTIONAL_ACCEPTANCE` | `PHASE_1` | `[G0]`, `[G1]`, or `[G0,G1]` | `EMPTY` when only `G0`; otherwise `CORE` |
| `FUNCTIONAL_ACCEPTANCE` | `PHASE_2` | `[G2]` | `HOST` |
| `FUNCTIONAL_ACCEPTANCE` | `PHASE_3` | `[G3]` | `INTEGRATED` |
| `PERFORMANCE_CALIBRATION` | `PHASE_3` | `[]` | `PERFORMANCE` |
| `PERFORMANCE_ACCEPTANCE` | `PHASE_3` | `[G4]` | `PERFORMANCE` |

The profiles are closed ordered arrays. The order below is the order of the schedule catalog table
and is part of canonical manifest form; set-equivalent permutations are invalid.

- `EMPTY` is `[]`.
- `CORE` is every catalog ID from `ORDER-EQUAL-MICROSECOND` through
  `RESOURCE-RETRY-EXPIRY`, followed by `DIRECT-SINGLETON-LIVE-PROTOCOL` (39 IDs).
- `HOST` adds the ten catalog IDs between `REALM-NEGATIVE-ISOLATION` and
  `CANONICAL-HOST-TIMESTAMP-ROUNDTRIP` at their catalog positions (49 IDs total).
- `INTEGRATED` is every catalog ID (53 IDs).
- `PERFORMANCE` is, in catalog order, `RESOURCE-SUPPLY-REGISTER-RACE`,
  `EVIDENCE-ONE-DEMAND-PER-ROUND`, `BEX-COMPILE-MISS-STORM`, `OUTBOX-BOUNDED-SUCCESSOR`,
  `APPLICATION-PLAN-A-SCALING`, `SHARED-N-SOURCE-STAGES`, `SHARED-N-FANOUT-FAULT`,
  and `LOCALITY-CONNECTED-DAG`.

The schema's exact ordered `scheduleSet*` arrays enforce these profiles. The new causal trace IDs
are required proofs, not assertions that a particular unimplemented scheduler already passes.
E1a is a smaller intermediate delivery exit, not another G1 profile. Full G1 requires the named CORE
positive feedback/cycle cases; an explicitly unsupported broader case cannot replace one of them.

Consequently, Phase 3 on its own does not imply the integrated profile: `runKind` distinguishes the
functional `G3` run from calibration and `G4` acceptance. Calibration and performance acceptance
use the same mandatory-schedule profile; calibration records observations under its frozen M0
context, while acceptance evaluates the preregistered M1 thresholds. Neither performance run
implicitly owns or awards `G1`, `G2`, or `G3`, and a functional run cannot award `G4`.

`mandatorySchedules` must byte-for-byte represent the derived ordered profile and may contain only
the closed IDs below. Each member is covered either by at least one row whose `scenarioId` equals
the table's owning card or by exactly one approved exclusion, never both. An exclusion remains in
`mandatorySchedules`; it is evidence that the required proof was not run, not a way to alter the
applicability function. In a functional or performance-acceptance run, an exclusion makes every
owned gate `NOT_RUN`, regardless of the other rows. In calibration,
an exclusion makes the calibration incomplete and therefore ineligible as the `calibration` link
of a later acceptance manifest. An `EMPTY` run has no exclusions. Additional non-mandatory rows may
use separately reviewed schedule IDs, but cannot compensate for an excluded mandatory schedule. A
failure-bearing row must reference the exact frozen failpoint and stage that realizes the schedule.

Historical IDs containing ROOT/AGGREGATE/FINALIZATION retain their names. Their required oracle is
complete per-operation/per-obligation accounting and recovery, independently checked by the test
auditor; they do not require a production global terminal receipt or an aggregate finalizer. If an
aggregate status is exposed, the same evidence must prevent premature success. No extra public
Contracts operation is introduced solely to publish that status.

| ID | Owning A–T card | Required proof |
|---|---|---|
| `ORDER-EQUAL-MICROSECOND` | M | named Base58 pair, reverse ingress/pages, silent member and noncommutative result |
| `DIRECT-ELIGIBILITY-ADD` | C | semantic-position registration creates entitled later delivery; exact occurrence surfaces reject omission/add/reorder/swap and stale snapshots; paged discovery is complete without freezing an all-recipient atomic partition |
| `DIRECT-ELIGIBILITY-REMOVE` | G | inverse case; no stale delivery |
| `DISJOINT-PREFIX-EXECUTION-CUT` | C | independent R1/R2 local commits; queued work retains source receipt/local predecessor/occurrence while unrelated writes and restart do not cause global-cut conflicts |
| `NEW-SHARED-CHILD-PARTITION` | C | two parents attach the same authored child, including READY neutral X: initialize/reuse one lineage, independent local gas/failure/commit; no second-attachment rejection merely for sharing |
| `EVENT-BATCH-NESTED-EMIT` | N | exact `Parent(E), Root(E), Root(F)` ordering in the control without earlier reference-update emission G; preserve actual G/incoming-batch/F admission order in the companion variant; kill after one tentative private delivery, discard the uncommitted invocation, and replay its closed input from the beginning |
| `ADMISSION-DURABLE-INELIGIBLE` | A | reservation excludes at/after-cutoff live work; admitted Root and fresh/nested children use exact prospective identity/absence/activation evidence; existing identical lineages use the exact view authorized by the accepted semantic cut, not the latest physical head; forged/stale absence and incomplete creation reject; crash creates no orphan, retry initializes once, and reservation ownership survives restart |
| `ADMISSION-PREPARED-RACE` | A | stale reservation-absence host transition conflicts before semantic preparation |
| `ADMISSION-PUBLISHER-FIRST` | A | distinguish capture before semantic acceptance from retry of an accepted intent: the former may obtain a new proposed snapshot/cut; the latter preserves the accepted cut and history choice for every mode, including `FROM_NOW`, while refreshing only required coverage/registration evidence. Accepted FROM_NOW@T20 followed by source@T25 during retry still selects its exact T20 view; conflicting overlapping authority rejects without silently moving NOW |
| `FULL-HISTORY-INITIAL-SOURCE-INTERLEAVING` | A | initial S=0; S@10=1, Pread@20, S@30=2; admit at C40: an ordinary direct /child/counter read at T20 records 1 independently of any event-maintained shadow counter, and later settles source=2; also nested initial C@10=1 → B.lastSeen=1 reconstructed under cutoff40 → A's T20 read reconstructed under cutoff60 records 1; no future leakage or hiding earlier reconstructed history by admission cutoff alone |
| `FULL-HISTORY-SHARED-ORIGINAL-CAUSE` | A | one original cause addresses source and parent: independently derive canonical direct-seed and caused-work/drain boundaries, multiplicity, gas and IDs under explicit ownership mapping; do not prescribe source-receipts-parent batching or waive common logical laws |
| `INITIAL-VERSUS-DYNAMIC-ATTACHMENT` | A | initially authored relationship versus later Handler attachment remain distinct; B emits CounterChanged(5) at T5: A attaching at T10 records 5 only through that later catch-up, while A attaching at T1 records 5 as a consequence of T5 through its existing relation; direct T3 child read after T1 sees initial 0, independently of the unset shadow counter; the complete ordered original-entry subsequence includes T3 and T7 exactly once in both variants |
| `ADMISSION-OWNED-PREREQUISITES-BEFORE-DEPENDENT-HISTORY` | A | H1 creates M1/M2/nested prerequisites required before dependent H2 reads value=2; also E20 removes or retargets pending B under cutoff40 without importing future B@30 first; scope-correct eligibility and atomic old-occurrence suffix retirement/new-occurrence ownership preserve applicable nested/foreign work; this is not a universal managed-before-history phase law |
| `CAUSAL-DRAIN-ONE-STEP-ADMISSION` | A | FROM_NOW ADMIT success with no descendant and deterministic initial ADMIT failure both begin/end with an absent drain slot, write no absent-to-absent drain transition, and remain serialized by exact fences; failure deletes the reservation, retains the terminal failed workflow/result/receipt, and leaves lineage/Root/drain/readiness/plan/gate/dependency absent |
| `ROOT-PENDING-ACTION-PROJECTION` | E | selected invocation appends one committed prefix entry and exact obligation delta; consume once, retain unresolved work, add every and only owned result obligation, exclude later roots and reject coherent ownership/cursor/fence mutations; root completion requires no unresolved state, not merely an empty queue |
| `CATCHUP-K1-K2-K3` | E | noncommutative exact retained receipts, one invocation/gas/commit per step; one-source/many-consumer and many-source/one-consumer histories progress under exact dependencies and immutable basis plus explicit causal target progress; no arbitrary live-head import, blanket source WORK freeze or same-root readiness exemption |
| `SOURCE-FEEDBACK-FINAL-STEP` | E | apply22's complete directed owned feedback component, exact activation/cause and shared gas; synchronous final feedback cannot be deferred until after a partial source commit; verify all existing-lineage publication projections |
| `SOURCE-FEEDBACK-NONFINAL-STEP` | E | preserve nonfinal synchronous feedback inside the same owned operation, including monotone tentative scope expansion, exact predecessors and gas; only genuinely independent future operations retain separate commits |
| `SAME-ROOT-INTERACTING-COHORTS` | E | R1 attaches S while R2 needs/changes S: local receipt dependencies and legal releasers avoid artificial cycles; shared read-only references do not merge transactions; shared-write/feedback scope explicit |
| `AUTHENTICATED-EVENTLESS-REPLAY` | E | before==after and zero events retain authenticated owning application, complete Contracts result/companion, state/gas and transitive import; forged state-only evidence rejects |
| `SOURCE-RECEIPT-EVENT-PROJECTION` | E | at least two source Root events prove receipt-local ordinal, source-invocation occurrence ordinal, source DocumentId, occurrence identity, event BlueId/exact event and mixed `publicAtSource`; every field mutation/reorder/duplicate rejects and source-public does not imply republication |
| `LIVE-MANAGED-ROOT-FINALIZATION` | E | source commit retains receipt/discovery basis; local consumer commits settle/create obligations; complete semantic discovery plus all entitled work closes aggregate outcome without blocking independent progress |
| `SIBLING-MANAGED-FAILURE-ROOT-WIDE-FINALIZATION` | F | one Order fails locally while source and healthy siblings continue; exact failure ledger prevents aggregate success; local blocker never becomes a source/sibling barrier |
| `AGGREGATE-MULTIPLAN-FAILURE-CLOSURE` | F | three consumer plans with first/middle failure: source/healthy commits survive, only genuine dependents block, exact ownership ledger prevents orphan cleanup and premature aggregate success |
| `DEPENDENCY-BLOCKED-NO-OVERTAKE` | F | local action cannot overtake required failed/missing predecessor; independent source/Order progresses; precise dependency evidence replaces shared-root/global barriers |
| `POLICY-BLOCKED-LIVE-DRAIN-WITHOUT-PENDING-WORK` | K | empty runnable queue cannot erase local failure/blocker or imply aggregate success; unrelated lineages remain eligible |
| `BLOCKED-LIVE-DRAINS-PRIOR-DESCENDANTS` | K | source/healthy prefixes survive local block; discovery/obligations remain accounted for; only dependent suffix waits and aggregate completion never abandons owed work |
| `NO-RECIPIENT-ONE-STEP-DRAIN` | G | complete semantic membership/eligibility evidence proves no delivery; exact no-delivery progress settles once without fake application/gas, while incomplete discovery or range/frontier races cannot imply emptiness |
| `CYCLE-CONTRACTS-TERMINATING` | J | finite `E0: A→B`, then `E1: B→A`, no continuation: preserve `B(E0), A(E1)` inside the required logical workflow's atomic scope/shared gas; restart replays its complete uncommitted invocation with identical history/gas |
| `CYCLE-ZERO-EFFECT` | J | passive same-epoch cyclic representation creates no business receipt echo; contrast actual self/cross-document workflow feedback, which retains one required atomic scope/shared gas and reaches its real limit outcome |
| `CYCLE-BRANCHING` | J | kill during branching caused workflow: discard the complete tentative invocation, not commit partial branches; replay its exact atomic/shared-gas result without a portable private FIFO checkpoint. Only genuinely independent earlier operations keep separate committed prefixes |
| `CROSS-INVOCATION-FEEDBACK-PAUSE` | J | Historical schedule name: pause a sequence of genuinely separately authorized operations, preserving its committed prefix. Never split one core feedback workflow into fresh-budget invocations or replace its real gas outcome with an operational pause. |
| `GAS-BOUNDARY` | J | below, exactly at, and one charge above limit plus counter-overflow rejection |
| `CANONICAL-SEMANTIC-INTEGER` | O | ordinary zero/max accept; `2^53`, undocumented negative, fraction, exponent and quoted decimal reject; field-specific signed Contract declaration orders -1/0/1 accept, while fromEpoch=-1 remains only its documented sentinel; implemented values resist constructor/accessor mutation, equal bytes have equal values/hashes, and null Optional/members, duplicate/noncanonical lists, supplied-identity mismatch and checked overflow reject |
| `SAME-APPLICATION-COMMIT-RACE` | Q | two attempts or replans of one stable application, one terminal receipt/effect; mandatory CORE sequential R1-live/R2-replay/R3-replay proves target-scoped settlement before the host oracle is accepted |
| `ORDERED-LATER-CAUSE` | M | later cause is ineligible without Contracts, then replans after predecessor; an already accepted complete coverage prefix stays valid under stronger provider guarantees and above-cutoff ingress, preserving its cutoff/semantic identities/result across reprepare/restart rather than repeatedly conflicting on monotonic proof growth |
| `RESOURCE-AUTHORITATIVE-NOT-FOUND` | R | provider `NOT_FOUND` remains non-semantic and preserves the barrier |
| `RESOURCE-RETRY-EXPIRY` | L | retry horizon suspends/quarantines; it never skips or terminalizes the cause |
| `REALM-NEGATIVE-ISOLATION` | B | equal IDs across realms and cache prewarm cannot authorize cross-realm work; repeat CORE R1-live/R2-replay/R3-replay target isolation through PostgreSQL, with unchanged retry, evidence growth and overlapping shared-child scopes; add same-domain admission contention without two active reservations |
| `RESOURCE-SUPPLY-REGISTER-RACE` | K | supply before/during/after waiter creation, crash after supply/before notification, restart and stale waiter; durable level-triggered recheck progresses under work fence without resurrecting finished work |
| `INDEX-COHERENT-OMISSION` | K | trusted SQL/index answers compared with raw authoritative state; omit existing matching row and recompute all supplied hashes, require independent completeness audit failure, not a fictitious core cryptographic proof |
| `DOMAIN-SEMANTIC-REVISION-FENCE` | K | per-lineage/occurrence/range/frontier fences reject stale inputs/phantoms; independent Order writes do not conflict globally; ingress/auth/reservation controls separate and host fences outside semantic identity |
| `EVIDENCE-ONE-DEMAND-PER-ROUND` | I | 10/100/1000 data-dependent discoveries; separate host copied/decoded/hashed/envelope/WAL bytes from repeated Contracts verification/execution and physical source calls; bounded retention and no avoidable cumulative host copying; report measured processor amplification instead of claiming all work is linear |
| `BEX-COMPILE-MISS-STORM` | P | one authorized engine-derived complete key compiles once through a BEX loading seam/compiler wrapper under concurrent cold misses; a get/put LRU or compiler-only default key is insufficient; waiter cancellation is isolated, different environment/tenant keys do not alias, and oversize output is not cached |
| `COMMIT-UNKNOWN-BOTH-LOCK-ORDERS` | K | publisher-first, reconciler-first, and stale delayed publisher |
| `OUTBOX-REVERSE-PUBLISH-ACK-LOSS` | K | each local terminal action appends output obligations atomically; per-lineage/causal predecessor keys survive reversed independent commits and ACK loss; no physical global commit ordinal enters semantic identities |
| `OUTBOX-BOUNDED-SUCCESSOR` | K | backlogs 1/100/10000 and continual appends/slow sink; exact successor or bounded page progresses without moving-tail rejection/full-suffix materialization; gap/reverse rejects, retries remain visible, normal new-batch validation is linear |
| `CANONICAL-HOST-TIMESTAMP-ROUNDTRIP` | O | exact safe-range epoch microseconds round-trip through Java/PostgreSQL unchanged; extra nanoseconds, negative/overflow, rounding, time-zone and alternate-text forms reject before operation/CommitKey identity; committedAt remains observational |
| `DIRECT-SINGLETON-LIVE-PROTOCOL` | K | one target's direct PROCESS uses a stable local application identity and canonical predecessor; local success/non-success, no descendants and created outgoing work survive ACK loss without a global plan ordinal or duplicate workflow |
| `APPLICATION-PLAN-A-SCALING` | S | A=1/10/100/cap/cap+1 consumers all progress through bounded definition pages; cap limits materialization, not total fan-out; measure paged definitions/local projections and cumulative control/WAL |
| `SHARED-N-SOURCE-STAGES` | D | N=1/10/100/1000 for LIVE_INDEPENDENT_FANOUT and RETAINED_HISTORY_CATCH_UP at all SOURCE_* cuts: source commits once with receipt/discovery basis; consumers commit independently, no all-parent preload |
| `SHARED-N-FANOUT-FAULT` | D | N=1000, page=1, cold, two JVMs: crash after source commit before discovery and after 17 Order commits; committed prefixes survive, remaining entitled occurrences resume once; slow/unavailable/gas-failing Order never strands healthy work |
| `LOCALITY-CONNECTED-DAG` | N | graph exceeds heap with small local exact views, bounded source receipt/discovery pages and consumer payload loading; independent per-lineage oracle, no all-parent preload |

`DIRECT-ELIGIBILITY-ADD` also requires live fresh/nested-child initialization, existing-identical-
lineage reuse, forged/stale absence, retry/crash and complete terminal-creation projection vectors.
Together with `ADMISSION-DURABLE-INELIGIBLE`, it covers both admission and live subscription changes:
new authorized same-provider Timelines work without stack reset, while genuinely unsupported or
unauthorized subscriptions hold before publication without an invented ProcessorStatus, cleanup or
silent omission. Relevant membership changes invalidate/reselect pending backlog against their exact
topology/lifecycle basis; missing relevant provider coverage stays nonterminal. Include TA-only
Agreement with stale reverse-only TO (Agreement proceeds), then Agreement embedding Order (TO is now
required), and Q's earlier topology change introducing TO before Agreement's later selection. New
same-provider membership is not a trust-universe reconfiguration. Prospective initialization binds
its canonical source operation before the gate; a future-work/plan identity cycle rejects. These are
mandatory fixture variants inside existing schedules, not new specification profiles.

`OUTBOX-REVERSE-PUBLISH-ACK-LOSS` additionally proves acyclic position → message keys → batch
construction, rejects a stale append-tail at semantic commit and foreign predecessors, and reuses
the exact position after CommitUnknown reconciliation. Publisher-side tail extension alone must not
invalidate its immutable successor. Append position is never a rewritten Contracts invocation identity.

### Required revision-15.1 subcases within existing schedules

The 53 schedule IDs and their gate profiles stay unchanged. A row for the following refinement has
its exact `subcaseId`; the schema binds it to `scheduleId`. When that schedule belongs to the run's
mandatory profile, every listed subcase is required. Extra unnamed rows cannot replace it. An
approved exclusion of the owning schedule retains the existing NOT_RUN/incomplete rules; there is
no independent subcase-exclusion mechanism. Multiple rows can cover a subcase's explicit variants.

| `subcaseId` | Existing `scheduleId` | Required execution and controls |
|---|---|---|
| `SOURCE-PROVENANCE-EQUAL-ORDER` | `CATCHUP-K1-K2-K3` | two source receipts retaining E provenance at distinct logical reaction/attachment positions produce consecutive consumer epochs with equal original sourceOrder; lineage-chain continuity is valid. The contrasting receipts due at the same origin and position compose one consumer operation, not two epochs. |
| `SOURCE-PROVENANCE-PAST-ORDER-CUTOFF` | `CATCHUP-K1-K2-K3` | K100-owned import of E10 produces a later consumer revision, possibly with decreasing original sourceOrder; that revision is excluded from cutoff50; forged producing-prefix/selection authority rejects |
| `NEXT-RECEIPT-PAYLOAD-UNAVAILABLE` | `CATCHUP-K1-K2-K3` | M1 Complete with exact M2 identity/header/selection but missing execution body commits once and creates exact next authority; M2 suspends and resumes after restart/resupply without M1 reapplication; changed body rejects; missing selection evidence may legitimately block current commit |
| `SIGNED-CONTRACT-DECLARATION-ORDER` | `CANONICAL-SEMANTIC-INTEGER` | -1/0/1 are valid declaration orders through canonical codec and cold routing; Phase2/3 also round-trip PostgreSQL; the same -1 in a non-negative limit/epoch/counter/ordinal rejects outside its documented constructor sentinel |
| `SEQUENTIAL-R1-R2-R3-REPLAY` | `SAME-APPLICATION-COMMIT-RACE` | actual Coordination+Contracts process E live on R1, then admit/finish R2 replay, then R3 replay in one domain; separate target-scoped settlements, no duplicate R1 effect; a cause-plus-live/replay-kind key omitting target must fail the oracle; retries/replans of the same target still settle once |
| `CONTROL-STATE-MULTICOMMIT-GROWTH` | `APPLICATION-PLAN-A-SCALING` | fixed A=1, finite 10/100/1000 committed steps with growing distinct owned/nested obligations; count canonicalization/hash/serialization, operation/receipt bytes, WAL, pending/owned elements inspected and retained unique immutable bytes across commits |
| `SAME-DOMAIN-ADMISSION-CONTENTION` | `REALM-NEGATIVE-ISOLATION` | R2/R3 positive target replay is sequential in one domain; concurrent admission attempts yield one reservation owner and another busy/stale; same-application two-worker/ACK-loss race remains valid; cross-domain parallelism is separate |
| `QUEUED-MANAGED-EXECUTION-CUT` | `DISJOINT-PREFIX-EXECUTION-CUT` | queued consumer work survives unrelated lineage commit with exact source receipt/local predecessor/occurrence intent; no global-cut retry loop |
| `EXISTING-READY-SHARED-TARGET-JOIN` | `NEW-SHARED-CHILD-PARTITION` | two parents attach READY neutral X without Handlers/update effects; both local attachments succeed with one source lineage and no forced transaction merge |

The sequential target-isolation subcase is owned by Q and exercises B's logical R1/R2/R3 story. Its
owning schedule already belongs to CORE, HOST and INTEGRATED. Omitting it cannot pass G1 merely
because realm-isolation tests are deferred to HOST. The separate HOST admission-contention subcase
remains under `REALM-NEGATIVE-ISOLATION`; no profile or schedule ID is added.

The source provenance fixtures compare three independent facts: original cause/sourceOrder,
monotonic lineage receipt position, and causal production/visibility authority for historical
selection. Replacing strict sourceOrder increase with nondecrease does not solve decreasing
provenance or cutoff selection. Freeze the independently expected receipt/epoch trace, not just a
final state. These fixtures do not change external Timeline ordering.

The initial-history schedule's nested positive and the attachment schedule's T1/T10 contrast must run
against actual isolated Coordination+Contracts before PostgreSQL. Together with K100 importing E10,
they reject both blanket backdating from old source provenance and blanket exclusion by an admission
cutoff. Producing action, relationship policy, replay cursor and exact receipt-prefix evidence must
justify the selected view; no fixture silently retimestamps ManagedRevisionCause or combines its
separate invocation/gas scopes. See [V3](11-validation-plan.md#v3--admission-chronology).

### Mandatory fixture variants, not additional schedule IDs

The following are coverage requirements inside the existing schedule families. When an owning
schedule is mandatory, preflight requires explicit fixture/oracle coverage for every listed variant;
the report must contain its executed evidence. One row may cover several variants only when its exact
fixture and independently expected trace actually exercise all of them. Missing coverage makes the
owning gate incomplete (`NOT_RUN`), not implicitly passed by another row bearing the same schedule ID.
An exclusion still follows the existing owning-schedule rule. The catalog remains 53 schedules and
nine closed `subcaseId` values; these descriptions introduce no new profile or schema field.

| Existing schedule | Required fixture variants and independent observations |
|---|---|
| `FULL-HISTORY-INITIAL-SOURCE-INTERLEAVING` | Direct ordinary parent read of `/child/counter` at T20=1 in S10/P20/S30, separately from the local child-event counter; positive no-matching-update-event variant gives direct=1 and initially unset reflected value=unset, with an authenticated source transition; nested initial C10 → B reconstruction at40 → A's T20 read during reconstruction at60=1. |
| `INITIAL-VERSUS-DYNAMIC-ATTACHMENT` | Both T1/T10 attachment histories; with B initially0 and B5=5, direct child read at T3 after T1=0 while the local event counter is unset; original A-entry subsequences are exactly `[attachT1, observeT3, observeT7]` and `[observeT3, observeT7, attachT10]`. |
| `ADMISSION-OWNED-PREREQUISITES-BEFORE-DEPENDENT-HISTORY` | Existing required-prerequisite-before-dependent-read positive; separate E20 removal and E20 replacement of pending B under cutoff40, with B10 due and B30 not yet due; nested and foreign-obligation controls for exact suffix retirement and new occurrence ownership. |
| `ORDERED-LATER-CAUSE` | Existing later-cause hold/replan; independently strengthen guarantees and add above-cutoff ingress after a complete prefix was accepted, then repeat together across restart; accepted coverage/input/result stay unchanged, while conflicting covered content or incomplete normalization rejects. |

Checking only a shadow counter can hide a resolver that supplies B@30 during the ordinary T20 call.
Dropping T7 from the T10 replay can leave the same final unset value. Both mutations must fail against
the direct-read and complete-entry-sequence oracles. Original-entry subsequences do not erase valid
managed-import revisions: those retain their separately expected positions, identities and gas.

For removal/retargeting, the parent is eligible relative to E20's actual scope and historical point,
not only after the old occurrence reaches the admission window's ultimate B@30 target. The complete
E20 result atomically retires only the removed occurrence/generation's no-longer-applicable suffix.
Retargeting creates obligations for its distinct occurrence/policy/selection, never inheriting B's
cursor accidentally. No B@30 reaction may occur through the retired relation. Earlier committed
imports, B's retained history, foreign consumers and still-applicable nested work are preserved.
The fixture freezes the old/new occurrence evidence and exact obligation delta. Global cancellation
of everything owned by the same source, early future import and a permanent artificial pending gate
are separate negative controls, not alternative accepted results.

Monotonic coverage growth changes available physical evidence, not already accepted semantic input.
Do not extend the accepted cutoff, absorb the new entries or require an obsolete whole-provider
witness forever. Actual conflicting covered evidence, missing normalization or relevant semantic
mutation is not covered by this stability rule.

These are required acceptance controls. The retained seven r15.2 chronology test cases do not
assert direct child reads, the complete original-entry subsequence, nested reconstruction or the
removal/retargeting cases; their existing results cannot substitute for this coverage.

Each control-growth row references exactly one dataset with `cardinalities.applicationCohortCount=1`
and `controlGrowth = {committedStepCount, distinctOwnedObligationCount}`. Counts exclude setup and
include every step/owned obligation in the finite measured computation. The schema restricts step
count to 10/100/1000; preflight requires all three, with strictly increasing positive owned counts
under matched fixture/overlay conditions. Warm and fresh-JVM-after-each-commit runs are distinct
required physical rows. Dataset evidence reproduces both counts from the terminal records and
obligation definitions. An A-scaling or evidence-round run without these dimensions does not cover
this subcase. Keep peak active/pending counts in raw telemetry; total historical ownership cannot
be replaced with peak residency. No new metric family or benchmark harness is necessary.

The signed-order subcase is fixture data. The manifest configuration's non-negative integer fields and
canonical host timestamps remain unchanged. A valid signed declaration order is not a sentinel and
must not be generalized to other numeric fields.

### Required r15.4 independent-lineage fixture variants

These variants are mandatory within the existing 53 schedule families and nine named subcases.
Schedule presence alone is insufficient: semantic preflight checks fixture and independent oracle
artifacts for each listed variant. No optional profile waives this coverage.

| Existing schedule | Mandatory variant |
|---|---|
| `SHARED-N-FANOUT-FAULT` | Crash after source commit before discovery; separately after exactly 17 of 1000 Order commits. Those prefixes survive and outstanding entitled deliveries recover once. |
| `SHARED-N-SOURCE-STAGES` | Slow, unavailable and gas-failing Order controls: source and healthy Orders progress; local failure prevents aggregate success, not independent processing. |
| `SOURCE-RECEIPT-EVENT-PROJECTION` | Mandatory CORE tiny fixture: one Agreement plus two Orders, source commits before either consumer; source T10=1/T20=2 while Order@T15 sees 1, without source recomputation. N=1000 D rows are not a substitute. |
| `SIBLING-MANAGED-FAILURE-ROOT-WIDE-FINALIZATION` | Mandatory CORE two-Order fixture: one Order exceeds local gas, source and healthy Order progress and remain committed, exact failure persists, aggregate cause is not successful. |
| `NEW-SHARED-CHILD-PARTITION` | Mandatory CORE scope contrast: read-only shared neutral source succeeds; attempted write through `/agreement/...` cannot implicitly mutate source. Unsupported/unreviewed nonlocal scope holds/rejects before tentative publication without an invented Contracts fatal result. |
| `FULL-HISTORY-INITIAL-SOURCE-INTERLEAVING` | Source T10=1/T20=2, Order reads at T15 and sees 1 even when source is physically ahead; direct view and event-derived value asserted separately. |
| `SOURCE-RECEIPT-EVENT-PROJECTION` | Duplicate/drop receipt and delivery; two authored paths to one source preserve per-occurrence identity/multiplicity and exactly-once cursors. |
| `INITIAL-VERSUS-DYNAMIC-ATTACHMENT` | Physically delayed consumer versus semantically later attachment has distinct expected history; registration delay does not retimestamp attachment. |
| `ADMISSION-OWNED-PREREQUISITES-BEFORE-DEPENDENT-HISTORY` | Source already T30, parent removal/retarget T20: no old-occurrence T30 reaction; preserve independently owed history/new-target policy. |
| `DIRECT-ELIGIBILITY-ADD` | Attachment-cut registration races before/during/after source commit/discovery; activation intervals and semantic frontiers prevent missing/spurious deliveries. |
| `INDEX-COHERENT-OMISSION` | Current index empty while lagging parent history can introduce entitlement: no false discovery closure or receipt GC. Retain evidence until coverage and owed obligations prove safety. |
| `FULL-HISTORY-SHARED-ORIGINAL-CAUSE` | One cause addresses source and parent; hand-derived seed, required source reaction and parent-direct ordering follows22's causal selection/FIFO rules, with exact gas/IDs and multiplicity independent of physical scheduling; receipt completion order cannot substitute for event admission order. |
| `LOCALITY-CONNECTED-DAG` | Reversed workers, pages 1/2/default, cold/warm caches and restart preserve exact per-lineage histories/IDs/gas without all-parent RAM preload. |
| `GAS-BOUNDARY` | Independent source/consumer gas/failure identities: consumer rejection cannot roll back source/siblings or change source identity with N. An admitted atomic join instead retains its complete shared rollback scope. |
| Feedback and cycle families | First one-way contact stays independent. Before installing an actual return edge, validate canonical charged prefixes plus admission cost against the common fixed limit. An admitted join retains one remaining meter and full rollback scope; rejection fails only the attempting operation with certified `AtomicScopeGasAdmissionFailure`. No replay may erase an admitted join, and physical winners cannot choose scope. Preserve stable execution/event identities separately from final atomic settlement, vertex-simple routes and passive same-epoch representation. Unsupported required positives are not PASS. |

### Required semantic refinement vectors (revision15.11)

The 53 schedules, nine named subcases and existing profile sets remain unchanged. EQ1–EQ8 are
mandatory **fixture variants inside existing families**, not eight new schedule IDs or specification
profiles. Their exact workflows and expected traces are in [V4a/V4b/V5](11-validation-plan.md).
They implement22's selected rules and replace incompatible earlier pin/relay/creation/alignment
expectations. The variants are planned executable proofs, not evidence that the selected rules run.

| Variant | Owning existing schedule | Independent observation |
|---|---|---|
| EQ1 | `SOURCE-RECEIPT-EVENT-PROJECTION` | B's triggered E1/E2 updates yield A reads [1,2]; physical precomputation cannot replace both views with final 2. |
| EQ2 | `SOURCE-RECEIPT-EVENT-PROJECTION` | Single buffered handler patches before emissions; [2,2] is the contrasting correct control, without invented per-emit snapshots. |
| EQ3 | `SOURCE-RECEIPT-EVENT-PROJECTION` | Nested update handler observes each 0→1→2 patch; net-zero 0→1→0 still has observable work. Final business equality is not whole-BlueId equality. |
| EQ4 | `EVENT-BATCH-NESTED-EMIT` | Preserve P1-before-F2 FIFO admission. Explicitly contrast distinct external-entry epochs (Root F1→1/F2→2) with one invocation's E1/E2 where Parent queues F1/F2 behind E2 and Root later reads Parent.y=[2,2]; immutable F1 payload may still contain 1. |
| EQ5 | `SOURCE-RECEIPT-EVENT-PROJECTION` | Ordinary/managed A→B→C mapping preserves original E at A with /b/c even when B emits nothing; no duplicate direct-plus-relay delivery. |
| EQ6 | `NEW-SHARED-CHILD-PARTITION` | Canonical source-local initialization plus FULL_HISTORY preserves source history/IDs/events/gas under cold/warm/evicted/concurrent promotion. Source80 and parent20 with separate limits90 pass cold/warm; source gas settles once. Attachment modes affect observer selection, not X's origin. Failed promoter cannot erase prior authoritative X or publish an orphan candidate. |
| EQ7 | `DIRECT-ELIGIBILITY-ADD` | Frozen original recipients, synchronous initialized/selected initial view, exact rollback and no duplicate /a delivery when /b is created. Historical source epochs then form separate ordered catch-up operations; no fabricated caught-up shadow field or creator self-wait. |
| EQ8 | `SIBLING-MANAGED-FAILURE-ROOT-WIDE-FINALIZATION` | r1 gas failure or library-certified deterministic RUNTIME_FATAL retains consumer0/no successful epoch. The next operation aligns0→1 with ordinary synchronous update/lifecycle/gas effects, then presents r2's exact observation program1→2; original r2 events remain unchanged. Event-before-patch reads1, eventless r2 still aligns, net-zero transitions remain visible. Alignment/r2 failure rolls back to0. Both-fail then D30, bounded-cut COMPLETED_WITH_FAILURES and ordinary uncoalesced catch-up are controls under warm/cold/restart/workers/ACK loss; no failed-event replay, forged receipt or skipped unattempted range. Timeout/cancel/unknown-commit or an uncertified exception never substitutes for a terminal runtime result. |

The reviewed `scenarioSpecificationArtifactKey` snapshot must enumerate each applicable EQ variant,
its owning family/row IDs, exact fixture and independently derived expectation artifacts, semantic
ownership mapping and physical-overlay coverage. Join report observations to those frozen rows.
Semantic preflight rejects missing variants even when all schedule IDs are present. The JSON schemas
validate artifact structure/references, not these semantic properties; the future runner/verifier must
implement this coverage check. There is no claim that a schema pass already performs it.

Retain these additional mandatory controls from preceding refinements:

- `J-DYNAMIC-SAME-ORIGIN`, `J-AUTOMATIC-REATTACHMENT-CHAIN` and `L-QUOTA-PAUSE` are witness labels
  inside existing J/L families, not new schedule IDs. Freeze their exact fixtures/expectations in
  the scenario specification artifact. Cover canonical seed/read/join-admission/fence controls and quota
  pause/replenishment without changed gas, input skips or a promise of universal in-band repair;

- within `J-DYNAMIC-SAME-ORIGIN`, freeze both actual return-edge admission outcomes under one common
  limit of 100: charged prefixes A=60/B=30 plus an illustrative admission cost of 1 join at 91 with 9 left;
  later exhaustion rolls back both. A=60/B=50 pays the local check of 1 but cannot fit prospective 111;
  reject B's edge/operation with certified `RUNTIME_FATAL` / `AtomicScopeGasAdmissionFailure`,
  B's authentic admitted gas of 51 and no B→A publication. The prospective 111/limit 100 is diagnostic,
  never extra charged gas. A may commit after accounting for B's failed outcome. If B cannot afford
  the check itself, ordinary `GAS_LIMIT_EXCEEDED` occurs before joining. Use the manifest's actual
  admission charge in executable fixtures. A first A→B contact alone never merges meters; reverse
  workers and warm/cold evidence preserve results. Do not rename prior execution/event IDs or settle
  them twice when recording the admitted atomic scope. Test prospective sums immediately below,
  exactly at and above the limit: equality admits the join but does not waive subsequent charges;
- within that same witness, B's tentative effects conditionally create AC at consumed61 while A/C's
  independently required own E seeds cost5 each. B reaches51 including its check; prospective112/100
  rejects B. Invalidate the entire dependent AC attempt, including its ownership, status, gas,
  events/topology and dispositions; there is no AC settlement. Replay A/C's own seeds from committed
  pre-state against B's failure for5 each. B retains actual51 and diagnostic112/100; neither already
  published history nor its recorded rejection is recomputed. Include conditional A gas failure:
  once its tentative producer fails, that whole conditional failure is invalid too. Stale success
  or failure proposals cannot consume input; seed IDs stay stable without preserving invalid groups;
- mutate runtime-fatal certification, admitted-prefix/prospective-sum evidence and failed-initialization reuse.
  A genuine deterministic runtime failure permits the selected terminal import accounting;
  timeouts, cancellations, unknown commits and unclassified exceptions do not;

- complete multi-placement scope beyond loaded pages, independently derived alias/update order,
  one logical gas/rollback boundary and per-route cursors; failure on its second reaction;
- within EQ8, Root retains A/B pins0/0 after an earlier combined failure while both sources reached1.
  The next origin changes both1→2, using the actual dependency-first A Entry/work, B Entry/work order.
  A direct Root seed ordered after those sources reads2/2; do not insert it artificially before A.
  A's alignment0→1 and patch1→2 callbacks both read B0. B aligns only at its
  first canonical Entry/consumption site, not in a global prelude or when a page/header is loaded.
  Alignment GA/GB plus source BOOT emissions EA/EB admit GA,EA,GB,EB to the same FIFO. Cover alias
  retirement/rebind before Entry, an eventless producer and diamond reuse: shared producer cells/Entry
  execute once, distinct occurrences retain their own updates, and no old alignment targets a replacement
  or patches through a failed intermediate producer's rollback pin. Freeze exact views, sites and gas;
- valid frozen E2 delivery after E1 retirement, with a separate later-delivery retirement control;
- external gas-loop → terminal failure/no epoch → later detach/new-input recovery, also reconstructed
  historically; this does not by itself prove EQ8 managed continuity;
- T10/T20 receipts with a direct T15 read1, eventless exact source evidence and complete original
  target-entry history; sourceOrder/provenance is not a visibility timestamp;
- K100 importing closed E10 owns newly emitted F; original event identity is not a new emission;
- accepted-but-unmaterialized lifecycle coverage and gap-free retained-tail/live registration;
- passive same-epoch cyclic representation without business receipt/epoch/fanout echo;
- actual cyclic workflow feedback with shared gas/atomic rollback, not fresh budgets per receipt hop;
  include a finite operation changing two existing lineages and verify each authenticated stream
  successor under one commit, plus metadata-only no-delivery with zero business stream appends;
- exact directed Timeline sets under trusted-universe authorization: missing reverse-only TO does
  not block Agreement, newly relevant TO does; same-provider creation works without reset and stale
  relevant membership rejects. Compare100,000 reverse observers with10,000 genuinely embedded
  dependencies using bounded aggregate/merge/wake work rather than per-entry full-set scans;
- P=1/2/10/100/larger supported placements in N=1 consumer, separately from N-consumer growth;
  capacity hold names a real releaser and resumes when supplied, without semantic gas splitting.

For source reuse, distinguish canonical source basis/initialization authority, observer attachment
selection, non-authoritative preparation, logical gas ledger and physical CPU savings. Test the
selected separate fixed meters rather than claim equality to old creator-combined initialization.
Lost materialized bytes do not authorize a new source history or another source gas settlement.
Preserve prior authoritative source history on unrelated parent failure and prove no-orphan promotion
for genuinely unpublished candidate closure. Canonical result disagreement is not first-INSERT choice.

Observation evidence must join the complete producer result to the exact observable updates/views,
event admission/frozen recipients, original/new provenance and gas obligations. No required view may
be reconstructed from ambient latest state. Implement the selected authenticated observation-program
frames, continuation/enqueue sites and recursive interpretation from [22](22-processing-kernel.md);
a self-consistent opaque hash is insufficient. Do not replace that program with immediate-Parent
relay, all-pins-first or final-state-only import. Validate separate creator initialization and
historical catch-up boundaries, exact alias order and selected vertex-simple cyclic route multiplicity.

Required positive paths remain required: an unresolved hold does not award their acceptance.
The comparison must state which ownership rules are unchanged and which deliberate independent
source/consumer ownership change is being tested. Ordinary nested handler behavior is not excluded
by blanket "different input" language.

The common source stages are exact cuts, not timing labels. Every such failpoint names
`sourceWorkIdentity`, preregistered `tentativeSourceCallsBeforeCut = k`,
`deliveredPrefixIdentity`, and `expectedCompleteResultIdentity` (null only before execution).
The result identity binds the **source-local operation**, not its consumers. The delivery artifact
describes committed consumer applications ordered canonically by semantic target/occurrence identity,
not physical cross-worker commit order; it may therefore represent a completed set rather than a
single serial global prefix.

- `SOURCE_BEFORE_EXECUTION`: after k tentative source attempts, before final source execution;
  no source Complete result and no consumer completion.
- `SOURCE_AFTER_RESULT_BEFORE_DURABLE_PROGRESS`: source Complete exists privately, before source
  state/receipt/discovery-basis commit; consumer completion is empty.
- `SOURCE_AFTER_DURABLE_RESULT_CHECKPOINT`: source result and recoverable discovery basis committed,
  before the first discovery page/consumer application. Required for both live fan-out and retained
  history; not a mid-Contracts continuation.
- `SOURCE_BEFORE_TERMINAL_SETTLEMENT`: source committed but aggregate cause not complete; failpoint
  binds an exact committed-consumer set. Mandatory N=1000 crash set has cardinality 17. Those commits
  survive; only an uncommitted local attempt restarts.

Preflight requires variant `LIVE_INDEPENDENT_FANOUT` or `RETAINED_HISTORY_CATCH_UP`, exact local
commit boundaries, and all four stages at every N=1/10/100/1000. Terminal-stage completed sets have
preregistered cardinality below N; use 0 at N=1. Schema validates structural fields; semantic validation
checks stage meanings and every matrix cell. Missing coverage is NOT_RUN. Old `CONNECTED_LIVE`
expectations are not accepted as the new target.
Phase-2/3 scaling additionally includes N=10000/100000 under the existing D/S families, with bounded
pages, independent source latency, consumer throughput/lag, fairness and overload/drain evidence;
these larger rows need not multiply every small semantic/failpoint permutation. `SOURCE_BEFORE_TERMINAL_SETTLEMENT`
names a partially delivered cut, not a required aggregate-terminal-receipt mechanism.

Source-call evidence separates tentative/completed/post-crash attempts from committed semantic source
transitions. Matched fault overlays do not invent N-fold source recomputation. Gas remains semantic;
attempt latency, discovery calls and worker schedules remain operational.

## Processing invariants exercised by the rows

Deterministic final histories, event order, BlueIds, Contracts results, gas traces, and durable
post-state must be equal for equal frozen semantic input, regardless of paging, retry, process,
elapsed outage, or cache. Correctness precedes optimization.

A Contracts invocation is an atomic semantic computation. The host may checkpoint only between
complete invocations or at the explicitly defined Coordination safe-yield boundaries. If a process
dies while an invocation is uncommitted—even after tentative deliveries exist in its private
queue—the host discards that invocation and replays the same exact closed input from the beginning.
It never restores a Contracts-internal FIFO, route ordinal, partial trace, or gas ledger. The replayed
complete result and trace must equal uninterrupted execution.

Cyclic routing uses22's vertex-simple authored routes, not global event/lineage deduplication.
No DocumentId, including the emitter, repeats on one original-event route; distinct aliases remain.
New handler emissions begin fresh routes inside the same operation. Every admitted route occurrence consumes the positive
Contracts-defined ENQUEUE charge; inside an invocation the FIFO follows the current Contracts
rules and exact gas/overflow boundary. Retry cannot change its semantic charged prefix/result.
Actual caused workflow feedback retains one logical invocation/shared gas; no fresh-budget receipt
chain may evade its terminal outcome. Only genuinely separate future operations may form an
unbounded sequence. Progress otherwise assumes eventual evidence and fair recovery.

Provider `NOT_FOUND`, transient unavailability, retry-horizon expiry, host-capacity exhaustion,
process cancellation, and wall-clock timeout are operational only. They preserve the ordered-cause
and admission barriers and may requeue, suspend, or quarantine an attempt. A verified no-recipient
proof permits only its exact no-delivery/discovery progress. The operation-kind table above governs
terminal projection: external policy applies to original external input and its historical replay,
never to successful managed-import progress. Independent source/consumer work continues; aggregate
settlement cannot ignore failed or undiscovered owed work. Typed `AdmissionHistoryNoDeliveryResolved`
may advance only that admission candidate cursor, never another lineage's input position. Failed
managed-receipt imports retain their exact failed position and perform only ownership-authorized
cleanup; terminal-disposition advancement and next-operation alignment for `GAS_LIMIT_EXCEEDED`
or library-certified deterministic `RUNTIME_FATAL`
follow [22](22-processing-kernel.md#6-import-failure-consume-one-operation-not-an-arbitrary-range);
successful state is not advanced by failure.
No blanket source freeze, universal phase law,
obsolete workflow-wide graph cut or abandoned sibling obligation may be hidden in that transition.
Administrative skip is outside the POC.

## Performance freeze and metrics

Before calibration, `performanceContext.m0` records externally chosen numeric bounds, approval time,
approver, review reference, and the schema-fixed application-plan linear baseline. Calibration owns
no gate; its row budgets are `OBSERVE_ONLY` with
null thresholds. A performance-acceptance manifest links the prior calibration using its exact run
ID, `manifestPath`/`manifestFileSha256`, and `reportPath`/`reportFileSha256`, then records M1
guardrails and their selection note. These are byte hashes of the complete canonical files and are
distinct from their self-excluding `manifestIdentity` and `reportIdentity` values.
Every M1 metric has the same unit and direction as M0 and is equal or tighter: lower-or-equal for
`AT_MOST`, higher-or-equal for `AT_LEAST`. M0 must predate calibration; M1 cannot be selected from an
unmatched, failed, or incomplete calibration report. The acceptance manifest repeats the calibration
manifest's M0 exactly, and M1 selection predates the acceptance manifest.

In `PERFORMANCE_ACCEPTANCE`, semantic preflight joins each row budget to exactly one M1 guardrail by
`metricId`. Its unit, direction and numeric threshold must equal that guardrail; a row cannot override
M1 with a looser or independently selected threshold. Report validation repeats the join and evaluates
observations against that M1 value. These cross-object checks are not supplied by JSON Schema alone.
Calibration still uses `OBSERVE_ONLY` and null row thresholds, not M1.

Each performance row freezes acquisition mode B1/B2, cache mode, worker count, dormant multiplier,
database snapshot, GC protocol, offered load/count, warm-up, measurement duration, terminal deadline,
sampling cadence, repetitions, `completionScope` (`SOURCE_OPERATION` or `CONSUMER_OPERATION`),
and an optional reciprocal paired row. The deadline is at least the measurement duration; that alone
does not give late-arriving samples enough observation time to prove a latency bound. A pair differs
only on its declared axis and keeps completion scope fixed. Existing cause-named metrics below count
the selected local semantic operations; they do not imply global cause completion. If source and
consumer targets need different bounds for the same metric ID, use separate frozen acceptance runs,
not a weaker row override. Whole-cause propagation reports are optional raw diagnostics outside this
closed acceptance metric set, with their scope/N and completeness evidence stated explicitly.

Independent source latency is a primary target; consumer throughput/lag, bounded resources and fair
service are separate targets. N=1000/10000/100000 required reactions legitimately cost N work/gas
and increasing total time. Do not impose one universal seconds-scale whole-fan-out guardrail. Preserve
the existing `shared-n1000` metric meanings; the larger-N rows retain exact cardinalities, query plans,
page/transaction/concurrency peaks and throughput/lag evidence in their frozen measurement artifacts.
Compare indexed work selection in million-document corpora and thousands active per user, including
few ready tasks among many waits, overload/drain and unrelated-user fairness. The selected host must
maintain historical dependency, ready-work and reverse-wait indexes transactionally; full-history or
whole-user scans are not the normal discovery mechanism.

The closed acceptance metric set and its exact string bindings are:

| Exact metric IDs | Exact unit | Acceptance direction |
|---|---|---|
| `eligible-cause-latency.p50`, `.p95`, `.p99`; `normal-ingress-latency.p50`, `.p95`, `.p99`; `backlog.p99-age`; `locality.connected-dag-p99-latency` | `milliseconds` | `AT_MOST` |
| `offered-load.causes-per-second`; `throughput.settled-causes-per-second` | `causes/second` | `AT_LEAST` |
| `error-rate.failed-or-timeout-causes-ratio`; `amplification.exact-read-bytes-ratio`; `b1-b2.p99-latency-ratio`; all seven `evidence.*-ratio` IDs; `b1-b2.throughput-ratio`, `.rows-ratio`, `.read-bytes-ratio`, `.heap-after-gc-ratio`; `application-plan.verified-hot-cache-miss-ratio`; `application-plan.cumulative-reprojection-to-linear-ratio`; `locality.dormant-100x-to-1x-p99-latency-ratio` | `ratio` | only `b1-b2.throughput-ratio` is `AT_LEAST`; all others `AT_MOST` |
| `backlog.p99-pending-causes` | `causes` | `AT_MOST` |
| `amplification.logical-rows-per-cause`; `locality.connected-dag-rows-per-cause` | `rows/cause` | `AT_MOST` |
| `amplification.durable-writes-per-cause` | `writes/cause` | `AT_MOST` |
| `amplification.sql-statements-per-cause` | `statements/cause` | `AT_MOST` |
| `amplification.jdbc-round-trips-per-cause` | `round-trips/cause` | `AT_MOST` |
| `amplification.wal-bytes-per-cause`; `locality.connected-dag-read-bytes-per-cause` | `bytes/cause` | `AT_MOST` |
| `application-plan.validation-cpu-nanos-per-application` | `nanoseconds/application` | `AT_MOST` |
| `application-plan.bytes-read-per-application` | `bytes/application` | `AT_MOST` |
| `locality.dormant-demand-count-delta` | `demands/cause` | `AT_MOST` |
| `locality.normal-heap-after-gc`; `locality.normal-rss`; `locality.shared-n1000-heap-after-gc`; `locality.shared-n1000-rss`; `locality.connected-dag-heap-after-gc` | `bytes` | `AT_MOST` |
| `locality.shared-n1000-sql-statements-per-parent` | `statements/parent` | `AT_MOST` |
| `locality.shared-n1000-jdbc-round-trips-per-parent` | `round-trips/parent` | `AT_MOST` |
| `locality.shared-n1000-rows-per-occurrence` | `rows/occurrence` | `AT_MOST` |
| `locality.shared-n1000-read-bytes-per-occurrence` | `bytes/occurrence` | `AT_MOST` |
| `processor.physical-source-calls-per-settled-transition` | `calls/transition` | `AT_MOST` |
| `processor.retry-execution-nanos-per-cause` | `nanoseconds/cause` | `AT_MOST` |
| `processor.retry-verification-bytes-per-cause`; `locality.document-body-bytes-per-cause` | `bytes/cause` | `AT_MOST` |
| `state.cumulative-authority-bytes-per-transition` | `bytes/transition` | `AT_MOST` |
| `publication.batch-validations-per-published-batch` | `validations/batch` | `AT_MOST` |
| `publication.peak-claim-bytes` | `bytes` | `AT_MOST` |
| `recovery.satisfied-need-wakeup.p99` | `milliseconds` | `AT_MOST` |
| `locality.topology-rows-per-cause` | `rows/cause` | `AT_MOST` |

The manifest schema enforces each exact `metricId`→unit binding and, for M0/M1 and acceptance
budgets, its direction. Calibration uses the same unit binding but replaces the direction with
`OBSERVE_ONLY`. M0 and M1 each contain the complete set; the union of
calibration row budgets, acceptance row budgets, and their corresponding report results contains that
same set with no duplicate budget IDs or omitted metric.

Every dataset freezes `applicationCohortCount`. This retained artifact field now counts independent
local target applications in the isolated fixture, not atomic graph cohorts or a materialized global
partition. Rows owning `APPLICATION-PLAN-A-SCALING` must cover
exactly the distinct counts `1`, `10`, `100`,
`environment.operationalBudget.maxMaterializedApplicationDefinitions`, and
`cap+1` under the schedule's named hot/cold/restart modes. The preflight validator resolves the row
dataset references and proves that equality; an overlay label is not cardinality evidence. The
`cap+1` row must complete through bounded pages; total N exceeding one page cannot reject valid
fan-out. Trying to materialize cap+1 definitions in one read is a separate operational-cap control.

Measurement rules are fixed:

- A measured cause is one local semantic operation fixed by `completionScope`, identified by its
  original cause plus selected operation/owned scope, not by the original entry alone or worker job.
  For `SOURCE_OPERATION`, `C_ingress` contains every such entitled source operation whose original
  input append is in the window, including operations not yet physically discovered or queued. One
  original entry addressing two independent sources contributes two samples; one coupled operation
  owning A and B contributes one, not two samples because it updates two lineages. Freeze exact
  target/scope entitlement in the fixture and reproduce this join from raw input/selection evidence.
  A no-recipient input retains its separate metadata outcome and is not a fabricated source operation.
  For
  `CONSUMER_OPERATION`, it contains each exact entitled consumer input whose durable source receipt
  and entitlement first coexist in the window, including inputs not yet physically queued. Record
  source receipt and entitlement evidence so delayed discovery cannot shrink this denominator.
  `C_eligible` contains local operations whose complete execution prerequisites first coexist there.
  Retries and physical duplicates remain attributed to that same semantic operation.
- Eligible latency is `settlementVisible - eligibleEvidenceVisible`; normal latency is
  `settlementVisible - ingressVisible`, using the scoped ingress defined above. `settlementVisible`
  is the durable observation time of the validated local operation receipt/terminal disposition,
  including all effects owned atomically by that operation. It does not wait for independent
  consumers. For a source committed at 20 ms followed by all consumers finishing at 60 s, source latency
  is 20 ms; any separately reported whole-cause duration is 60 s. An empty queue, processor return or
  staged outbox blob is not a durable local endpoint. Sink acknowledgement remains separate.
- Preserve the actual outcome at the local endpoint. An unsuccessful semantic outcome is a failed
  completion, not throughput success. Retain pending work and its exact blockers without allowing
  independent consumer failure to reclassify a successful source operation. For any measured local
  operation lacking an endpoint at the deadline, retain its censored latency lower bound and a
  timeout flag even if it completes correctly later. Keep failed and timeout flags separately in raw
  evidence; the error-rate numerator is their union over ingress causes, counted once per cause.
  Neither a timeout nor a measurement deadline authorizes semantic terminalization or cleanup.
  The reviewed fixture freezes its expected normal outcomes and error classification. A verified
  no-recipient completion or an explicitly normal `NO_MATCH` is not an error merely because it has
  no Contracts `SUCCESS` result; an actual terminal failure is not relabeled normal after the run.
- Source-commit latency runs from source eligibility to its exact durable local receipt; consumer
  lag runs from source receipt availability plus the consumer's semantic eligibility to its local
  application commit. Record both endpoints separately, alongside full source-commit-to-consumer
  delay so prerequisite wait is not hidden. Required raw P50/P95/P99 and healthy-consumer progress
  survive slow/failed-consumer controls. Neither series is labeled completion of all entitled work.
- Optional whole-cause reporting joins source authority, complete historical membership/discovery
  evidence and every entitled local obligation disposition. It may be derived by the host or test
  auditor; no global terminal receipt, extra Contracts invocation or scheduler barrier is required.
  Late discovery must not be mistaken for completion just because current known consumers finished.
  Local receipts, durable recoverable work and discovery correctness are mandatory even without
  aggregate reporting. Failure and unresolved work cannot be hidden in an all-success aggregate.
- Percentiles use nearest rank `ceil(p*n)` over the complete preregistered samples, retaining failures
  and censor flags. An unfinished duration is a lower bound, never an exact sample. Report an exact
  numeric percentile only when the evidence determines that order statistic; otherwise retain
  bounds in raw evidence and use `NOT_RUN` with null observation for the unresolved exact metric.
  A proven bound violation may produce FAIL using its reproduced lower bound, explicitly marked
  in evidence; never award PASS because observed ages are below an upper-bound threshold. Preserve
  the control with 98 samples at 1 ms and two unfinished at age 2 ms: p99≤10 ms is not established. Follow
  each input long enough to classify its threshold or finish the quantile measurement. Deadline
  expiry changes neither semantic processing nor retry/gas. Per repetition the rate window is the exact
  half-open steady-state interval `[w0, w0 + measurementMilliseconds)`, following the frozen warm-up.
  Offered rate counts scoped ingresses in that window. Settled throughput counts unique
  successful local completion events in the same window, with exact input and local-receipt joins;
  preserve unsuccessful completions separately as errors, not throughput. Pool these counts over the
  sum of those windows' seconds. Post-window terminal-drain completions remain latency/outcome
  evidence but do not enter that shorter window's throughput numerator. A separately preregistered
  longer measurement window must extend both event selection and its denominator, never just the
  numerator or an overriding post-run protocol. Paired rows use identical window rules. Sampling
  instants are `w0 + k*cadence` through the deadline, with the deadline appended once when off-cadence.
- Amplification divides total attributable work, including retries, by `|C_ingress|`. Exact-read
  ratio divides physical exact bytes by canonical bytes of unique exact objects consumed per cause.
- Evidence-growth ratios use canonical bytes of unique evidence blobs as the denominator. The
  seven existing `evidence.*-ratio` metrics cover host evidence bookkeeping and retention; copied,
  decoded and hashed bytes exclude separately counted processor verification/execution. The
  serialized-envelope numerator includes proposal, apply-plan, operation and receipt bytes. Do not
  double-count shared in-memory blobs as copied bytes or omit actual canonical serialization/WAL.
  Apply the no-avoidable-superlinear-growth rule to this host work, not all physical Contracts work.
  A passing aggregate heap value cannot hide quadratic host copying or growing mutable authority.
- B1/B2 metrics are aggregate B2/B1 ratios except throughput, which retains its explicit
  `AT_LEAST` direction. Dormant latency is 100x/1x; dormant demand delta is per-cause 100x minus 1x.
- Application-plan measurements use the frozen `A=1,10,100,cap` series under matched work. One
  `application-blueprint-record-examination` is counted whenever one blueprint record is decoded,
  hashed, compared, or copied by plan validation or successor projection; repeated operations count
  separately. The schema-fixed `PAGED_A_DEFINITIONS_PLUS_A_LOCAL_COMMITS` denominator is
  `A + A = 2A`: each consumer definition examined once across bounded pages, plus one local completion
  projection per consumer in the isolated no-feedback fixture. No global partition is materialized.
  This bookkeeping baseline does not bound arbitrary processor or feedback work. Cold/restart
  runs report actual definition-validation work; the hot sequence reports CPU/bytes/cache misses
  and examined-record units over this denominator. Repeated full-prefix authority serialization is
  measured separately and cannot be excused by a hot immutable-plan cache. Avoidable superlinear
  bookkeeping or total-N rejection for `cap+1` fails regardless of aggregate latency.
- `H` is maximum summed application-JVM used heap after the frozen full-GC checkpoints. `R` is maximum
  synchronized application-JVM RSS through final drain. PostgreSQL memory is separate.
- Scenario D `N=1000` SQL/JDBC denominators are oracle-required affected parents; row/read
  denominators are oracle-required active occurrences. Connected-DAG attribution counts all work
  required by the chosen cause, including topology inspected outside the body-update spine. It must
  not drop traversal merely because that document body was never decoded.
- `processor.physical-source-calls-per-settled-transition` counts every source execution attempt,
  including tentative/retried/post-crash calls, divided by exact committed source transitions.
  `processor.retry-execution-nanos-per-cause` counts execution in unsuccessful/restarted attempts
  over accepted causes; `processor.retry-verification-bytes-per-cause` counts canonical input bytes
  verified during those attempts. Separate successful verification and all-attempt raw totals are
  retained, so moving work between buckets cannot hide it. Do not interpret these physical metrics
  as extra semantic gas or require linearity for arbitrary data-dependent discovery.
- `state.cumulative-authority-bytes-per-transition` sums actual canonical mutable control-state and
  identity-envelope bytes emitted over terminal transitions, with immutable definitions charged
  once when first stored. Raw SQL/WAL and identity-hash bytes remain separate recorded totals.
- `publication.batch-validations-per-published-batch` counts complete batch validations, including
  retries, over unique cursor-advanced batches. Record retry-free and injected-fault runs separately;
  the retry-free backlog series must have linear total work. `publication.peak-claim-bytes` is the
  maximum simultaneously materialized claim/page evidence. Continuous producer tail growth cannot
  prevent cursor progress. Neither metric excludes empty batches.
- `recovery.satisfied-need-wakeup.p99` is nearest-rank latency from the later of durable resource
  availability and durable waiter registration to its eligible QUEUED transition. Unwoken needs at
  the terminal deadline are censored timeouts and fail liveness, not semantic terminal results.
- `locality.topology-rows-per-cause` counts all attributable occurrence/component/routing rows read,
  including repeated reads and negative filtering; `locality.document-body-bytes-per-cause` counts
  attributable decoded exact document-body bytes, with repeated decoding counted. Their fixed
  denominator is accepted causes. Required whole-topology work is reported rather than asserting
  constant-size spine locality.
- Every division requires a positive denominator. Zero yields
  `UNDEFINED_ZERO_DENOMINATOR` and `FAIL`; missing operands yield `NOT_RUN`. NaN, infinity, epsilon
  substitution, and dropping a repetition are forbidden.

An `OBSERVE_ONLY` calibration budget has no threshold comparison. Its result is `PASS` exactly when
the validator independently reproduces one finite numeric observation with the manifest-bound unit
and complete hashed raw evidence. It is `FAIL` for invalid evidence, a unit/metric mismatch, a
non-finite or `UNDEFINED_ZERO_DENOMINATOR` value, or a contradictory reproduced value; it is
`NOT_RUN` when required operands or evidence are absent. A calibration is complete only when every
row and observation is `PASS`, the environment and datasets pass, the exact metric set is present,
and there are no exclusions. Numeric performance outside M0 is still a valid observation; it does
not relax M0 or predetermine G4, which is evaluated only against preregistered M1.

Report `observedValue` is `INVALID_OBSERVATION` with `FAIL` when the raw measurement is non-finite
or malformed and therefore has no valid canonical JSON numeric value; its raw evidence identifies
the defect. The existing `UNDEFINED_ZERO_DENOMINATOR` sentinel identifies that specific failure.
Neither sentinel is permitted for `PASS`; absent evidence remains `NOT_RUN` with null observation.

## Report and gate rules

The report repeats `runId`, `manifestIdentity`, and the complete-manifest byte hash; records start/end
time and environment verification; has one flat artifact map of all observed values and raw evidence;
and contains exactly one result for every manifest row.

For each row, report IDs must exactly equal the manifest's forbidden-outcome and budget ID sets.
Units match the schema-bound metric unit. Performance-acceptance budgets also retain the exact M1
join above. Artifact keys resolve in the appropriate flat map and their hashes verify. Dataset
evidence proves the selected corpus identity, all five schema-defined cardinalities
(`documentCount`, `processEmbeddedEdgeCount`, `historyEntryCount`, `occurrenceCount`, and
`applicationCohortCount`), `payloadBytes`, and both `controlGrowth` dimensions when present:

- Phase 1 uses immutable exact fixtures plus independently inspected committed state and result
  evidence from actual isolated Coordination+Contracts tests. In-memory adapters and controlled
  resource providers may be used; mocked semantic decisions or canned processor results are not an
  oracle. No PostgreSQL instance is required to accept the library gate.
- Phase 2/3 use transactionally stable PostgreSQL evidence. A Phase-1 in-memory state capture cannot
  substitute for proving the real host's persistence, atomicity or crash recovery.

The existing `expectedDurablePostState` artifact field denotes committed logical library state in
Phase 1 and actual durable host state in Phase 2/3; its name does not turn an in-memory pass into a
PostgreSQL durability claim.
These are named dataset fields, separate from the shared-source N series and the application-plan
A series. Environment evidence matches every frozen static field, including all seven positive
operational-budget values.

The validator independently compares observed core result, semantic trace, and durable post-state to
their manifest oracle artifacts. It also evaluates every forbidden outcome and budget from raw
evidence; a runner's status is not its own oracle.

- `PASS`: environment and dataset pass; all three comparisons, all forbidden outcomes, and all
  budgets pass; all required evidence hashes exist.
- `FAIL`: at least one independently reproduced subresult fails. Other completed or missing evidence
  remains visible.
- `NOT_RUN`: no subresult fails and at least one is `NOT_RUN`. It never awards a gate.

Report row IDs must equal the planned row-ID set exactly. A missing mandatory row, unresolved
artifact, environment mismatch, invalid oracle, missing raw evidence, or incomplete metric set is
`NOT_RUN` or invalidates the report; it never becomes a pass. A calibration report cannot award a
gate. Only an acceptance report whose manifest owned and passed the gate can satisfy C*, E*, X*, or
M* review criteria.

## Minimal validation procedure

1. Before execution, validate all three schemas; recompute manifest/artifact hashes; check commits,
   patches, semantic configuration, exact row/owned-gate equality, unique references, datasets,
   overlay pair coverage, mandatory schedules/subcases/fixture-variants/exclusions, control-growth dimension coverage,
   failpoint stages, phase-appropriate actual-library/PostgreSQL dataset evidence, metric/unit/direction,
   budget completeness, exact acceptance-row-to-M1 joins, M0 timing, and measurement pairs.
2. Write the manifest once and give its identity to the runner before the first SUT action.
3. Run without editing the plan. Preserve partial and failed evidence.
4. Write one report, recompute every comparison/budget from raw evidence, and verify the exact
   manifest/report row join and status rules.
5. Retain the manifest, report, artifacts, phase-appropriate library/PostgreSQL evidence, traces,
   receipts, applicable SQL telemetry, and environment capture. Any rerun uses a new run ID.

The validator suite needs one valid functional, calibration, and performance-acceptance witness plus
isolated negative mutations for: missing/extra fields; bad hashes/paths/references; dirty state
without a patch; omission of any new chronological/feedback/execution-cut/recovery/index/outbox
schedule; use of retired `REFERENCE_MONOLITH` or managed-first schedule names; semantic integers outside the safe range; decoded-byte Timeline ordering; duplicate
or omitted rows/schedules/metrics; row gates different from `ownedGates`; wrong metric unit or
acceptance polarity; invalid M0/M1 tightening; deadline shorter than the window; non-reciprocal
pairs; zero denominators; executed results without artifacts; `FAIL` without a failing subresult;
`NOT_RUN` carrying a failure; and an `OBSERVE_ONLY` pass with absent, non-finite, or unreproducible
evidence.

Also omit each required subcase, swap its owning schedule, substitute a different row's fixture,
or claim fixed-A multi-commit coverage with missing/nonincreasing owned counts, wrong A, missing
10/100/1000 steps, absent warm/restart rows or mismatched observed dataset dimensions. All fail
preflight or report validation, not silently pass. Signed-order negative controls must target
non-negative fields rather than rejecting their positive -1 declaration-order fixture.

Omit each applicable EQ1–EQ8 variant while retaining its owning schedule ID; semantic preflight
must reject missing row/fixture/expected-trace coverage. Mutate an intermediate required view to
final state, invent an intermediate view in the buffered control, collapse per-patch updates, preload
F2 ahead of P1, suppress A's E when B emits nothing, or duplicate direct-plus-relay delivery.
Also reject cache-driven gas/history/initialization identity, fake successful state after failed r1,
permanent-block claims in place of required repair, or r2 assuming r1 succeeded. Creator work cannot
escape rollback, become a mandatory later operation without proof, or expand original frozen targets.
Retain omission/mutation controls for complete membership, frozen retirement, source provenance and
K100 completion ownership, accepted lifecycle coverage, passive representation, P versus N growth,
actual capacity releasers and no gas reset per page or workflow feedback hop.

Specifically omit `SEQUENTIAL-R1-R2-R3-REPLAY` from a CORE run, or move it to the HOST-only realm
schedule; neither can award G1. Test the target-omitting live/replay-kind settlement-key mutation.
A valid Phase-1 witness needs no PostgreSQL artifact, but replacing its actual library evidence with
mocked semantic outputs fails; Phase 2/3 cannot reuse that witness as database durability evidence.
For M1, a p99 guardrail of 2000 ms with a row threshold of 60000 ms is rejected before execution,
not accepted because an observed 30000 ms passes the row threshold. Mutate the join again during
report validation and require rejection there too.

Omit each required direct-read, complete-entry-sequence, nested, pending-removal/retargeting and
accepted-coverage-stability variant while leaving its owning schedule present. Each omission fails
coverage validation. Reject a direct read replaced by a shadow-counter check, an omitted T7 hidden
by an unchanged final value, future B@30 imported before E20 removal, stale suffix delivery after
retargeting, foreign/nested obligation deletion and accepted-prefix invalidation solely from stronger
guarantees or above-cutoff ingress. Fixture labels without their exact independently expected trace
and executed evidence do not satisfy these checks.

For measurement validation, relabel a 20 ms source receipt as 60 s whole-propagation completion,
change `completionScope` after execution, count post-window drain completions over the shorter rate
window, remove failed/blocked inputs or deadline timeouts, or claim completion from an empty queue
while owned work remains. Also omit undiscovered but entitled consumer inputs from denominators,
collapse two independent source operations triggered by one entry into one ingress sample, count one
coupled A/B operation twice, or use censored lower-bound ages to claim a passing p99. Each mutation fails the scoped raw
receipt/state/time-window join. Optional aggregate reporting is not itself required to exist.

Also mutate the D fixture variant and omit each applicable variant/N/source-stage cell. Reject the
old all-connected atomic oracle, a live fixture refusing the durable-source-before-consumers cut,
and a passing row without its executed semantic failpoint. Reject loss of source/17-consumer commits,
healthy-consumer blockage from a failed sibling, latest T20 in a T15 read, stale T30 delivery after
T20 removal, registration gaps, duplicate path collapse, early receipt GC or closure based only on
current index emptiness. Each mandatory r15.4 variant needs exact independent expectations and
executed evidence; a schema-valid schedule label does not satisfy it.

Include separate mutations of every operational-budget value and dataset cardinality; replacing a
canonical semantic-configuration identity with its JSON artifact hash; disagreeing structured and
canonical policy/environment bytes; either fixed processor-status mapping; and replacing a linked
calibration file hash with its self-excluding identity. Reject host semantic-revision/SQL-fence fields
inserted into the semantic configuration. Mutate host-versus-processor cost classification, drop
source retries, omit negative topology reads, treat a growing tail as a publisher blocker, or mark a
satisfied unnotified waiter complete without a durable requeue. Every performance metric must pass its exact
schema-bound unit/direction positive vector and reject a wrong unit or acceptance polarity. A valid
calibration observation may exceed M0 and remain a valid observation, but an excluded, incomplete,
failed, or evidence-invalid calibration cannot qualify an acceptance run.
