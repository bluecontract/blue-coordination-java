# Pre-PR qualification after upstream reconciliation

The Cost41 review corrections are in MyOS (projection-prefix integrity and
detached READY observations), not changes to Coordination processing semantics.
Coordination next `69cd6db2` was already included. Language next `bfe39bdf`
is included in the pinned dependency tuple through POC merge `e1a4e2dc`.

The first broad native run selected all 87 changed POC test owners and 12 safety
owners: **628 tests, 623 passed, five failed**, zero errors/skips. Javadoc,
production shape and both public API boundary gates passed. The source remained
unchanged during the run; results are archived in `cost41/coordination-controls01`.
This is focused POC qualification, not the complete release-conformance inventory.

The five failures expose stale test mechanisms left by earlier POC optimizations:

- Three assertions required new capture/surface work for every selection, even
  though Cost38 now reuses an unchanged fenced observation. Updated controls hold
  a strong reference while measuring opportunistic weak-cache hits, retain lazy
  cutoff checks, and still compare exact invocation/results, history and gas,
  including a fresh decision after publication. A failed cutoff lookup must not
  be reused as a negative selection.
- Two corruption fixtures called `remove/clear` on an append-only history.
  They now replace the test-only row image and matching range index together,
  restore both afterwards, and reach the original semantic rejection assertions:
  missing terminal history and forged retained history must still be rejected,
  also after memo warming. No corruption check is removed.

Only tests and this note change in Coordination. No runtime, wire format,
public API, logical gas or publication boundary is changed by this refresh.
Targeted requalification (`cost41/coordination-controls02`, source
`30a049fed27df46bbf79babfdb979347199f6632`) passed **27/27**, zero
failures/errors/skips, across all four previously failing owners plus the
observation-cache owner. Javadoc, shape and both API gates also passed; source
unchanged. This completes the five fixture failures but does not relabel the
initial 628-case run as a single green run.

MyOS qualification uses the immutable runtime artifact from `a14721e6`:
its production/build source is identical to this test-only follow-up. Language,
BEX and Catalog manifests remain the same between both qualification runs.

## Published RC12 preparation — 18 September 2026

Language `3.1.0-rc.32` is published (release run `35283988316`). The RC12
candidate replaces the six Language/Contracts RC27 coordinates with RC32;
BEX RC6, Repository RC22 and all other locked dependencies are unchanged.
Merged Coordination next remains `69cd6db2`. The feature branch keeps the
preceding `.cz.toml` version; CI prepares and seals RC12 in its own checkout.

Local Java 17 published-dependency preflight and all four suite compilations
passed in 49 seconds. Release automation passed **33/33** Node tests. Production
shape, both API boundaries, metadata, documentation, artifact contents, POM and
published dependency isolation passed. The production inventory remains
327 files, 87,765 lines and 96 public source types.

The test-architecture check found 407 POC methods without the repository's
required exact `given / when / then` sections. Add those phase annotations;
retain every original assertion, fixture, input count and failure expectation.
Delegating equality tests name their original expected/actual computations;
void scenario delegates retain their internal assertions and are invoked once
through JUnit `assertDoesNotThrow`. No acceptance outcome is relaxed. Javac AST
comparison confirms 63 affected files are comment/formatting-only; the other
12 contain those explicit phase extractions. All 75 files compile, and the
unchanged test-architecture and documentation gates now pass.

These are packaging/structural checks, not a full current-tuple test result.
The PR's complete Java 17/21 `releaseCheck`, extracted-source build and RC12
readiness are required before merge, followed by the normal publication gates.
MyOS acceptance against the published tuple is a later, separate step.

### First complete PR attempt

PR #27 run `35287896191`, head `26b5be2`, completed the same inventories on
Java 17 and Java 21: unit **1,515/1,515**, built-JAR consumer **16/16**, and
integration **90/91**, with no errors or skips. The integration failure stopped
later release tasks; this is not complete release acceptance.

`ApplicationReadinessProofIntegrationTest` attempted `List.set` on the now
append-only session revision history while constructing its deliberately corrupt
parent state. It therefore failed before exercising the intended parent/cursor
mismatch rejection. Replace the test-only revision image and its exact retained
position metadata together, leaving the child cursor and all original
`DOCUMENT_NOT_READY`, diagnostic and audit-state assertions unchanged. A first
local attempt replacing only the row correctly hit the independent row/index
integrity guard; the fixture must isolate the composite readiness proof instead.
No production source or integrity rule changes.

The repaired five-case owner passes locally on both Java 17 and Java 21. The previously unreached
scenario suite also passes **14/14** (13 owners), and test architecture,
conformance coverage and source-archive hygiene pass. These scoped results do
not replace the new exact-head Java 17/21 PR run or extracted-source release gate.
