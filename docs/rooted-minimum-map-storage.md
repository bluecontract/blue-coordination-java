# Stored minimum-order tree for current catch-up work

## Problem and solution

The current due-work index is a `PersistentMinimumMap`, not the general ordered map. Rebuilding its rows into another tree can change the physical shape and existing logical minimum/successor and mutation counters. Keeping it resident would force the complete due catalog into memory before a selected root could progress.

The component therefore keeps the current minimum-map algorithms and adapts their node access to the already-qualified `PersistentMapStorage` and shared node interface. Resident nodes still use the same AVL shape, rotations, comparison and copied-node accounting. Stored nodes are exact content-addressed records, bound to the caller's ordering and row codecs. `minimum`, strict `higherThan`, point reads and mutations have bounded per-operation node caches, cleared after success or failure. Physical reads and retained bytes do not contribute to logical work/gas.

Opening reads a selected root only. Resident-to-stored conversion is an explicit shape-preserving partition operation, not an automatic cold-runtime scan. A stored descriptor assumes the same complete byte store. The caller owns root pinning, absence/predicate fencing and publication; this primitive does not claim a global due queue is an independent publication partition. Explicit structural validation still traverses the whole selected tree and is not advertised as a bounded-total-memory startup operation.

Missing, corrupt, wrong-binding or oversized selected records fail noncommitting. A failed write may leave unreachable immutable bytes, but it cannot change the original root or return an accepted replacement. No queue eligibility, scheduler ordering, deadline, transition budget or public API changes are introduced.

## Focused qualification

Gate 61657 passed **14/14 tests plus Javadoc** in nine seconds: four new controls compare exact resident/stored minimum, successor, point and randomized mutation counters, fresh reopening and old forks; distinguish unavailable selected subtrees from an unrelated available branch; and cover bad acknowledgments, retry, bounds and bindings. The existing one minimum-map and nine catch-up-plan controls remain unchanged.

The immutable tuple is Language `f241be7ee031cace33ba37ae93e6801d9895d2af`, unchanged BEX `ab72af14ee54c6123e6d80349956af373a887680`, and Catalog `0b68744ba6456ef312d1ba87a27096bd2ac5341f`. Before/after source SHA256: `e566b8de845e22603933e865e435b0544db8eeab9eb92d8b3405725aedbbe8c6`. Only this qualification note changed afterward. Exact source/command/XML are retained outside the repository in `rooted-minimum-map-evidence.WbDDGL`. This does not qualify full catch-up work restoration or runtime publication.
