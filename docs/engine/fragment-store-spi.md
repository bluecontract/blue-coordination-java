# Fragment-store SPI

`CoordinationFragmentStore` is the physical, storage-neutral boundary for
immutable canonical fragment bodies and body-free graph inventories. It also
implements `NodeProvider`, so exact content can be resolved by BlueId, and the
atomic immutable-admission contract used by the verifier.

## Required operations

An adapter implements:

- `fragmentationProfileIdentity()` — the one exact physical namespace used by
  the store;
- `read(profile, blueId)` and the normal `NodeProvider` lookups;
- `readAll(blueIds)` — exact outcomes for one requested batch;
- `putIfAbsent(profile, blueId, fragment)`;
- `putAllIfAbsent(profile, fragments)` — one all-or-nothing immutable body
  batch;
- `putInventory(inventory)` — idempotent persistence of a verified body-free
  inventory;
- `requireInventory(identity)` — closed-shape rehydration or fail closed.

The engine accepts only the current `CoordinationDocumentSplitter` profile. A
profile mismatch is not a cache miss; it is an evidence/configuration failure.

## Atomic immutable body admission

`putAllIfAbsent` must first validate all existing winners and then install all
missing bodies atomically. If any existing identity maps to conflicting
canonical bytes, it must install none of the proposed batch. Its boolean result
only says whether this call installed at least one body; it is not proof that a
duplicate was valid.

The engine's `CoordinationFragmentAdmissionVerifier` validates the complete
graph before calling the store, invokes this method once for the complete body
batch, and then reads every winner back. Every winner must have the requested
BlueId and the same canonical wire bytes as the proposal. Adapters must return
defensive values and fail closed on ambiguity, corrupt identity evidence, or
conflicting immutable content.

## Inventories are metadata, not body blobs

`CoordinationFragmentInventory` records the schema, fragmentation profile,
edge schema, semantic Root, retained fragment IDs, root records, direct-edge
occurrences, and metadata records. Its identity covers that closed structure;
the structure contains no fragment bodies.

`toMap()` is the closed scalar/list/map persistence representation.
`rehydrate()` rejects missing or unknown fields, unsupported schemas, malformed
graphs, and an identity that does not match the content. `reconstruct()` loads
all required bodies, verifies each BlueId, rebuilds the graph, and verifies the
semantic Root.

Persist an inventory only after every body it owns is present. Repeating the
same inventory identity and content is idempotent; different content under the
same identity is an integrity failure. An authored unresolved pure reference
may point outside the inventory, while every splitter-created cut must point to
a body retained by it.

## Reads and request locality

`readAll` should preserve an exact outcome for every requested identity,
including not-found results. The bundle loader uses it for the predictable
initial batch. Request-local fallback reads may still occur within the exact
allowed causal closure, and diagnostics distinguish initial batch reads from
fallbacks.

Do not make a cache return content from a different profile or silently choose
one of multiple candidates. A cache may change physical latency, never the
semantic provider domain or PROCESS result.

## Deduplication and retention

BlueId-keyed bodies are globally reusable within the configured physical
profile. Equal Roots or embedded nodes across different sessions can share the
same stored body without sharing session state. Inventories, edges, and
occurrence metadata retain the graph context needed to interpret those bytes.

Removing a session does not delete fragments. A production host may add
mark-and-sweep, leases, legal holds, or archival tiers, but collection must be
defined over authoritative session and epoch reachability and must not violate
immutable read semantics. Garbage collection is outside the engine SPI's
transactional promises.

## Failure model

Treat these as hard integrity failures, not retryable misses:

- same profile and BlueId with different canonical bytes;
- a winner whose calculated BlueId differs from its key;
- a partial `putAllIfAbsent` after a conflicting winner;
- an inventory whose referenced owned body is absent;
- an altered, open-shaped, or identity-mismatched inventory;
- a read that is ambiguous rather than exactly found or absent.

See [database host integration](database-host-integration.md) for a relational
mapping and [atomic commit](atomic-commit.md) for the boundary between immutable
admission and the authoritative session CAS.

