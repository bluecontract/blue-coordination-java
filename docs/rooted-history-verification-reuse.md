# Reuse complete historical verification within its existing boundary

## Problem and concrete example

The persistent ring/chord example attaches C a second time under A. Importing its
history includes ordered representation positions inside the same source epoch,
not just numbered epochs. The fixed target does not move as reconciliation runs.
Nevertheless, authenticating the next position repeatedly traversed the same
complete source chain within one verification call.

A diagnostic replay of one late work item on the preserved PostgreSQL input
completed in 63.185 seconds after a cold process restore. Actual Contracts
processing took 1.279 seconds. There were 651 closure-reference decode calls,
87 complete receipt decodes, and 82 additional outer receipt re-encodes despite
existing decoded-artifact reuse. These are nested method counts/times, not
independent processing operations. The cold replay is not a steady-state benchmark
or a full-example acceptance result.

Two concrete causes were found:

1. `verifyCause` authenticated the complete chain, then `verifySupplied` built it
   again. `verifySuccessor` built it three times for capture, prefix and supplied
   evidence checks. These checks operate synchronously on the same invocation.
2. A verified publication receipt could survive in the process-wide cache while
   its encoding association was absent from the separate 8 MiB owner memo. The
   measured new receipt was 9,908,919 bytes, too large for that owner memo. The
   outer reference decoder then encoded its complete graph again to check bytes.

## Correction

- Authenticate one complete chain within each verification call and pass it to
  private helpers for the subsequent checks. Standalone public/package entry
  points still authenticate normally. No chain is cached across calls or owners.
- Keep the canonical byte association alongside the already verified immutable
  receipt in the existing process-wide weighted cache. Reuse requires exact
  object identity and codec profile. Cache hits still adopt and check all retained
  view dependencies in the current scope. The existing publication decoder
  already verifies the exact canonical roundtrip; retain that decoder-issued
  association without running a second cold roundtrip in the cache. The internal
  verified-canonical cache entry point requires this complete check on the same
  input frame and codec profile. It is not exposed to the host. Encoding-only
  objects do not acquire a verification certificate.

This changes neither the source-history target nor logical processing, ordering,
gas, failure policy, publication authority, storage format or public API. Language
and MyOS do not require changes for this batch.

## Alternatives deliberately not taken

- Do not omit the suffix after the frozen target: the existing complete-chain
  validation remains intact.
- Do not introduce a descriptor-only cache that skips physical receipt reads:
  current staging does not independently revalidate those receipt payloads.
- Do not increase the per-owner cache to another large independent budget. The
  existing process-wide cache owns capacity and eviction.
- Do not optimize Timeline SQL or immutable PUT first. Their measured times were
  1.160 and 1.876 seconds, respectively, not the dominant cost in this work item.

## Verification

Required controls cover valid rooted and non-rooted causes, successors, foreign
or skipped positions, malformed targets, missing/corrupt later publications,
repeated standalone verification, actual complete-chain lookup counts, canonical
encoding reuse after owner-memo misses, cache eviction, incompatible view scopes,
and unverified values. Existing storage and representation owners remain in the
combined regression batch.

The combined `controls02` batch completed **73/73 tests across 13 owners**, with
no failures/errors/skips and unchanged source throughout its run (1,037 seconds
wall time). This includes the complete original ring/chord sequence and restart.
These are library tests, not PostgreSQL application acceptance.

Review then identified one unnecessary check in that candidate: the new cache
wrapper repeated `decodePublication`'s existing canonical roundtrip on a cold
read. The final storage correction retains the result of that existing check.
The three affected storage owners were rerun in `controls03`: **29/29 passed**,
with no failures/errors/skips and unchanged source during that run (91 seconds
wall time). The representation-chain and resident SDK implementations are
unchanged. Do not attribute the first batch's source identity to this follow-up
or count its repeated tests as 29 additional unique cases.

The same-input `run02` diagnostic reached the original selected work, but expired
at its 300-second whole-process watchdog without a completed response. Startup
alone took 235.649 seconds (previously 39.553 seconds to the same startup-return
point).
The run overlapped the library regression and physical reads were also much
slower. Neither semantic equivalence nor a speedup is established by this
incomplete run; the exact cause of its timing difference is not established.

Implementation accounting is +44 private production lines: 313 source files,
85,436 lines and 92 public API source types. Only the physical source-size guard
changes; no runtime/protocol limit changes.

Required final gate: the unchanged full PostgreSQL ring/restart example, then
the full paired acceptance corpus. Compare complete semantic response, history
positions, events, gas, receipts and resulting state before interpreting timing
changes. This document does not yet claim those runs have passed.

## Follow-up: keep the complete pure proof with its verified publication

The exported candidate `205029be` still did not complete the PostgreSQL scenario:
the outer 1,200-second driver deadline expired after six settled operations and
submission of the second C attachment. This is not a semantic PASS or evidence
that the algorithm cannot terminate. No restart assertion was reached.

