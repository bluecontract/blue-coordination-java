# Contracts 1.0 and SDK current verification boundary

This source tree is the local-only `3.0.0-rc.3` SDK freeze candidate.
`BlueCoordination.inMemory()` uses the bundled Contracts 1.0 release manifest;
the older `CoordinationEngine` surface remains an advanced/legacy compatibility
boundary.

Development tests use the coordinated Language, BEX, and Repository source
checkouts through `local-composite`. Artifact verification is a separate
`staged-artifact` lane that consumes exact JAR/POM/module bytes from the file
repository supplied by `-PblueStagingRepository`. It uses no sibling composite
substitution and no Maven Local.

The SDK freeze gate includes authored ordinary/cyclic admission, exact targeted
operations, explicit broadcasts, typed multi-closure results, append/drain
parity, a built-JAR consumer, and an extracted staged consumer on Java 17 and
Java 21. It also includes new-lineage `FROM_NOW` operation-result admission for
the Order-draft and five-occurrence/three-lineage cases, and binds the release
manifest and SDK classes in the produced artifacts.

## Open conformance gate

The required new-lineage `FROM_NOW` managed-draft cases are implemented and
covered by the public SDK acceptance corpus. Imported known-epoch drafts and
historical/frontier/attach-current/passive operation-result activation remain
unsupported and fail closed. Final conformance is still bound to immutable
staged bytes and the complete acceptance, fixture, artifact, and Java 17/21
consumer run, rather than inferred from a source-tree test subset.

```text
implementationConformanceClaimed = false
CONTRACTS10_CURRENT_PERFORMANCE_CLAIM: NONE
```

Neither a green source-only SDK suite nor a green dependency graph alone
changes that claim. It can be reconsidered only after the complete
artifact-bound acceptance and fixture corpus passes.

## Evidence ownership

`verifyCurrentContractsDocumentation` derives the exact current source
manifest and source/test counts from the worktree. SDK-specific public-signature,
staged-graph, candidate-coordinate, artifact-content, and extracted-consumer
checks are owned by `sdkFreezePrepublicationCheck` and
`sdkFreezeArtifactCheck`.

The SDK maintainability guardrails are at most 170 production Java files,
42,000 production Java lines, and 50 public source types across
`blue.coordination.api` plus `blue.coordination.sdk`. The measured integration
baseline when the guardrails were selected was 163 files, 39,656 lines, and 49
public types. The rc.3 candidate measures 165 files, 41,134 lines, and 50
public types. These caps are engineering tripwires, not semantic or performance
evidence. The exact public-internal allowlist is `DefaultCoordinationEngine`,
`BundledContracts10Release`, and `Contracts10AuthoredClosureCompiler`.

The final external candidate evidence must bind source commits, clean status,
staged coordinates and hashes, bundled specification/fixture/gas/finalizer/
verifier identities, recovered topology evidence, SDK tests, and Java 17/21
consumer results. It must bind the supported from-now managed-draft cases and
list the imported/history exclusions rather than silently omit either.

The retained rc.1 Round 13 report, JSON, schemas, and provenance describe only
their historical bound candidate. They are not modified, compared with current
source counts, or presented as rc.3 evidence. No latency or throughput claim is
inferred from them.
