# Current catch-up plan and barrier storage

## Problem and example

An occurrence-specific catch-up plan may wait at exactly source epoch 2 while a sibling plan is blocked or already complete. A cold view must retain that exact status, cursor, cause, and barrier membership, rather than recreate a new plan from the current source head. An old OPEN barrier cannot be mixed with a newly WAITING member plan merely because both row identities are individually valid.

## Solution and boundary

`StoredCatchUpPlanIndexes` stores current verified plan and barrier rows. Every field is retained, including admitted state, target path and activation generation, required frontier, exact waiting evidence, and mixed integer/text logical-order components. Existing public constructors verify the asserted immutable definition and snapshot identities during decode. Existing `CatchUpPlanStore.canonicalBarrier` supplies the same member/status rule used by ordinary publication; no readiness or scheduling rule changes.

The six existing plan indexes retain their AVL shapes and operation counters. Nested membership buckets retain their own map roots and counters. Each family root and the barrier root is independently supplied by the future caller-pinned directory. Exact selected reads validate primary/reverse memberships. A selected complete source bucket additionally validates its active-plan count; a selected barrier must match every current member and canonical waiting/blocked status. Missing or corrupt physical records fail noncommitting and are never semantic absence. Read validation does not stage objects.

This is a component, not a complete `CatchUpPlanStore` installer. Work/application rows, source receipts, directory snapshot/read predicates, atomic publication, and outer aggregate counters are separate obligations. The original in-memory mutation algorithms and counters remain unchanged. No PROCESS, provider lookup, replay, or new public SDK API is used here.

Resident partition conversion is explicit and shape-preserving, not a startup whole-catalog transform. Current selected membership buckets may still contain all matching plans: this does not claim bounded fanout or bounded whole-history memory. Global family roots also do not imply independent publication; production owner/bucket partitions and predicate revisions remain necessary. Pinned related roots are authoritative inputs: local selected checks are not an exhaustive corruption audit of unrelated absent rows.

## Why not reconstruct plans or reuse a global snapshot

Reconstructing from live heads discards frozen admission frontiers and exact failure dispositions. A global serialized store would introduce an unrelated coordination bottleneck and eager realm hydration. Flat lists would also change the existing shape-dependent logical operation counters. The component therefore retains the current rows and maps exactly, while leaving publication ownership outside the codec.

## Qualification

Final gate 30530 passed **16/16 tests plus Javadoc** in nine seconds on the exact immutable Language `f241be7ee031cace33ba37ae93e6801d9895d2af`, unchanged BEX `ab72af14ee54c6123e6d80349956af373a887680`, and Catalog `0b68744ba6456ef312d1ba87a27096bd2ac5341f` tuple. Seven new controls cover actual source/parent admission rows reopened after producer closure, all row statuses, logical order, cold multiple-bucket reads and mutations, exact old-root retention, mixed or missing memberships, wrong active counts, stale barrier progress, unavailable/corrupt bytes, and failed staging. A blocked member cannot hide a later member with mismatched consumer/cause: the stored boundary checks all associations before the unchanged canonical status fold may short-circuit. The nine-test existing `CatchUpPlanStoreTest` owner remains unchanged.

The source hash before and after the gate was `2ac8cefe20a6137898f764385be9a5df7152826d3b4dd75ebd30a98461fcdfce`; only this qualification note changed afterward. Exact source, XML and command are archived outside the tree in `rooted-catch-up-index-evidence.XNoelz`. First feedback rejected a new fixture's order integer outside the existing safe range before any storage call. That fixture was corrected to the maximum supported integer; the original red XML is retained. No protocol bound or ordinary production guard was changed.
