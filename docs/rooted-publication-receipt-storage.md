# Exact publication and observed-result storage

## Problem and example

A complete Language result is not the complete Coordination publication row.
A rooted terminal also retains the original invocation, original entry context,
historical origin and work, publication fences, required Timeline set, and an
optional managed-draft plan. A rejected declared birth can retain a genuinely
suspended processor attempt, not a manufactured complete result. Its selected
and executed cohorts must still share the *same plan object*, and its issues
must refer to the original issued demands in that attempt.

For example, a host operation which writes the wrong exact declared child is a
retained feeder rejection. Restoring only an error code, reconstructing a public
demand with equal fields, or independently decoding the two equal plans would
lose checks that the existing rejection authenticator intentionally enforces.
Likewise, a saved-source catch-up drain contains original work and representation
causes which cannot be reconstructed from the source's latest head.

## Minimal physical boundary

All new codecs are package-private components. They use the existing bounded
closed wire, canonical re-encoding, Language-owned exact invocation/result/demand
transport, and the existing session-view reference scope. They do not select
work, consult an exact provider, execute PROCESS, replay a journal, or install a
store state.

- `PublicationReceiptStorageCodec` retains every publication field and the
  original terminal evidence. Its package-only terminal storage projection
  preserves the required Timeline set rather than deriving it from a later
  layout or from an incomplete reconstructed cohort. The existing publication
  constructor still validates the complete result and ownership association.
- Its declared-birth packet retains the selected cohort and associated executed
  attempt. Issues use actual demand-list identity/position. After byte-exact plan
  comparison, the reader creates one packet-local plan alias and reuses the
  original rejection capture/authentication path. There is no global interner
  and no public authority-stamping constructor.
- `CoreReceiptStorageCodec` retains admission receipts and full drain DTOs:
  ordered entry/outcome/attempt maps, progress flags and counters, exact revisions,
  complete or suspended attempts, resolutions, component proofs, route changes,
  publication failures, evidence failures, application receipts, and rooted local
  retained attempts. Map order is preserved where public flattening exposes it.
  A standalone observed attempt retains its original markers but does not acquire
  an invented input association; active continuation uses the associated cohort
  packet instead.
- Application receipts require the caller's original immutable work selected by
  `workIdentity`. `ManagedApplicationStorageCodec` checks the original work and
  both representation domains. Missing work is a physical noncommitting failure,
  not an invitation to select current work. The reader retains the embedded work
  while checking canonical bytes; it does not query a runtime.
- `ResultRowStorageCodec` retains an original complete-result address, row kind,
  and row position for outbox and checkpoint rows. The encoder requires actual
  object-identity membership before any result prewrite; the reader returns the
  actual row from the selected original result. This preserves private typed
  event and checkpoint evidence without flattening or external re-admission.
  Arbitrary manually staged rows without their original result are explicitly
  unsupported and fail closed. One original result address can serve many rows.

ComponentSnapshot transport is extracted byte-for-byte from the already qualified
structural index mapper into the existing shared row codec. It retains the full
original cyclic proof, not a newly inferred component or default proof.

## Trust, limits, and rejected shortcuts

These are trusted pinned-record transports, not user-input verification APIs.
The enclosing immutable store authenticates the selected address and complete
bytes and enforces the physical size/read policy. A checksum is not a new BEX
proof or publication authority. A result-address resolver must load that exact
authenticated result; it must not substitute a current or convenient result.
Malformed, truncated, trailing, noncanonical, out-of-bound, missing-selected,
wrong-domain, or incompatible original-association records fail noncommitting.

No original required Timeline set, captured view, occurrence, original input,
historical cause, result row, or private issued marker is synthesized from current
state. No gas, identity, history, birth policy, scheduling, or publication rule is
changed. This is component qualification, not complete SDK restart or a
PostgreSQL/fresh-process application recovery claim.

## Focused proof

### POC correction: a retained host rejection is not a semantic commit

The parent-owned `coordination-cold-receipt-red01` gate on POC source
`fdf3485334ff4e87353589fb9f273ada2ec565b0` executed 18 cases: 15 passed and
three failed. The two actual `zeroMatches` / `wrongPath` cases completed
Contracts with positive gas but were correctly rejected by the host because
the declared child occurrence was absent or at the wrong path. Their standalone
receipt codecs passed. After retaining and opening the actual document-store
partition, selecting either receipt incorrectly required the speculative
result's changed after-head in published history. That read failed before later
SDK work could be selected. The third red case concerns repeated typed receipt
and representation-proof construction and is a separate physical reuse change.
The native red archive remains external; no application or library green result
is inferred from this source correction.

