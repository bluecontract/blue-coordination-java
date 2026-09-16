# Coordination baseline reconciliation — 15 September 2026

## Final local qualification and published delivery — 16 September 2026

The reconciled local baseline is **PASS in the agreed C03-deferral scope**:
Coordination `df40069e3112f266bee3fc8014f378bf2eba6ef1`, Language
`9a7f0356af29c725cd9d6924a167db0aa5bd5dcc`, MyOS
`c6705318f79021ab0df67aaea69cdee9a45d245c`, unchanged BEX `ab72af14` and
Repository `0b68744b`. Sealed bundle03 candidate SHA256 is
`cc53f8ec781eba9a6ba8d577e7733289d09026f6c8922ffa082df1bac5f63ac0`.

- Coordination: complete Java17 and Java21 `releaseCheck` lanes, each with
  1186 primary + 54 extracted-source executions; zero failures/errors/skips.
  Scope, API, dependency, source-artifact and archived-result audits passed.
- Final immutable handoff: the same three exported-artifact consumers pass
  on Java17 and Java21 (six executions); exact runtime dependencies verified.
- Language: 3796 clean-build cases, final quality/RC gates, 394 fresh profile
  executions, 480 release fixtures and 299 controls pass.
- MyOS: 1367 required passes and exactly the single agreed C03 skip, across
  source, integration, packaged and independent HTTP scopes. The original
  full ring/chord/reconnect/restart case passes normal deadlines, without a
  further exclusion or increased acceptance timeout.
- BEX/Catalog native results (968/66 passes) remain applicable to authenticated,
  byte-identical sealed runtime payloads; no source changes in either repository.

These are the completed local-candidate results, not fresh published-configuration
results. Language PR #41 subsequently merged and released `3.1.0-rc.27`.
All six published Language runtime JARs match bundle03 byte-for-byte; POM changes
are version-only, both required additive APIs exist, and all seven SDK bindings
match. This branch now transitions to that published Language tuple, retaining
BEX `1.1.0-rc.6` and Repository `3.0.0-rc.22`, with coherent RC11 release
authority/readiness metadata. No runtime correction is part of that packaging
transition. Both complete published Java17/21 PR jobs remain required before
merge. Existing historical receipts and the sections below retain their original
source/version scope; their earlier pending statuses do not supersede this one.

The detached B→C path rule is the single explicitly approved normative correction,
implemented in Language/Contracts and consumed here. Other retained families
are implementation/API corrections and the documented bounded eligibility memo.
No POC history cache, verified-artifact reuse mechanism or C03 algorithm change
is included.

## Source and qualification boundary

The writable branch `codex/baseline-reconciliation-20260915` starts at pinned,
already merged `origin/next` `c095f0f3321257531096289eb27a5f439c5011fb`.
The only correction donor is frozen `d220a50bb320fba67a6215ec0efdfd8ed3104928`.
Their merge base is `b5f7767de95ad494b46667b7e91369c7ab3c6a41`.
No current PR head or POC source is an input to this reconciliation.

The pinned base contains the Named Compute implementation from merged PR18,
its canonical root-pointer follow-up `1a14e0f`, and RC10 metadata. Since the
historical comparison `0a047461`, upstream changed no rooted processing source.
Its only additional production changes relative to the common ancestor are in
Compute normalization and static-type pointer composition. RC10 release metadata,
release guards and release notes were the pinned reconciliation inputs. The
published successor is described above; historical release notes remain unchanged.

The frozen review and linked correction records establish the earlier failures
and qualified donor behavior. They do not qualify this newly assembled source or
its new Language dependency. Fresh qualification was required rather than
inherited from the donor; its completed results are summarized above.

The local [historical correction record](rooted-baseline-release-fixes.md) extracts
the concrete failures, rejected alternatives, API/behavior impacts and regression
owners from the frozen registry, making this review independent of another local
checkout. The [rebind procedure](baseline-reconciliation-rebind.md) records the
maintained files and commands for the new Language artifact tuple.

## Approved baseline-first scope

The user has deferred the whole nested/contextual identity family (C03) to a
separate follow-up. This includes the earlier donor/e101 explicit-versus-inherited
type policy and Source-aware minimizer change, not only the uncommitted contextual
successor. Language will retain pinned merged `450230c1` Core identity/minimizer
behavior while keeping the other five baseline families: legal later detached
retargeting, numbered source-epoch continuity, coherent immutable witness contexts,
fresh same-cause witness selection, and Named Compute contribution provenance.

