# POC: close measured immutable-cache propagation gaps

Status: implemented on the isolated POC branch; grouped compilation, tests,
artifact rebinding and MyOS correctness qualification are pending. No baseline
branch or another engineer's PR is changed.

## Problem, example and evidence

Two retained historical causes differ in their target occurrence or outer
context but include exactly the same complete source result R. Caching the
whole cause cannot reuse R across those different wrappers. Ring09's bounded
32-object, 69.5 MB census found five distinct cause frames and a shared
727,299-byte complete result. The complete-result helper introduced previously
covered direct Coordination readers, but Language's execution-evidence codec
still constructed a private raw result codec for nested R.

In the same completed recording, 155 writer execution samples followed that
nested result path: ManagedWork cause 66, publication surface 14, terminal 12,
and 63 with truncated enclosing stacks. These counts identify a mechanism; they
do not prove how many identical frames were processed or predict elapsed gain.

A second measured seam is whole-object content access. Of 117 exact-value
decode samples, 106 came directly from `WholeObjectStorage.decodeEntry`
(62 canonical, 44 provider). Document-session `SessionRecordCodec.exact` already
used L1 and appeared directly in only three such samples. The host's cache was
not passed to the whole-object reader. Repeated nested exact-node bytes in a
physical census alone do not justify caching mutable Nodes.

## One shared, bounded integration

`StoredClosureResultCodec` now configures the final Language codec with its
optional opaque reuse port. Language alone creates a `VerifiedFrame` after the
unchanged complete decoder and canonical check succeed. It checks returned
frame bytes and the exact byte/depth profile on every reuse, and requires exact
decoded-result identity for encoding reuse. Processor-produced outputs do not
acquire a certificate merely by being encoded.

The Coordination bridge stores that opaque frame in the existing host-weighted
`RootedStorageCache`. Its existing reverse identity index points from the exact
result object to that same entry; there is no second retaining map or unbounded
token registry. The token's own frame bytes are additionally charged by the
existing conservative estimator. Eviction/clear removes the entry and identity
association together; different profiles miss. Concurrent equal inner frames
use the existing single-flight loader even when their outer causes differ.

The previous outer `CanonicalStorageCodec` around complete results is removed.
Keeping it around the newly configured Language decoder could make a cache
miss re-enter the same in-flight key and wait on itself. The supplied Language
loader performs ordinary decoding directly; it is not another cache lookup.

Configured result codecs flow through all eight execution-evidence construction
sites: ManagedWork, CohortInvocation, CoreReceipt, PublicationReceipt,
RootedLocalStep, SourceDiscovery, StoredManagedEpochIndexes and
StoredFeederProgress, plus their constructor chains through managed application,
work indexes, pending storage and rooted-engine storage. CoreReceipt's
direct results use the exact same configured codec instance. Legacy constructors
remain uncached. Record/index profiles stay separate even within one host L1.

`WholeObjectStorage` receives that same host cache and shares only canonical and
provider `ExactValue` lanes with the existing `SessionRecordCodec` family. It
does not cache the outer entry, mutable Node lane, cyclic proof selection or
provider membership decision. Fresh content loads may reuse verified immutable
values without inserting those reads into an owner's mutation maps.

## Unchanged correctness boundaries

- Every physical owner still reads/authenticates the selected outer object and
  checks pinned index keys, scope budgets and publication membership.
- Original-result rows require a committing result after every shared decode,
  even if a valid rollback frame was populated by a receipt reader.
- Work coordinates and cause subtype, rooted input/witness associations,
  selected demands, attempt identities and member registration remain validated
  in their existing owners. Only terminal result values are shared.
- Whole-object reads still validate current cyclic wire-body/proof relationships.
  A warm exact value cannot hide missing or changed backing proof data.
- Returned Node/event/proof/byte copies remain detached. Wire formats, logical
  gas, epochs, ordering, failure policy and PROCESS semantics do not change.

## Verification and rejected alternatives

`NestedClosureResultFrameReuseTest` has six controls: different causes with one
actual source result; real work-envelope coordinate checks; corruption and
strict profiles; concurrent inner-result loading; token-byte budget/clear; and
an actual SDK-selected pending representation work item reused through its work
envelope and the direct result reader. The first two constructed-occurrence
fixtures are storage tests, not claims that two independent consumers were
processed end to end. The SDK fixture retains its existing event/count oracle.

`WholeObjectExactValueReuseTest` has four controls covering cross-lane/session
reuse, fresh readers and writes, missing/corrupt/wrong-key outer selection,
mutable returned cyclic data and current proof failures, profiles and disabled
retention. Existing nine `ClosureResultFrameReuseTest` controls remain. Their
encode counter now counts public raw-encoder fallback, not Language's internal
canonical verification or the removed extra Coordination encode.

Run all prior 19 owners plus these two owners and the complete cohort, source
discovery, pending-storage, feeder and historical-representation owners together
(expected 182 tests across 26 owners, subject to discovery), with
API/SDK/dependency/Javadoc/shape checks once, then the exact rebound MyOS tuple.
Prior source results do not qualify this follow-up.

Rejected: whole-cause/input caching as the primary fix (the sampled cause bytes
were distinct); mutable Node interning; final-state/total-gas-only results;
caller-created trust flags; broad validation bypasses; a separate unbounded
certificate map; and changing protocol behavior or readiness timeouts.
