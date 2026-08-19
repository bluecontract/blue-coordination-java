# Cyclic topology and performance completion receipt

Generated: 2026-08-19T19:27:45Z

Status: **INCOMPLETE_WITH_CHARACTERIZED_BLOCKERS**

`implementationConformanceClaimed = false`

This is the final evidence receipt for the cyclic topology, detachment, identity, locality, and performance completion round. The bounded implementation and verification work is committed on separate local branches. Most requested topology behavior is proven through the public Coordination engine, but the prompt Definition of Done is not satisfied: the authoritative campaign exposes hard broad-state-traversal and raw-BEX-observability blockers, and two requested public-host/profile capabilities remain unavailable.

No package was pushed, published, staged, or installed to Maven Local. All cross-repository verification used local composite sources, as explicitly required by the user.

## Bound inputs and repositories

| Input | Exact value |
|---|---|
| Prompt SHA-256 | `568c6bf6cf7a4be81af60a6a932ab322997f87fcd07ba17bcdd6ca3a6a8ae136` |
| Language specification | `sha256:01b038b64e3f0a9a11f3f70d544a63ff78a01d5169f1a03f8b8629cf73645a7d` |
| Contracts specification | `sha256:dfb444962a5a17b3a6519e8d148c2bf4a975a921b1fcb1277710052caaecd930` |
| Contracts release | `sha256:7e6c3717bc28d21ebadec9f81725913e944bb3b9b70094531f19f10510a10e50` |
| Fixture package | `sha256:071cecb68e1c4dcec2dbb0895de928629281d2b0a18f3e8a83a41a720e621bfa` |
| Closure fixtures | 67, unchanged |
| Specification source | `blue-spec/latest` at `5dc8096276652156e248c9c018a0850fcd8dbdbb` |

The older Language characterization hash `a234b0b42190a7982809781b5efdaa2e5f1ab4b7f8d870fbd1ffe7020cc7e869` was not restored or used.

| Repository | Branch | Baseline | Final |
|---|---|---|---|
| Language | `codex/cyclic-topology-language` | `2cff37bc48bda44e800ae82b4d0a706dda6d6258` | `d4a0379053e1a716395349c40fa403ee993796ff` |
| BEX | `codex/cyclic-topology-bex` | `821fe877fef5b04a729b7422cdda05a7ace55a1f` | `821fe877fef5b04a729b7422cdda05a7ace55a1f` |
| Coordination | `codex/cyclic-topology-coordination` | `3fd8b5a6f1aa5db295b2de5d03b617281a080e5b` | `f245270c87cbcec80ed81b416c82513a64367ffc` (receipt parent) |
| Spec | `codex/contracts-1.0-spec` | `5dc8096276652156e248c9c018a0850fcd8dbdbb` | `5dc8096276652156e248c9c018a0850fcd8dbdbb` |
| Repository | existing local branch | `2fcf29bf060ed114c971194adb6f8b747899aee2` | `2fcf29bf060ed114c971194adb6f8b747899aee2` |

The Coordination evidence head before adding this receipt and checksum manifest is `f245270c87cbcec80ed81b416c82513a64367ffc`.

The final local-composite locks bind:

- Language `blue.language:blue-contracts-core:3.1.0-rc.20` at `d4a0379053e1a716395349c40fa403ee993796ff`.
- BEX `blue.bex:blue-bex-core:1.1.0-rc.3` and `blue.bex:blue-bex-contracts:1.1.0-rc.3` at `821fe877fef5b04a729b7422cdda05a7ace55a1f`.
- Repository `blue.repo:blue-repo-java:3.0.0-rc.21` at `2fcf29bf060ed114c971194adb6f8b747899aee2`.

## What was implemented

