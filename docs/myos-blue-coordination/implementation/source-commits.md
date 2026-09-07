# Committed Phase1/2 source sets

> Updated 2026-09-07. Working branches, not releases or an API freeze.

## Latest verified repair source set

The [R1–R5 repairs](final-review-remediation.md) and subsequent [N1–N5 repairs](post-remediation-fixes.md)
were committed on the same working branches after 1866 affected library, 99 PostgreSQL host and
7 actual-library/host checks passed. The user authorized delivery to those remote branches.
The exact tested base commits, source patches/new files and artifact hashes remain in the linked
verification archives; committing the same sources does not imply another test run.

| Repository | Working branch | Verified repair implementation commit |
|---|---|---|
| `blue-coordination-java` | `codex/coordination-external-state-poc` | `294e60e9f5dc86772736c73cfe1c741805549a85` |
| `blue-language-java` | `codex/coordination-external-state-poc` | `9dc72da8c3c858a75e8b9f16e15e07bd4f722ec5` |
| `blue-bex-java` | `codex/coordination-external-state-poc` | `c74052bf700d048f51820c1a29a0e16973aee7dc` |
| `myos-simple` | `feat/coordination-with-external-state` | `63e5890c5599f4e7143b0bb08136d10811fb4b64` |

This source map is a subsequent documentation-only Coordination commit, so its branch tip is later
than the implementation commit listed above. R6 remains deferred and Phase3 has not started. The
tables below identify historical source sets, not a competing current candidate. No raw local build
archives or the user's untracked MyOS example are included in the repair commits.

## Reviewed remediation source set

The [pre-Phase3 repairs](pre-phase-3-review-remediation.md) were subsequently committed and pushed
on the same working branches. Remote branch heads were checked against these exact commits on
2026-09-06. The [final implementation review](../review/final-implementation-review-2026-09-06.md)
inspects this source set and finds further open defects; committed/pushed does not mean accepted.

| Repository | Working branch | Reviewed implementation commit |
|---|---|---|
| `blue-coordination-java` | `codex/coordination-external-state-poc` | `b08b8e2f9d08e185cc7eb3500d2199d4f71921cc` |
| `blue-language-java` | `codex/coordination-external-state-poc` | `7dd28cbe917688b1bfb16e7ca9488a5f6a085389` |
| `blue-bex-java` | `codex/coordination-external-state-poc` | `95ba24fa9bf78930daa690ac1885214b0a4da8a9` |
| `myos-simple` | `feat/coordination-with-external-state` | `701f70b2aaba3c796a047648551a9fecd5bd915f` |

The final report and readiness notices are a later documentation-only Coordination commit. Use
the immutable implementation commits above when reproducing its findings. The untracked MyOS
`src/main/resources/examples/managed/multiplicity/request.yaml` remains excluded and untouched.

## Earlier baseline source set

The commits below are the earlier **baseline**, not the subsequent remediation source set.

These commits capture the implementation underlying the [readiness record](phase-1-2-readiness.md).
Committing the verified source did not rerun the test suites or begin Phase3. The source trees were
already verified as local changes; their compiled artifacts and test reports retain that original
provenance. Raw build directories, caches, database data and local readiness archives are not added
to Git.

| Repository | Working branch | Implementation commit |
|---|---|---|
| `blue-coordination-java` | `codex/coordination-external-state-poc` | `8ac259fee0e39a7e5d8f1189ad4d3a9fdbbcb82d` |
| `blue-language-java` | `codex/coordination-external-state-poc` | `86234912f0b0f4c0f1963fda8a533784bfb0c435` |
| `blue-bex-java` | `codex/coordination-external-state-poc` | `0b9eac1c62b43959f54ea77424e4bb1bfeab3b46` |
| `myos-simple` | `feat/coordination-with-external-state` | `5a5d34d4d03c9df84595908495a5faca23ffc5f5` |

Coordination's branch also incorporates the synchronized documentation as a separate commit, so its
branch tip can be later than the implementation commit above. There is one shared code/documentation
branch for ongoing work; `codex/myos-coordination-design` is retained locally as the documentation
import source, not a separate remote delivery branch.

The early chronology experiment is preserved in the [r15.2 archive](r15.2-experiment/README.md), not
installed into current source sets. Unrelated local reviews/plans, old ZIP snapshots and the
pre-existing MyOS example remain outside this source set. No remote `next` or `main`, version tag,
published artifact coordinate or package release is changed by publishing these working branches.
