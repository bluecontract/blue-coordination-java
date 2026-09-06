# Current implementation and baseline gap analysis

> **Status:** Phase1/2 handoff synchronized 2026-09-06 · **Design baseline:** 15.12 · **Scope:** implementation status followed by historical source analysis  
> [← Map and decision](../myos-blue-coordination-host-architecture.md) · [Conventions and provenance](00-conventions-and-provenance.md)

Section numbers are retained from the original analysis so that references and checklists remain unambiguous. Reference listings are kept outside the prose in [`reference-api/`](reference-api/README.md).

## Current implementation status

Phase1/2 passed the [baseline verification scope](implementation/phase-1-2-readiness.md).
The subsequent [pre-Phase3 review repairs](implementation/pre-phase-3-review-remediation.md) passed
their separate affected-library and host integration checks; the earlier results are not relabeled
as that changed candidate. Code and documentation are now
consolidated on the implementation branch in the isolated [workspaces](00-conventions-and-provenance.md).

| Area | Implemented | Still to integrate or measure |
|---|---|---|
| Libraries | Stateless evidence-driven core; chronological selection; independently atomic owned groups; retained source observation programs; canonical initialization; typed semantic failures and exact progress lanes | General PostgreSQL adapter and full scenario-catalog evidence |
| Host | PostgreSQL content/history/work/waits/indexes/outbox, fenced commits, strict typed Timelines, bounded discovery and staged prefixes | Runnable general graph processing and continuously operating recovery/discovery/publication pumps |
| Lazy execution | Exact managed-body demands, scoped source reuse and cold replay | Capture/decode peak memory, large histories, genuine large fan-in and sustained fan-out |
| Verification | Owning library suites, host foundation tests and seven actual-library PostgreSQL bridge tests | Full application E2E correctness/fault suite and processing throughput/latency |

See [24](24-phase-1-library-summary.md) for changes relative to the plan and
[25](25-phase-3-integration-plan.md) for the remaining integration work. The default MyOS app still
uses its older processing path; test bridges are not a complete runtime adapter.

## Historical analysis of the starting implementation

**Everything below records the inspected pre-implementation baseline and r15.2 experiment.**
Within this retained analysis, “current”, “not available” and “gap” refer to that snapshot, not the
completed Phase1/2 handoff. Old file/line citations and negative results are preserved to explain
why the repairs were needed. They are not a second specification or current acceptance evidence.

`myos-simple`/MyOS Mini is the host integration target. The Timeline provider in `myos-java` is the
implementation reference for provider-side timestamp, append, predecessor, idempotency, and
completeness behavior. Its older document runtime remains comparative evidence only; no MyOS data,
document-runtime integration, backfill, or migration is proposed.

**[F] Auxiliary chronology experiment (revision 15.2).** Three tests in
`SdkAttachmentChronologyTest` pass against actual Coordination and Contracts. The dedicated
`pocSemanticTest` suite has four failing settled-history replay cases, including late attachment
reconstruction, initial source/parent interleaving and independent replay to newly admitted Roots.
**Prototype requirement gate: FAIL; G1 not accepted.** This is not a full manifest-based acceptance
run. Runtime code is unchanged; test/build additions expose the gap rather than fix it.
No PostgreSQL tests or integrated acceptance are claimed. See the
[Phase-1 chronology report](implementation/phase-1-chronology.md) for exact commands and observations.
At that snapshot the stage was design review. Those earlier test/build edits did not authorize implementation;
r15.5–r15.11 change documentation and the reference sketch only. The current assertions omit a direct child read during the parent's
own historical event and the complete original-event sequence in the T10-attachment variant. The
expanded scenario plan is therefore stricter than the retained executable experiment.

<a id="section-2"></a>
Revision15.7's [source evidence](review/revision-15-7-source-evidence.md) separates existing behavior
from required corrections. Ordinary PROCESS routes an emitted occurrence through frozen ancestors;
the managed closure currently selects immediate bindings. New authored initialization executes
within its introducing closure, while retained-source import has different invocations, epochs and
gas accounting. These are not proven equivalent warm/cold implementations of one invocation.
Receipt assembly retains final state and own emissions, not the complete observable update/admission
sequence needed by a nested observer. See [21](21-semantic-equivalence-and-source-reuse.md).
No runtime fix or new processing-test pass is claimed. These owning-library gaps are in Phase 1.

Revision15.9 selects a proposed managed GAS_LIMIT_EXCEEDED continuation: terminal delivery outcome
without successful consumer epoch/view, followed by next-input processing from actual rollback state
with authenticated source history, terminal-gap evidence and explicit source-before alignment inside
the successor operation. Current exact-predecessor importing
does not implement it. The host plan now requires maintained transactional temporal/ready/reverse-wait
indexes, with concrete PostgreSQL access-plan validation at scale; no implemented capacity is claimed.

**[F/P] Dynamic same-origin scope.** Current `ContractsClosureAdapter.capture` freezes separate
cohorts for one entry. Its managed expansion retains the original invocation's direct deliveries;
the feeder iterates frozen tickets and rejects stale heads/generations rather than providing the
selected cross-candidate join protocol. The Mini dynamic-cycle test starts with A already embedding
B before adding B→A, not initially independent A/B both joining during E. Revision15.11 selects
22 §2.1's pre-edge gas admission with stable seed-local identities and explicit Phase1 controls.
Independent meters remain separate until admission; rejected scope budget fails the initiating
current group, not an over-limit retroactively joined invocation. Inspection is not an executed
demonstration of divergent histories. Fixed semantic policy is preserved; host quota
pause and the first-POC exclusion of general semantic interruption are specified separately.

