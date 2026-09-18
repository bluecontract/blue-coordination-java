# POC complete terminal-result frame reuse

Historical design note for the `e64a16b` candidate. The following isolated
follow-up replaces its outer `CanonicalStorageCodec` wrapper with Language's
opaque complete-result port, including nested execution-evidence readers:
[nested result and whole-object reuse](poc-nested-result-and-whole-object-reuse.md).
Its first-loader and public-fallback counters are described separately; they
must not be confused with the former extra Coordination canonical encode.

Status: implemented on the isolated POC branch; compilation, grouped tests and
exact-source MyOS acceptance are pending. No results from an older tuple qualify
this change.

The associated Language change certifies the actual owned snapshot DAG only
after complete canonical envelope acceptance; it does not add a retained
result-to-output graph field. MyOS separately adds a test-only PostgreSQL
snapshot-container selector. Those changes do not broaden this helper's reuse
boundary; the exact combined tuple requires its own qualification.

## Problem and example

A rooted result R can occur in a document view, its publication receipt, a
completed source-prerequisite response and the original-result object backing
event/checkpoint rows. These outer records differ, while their nested complete
R bytes are identical. The original-result row reader used decoded L1; the view
and CoreReceipt readers still called the full Language decoder. Their canonical
writers could then serialize the same R again.

The isolated PostgreSQL ring08b run reached the second chord but timed out at
its unchanged 600-second readiness bound. In one completed recording, direct
view and CoreReceipt result decoding accounts for 39 of 686 writer execution
samples (21 + 18). Other result decodes occur inside Language execution-evidence
frames and are **not covered** by this change. These samples identify a repeated
mechanism, not a predicted elapsed-time improvement or a semantic-failure proof.

## Solution and invariants

`StoredClosureResultCodec` is a private typed wrapper around the unchanged
Language complete-result codec and existing `CanonicalStorageCodec`. Its family
includes exact format, maximum bytes and depth. The first raw decode and raw
encode must succeed and match before the host-owned weighted L1 retains the
result and its complete canonical frame. Warm encoding requires that exact
retained object identity; merely equal bodies, BlueIds or invocation strings do
not grant reuse. Encoding a new processor result never certifies it.

The same host cache is passed through `DocumentSessionStorage`,
`StoredResultRows`, `CoreReceiptStorageCodec`, `PublicationReceiptStorageCodec`,
`StoredPublicationIndexes`, `EnginePendingStorage` and `RootedEngineStorage`.
Existing constructors still support the uncached path. Different configured
record/index limits remain different validation profiles.

Complete results preserve input/output, rooted owner/witness context, ordered
events, receipts, checkpoints, gas and deterministic rollback evidence. They
are detached immutable terminal values, not suspended attempts or live demand
capabilities. Returned bodies/events and encoded bytes are defensive copies.
No nested invocation/demand alias table is changed.

Important guards remain outside reusable decoding:

- Every owner authenticates its selected physical bytes and current publication
  membership and validates enclosing input, owner, head and witness cross-links.
- Original-result rows require `result.commits()` **after every shared decode**.
  CoreReceipt legitimately accepts complete rollback evidence; a cache hit on
  that evidence must not turn it into published event/checkpoint ownership.
- Every original-row scope registers the actual result's member objects and
  charges its own scope budget. A separately decoded lookalike member cannot
  borrow registration.
- Receipt/view scope adoption, byte bounds, acknowledgements, mutable sessions,
  work selection and publication fences are unchanged.
- Eviction, clear, disabled retention or a different profile uses the full
  fallback. The cache never closes or retains a mutable owner.

## Alternatives not selected

No final-body/total-gas cache, logical-ID interner, trusted-host validation bypass,
public Language extension or new cache setting. No cache-wide relaxation of
terminal status: valid rollback results remain valid receipts and invalid
original published-row owners. Pure encoding is not sufficient to issue reverse
encoding evidence. The existing proof callback compatibility overload remains
non-null and unused, as specified by Language.

The wrapper does not optimize results nested within Language execution-evidence
codecs; nor does it claim to eliminate the first decode or genuine work for a
new frame.

## Verification to run as one batch

`ClosureResultFrameReuseTest` adds nine controls: actual raw decode/encode
counters across codec owners; detached arrays/body/event copies; corrupt frames;
strict byte/depth profiles; different invocations with equal counters;
certification failure; disabled/oversize/evicted/cleared fallback; cached rollback
rejected by original rows; and two-owner member/physical/scope validation.

`RuntimeDecodedArtifactsTest` verifies shared complete-result identity across
actual view, CoreReceipt, publication-index and original-row readers, while a
changed enclosing publication key still fails. `RootedEngineStorageTest` uses
the existing source-resumption fixture with shared cache to check completed
source and independently retained admission evidence through fresh owners.

Run these with the existing 18-owner / 135-test storage/cache/SDK/gas/representation
group: expected 19 owners and 146 tests after discovery. Run API, SDK, shape,
dependency and Javadoc guards once, then bind actual artifacts and rerun MyOS.
No performance or E2E PASS is claimed by source inspection or this document.

Measured inventory: 312 production Java files and 85,013 lines; this change adds
one private 43-line helper and 47 net production lines in total. Public API/SDK
type and per-source caps are unchanged. The shape cap records that exact
inventory, not extra implementation headroom or a protocol limit.