Coordination needs no production change for this deferral. It does not call the
Source-aware minimizer overload; its required Language additions remain
`WorkingDocument.sourceContributionsAt(String)` and
`ClosureEvidenceFactory.rootedWitnessSelection(...)`. Static separation does not
prove runtime independence: the reduced candidate requires fresh sealed artifacts,
maintained generation, native/consumer gates and complete application acceptance.

The two contextual four-form SDK cases introduced in `c2ce06d4` are removed from
the baseline owner, with their exact source preserved in that commit for the
deferred follow-up. This is a scope deferral, not skipped tests or a passing run.
The owner is restored byte-for-byte to `94d73e1c`. All pre-existing upstream and
retained baseline assertions remain in scope, including redundant canonicalized
definition maps, raw exact-provider rejection, canonical child copying, history,
ordering, gas, failure and identity checks.

The recorded generation02/e101 inputs and qualification below are historical.
They do not qualify the reduced source. The active profile still contains the
pinned Language specification `sha256:77b48506ff7b5ddbab26b98ce9e060e943cb3babf1e6f5511085bbb3c31c4144`;
no deferred contextual specification or API binding has been imported into
Coordination. Rebind generated inputs from the new sealed candidate using the
[maintained procedure](baseline-reconciliation-rebind.md), without manually
rewriting semantic outcomes. The later published-default transition is recorded
separately above.

## Disposition

Historical first reduced-candidate inputs (16 September 2026) were bound to sealed Language
`9204d7fe7064245565c7c609527967aaa968209b` in `baseline-without-c03-01`.
The complete Language/BEX/Catalog artifact closure and all seven owning-artifact
identities match the reviewed profile; only four profile values and two exact
test literals change. The pinned specifications, gas schedule, RC10 defaults and
runtime source remain unchanged. All 123 focused cases passed; the 32 regenerated
topology records were reviewed as consistent identity-only substitutions. Exact
parameter attribution is supplied by a same-run HTML/XML audit supplement; its
original display-name-parser failure remains preserved. Native/consumer and
application qualification were pending at that checkpoint; final bundle03
results above supersede that status.

