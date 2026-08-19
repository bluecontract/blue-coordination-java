# Changelog

This project follows Semantic Versioning. Release candidates may still refine
the new 3.x API before the first stable 3.0.0 release.

## 3.0.0-rc.1 - unreleased

### Added

- A compact Java 17 in-memory Coordination engine with a small immutable
  application API.
- An explicit `CoordinationEngine.inMemoryContracts10(...)` lifecycle boundary
  requiring final Language/Contracts SHA-256 artifact identities and public
  Root lineages, plus typed `admitContractsClosure(...)` all-new-lineage
  admission with one atomic durable receipt. Legacy `startDocument` and mixed
  existing/new admission remain rejected in that mode.
- Contracts 1.0 affected-closure capture and independent per-document
  execution, with the same processor/context for acyclic and cyclic documents
  and no ambient containing-document context.
- A durable Root feeder/window over the union of Root and active embedded
  Timelines, with exact lane-local `NeedsResources` no-overtake and
  disconnected-Root progress.
- Copy-on-write connected-closure publication with CAS-fenced per-document
  heads/epochs, active/inactive occurrence inventory, active SCC component
  state, per-document graph generations, exact subscriptions/routes,
  checkpoints, outbox, and idempotency receipts.
- Exact whole-request and whole-Timeline-Entry admission.
- Environment-selected append/drain processing, immutable document snapshots
  and revision history.
- Managed `Process Embedded.paths` and `collectionPaths`, historical catch-up,
  shared-child convergence and nested synchronized barriers.
- A legacy compatibility profile with immutable bindings, separate occurrence
  cursors, exact processor-owned child-epoch inputs, document-local commits,
  commit companions, and idempotent retry behavior.
- Explicit `FULL_HISTORY`, `FROM_FRONTIER`, and `FROM_NOW` top-level admission.
- Occurrence-specific embedded admission evidence with exact child epoch,
  completeness proof, attachment identity, atomic consumption, and retry.
- READY-only application reads, explicit intermediate audit reads, and
  deterministic paused/resumable PROCESS-work budgets.
- Phase timers and work counters separating Coordination host work from frozen
  Language/Contracts/BEX execution.
- Library-owned unit, compact-engine integration, built-JAR consumer and
  realistic scenario suites enforced by `releaseCheck`.
- Standalone `blue-basic` historical performance and metrics evidence,
  explicitly isolated from release correctness.
- Causally exact initialization revisions whose ordered lifecycle events are
  retained once and applied to every current or later containing document.
- Dynamic `Process Embedded.paths` and `collectionPaths` activation with
  code-point-canonical occurrence order, nested settlement, and retry proofs.
- A reproducible extracted-source archive smoke that runs focused tests using
  its own executable Gradle wrapper and authoritative `.cz.toml`.
- A default local-composite implementation lane that substitutes the complete
  Language graph (`blue-language-model`, `blue-language-core`,
  `blue-language-mapping`, `blue-language-ipfs`, `blue-language-java`, and
  `blue-contracts-core`) together with both BEX modules and Repository, with
  source-lock and extracted-archive path verification.

### Changed

- Java 17 is now the minimum runtime and compilation baseline.
- The compact engine replaces the 2.x general planning/fragmentation engine.
- Only managed Process Embedded documents are cut; initial documents, requests,
  Timeline Entries and ordinary nested values remain whole.
- The exact 3.0.0-rc.1 release policy permits explicit workflow publication as
  `PASS_WITH_KNOWN_PERFORMANCE_LIMITATION`. All non-performance gates remain
  mandatory, and the exception is ineligible for stable release.

### Known limitations

- The retained Round 13 campaign failed append p95 (18.680667 ms versus the
  1.000000 ms hard limit) and Coordination-host p95 (872.356126 ms versus the
  250.000000 ms hard limit). The receipts remain unchanged and no latency pass
  is claimed; performance remediation is required before stable.

### Removed

- The legacy engine, fast-path hierarchy, generic fragmentation APIs,
  `basicTest` and `myOsDemoTest` source sets.
- Compatibility shims for the pre-3.x experimental API.

### Release prerequisites

The explicit published-artifact isolation lane resolves Language rc.20,
`blue.repo:blue-repo-java:3.0.0-rc.21`, `blue.bex:blue-bex-core:1.1.0-rc.3`,
and `blue.bex:blue-bex-contracts:1.1.0-rc.3` from Maven Central without sibling
substitution. That proves repository isolation and a conflict-checked resolved
graph only. The current Contracts 1.0 source requires Language and BEX APIs
newer than those published bytes, so published compile/API compatibility
remains red until matching artifacts are published. Until then,
`local-composite` is the supported implementation lane and release automation
must not describe the candidate as staging-ready.