One reuse boundary remained inconsistent. The process cache could retain a fully
decoded receipt larger than 8 MiB, while `retainedForProofReuse` only consulted
the separate 8 MiB owner scope. The exact same receipt could therefore be read
from the process cache without reusing its historical representation proof.
Furthermore, the former constructor memo left the pure rooted checkpoint-reference
authority check outside the memo, repeating original body/reference validation.

The correction keeps a bounded proof memo **inside** the process cache's verified
publication entry. Only full canonical decoding issues that entry; neither
encoding a proposal nor storing arbitrary host bytes can create it. Its reserved
64 KiB proof budget and container overhead are included in the cache weight.
Cache eviction retires the memo with its exact receipt rather than leaving deep
graphs in an independent owner map. Independent owners synchronize memo access.

One memo result covers both the representation constructor and, where applicable,
the original rooted checkpoint-reference authority check. The key includes the
exact publication/input/result objects, source document, epoch, anchor,
predecessor and transition receipt. Before lookup, current physical publication
selection and dependency adoption still run. After a cold proof, insertion also
requires unchanged current membership and the same cache-owned memo. Failed or
staged proofs are not retained. Row ordering, endpoints, current head, next
revision, target and occurrence guards remain outside the memo.

This closes a code-demonstrated reuse gap; its end-to-end speedup is not yet
established. It does not justify retaining a mutable SDK owner across commands,
skipping storage reads or changing the logical processing algorithm. Language,
MyOS, storage format and public API remain unchanged.

Required focused controls use an empty owner cache to exercise this boundary
without an artificially large fixture: same complete proof across two owners,
cold reconstruction after process-cache eviction/clear, rejection of missing
rooted authority and staged/unverified proposals, and unchanged current-history
guards. Existing canonical-byte, corruption and owner-adoption controls remain.

Qualification: controls04 passed all 61 test cases across nine complete owners,
with zero failures, errors or skips. Its before/after source manifests are
identical; no production or test source changed after that gate. The subsequent
run03 one-work diagnostic was incomplete: startup returned after 221.596 seconds,
the first progress hook arrived after 232.838 seconds, and the 300-second process
cap left only about 58 seconds after the selected-work gate. The first history
capture was still running; no after-snapshot, terminal result or speedup claim
was produced. Partial counts do not establish complete-proof memo hit rates.

The user-selected next correctness scope is short-history examples. Those checks
have not yet run on this exported tuple. The original combined long-history
ring/restart scenario and full paired corpus remain unverified follow-up work;
this focused qualification is not a substitute for either.

## Follow-up: select and prove each current publication together

Complete-proof reuse above does not remove physical publication selection. For
each historical representation row, `at` selected the original publication and
then `proveRepresentation` selected it again before looking up the same pure
proof. Each selection can reopen the complete receipt envelope and authenticate
its dependent views. A two-row warm chain therefore selected four receipts,
even though both immutable proofs were already retained.

The private store operation now selects the current durable publication and
looks up its proof together under the store monitor. It uses the ordinary map
read, including physical/canonical and crosslink checks, and the unchanged
identity-bound proof key. The pure cold proof still runs outside that monitor;
retention still rechecks both the current publication identity and the selected
memo after proof completion. A removed/replaced publication or cleared memo
cannot lend a completed insertion. The supplied/staged-object entry point keeps
its independent membership check and cannot gain durable authority merely by
naming a publication identity.

For N durable rows and K cold proofs with an eligible memo, publication selections
fall from `2N + K` to `N + K`. The two-row controls expect warm `4 -> 2`, cold
`6 -> 4`, and no eligible memo `4 -> 2`. The last control uses real physical
publication storage with both owner retention and process-cache reuse disabled;
a zero-capacity pure memo alone would still perform its cold retention guard.
Counter assertions change to measure the intended lookup reduction, not to relax
semantic assertions. Existing frozen-target, malformed/missing later-suffix,
anchor/head, receipt, event, gas and staged-abort checks remain intact.

This is only an O(history) constant-factor correction. It does not cache a chain
across calls, truncate a frozen source history, add a trust flag, change storage
bytes, or implement prefix/delta persistence. The initial replay27 attempt used
an older preserved database whose Coordination artifact provenance did not match
the current candidate; its rejection is not a valid same-input before/after
performance or semantic comparison.

Focused qualification on 16 September: replay27 `coordination-controls01`
passed 72/72 invocations across ten complete owners, including disabled/warm/cold
physical selection, stale insertion, staged abort, corruption and missing evidence.
Dependency, Javadoc, production-shape and public API/SDK guards passed. Production
accounting is 313 sources / 85,504 lines / 92 public types (+29 private lines).
The dirty-source diagnostic JAR is separately byte/source-bound and is not a
published or immutable development artifact. Matched-input timing and combined
host acceptance remain separate; no speedup is claimed by these unit controls.