**[F/P] Runtime failure classification.** Current Contracts specifies RUNTIME_FATAL as deterministic
processing failure after admission, but ScopeHandlerDispatcher catches broad RuntimeException and
maps it through abortRuntimeFailure to that status. This can blur semantic errors and internal/host
failures; the type name alone is not proof of deterministic classification. BEX local-budget exhaustion
can also become a typed ProcessorFailureException/GasLimitExceeded and outer runtime-fatal result.
The planned continuation consumes recognized semantic runtime failures, not arbitrary exceptions.
Phase1 must tighten the owning-library boundaries and portable diagnostics before acceptance.
Current Mini ManagedCatchUpContinuationPolicy labels runtime/gas/portable rollback as retryable;
that does not implement terminal next-input continuation or make identical deterministic retry succeed.

## 2. Verified current state

### 2.2 Current semantic boundary

**[F]** The checked-out Language provider contract distinguishes `FOUND`, `NOT_FOUND`, `UNAVAILABLE`, and `INVALID_EVIDENCE`; unavailable or invalid material is not an empty value (`LANG/blue-language-core/src/main/java/blue/language/api/NodeProviderOutcome.java:3-13`). Current Coordination retains Timeline entries and provider order in an in-memory journal rather than exposing a durable paged completeness SPI (`COORD/src/main/java/blue/coordination/internal/InMemoryTimelineJournal.java:19-33,51-180`). The proposed host boundary must therefore make logical range coverage and authoritative absence explicit without attributing nonexistent provider statuses to the current API.

**[F] Known Timeline identity/order gaps.** Repository semantics define a Timeline as one complete
exact typed value, make timestamps unique and strictly increasing within it, and allow equal
microseconds only across Timelines with a deterministic feeder tie-break (`REPO/src/main/resources/blue/repo/BlueRepository.blue:1941-1978,2015-2034`). `ExternalOrderKey` supplies only a generic typed Integer/Text lexicographic comparator; Contracts requires the environment to bind the actual total-order and completeness policy (`LANG/blue-contracts-core/src/main/java/blue/language/processor/ExternalOrderKey.java:9-16,32-65,97-164`; `CONTRACTS:2345-2380`). Current Coordination instead validates/registers a source by `(raw timelineId, actorId)`, keys predecessor/head/sequence by raw `timelineId`, constructs `(timestampMicros, raw timelineId, exact entry BlueId)`, and drains retained entries without a production provider-completeness gate (`COORD/src/main/java/blue/coordination/api/Timeline.java:6-10`; `COORD/src/main/java/blue/coordination/internal/WholeRequestEntryFactory.java:91-94,119-140`; `COORD/src/main/java/blue/coordination/internal/InMemoryTimelineJournal.java:23-29,116-147`; `COORD/src/main/java/blue/coordination/internal/ContractsJournalDrainCoordinator.java:83-143`).

**[F]** MyOS Mini's newer import store selects an adapter by exact concrete Timeline type and keys
rows by `(timelineTypeBlueId, timelineId)`, but it currently accepts equal timestamps along one
predecessor chain; a test explicitly requires that nonconforming behavior
(`SIMPLE/src/main/java/blue/myos/mini/timeline/TimelineRuntime.java:6-24`;
`SIMPLE/src/main/java/blue/myos/mini/timeline/MyOsTimelineRuntime.java:327-343`;
`SIMPLE/src/test/java/blue/myos/mini/timeline/TimelineImportServiceJdbcTest.java:383-429`). It also
retains imported evidence outside Coordination because the frozen SDK has no provider history/
completeness seam (`SIMPLE/src/main/java/blue/myos/mini/timeline/CoordinationTimelineAuthority.java`).
These are P0 baseline corrections, not behavior to copy into PostgreSQL fixtures.

**[F]** Contracts executes one closed invocation and returns either `Complete` or `NeedsResources`. The affected closure, rather than the physical arrangement of rows, caches, or threads, is the semantic publication boundary. `PROCESS`, `PROCESS_CLOSURE`, and `ADMIT_CLOSURE` have distinct boundaries; `PROCESS_DOCUMENT_STEP` is internal closure decomposition, not a separately authored public commit protocol. Documents execute separately but the complete required closure publishes together; physical separation and lazy loading cannot change the settled history. The feeder supplies evidence and the processor performs no ambient I/O (`LANG/blue-contracts-core/src/main/resources/specifications/blue-contracts-and-processor-specification-1.0.md:23-178,180-259,4320-4368`).

**[F]** The current Contracts model already requires the essential per-invocation outcome: validate
the closed input, execute with invocation-local queues/reuse maps/gas/rollback, and return
`Complete` or `NeedsResources`. `NeedsResources` contains no partial semantic state. A durable host
may acquire immutable evidence before the call and persist a committed workflow cursor between
calls, but it cannot checkpoint Contracts internals. Missing pieces include the stable host seam and revised local source/consumer operation scopes
in18, with ordered local histories and explicit causal dependencies. Keep the working set bounded
without collecting every observing Order into the source call.

### 2.3 Blue Language/Contracts runtime

**[F]** `BlueClosureContracts` accepts a complete `ClosureInvocationInput` and synchronously returns `ClosureAttemptResult`; the input and closure processor are in-memory objects (`LANG/blue-contracts-core/src/main/java/blue/language/processor/closure/BlueClosureContracts.java:23-95`). Retry does not preserve an in-memory continuation: it validates and re-executes an immutable input (`:97-119`). Admission uses `admitClosureWithLifecycleQueue` when admission may enqueue lifecycle work (`:121-169`).

