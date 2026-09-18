# Cost40: READY revision point audit

MyOS's public history projection previously obtained the complete `history()`
list even when it already retained all but one epoch. `auditManagedEpoch` covered
numbered source receipts but not the distinct READY public-revision boundary.

Add `AdvancedCoordination.auditReadyRevision(documentId, epoch)` through the
existing indexed `revisionAt` read. It returns the same numbered revision as
`DocumentHandle.history()`, or empty beyond READY. Negative epochs and absent
documents are errors; physical faults are not absence. No host storage dependency,
format change, semantic cursor, gas or processing rule is introduced.

This is an observation, not permission to trust arbitrary host projection rows.
Mini's Cost40 package defines transactional frontier/protected-row requirements.
Same-epoch representations still use their existing exact position and publication
path; they are not new numbered revisions. Full history APIs remain unchanged.

Production inventory grows by 16 lines, with no additional public type. Tests
compare exact point results with maintained public history and check READY bounds.
## Verification

The final implementation is Coordination `08e31572d70918542321fa8af2c8c614c3169053`;
documentation-only follow-ups do not change it. Language, BEX and Catalog are
unchanged by this package. No PostgreSQL/storage dependency enters the library.

The focused lane passed **16/16** tests: `ReadyRevisionPointAuditTest` (2),
`DocumentSessionHistoryRangeTest` (7), and the unchanged
`ManagedRepresentationHistorySinglePassTest` (7). The production-shape gate passes
at 327 classes / 87,765 lines / 96 public types. Archived XML is under
`processing-measurement13/cost40/coordination-01/` in the retained POC evidence.

Mini candidate `f3300343c6e69d1cdc76acb423590e649a4512b9` uses this artifact in the
qualified `cost40-02` build. Its 83-test host/PostgreSQL/E2E batch, seven matching
resident scenarios and original long graph plus cold restart all pass. Full
coverage and timing qualifications are in Mini's
`docs/poc-cost40-projection-history-delta.md`; this is not full-suite/release
qualification or a proof of arbitrary-history bounded processing.

The change removes the need for the host to request the complete public history
just to project its new revision. It does not optimize the library's internal
same-epoch representation-chain authentication or introduce a reusable proof for
skipping that authentication. Those integrity controls remain unchanged.
