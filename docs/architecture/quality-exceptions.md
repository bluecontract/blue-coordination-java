# Temporary release-candidate quality exceptions

The architecture gate enforces zero production package cycles and zero
production classes in `blue.language.*`. Four remaining size exceptions are
explicitly tracked rather than hidden by generated sources or relaxed checks.

## `CoordinationDocumentSplitter`

The public splitter façade still contains its compatibility value types and
the orchestration for canonical cuts, PROCESS-header views, and provider
composition. `EffectiveCutCatalogReader` is already extracted and the
reconstructor and admission verifier are separate components. Follow-up:
extract `ScopeCutPlanner`, `ExecutableBodyCutPlanner`,
`CanonicalFragmentBuilder`, and `SplitGraphAssembler`, retaining the existing
public nested value descriptors until the next public-API baseline.

## `FixedRepositoryBoundSourceProvider`

The adapter keeps source retrieval, historical-registry evidence, cyclic
proofs, and diagnostics together because each path is bound to the same
immutable Repository artifact and fail-closed identity rules. Follow-up:
separate retrieval, historical environment, cyclic-proof, and diagnostic
components after the locked Repository supplies current Language-compatible
bytecode; no compatibility definition or identity alias may be introduced in
the meantime.

## `BexProcessingMetrics`

The class retains the previous candidate's public metric methods for binary
compatibility while implementing the current `ProcessingObserver` and
immutable BEX snapshot sinks. Follow-up: move the legacy counters behind a
deprecated report projection and publish a small recorder/snapshot API at the
next major binary baseline. Metrics must remain diagnostic-only.

## Root Gradle build

The dependency topology and release/working gates are split into
`gradle/latest-language-topology.gradle`, `gradle/coordination-working.gradle`,
and `gradle/coordination-release.gradle`, but the root script still contains
legacy typed-task logic. Follow-up: move the characterized binary, bytecode,
archive, JMH-report, and conformance tasks into convention plugins without
changing their receipts or same-run failure semantics.

These are size and cohesion exceptions only. They do not permit a split
package, package cycle, remote Blue fallback, provider trust bypass, mutable
Language runtime adapter, or semantic shortcut.
