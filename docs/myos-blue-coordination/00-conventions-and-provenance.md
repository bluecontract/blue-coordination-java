# Conventions, scope, and provenance

> **Status:** PROVISIONAL / EXPERIMENTAL · **Revision:** 15.12 · **Scope:** local POC, terminology, sources, and verified material  
> [← Map and decision](../myos-blue-coordination-host-architecture.md) · [Conventions and provenance](00-conventions-and-provenance.md)

Section numbers are retained from the original analysis so that references and checklists remain unambiguous. Reference listings are kept outside the prose in [`reference-api/`](reference-api/README.md).

**Status:** **PROVISIONAL / EXPERIMENTAL.** Revision15.12 is the implemented design baseline, now synchronized with the Phase1/2 handoff on 2026-09-06. It is neither a final specification nor approval to freeze a public API or schema. Integration and performance remain to be tested in Phase3.

**Current stage:** Phase1 libraries and Phase2 PostgreSQL host foundation passed the scoped
integration-readiness checks. [24](24-phase-1-library-summary.md) records implementation refinements;
[the readiness record](implementation/phase-1-2-readiness.md) records actual tests and limitations.
[23](23-first-implementation-execution-plan.md) records isolated workspaces. Phase3 is planned in
[25](25-phase-3-integration-plan.md), not implemented. Readiness is not formal all-catalog acceptance.

**Baseline date:** 2026-09-04  
**Revision 15 date:** 2026-09-05  
**Revision 15.1 date:** 2026-09-05 (focused review-feedback clarification)  
**Revision 15.2 date:** 2026-09-05 (first in-process chronology characterization)  
**Revision 15.3 date:** 2026-09-05 (documentation-only review refinement and stage clarification)  
**Revision 15.4 date:** 2026-09-05 (independent-lineage boundary and live fanout design correction)  
**Revision 15.5 date:** 2026-09-05 (critical r15.4 review follow-up; documentation/reference sketch only)  
**Revision 15.6 date:** 2026-09-06 (decision traces and documentation/reference-sketch refinement only)  
**Revision 15.7 date:** 2026-09-06 (semantic equivalence/source-reuse correction; documentation/reference sketch only)  
**Revision 15.8 date:** 2026-09-06 (entry/FIFO clarification, selected managed gas-failure continuation and indexed/scalable host contract; documentation/reference sketch only)  
**Revision 15.9 date:** 2026-09-06 (selected processing kernel, canonical source origin, directed Timelines, explicit gap alignment and0/1/N publication; documentation/reference sketch only)  
**Scope:** a production-shaped POC for Coordination, managed documents, and affected-closure processing, integrated locally with MyOS Mini (`myos-simple`) on PostgreSQL with targeted, paged, lazy state access.

The report uses the following markers: **[F] verified fact**, **[I] inference**, **[R] recommendation**, **[Q] unresolved question**, and **[P] provisional API or schema shape requiring implementation evidence and an owner decision**. Unless explicitly marked otherwise, the proposed design and plans are **[R]**, and Java reference listings are additionally **[P]**. `file:line` references apply to the commits recorded below. Line ranges are navigational aids; the named symbol is the primary reference.

Throughout this package, `(timestampMicros, exactTimelineEntryBlueId)` means the current Language
`ExternalOrderKey` containing an integer atom followed by a text atom. The entry identity must first
pass canonical plain Base58 SHA-256 BlueId validation; its canonical text is compared by Unicode
code point. Because the Base58 alphabet is ASCII, that is also unsigned UTF-8 byte order. It is
**not** decoded-digest byte order, Base58 digit-value order, database collation, or Java's locale.

[22 — Selected processing kernel](22-processing-kernel.md) is the
current semantic authority for this proposal. [18](18-independent-lineage-processing.md) describes
the independent-lineage target; [19](19-semantic-edge-contracts.md) its edge obligations, and
[20](20-decision-traces.md) the diagnostic traces. Revision15.7 withdraws the previously selected
all-pins-first, final-state-only replay, mandatory Parent relay and unconditional post-commit
new-placement rules. Revision15.9 selects their replacement: composed reaction origins, observation
program interpretation, per-placement synchronous updates, canonical origin and explicit catch-up lanes.

