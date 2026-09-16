# P2: indexed session history — implementation and measurement card

Status: indexed P2 library gate passed (146/146 tests and all four access-budget
workloads). The merged-baseline refresh is closed. MyOS also passed 25/25
PostgreSQL store controls and five application scenarios in both physical modes
(10/10 invocations). This is focused qualification, not the full POC matrix or
the original long graph. See the final qualification sections for source pins.
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

Next: qualify the indexed session/lineage roots and explicit controlled-origin
restore path together, including strict-import and selected-corruption controls,
then prove the predeclared reduction on these same fixtures. Only after that library
gate should MyOS receive the new tuple for short PostgreSQL confirmation and,
last, the original long graph. No changes to logical history, gas or ordering are
authorized by the physical optimization.

## Candidate implementation

The private session `/3` descriptor stores independent immutable index roots for
numbered revisions, representation positions, receipts, state occurrences and
publication membership. Session copies share their prefixes. Revision/view
payloads remain separate immutable objects; selecting position metadata does not
load that position's view payload. The lineage `/2` row shares the exact numbered
metadata root rather than retaining a second flat inventory. Point epoch lookups
in attachment selection also use that root.

The additive `RootedCoordinationStorage.controlledRepository(objects)` facade is
the explicit controlled-writer boundary. Its `retainPartition` and subsequent
scope `stage` issue the indexed selections; reopening requires the host to return
unchanged, coherently published library-origin data. This does not create a
signature or grant graph/publication authority. Existing raw `open` validates
the full selected session and all supplied secondary indexes, including `/3`
frames; it never infers trust from format, hash or L1 warmth. A selected corrupt
or missing dependency is still a noncommitting storage failure.

This experimental physical format changes the whole-store lineage codec binding.
Whole-store selections containing lineage `/1` roots cannot be opened by this
candidate. Qualification uses newly exported resident fixtures; existing old POC
databases must remain on their pinned build until an explicit conversion is
provided, or be recreated from the scenario inputs. There is no implicit on-read
migration. Standalone legacy session `/1` and `/2` decoding remains.
No live production migration or larger physical record caps are introduced.

Qualification includes full resident/strict/controlled equality, disabled and warm
cache, missing/corrupt/unavailable selected history, internally inconsistent
secondary indexes, old-root stability, and prewrite failure/retry. The same
5/20/50-epoch and same-epoch SDK probe reports object traffic before and after;
`blue.poc.history.controlled` selects the new path and the independent
`blue.poc.history.requireSelective` switch asserts its mechanism budgets. Until
that gate is recorded, this section is an implementation description, not a
correctness or performance result.

## First candidate gate and correction batch

Candidate `9db69a569335e8398b684ef348f3253d0260da92` completed the grouped
library gate: 134 tests across 22 classes, 127 passed and 7 failed, no errors or
skips. Javadoc, production shape, API boundaries and dependency checks passed.
The failed run is retained under `history33/controls01`; it is not acceptance.
All four real SDK fixtures completed the full resident/cold equality oracle.

The measurements and review identified the following corrections before rerunning
the group with the zero-unrequested-read and zero-old-payload-write gates enabled:

- **SDK current selection still enumerated history.** `publicEventsAt` selected
  one epoch by filtering `engine.history`. At 50 epochs it fetched 51 revisions,
  although the indexed session already supported exact lookup. Use `revisionAt`
  through the internal engine assembly; leave explicit full-history APIs intact.
- **Publication staging bypassed owner reuse.** The receipt's admission source
  was already retained, but an unscoped view writer rewrote its 81,400-byte frame.
  Publication, pending-source and feeder codecs now prewrite through their shared
  session scope. Controlled storage reuses only acknowledged exact-object
  addresses; raw storage retains the existing strict behavior. This does not
  approve publication or skip acknowledgement of new bytes.
- **Backing replacement could change live object identity.** Two distinct,
  already captured views can have identical immutable bytes. Keep a runtime-only,
  structurally shared ordinal-to-owned-view overlay when installing storage
  roots, checking invocation and exact stored address at selection. Cold owners
  still intern canonical payloads. No extra history root or semantic identity is
  serialized; a regression covers repeated retention, copies and cold reopening.
- **Hashing lineage could enumerate indexed metadata.** Use fixed scalar fields
  for `Lineage.hashCode`, preserving record equality and the equal-values hash
  contract across indexed and resident histories. Tests forbid storage reads
  during hashing. This removes a latent traversal; it has not been established
  as the cause of the measured execution-phase index-read slope.
