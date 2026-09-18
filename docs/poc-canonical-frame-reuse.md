# POC canonical-frame reuse follow-up

Status: implementation and controls prepared; grouped regression and MyOS E2E
pending. This follows the bounded runtime L1 experiment, not the frozen baseline.

## Problem and example

The PostgreSQL ring/chord scenario reached its second historical import but did
not finish within the unchanged 1,200-second driver limit. Samples found the
writer re-encoding complete work evidence while selecting pending work, and
serializing a new rooted view separately for several sessions in one stage.
An L1 decode hit alone did not eliminate this work. Samples locate costs; they
do not establish a percentage or prove an end-to-end improvement.

For example, pending and primary indexes select the same immutable work W.
W carries an original Contracts result with verified cyclic snapshots. Encoding
W twice to establish full equality descends into those snapshots again. Likewise,
several changed sessions can share a new immutable view V while their session
records themselves remain different and mutable.

## Solution and safeguards

- Eligible self-contained immutable work, application and managed-receipt codecs
  retain a complete canonical frame together with the decoded object. The first
  **raw decode then raw encode** must match before retention. A generic decoded
  cache entry is not a canonical certificate.
- Existing persistent-map canonical round trips remain. Encoding can reuse only
  the exact retained object identity, with owned bytes and matching codec/limits.
  Registered work additionally binds the nested due-key byte limit. Equal but
  independently created values take the full fallback.
- Reverse evidence shares weighted-LRU lifetime with the complete artifact.
  Encoding reuse refreshes access order; eviction/clear remove reverse entries.
  Cache hits now include canonical-encoding hits as well as decoded-artifact hits.
- Work equality may use the same immutable work object. Receipt equality may use
  both identical fields of the two-field immutable `StoredReceipt`. Matching only
  a logical work/receipt ID is never sufficient. Distinct values retain full
  canonical equality checks.
- A separate owner-bound, stage-local token remembers the first successful
  encoding address for V. Reuse still authenticates retained bytes, charges each
  session scope and obtains all actual new/session write acknowledgements.
  [Freshly authenticated view dependencies](poc-authenticated-view-retention.md)
  are not PUT again; missing or corrupt selected reads still fail. Failed writes do not
  certify V. The token is closed after staging and never enters decoded-view or
  publication membership maps. Mutable session records are always serialized.

No format, public API, processing order, gas, epoch, failure, historical boundary,
publication fence or BlueId rule changes. No Redis or trusted-host validation
bypass. Lower-level broad interning and caching mutable selection results are
unnecessary and would require wider ownership contracts.

## Inline exact-value reuse: implemented, qualification pending

A session stores exact values inline in revisions, events and layouts, separately
from its referenced rooted-view records. Reopening the same historical session
can therefore hit the rooted-view cache while repeatedly rebuilding and
verifying identical inline `ExactValue` frames. A cached view is not a cache of
those session-row values.

This follow-up passes the existing host-owned `RootedStorageCache` only
to the session's private `SessionRecordCodec`. It reuses a complete inline exact
frame, including its frozen, resolver-snapshot or cyclic-proof lane. The key
binds the entire owned frame and the byte/depth codec limits, not just BlueId.
On the first load, the ordinary `ExactValueStorageCodec` still verifies identity,
lane evidence and canonical bytes; the existing cache additionally requires raw
decode/encode equality before issuing an encoding certificate. Later writes
may reuse bytes only for that exact retained immutable object identity.

This is reuse in the existing estimated weighted-LRU budget, not a second cache.
Eviction, disabling or a different value/codec profile takes the ordinary path.
Cold loads retain full verification cost; this fallback does not optimize the
initial decode. Mutable sessions, owner state, indexes and publication decisions
are never cached. Enclosing session bytes still load and authenticate, session
history/current-state validation still runs, and scope bounds and publication
checks remain unchanged. Returned mutable nodes, proof nodes and byte arrays
are detached. There is no public API, storage format, Language change, authority
bypass, gas rule or processing-semantic change.

Controls in `SessionRecordExactValueReuseTest` cover cyclic and
snapshot-bearing frames, equal BlueIds with different complete lanes, corrupt
and wrong-identity bytes, codec limits, eviction/disabled fallback and detached
getters. The real-session control also checks immutable `after` sharing while
requiring a new mutable session. These are implemented controls, **not yet PASS results**.
Grouped regression/API/shape qualification and an exact-source MyOS E2E remain
required. The host-only `poc-16g` experiment on Coordination `8676f30` ended at
the second chord's 600-second readiness deadline without OOM or decoded-cache
eviction. Its completed prefix and CPU profile identify this remaining seam;
they do not qualify this library follow-up or prove the unexecuted suffix.

The earlier canonical-frame batch's measured production inventory is 311 Java files and 84,949 lines (+1 private
file, +138 lines from `3f343a1`). Shape caps match that inventory without spare
allowance; 92 API/SDK types and the 1,166-line retained-managed-epoch cap remain.
The applied inline follow-up measures 311 Java files and 84,966 lines (+17);
the shape cap matches that inventory. API/SDK type and per-source caps stay
unchanged. Native shape/API regression and E2E qualification remain pending.

## Verification

`CanonicalStorageCodecTest` counts full encodes and decodes, including repeated
actual persistent-map reads. It covers raw certification, equal-but-distinct
fallback, owned frames, complete validation profiles, LRU/clear, disabled/oversize
fallback and clear during validation. `StoredCatchUpWorkIndexesTest` checks warm
reuse while preserving current links and strict-after-permissive nested-key
rejection. Existing managed-receipt corrupt/current-membership tests remain.
Stage tests count full view encodes and exercise failed writes, missing/corrupt
bytes, owner boundaries and real multi-session staging.

Run all affected storage, cache, SDK history/gas/representation controls together,
then export the exact tested source and repeat the full unchanged PostgreSQL
ring/chord/reconnect/restart scenario with 16 GiB heap and no CPU cap. Only its
complete pass permits claiming closure; then run the full acceptance corpus in
memory and with external persistence. No semantic assertion is weakened.