There is one logical Contracts semantics. Physical materialization/cache/factorization must preserve
its observable updates, handler reads, event order, gas, outcomes and exact identities. Ordinary
PROCESS versus managed PROCESS_CLOSURE remains a conformance obligation, with the implemented
controls recorded in the readiness report, not an approved exception. Explicit independent source/consumer ownership can change the old combined invocation's
rollback and identities; document that semantic change separately and prove equality under the same
chosen ownership. Do not use it to waive unchanged routing or chronological observations.

[17 — Causal processing](17-causal-processing-model.md) defines chronological/dependency laws;
the [tutorial](tutorial/README.md) explains them. No host splits a current Contracts result.
The [historical r15.10 outcome](review/revision-15-10-review-outcome.md) records the bounded dynamic-join and
host-quota refinement. General cancellation of nonterminating import chains is outside the first POC.

Ordered lineage histories and exact producer dependencies replace one global physical commit prefix.
Source history and a durable bounded discovery basis prevent lost fanout without hydrating all Orders.
Transactionally maintained temporal indexes/frontiers establish recipient eligibility with scoped
queries, not routine full-history reconstruction. The host durably schedules/accounts all due work;
ready-work and reverse-wait indexes are required at the intended million-document scale. A failed
consumer cannot undo the source or healthy siblings. Aggregate completion is optional reporting/test
bookkeeping, not a mandatory global receipt or source gate; required N reactions may take longer as N grows.

The PostgreSQL adapter trusts reviewed complete queries and uses scoped target/occurrence/index fences.
Host global counters never enter portable IDs or invalidate independent operations merely because
a sibling committed. Immutable definitions, bounded cursors/deltas and level-triggered recovery remain.

Revision 15.1 applies the accepted r14 Pro-feedback subcases to this foundation; it does not change
the recorded implementation baseline. The [follow-up outcome](review/revision-15-1-review-outcome.md)
records accepted, already-addressed and deliberately non-adopted suggestions.

**[F] Retained revision-15.2 experiment.** Earlier local test/build changes exercise actual Coordination and
Contracts in process. The three new SDK attachment tests pass; four cases in the dedicated
`pocSemanticTest` suite fail on settled-history replay. **Prototype requirement gate: FAIL; G1 not
accepted.** This preliminary characterization is not a full manifest-based acceptance run.
At that experiment's snapshot, runtime code and the recorded baseline SHAs were unchanged; local
test/build patches were additional run provenance, and no PostgreSQL tests had run. These are not
statements about today's implementation. The [chronology report](implementation/phase-1-chronology.md)
records exact commands, outcomes and limitations. These test files were created before the current
stage was clarified and remain auxiliary evidence. Revisions 15.5–15.11 change neither those tests nor
their stored results. The direct-read and complete-entry-history assertion gaps are explicitly
recorded; future green results on the unchanged tests would not prove the strengthened scenario plan.

**[F] Working branches.** The [committed source set](implementation/source-commits.md) records the
Phase1/2 implementation commits. Coordination's synchronized documentation is consolidated onto
`codex/coordination-external-state-poc`; `codex/myos-coordination-design` is its local import source.
The original documentation checkout's runtime files remain at the old baseline. The earlier test
sources are preserved in the [r15.2 archive](implementation/r15.2-experiment/README.md), without
adding their Gradle wiring or historical assertions to the current test scope.

| Current implementation | Workspace / branch | Starting point |
|---|---|---|
| Coordination, Language/Contracts, BEX | `/Users/kamil/Documents/Projects/Blue/worktrees/coordination-external-state/<repository>` / `codex/coordination-external-state-poc` | Each library's recorded `next` commit below, followed by the recorded implementation commit |
| MyOS Simple | `/Users/kamil/Documents/Projects/Blue/myos-simple` / `feat/coordination-with-external-state` | `main` at `2c4651335f9da0ce7a05a4b55f75c56b31a732c3`, followed by the recorded implementation commit |

Exact compiled artifacts, original pre-commit patches and archived test outputs are described in
the readiness record. RC version strings alone do not identify these locally modified implementations;
use the source commits and the test record's build provenance together.

Historical source roots used by baseline citations (not aliases for the implementation worktrees):

