# Cyclic topology round — Phase 0 baseline

Captured at `2026-08-19T12:57:24Z` (`2026-08-19T14:57:24+0200 CEST`) before this round changed production or test code.

The source and machine snapshot was captured without running Gradle. The root runner then executed the three Phase 0 gates serially against those unchanged source commits. The commands, displayed Gradle durations, XML suite times, counts, and preserved evidence below are from this round; prior-round results are not substituted.

## Clean task branches

| Repository | Task branch | HEAD | Tree | Pre-baseline status |
|---|---|---|---|---|
| Blue Language | `codex/cyclic-topology-language` | `2cff37bc48bda44e800ae82b4d0a706dda6d6258` | `b683e83242bb714c3b08dbeee5b3067297c0fe25` | `## codex/cyclic-topology-language` |
| BEX | `codex/cyclic-topology-bex` | `821fe877fef5b04a729b7422cdda05a7ace55a1f` | `bd11323bbe7c9ce0dff51d416ac2838aa5cedc5d` | `## codex/cyclic-topology-bex` |
| Coordination | `codex/cyclic-topology-coordination` | `3fd8b5a6f1aa5db295b2de5d03b617281a080e5b` | `23de1fcfc1ae21772a23d16c5b4a8dadbd3ea46c` | `## codex/cyclic-topology-coordination` |

All three worktrees were clean at capture time. For each repository, all four byte streams below were empty and therefore had SHA-256 `e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855`:

- `git status --porcelain=v1 -z`;
- `git diff --binary --full-index`;
- `git diff --cached --binary --full-index`;
- `git ls-files --others --exclude-standard -z`.

Consequently, there was no dirty patch payload to save. The binary-capable unstaged and staged patch records in `baseline.json` each state `bytes: 0`, the empty-stream SHA-256, and `artifact: null`. Creating this baseline makes only the two requested files untracked in the Coordination task worktree; it does not invalidate the recorded pre-baseline state.

## Frozen specification and package inputs

| Input | Identity |
|---|---|
| Canonical `blue-spec` commit | `5dc8096276652156e248c9c018a0850fcd8dbdbb` (`codex/contracts-1.0-spec`, clean) |
| Cyclic-topology task prompt | SHA-256 `568c6bf6cf7a4be81af60a6a932ab322997f87fcd07ba17bcdd6ca3a6a8ae136` |
| Blue Language specification | SHA-256 `01b038b64e3f0a9a11f3f70d544a63ff78a01d5169f1a03f8b8629cf73645a7d` |
| Blue Contracts specification | SHA-256 `dfb444962a5a17b3a6519e8d148c2bf4a975a921b1fcb1277710052caaecd930` |
| Contracts release identity | `sha256:7e6c3717bc28d21ebadec9f81725913e944bb3b9b70094531f19f10510a10e50` |
| Fixture package identity | `sha256:071cecb68e1c4dcec2dbb0895de928629281d2b0a18f3e8a83a41a720e621bfa` |
| Closure fixtures | `67` |
| Full fixture corpus | `234` executable fixtures: `167` ordinary + `67` closure; `135` vectors |
| Slim local package identity | `sha256:b67a400d676dedb48260733fadd2fcc66a70c2aff95b8b93cc1ca4342accb29b` |
| Slim package ZIP | SHA-256 `50b1ee092752b0ede42066967c8097709bccf8cd01d11bf53ba7725a319029c4` |
| Package validator status | `PACKAGE_VALID`; `SEMANTIC_REFERENCE_VALID`; `implementationConformanceClaimed=false` |

The canonical reference file, the packaged reference file, and the Language worktree's embedded specification all hash to `01b038...`. The previous characterization hash `a234b0...` is not an input to this round.

The machine-readable baseline records the release-manifest, fixture-manifest, package-manifest, package checksum manifest, validation-output, lock-file, wrapper, and selected source-file hashes individually.

## Local source bindings

- Language: `blue.language:blue-contracts-core:3.1.0-rc.20`, commit `2cff37bc48bda44e800ae82b4d0a706dda6d6258`.
- BEX: `blue.bex:blue-bex-core:1.1.0-rc.3` and `blue.bex:blue-bex-contracts:1.1.0-rc.3`, commit `821fe877fef5b04a729b7422cdda05a7ace55a1f`.
- Repository: `blue.repo:blue-repo-java:3.0.0-rc.21`, commit `2fcf29bf060ed114c971194adb6f8b747899aee2`.
- Canonical spec root: `/private/tmp/blue-contracts-1.0-consolidation.btw1Cr/worktrees/blue-spec/latest`.

The execution mode remains local-composite. This baseline does not publish, stage, install, or push an artifact.

## Reference machine