- Test-only authored Contracts scenario construction with canonical proof, occurrence, binding, route, and insertion-order handling.
- Exact parity between the authored-document facade and expert `ClosureInvocationInput` construction.
- Three-member finite ring, canonical discovery variants, three direct seeds, and deterministic shared-gas rollback.
- Collection-backed shared-anchor five-member SCC and two genuinely disjoint two-member SCCs.
- One-thousand-unrelated-document semantic-locality scenario.
- Partial detachment, full dissolution, post-detachment success, frozen delivery, and lineage-safe re-addition.
- Public merge/split, self-cycle dissolution, and late-failure rollback scenarios.
- Static initialization coverage plus precise failure characterization for unavailable dynamic initialization patching.
- Ordinary nested-scope positive control plus exact Root-only affected-closure characterization.
- Targeted closure planning, source indexes, operation-route indexes, provider/read metering, structural metrics, and operation-local timing.
- Runtime-generated identity evidence for 32 checkpoints.
- A non-cacheable six-shape performance campaign using fresh public engines and state.
- Coverage and future high-level authored API documentation without changing the production admission API.

The architecture remains the frozen architecture from the prompt. No second graph, cyclic-specific handler/BEX API, caller-selected routing, reverse-container binding, separate component gas meter, timeout semantics, or specification rewrite was introduced.

## Topology results

The complete diagrams, before/after partitions, fixture mapping, and scenario-by-scenario status are in `CYCLIC_TOPOLOGY_COVERAGE.md`. The exact BlueIds, MASTERs, proofs, component IDs, occurrence/binding IDs, activation generations, event multiplicity/order, gas traces, work order, direct-seed order, and durable state are in `cyclic-topology-identities.md` and its JSON counterpart.

Compact topology map:

```text
P2 ring:       A -> B -> C -> A              SCC {A,B,C}

P3 shared A:   A -> B1 -> C1 -> A            one SCC
               A -> B2 -> C2 -> A            {A,B1,C1,B2,C2}

P3 disjoint:   A1 <-> B1    A2 <-> B2        two SCCs

P4 partial:    A -> B1 -> C1                 acyclic tails B1,C1
               A -> B2 -> C2 -> A            SCC {A,B2,C2}

P4 full:       A -> B1 -> C1                 no cyclic component
               A -> B2 -> C2

P5 merge:      {A1,B1} + {A2,B2} -> {A1,B1,A2,B2}
P5 split:      {A1,B1,A2,B2} -> {A1,B1} + {A2,B2}
```

Coverage summary:

| Category | Count |
|---|---:|
| Semantic scenarios | 30 |
| Performance mappings | 6 |
| Matrix rows | 36 |
| `PASS` | 25 |
| `PASS_SEMANTIC` | 1 |
| `BLOCKER_CHARACTERIZED` | 3 |
| `PARTIAL_BLOCKER_CHARACTERIZED` | 1 |
| New normative fixture required | 0 |

The normative package and its 67 closure fixtures were not modified. The coverage audit concludes that successful cases compose existing portable laws; the blocked cases require a public-host seam or a normative-profile decision rather than another expected-value YAML file.

## Exact identity evidence

`cyclic-topology-identities.json` uses schema `cyclic-topology-identities/1.0` and contains 32 runtime-produced scenario checkpoints. It records:

- before/after BlueIds and MASTERs;
- component and proof identities;
- occurrence and binding identities plus activation generations;
- event BlueIds, occurrence IDs, order, and multiplicity;
- gas trace identity, total gas, rejected work, and rejected charge;
- work, isolated document-step, and direct-seed order;
- durable publication state.

The three-member finite ring was repeated six times with exact equality; the shared-gas rollback was repeated once with exact equality. The exporter is dormant in ordinary tests, writes only with `BLUE_CYCLIC_TOPOLOGY_IDENTITY_ARTIFACT_MODE=WRITE`, and otherwise compares the committed JSON and Markdown byte-for-byte. Invalid, partial, missing, or invocation-mismatched evidence fails closed.

Boundary truth retained in that artifact:

- Raw BEX fingerprints are unobservable at the public Coordination boundary; the exact public semantic/gas/event projection is recorded instead.
- Dynamic initialization identities cannot be produced because the public host lacks the conformance initialization-patch seam; exact failed input/result identities are recorded.
- Nested cyclic work cannot be produced because the affected-closure profile is Root-only; the ordinary nested success and cyclic route miss/Root work are recorded.
- The observed rejected `internalEventEnqueued` charge belongs to an already-started occurrence. This round does not claim rejection before that work begins.

## Authoritative performance campaign