`StoreState.requireRetainedClosureReceipt` is now the common eager-restoration
and lazy selected-row guard. Only an authenticated rooted rejected-draft receipt
takes the original-input path: the same terminal/plan/result/publication
association, exact target and captured fence, entry-owner history/admission
identities, and original existing-owner positions must remain present. Exact
positions include already validated same-epoch representation history. Current
heads and graph generations need not equal the past invocation; unrelated
read-only source snapshots are not relabelled as publication owners.

Known draft absence belongs to the original receipt-only transaction. An unused
declaration need not have entered the processor snapshot, and a later valid
operation may admit that same child without invalidating the older rejection.
Any actual prospective owner must still match its original uninitialized epoch
zero authored value. No speculative after-head, child initialization, event,
checkpoint, or route effect is published by the reader. No-plan and non-rooted
receipts retain the previous generic result-history validator, including the
committed checkpoint, representation and rollback checks.

The two existing rejection cases retain every original codec/plan-alias/result
assertion and now check both eager and cold selected-store restoration, full
input/result/receipt bytes, unchanged semantic roots and gas, and no provider or
PROCESS work. `rejectedReceiptStillRequiresItsExactOriginalRetainedAuthority`
adds missing/foreign target, missing/changed fence, changed target epoch,
missing/mismatched history/admission, and forged draft-fence controls. Each
corrupted envelope is constructed before asserting rejection by the retained
crosslink guard, so a malformed setup cannot masquerade as that coverage.
`rejectedTargetCanBeAnExactRetainedSameEpochRepresentation` reuses the existing
two-publication representation scenario, then rejects a real declared birth.
The target is demonstrably different from its numbered revision at the same
epoch; eager and cold receipt reads retain its exact input/result while a
different epoch is not accepted as the same position.
Fresh grouped and complete SDK/application qualification is still required.

This is POC-only restoration validation. The qualified baseline branch, public
API, wire format, source-selection rules, semantic identities and logical gas
are unchanged.

`PublicationReceiptStorageCodecTest` uses actual static admission, a committing
rooted operation with private outbox/checkpoint rows, completed host rejections,
an issued wrong-birth suspension, retained response-loss observation, and actual
G-minus-one rollback. It compares full result/input/packet bytes, preserves the
shared plan and demand-list identities, and rejects an unissued public copy,
equal-but-new result rows, wrong row kind/position, missing result, corruption,
and bounds. The producing SDK closes before selected live rows are reopened.

`StoredProcessingReceiptSdkTest` uses the existing real terminal-tail SDK fixture
for one numbered successor and two representation applications, and a real
three-node root-local retained publication. The test-only observation bridge
round-trips raw drains and original publication receipts without replacing any
store or using reflection. It checks complete result bytes (including gas and
ordered evidence), unchanged independent sources, and pure reopens after producer
close. The original bounded selection limits remain unchanged.

Exact commands, source freeze, outcomes and archive hashes are recorded in the
adjacent compact evidence manifest after the final focused gate. Earlier failed
compile/fixture controls remain in the external archive and are not counted as
passing qualification.

### Shared SDK checkpoint oracle correction

The first combined gate, `coordination-cold-receipt-group01` on
`15f2c8c251382397e0eb9ea56c3e485253046b74`, executed 46 cases: 38 passed and
eight failed. Seven failures were in the newly extended shared SDK scenario
(five resident invocations and the two cold `zeroMatches` / `wrongPath`
invocations). The separate eighth failure was a same-epoch scenario's authored
child-identity fixture. The original red archive and this combined archive
remain preserved externally.

All seven SDK invocations passed exact retained-rejection byte comparisons,
including the first checkpoint, and reached the later valid same-child birth.
They then failed a new assertion that a fresh `processing().process(root, oldEntry)`
must return the original rejection bytes. That assertion incorrectly conflated
historical result lookup with supplied-input processing in the current forward
view. `ProcessingGateway.process` documents the latter;
`SdkCoordinationRuntime.processRootInput` calls the engine, without the retained
result fallback used by operation execution. The existing
`RootedRetargetInputTest` explicitly expects `STALE_TARGET_DOCUMENT` when an old
exact-version input is supplied after a successful intervening change, while
separately rereading its original retained execution.

The test-only correction keeps exact historical rejection bytes and pre-birth
duplicate-result equality. After the valid birth, both before and after the
second cold reopen, it checks the current-view response for `STALE`, the exact
original/current target identities, zero gas/transitions, and no closure or
event effects; it then rereads the unchanged stored rejection. Full final
host/child state, receipt histories, original entry identities and exactly one
child initialization remain asserted. No production behavior, replay policy,
gas limit or processing budget changes. Fresh grouped execution must confirm
these corrected assertions; the failed group's byte-length message alone did
not preserve the returned DTO's status or diagnostic.
