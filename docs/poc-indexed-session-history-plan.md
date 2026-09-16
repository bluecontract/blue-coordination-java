# P2: indexed session history — implementation and measurement card

Status: implementation plan and executable baseline probe in preparation.
The merged-baseline refresh is closed; P2 is not yet qualified or wired into MyOS.
Work stays on the existing owned `codex/poc-baseline-refresh-20260916` branches.
No new source from open PRs, protocol changes, larger record limits or Redis.

## Outcome

For the same small graph with 5, 20 and 50 numbered epochs, cold-open one exact
historical position and append one successor. Read only the selected payloads and
their predeclared required dependencies; write only the new payloads and changed
index paths. A separate workload includes multiple representations within one
epoch. Cache warmth must not be necessary for selective access.

The reference is the same resident SDK calculation: exact states/BlueIds,
numbered and same-epoch positions, events/order, complete results and gas. The
measured operation must not include the subsequent full-history equality oracle.

## Why changing only the session codec is insufficient

| Current path | Work that P2 must remove or isolate |
| --- | --- |
| `DocumentSessionStorage.OpenScope.open` → `DocumentSession.restoreStored` | Reads all revision/view references, restores all payloads and checks complete prefix/index equality. |
| `DocumentSession.storedState` / `copyForAtomicPublication` | Copies growing history and derived indexes. |
| `StoredDocumentIndexes.Owner.find` | Reconstructs `Lineage.from(session)` and checks every retained-state reverse membership. |
| `ManagedLineageIndex.withAdvancedRevision` / `Lineage` | Reads the delta but copies and serializes the complete retained-state list. |
| Four attachment/peer call sites | Build a full publication-prefix set but discard it; only exact retained-view membership is needed. |

The last row can use the existing `requireRetainedRootedView` check now. That is
a preparatory removal of unnecessary traversal, **not** the P2 selective-storage
implementation or evidence of application speedup.

## Smallest coherent storage/runtime change

1. Replace growing session inventories with immutable, indexed history lanes.
   Numbered epoch and representation/publication ordinal remain distinct. Keep
   exact before/after positions and clamped logical boundaries; repeated BlueIds
   and repeated invocation identities do not collapse distinct positions.
2. Use a bounded session descriptor containing current/READY tips and roots of
   those indexes. Histories, terminal receipts, state-occurrence indexes and
   publication membership must not reappear as flat lists in that descriptor.
   Reuse maintained persistent-map primitives; map values reference separately
   bounded payloads rather than embedding a whole result in an index node.
3. Give resident and stored sessions the same point/range/append semantics with
   structurally shared immutable prefixes. Atomic session copies retain index
   roots without opening the prefix. Explicit full-history enumeration remains
   an intentionally exhaustive operation.
4. Make lineage rows refer to the same exact retained-history basis and update
   secondary indexes from the new suffix. Opening a current head checks selected
   membership rather than reconstructing the whole lineage. Preserve the
   existing `lastAnchoredNonReplayableEpoch` handling: same-epoch representation
   changes mean numbered `before` need not equal the preceding numbered `after`.
5. Keep publication fencing, coherent session/lineage roots and error handling.
   Immutable prewrites may leave unreachable objects; failed reads/prewrites
   must never publish a partially advanced descriptor. Missing selected history
   is an error, not permission to skip it.

Whole result/view segmentation and bounded catch-up windows are subsequent P3/P4
work, not implicitly solved by P2. If an operation really requires an entire SCC
proof, count it as required work; do not weaken that proof to satisfy a budget.

## Retaining verification across restart

A content hash or new format tag does not prove that all history/index invariants
were established. The current raw object port and caller-constructed `Selection`
cannot mint that proof. Do not simply delete `restoreStored` validation.

The planned fast path is an explicit **library-managed, controlled storage
namespace**. This is a new additive restore contract, not an automatic behavior
change for existing raw descriptors or a format-version switch. Only library
publication staging, or a completed strict import,
produces its versioned history descriptor. The host persists and returns that
descriptor unchanged with the coherently published roots; it does not create a
`verified=true` claim. Library origin is guaranteed by this write boundary, not
by the SHA-256 address. Generic/untrusted recovery retains strict validation,
including rejection of correctly addressed but internally inconsistent indexes.

The descriptor binds document/authored identity, all associated index roots,
current/READY/rooted tips and invariant/codec versions. Reopen checks selected
paths and cross-links; append checks the certified previous boundary plus the
new delta. Cache entries are optional accelerators, never publication authority.
Untrusted content admitted from an external provider still follows normal exact
verification. A malicious writer with access to the controlled namespace breaks
its prerequisite; a digest alone cannot detect every coherently forged graph.

The incremental invariant set must cover every existing full-restore check:
contiguous numbered/application order, initialization, receipt uniqueness and
coverage, state first/ambiguous indexes, representation order/ownership, exact
head membership, rooted-history owner, view publication membership, clamped
boundaries and final-current relation. Counts/endpoints alone are insufficient.
The public capability's final shape requires review with the private indexed
format; no trust-bypass flag is introduced by the preparatory patch.

## Executable gate and advancement

`RootedSessionHistoryAccessIntegrationTest` uses real SDK admission, timelines,
processing, `retainPartition/open`, fresh owners and `stage`; not fabricated
revision lists. Its fixed-body parent omits the growing diagnostic log so history
length is varied independently of document body size. The standard P1 unmatched
event fixture cannot stand in for numbered-epoch growth.

Separate phases: cold SDK open, current/point selection, successor execution,
stage, cold reopen. Capture object GET/PUT calls and bytes, distinct historical
payload addresses, repeated old payload PUTs, session-descriptor bytes, CPU/wall
time and cache conditions. Physical GETs are **not** decoder counts; a decoder
metric must come from actual decoder instrumentation. Report unavailable metrics
as unavailable. Fixture construction and correctness verification are outside
the counters/timers.

Before code replacement, retain the baseline's correct semantic output and
mechanism costs. After replacement, the same workload must satisfy:

- zero unrequested historical payload reads/decodes;
- zero writes of unchanged historical payloads;
- bounded descriptor size, with index-node work reported separately;
- exact reference equality of the observable result, including restart.

Exercise cold/disabled cache, warm reuse, eviction, missing/corrupt selected
records, incorrect ownership/boundaries and failed publication. Unselected
payloads must not be opened merely to prove that they were not needed. First
close the library before/after result; then run the corresponding short MyOS
PostgreSQL confirmation. Do not use another long application timeout as the
first test of this hypothesis.