- `COORD=/Users/kamil/Documents/Projects/Blue/blue-coordination-java`
- `LANG=/Users/kamil/Documents/Projects/Blue/blue-language-java`
- `BEX=/Users/kamil/Documents/Projects/Blue/blue-bex-java`
- `REPO=/Users/kamil/Documents/Projects/Blue/blue-repository-java`
- `SIMPLE=/Users/kamil/Documents/Projects/Blue/myos-simple`
- `MYOS=/Users/kamil/Documents/Projects/Blue/myos-java`
- `CONTRACTS=LANG/blue-contracts-core/src/main/resources/specifications/blue-contracts-and-processor-specification-1.0.md`

`MYOS` is the implementation reference for the MyOS Timeline provider's clock, append,
predecessor, idempotency, completeness, and transactional behavior. It is not the document-runtime
integration target, a migration source, or an alternate specification. Its older document feeder
and processor do not define the new Coordination graph semantics. The exact reuse boundary is in
[`15-myos-timeline-foundation.md`](15-myos-timeline-foundation.md).

## Single semantic baseline

The source of implementation truth is the current local implementation worktrees of Coordination
and its dependencies listed above. Historical citations below describe the inspected starting point.
The three `next` branches were fetched and fast-forwarded during the baseline preparation; the
earlier read-only remote check on 2026-09-05 confirmed the recorded commits against `origin/next`.
This documentation-only refinement retains those starting-point identifiers and makes no new remote
freshness claim:

| Repository | Baseline branch and commit | Baseline artifact |
|---|---|---|
| `blue-coordination-java` | `next` / `20fca9fd9934f367612c27348b8b6532d4029672` | Coordination `3.0.0-rc.5` |
| `blue-language-java` | `next` / `be2260217d1dbab0c7b60bcbd28073a5955e2b7b` | Language/Contracts `3.1.0-rc.23` |
| `blue-bex-java` | `next` / `42691919f8a4b9f114d6b0e591d2cf9befc87ada` | BEX `1.1.0-rc.4` |

Additional evidence snapshots used by the analysis are recorded separately; they are not part of the
remote refresh claim above and do not assert that another task has left those working directories unchanged:

| Repository | Recorded source branch and commit | Role |
|---|---|---|
| `blue-repository-java` | `next` / `29798ce57d4f8ec77e9310836c50d0571199894e` | Repository `3.0.0-rc.21` |
| `myos-simple` | `main` / `2fe59d3d67f0db9e93abc5b1891a4af231665953` | Historical host analysis snapshot; not the Phase2 starting commit |
| `myos-java` | `main` / `cfda40cd0f87b4b85c1e27a65f26294d1a47cac2` | inspected local Timeline-provider implementation reference |

The pinned local `myos-java` source snapshot was inspected on 2026-09-04. Its Timeline architecture and
tests are cited by file/symbol rather than treated as a selectable stack version; no MyOS data or
document runtime is migrated into the POC.

The artifact versions and commit SHAs above identify one reproducible development baseline. They do **not** define selectable specification profiles. The POC must not introduce:

- runtime selection among Language, Contracts, BEX, or Coordination revisions;
- compatibility adapters between competing specification variants;
- persisted-state migration between experimental POC revisions;
- mixed-version or rolling-upgrade behavior.

External specifications and earlier review documents remain useful research material, but they are not alternate normative inputs. The checked-out stack is the source of implementation evidence, not an infallible oracle: when an executable counterexample proves that current behavior violates a core invariant, correcting that behavior is an in-scope change to this single evolving baseline. The corrected checkout then replaces the earlier one; the POC never supports both behaviors. A test run records the exact SHAs and local patches so that its result can be reproduced; the record is observability metadata, not a semantic dispatch key.

The isolated POC database is disposable. After an intentional incompatible change, development may delete all integration state—including documents, work, receipts, outbox records, demands, and waiters—then reseed and rerun the suite. No migration rules are required before the first deliberately published release.

## Implementations, not specification versions

One semantic baseline may have several implementation strategies:

```text
Current Coordination semantics
    ├── existing in-memory Coordination runtime/adapter
    ├── PostgreSQL-backed MyOS Mini adapter
    └── adversarial/fault-injection test adapter
```

