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