**[F]** `ClosureAttemptResult` is the closed `COMPLETE | NEEDS_RESOURCES` result and canonicalizes typed demands (`LANG/blue-contracts-core/src/main/java/blue/language/processor/closure/ClosureAttemptResult.java:11-116`). The current baseline exposes `EXACT_NODE` and `MANAGED_OCCURRENCE_EVIDENCE` demands (`LANG/blue-contracts-core/src/main/java/blue/language/processor/closure/ClosureResourceDemand.java:5-127`). The POC implements only the demand forms exercised by its scenarios, while keeping the surrounding boundary extensible enough for known direct, source-mediated, and cyclic exact-value verification.

### 2.4 Current Coordination SDK and implementation

**[F]** The public facade owns one long-lived runtime and exposes catalogs and gateways (`src/main/java/blue/coordination/sdk/BlueCoordination.java:7-67`). `SdkCoordinationRuntime` stores handles, definitions, pending managed definitions, and results in process maps; most operations are `synchronized` (`src/main/java/blue/coordination/sdk/SdkCoordinationRuntime.java:43-96,129-759,1095-1140`). The provider is invoked from the runtime critical section (`SdkCoordinationRuntime.ProviderScope`, `:1339-1381`).

**[F]** The engine owns `WholeObjectStore`, `InMemoryTimelineJournal`, `InMemoryDocumentStore`, and process-local indexes and clocks (`src/main/java/blue/coordination/internal/DefaultCoordinationEngine.java:139-205`). `DocumentSession` retains complete history and epoch-state identities (`src/main/java/blue/coordination/internal/DocumentSession.java:21-98,190-213,349-395,512-555`). `WholeObjectStore` retains whole requests, entries, roots, and documents without eviction (`src/main/java/blue/coordination/internal/WholeObjectStore.java:29-52,73-125`), and the repository exact-node cache is another unbounded process map (`BlueRuntime.RepositoryExactNodeCache`, `src/main/java/blue/coordination/internal/BlueRuntime.java:504-555`).

**[F]** `MultiDocumentPublicationTransaction` correctly models package-private copy-on-write, checks heads and generations, and swaps state once (`src/main/java/blue/coordination/internal/MultiDocumentPublicationTransaction.java:37-101,702-1061,1109-1170`; `src/main/java/blue/coordination/internal/InMemoryDocumentStore.java:625-630`). This is a valuable single-process semantic implementation and test oracle; it is not an external durable-transaction SPI. `restartFromStores()` reconstructs indexes from the same live objects rather than from a fresh process (`src/main/java/blue/coordination/internal/DefaultCoordinationEngine.java:1152-1172`).

**[F]** Drain is globally synchronized and sequential (`src/main/java/blue/coordination/internal/DefaultCoordinationEngine.java:998-1129`). `DrainBudget` limits selected entries and committed transitions, not memory, database reads, wall time, or the work inside one closure (`src/main/java/blue/coordination/sdk/DrainBudget.java:3-17`). `AdvancedCoordination` exposes large list-based snapshots, plans, and routes but no durable store contract (`src/main/java/blue/coordination/sdk/AdvancedCoordination.java:22-163`).

**[F]/[I] Terminal-settlement scope needs a narrow reconciliation.** Current Contracts specifies
host terminal idempotency keyed exclusively by `causeIdentity` (`CONTRACTS:2027-2033,5024-5030`).
Current Coordination already uses target-bearing `EventLaneKey` and publication identity
(`ContractsRootFeederWindow.java:273-307,372-379`; `ContractsClosureAdapter.java:3527-3550`), while
Contracts `InvocationId` already binds closure/documents/direct-delivery evidence
(`LANG/blue-contracts-core/src/main/java/blue/language/processor/closure/ClosureIdentityService.java:616-627`).
The specification wording alone therefore does not establish a runtime bug that globally suppresses
R2 after E ran on R1. Phase 1 starts with R1 live/R2–R3 replay, unchanged retry, evidence growth and
overlapping shared-child vectors, then canonicalizes stable target-scoped settlement with the
smallest proven delta. Reuse existing lane/publication machinery where it passes. Separately, revision15.4 deliberately
changes the source/observer semantic scope and therefore requires a targeted identity/gas/receipt
constructor audit; that new requirement is not inferred from the old wording ambiguity alone.

**[F] Top-level Timeline replay is current baseline behavior, not a new optional feature.**
`CoordinationEngine.AdmissionPolicy` defines `FULL_HISTORY`, strict-after `FROM_FRONTIER`, and
`FROM_NOW` (`src/main/java/blue/coordination/api/CoordinationEngine.java:313-320`). The existing
sequential path scans the global canonical journal after one exact cursor and before one finite
cutoff, selects the next entry against the Root's current staged route surface, processes that
entry, advances the exact global cursor, and recomputes graph/routes before selecting another
(`src/main/java/blue/coordination/internal/SequentialDrainCoordinator.java:2028-2084,2488-2510`).
`TemporalAdmissionPolicyIntegrationTest.topLevelHistoryPoliciesUseExclusiveVerifiedFrontiers`
proves the three policies (`FULL_HISTORY=6`, `FROM_FRONTIER=5`, `FROM_NOW=0`), while
`DynamicHistoricalSourceSurfaceIntegrationTest` proves that an earlier historical entry may add
or remove a relevant Timeline before the next entry is selected
(`src/integrationTest/java/blue/coordination/integration/TemporalAdmissionPolicyIntegrationTest.java:50-87`;
`src/integrationTest/java/blue/coordination/integration/DynamicHistoricalSourceSurfaceIntegrationTest.java:62-134`).
This direct replay is distinct from managed-source receipt catch-up: the newly admitted Root did
not exist when those Timeline facts originally settled, so it has no receipt chain that can
replace the replay.