| Problem / example | Pinned upstream solution | Disposition | Remaining delta | Regression evidence retained |
| --- | --- | --- | --- | --- |
| Named Compute must execute a sibling, pointer or exact definition. | Merged implementation and shared helpers are present. | Already solved | Keep the upstream resolver, plan and static-type design. | Existing `SdkNamedComputeDefinitionTest` and `ComputeProgramNormalizerTest`. |
| Root `/` definition builds `//functions/...` paths. | `1a14e0f` uses canonical `PointerUtils.appendPointer`. | Already solved | No competing pointer implementation; retain upstream positive test and frozen root/nested negative controls. | `shouldValidateStaticTypesThroughTheDocumentRootDefinition`, `rootAndNestedDefinitionPointersUseTheSameStaticTypeRules`. |
| An exact provider's explicit `{type: Dictionary}` map is rejected when selected directly but erased by named selection. | Upstream still omits empty inherited maps by effective equality. | Still needed | Frozen exact contribution presence mask and presence-aware plan cache key. | Exact provider absent/empty/declaration and inherited/referenced/partial-overlay cases, normalizer presence controls. |
| Canonical Source ingress removes redundant authored inherited-only maps. | Upstream correctly tests identical canonical identity and successful execution. | Preserve upstream behavior | Keep that upstream test as well as the separate exact-provider controls; discarded Source text is not retained provenance. | `shouldTreatARedundantAuthoredDefinitionMapAsTheInheritedDefinition`. |
| Static FROM_NOW or FULL_HISTORY admission substitutes replay beginning E0 for frozen terminal E3. | Rooted code unchanged from historical base. | Still needed | Retain exact admission-owned endpoint/prefix evidence separately from the start. | `RootedStaticAdmissionCutoffTest`, `RootedFullHistoryAdmissionFrontierTest`. |
| A selected X→B view is mixed with independently detached X; historical work repeats its original LIVE selector. | No merged correction. | Still needed | One frozen prerequisite graph; prospective selectors remain scoped to LIVE. | `RootedJoinPrerequisiteViewTest`, `RootedLocalHistoryRecoveryTest`. |
| Exact provider-authored source is reparsed and changes identity. | No merged correction. | Still needed | Internal exact-authored compiler entry and discovery handoff. | `RootedExactProviderAdmissionTest`. |
| Copying saved C0 through expanded `$document` invents a new content identity. | No merged correction. | Still needed | Current working snapshot supplies canonical body/identity separately from resolved semantics. | `RootedNestedDocumentValueIdentityTest`, adapter exact-value controls. |
| A retained receipt and the same frozen exact source normalize ordinary wire content differently. | No merged correction. | Still needed | Reuse matching authenticated frozen source wire after full receipt/source checks. | `RootedRetainedSourceWireTest`, exact result/trace and G−1 controls. |
| Payment0 is classified against physical Payment1 and creates impossible 0→0 history. | No merged correction. | Still needed | Classify new paths at their authenticated numbered logical activation position. | `RootedBorrowedCycleReadinessTest`, schedule parity, genuinely older/same-epoch and gas negatives. |
| Ring planning omits a required same-epoch terminal successor. | No merged correction. | Still needed | Share exact staged/committed consumer context between planning and capture. | `RootedRingChordSuccessorReproductionTest`, full duplicate-occurrence ring and staged-context negatives. |
| Related LIVE work overtakes a pending join; a terminal sees stale immutable peers or misses original receiving work. | No merged correction. | Still needed | Retain join prerequisite/fence scheduling and fresh-terminal peer acquisition; no original LIVE peer replacement. | Automatic join, diamond, dormant, SCC-entrypoint, original-forwarding and publication-safety owners. |
| Detach B, then attach C at the same path in a later operation is rejected. | No merged correction. | Adaptation needed | Preserve the user-approved legal retarget and authenticated retry demand while keeping original rows immutable until PROCESS. | `RootedDetachedAuthoredRetargetTest`, `RootedRetargetInputTest`, resolver/authored historical controls. |
| Removed-operation transport is consumable but audit reports NONE. | No merged correction. | Still needed | Share transport completion eligibility with selection. | `RootedTransportSelectionTest`, journal-cutoff and blocked-history negatives. |
| Short drains repeatedly select a failing historical owner; future T300 prevents retained T200 progress. | No merged correction. | Still needed | Persist fair scheduling turns; bounded Journal calls yield to eligible retained work without extending cutoff. | `RootedSlicedSelectionTest`, `RootedJournalCutoffFairnessTest`, history failure/retry controls. |
| Managed attempt gas is absent from drain total; a three-transition joint application reports one. | No merged correction. | Still needed | Sum completed non-replayed attempts once; use actual owned PROCESS transition receipts for the existing budget. | `RootedManagedDrainGasReportingTest`, `RootedAutomaticJoinSchedulingTest`, existing mapper owners. |
| Empty source actions cannot distinguish satisfied from stale; global audit hides explicit root B's managed step. | APIs absent. | Still needed | Original-request-bound observation and exact-root audit, with no execution/reservation authority. | `RootedSourcePrerequisiteObservationTest`, `RootedExplicitSelectionAuditTest`, local/join/blocked companions. |
| Repeated exact eligibility classification occurs during the 54-program restart that exceeded its existing 120-second deadline. | No equivalent merged implementation. | Retain demonstrated acceptance-related correction | Keep bounded complete-input eligibility memoization. A later combined host/library run passed at 73.311 seconds; this is concrete acceptance evidence but does not isolate the memo's speedup. | `RootedEligibilityCacheTest`, `BlueRuntimeEligibilityCacheTest`, `RootedEligibilitySelectionTest`; unchanged original MyOS restart deadline. |
| Immutable proof-container copies, cross-call representation verification and losing LIVE capture appear in hotspot samples. | No equivalent merged optimization. | Omit optional physical deltas | Preserve upstream defensive copies, fresh representation constructors and ordinary LIVE capture. Samples and combined passes do not establish these three independently necessary for the first minimal candidate. | Preserve donor semantic corruption/restart/order controls under the ordinary implementation; omitted counter/eviction-only methods listed below. |
| Historical diagnostic drivers bypass normal original work or acquire peers before the fresh terminal. | No merged correction. | Still needed test adaptation | Retain final frozen ordinary-selection ring/diamond drivers and their complete histories, result/gas equality and shared call bounds. | `RootedDiamondOriginalDriverDiagnosticTest`, `RootedSavedOriginalGraphTest`, unchanged SCC control. |
| Runtime/specification identities and source shape change. | RC10 release metadata is current; frozen donor hashes are from old Language. | Adaptation needed | Measure exact new shape; regenerate/rebind maintained fixture/profile evidence to the new candidate using supported tooling. Do not copy old release defaults or weaken gates. | Native source/API/quality, generated fixture and exported-consumer gates. |

## Required APIs and compatibility

The pinned merged Language does not provide the frozen
`WorkingDocument.sourceContributionsAt(String)` or
`ClosureEvidenceFactory.rootedWitnessSelection(...)`; both remain necessary for
Coordination's other baseline fixes. The earlier inventory also listed
`MinimizedOverlayBuilder.build(FrozenNode, Node, CanonicalTypeIdentityLookup)`,
but Coordination has no code consumer of that overload. Its removal with the
whole deferred identity family requires no Coordination adapter. Coordination
uses only the retained frozen contracts and pinned merged APIs, not an open PR.

