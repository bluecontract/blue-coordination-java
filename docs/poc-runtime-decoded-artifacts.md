# POC runtime decoded-artifact reuse

Status: the complete managed-epoch receipt extension passed 41/41 controls with
API/shape/Javadoc checks, but its bounded PostgreSQL run still timed out during
the second import. The [canonical-frame follow-up](poc-canonical-frame-reuse.md)
is now implemented and awaits grouped regression. The earlier three-family cache completed 84/84 storage
controls; a public implementation-type rejection was corrected through the named
assembly bridge. Its bounded PostgreSQL run passed the first chord import and
following reaction, but timed out during the second import. The full ring/chord
scenario is not yet qualified.
This is a physical-storage follow-up to the separately frozen baseline. It changes
neither rooted processing rules nor logical gas, publication, event, epoch or
BlueId rules. PostgreSQL remains the only durable backend in this POC.

## Concrete problem

Successive catch-up steps open and close independent storage owners. The next
owner can reread the same immutable view, result or receipt and repeat complete
Language/Contracts reconstruction and validation. A receipt can reference views
outside its own byte frame. Retaining only the receipt object across owners is
incorrect: the new owner's view-address/provenance maps and scope accounting have
not been established.

## Change and rationale

A host-lifetime `RootedCoordinationStorage.Cache` is explicitly passed into storage assembly.
The host selects its estimated weighted-LRU budget and lifetime. Only the library
can insert decoded artifacts; the host cannot create a verification certificate.
Existing constructors without this optional cache preserve cold behavior.

The SDK facade delegates only through the existing named `RootedEngineStorage`
assembly bridge and its nested `Cache`. The actual `RootedStorageCache` algorithm
is package-private. No new top-level implementation class is public, no artifact
insertion is exposed to the host, and neither API-boundary allowlist is broadened.

Four complete artifact families are reused:

1. **Rooted view:** a self-contained fully decoded `RootedDocumentView`. A new
   owner authenticates the selected physical frame, enforces its own scope budget,
   then registers the immutable view and exact address in its own identity maps.
   The surrounding mutable `DocumentSession` is always restored separately.
2. **Original result:** one fully decoded committing `ClosureProcessResult`.
   Each new `StoredResultRows` owner registers its original event/checkpoint
   member objects and positions again. Equal but independently constructed rows
   do not inherit that association.
3. **Publication receipt:** a complete fully decoded receipt plus a private
   manifest of every referenced rooted view, its exact bytes and physical
   address. On reuse, all dependencies are authenticated and the complete new
   scope charge is preflighted before registering any view. If an already-open
   owner has a different immutable object identity for that address, ordinary
   whole-frame decoding preserves that owner's aliases instead.
4. **Managed-epoch receipt:** one complete public numbered receipt plus its
   optional original Contracts transition receipt. The same canonical frame is
   used by numbered and receipt-identity indexes. It is self-contained, with no
   external-view manifest or owner callback. Current document/epoch keys,
   first/last history coverage, range holes and cross-index correspondence are
   still checked on every selected read. Canonical re-encoding occurs inside the
   initial cache loader, before insertion; the outer physical map's existing
   canonical check also remains.

Keys include the full frame format/version, maximum record size and maximum
decoding depth, plus a digest and exact-byte comparison. A changed profile cannot
inherit another profile's validation. Failed full decodes create no cache entry.
Receipt manifests account conservatively for all retained view frames as well as
the receipt frame. The configured weight is an **estimate**, not a hard JVM heap
bound; decoded allocations must be measured separately.

The starting weight estimator is `512 + 8 * (frameBytes + dependencyFrameBytes)`:
entry overhead, retained physical arrays and estimated decoded containers/nodes.
Shared dependencies are charged again rather than silently retained for free.
This multiplier needs heap calibration, not a claim of exact retained size.
Active operation references and in-flight decodes are outside retained-cache
capacity and remain bounded by host concurrency and existing operation limits.
LRU uses access order; same-key loads share one in-flight computation without
holding the global LRU lock during decoding. Wait interruption is noncommitting.
Clear/close prevent loads already in progress from repopulating the old cache
generation. Cache statistics are process totals, not per-owner additive counters.

## Checks deliberately retained

- First decode performs the original canonical and semantic cross-checks.
- Selected immutable records and referenced view bytes are read and authenticated
  even when their complete decoded artifacts are cached.
- Current publication-map membership, receipt keys, owner/session association,
  logical boundary, captured history positions and publication fences remain
  per-selection checks. Cached content does not establish publication authority.
- Scope budgets, original-row provenance, write acknowledgements and staged-root
  publication remain unchanged.
- No open scope, mutable document, provider callback, store projection or
  representation-proof memo is shared across owners.
- Individual nested execution/demand fragments are not globally interned;
  complete-frame alias topology remains intact.

This first implementation is read-through reuse of already fully decoded
artifacts. It does not introduce a general trusted-storage `skipValidation` flag
or claim that arbitrary newly encoded/staged objects are authenticated published
results. A future optimized cold trusted-producer restoration contract is a
separate change.

## Added controls

`RuntimeDecodedArtifactsTest` covers immutable view reuse after owner close,
fresh mutable sessions, defensive Node access, profile isolation, missing/corrupt
bytes under a warm cache, result-member re-registration, receipt dependency
rebinding, current publication membership rejection, fallback for incompatible
local aliases, and atomic preflight of a too-small view scope. These tests use
real SDK-produced rooted artifacts. Run them together with existing session,
receipt, result-row and rooted engine storage controls before MyOS E2E.

No acceptance assertion, resource budget, timeout or semantic oracle is changed
by this artifact integration.

## Complete managed-epoch receipt follow-up

A sample during the first cache-enabled PostgreSQL run found the command writer
in `ExactValueStorageCodec.decode` while `RootedCheckpointDriver.order` requested
managed-epoch evidence during projection capture. The first three cache families
did not cover this path. `StoredManagedEpochIndexes` decodes the selected history
twice in its exact cross-index check; history decoding reads first and last
numbered receipts, and the target and identity-index rows are decoded separately.
`PersistentMapStorage` caches physical node bytes, not decoded row values. Thus
even one selected operation can repeat complete verification of identical cyclic
exact-value proofs. This is a verified code path, not a measured share of total
runtime or a claim that no other bottleneck remains.

The follow-up extends the existing L1 to complete `StoredReceipt` frames and
passes that same cache from `StoredDocumentStore`. It changes two existing private
production sources (+19 lines), adds no API, and changes no Language or MyOS code.
It does not cache `DocumentHistory`, persistent maps, selected `EvidenceRead`
objects, or an independently derived order shortcut. Lower-level broad
`ExactValue` interning would require wider propagation; an order-only index would
introduce another authenticated selection contract. Neither is needed here.

`StoredManagedEpochIndexesTest` adds decoder-load-count controls across fresh
owners and both indexes; warm missing/swapped identity, wrong-owner, interior-hole
and physical-corruption rejection; malformed-frame nonretention and profile
isolation; eviction/disabled parity; an actual SDK-produced cyclic receipt; and
the distinction between a warm numbered receipt and a newer same-epoch
representation. Existing unrelated-history read guards also run with the cache.
The receipt extension passed its 41-test grouped run on `3f343a1`. That result is
not a pass for the newer canonical-frame batch or complete MyOS E2E.
