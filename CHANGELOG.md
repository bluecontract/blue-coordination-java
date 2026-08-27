# Changelog

This project follows Semantic Versioning. Release candidates may still refine
the new 3.x API before the first stable 3.0.0 release.

## 3.0.0-rc.5 - retained managed-epoch catch-up candidate

### Added

- Complete Coordination managed-epoch receipts, duplicate-preserving event
  occurrences, occurrence-specific catch-up plans, extendable barriers,
  committed-versus-ready heads, and bounded application/audit SDK surfaces.
- Historical matching for an existing authored initial, initialized epoch
  zero, and unique retained epochs, with current-state precedence and explicit
  selector support for ambiguous history.
- Durable application receipts, exact failed-attempt evidence, response-loss
  reconciliation, restart-safe route rebuilding, and indexed locality metrics.

### Changed

- The candidate build consumes the invocation-owned immutable
  `blue.language` `3.1.0-rc.23` stage derived from published rc.22 sources and
  the additive Contracts transition-receipt change. Published-artifact mode
  remains pinned to `3.1.0-rc.22`; Maven Local and composite builds remain
  forbidden.
- The drain scheduler fences later consumer work behind active barriers while
  allowing unrelated source and sibling-parent lanes to continue and extend
  catch-up frontiers.

### Known limitations

- Timeline-provider completeness, Mandates, MyOS persistence/UI integration,
  and production multi-node durability are outside this candidate. The
  release remains unpublished, in-memory, sequential, and not
  production-ready.

## 3.0.0-rc.4 - dynamic contract and occurrence evolution candidate

### Added

- Automatic managed-occurrence resolution for exact current state and complete
  new authored initial documents.
- Typed resource demands, non-publishing suspension/retry, active-path rebind,
  atomic managed publication, and dynamic cycle formation and dissolution.
- Runtime contract generalization plus typed document-transition and
  operation-route evidence.

### Changed

- The Maven Central graph is pinned to Language `3.1.0-rc.22`, BEX
  `1.1.0-rc.4`, Repository `3.0.0-rc.21`, and Coordination
  `3.0.0-rc.4`.
- The rc.4 readiness gate is bound to the sealed dynamic-evolution handoff and
  rebuilds fresh release artifacts from the published dependency graph.

### Known limitations

- Existing-session authored-initial attachment, retained historical epoch
  catch-up, authoritative external Timeline completeness, Mandates, and
  production multi-node durability remain unsupported.
- The candidate remains in-memory, one-JVM, and sequential. It is not stable or
  production-ready.

## 3.0.0-rc.3 - bounded external-pilot cyclic-topology SDK candidate

### Added

- From-now admission of new managed lineages produced by an operation result.
  The SDK binds exact draft values from `request.managed(...)` to complete
  effective paths declared with `expectOccurrence(...)`, including multiple
  occurrences that share one stable lineage, and publishes the affected
  closure atomically.
- The advanced `ManagedOccurrenceAudit` diagnostic, exposed through
  `AdvancedCoordination.auditManagedOccurrence(...)`, for the retained target
  lineage, activation generation, and active/inactive state.
- Public SDK acceptance cases for the operation-produced Order draft and the
  five-occurrence/three-lineage permutation, plus fail-closed malformed,
  ambiguous, retry, and rollback coverage.

### Changed

- The Maven Central graph is pinned to Language `3.1.0-rc.21`, BEX
  `1.1.0-rc.4`, Repository `3.0.0-rc.21`, and Coordination `3.0.0-rc.3`.
  Repository rc.21's stale Language rc.20 edge is excluded in favor of the
  direct rc.21 runtime pin.
- Local composites, Maven Local, staged file repositories, source locks, and
  the standalone staged-consumer fixture were retired from the live build.
- Every test now follows the enforced lowercase `// given`, `// when`,
  `// then` structure.