- **Test contracts:** strict SDK failures remain wrapped by the existing engine
  boundary, so assertions inspect their storage cause; the obsolete aggregate
  GET-count bound is replaced by exact selected-session cardinality and explicit
  exclusion of all unrelated sessions. Payload budgets are not loosened.

The fixed-body SDK fixture also requires every measured session descriptor to fit
within 64 KiB as epochs increase. This is a fixture mechanism assertion, not a
new protocol or configurable storage limit. Index-node traffic remains separately
reported. This batch changes physical access only; no MyOS wiring or timing claim
is implied by these library results.

The second grouped run (`94e1a21e990dc826bd7dae86f4102ec025eaf049`,
`history33/controls02`) completed 135 tests across 23 classes: 132 passed and
three failed the newly enabled access budget. All four complete semantic oracles
passed. Current selection now reads one revision and two views at 5/20/50 epochs;
unchanged payload writes are zero in every measured phase. The residual 5/20/50
old revision reads occur exclusively during entry submission, before execution.

### Remaining submission scan

`InMemoryDocumentStore.requireAfterRootedProviderFrontier` enumerated all prior
closure receipts to enforce previously promised Timeline completeness. Selecting
each full receipt also verified its historical revision. With 50 old epochs,
appending one new external entry therefore read 50 unrelated revision payloads.
This is not required replay or new logical gas; the question is only whether the
new timestamp exceeds that Timeline's previously closed boundary.

The correction maintains a persistent per-Timeline maximum closed timestamp,
with its exact source order and witness publication identity. It is derived from
the same terminal receipts and published in the same immutable store replacement.
Noncommitting terminal failures retain their promises; suspension does not add
one. Session removal does not erase a promise; a genuinely new empty store resets
it. Existing exact-entry replay keeps its existing idempotent path.

Controlled storage checks the selected small row and publication membership
without opening the witness payload. A raw strict owner reconstructs the complete
projection from checked receipts before its first frontier check, mutation or
export and rejects missing, extra or forged rows. Mere opening stays lazy; it
does not select every receipt's document or consume the selected-session budget.
Failed verification cannot establish a reusable validation result. The
predicate remains `timestampMicros <= closedThroughMicros`, including rejection
at equal timestamps. A rejection diagnostic can cite the maximum cutoff instead
of the first rejecting receipt in the old iteration order; this text is not
retained protocol evidence. No global cross-Timeline watermark is introduced.

The before/after audit also records a limitation: private Parent view frame
addresses differ between the earlier two baseline runs despite their production
byte parity. The original baseline run and both indexed runs match each other;
the test-only refined baseline run differs. Those private frames include
operational subscription-index counters, but the old payload bytes were not
archived, so the differing field has not yet been proven. Cross-run frame equality
is not claimed. Within every workload the complete resident-versus-restored
oracle remains mandatory; addresses and access costs are never normalized to
hide a semantic difference.

### Grouped frontier gate and SDK response scan

`history33/controls03` stopped at compilation (an unnecessary `iterator()` call
on a range iterator); no test result is credited to that attempt. After that
two-line correction, candidate `1503b5e63c70b9a9078a5bc802d9d7e3b9e11167`
completed `history33/controls04`: **137/140 tests passed across 24 classes**,
three failed the zero-unrequested-payload-read budget, no errors or skips.
The five frontier-index controls, Javadoc, API boundaries, production shape and
dependency checks passed. This is still a failed qualification gate.

Submission no longer reads the old revision prefix. The remaining 5/20/50
unrequested revision reads now occur during `successor/process`:
`SdkDrainResultMapper.changes` enumerates the entire history to select the first
and last revisions with the input's `causalEntryBlueId`. The old submission scan
had warmed those records and masked this second scan. All four complete semantic
oracles still pass and all unchanged historical payload PUT counts remain zero.

The correction records **causal-entry first/last numbered positions** alongside
the other maintained history indexes. These are endpoints, not a claim that
matching epochs form a contiguous range. This preserves the mapper's original
first-before/last-after behavior, including multiple reactions and revisions
without a direct Timeline source entry. Reusing the existing first-source-entry
index would be incorrect. Strict restore must rebuild and compare this new
derived index; controlled reads must validate the selected endpoints. Explicit
full-history enumeration remains exhaustive. A separate admission caller that
only needs epoch zero should use the existing exact revision selector.

## Qualified P2 library result — 2026-09-16

