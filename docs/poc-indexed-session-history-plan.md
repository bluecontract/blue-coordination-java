# P2: indexed session history — implementation and measurement card

Status: executable baseline probe and membership-only prerequisite qualified.
The merged-baseline refresh is closed; indexed P2 storage is not yet implemented
or wired into MyOS.
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

## Measured starting point — 2026-09-16

The final probe uses the same two-document graph with scalar parent state, no L1,
detached stored bytes and fresh SDK owners. It passed the complete resident/cold
oracles for all four workloads, including exact full stored-view identity (not
only final bodies or result hashes), same-epoch positions, successor publication,
old-root stability and cold reopening followed by a measured current-tip read.

| Numbered parent epochs | Current-tip revision GETs | Current-tip view GETs | Current-tip total GET bytes | Largest session descriptor |
| ---: | ---: | ---: | ---: | ---: |
| 5 | 6 | 9 | 2,714,614 | 53,194 bytes |
| 20 | 21 | 24 | 8,293,093 | 55,078 bytes |
| 50 | 51 | 54 | 19,476,755 | 82,040 bytes |

The separate five-same-epoch-transition case remains at parent epoch zero: one
numbered revision and seven view GETs, 1,721,724 bytes for current-tip selection.
It has six retained parent view positions including its starting view; five
transitions are not five numbered epochs.

The 50-epoch successor's process phase performs 23,176 object-port GETs after
submission has already loaded its historical state. Staging then reads another
20,625,550 bytes, including 52 revision and 55 view GETs, and issues one PUT for
an unchanged historical payload. Cold selection after reopening repeats the
growing-history cost. Thus session recovery, index access and staging all belong
in the proposed change; improving history lookup alone cannot close this gate.

These are library byte-port calls, **not SQL queries or network traffic**. The
test stores detached arrays behind the actual immutable-object interface; it
uses real SDK/Contracts execution but no database. Phase times include accounting
overhead, are single samples and are not application-latency predictions. The
complete raw phase inventory is preserved. Decoder counts remain unavailable;
payload GETs must not be relabelled decodes.

"Unrequested" means outside the predeclared logical selected payload/boundary
set. Current strict restoration deliberately reads the prefix to establish its
invariants; those reads are not evidence of a correctness bug. The new indexed,
library-origin path must preserve those invariants without repeating the work.
The baseline's zero-read/rewrite budget is explicitly **not passing**.

The four membership-only call sites were changed without removing their exact
owner/head/frontier checks. A controlled test uses the same retained inputs at
5/20/50 positions: constructing the formerly discarded prefix visits 5/20/50
positions; the new membership path visits zero. A detached value-identical view
still fails. This qualifies that narrow traversal reduction only; the table
above remains the starting point for indexed P2, not a claimed P2 improvement.

## Qualification and handoff

- Implementation/test commit `3e44f34bd3b9c2f8ea946715e3f73f221c516129`:
  **30/30 tests across nine owners**, including the original four measurement
  workloads; Javadoc, API/SDK boundary, production-shape and exact dependency
  controls passed. The normal filtered topology-aggregation task did not run.
- Test-only refinement `384cef635ec8f5a434149244faaf94082ebec50c`: strengthened
  complete-view equality and measured selected reads after reopen. Re-ran only
  those **4/4 workloads**, with 12 phases each. Production/build byte content is
  unchanged from the 30-test gate. These are **30 distinct tests, not 34**.
- Both native runs had zero JUnit failures, errors or skips, and unchanged clean
  pinned source. Test heap was 16 GiB, with no processor-count cap. Neither
  Language nor BEX nor Catalog was changed; the baseline31 immutable dependencies
  were reused. MyOS still uses its qualified baseline31 artifact tuple; this
  prerequisite was not exported to the app or presented as new E2E acceptance.

Evidence root under `/Users/kamil/Documents/Projects/Blue`:
`rooted-external-resumption-evidence.c0VVLS/processing-measurement13/history32/`.
`controls01/` preserves the first native run. `probe02/measurement-audit.json`
and `probe02/measurements.json` are the final cost inventory and raw accesses;
their receipt binds the tested source, exact dependencies, logs and archived XML.
The run commands and local 16 GiB profile are retained beside them. Later
documentation-only commits do not relabel these tested source pins.

Next: implement the indexed session/lineage roots and explicit controlled-origin
restore path together, add strict-import and selected-corruption controls, then
prove the predeclared reduction on these same fixtures. Only after that library
gate should MyOS receive the new tuple for short PostgreSQL confirmation and,
last, the original long graph. No changes to logical history, gas or ordering are
authorized by the physical optimization.
