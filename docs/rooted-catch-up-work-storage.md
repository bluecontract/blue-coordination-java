# Current rooted work and application storage

## Problem and example

A numbered historical application can retain a successor representation cause without applying it yet. Later applications may keep the same source epoch while advancing two different authenticated representation positions. Reopening those records as ordinary numbered work would lose the frozen target and change what the next step means. Reconstructing the cause from today's source head or from a public result would create authority that was never retained.

## Solution

`StoredCatchUpWorkIndexes` retains the current work, pending-plan, application identity, application-by-work and minimum due-order indexes. The shared `ManagedWorkStorageCodec` transports complete original work, including the two mutually exclusive cause roles, through Language-owned exact execution-evidence codecs. Row decoding does not invoke a provider, PROCESS, or admission.

An internal `RegisteredApplication` keeps the immutable receipt together with the already-registered original work. It adds one reference in resident state; getters still return the same public receipt. `ManagedApplicationStorageCodec` retains both complete values so the existing numbered/representation/successor receipt factories can verify the original association. The derived receipt identity and all cause/cursor fields must match the stored values. Standalone drain/receipt transport must supply the original work from its caller's scoped retained-work lookup; it cannot infer a representation cause from a receipt identity. There is no public raw-authority constructor. The physical application row duplicates its bounded original work transport; this is an explicit cost, not a history-residency optimization.

All original AVL keys, shape, minimum/successor order and logical mutation counters remain unchanged. Stored-state factories preserve the catch-up store's original aggregate counters, rather than recomputing progress from a partial family. A selected due entry must equal its registered due key; a selected execution is additionally checked against the original barrier and immutable source receipt order supplied by the caller. This does not discover or redefine eligible work.

Pending/due and applied/work identity crosslinks are checked at selected access boundaries. Retired work is allowed to remain as immutable history without a pending slot. An applied older representation may share its logical due key with a different later work item; it must not retain its own pending membership. Missing, malformed or unavailable selected evidence fails noncommitting through these component boundaries, not as semantic absence or a different scheduler choice.

## Integration and limits

Family roots must be pinned together by the owning directory, and later publication must atomically replace the affected roots. No whole-runtime descriptor, global CAS or SDK builder is added. Factories must route selected access through these checks; exposing a lazy map alone is not a complete recovery boundary. Source receipts and original plans/barriers remain separate authenticated components.

Resident partition conversion and the test-only complete component replacement are explicit operations, not production realm scans. Selected read paths use bounded physical-node caches; selected complete work and representation proofs may still be large bounded records. Exhaustive tests and audit operations remain exhaustive. No total-runtime-memory or independent-global-family-publication claim is made.

## Focused evidence

On 2026-09-11, focused gate 4752 passed 16/16 tests plus Javadoc on the exact Language `8542285144a8969f157d73e73292398885105c46`, unchanged-source BEX `ab72af14ee54c6123e6d80349956af373a887680` and Catalog `0b68744ba6456ef312d1ba87a27096bd2ac5341f` tuple. The source hash before and after was `76266218031845e44e7cdef55d306c209789a47a6b45c61e33000be70808f09f`; only this evidence text was added afterward.

The gate comprises four `StoredCatchUpWorkIndexesTest` controls, one `StoredCatchUpWorkSdkTest`, nine unchanged `CatchUpPlanStoreTest` controls and two unchanged `RootedTerminalTailSdkBoundaryTest` controls. Component tests cover exact due/exclusion order and counters, idempotent application, retirement, old roots, inconsistent/missing memberships, wrong source/barrier order, unavailable source evidence and failed multi-root staging. The actual SDK terminal-tail control compares one numbered successor and two representation applications with an unchanged resident reference, reopening work indexes between steps, retaining full history/event/gas and original-work receipt bytes. It also rejects mixed original work and altered representation cursors. The original 32-selection bound and semantic policy are unchanged. This qualifies the component and next-action parity, not a complete cold SDK installer.

Raw XML/HTML archive: `/Users/kamil/Documents/Projects/Blue/rooted-catch-up-work-evidence.9NgpkE/first-16-pass-results.tar.gz`, SHA-256 `ac4c96917032b8cca2ab273ce4d70cb085cef4be824604f7a38423c8cdbb2492`. The same external directory retains the exact command and gate log.
