# POC baseline31 reconciliation

Status: source reconciliation only; no build, test, artifact qualification or
commit is claimed by this record. The previous POC worktree remains unchanged.

## Exact boundary

- New base: merged Coordination `69cd6db22707028abfbaab04741dc9b61961aa19`
  (RC11); no open PR head is an input.
- Net POC donor: `d220a50bb320fba67a6215ec0efdfd8ed3104928` to
  `b259347d221f92653bf7cd1534e77a0833015db8`.
- Applied in `codex/poc-baseline-refresh-20260916`, in the separate
  `rooted-external-coordination-baseline31` worktree.
- C03 remains deferred. The new merged Language identity/minimizer direction is
  authoritative; this Coordination port does not restore its older alternative.

## Problems, resolutions and rationale

| Problem / example | Resolution | Why / rejected alternative |
| --- | --- | --- |
| The minimal baseline omitted an early LIVE search bound, while POC chooses local and LIVE candidates from one captured view. A retained candidate at T20 must not require capturing a later LIVE T30 candidate that cannot win. | Keep the complete POC `nextRootLiveInput` overload chain, strict `>=` cutoff, `nextRootInputCandidates`, and driver use of `candidates.live()`. | Same selected input and tie order; less losing work. Do not combine shared capture with an additional independent LIVE search. |
| Baseline constructs fresh representation proofs; POC reopens the same durable publication through multiple short-lived readers. Dropping the memo would discard already measured reuse and break its storage wiring. | Retain `ManagedRepresentationVerificationMemo`, store proof methods/current-membership checks, bounded cache association, and restart/close clearing. Keep history's staged-versus-retained selection and ordered before/after checks. | Reuse remains pure and exact; it never replaces current publication authority. Do not accept the modify/delete as a cache deletion or retain proofs for uncommitted proposals. |
| Baseline copies an already immutable cyclic proof container, while POC retains it with defensive extracted Nodes. | Retain POC `ManagedEpochSourceEvidenceVerifier` proof-container sharing and the mutation/later-provider-failure controls. | Physical reuse only; provider outcomes are still reacquired. Do not add a trust flag or change exact proof semantics. |
| Historical POC carries an inert `CohortAdmission` wrapper and unused `peerPrefixes` overload. | Adopt merged baseline's direct `executeAndPublish` callback and three-argument attachment selector. | All wrapper outcomes were non-null and the unused map was always empty. No operation, gas, publication or retry policy changes. |
| Baseline tests deliberately required fresh proof identity because that minimal build omitted caches. | Keep their semantic/authority assertions; change only the repeated valid read assertion to `assertSame` for POC. Retain the separate memo and cutoff test owners and fixture seam. | Production observations remain identical; allocation behavior intentionally differs. No failure, gas, order, identity or corruption oracle is weakened. |
| The incoming build describes the smaller baseline implementation. | Retain reviewed POC storage API allowlists and immutable-development controls; adopt RC11 metadata and published Language RC27 defaults. Re-measure shape: 315 production sources, 85,912 lines, 94 public API/SDK source types. | These are physical maintainability guards, not protocol capacity changes. Do not restore RC9/RC25 defaults or broaden API exemptions. |

The five textual conflicts were `build.gradle`, `ContractsClosureAdapter`,
`InMemoryDocumentStore`, `ManagedRepresentationHistory`, and
`RootedCheckpointDriver`; the memo was additionally modify/delete.
An automatic merge also removed the strict cutoff, clear hooks and fixture-only
bound helper without conflict markers: these were explicitly restored.

Against the old POC production tree, the behavior-neutral reconciliation changes
five files (+22/-68 lines): the accepted wrapper/callback simplification, inert
attachment overload removal and upstream Compute pointer formatting. Two more
production files lose only a trailing blank line (+22/-70 lines overall).
Storage codecs, cache policy, byte-cap compatibility, reference validation,
history ordering, publication fences and failure classification are preserved.

## Retained controls and pending qualification

- `ManagedEpochIndirectComponentRebindTest`: immutable capsule sharing,
  detached-member mutation, fresh source reacquisition and provider failures.
- `ManagedRepresentationVerificationMemoTest`: exact complete constructor keys,
  eviction/weight limits, current durable authority, cold restart, staged/abort
  exclusion. Its `Scenario` is also required by POC durable integration tests.
- `ManagedRepresentationHistoryEvidenceTest`: incoming baseline correctness
  controls retained; only warm repeated proof identity follows POC reuse.
- `RootedLiveSelectionCutoffTest`, `RootedLiveSelectionOrderTest`,
  `RootedCalculationFixture.nextLiveInputBefore`: retain baseline order checks
  and POC equal-bound/no-capture controls.
- All POC storage/cache owners remain, including
  `RootedStorageResultProfileIntegrationTest` and same-epoch/cold-owner controls.

The seven SDK bindings currently remain those of merged RC11. They must be
authenticated against the newly exported Language POC tuple before any Gradle
development invocation; `gradle/development-catalog.gradle` intentionally
checks them during configuration. Rebind only measured differences, preserving
the merged baseline specification and C03 deferral. Baseline-generated topology
artifacts are retained as the starting oracle; do not manually replace scenario
hashes with old POC identities.

Qualification requires the final coherent artifact tuple, compilation/API/shape
checks, retained optimization controls and baseline behavior controls, followed
by MyOS resident/PostgreSQL confirmation. Prior baseline or batch30 passes do not
qualify this assembled source. No remote action is part of this reconciliation.