| Property | Captured value |
|---|---|
| Host model | MacBook Pro `Mac15,9`, Apple M3 Max |
| CPU | 16 cores: 12 performance + 4 efficiency; ARM64/arm64e |
| Memory | 64 GB |
| OS | macOS 26.5.2, build `25F84` |
| Kernel | Darwin 25.5.0, `RELEASE_ARM64_T6031` |
| Default Java | OpenJDK 26.0.1+8-34, arm64, Oracle Corporation |
| Required comparison JDKs available | Java 17.0.10 arm64 and Java 21.0.2 arm64 |
| Gradle wrapper | 9.6.0 in all three repositories |
| Gradle wrapper JAR | SHA-256 `497c8c2a7e5031f6aa847f88104aa80a93532ec32ee17bdb8d1d2f67a194a9c7` |
| Locale | `LANG=C.UTF-8`, `LC_ALL=C.UTF-8` |

macOS sandboxing denied the direct `sysctl` queries. `system_profiler` and `hostinfo` independently reported the model, chip, 16 physical/logical processors, and 64 GB memory. No exact byte-valued memory claim is made.

## Serial baseline runs

| Run | Result | Gradle duration | XML suite time | Counts |
|---|---|---:|---:|---:|
| Focused public cyclic acceptance | `BUILD SUCCESSFUL` | 1m30s | 73.820s aggregate | 15/15 passed, 0 skipped/failures/errors |
| Dynamic closure corpus | `BUILD SUCCESSFUL` | 1m | 53.024s | 67/67 passed, 0 skipped/failures/errors |
| Full closure corpus | `BUILD SUCCESSFUL` | 56s | 49.719s | 1/1 passed, 0 skipped/failures/errors |

Focused Coordination command, from the Coordination worktree:

```text
env PATH=/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin ./gradlew --no-daemon --max-workers=1 test -PblueDependencyMode=local-composite -PblueLanguageCompositePath=/private/tmp/blue-contracts-1.0-consolidation.btw1Cr/worktrees/blue-language-java -PblueBexCompositePath=/private/tmp/blue-contracts-1.0-consolidation.btw1Cr/worktrees/blue-bex-java -PblueRepositoryCompositePath=/Users/piotr/data/blue-repository-java -PblueSpecRoot=/private/tmp/blue-contracts-1.0-consolidation.btw1Cr/worktrees/blue-spec/latest -PtestJavaVersion=17 --no-parallel --console=plain --tests blue.coordination.internal.ContractsClosureAdmissionAdapterTest --tests blue.coordination.internal.ContractsPublicOrderingAcceptanceTest --tests blue.coordination.internal.ContractsPublicLoopAndIsolationTest
```

Its three XML suites contain 10 tests in 7.354s, 3 tests in 8.366s, and 2 tests in 58.100s. Evidence is under `/private/tmp/blue-contracts-1.0-consolidation.btw1Cr/evidence/cyclic-topology-round/baseline/coordination-focused`.

Dynamic corpus command, from the Language worktree:

```text
env PATH=/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin BLUE_CONTRACTS_CLOSURE_PACKAGE_ROOT=/private/tmp/blue-contracts-1.0-consolidation.btw1Cr/evidence/contracts-package/extractions/fresh-build-1/blue-contracts-and-processor-specification-1.0-final ./gradlew --no-daemon --max-workers=1 :blue-conformance:test --tests blue.language.conformance.contracts.closure.DynamicClosureCorpusConformanceTest --no-parallel --console=plain -PblueSpecRoot=/private/tmp/blue-contracts-1.0-consolidation.btw1Cr/worktrees/blue-spec/latest
```

The 67-row discrepancy report is SHA-256 `1311fa5b90d6c2532a2f518d3fce3d61a75cf849473b557ee400d66bb8862cd0`. Every row is `PASS`: 35 `process-closure`, 14 `admit-closure`, and 18 `limit-micro` rows.

Full corpus command used the same environment and options with this test selector:

```text
env PATH=/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin BLUE_CONTRACTS_CLOSURE_PACKAGE_ROOT=/private/tmp/blue-contracts-1.0-consolidation.btw1Cr/evidence/contracts-package/extractions/fresh-build-1/blue-contracts-and-processor-specification-1.0-final ./gradlew --no-daemon --max-workers=1 :blue-conformance:test --tests blue.language.conformance.contracts.closure.FullClosureCorpusConformanceTest --no-parallel --console=plain -PblueSpecRoot=/private/tmp/blue-contracts-1.0-consolidation.btw1Cr/worktrees/blue-spec/latest
```

The Language XML files and discrepancy report are under `/private/tmp/blue-contracts-1.0-consolidation.btw1Cr/evidence/cyclic-topology-round/baseline/closure-corpus`.

Command start/end timestamps and launcher `JAVA_HOME` values were not separately recorded, so the machine-readable receipt leaves those fields null instead of inferring them. The Coordination command explicitly requested test Java 17. The independently captured shell runtime is OpenJDK 26.0.1 and is recorded only as machine identity, not claimed as the exact launcher for these three commands.

## Scope guardrail

This capture does not authorize a semantic redesign. It freezes the existing execution, scheduling, identity, publication, and ingestion units described in the task prompt. Production and test source files were not edited during this static capture.
