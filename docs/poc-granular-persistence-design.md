# Granular persistence: bounded records and selective access

Status: design requirement and implementation follow-up, not an implemented or
qualified storage format. Increasing the existing 32/40 MiB bounds is not the
solution. Complete-result cache reuse is a separate, narrower improvement.
Every implementation slice first needs the library-level before/after evidence
defined in [optimization acceptance](poc-library-optimization-acceptance.md);
MyOS confirmation must not be its first performance measurement.

## What still grows today

The outer stored maps already support lazy point access, whole-object entries are
separately addressed, and numbered revisions have separate records. However:

- `DocumentSessionStorage.encodeSession` still writes all revision addresses,
  terminal/transition identities, representation positions and history indexes,
  plus current/READY layouts. Session size grows with retained history/topology.
- `OpenScope.open` resolves the selected session's entire revision/view inventory.
  `DocumentSession.restoreStored` reconstructs and validates the complete history.
  Separate revision records therefore do **not** yet provide selective history
  reads. Retention also revisits old dependencies, even when their PUT is skipped.
- `encodeView` embeds a complete result and complete original/retained snapshots.
  Those Language codecs have whole-envelope APIs; one large view remains one
  large physical value. Layouts also contain complete semantic/processing forms.
- Execution also has eager structures: `ClosureExecutionSession` populates a
  body map for every selected managed document and can clone that map's bodies.
  Granular transport alone therefore does not establish bounded processing
  residency; selective execution access needs a separate implementation check.
- MyOS `HostMetadataStorage` also replaces the selected
  parent's complete occurrence lists. This is an independent host-side index
  granularity item, not fixed by changing the library's view format.

See [session revision storage](poc-session-revision-delta-storage.md),
[session storage](rooted-document-session-storage.md),
[point-map storage](rooted-persistent-map-storage.md) and
[object storage](rooted-whole-object-storage.md). This is a remaining scalability
boundary, not evidence of a semantic difference from resident execution.

## Required shape

| Data | Physical shape | Normal access/update |
| --- | --- | --- |
| Session head | Small fixed-field descriptor: current/READY positions, status, and roots of separate indexes/layouts | Open the descriptor; atomically replace it and associated publication roots |
| History and membership | Bounded immutable pages plus ordered/search indexes; positions include same-epoch representation order, not epoch alone | Read a point or requested interval; append changed pages and copy only index paths |
| Rooted view | Small descriptor selecting persistent maps of exact members, occurrences, routes and witness positions | Read required members from one pinned historical view; share unchanged map/subtree records |
| Result/evidence | Descriptor selecting bounded ordered pages of events, charge trace, receipts and snapshot references | Read required evidence without decoding every unrelated payload; preserve complete ordered evidence |
| Exact content and proofs | Independently addressed, reusable immutable components; large content may require subdivision below document level | Resolve selected components and their required proof dependencies, not recursively expand the whole graph |

Descriptors must not become another flat list of every page/member. Their own
variable-size indexes need bounded nodes. Both rows and pages need byte bounds:
"one epoch per record" or "one document per record" alone is not sufficient.
Physical addresses/layouts are distinct from semantic BlueIds and identities.
No replacement cyclic-identity or event-ordering algorithm is proposed.

Example: opening a document at epoch 50,000 and appending one ordinary successor
must not read, validate or rewrite 50,000 old revisions merely to recover its
current position. An explicit historical import of 5,000 relevant positions may
still require processing those 5,000 positions, but should consume them in bounded
ordered windows. Exact membership indexes, cutoffs and same-epoch positions must
make point/range access possible without reconstructing the entire prefix.

Likewise, many Orders referencing one exact Agreement result should reference
shared immutable evidence, not persist a fresh copy of that evidence per Order.
Each Order's own reaction and logical gas remain real work. If the protocol
requires visiting every member of a selected graph, pagination does not eliminate
those visits; it eliminates avoidable whole-graph residency and copying.