The campaign used Java 17.0.10, fixed 2 GiB heap, G1, `en_US`, UTC, and a fresh public engine/state for every iteration on a MacBook Pro `Mac15,9` with Apple M3 Max, 16 logical cores, 64 GB RAM, macOS 26.5.2 (`25F84`). Hardware/JVM binding to `baseline.json` passed.

It completed six shapes with 20 warmups and 50 measured samples each: 420 iterations and 490 operations. The true release/locality wall basis is append plus drain.

| Shape | Warm p95 total | Target | Result |
|---|---:|---:|---|
| 2-member finite cycle | 698.537 ms | 1,000 ms | PASS |
| 3-member ring | 952.256 ms | 1,500 ms | PASS |
| 5-member shared-anchor SCC | 2,332.671 ms | 2,500 ms | PASS |
| 2 disjoint 2-member SCCs | 1,344.114 ms | no independent target | reported |
| 5-member + 1,000 unrelated | 2,336.946 ms | locality comparison | reported |
| detach + dissolution | 1,216.268 ms | no independent target | reported |

Adding 1,000 unrelated documents caused 0.183250% p95 overhead versus the controlled five-member shape, under the 10% limit. The controlled pair had exact affected semantic equality, gas equality, and public observable-result equality. Unrelated documents had zero semantic opens, zero steps, and zero finalizations.

Host residual p95 was between 0.175 ms and 0.285 ms across all shapes, well below 100 ms.

The non-hard aspirational targets (250 ms, 500 ms, and 1,000 ms) were not reached. More importantly, the authoritative campaign status is `FAIL` because these hard gates remain unresolved:

- Broad global-state traversals: observed 57, required 0.
- Broad global-state entries traversed: observed 100, required 0.
- Raw BEX cold/warm equality: `UNOBSERVABLE` at this API boundary.

The narrow `FULL_ENVIRONMENT_SCANS` metric is zero, but it is not a substitute for the broader publication/state-copy gate. Correctly eliminating the remaining traversal requires a persistent/path-copy or copy-on-write state architecture across StoreState, sessions, occurrences, components, subscriptions, and receipts. That is larger than a bounded cache/index/finalizer fix and was not invented in this round.

## Verification ledger

All paths below used the worktrees listed above and `blue-spec/latest`. Failure and skip counts are explicit. A missing duration means the final command's duration was not separately captured; no duration is fabricated.

| Gate | Command summary | Duration | Tests / samples | Fail | Error | Skip | Result |
|---|---|---:|---:|---:|---:|---:|---|
| Language exact-head Java 17 | `./gradlew --no-daemon --max-workers=1 clean build ...` | 11m37s | 2,859 across 330 XML files; 2,381 root tests | 0 | 0 | 0 | PASS |
| Language release/semantic/API/docs/archive | release conformance + semantic baseline/API + final API baseline + documentation + deterministic source archives | 8m39s initial command + 1m28s docs rerun + 24s final-head archive rerun | 387 fixtures (153 Language + 234 Contracts); 337 approved incompatible + 473 approved additions | 0 unexpected | 0 | 0 | Initial command failed only at stale docs; both bounded reruns PASS |
| Direct `blue-spec/latest` corpus | Combined + Dynamic + External + Full, with no package-root environment override | 2m35s | 8 + 67 + 5 + 1 = 81 | 0 | 0 | 0 | PASS |
| BEX clean/check/compat/repro | `clean check bexLocalLanguageVerification bexCompatibilityCheck bexReproducibilityCheck` against local Language | 32s | 911 across 62 XML files | 0 | 0 | 0 | PASS |
| Focused public cyclic Java 17 | 15 selected classes, including identity verify | 7m15s | 57 | 0 | 0 | 0 | PASS |
| Coordination Java 17 | `clean releaseCheck`, local composites | 20m49s | 438 primary + 18 extracted = 456 | 0 | 0 | 0 | PASS |
| Coordination Java 21 | independent `clean releaseCheck --rerun-tasks`, local composites | 20m22s | 438 primary + 18 extracted = 456 | 0 | 0 | 0 | PASS / BUILD SUCCESSFUL; 67/67 tasks executed |
| Identity artifact WRITE | focused exporter test | 2m35s | 1 | 0 | 0 | 0 | PASS |
| Identity byte verify | focused exporter test with `--rerun-tasks` | 2m39s | 1 | 0 | 0 | 0 | PASS |
| Performance smoke | 1 warmup + 1 sample per shape | 1m07s | 6 shapes; 84 phase gates passed | hard blockers | 0 | 0 | expected nonzero |
| Distinct cold/warm probe | 1 warmup + 1 sample per shape | 2m08s | 6 shapes | hard blockers | 0 | 0 | expected nonzero |
| Authoritative performance | `cyclicPerformanceAcceptance`, 20/50 | 1h13m24s | 420 iterations / 490 operations | hard blockers | 0 | 0 | complete artifacts, overall FAIL |
| Published/staged artifact lane | not invoked | 0 | 0 | 0 | 0 | 0 | NOT_RUN_BY_USER_POLICY |

