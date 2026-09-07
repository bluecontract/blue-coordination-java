# Post-remediation review evidence

Diagnostics began on 2026-09-06; the [final review](../post-remediation-review-2026-09-07.md)
is dated 2026-09-07. These are review witnesses and controls, not implementation changes or
new acceptance tests registered in the project builds. Historical R1–R5 witnesses are untouched.

| Scope | Evidence |
|---|---|
| Independent initialization policy | [Source](cross-layer/InitializationPolicyProbe.java) · [stdout](cross-layer/InitializationPolicyProbe.stdout.txt). Source limit 5000 succeeds; parent limit 100000 rejects both hot/cold evidence; same-policy controls succeed. |
| Independent frontier policy | [Source](cross-layer/FrontierPolicyProbe.java) · [stdout](cross-layer/FrontierPolicyProbe.stdout.txt). Actual FROM_FRONTIER creator path, complete authenticated boundary and expected source basis. |
| Metadata fences | [Reproduction](coordination/REPRODUCING.md). Actual Core drops an owner-attributed fence on an ignored-input metadata result. |
| BEX schema reference and gas boundaries | [Report/probes](contracts/report.md). Standalone BEX plus actual Core/Compute, ordinary-field/type controls, and 920 lifecycle local-cap diagnostic runs. |
| Host recovery/discovery | [PostgreSQL probes](host/README.md). Real stores in isolated schemas; positive controls, capacity assumption and public-API qualification. |
| Discarded cache-duplication concern | [Control source](coordination/UnusedDuplicateProgramWitness.java) · [stdout](coordination/UnusedDuplicateProgramWitness.stdout.txt). No new reads or liveness failure; not a finding. |

The coordinating reviewer independently repeated the initialization, frontier, metadata-fence,
actual Compute, retained-release and discovery witnesses. The reported observations matched.
The standalone BEX export cases and lifecycle gas sweep were run by the independent library
reviewer. The reservation/revocation schedule is a qualified policy note, not a security finding.

## Cross-layer commands

From the Coordination implementation worktree, using its existing test runtime classpath:

```sh
review_classes=$(mktemp -d /tmp/coordination-post-review-XXXXXX)
review_classpath=$(sed -n '2p' build/readiness-evidence/coordination-final-affected-20260906-pECpT9/worker-classpath.txt)
javac -cp "$review_classpath" -d "$review_classes" docs/myos-blue-coordination/review/post-remediation-review-2026-09-06/cross-layer/InitializationPolicyProbe.java docs/myos-blue-coordination/review/post-remediation-review-2026-09-06/cross-layer/FrontierPolicyProbe.java
java -Xmx1g -cp "$review_classes:$review_classpath" blue.coordination.external.InitializationPolicyProbe
java -Xmx1g -cp "$review_classes:$review_classpath" blue.coordination.external.FrontierPolicyProbe
```

The classpath file's directory name predates the repair, but the referenced production jars are
the current frozen repair artifacts, verified against
`build/readiness-evidence/r1-r5-final-20260906-ZiROHS/manifest.json`. The probes reuse compiled
`CanonicalSourceHistoryTest.Fixture`; a clean checkout must first compile the actual library/test
fixtures through the documented composite build. To reproduce elsewhere, use the resolved current
Coordination test runtime classpath rather than assuming these machine-local paths exist.

The coordinator's additional raw outputs and launcher are retained locally under
`build/readiness-evidence/post-remediation-review-20260906-H3ZbXf/`. The initial initialization
probe had a noncanonical fixture component order; it was corrected before the recorded successful
positive controls and policy-mismatch reproduction. No production expectation was relaxed.

Review probes exit successfully when they have observed and printed their expected contrast; exit
0 is not evidence that the implementation is free of the displayed defect. No library/host
production or test source, user example, branch, remote or published package was changed.