The existing in-memory Coordination runtime remains available and its high-level API is retained.
It is a fast semantic reference, but behavior is promoted to an oracle only after its invariant
tests pass. This revision explicitly includes correction of unrestricted catch-up frontier extension by unrelated future input in the retained
counterexample described in [`13-deterministic-catch-up-repair.md`](13-deterministic-catch-up-repair.md),
the exact Timeline/microsecond ordering gaps, an eager-versus-lazy differential over the **same managed graph**, plus independent
hand-worked invocation/history oracles for chronology, sharing and cycles, and a
compute-once/fan-out proof for a lineage shared by many parents.
The POC adds a PostgreSQL-backed execution path against the same corrected semantics; it does not
remove current Coordination features or redefine the in-memory path as a different specification.
An adversarial adapter may exercise missing evidence, conflicts, crashes, and invalid data while
preserving the same semantic contract.

**[F] Historical Timeline discrepancy and its resolution.** Repository semantics require a provider-assigned
microsecond timestamp that is unique and strictly increasing within one exact Timeline; equal time
is legal only across Timelines and needs a deterministic feeder-policy tie-break
(`REPO/src/main/resources/blue/repo/BlueRepository.blue:1941-1978,2015-2034`). The inspected Coordination baseline
constructs `(timestampMicros, raw timelineId, entryBlueId)`, keys predecessor/head/sequence state by
raw `timelineId`, and rejects equality within that raw key
(`COORD/src/main/java/blue/coordination/internal/WholeRequestEntryFactory.java:91-94,137-140`;
`COORD/src/main/java/blue/coordination/internal/InMemoryTimelineJournal.java:23-29,116-147`). The
inspected MyOS Mini exact-provider import path instead permitted equal timestamps within one predecessor chain,
and its baseline test required that behavior
(`SIMPLE/src/main/java/blue/myos/mini/timeline/MyOsTimelineRuntime.java:327-343`;
`SIMPLE/src/test/java/blue/myos/mini/timeline/TimelineImportServiceJdbcTest.java:383-429`). These
behaviors could not jointly define the golden oracle. Phase1/2 implement exact typed Timeline
identity, strict per-Timeline microseconds and canonical cross-Timeline ordering; see
[15](15-myos-timeline-foundation.md) and the current verification record. The old citations explain
the repair, not a remaining permitted discrepancy.

**[F] Existing MyOS provider foundation.** `myos-java` already serializes append and guarantee on
one Timeline-row lock, assigns
`max(dbNowMicros, lastTimestampMicros + 1, guaranteedBeforeMicros)`, builds a canonical predecessor
chain, implements append idempotency, and issues an exact exclusive completeness proof
(`MYOS/modules/myos-core/src/main/java/blue/myos/core/timelines/timeline/domain/TimelineHead.java:32-44`;
`MYOS/modules/myos-core/src/main/java/blue/myos/core/timelines/timeline/repository/TimelineRepository.java:25-28`;
`MYOS/modules/myos-core/src/main/java/blue/myos/core/timelines/timeline/service/guarantees/TimelineGuaranteeService.java:55-96`).
Those mechanisms are reused. Its older document feeder's
`(timestampUs, providerId, orderingChannelName, raw timelineId)` tie-break is not reused because the
target policy keeps timestamp as the only priority field and uses exact entry identity only to
totalize collisions.

**[F] Baseline top-level admission-history evidence.** The inspected Coordination API promises
`FULL_HISTORY`, strict-after `FROM_FRONTIER`, and `FROM_NOW`; its sequential implementation retains
one global exact cursor, selects a historical entry against the staged route surface, processes it,
and recalculates that surface before selecting the next entry
(`COORD/src/main/java/blue/coordination/api/CoordinationEngine.java:313-320`;
`COORD/src/main/java/blue/coordination/internal/SequentialDrainCoordinator.java:2028-2084,2488-2510`).
The policy and dynamic-surface behavior are executable in
`TemporalAdmissionPolicyIntegrationTest.topLevelHistoryPoliciesUseExclusiveVerifiedFrontiers` and
`DynamicHistoricalSourceSurfaceIntegrationTest`
(`COORD/src/integrationTest/java/blue/coordination/integration/TemporalAdmissionPolicyIntegrationTest.java:50-87`;
`COORD/src/integrationTest/java/blue/coordination/integration/DynamicHistoricalSourceSurfaceIntegrationTest.java:62-134`).
That baseline Contracts admission path did not expose an equivalent journal-history dependency
(`COORD/src/main/java/blue/coordination/internal/DefaultCoordinationEngine.java:512-543`;
`COORD/src/main/java/blue/coordination/internal/ContractsClosureAdmissionAdapter.java:190-219`),
so Phase1 added chronological selection/evidence at the external boundary. The historical gap was
not a reason to replace original direct inputs with managed-source receipts. The general durable
admission/continuation adapter is still Phase3 work; do not infer it from isolated library tests.