**[F]/[I] Contracts-path gap to repair in Phase 1.** The current Contracts admission entry point
computes a policy frontier and calls `ContractsClosureAdmissionAdapter` directly
(`src/main/java/blue/coordination/internal/DefaultCoordinationEngine.java:512-543`). That adapter's
admission boundary accepts the closure input, policy, frontier, exact-node provider, and optional
managed-epoch selection plan, but no journal-history dependency
(`src/main/java/blue/coordination/internal/ContractsClosureAdmissionAdapter.java:190-219`). No
equivalent direct top-level replay is visible on that call path. This is a gap in the evolving
single baseline to make executable and fix; it is not evidence that the public admission semantics
were removed, and it must not be hidden by the new managed-receipt catch-up protocol.
The revision-15.2 chronology tests now reproduce missing settled-history replay through the actual
SDK/Contracts path. They establish the observable failure, not yet a complete root-cause explanation
or proof that one adapter change resolves every chronology/grouping case.

**[F]/[I] Historical operation eligibility is not just a journal cursor.** The ordinary Contracts
feeder rejects any invocation with an existing member in `SessionStatus.CATCHING_UP`
(`DefaultCoordinationEngine.java:1805-1813`). Contracts can bind reads to the exact pending historical
BlueId (`LANG/blue-contracts-core/src/main/java/blue/language/processor/closure/TentativeResolutionContext.java:148-173`),
but rejects changing a pending historical value outside its managed-revision lane
(`ProcessEmbeddedSurfaceReconciler.java:288-296`; `ClosureInvocationVerifier.java:119-153`). Thus a
proposed chronological A@T20 replacement of B after importing B@T10, with retained B@T30 still not due,
needs explicit supported reconciliation and operation-specific eligibility. A pure-read result does
not prove that replacement/removal works, and importing B@T30 first would violate chronology.
Removal also needs a plan-retirement trace: keeping an inactive binding without an occurrence
transition (`ProcessEmbeddedSurfaceReconciler.java:270-280`) must not strand or execute its obsolete
catch-up suffix (`ManagedCatchUpPlanner.java:92-103,139-153`). This removal concern is a source-based
risk, not a newly executed failure. See [attached source evidence](review/revision-15-3-source-evidence.md)
and the proposed rule in [17](17-causal-processing-model.md).

**[F]/[R] Active-parent atomicity is a baseline boundary to change.** Current Coordination includes
active observing parents via typed incoming demand (`ContractsClosureAdapter.capture`, lines271–276,
and `connectedSelection`, lines949–965). This is not every graph-connected document indiscriminately,
but the common Agreement event / interested Order case does share one call. Contracts uses one
closure gas policy and rolls back all snapshot documents on gas/runtime failure
(`ExecutionPolicy`; `DefaultClosureProcessor:114-139`; `ClosureRollbackResultAssembler:106-147,181-209`).
Revision15.4 intentionally replaces this common live boundary: source commit first, then independently
committed consumers. A failing Order cannot undo Agreement. This requires owning-library work,
not slicing a current result in the host.

**[F]/[R] Retained-history imports supply a useful mechanism, not finished live delivery.**
`ManagedEpochInvocationCapturer:125-139` currently requires an inactive historically pending
occurrence; lines240–267 build a managed-revision cause from a retained source result. The planned
path needs ongoing active subscription/cursor semantics, local failure/gas ownership, per-use
historical reads and gap-free registration/replay/live handoff. Physical consumer lag is not a new
attachment. See [18](18-independent-lineage-processing.md).

**[F]/[R] Identity locality must be reconciled with changed scopes.** Current
`ClosureIdentityService:609-630` binds invocation identity to closure/member/binding/policy inputs,
and `ClosureExecutionSession:1582-1583` derives event occurrence identity from InvocationId.
A source operation cannot include unrelated Order memberships or physical sibling commit heads.
Review the actual constructors for the new local scopes; do not invent replacement host BlueIds
or pretend old combined-invocation IDs remain unchanged.

**[F]/[I] Multi-hop propagation still needs a correctness trace.** Managed event routing currently
exposes immediate containing bindings, whereas ordinary nested processing walks its ancestor chain
(`ClosureExecutionSession:1938-1965,3605-3641`; `ScopePropagationChain:79-120,216-275`).
Both preserve event-time receiver evidence inside a call. The revised inter-lineage protocol must
carry required event occurrences/composed paths through durable source/consumer dependencies with
the correct multiplicity and local ordering. A global all-ancestor transaction or global visited set
is not the repair. Source membership discovered physically early is not temporal eligibility when
consumer topology history is behind; the new receipt/lifecycle frontier must account for this.

**[F] Known unrestricted-frontier defect.** Retained managed-epoch catch-up currently has a
moving required frontier. `ManagedCatchUpPlanner.afterPublication` extends every active plan for
an advancing source using only the source `DocumentId` and epoch
(`src/main/java/blue/coordination/internal/ManagedCatchUpPlanner.java:75-85`), while
`CatchUpPlanStore.withExtendedSourceFrontier` receives neither the receipt's `sourceOrder` nor the
barrier's attachment order (`src/main/java/blue/coordination/internal/CatchUpPlanStore.java:244-280`).
The journal scanner can move its attempt-local `scanAfter` beyond an earlier non-terminal entry
(`src/main/java/blue/coordination/internal/ContractsJournalDrainCoordinator.java:87-132`), direct
work whose closure contains a `CATCHING_UP` member is ineligible, and the external/managed turn is
cut by `DrainBudget` (`src/main/java/blue/coordination/internal/DefaultCoordinationEngine.java:1805-1813,1843-1996`). Consequently, for one already complete journal, changing only drain-call
boundaries can change whether a post-attachment source revision joins catch-up before intervening
direct consumer work. The existing live-extension test intentionally records the moving behavior
(`src/test/java/blue/coordination/sdk/SdkManagedCatchUpBarrierAndLiveExtensionTest.java:62-200,350-485`); it is regression evidence for the defect, not the golden oracle for the repaired semantics.