Important corrected verification incident: the first Language documentation gate detected generated `docs/reference/public-api.md` drift after adding timing accessors. The reference was regenerated in `d4a0379`, and the affected gate plus the exact-head clean build passed. Because the reference is part of the source archive, deterministic source-archive verification was then rerun at `d4a0379`: 94/94 tasks executed in 24s, the archive and independent replica were byte-identical, and the final archive SHA-256 is `45728c6b4d75c28fb8961240437a1b8319133c7239c57b4fe8c8352dea38111d`. This was evidence drift, not a semantic/package mutation.

Structured counts and exact final release commands are preserved in `final-receipt.json`; the earlier smoke/probe entries are explicitly labeled as command summaries.

The Java 17 and Java 21 builds produced byte-identical local artifacts. The source ZIP SHA-256 is `d0d997684c74894dc3268d4d2be2860137be1739396271c7fa1c4269bf1074b5`; the main, sources, Javadoc, and test-fixtures JAR hashes are respectively `485cd608e216d76406c8de93fa164a36b27f061b117cc9179a48b82ab13ea556`, `6f995253ecf4ad6ff3411778ed483a93512cf1eebd70a4394328cf7163585f34`, `54c94c8c5bde70b4a0afb5333a81f94a6fb376ef5d2db42246149dad711c1436`, and `f6e61fd4ac620b366995dc061569c738ec55f9e4d899b4ab81f61cb76d8b93a6`. These are local build artifacts only; none was published, staged, pushed, or installed to Maven Local.

## Evidence artifacts

| Artifact | Bytes | SHA-256 |
|---|---:|---|
| `baseline.md` | 8,692 | `333197e973f83f764c1cf188198fa3a214c3788686bdf9bbfe59df196bcae280` |
| `baseline.json` | 33,354 | `1cfcbb840c8fcfd0244e2fdb44277fbf68eeeaf3a7efdbed866a4b78b3c4a5d2` |
| `CYCLIC_TOPOLOGY_COVERAGE.md` | 33,018 | `5d6350a2303fbaca9924bdd0d67c8deda434a27a658987afd407db697e18c646` |
| `cyclic-topology-coverage.json` | 43,731 | `60e2111a404df56561c94e9fd26f16e6e681898e2c390d8d835e12ac9de4dc52` |
| `cyclic-topology-identities.md` | 2,166,438 | `a23f9b1c19630e9ca47afb7f2c675c3d233eda998453a52f9159db6fefea860f` |
| `cyclic-topology-identities.json` | 2,355,377 | `10b4e7b4788773ebd23915eafebf6790182d5557901b24e8f9f3c0d487b63470` |
| `cyclic-performance.md` | 10,847 | `8fadbc5780ee0d7f23c7b047c2a3c1c408323450b1ecf68c14e270cfe3cc88e9` |
| `cyclic-performance.json` | 9,680,051 | `63d65fc48f219c4e2f9be58ccb4028b4af0798e4a8d7585bdc20b4090984cd7e` |
| `changed-files.sha256` | 51 entries | reported in the external handoff; intentionally not embedded here |

The checksum manifest excludes itself but includes both receipt files. Its own hash is reported in the external handoff rather than embedded here, because embedding it would create a digest cycle: the manifest authenticates this receipt. The receipt likewise does not embed its own digest.

