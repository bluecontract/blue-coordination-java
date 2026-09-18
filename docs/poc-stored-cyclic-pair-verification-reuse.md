# Reusing verification of persisted cyclic provider pairs

## Problem and concrete example

The qualified tuple10 libraries still left the durable ring's second chord
unfinished after its unchanged 600-second readiness window. In a 60-second
recording from that interval, 160 of 626 writer execution samples were classified
as other provider verification. These are samples, not elapsed-time percentages.

The provider boundary repeatedly asks for the same cyclic member's wire body and
complete proof while capturing rooted state. Caching its immutable ExactValue
does not remove this separate body/proof verification. The packaged C4bffe
bytecode was checked against source: line 447 calls the cyclic helper, whose
actual verifier call is at line 473. No stale class or packaged shadow was found.

For example, ten separate state captures can read the same persisted member A,
the same exact wire body and the same complete cyclic proof. Every read must
still authenticate its current selected storage records. Once the **exact pair**
has passed the provider verifier, re-running that pure calculation is redundant.

## Solution and safety boundary

Only the closed WholeObjectStorage decoder offers the optimized internal
accessor. A call performs these steps:

1. Select and read the current member record; verify its size, physical digest,
   complete framing and expected member identity.
2. Decode its canonical/provider values and exact cyclic wire body.
3. Select and read the current master-proof record; verify size, digest, format,
   master identity and complete framing, then reconstruct the complete proof.
4. Look up a success-only memo keyed by the expected member, exact wire frame,
   complete selected proof frame and byte/depth profile.
5. On a miss, execute the unchanged provider verifier. Retain only Boolean
   success and the key bytes in the existing host-weighted cache.
6. Return a fresh detached Node. No Node, proof, mutable store or publication
   authority is retained by this memo.

The key uses length-delimited fields and full byte equality after the cache
digest lookup. Invalid pairs are never retained. The proof must be selected and
read **before** a hit; a warm memo cannot hide missing, corrupt or changed proof
records. Existing generic backing checks remain intact.

WholeObjectStore uses this accessor only when neither the member body nor its
master proof has an attempt-local overlay. Public/local Nodes may have custom
clone behavior; those objects are never eligible merely because they serialize
to the same bytes. Their original processing path and error behavior remain.

The default backing accessor is unsupported/empty, so other backing
implementations continue through the existing verification path.

## Capacity, lifecycle and unchanged behavior

There is no second cache and no unbounded map. The process-wide weighted LRU,
single-flight loading, eviction, clear and close behavior remain the controls.
A combined key exceeding the available per-entry/total retention budget skips
memoization and uses the ordinary verifier. This does not introduce a new
combined storage-record limit or reject a previously valid read.

This is not verification of arbitrary externally asserted results. Authority
comes only from a successful calculation over freshly decoded, storage-owned
values. The optimization changes no public API, wire format, BlueId, graph,
epoch, ordering, logical gas, operation boundary or publication rule.

Rejected alternatives:

- Memoizing by member BlueId alone: different representations/proofs must not
  inherit earlier success.
- Trusting cached ExactValue instead of validating the selected wire/proof pair:
  these are different evidence and mutation boundaries.
- Retaining mutable Nodes or caching whole backing entries: that could hide
  current row failures and leak mutations or subclass behavior.
- Disabling provider validation or optimizing unrelated result/snapshot work:
  neither is needed for this measured seam.

## Verification plan

Eight focused controls exercise the real requireProviderDocument path:

- repeated/new-owner calls: one raw pair verification, two fresh physical reads
  per call and detached output Nodes;
- missing, corrupt and wrong current selected records, even after warm success;
- digest-valid but inconsistent body and proof changes, with repeated failures;
- member and byte/depth profile separation; clear forces another verification;
- disabled/tiny cache budgets preserve accepted reads through cold verification;
- valid local overlays and rollback;
- either a local body or local proof alone prevents memo authority;
- concurrent independent owners share only the successful pure calculation.

The existing generic persisted-corruption test also covers this provider accessor.
The complete previous 26-owner gate plus this new owner is expected to contain
190 tests across 27 owners; exact fresh discovery and results remain required.
No tests/builds have been run for this follow-up while implementing it.

Before accepting the candidate: run that grouped library gate and existing
API/SDK/shape/Javadoc guards, export exact immutable artifacts, then run MyOS
controls and the unchanged durable ring. A targeted library PASS is not E2E
acceptance or a performance result.

Production delta versus C4bffe: 73 net Java lines in four existing files;
312 production source files / 85,183 lines, with 92 public API types unchanged.
