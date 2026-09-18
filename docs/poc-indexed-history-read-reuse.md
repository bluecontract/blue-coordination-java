# POC indexed history reads and cache-outcome diagnostics

## Problem and example

Selecting one source epoch copied and filtered representation transitions from
every epoch. Captured-root verification also repeatedly built sets containing all
earlier publication identities. A source with 50 representation positions in each
of five epochs needs the complete selected epoch, not four unrelated ranges.

Separately, a decoder call does not establish a cold decode. Owner-local and
process caches can satisfy it; a miss can mean first use, eviction, capacity
rejection or incompatible current-scope view identities. Optimizing the wrong
cause adds complexity without removing the measured cost.

## Change register

| Change | Solution and reason | Classification |
| --- | --- | --- |
| R29-C1: unrelated history traversal | Binary bounds select an immutable copy of all rows in the requested epoch. Derived session-local ordinal indexes answer exact retained-view and first-invocation membership; binary search preserves the existing strictly-before logical boundary rule. | Internal algorithm/data-structure optimization; unchanged processing semantics. |
| R29-C2: ambiguous cache totals | Package-private no-op diagnostic seams expose existing outcome branches to an external, class-hash-pinned measurement agent. Existing digests are reused; there is no callback, public API, extra payload hashing or library-side telemetry retention. | Diagnostic support only. |

No storage format, SDK/API, gas, epoch, failure, publication, source target or
BlueId rule changes. Language, BEX, Catalog and MyOS application code are unchanged.

## Invariants

- Every selected same-epoch transition remains ordered and authenticated, including
  positions beyond a frozen target where the existing verifier checks the suffix.
- Current selected publication membership, original result/input identity,
  predecessor, next-revision and terminal-head checks remain.
- Position membership uses the actual retained view object, not an equal endpoint
  or an invocation string supplied without retained evidence.
- Derived indexes belong to one session. Atomic staging copies them independently;
  restoration rebuilds them from the validated retained positions. Discarded
  publication cannot advance another session's index.
- Returned epoch ranges remain immutable across later appends. Repeated endpoint
  BlueIds are not deduplicated.
- Staged evidence must still be this transaction's exact owned publication.
- The old whole-prefix accessor remains for callers that require the whole set.

The new indexes cost O(retained views) session-local space. This is not metadata-only
runtime restoration and does not avoid eagerly opening every selected session.

## Cache interpretation

Process canonical-encoding hits are reported separately from decoded-value hits.
Owner publication LOAD delegates to a decoder that may itself hit the process
cache. Owner weights are encoded bytes; process weights are decoded estimates.
Do not sum those as measured heap. First-observed bytes do not imply a newly
produced logical publication.

The external collector retains bounded scalar metadata (4,096 frame keys) and
reports overflow. Its lifetime outcome counters continue after the 180-second
detailed-trace window. Normal uninstrumented execution retains no diagnostics.

A cached publication whose view identities conflict with the current scope still
takes the ordinary verified fallback. No identity check has been removed and no
cross-scope rebinder has been added: that would require measured justification,
complete wrapper rebinding and canonical/proof-certificate lifetime controls.

## Verification and evidence

One source-stable grouped run passed **108/108 invocations across 15 complete
owners**, including all seven new structural/range controls, restored sessions,
cache/corruption controls, frozen representation boundaries, cycles, failed
atomic publication and retry. Dependency, Javadoc, production-shape and public
API/SDK guards also passed. Production inventory is 315 sources / 85,955 physical
lines / 94 public API types; the source cap records the +160 lines, not new
protocol capacity.

The diagnostic collector passed 93/93 synthetic controls, including on/off
equivalence, bounded overflow, concurrency and lifetime collection across detail
window reset/end.

The same-input PostgreSQL before/after comparison and native short paired E2E
are tracked in the batch evidence. They are not implied by these library passes.
No old full acceptance matrix qualifies this new tuple; the original long graph
remains unqualified until its complete assertions and restart finish.

Evidence under the shared Blue project directory:
`rooted-external-resumption-evidence.c0VVLS/processing-measurement13/readreuse29/`.
See `coordination-controls01/receipt.json`, source manifests and JUnit XMLs,
`collector-build/manifest.json`, `PLAN.md` and subsequent measurement reports.
Diagnostic bundles deliberately preserve the previous release metadata solely
for frozen retained-input comparison. They are never release/deployment artifacts.
