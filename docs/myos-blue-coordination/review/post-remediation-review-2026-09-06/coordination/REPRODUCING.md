# Coordination read-only diagnostics

Run from the Coordination worktree. No Gradle invocation or source modification is needed; these use the existing compiled production and test fixture artifacts referenced by the captured Gradle worker classpath. The classpath's second line is the actual path list.

```sh
metadata_review_cp="$(sed -n '2p' build/readiness-evidence/coordination-final-affected-20260906-pECpT9/worker-classpath.txt)"
metadata_review_classes="$(mktemp -d /tmp/coordination-metadata-review-XXXXXX)"
javac -cp "$metadata_review_cp" -d "$metadata_review_classes" docs/myos-blue-coordination/review/post-remediation-review-2026-09-06/coordination/MetadataFenceWitness.java docs/myos-blue-coordination/review/post-remediation-review-2026-09-06/coordination/UnusedDuplicateProgramWitness.java
java -cp "$metadata_review_classes:$metadata_review_cp" blue.coordination.external.MetadataFenceWitness
java -cp "$metadata_review_classes:$metadata_review_cp" blue.coordination.external.UnusedDuplicateProgramWitness
```

Executed 2026-09-06, both exit 0. Standard SLF4J missing-binding warnings are omitted from the saved stdout files.

`MetadataFenceWitness` demonstrates an API-level loss: an actual initialized root ignores the selected Timeline Entry's actor. The same target read fence is present in `operationFences` in both inputs, but removing the legacy undifferentiated copy changes returned `MetadataProgress.fences()` from one to zero. The handled input remains identical. This does not demonstrate a host lost update or publication: the diagnostic never commits anything. Early metadata returns in `CoordinationCore` (lines 661, 685 and 743 at review time) use legacy fences, whereas late target-only progress (lines 775–776) uses target attribution. Metadata progress names one target lineage; do not fix the inconsistency by indiscriminately unioning unrelated source-head fences.

`UnusedDuplicateProgramWitness` is a negative control, not a finding. One restored unrelated source program, two references to that same object, and two equivalent independently restored objects all return `MetadataProgress` without further fragment reads after the provider becomes unavailable. Full-DAG reserialization when comparing distinct equivalent objects is a potential physical-work concern, not a proven correctness or liveness failure here.
