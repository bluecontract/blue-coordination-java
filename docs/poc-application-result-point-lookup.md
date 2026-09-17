# POC: point lookup of an application's retained publication

## Problem and evidence

After a managed application, `InMemoryDocumentStore.closureReceiptForApplication`
first checked its exact work key, then searched **every** retained closure receipt
for the application's Contracts result and commit-companion identities. The search
opened full receipt/result payloads even when they belonged to unrelated operations.

The `cost35` deep replay measured 31.236 seconds in this fallback for one actual
epoch-30 application: 157 decoder calls, including 82 full result restorations.
PROCESS itself took about 0.30 seconds. This is a physical access defect, not a
reason to change processing order, history, failures or logical gas.

## Solution

Maintain a library-derived persistent index:

`(exact Contracts result identity, exact companion identity) -> publication identity`

It is updated in the same immutable `StoreState` replacement as the closure receipt.
The stored form is the new `documents/APPLICATION_RESULTS` descriptor. Each mutation
path-copies the index; opening, staging and misses do not reconstruct it from history.
Explicit resident partition conversion derives the index from its complete resident
receipts once, alongside other existing indexes.

The original behavior is retained:

- Exact `workIdentity` lookup still takes precedence.
- Eligibility uses `processResult.commits()`, not `publicationReceipt.commits()`;
  a rejected host draft can retain a successful PROCESS result.
- Two different publication matches remain an ambiguity error. Each index row
  retains at most two canonical witnesses, sufficient to prove ambiguity without
  growing an unbounded duplicate list.
- The selected receipt still passes its normal publication-membership and retained
  history checks. Its successful result and companion must match the requested pair.
- Failure before the atomic state swap publishes neither the receipt nor its index.

No host-created verification token is introduced. In the existing controlled
library-writer namespace, the index is another coherent library-produced artifact.
Raw/untrusted storage must verify the complete projection against retained receipts
before using or exporting it, including omissions and duplicate membership. That
strict path intentionally retains a full verification walk; a selected positive
witness cannot prove a secondary index complete.

## Verification and performance gate

`ClosureApplicationResultIndexTest` produces real rooted publications, opens fresh
physical-store scopes with no decoded cache and repeats an exact lookup with 5, 20
and 50 unrelated publications. The gate is **zero unrelated non-index payload reads**,
not a wall-clock threshold. A missing result must read only index nodes and must not
fall back to scanning. The suite also covers wrong binding, ambiguity, exact-work-key
precedence, strict raw omission/misbinding, repeated failure and atomic rollback.
`PublicationReceiptStorageCodecTest` checks the successful-PROCESS/rejected-draft case.

The new descriptor changes this experimental storage assembly. Existing POC physical
snapshots without it are not silently migrated by a scan on open. Fresh measurement
schemas are used; cold reopening newly retained state is covered by the tests.

## Rejected alternatives

- `findFirst()` after a scan: retains linear I/O and loses ambiguity rejection.
- Rebuild a reverse map on each runtime open/cache miss: merely moves the same work.
- Cache a previously found result without a durable derived index: cold owners still
  scan and physical cache state determines performance.
- Trust only the result hash: does not establish publication membership or the exact
  result-plus-companion association.

This change is isolated to Coordination's physical store/index implementation. It
does not alter Language, BEX, public processing policies or logical gas accounting.