**[F] Constraints the repair must preserve.** A current barrier already carries a canonical list
of member plan identities, so plan completion and barrier completion cannot be treated as the same
predicate (`src/main/java/blue/coordination/api/ManagedCatchUpBarrier.java:19-45,64-104`). Every
historical application has a response-loss-safe, identity-bearing receipt with work, plan, source
receipt, Contracts result, consumer revision, committed BlueId, and resulting cursor
(`src/main/java/blue/coordination/api/ManagedEpochApplicationReceipt.java:8-67`). The source receipt
store also permits a same-epoch component-representation rebind without creating or replacing a
source receipt (`src/main/java/blue/coordination/internal/ManagedEpochReceiptStore.java:138-184`),
and the bounded-SCC SDK test exercises that behavior
(`src/test/java/blue/coordination/sdk/SdkManagedEpochCycleAcceptanceTest.java:20-87`).

**[R] P0 repair in this POC.** Freeze the external input boundary and historical selection
basis, but distinguish unrelated future input from authenticated causal source progress created
by this root. The latter can legally arise when a catch-up consumer emits back into an existing
containing source. A blanket source gate or immutable target-head rule would prohibit valid
Contracts behavior; final and non-final imports have different activation observations and need
explicit traces. See [18](18-independent-lineage-processing.md) for the current boundary and
[17](17-causal-processing-model.md) for its causal rules.

A zero-step suffix creates no fictitious invocation. Each selected contiguous authenticated receipt
has its own managed invocation/commit, and that commit advances the cursor and creates any next
obligations atomically. The initial replay of an embedded source also needs chronological alignment
with the parent's own history; this is not interchangeable with a later dynamic attachment.

**[F] Eventless receipts are not automatically invalid.** The current source-evidence verifier has
a retained application/result/companion/gas validation path for same-state eventless publication
(`src/main/java/blue/coordination/internal/ManagedEpochSourceEvidenceVerifier.java:150-235`).
Nested/transitive and cyclic publication tests exercise this support
(`src/test/java/blue/coordination/internal/ManagedSameStateEpochPublicationTest.java`).
The repair preserves authenticated eventless history; it rejects a bare unauthenticated state
substitution, not every receipt with an empty public-event list.

After implementation approval, the in-memory path is repaired and tested first; it becomes an oracle
only for laws already independently demonstrated. The PostgreSQL path implements those same laws.
This is one local evolving baseline, not alternate specification profiles or data migration.

The POC must preserve the in-memory runtime and its public capabilities. It adds another
implementation strategy against the same semantics and includes the fixed-cutoff correction; it
does not replace, downgrade, or delete the in-memory runtime.

**[F] Source order is inherited provenance.** Managed catch-up takes `causalOrder` from its source
receipt and passes it into the next consumer epoch without allocating a new external timestamp
(`COORD/src/main/java/blue/coordination/internal/ManagedEpochApplicationExecutor.java:369-375,514-549`).
The receipt mapper retains that order, while the store checks contiguous epochs and exact before/current
representation continuity, not increasing source order
(`COORD/src/main/java/blue/coordination/internal/ManagedEpochReceiptMapper.java:68-83`;
`COORD/src/main/java/blue/coordination/internal/ManagedEpochReceiptStore.java:534-560`). Distinct later
receipts can therefore share an original order; importing older history can also give a later consumer
epoch an older provenance order. Revision 15.1 makes producer-visible cutoff selection and both
counterexamples explicit Phase-1 tests. This does not change strict ordering within a Timeline.

#### 2.4.1 Managed-document identity

**[F]** The active MyOS Mini path configures Coordination with `contentDerivedDocumentIds()` (`SIMPLE/src/main/java/blue/myos/mini/coordination/AuthoredDocumentIdentity.java:30-35`; `SIMPLE/src/main/java/blue/myos/mini/coordination/CoordinationRuntimeHolder.java:518-523`). In this mode:

```text
DocumentId = BlueId(exact initial authored Blue value)
```

`BlueCoordination` and the authored-closure compiler enforce this relation (`src/main/java/blue/coordination/sdk/BlueCoordination.java:108-118`; `src/main/java/blue/coordination/internal/Contracts10AuthoredClosureCompiler.java:138-168`). Consequently:

- the same canonical initial authored value identifies the same lineage;
- formatting-only YAML differences do not create a new lineage;
- two occurrences may reference the same lineage while retaining distinct occurrence identities;
- retry derives the same `DocumentId` without a durable ID reservation;
- a work key or allocation slot may deduplicate an attempt, but cannot be the document identity;
- creating a distinct lineage requires a semantically different initial authored value.

Coordination's existing explicit-lineage mode is not removed. It remains part of the current implementation; it is simply not the identity rule used by this MyOS Mini POC path.