Implementation `052687128efb84563d3722b564d9d55c0b1d844b` passed
**146/146 tests across 25 classes**, with zero failures, errors or skips
(`history33/controls05`). Javadoc, production shape, both API boundaries and
exact dependency checks passed. All 48 measured phases (four real SDK workloads)
passed the zero-unrequested-historical-payload-GET and zero-unchanged-payload-PUT
budgets. The complete resident/cold history and result oracles remain unchanged.
One unchanged N5 test was repeated in a fresh JVM (`frame-check01`): its complete
archived view frame was byte-identical, including result, snapshots, routes,
published heads and subscription counters. This is 146 distinct tests, not 147.

For the 50-numbered-epoch fixture, physical byte-port reads changed as follows
(decimal MB; neither SQL query counts nor decoder counts):

| Phase | Previous strict storage | Controlled indexed storage |
| --- | ---: | ---: |
| Cold current selection | 19.477 MB; 51 revision + 54 view GETs | 0.623 MB; 1 revision + 2 view GETs |
| Submit next input | 41.901 MB | 0.180 MB |
| Process successor | 11.710 MB | 12.212 MB |
| Stage successor | 20.626 MB; 1 unchanged payload PUT | 1.021 MB; 0 unchanged payload PUTs |

All numbered 5/20/50 fixtures now select one current revision and two current
views. The largest measured session descriptor is 56,580 bytes in each workload,
including the same-epoch fixture (the previous 50-epoch descriptor was 82,040
bytes). Submission + execution + staging read 74.236 MB before versus 13.413 MB
after, about 82% fewer bytes in this fixed fixture.

This is **not** a claim that every phase is faster or independent of history
length. Execution still makes 23,681 index-node GET calls at 50 epochs, for 438
distinct index addresses. In this sample its CPU time increased from 2.274 s to
3.124 s, while submission fell from 1.945 s to 0.029 s. These are single instrumented
samples under changing machine load, not a benchmark or application latency SLA.
Some reads/writes also move between phases as earlier prefix warming disappears.
The mechanism gate proves selective payload access and no unchanged payload
rewrites; it does not prove selective metadata validation or O(delta) total work.

Two separately identified follow-ups remain unimplemented: repeated projected
lineage/reverse-index validation and map-operation-local cache lifetimes; and
the full receipt fallback in `closureReceiptForApplication` for retained
historical/idempotent applications. The latter has not been shown to run in this
simple successor fixture. Whole-result segmentation and catch-up windows remain
P3/P4, not part of this qualification.

`history33/export01-audit.json` binds the exported Coordination artifact to all
959 tested runtime entries (the version manifest is checked separately). It
reuses the unchanged Language/BEX/Catalog artifacts; it does not rebuild or
republish them. The host candidate is `history33/export01-myos-candidate.json`.
The remaining PostgreSQL/app gates must report their own outcomes; this library
pass is not full POC acceptance or proof that the original long graph finishes.

## MyOS confirmation

MyOS `81b5066e6dfbd841d294e49cbadf785426251d0c`, using the exact exported
Coordination artifact above, passed:

- **25/25 PostgreSQL store tests**, including actual indexed `/3` SQL records,
  producer close/cold reopening, exact result/history/gas, failed publication,
  rollback, stale fences, reset and retry. Native duration: 56.32 seconds.
- **Five application scenarios in each physical mode (10/10 invocations):** two
  cyclic-reference/restart scenarios, plus ring/chord, duplicate-occurrence cycle
  and forwarding/detach/reconnect small graphs. Resident uses H2; external runtime
  uses real PostgreSQL with fresh per-case schemas. Complete restart comparisons,
  exact dependency/JAR bindings, original deadlines and 16 GiB profiles remain.

Evidence: `history33/host02-store/reaudit.json` and
`history33/combined-smoke-summary.json`. The native store run was green; its first
auxiliary audit confused XML `classname` with `name`. The corrected read-only
audit preserves the original failed audit and verifies the same closed run;
no test was rerun or assertion weakened. An earlier `host01` attempt stopped
before tests because init scripts are forbidden. The native PostgreSQL task now
honors the existing explicit heap property; the application JAR stayed unchanged.

All selected PostgreSQL scenarios, including the three small graphs, passed.
The full maintained POC matrix and original long catch-up graph were **not** run
in this confirmation. Test-task durations are not per-operation latency results.
The measured next candidate is [verified index-node reuse](poc-verified-index-node-reuse-plan.md).