## Read, verify, publish

1. Pin the session/publication descriptor and its immutable index roots. Do not
   mix pages from a newer head into a historical operation.
2. Select required positions/components through point/range access. Missing
   selected data is not absence of semantic history and must not cause skipping.
3. Reuse library-issued verification of unchanged artifacts. Validate new pages,
   their boundary links, exact ownership/order and changed index paths. Cold
   restoration needs a designed, versioned trusted-storage transport for this
   evidence; a digest or a host-created `verified=true` flag is insufficient.
4. Prewrite only new/changed immutable pages, with bounded staging memory and
   checked acknowledgements. Do not first accumulate every page in one byte map.
5. Publish the new descriptor and required index/outbox changes atomically under
   the existing ownership/conflict checks, only after all required data and
   cross-links are valid. Failure leaves the old publication visible; unreachable
   prewrites are not semantic commits. GC must preserve pinned snapshots and
   history needed by imports, restart and retained results.

Moving today's full validation loop behind a manifest is not sufficient. The
format and runtime access contracts must support compositional validation:
verified unchanged structure plus fully checked new structure and boundary links.
Any check requiring a complete prefix must be redesigned with an equivalent
incremental invariant, not simply removed. Lazy access also needs bounded
dependency prefetch/batching to avoid replacing one large read with thousands of
unnecessary sequential database round trips.

In particular, index counts, endpoints and digests do not establish that every
interior history position is contiguous and correctly owned. The trusted-storage
path must retain library-established append/index invariants; externally supplied
or untrusted histories require their own full validation/import path. Complete
SCC proof work may still be required even when member records are separately stored.

## Ownership and implementation order

Host responsibilities: PostgreSQL storage/transactions, cache budgets, batching,
prefetch, scheduling and recovery/GC policy. Library responsibilities: exact
position selection, structured codecs, indexed history access, reusable evidence
and validation of semantic cross-links. Splitting opaque byte arrays only in
MyOS would still require concatenating them for the current full decoder.
The existing object-store SPI need not change if each new library-defined object
is itself bounded. Work selection also needs metadata-only access so identifying
the next operation does not open every candidate session's complete payload.

1. **Session history:** reuse the existing persistent point-map/range primitives
   and append storage where suitable. Remove flat inventory rewrites and eager
   restore dependencies together; changing only the byte format is insufficient.
2. **Views/results/layouts:** design library-owned segmented transport and
   selective access, including exact representation/witness provenance and
   result-row ownership. Do not flatten private evidence or infer it from heads.
3. **Host integration:** publish descriptors, batch selected reads and measure
   bytes/allocations/round trips. Keep storage limits as operational safeguards,
   separate from deterministic gas and protocol limits.

Each slice requires its own concrete API/design review before implementation.
This note does not approve a broad format rewrite as part of the cache fix.

## Acceptance evidence for the follow-up

- Compare identical next operations over short and long unrelated historical
  prefixes. Count GET/PUT bytes, records, decodes, validation work and peak live
  data, not just elapsed time. Point work should depend on selected data plus
  bounded index paths, not the complete retained prefix.
- Vary graph size independently from history length and selected dependency size.
  Include shared children, cycles, multiple occurrences and large individual
  bodies/results. Explicitly record unavoidable whole-graph protocol work.
- With caches disabled, after eviction and after process restart, bounded reads
  must still hold. A warm cache is not evidence of a scalable cold path.
- Cross page boundaries in ordered events, charge traces and same-epoch history;
  verify exact resident equality, including tight-budget failure location.
- Fail reads and prewrites at every dependency/publication boundary; retry must
  preserve the old committed state, complete evidence and exact next work.
- Corrupt selected pages, range boundaries and cross-links; reject before their
  data affects processing. Unselected data must not be eagerly decoded, but lazy
  validation must never claim whole-history verification it has not established.

No numerical page-size target or large-scale throughput claim is established by
this design. Those choices follow measured payload sizes and access patterns.
