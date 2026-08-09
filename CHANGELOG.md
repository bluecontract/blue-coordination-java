# Changelog

This project follows Semantic Versioning. Release candidates may still refine
the new 3.x API before the first stable 3.0.0 release.

## 3.0.0-rc.1 - unreleased

### Added

- A compact Java 17 in-memory Coordination engine with a 16-type application
  API.
- Exact whole-request and whole-Timeline-Entry admission.
- Operation-aware routing, immutable document snapshots and revision history.
- Autonomous `Process Embedded` documents, historical catch-up, shared-child
  convergence and nested catch-up.
- Atomic rollback, committed-delivery receipts and idempotent retry behavior.
- Phase timers and work counters separating Coordination host work from frozen
  Language/Contracts/BEX execution.
- Library-owned unit, compact-engine integration, built-JAR consumer and
  realistic scenario suites enforced by `releaseCheck`.
- Standalone `blue-basic` historical performance and metrics evidence,
  explicitly isolated from release correctness.

### Changed

- Java 17 is now the minimum runtime and compilation baseline.
- The compact engine replaces the 2.x general planning/fragmentation engine.
- Only embedded autonomous documents are cut; initial documents, requests,
  Timeline Entries and ordinary nested values remain whole.

### Removed

- The legacy engine, fast-path hierarchy, generic fragmentation APIs,
  `basicTest` and `myOsDemoTest` source sets.
- Compatibility shims for the pre-3.x experimental API.

### Release prerequisites

The RC must not be published until `blue.repo:blue-repo-java:3.0.0-rc.19` and
`blue.bex:blue-bex-core:1.1.0-rc.3` plus
`blue.bex:blue-bex-contracts:1.1.0-rc.3` are available from Maven Central.
Release automation verifies this before building.
