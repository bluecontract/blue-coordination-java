# Bounded cold publication receipt and proof reuse

## Problem and reproduced boundary

MyOS `25eacea` / Coordination `fdf3485` completed the whole product corpus with
six external-state cyclic/history READY deadline failures. One bound thread
sample in the ring/chord case was inside stored receipt canonical re-encoding,
including full result, snapshot and cyclic identity verification. This is a
concrete costly path, not attribution of all elapsed time to that path.

`PersistentMapStorage` previously decoded a new closure receipt on every get.
The original publication codec verified its canonical round trip, then the map
performed another encode check. Fresh receipt/input/result objects also failed
the existing exact-object eligibility check for pure representation-proof reuse.
The isolated pre-fix group reproduced that distinction with actual publications:
identical bytes and semantic positions, but four failed object/proof reuse
assertions. The group was 15/18 passing; its other two failures independently
reproduced rejected-publication history validation. Raw RED results remain in
`rooted-external-resumption-evidence.c0VVLS/coordination-cold-receipt-red01-reports`.

## Correction

Each stored publication-index binding owns a disposable immutable receipt cache.
Its default limits are 2,048 entries and 8 MiB of complete encoded receipt
payload; these are physical optimization bounds, not logical gas or portable
processing limits. Values exceeding the bound still decode correctly, uncached.
First decode retains all constructor and canonical checks. Cache hits require
exact canonical bytes, not only a digest, BlueId, final body, or gas total.
Only the exact successfully decoded object can reuse its canonical encoding.
Supplied/returned bytes are detached; failures and encode-only proposals are
not cached. Mutation preparation still retains required views/results before
physical map writes.

Selected key, generic/admission membership and retained-history validation remain
outside this cache and execute on every selected read. It provides neither
independent publication authority nor reuse of uncommitted LIVE calculation.
The wire format, result/companion identities, historical ordering, failure
outcomes and logical gas remain unchanged.

Cold representation memoization is explicitly bound to that cache before an
opened engine is exposed. Only cache-owned retained receipts qualify. Eviction
changes an opaque retention epoch; the proof memo clears before its next lookup
or retention, including after an out-of-monitor proof calculation. Therefore
memo entries cannot accumulate uncharged deep graphs from successively evicted
receipt generations. Before the next proof, an older bounded receipt generation
can remain reachable through the memo; it is not a strict instantaneous heap
cap of 8 MiB. Encoded-byte accounting is not a measured JVM heap size. Referenced
rooted views continue to use their separate existing bounded restoration scope.
Close clears the receipt scope and engine proof memo. A manually restored cold
store without the explicit binding does not enable strong proof memoization.
The resident store's existing proof memo and semantics are unchanged.

## Controls and rejected alternatives

Controls use real authenticated publication fixtures and count complete decoder
calls. They cover repeated exact reads, returned-byte mutation, equivalent but
independently decoded objects, malformed input retry, entry/byte eviction,
oversize/disabled caching, close/new-owner separation, and proof invalidation
after eviction. Existing cold representation, corruption, gas, original-store
and byte-identical staging assertions remain in the grouped gate.

Rejected: an unbounded/global object interner; changing identity guards to string
equality; retaining deep receipt graphs in a wrapper-only proof budget without
eviction coupling; weakening first-read verification; increasing acceptance
deadlines. A body plus total gas is not a replacement for an exact result.

The pre-fix failures are executed evidence. The correction's grouped and paired
application gates are pending; no speedup or full external acceptance is claimed.
