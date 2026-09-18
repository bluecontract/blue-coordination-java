# Five lazy SDK record maps

## Problem and scope

The SDK has five mutable insertion-ordered maps: registered Timeline handles,
submission intents, retained entry results, exact core-entry references, and
source-history processing results. Whole metadata encoding enumerates all five;
restoring their summaries would lose owner identity or bypass the authenticated
journal. `SdkRuntimePointMaps` instead supplies the existing `StoredMaps` seam
with five point-backed ordinary maps. The runtime/engine constructor, actual
engine state restoration, and publishing their associated roots remain separate
owner responsibilities.

The only cross-package addition is the internal `InsertionOrderedStorage`
facade over the existing authenticated insertion-map primitive. Its closed
codec, scalar physical bounds, selected snapshots and owned scopes confer no
semantic authority and are not a host publication API.

## Exact records and selected reads

Each family binds the full actual SDK configuration and existing closed codec
version. Insertion order remains the original `LinkedHashMap` order, including
replacement versus remove/reinsert behavior. The source-result comparator uses
the complete canonical `SourceHistoryPrerequisite` encoding, not just its
claimed `selectionIdentity`; cutoff, requester, demand, source, phase and all
physical coordinates remain distinct keys.

An index value retains the exact selected key and immutable `SdkPointStorage`
descriptor. Initial resident conversion explicitly enumerates the original
maps. Cold open does not. Point-value decoding delegates to the existing scope:
actual configuration and runtime owner, registered Timeline/actor kind, verified
journal coordinates, full core-entry bytes, intent's mandatory core membership,
and complete result handle evidence are still checked. Core-entry recovery
returns the engine's verified `TimelineEntry`, never a new authority constructed
from a DTO. An advanced retained result need not be an SDK submission, so it
does not acquire an invented mandatory core-map row.

The SDK append owner separately corrected its two private insertion sequences:
after the actual journal append, it retains the exact core entry before the
dependent intent, then returns the same original entry handle. This permits
strict point-backed intent validation without a temporary missing-row exception
or deferred/relaxed validation. Public entry identity, append ordering,
processing input, and gas policy are unchanged. Cross-family publication is
still the outer selected owner's responsibility, not this local ordering.

The already validated record retains its exact descriptor for pure canonical
re-encoding. Read/re-encode never prewrites point objects. Warm values are
identity-pinned by the map, and even warm reads check that the actual SDK owner
remains open. New puts compare original handle owners against the verified
receiver handles; serializing a foreign owner cannot launder it into the scope.

## Bounds and ownership

The insertion-map byte charge covers its descriptor-bearing encoded records,
not the full SDK value behind each descriptor. Independently, each point row is
bounded by `maximumRowBytes` and each of five maps pins no more than
`pinnedEntries`. Thus the conservative full encoded-row allowance for these pins
is `5 × pinnedEntries × maximumRowBytes`, in addition to the separately bounded
point-scope memo and index-node caches. None of these is a measured Java heap
quota. No eviction may change an actively returned SDK record's identity.

Both indexes and insertion counter for every family are selected by the caller;
the final runtime owner must publish all associated roots under its actual
cross-component read predicates. No global-root CAS or full runtime scan is
introduced. The five map scopes and shared point scope close together, without
closing an independently owned engine. Missing selected bytes, missing required
core membership, wrong family/configuration, or corrupt key/body bindings fail
noncommitting rather than falling back to resident data or replay.

Installed map views implement `AutoCloseable` and share an idempotent group
close. The SDK owner separately closes such owned maps after marking itself
closed; its legacy resident maps still use clear. This avoids treating shutdown
as a semantic map mutation or bypassing closed-owner read guards. Cleanup
attempts all owned maps even if another close fails. Both scope-first and
SDK-first shutdown are covered.

## Qualification status

The final focused gate passed **22 tests**, zero failures/errors/skips, plus
Javadoc, in 18 seconds: six new actual SDK map controls, seven existing point-row
tests, seven insertion-map tests and two constructor tests. All four owners ran
unfiltered. The actual installed-map append and both shutdown orders passed.
Source base is `bdd2e2be745b4e765c7de821098eb3ba4f84660f` plus these four new
component/test/documentation files; that base contains the source owner's two
append reorderings and AutoCloseable cleanup.

Preserved earlier diagnostics are separate: the initial 18-test partial gate
passed; the first complete 21-test gate had one teardown-only failure after its
append assertions; one following invocation stopped at test compilation because
the deliberate close-order test used explicit closes inside try-with-resources
under `-Werror`. The final test uses explicit try/finally, with the same close
assertions and no lint suppression or relaxed guards. These runs are not added
to the final count. This is not full cold-engine, cold SDK application, or
published acceptance evidence.

Dependency tuple: Language `8542285144a8969f157d73e73292398885105c46`, BEX
`ab72af14ee54c6123e6d80349956af373a887680`, Catalog
`0b68744ba6456ef312d1ba87a27096bd2ac5341f`, all matching immutable development
repositories, Java 17/offline/one Gradle worker.
