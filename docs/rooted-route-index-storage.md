# Retained routing and active-source indexes

## Problem and change

A cold runtime previously had to rebuild operation routes and the configured
public-Root Timeline union from document surfaces. That replay is physical work,
not a new Contracts invocation. `StoredRouteIndexes` now retains the route-row
and document-key AVL roots with the original route generation.
`StoredActiveSourceIndexes` separately retains configured public roots, exact
per-root surfaces, reverse managed-document memberships, and Timeline reference
counts. Opening these internal components reads their selected root metadata;
it does not resolve documents, traverse occurrence graphs, or run processing.

The first conversion of a resident index visits its existing map nodes. Later
opens preserve the exact stored tree shape. Selected reads decode only visited
map paths and selected buckets; an individual bucket must fit the explicitly
configured physical byte bounds. These are operational bounds, not new semantic
limits. The codecs retain full Java text, arbitrary-precision external order
keys, route ordering, active intervals, and source actor/Timeline pairs.

Resident route compilation, delivery selection, generation changes, comparison
counters, and source-surface resolution remain the existing algorithms. Active
source refresh stages changes to all four immutable roots before exposing any
replacement. A failed physical write can leave unreachable immutable objects,
but cannot publish a partially updated union. Existing `addPublicRoots` and
source refresh support dynamic admission and removal of contributions; there is
no new public-root deregistration policy.

## Trust and integration boundary

These are package-private storage components, not a complete cold SDK factory.
The caller must pin the exact independently owned root descriptors and route
generation under the appropriate publication read predicates. A descriptor is
not authorization for a global realm CAS, and opening one family does not prove
cross-family publication consistency. Restored selected-head/session resolvers
belong to the new runtime owner. Existing route targeting and publication
generation checks still run. No restore calls register, append, or PROCESS.

Missing, malformed, wrong-family, over-bound, or corrupted selected storage
fails noncommitting. Opening is deliberately not a whole-index integrity scan.
The immutable-object store and descriptor owner must retain all referenced
objects; missing data never denotes an empty index.

## Qualification

Focused qualification passed: 26 tests, zero failures/errors/skips, plus Javadoc
in 16 seconds (Java 17, offline, one Gradle worker). This comprises 8 new stored
tests, the existing 13-test route owner, and the existing 5-test active-source
owner; these are not additive to another full-suite result. The source base is
Coordination `7f7fde1626a599d4cc359b185f4f879b67cf5f94`, with this isolated
storage patch. Dependencies are Language
`8542285144a8969f157d73e73292398885105c46`, unchanged BEX
`ab72af14ee54c6123e6d80349956af373a887680`, and unchanged Catalog
`0b68744ba6456ef312d1ba87a27096bd2ac5341f`, using their matching immutable
development repositories.

The new tests cover byte-only fresh-JVM
reopen of both families, resident/cold mutation and counter parity, exact route
generation and stale prepared publication, point-read locality, dynamic shared
memberships and reference counts, old immutable union snapshots, and physical
failure without partial root installation. They supplement the existing
`OperationRouteIndexTest` and `ContractsActiveSourceTimelineIndexTest` owners;
they do not establish full engine/store/SDK recovery.