## Reviewable commits

Language:

1. `3bb97b5` — `perf(contracts): expose closure phase timings`
2. `d4a0379` — `docs(language): refresh timing API reference`

Coordination:

1. `94dd149` — baseline capture
2. `2e19951` — test-only authored scenario support
3. `f803fba` — branching cyclic topology
4. `146b386` — three-member cyclic execution
5. `d044c0b` — closure execution evidence
6. `b1dd137` — detachment and reactivation
7. `aafcf34` — public merge and split
8. `f9c958c` — initialization characterization
9. `42d60f8` — scope-boundary characterization
10. `cd533cf` — authored admission boundary/API proposal
11. `e9a7948` — coverage audit
12. `32449f0` — bounded closure planning
13. `c5eb78b` — phase timing publication
14. `6280acc` — performance campaign implementation
15. `ef214a5` — exact identity evidence
16. `3e01b07` — authoritative performance evidence
17. `ab071ed` — normalized coverage metadata
18. `f245270` — final Language documentation-head binding

The receipt/checksum commit follows these reviewable commits. This receipt binds its parent evidence head `f245270c87cbcec80ed81b416c82513a64367ffc`; its own resulting local commit hash is reported in the external handoff because a commit cannot self-bind its own hash.

## Remaining limitations and stop conditions

1. **Broad publication/state traversal is still release-blocking.** Fixing it correctly requires a wider persistent/COW storage architecture. Skipping identity, proof, gas, schema, or publication work would violate the prompt, so no shortcut was taken.
2. **Raw BEX equality is unobservable.** Semantic state, gas, public events, and resulting identities compare exactly, but literal raw BEX output is not exposed at the Coordination boundary.
3. **Dynamic topology during initialization lacks a public patch seam.** A C-CLO-08-shaped bridge fails `RUNTIME_FATAL`; reciprocal/collection staging rolls back with `SUBSCRIPTION_SURFACE_INVALID`. Fabricating a Timeline Entry or bypassing closure processing is prohibited.
4. **Nested affected-closure work is normatively Root-only.** Ordinary PROCESS proves `$document` isolation plus exact nested `$scope`, while the cyclic profile admits only `/` with activation generation 0. Broadening this requires an explicit specification/API decision.
5. **The gas rejection phrasing is narrower than requested.** Rollback and deterministic retry are proven, but the recorded rejected enqueue charge is associated with a work occurrence that has already started.
6. **The staged/published exact-package lane is absent by explicit user policy.** This alone prevents promotion of the global implementation-conformance claim even if all local gates were otherwise green.

These are precise prompt stop conditions, not silently broadened behavior.

## Definition of Done

- [x] Existing public two-member cyclic tests remain green.
- [x] Three-member finite and loop cases are green.
- [x] The shared-A branching graph is one five-member SCC.
- [x] Two disjoint cycles remain two SCCs.
- [x] Partial detach leaves one smaller SCC plus acyclic tails.
- [x] Full detach dissolves cyclic identity.
- [x] The former loop succeeds after detachment.
- [x] Re-add creates fresh activation/binding/occurrence lineage and reforms a cycle.
- [x] Merge and split work through the public engine.
- [~] Initialization and nested scope are covered to the current public/profile boundary; positive dynamic-init and nested-cyclic cases remain blocked.
- [x] One thousand unrelated documents cause zero semantic work.
- [ ] Every performance structural gate is green: broad traversal and raw-BEX observability remain blocked.
- [x] Java 17 full gate is green.
- [x] Java 21 full gate is green: 456/456 tests, 67/67 tasks executed, BUILD SUCCESSFUL in 20m22s.
- [x] All source worktrees were clean before the receipt commit; the resulting receipt commit and final clean check are reported externally.
- [x] Fixture/package identities were left unchanged because the normative corpus did not change.

The round therefore ends with a complete and honest evidence package but not a conformance or release-readiness claim:

```text
overallStatus = INCOMPLETE_WITH_CHARACTERIZED_BLOCKERS
implementationConformanceClaimed = false
definitionOfDoneSatisfied = false
releaseReady = false
```