**[F]** `DocumentId` is stable while current exact-state identities evolve. When managed documents form a strongly connected component, the finalizer derives a cyclic master and member exact IDs such as `master#ordinal`; complete exact cyclic-set evidence is required to verify an isolated member (`LANG/blue-contracts-core/src/main/java/blue/language/processor/closure/ComponentFinalizationKernel.java:75-141,213-295`; `src/main/java/blue/coordination/api/ExactValue.java:170-315`). Merge, split, or another state transition may change those exact IDs and the cyclic-set proof. It does not change each member's content-derived `DocumentId` or conflate that proof with managed-graph membership/generation evidence.

### 2.5 `myos-simple`: current host POC

This subsection is the starting point for Phase 2, not the desired host. Merely replacing H2 with
PostgreSQL would leave the global runtime, replay-all recovery, process lock, and multi-instance
hazards intact. Phase 2 must replace or bypass those mechanics on the target-shaped path; Phase 3
then proves the real Coordination integration rather than treating the new database as sufficient.

**[F]** MyOS Mini resolves the single dependency baseline recorded in [Conventions and provenance](00-conventions-and-provenance.md) (`SIMPLE/gradle.properties:5-9`; `SIMPLE/settings.gradle.kts:13-67`; `SIMPLE/build.gradle.kts:31-58,78-102`; `SIMPLE/gradle.lockfile:5-14`). H2 remains the runtime database.

**[F]** MyOS Mini owns one process-local `BlueCoordination`, maps all timeline/document handles and definitions, and protects mutation with a process lock (`SIMPLE/src/main/java/blue/myos/mini/coordination/CoordinationRuntimeHolder.java:27-52,102-218`; `RuntimeMutationGate.java:9-52`). A worker mutates the runtime and a separate `@Transactional` finalizer persists projections, response/proof, `APPLIED`, progress, and work (`CoordinationCommandWorker.java:57-84,387-423`; `CommandFinalizer.java:16-67`). The boundary is therefore **mutate memory → atomically project to the database → rebuild memory from APPLIED commands after uncertainty**, not a storage-backed atomic Coordination commit.

**[F]** Startup reads every APPLIED command, registers exact content, and replays the complete history (`RuntimeRebuilder.java:75-172`). After each replayed command, `RuntimeProjectionVerifier.capture` scans every document, reads complete `handle.history()`, and serializes every epoch (`RuntimeRebuilder.java:122-140`; `RuntimeProjectionVerifier.java:139-165`). **[I]** Repeatedly materializing growing prefixes can make startup time and peak memory quadratic in history size.

**[F]** Useful durable mechanisms already exist: command ordering and the APPLIED marker (`CommandRepository.java:282-323,499-535`), an exact-read ledger (`ExactProviderReadLedger.java:18-34,82-189,334-345`), a five-minute work lease/attempt fence (`HostSafeProcessingWorkRunner.java:25-44,64-206,244-311`), and an atomic database finalizer. However, claims are globally serialized and startup treats all prior claims as belonging to the old single writer (`ProcessingWorkRepository.java:305-490,990-1008`). Two instances can therefore hold divergent runtimes while one reclaims another live instance's work; switching from H2 to PostgreSQL does not solve this.

**[F]** Public invoke commands carry no expected head, epoch, or graph token (`SIMPLE/src/main/java/blue/myos/mini/command/CommandPayloads.java:94-120`). Admission retains values, but readiness does not compare those preconditions before execution (`CommandApplicationService.java:114-128`; `CommandRepository.java:28-80`). Two operations that observed one head may consequently apply in sequence to different heads instead of returning typed `STALE_PRECONDITION`.

**[F]** Demand lifecycle is inconsistent: `BLOCKED` is declared terminal (`CommandStatus.java:3-13`), but resource upload mutates the same command from `BLOCKED` to `QUEUED` and clears its result/error (`ResourceDemandRepository.java:333-370`). This supports a separate non-terminal `Suspended` state and immutable terminal results.

**[F]** Existing integration tests cover cycles, sharing, detach/re-add, historical catch-up, multiplicity, missing exact content, and restart (`SIMPLE/src/integrationTest/java/blue/myos/mini/AllScenariosIntegrationTest.java:59-70,120-147`; `RestartReplayIntegrationTest.java:51-240`). They are useful candidate inputs, but do not prove PostgreSQL behavior, bounded memory, multi-instance correctness, targeted restart, or that a shared child's processor work remains constant as parent count grows. A catch-up fixture that absorbs unrelated post-cutoff input is not promoted as a semantic oracle. The corrected tests distinguish that invalid extension from authenticated same-root causal feedback and prove drain-budget invariance. The POC therefore instruments logical source applications, normal-path processor/Handler executions, retry replays, occurrence routes, and parent reactions as separate counts rather than inferring compute-once behavior from equal final BlueIds.

### 2.6 Repository and BEX

**[F]** `BlueRepository` is an exact-asset provider and mapping layer, not a user-document store or transaction manager (`REPO/src/main/java/blue/repo/BlueRepository.java:27-94`). `RepositoryNodeProvider` owns a manifest and process cache (`REPO/src/main/java/blue/repo/provider/RepositoryNodeProvider.java:26-52,74-109,148-169`); the manifest maps current IDs rather than managed-lineage history (`REPO/src/main/java/blue/repo/RepositoryManifest.java:76-92,182-213`). It must not become the MyOS persistence SPI.