- Integration and slow scenario suites now share a private, non-published
  `testSupport` layer instead of scenarios compiling against integration-test
  output. Built-JAR consumer examples keep authored YAML in named resources so
  the Java tests emphasize the application flow.
- Managed-draft plans are preflighted before journal append and retained only
  while retry can make progress; terminal results retire the plan without
  erasing rollback evidence.
- Developer documentation now has one canonical SDK journey for exact provider
  entries and evolving managed closures, with current cycle, multi-Timeline,
  operation-created lineage, ordering, diagnostics, migration, and deployment
  guidance. Stale examples that selected the legacy engine as the public API
  have been replaced or explicitly profile-labeled.

### Known limitations

- Operation-result managed admission supports only new `FROM_NOW` lineages.
  Imported draft epochs and historical, frontier, attach-current, or passive
  occurrence activation remain unsupported and fail closed.
- The candidate remains in-memory, one-JVM, and sequential. It makes no
  provider-completeness, provider-backed Mandate, parallel/distributed,
  production MyOS durability, latency, or throughput claim.

### Distribution status

- `3.0.0-rc.3` is published by the RC workflow from the exact Maven Central
  dependency graph. The release tag is pushed only after deployment succeeds.
- The release tier remains bounded external pilot; stable and production
  readiness are explicitly false.

## 3.0.0-rc.2 - local-only freeze candidate

### Added

- The additive `blue.coordination.sdk` application facade, headed by
  `BlueCoordination.inMemory()`, with the bundled Contracts 1.0 release as its
  only normal default.
- Immutable SDK document, closure, entry, result, diagnostic, event, revision,
  and processing-stat values. Low-level closure inputs and proof structures do
  not appear in normal SDK signatures.
- Authored ordinary and complete cyclic-closure admission. The SDK derives the
  effective `Process Embedded` graph, validates managed occurrence bindings,
  and delegates exact finalization and proof verification to the pinned
  Language/Contracts runtime.
- Exact document-targeted operation calls, explicit broadcast events,
  append-only `submit()`, append-and-drain `execute()`, terminal `NO_MATCH`,
  precise target `REJECTED`, and disconnected per-closure results.
- A built-JAR-only SDK consumer test and a standalone extracted consumer that
  resolves the staged candidate on Java 17 and Java 21.
- A separate local SDK freeze lane that consumes Language, BEX, Repository, and
  Coordination from one explicit Maven-shaped file repository with composite
  substitution and Maven Local disabled.

### Changed

- `CoordinationEngine` is now documented as an advanced host-integration and
  legacy compatibility boundary. Its earlier acyclic `inMemory()` profile is
  not the SDK default.
- Public API/Javadoc, package ownership, dependency isolation, artifact
  contents, and candidate-coordinate checks are release gates for the SDK
  lane. Historical rc.1 staging and evidence remain unchanged.

### Known limitations

- Managed-child admission from an operation result is not implemented. Calls
  carrying `request.managed(...)` or `expectOccurrence(...)` fail before append
  with `UNSUPPORTED_MANAGED_DRAFT_ADMISSION`; the runtime does not emulate this
  through the legacy admission path.
- The candidate is in-memory, one-JVM, sequential, and has no fresh-process
  recovery, durable provider-completeness adapter, Mandate resolver, stable
  latency SLA, or production MyOS operational profile.
- `implementationConformanceClaimed` remains `false` until the managed-draft
  bridge and every artifact-bound acceptance/conformance gate are complete.

### Distribution status

- `3.0.0-rc.2` is staged locally only. The freeze workflow does not upload
  packages, publish to Maven Local, push commits, or create/push tags.

## 3.0.0-rc.1 - historical candidate

This section records the earlier pre-SDK candidate. Its retained receipts and
performance policy are historical evidence, not evidence for rc.2.

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

At rc.1 time, matching Contracts and BEX artifacts were not yet available, so
the published lane could not compile the later Contracts 1.0 source. This is a
historical constraint only; rc.3 uses the published rc.21/rc.4 graph described
above.
