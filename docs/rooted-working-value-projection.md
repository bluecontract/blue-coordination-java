# Lazy working values over retained point rows

## Problem and example

The existing publication algorithm installs an interim copied session before attaching its final authenticated rooted view. A physical session-value codec invoked on every AVL `put` would serialize an intermediate row, or replace the actual mutable working object with a decoded copy. Eagerly converting every retained address to a session at open would instead restore the whole catalog.

## Solution

`PersistentOrderedMap.ValueProjection` lazily projects values from an already pinned physical AVL. Opening creates no descendant traversal or value hydration. A selected value is resolved by the caller's scoped mapping; callers such as session storage must memoize the actual mutable selected instances. Existing put/remove/rotation algorithms retain their comparator order, tree shape and logical comparison/copy counters. Working mutations hold new values in memory and copy unchanged value references lazily; they perform no physical writes.

Explicit `stage` maps only changed final values back to the original physical binding. Unchanged subtrees retain their exact handles; unchanged rows on copied paths are rebranched from their encoded values, without decoding their session bodies. Staging does not mutate the working tree. A failed immutable prewrite leaves the original root authoritative and the actual working objects available for retry. The owning directory still publishes all selected roots atomically; this primitive neither publishes a root nor selects work.

Session mutation without an AVL put is intentionally not inferred by this generic map. The document-store owner must retain its bounded selected-session set at final staging and replace changed addresses only when the selected object remains the current map value. Removed or replaced old instances must not re-enter the final tree. This is an assembly requirement, not a completed SDK recovery claim.

`StoredDocumentIndexes.WorkingSessions` implements that selected-session rule. It reuses the original owner and view scope for reads, retains complete selected rows only at explicit staging, and ignores original instances that were removed or replaced in the current map. It does not replace the working tree or manufacture updated lineage/generation roots: those independent families still have to be staged from the exact complete final StoreState by its owner.

`PersistentAppendLog.workingCopy` similarly buffers exact new chunks in memory above an immutable stored prefix. Final retention reuses that prefix without opening its old values and preserves each new append boundary. This lets the complete original publication results be registered before their outbox/checkpoint rows are encoded; no intermediate result association is inferred by the log.

## Bounds and evidence

Physical path reads use the original bounded storage read scope. Exhaustive list operations remain exhaustive and do not become bounded total-memory operations. Unavailable path metadata still fails noncommitting even when a replacement value is already in memory; the projection is not an authority cache.

Focused gate 33136 passed 31 tests plus Javadoc on the Language854 / unchanged BEXab72 / Catalog0b immutable tuple. Three new controls cover zero eager mapping and writes, exact final mutable values, untouched subtree retention, 180 deterministic resident/cold AVL mutation comparisons, mixed projection rejection, failed staging and exact retry. The other 28 existing map/catch-up controls were unchanged. Gate82658's first run passed30 and failed one new assertion that incorrectly expected lookup through unavailable physical path metadata; the second run restores availability before checking the retained object's identity. No production change was made between those runs. Logs and both result sets are retained under `/Users/kamil/Documents/Projects/Blue/rooted-catch-up-work-evidence.9NgpkE`.

Follow-up gate75878 passed 16/16 tests in the working-session and log owners plus Javadoc on the same tuple. An actual rooted source control retains an in-place status change without a map put, stages a real successor replacement, rejects an injected mid-stage write failure, retries exactly, preserves the old prefix, and does not resurrect a removed session. Two new log controls preserve chunk order/multiplicity and actual mutable suffix rows across failed final staging. Whole StoreState/SDK assembly is still a separate qualification.