**[F]** BEX executes a compiled program, patch, or event and returns a result without persistence (`BEX/blue-bex-core/src/main/java/blue/bex/api/BexEngine.java:22-28,86-149`; `BEX/blue-bex-core/src/main/java/blue/bex/result/BexExecutionResult.java:11-18`). `BexDocumentView` is a synchronous pointer callback without versions, batching, suspension, or transactions (`BEX/blue-bex-core/src/main/java/blue/bex/api/BexDocumentView.java:6-17`), and overlay rollback is transient (`BEX/blue-bex-core/src/main/java/blue/bex/runtime/BexRuntime.java:91-114`). Bounded program/pointer caches and the rich compile key are patterns worth retaining (`BEX/blue-bex-core/src/main/java/blue/bex/compile/LruBexCompiledProgramCache.java:9-33`; `BEX/blue-bex-core/src/main/java/blue/bex/pointer/BexPointerCache.java:11-43`); BEX is not a storage boundary.

**[F]/[P]** The engine already derives its full compilation key from source plus compiler/runtime,
gas manifest/weights, Language registry and intrinsic environment (`BexEngine.java:119-131`). Reuse
that key, not the default one-argument `BexCompiledProgramKey.from(source)`. Its current
get→compile→put path (`BexEngine.java:66-79`) has no atomic per-key loading seam, so a weighted LRU
adapter alone cannot implement miss coalescing. A small BEX loading seam or full-key compiler wrapper
belongs to Phase 1; concurrent-miss/cancellation/byte-budget proof belongs to integrated verification.

### 2.7 MyOS Timeline foundation and selected durability evidence

**[F]** The `myos-java` Timeline provider already implements the target per-Timeline foundation:
PostgreSQL epoch microseconds, one pessimistic Timeline-row lock shared by append and guarantee,
`max(dbNow, lastTimestamp + 1, guaranteedBefore)` allocation, an exact predecessor chain,
sequence-free canonical Blue entries, append idempotency, and an exclusive exact completeness proof
(`MYOS/modules/myos-core/src/main/java/blue/myos/core/timelines/timeline/repository/PostgresTimelineClockRepository.java:18-27`;
`MYOS/modules/myos-core/src/main/java/blue/myos/core/timelines/timeline/domain/TimelineHead.java:32-44`;
`MYOS/modules/myos-core/src/main/java/blue/myos/core/timelines/timeline/repository/TimelineRepository.java:25-28`;
`MYOS/modules/myos-core/src/main/java/blue/myos/core/timelines/timeline/service/guarantees/TimelineGuaranteeService.java:55-96`). This is reused rather than redesigned from MyOS Mini.

**[I] Cross-Timeline gap owned by this POC.** A per-Timeline provider proof does not by itself prove
that every Timeline relevant to one canonical-journal domain is complete beyond a candidate. The
POC therefore binds an immutable exact active-Timeline set at bootstrap, consumes one complete
per-member proof cohort, and provides a guarantee-only ingress operation because a quiet Timeline
may advance completeness without appending an entry. Dynamic source membership is not required for
the first POC; changing the set changes the semantic configuration and resets isolated state.

**[F]** The adaptation boundary is equally important. Provider rows and proofs identify a raw MyOS
`timelineId`; the older document feeder orders equal timestamps by
`(providerId, orderingChannelName, timelineId)` and does not persist an external-order policy
identity (`MYOS/modules/myos-core/src/main/java/blue/myos/core/documents/processing/domain/DocumentPhysicalInput.java:18-22`). The new adapter binds the verified proof to an exact typed Timeline BlueId and uses
`(timestampMicros, exactTimelineEntryBlueId)`. Provider-local sequence remains locking/paging
metadata. See [`15-myos-timeline-foundation.md`](15-myos-timeline-foundation.md).

**[F]** `myos-java` uses an older document-centric model. `BlueRuntime.process` accepts one complete `ResolvedSnapshot` and one Timeline Entry and has no managed-lineage closure, demand, read/write set, or proposal (`MYOS/modules/myos-core/src/main/java/blue/myos/core/blue/BlueRuntime.java:8-59`). It is not the normative semantic target. After invariant review, selected whole-resolved inputs may still serve as differential test inputs; Contracts and the corrected Coordination baseline define the expected result.

**[F]** Its useful implementation patterns include single-document optimistic concurrency and replay expectations, prewritten immutable objects, a bounded transaction after off-transaction preparation, durable events/work, and multi-worker leases and recovery (`MYOS/modules/myos-core/src/main/java/blue/myos/core/documents/processing/protocol/ProcessorBatchReplayExpectation.java:17-193`; `MYOS/modules/myos-core/src/main/java/blue/myos/core/documents/runtime/service/DocumentEpochCommitService.java:226-307`; `DocumentEpochCommitTransaction.java:249-419`; `MYOS/modules/myos-core/src/main/java/blue/myos/core/workers/repository/WorkerTicketLeaseStore.java:193-334,470-558`). It does not own or atomically commit a managed affected closure. Patterns may be reused only after they pass MyOS Mini's tests; no platform or data migration is proposed.

### 2.8 Current failure boundary

