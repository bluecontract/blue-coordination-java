# Committed Phase1/2 source set

> Recorded 2026-09-06. Working branches, not releases or an API freeze.

This is the committed **baseline** source set. Later local changes and their verification are
tracked in [pre-Phase3 review remediation](pre-phase-3-review-remediation.md); the commits below
must not be cited as containing those subsequent repairs.

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
