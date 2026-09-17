# Cost38: repeated reads and publication — one POC package

Status: implemented candidate; combined verification and timing pending. This is
not a semantic change or a release proposal. The preceding measured candidate
completed the original ring/restart, but its two attachments took 272.402 and
888.102 seconds. Measurements are retained separately in `processing-measurement13/cost37/ANALYSIS.md`.

## Problem → change → justification

| Measured/example problem | Change | Boundary preserved |
| --- | --- | --- |
| Targeted managed drain scans once to validate and again to execute. Projection also requests unchanged global/per-root reads. | Execute the validated head directly. Reuse derived observations under exact document-image, route, object/provider, plan and declared-rejection fences, plus the complete journal entries and cutoff for selection. | Fairness is evaluated on every call; final publication fences remain. Blocked/empty selections and exceptions are not negative evidence. New data/owner recaptures normally. |
| A reconnect makes 63,124 receipt-reference reads although the shared decoded cache is almost always warm. | In a controlled immutable namespace, retain weak owner-local descriptor handles only after that owner has authenticated bytes and adopted dependencies. A hit additionally requires the exact, still-retained shared canonical certificate, including codec family/bounds, digest and length. | First read in a new owner still authenticates storage/dependencies. Raw stores always verify. Eviction/clear falls back to decoding. No host-created certificate or mutable array is accepted. |
| Repeated paths through immutable AVL nodes re-read and parse the same frames. | Reuse authenticated structural frames under the existing weighted process cache budget. The owner keeps only bounded weak handles. | No mutable decoded values are cached. Parent dimensions/range checks remain; transient unbalanced nodes are never retained across operations. Cold owners and raw stores reauthenticate. |
| Canonical enclosing receipt encoding repeats its accepted invocation-frame encoding. | Pass its already decoded canonical frame through the private enclosing-codec call. | The complete enclosing envelope still round-trips; bytes and format are unchanged. |
| A completed encoder forgets that its privately owned Language snapshot was already verified; BlueId traversal repeatedly re-normalizes private canonical parent paths. | Preserve bounded exact encoded-frame acceptance for verified/detached snapshots; append escaped segments to private canonical paths directly. | No trust for public mutable snapshots. Public JSON-pointer behavior, identity, failure paths, gas and wire format stay unchanged. |

Observation values are weak and the key inventory is bounded (32). They are an
opportunistic optimization, not another strongly retained graph cache. GC can
discard them at any time. Runtime close clears the observation. Positive cached
selection does not reserve work or allow bypassing execution/publication checks.
The immutable namespace contract excludes administrative mutation underneath a
live owner. A physical restore requires retiring its owners/caches. This package
does not promise detection of an administrator modifying already authenticated
bytes behind a live controlled owner; cold/untrusted corruption checks remain.

## Host part, in the separate MyOS worktree

- Batch private immutable prewrites (64 objects / 1 MiB); larger objects retain
  the synchronous path. Flush before computation returns or a publication packet
  escapes. Failure discards the owner or retries staging; no head is published.
- Batch slot insertion, ordered locks, current-fence checks, object SHARE locks,
  terminal object links and terminal fences (128 rows per SQL statement). Preserve
  Java host identity order explicitly with SQL ordinals, including non-BMP text;
  database collation must not silently choose another lock order.
- Retain every read fence and unchanged dependency. Head updates keep their CAS.
  This is not batching semantic epochs into one operation.

The projection benefits from common library read reuse and fewer publication
round trips. This package deliberately does **not** skip retained receipt/event
row comparisons on the strength of an in-memory prefix counter. A future suffix-
only SQL projection needs an explicit durable authority, not an assumed cache hit.

## Combined verification, after implementation

1. Library controls: identical versus changed observations; unresolved/failing
   reads; controlled versus raw/cold node and receipt access; cache eviction,
   bounds, canonical bytes, mutable input and cyclic historical witnesses.
2. PostgreSQL controls: 500 read fences should require fewer than 40 prepared
   statements for a read-only publication (previous point path: at least 2,000).
   Test late-chunk stale fences, concurrent causes, Unicode lock order, rollback,
   missing objects, exact conflicts and failed immutable batch flushes.
3. Build one pinned combined Language/BEX/Catalog/Coordination/MyOS artifact set.
   Reuse unchanged BEX/Catalog source, rebinding only its local Language artifact.
4. Run the unchanged full ring and cold restart on fresh PostgreSQL with a 16 GiB
   heap. Compare complete step 7/13 latency, total time, work count and assertions.
   The requirement remains **under 60 seconds per complete authored operation**,
   not merely per internal continuation. No speedup is claimed until measured.
5. Then run the broader regression on that same candidate. Keep full row-validation
   costs visible if they remain a bottleneck; do not loosen correctness assertions.

No upstream/other-engineer branch or PR is changed. No library is published.