| Flow | Current semantic decision | Current memory/read behavior | Current durable behavior and failure consequence |
|---|---|---|---|
| Start public/root document | `SemanticCommandExecutor` validates the root and passes the exact authored source to Coordination (`SIMPLE/src/main/java/blue/myos/mini/coordination/SemanticCommandExecutor.java:172-186,308-397`). | Provider data, handles, definitions, and history remain resident. | The SDK mutates RAM before `CommandFinalizer` persists projections and APPLIED. A crash between them requires replay. |
| Timeline append and PROCESS | Coordination selects canonical work after an exact entry append (`SemanticCommandExecutor.java:210-245,940-1052`). | Global journal, route index, and full document state are resident. | Entry/command state and projections cross two commit boundaries; uncertainty triggers verified rebuild. |
| Static or dynamic managed closure | Coordination discovers occurrences, drafts, graph changes, and the affected closure (`SemanticCommandExecutor.java:308-397,787-937`). | All member bodies, histories, graph state, and results are resident. | The in-memory COW publication is atomic, then a separate database transaction projects it. |
| Historical catch-up | Coordination selects current, initial, epoch-zero, or retained exact state and advances occurrence progress, but the current moving frontier can absorb post-attachment revisions and make drain boundaries observable. | Source history and catch-up plans have no page/hydration boundary; attempt-local scanning may pass an earlier non-terminal entry. | Per-occurrence cursors and barriers are projected, but the semantic cutoff is not enforced and recovery still rebuilds the global runtime. |
| Missing exact evidence | A synchronous provider and `CoordinationResourceBlocker` produce exact/occurrence demands (`PersistentExactNodeProvider.java:12-61`; `CoordinationResourceBlocker.java:48-162`). | The provider may read the database while the global runtime remains held. | Demand/waiter data is durable, but terminal `BLOCKED` is later mutated back to `QUEUED`. |
| Restart | `RuntimeRebuilder` replays every APPLIED command (`RuntimeRebuilder.java:75-172`). | The complete exact corpus, all documents, and repeated full histories are loaded. | Database state is recovery authority, but recovery cost grows with total historical state rather than touched work. |

**[I]** MyOS Mini demonstrates most of the required current behavior but inherits the known moving-frontier catch-up defect and has an unsuitable runtime/storage boundary. `myos-java` supplies the mature per-Timeline provider foundation plus useful durability patterns for an older document unit. Phase 1 repairs fixed-cutoff and graph-causality behavior inside Coordination; Phase 2 reuses/adapts the Timeline foundation and builds the minimum target-shaped durable host protocol in MyOS Mini; Phase 3 combines and validates them without migrating existing data.

<a id="section-4"></a>
## 4. Baseline SDK capability and gap matrix

| Capability | Existing in-memory behavior | Host work possible now | Gap to test in the POC |
|---|---|---|---|
| Exact Timeline order and completeness | Generic order tuple plus raw-ID journal; strict time inside the current raw Timeline, but no production completeness gate | Exact typed Timeline import exists outside frozen Coordination authority | Exact Timeline identity, strict microsecond validation, bound `(timestampMicros, entryBlueId)` policy, equal-time vectors, and completeness-gated selection |
| Deterministic ordinary PROCESS | Supported | Command/idempotency wrapper | Durable execution against the same semantics |
| Static closure and bounded cycles | Supported while resident | Admission and projection | Batched lazy capture, seconds-scale hot path, exact invocation replay, and equivalent settlement |
| Content-derived managed draft | Supported for the current baseline | Validate derived ID and idempotent admission | Durable identity handling without host-assigned `DocumentId` |
| Attach current/retained lineage | Exact selection exists; unrestricted extension can admit unrelated future input | Persist receipts, source order, fixed cutoff, completeness, and barriers | Coordination-owned external boundary, causal source progress and typed lazy receipt access instead of runtime maps |
| Top-level full-history admission replay | Implemented by the sequential baseline with one global cursor and dynamic route-surface recomputation; the Contracts admission path does not yet expose the journal dependency | The host can supply exact paged successor/end evidence | Preserve the baseline semantics through a scalable portable global-order protocol and add the missing Contracts-path integration; do not replace it with managed receipt catch-up |
| Exact content | Synchronous whole-value lookup | Database callback/read ledger | Typed outcomes, batching, authorization, limits, and durable demands |
| Lazy document/head/history access | Not available | Global rebuild workaround | Request-scoped reads, immutable pages, and explicit evidence demands |
| Eviction and targeted reopen | Not available | Full rebuild | Stateless attempt and bounded caches |
| External database commit | Not available | Project after RAM mutation | Typed prepared change and atomic commit port |
| Optimistic concurrency | Internal COW checks | Global serialization | Complete semantic/storage read set and typed conflict |
| Local settlement and independent observers | Current active observers share a closure swap; historical imports are separate | Persist local results and durable receipt/discovery/cursor state | Owning-library independent scopes, local failure/gas, scoped causal readiness and separate complete-cause coverage |
| Crash/provider-outage recovery | Same-process stores | APPLIED replay | Exact-input replay or committed-step resume; no portable mid-invocation continuation |
| Multiple instances | Not supported by current runtime ownership | Single writer can constrain the experiment | Reentrant request-scoped processing; leases optimize, CAS proves correctness |
| Bounded work | Gas and drain counters only | Operational quotas | Read/byte/closure/history/demand budgets |
| Typed suspension | Contracts has demands; SDK paths diverge | Host can normalize results | One durable non-terminal suspension lifecycle |
| Development changes | Current local stack can be recorded | Reset the isolated POC database | No compatibility, version selection, or migration layer |
| Tenant isolation | Not part of current SDK | Authorize before calls | Tenant in every durable key/session and visibility fence |

**[I] Correct but inefficient control:** a single writer for an entire realm, a command log, complete deterministic replay, and comparison of all projections can preserve correctness for a small test host. Startup, memory, and read amplification nevertheless grow with the entire system.

**[R] A repository callback alone is insufficient.** Current algorithms copy complete `DocumentSession`, journal, and map state, scan global state on selected paths, and invoke providers under the runtime monitor. A storage callback without request-scoped ownership would move I/O under a global lock while retaining unbounded residency. The experiment therefore needs explicit attempt state, targeted indexes, and immutable pages. The existing in-memory adapter remains available throughout as the fast reference adapter; its result becomes a semantic oracle for a scenario only after the relevant invariants pass.
