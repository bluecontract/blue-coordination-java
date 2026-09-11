# Exact-input eligibility memo — candidate physical optimization

## Problem and observed example

The final MyOS pre-merge product run on Coordination `d0382bbc` and Language
`2ce3e66b` completed all 54 negative BEX program operations. Each operation was
REJECTED without changing its document session. Restart then exceeded the
unchanged 120-second readiness deadline. Six smaller groups of nine operations
passed, including their restarts. Passing the operations is not passing the
composite restart, and no deadline or expected result is changed here.

Two attributed thread samples at 68.8 and 118.6 seconds showed the main thread
RUNNABLE in replay, journal preselection, rooted delivery classification and
exact Language body resolution/identity reconstruction. Its CPU time increased
from 65.9 to 114.0 seconds. The retained ledger has 56 commands: Timeline
creation, document start, and the 54 operations. The samples do not establish
how many commands had replayed before timeout. They show repeated CPU work,
not a JDBC-wait diagnosis or proof of a fixed runtime complexity.

Original evidence is retained outside this repository in MyOS worktree
`rooted-baseline-merge-readiness`:

- `build/campaign/r2/batches/baseline-final-premerge-product-01/gradle.log`
- that batch's `diagnostics/bex-composite-restart-01-thread.txt` and
  `diagnostics/bex-composite-restart-02-thread.txt`
- `build/reports/public-api/bex-negative-progress/programs-composite-116dfeff-eb60-4bc8-b447-e09e0105cf32.json`

The external `rooted-baseline-release-review.VamIZv/final-premerge-product-followups.md`
record F01 binds those observations. The original acceptance tuple and sealed
exports remain untouched. This candidate is based on `d0382bbc` but was not part
of those completed, failing full product/public-HTTP runs.

## Proposed correction

`BlueRuntime.eligibleRootDeliveries` keeps a runtime-local, disposable memo of
one **complete successful comparison call**. The key retains:

- every selected target's complete resolved structural representation;
- the exact event BlueId and frozen structural representation;
- the original ordered delivery descriptors: document, raw channel, logical
  delivery key and raw occurrence order.

It does not trust `ManagedDocumentSnapshot.blueId` as a substitute for the body.
Language's existing `FrozenNode.resolvedStructuralKey` distinguishes exact
representation and construction modes that a semantic BlueId can omit. Only
the nodes actually read by this classifier enter this key; graph selection,
history, activation intervals, terminal identities and publication fences
remain in their existing callers and are evaluated afresh.

A successful cache entry contains only a defensive eligibility mask. A hit
returns the current call's delivery objects selected by that mask, never old
invocations, PROCESS results, source-history capabilities or publication
handles. An exception or incomplete mask installs nothing. Disabled reuse skips
key construction entirely. If exact key capture cannot represent the input,
the uncached classifier retains ownership of validation and its original
failure order; classifier exceptions are outside the key-capture fallback.
A miss executes the
whole original comparison session with the same ordered calls and shared
comparison gas meter. PROCESS, canonical next-work ordering, typed terminal
receipt/rejected-birth validation and publication ownership are unchanged.

The private default uses the existing bounded Language derived-cache budgets:
128 entries, 64 MiB estimated retained weight, and 16 MiB per entry. This is an
additional disposable cache cap, not a reservation inside Language's existing
cache budget. Keys are
constructed before using `FrozenNode.approximateRetainedWeightBytesOf`, so its
iterative estimate includes cached structural-key graphs; conservative
key/list/string/mask overhead is added with saturating arithmetic. Sharing
across entries is deliberately overcounted rather than retained without a byte
charge. Both total weight and entry count evict least-recently-used entries.
Oversized entries execute uncached, without rejecting the document or evicting
useful entries. Cache reset and runtime close clear all entries. Limits are
private physical reuse policy, not protocol input, gas, or history limits.

The enclosing runtime privately builds and retains one fixed processor,
registry and Language configuration for its lifetime. Memo entries never cross
that lifetime or migrate to another provider/configuration. Successful exact
evidence may be reused like the existing derived caches; no unavailable result
is retained, so subsequently supplied missing evidence is retried normally.

## Rejected shortcuts and limits

RCP-ORDER-06 and RCP-CAUSE-03 require equivalent ordering, results and failures
when optimizing; they do not require this memo. The internal comparison cannot
be replaced by a host-supplied result through an existing capability, but another
replay/index design could address the cost. Retaining this candidate in the
final fix set depends on the measured original composite restart benefit, not
merely on a passing cache test. It is not a protocol-correctness requirement.

An entry ID, root ID, journal prefix, epoch, or asserted document BlueId alone
is not a safe terminal filter. The same input can encounter a different active
receiving set or owner context after topology/channel changes. Terminal keys
remain based on actual accepted ChannelOccurrences and rooted history/SCC
context. Their current validation is not moved ahead of classification.

Per-delivery hits mixed with misses are also not used: the comparison call
shares one gas meter, and dropping some comparisons can change exhaustion.
The memo reuses only a successfully completed whole-call result. Missing
evidence, invalid bindings, thrown failures and partial classifications cannot
be cached as “ineligible.”

This proposal removes repeated classification for matching exact calls. It
still scans/sorts the journal and constructs exact structural lookup keys;
changed checkpoint bodies miss, and byte pressure or oversized bodies can
reduce reuse. It is not a journal index, an asymptotic scaling proof, a physical
heap bound for the whole runtime, or evidence that the 120-second restart now
passes. No provider, Language, SDK public API, persistence mode or application
deadline changes are included.

## Focused regression evidence and remaining gate

Three maintained owners (18 cases) passed in `focused-library-followups-02`:

- `RootedEligibilityCacheTest`: reallocated-equal exact keys/current DTOs;
  complete empty/all-false masks;
  same asserted epoch/BlueId with changed body; event identity/construction
  modes; ordered delivery/channel changes; exceptions and partial masks;
  entry/byte eviction, clear, disabled/unsupported-key and oversized uncached fallback.
- `BlueRuntimeEligibilityCacheTest`: actual Language comparison/hit/reset/close,
  repeated actual invalid-channel exceptions, malformed body with an old
  asserted head, missing target/event validation order, and an actual calibrated shared comparison-meter exhaustion
  after a previously cached single-delivery call; no substituted processor.
- `RootedEligibilitySelectionTest`: actual rejected unchanged-head sequence
  followed by new LIVE work; genuine same-epoch checkpoint-containing-reference
  transitions with borrowed-source isolation; independently materialized exact
  gas/trace/output/event/checkpoint comparisons; G−1 failure, publication
  rollback, eviction and restart with unchanged retained histories.

The SDK fixture's `restartFromStores` rebuilds scheduling/routes over retained
stores; it is not a new process or complete SDK reconstruction. The prepared
test explicitly evicts the disposable caches before that boundary. The actual
fresh-process acceptance remains the unchanged MyOS composite/restart owner.

The same run passed 23 adjacent selection/gas/history controls but was red
overall: four unrelated new FULL_HISTORY reference cases lacked exact resources.
The archived XML and source are in
`rooted-focused-followup-evidence.jtpYIX/library-focused-02.tar.gz`.
This qualifies neither a sealed library artifact nor the fresh-process
54-program deadline. That original composite/restart and the complete
product/public-HTTP gates still need the exact final new tuple. Neither library
may merge on the basis of these focused passes. No public API/metric, BEX or
Language code change belongs to this memo.