For named definitions, `canonicalAt` cannot traverse arbitrary verified sibling
references and `resolvedAt` cannot distinguish explicit exact presence from type
defaults. `SelectedExecutableBody` only grants the executing body capability.
The contribution query therefore remains necessary for the exact-provider
counterexample. Its `$replace` slot correction belongs to the new provenance
selector, not to upstream list resolution. The presence mask is evaluated for
each invocation, including after working-document patches, and participates in
the existing plan key. Ordinary Source canonicalization remains authoritative.

The frozen rooted corrections are source deltas against production files that
upstream has not modified. Their retained records explain the concrete failure,
library ownership, rejected alternatives, contract impact and positive/negative
controls. The frozen registry's dated pending statuses are historical: consult
the 13 September frozen consolidated review for donor qualification and the new
parent report for this candidate's results.

## Optional optimization omission and retained semantic coverage

The first minimal candidate omits three independently optional frozen groups:

- `ManagedEpochSourceEvidenceVerifier` retains the pinned upstream defensive
  `CyclicSetProof` copies. The donor mutation, retained-source authentication,
  recapture, exact input and later-provider-failure controls remain. The test
  `verifiedSourceCapsuleSharesProofWithoutExposingMutableMembers` becomes
  `verifiedSourceCapsuleCopiesProofWithoutExposingMutableMembers`; object identity
  now asserts the upstream copies, while every semantic assertion remains.
- `ManagedRepresentationVerificationMemo` and its store hooks are omitted.
  `ManagedRepresentationHistory` uses its original fresh constructor, retaining
  the coherent per-call history sharing and exact staged/consumer contexts.
  Five donor methods survive in `ManagedRepresentationHistoryEvidenceTest`:
  repeated reconstruction/restart/mutation, later-numbered-receipt rejection,
  complete constructor operands and invalid inputs, current durable authority
  corruption, and actual staged/publication-abort controls. Exact position
  equality replaces cache-object reuse expectations. The one omitted method is
  `entryAndKeyWeightLimitsEvictWithoutChangingExactProofs`, whose eviction and
  cache-size policy has no implementation in this candidate.
- The early LIVE search bound and its fixture-only helper are omitted. Three
  donor methods survive in `RootedLiveSelectionOrderTest` with ordinary LIVE
  reads, preserving all retained-order, event, gas, isolation and restart
  assertions. Only bound/capture-counter assertions are removed. The omitted
  method is `anEqualFirstEntryIsNotCapturedAndTheUnboundedCallStillSelectsIt`,
  which exists solely to prove zero capture under the absent optimization.

The two method omissions above are deliberate scope changes, not skipped tests
or passing executions. The existing upstream test corpus remains enabled. New
acceptance evidence may motivate a separate reconsideration; no POC source is
authorized by that possibility.

## Historical generation02 implementation status

This section records the original combined source and is superseded by the
baseline-first scope above for the next candidate. No reduced-source pass is
claimed by its historical status or bindings.

Source and regression reconciliation is complete pending artifact authentication,
topology regeneration and actual qualification. Measured production shape is 253 sources, 74,084 lines
and 87 public API/SDK source types; the retained-source per-file cap is unchanged.
The source-shape limits record this exact delta while preserving all RC10 release
gates. The full frozen historical documentation remains in its read-only donor
directory; its necessary review record is extracted in the linked local registry.

The upstream redundant canonicalized authored-map tests and root-definition
positive test remain intact alongside all frozen exact-provider diagnostics.
The working profile and release/fixture assertions now bind the parent's reviewed
Language generation02 package. All seven input identities were checked against
that staged package and current Language runtime constants; five changed. This
is an applied input binding, not owning-JAR or sealed-export authentication.
Those artifact checks and complete maintained topology regeneration remain
pending. The topology JSON/Markdown outcomes have not been edited or rehashed.
The [rebind procedure](baseline-reconciliation-rebind.md) records the exact
applied identities and remaining validation boundaries.
No new build/test pass, commit, release, published dependency qualification or
merge readiness is claimed.

The frozen `CohortAdmission`/`prepareAndPublish` wrapper and feeder plumbing are
also omitted: its only record component is required non-null and every return
simply wraps an ordinary `CohortOutcome`. The pinned `executeAndPublish` method
already preserves identical completed results, failures and gas. Retain that
upstream method and callback shape. The unused original-stage attachment
`peerPrefixes` overload always received `Map.of()`; remove that inert hook so
fresh-terminal acquisition is the single peer-selection mechanism. Neither
omission removes a reachable behavior or a regression owner.