The implemented boundary and its limits are described in [`05-poc-api.md`](05-poc-api.md).
The rationale in [`05-api-contract.md`](05-api-contract.md) separates implementation from extension
seams. [`reference-api/`](reference-api/README.md) is explanatory reference material; compiling a
standalone sketch is not proof that its conceptual host types exist in the runtime.

## Identity convention used throughout this documentation

Platform counters, epochs, generations, gas, timestamps and occurrence ordinals use their declared
nonnegative canonical JSON integer domain, bounded by `9007199254740991` (`2^53 - 1`) where the
constructor requires the portable safe range. Fractions, exponent spellings, out-of-range values and
quoted decimal strings are not alternate encodings. A constructor-specific sentinel, such as
`ManagedRevisionCause.fromEpoch=-1` before epoch zero, is valid only in its documented field.

This does not redefine signed declaration `order` or Integer content in authored Blue documents.
Each such field keeps its current Language/Contracts domain. In particular, declaration `order=-1`
is an ordinary signed ordering value, not a sentinel: preserve `-1, 0, 1`, never clamp or shift them.
The current loader preserves declared order/default zero and Coordination sorts it with signed
`comparingInt` (`LANG/blue-contracts-core/src/main/java/blue/language/processor/ContractHeaderLoader.java:420-427`;
`COORD/src/main/java/blue/coordination/internal/OperationRouteIndex.java:908-935`). Any new identity
projection must preserve that field's exact domain; the semantic-configuration schema's nonnegative
limits and generations are unrelated and remain unchanged. Host-only metric strings remain outside
semantic identity preimages.

In the active MyOS Mini integration path, managed-document identity is content-derived:

```text
DocumentId = BlueId(exact initial authored Blue document, including initial state and channels)
```

The same initial authored Blue value therefore denotes the same lineage in this path. Retry naturally derives the same `DocumentId`; a work key or deterministic allocation slot may identify an idempotent creation attempt, but must not replace or influence `DocumentId`. The current exact state identity can change across epochs. For a cyclic component, current member identities and the exact cyclic-set proof are derived during finalization, while managed-graph component membership/generation remain separate and each member's `DocumentId` remains the BlueId of its own initial authored value (`COORD/src/main/java/blue/coordination/sdk/BlueCoordination.java:108-118`; `COORD/src/main/java/blue/coordination/internal/Contracts10AuthoredClosureCompiler.java:138-168`; `LANG/blue-contracts-core/src/main/java/blue/language/processor/closure/ComponentFinalizationKernel.java:75-141,213-295`). Existing Coordination modes remain available; the POC does not remove them or use them as a reason to change the new host boundary.

Throughout this package, **one source-lineage processing** means one logical managed-lineage
application/revision/receipt for one exact source transition, keyed by at least cause, lineage,
before-state BlueId, semantic input/work occurrence, and semantic configuration. Distinct
same-cause deliveries remain distinct transitions. One physical processor/internal-Handler
sequence is required only by the uninterrupted **closed-evidence fixture**. Dynamic evidence
discovery may require multiple tentative physical calls even without a crash; tentative, closed,
and replay calls are counted separately and their count must remain independent of parent count at
the same named semantic failpoint shared by every fan-out size. Fan-out-relative failure schedules
are reported separately. Calls to the pure `CoordinationCore.evaluate` that do not invoke the
processor are not source processing. A conflict or crash before a durable safe boundary may replay
pure computation, but replay may never duplicate the settled transition effect.

Unrelated pre-existing untracked files in the reviewed checkouts are not treated as authoritative evidence and are not modified by this work.
