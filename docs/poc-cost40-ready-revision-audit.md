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
Verification status: pending; this document does not claim full POC acceptance.
