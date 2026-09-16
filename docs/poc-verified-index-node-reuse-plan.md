# P2b: retain verified immutable index nodes

Status: measured follow-up design, not implemented or qualified. P2 has removed
the unrelated revision/view payload reads and rewrites; see
[its exact results](poc-indexed-session-history-plan.md).

## Problem and intended result

The qualified 50-epoch SDK successor still makes 23,681 index-node GET calls for
438 distinct addresses. `PersistentMapStorage.scoped` discards its verified
node cache at the end of each map operation. Subsequent projected lineage and
reverse-index checks revisit the same immutable metadata. This is not evidence
that those checks may simply be removed, nor that all remaining work is redundant.

Retain the verified physical node frame under the existing host-owned bounded
cache. While that frame fits and remains retained, repeated use should perform
no second physical GET or structural-frame decode. Continue all contextual
checks at each traversal. This is narrower than selective result reconstruction:
it changes neither the Language proof API nor result/history semantics.

## Exact boundary

- Only the library can insert an authenticated or successfully acknowledged
  immutable frame. The host controls capacity, lifetime and eviction, not proofs.
- Bind namespace, physical format, ordering identity, key/value codec identities
  and address. Recheck current byte bounds, expected height/size and inherited
  key bounds; a node valid under one parent is not automatically valid under any
  other parent. No cached mutable owner, session or publication decision.
- Keep transient AVL construction exceptions operation-local. An intermediate
  node tolerated during rebalancing is not a reusable valid-tree certificate.
- A failed read, malformed frame or failed write acknowledgement cannot issue
  reusable evidence. A cached frame grants no root-membership or publication
  authority. Existing coherent-root selection and fences remain mandatory.
- The controlled-origin path can retain completed node verification. Generic
  untrusted reads retain their existing strict contract. Cache hits must not
  silently turn an arbitrary external namespace into a controlled one.
- Use the existing weighted process cache, including entry-count and single-
  entry limits, LRU eviction and single-flight behavior. No unbounded per-map
  cache or additional storage service.

Likely private touchpoints are `PersistentMapStorage`, `RootedStorageCache` and
the storage assembly that passes the optional cache and controlled namespace.
Confirm the exact integration before changing code; cache-family names alone
are not a security boundary.

## Library-first acceptance

Use the same real SDK 5/20/50 numbered-history and same-epoch fixtures: cold open,
current/point reads, submit, process, stage, close and fresh reopen. Preserve the
resident/cold equality oracle for full results, states, BlueIds, epochs, events,
order and gas, and every P2 zero-unrequested-payload/read-rewrite budget.

Measure actual verified-node loads/structural decodes, physical GETs and bytes,
distinct addresses, hits/evictions, retained weight, contextual row-check counts
and phase CPU/wall time. Do not call object GET counts decoder counts. Declare
the namespace/binding key and fitting cache budget before measuring.

Run disabled/cold, fitting warm, eviction and fresh-owner cases. For a retained
node, require at most one physical read and structural decode per cache lifetime;
a successful library write may establish it without a later read. Explicitly
test foreign namespace/binding, smaller current limits, corrupt/missing cold
records, wrong parent dimensions/order bounds, failed acknowledgement and failed
load retry. Eviction or a fresh cache must reproduce identical semantic output.

The unchanged implementation must fail the new reuse budget for the expected
reason before the candidate passes it. Report remaining metadata work separately;
do not promise constant total cost or a wall-time percentage. Only after this
library result should the same pinned artifact receive a short PostgreSQL
confirmation. The long graph is not the first experiment for this hypothesis.

## Not included

P3 ordered catch-up windows, P4 selective result/view reconstruction, Redis,
larger record limits, new protocol limits and changes to logical gas are separate.
Chunking a complete verified result byte array alone does not establish a valid
selective result read.
