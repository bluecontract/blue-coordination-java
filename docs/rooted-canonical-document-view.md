# Canonical document values in retained nested processing

## Problem and concrete example

The MyOS nested-history scenario has independent managed sources B and C, each
already READY at epoch 1. A attaches saved B0 at `/children/b` and retains the
complete canonical C0 value at `/candidateC`. Applying B1's retained receipt
invokes A's handler. The handler uses `$document: /candidateC` as the value of
an `$appendChange` that installs `/children/c`.

Before this correction, the same compact public-SDK scenario on Coordination
`9807ca3901829f9ef965d1c6a8a63bff516081ff`, with Language
`7ec0fdaafff41ad8e41387e7e5647d5a800d807c`, stopped before source-history
discovery. It requested exact node
`4rN9acTdLU58Pd8egeY6yNMjacuRksWvqKn6ZYyndyPk` for work
`sha256:d34836b5f7c6ac5fc8f7ecb1fd68c6324c1f0fd3b2c65903ffe9b48db18fa27e`.
The real saved C0 identity is
`HsqnMgReSkSYRbH6m4V6z8PS7wuTsRSMVFBiGk9Rkssp`.

The requested `4r...` value is not another missing managed epoch. It is the
diagnostic digest of the expanded resolved C0 graph. Its full effective types
and inherited metadata are a semantic view, not canonical identity input.
The inline canonical C0 bytes were already present and authenticated.

Two adapter representation losses caused the demand:

1. `WorkingDocument.canonicalAt` exposes the selected/source-backed lane, whose
   diagnostic digest need not be its strict canonical identity. The current
   working snapshot already contains the authoritative canonical lane.
2. The BEX adapter retained the parent's exact identity but used the resolved
   graph as its structural canonical body. Child traversal therefore derived
   an expanded graph's digest. The outer generic processor cursor was then
   written as a reference, discarding the locally available canonical body.

The exact output boundary correctly refused to materialize that invented
reference. Its BlueId validation is not the defect.

## Narrow solution

Only `ScopedProcessorExecutionContextBexDocumentView` changes in production.

- Read canonical identity/body from the **current working snapshot**, not the
  raw source-backed working node and not a stale pre-step processor snapshot.
  This uses `snapshot()`, not `commitSnapshot()` or forced full resolution.
- Use the existing BEX `admittedExact(canonical, resolved, identity, supplied)`
  contract. Keep canonical bytes and resolved semantics in separate lanes.
  The invocation-owned pointer cursor is the supplied semantic cursor, rather
  than an outer wrapper that hides the canonical body from `BexFrozenWriter`.
- Carry immutable resolved subtrees alongside child cursors. Ordinary child
  traversal reuses those nodes rather than cloning the resolved subtree through
  a mutable `Node` just to preserve its body.
- If the canonical lane really is only a reference, keep that original
  reference. Structural descendants that lack canonical evidence are opened
  through the invocation-owned processor path. Do not infer their identity from
  a resolved body or attach BEX's standalone construction-time provider.
- Preserve the existing verified cyclic-member handling and scalar semantics.

No public API, authored syntax, schema, Language/BEX implementation, source
selection policy, managed birth policy, scheduler, or gas schedule changes.
The previously incomplete nested transition can now consume its existing exact
input. This is not permission to accept mismatched exact bytes.

### Physical cost boundary

The pinned Language implementation caches `WorkingDocument.snapshot()` and
invalidates it when patches change its roots (`WorkingDocument.java:423,
483–522`). The first source-backed snapshot may reconstruct the whole canonical
graph from the already-held source, resolved graph, and type evidence
(`ResolvedSnapshot.withSource`, lines 350–374). The first `canonicalAt` call also
builds a whole canonical path index, cached for subsequent reads (lines 622–635).
Thus the first read of a changed working state is **not** a bounded path-only
CPU/memory operation; later reads reuse the immutable snapshot and index. This
does not force full resolution or introduce a new provider scan. The provider
locality controls are not a measurement of this cold construction cost.

Normal exact child output conversion returns the existing canonical frozen
node (`BexFrozenWriter.java:33–44`); the adapter passes the existing resolved
child alongside it. It does not deep-copy that resolved graph on each normal
child read. This correction does not add a speculative snapshot optimization.

## Controls and evidence

`RootedNestedDocumentValueIdentityTest` retains two real SDK scenarios:

- `retainedBEventCarriesInlineC0WithoutInventingAResolvedContentDemand` is the
  red/green nested reproduction. B1 installs C0, the nested C catch-up plan uses
  the original barrier and epoch-0 selector, C then advances to its existing C1,
  independent B/C heads and histories do not change, and A survives store
  reattachment without additional work.
- `laterWorkflowStepCopiesCurrentCanonicalValueAndReadsResolvedType` first
  replaces a typed value, then in a second workflow step copies it with
  `$document`, reads its scalar and resolved type name, and checks byte-exact
  canonical output. The earlier snapshot remains unchanged; the final head
  survives reattachment. This excludes a stale processor-snapshot workaround.

The adapter owner additionally checks that an authenticated typed child has a
full canonical output body while its semantic view exposes the expanded type.
The collapsed-reference controls provide explicit canonical leaf evidence and
count the invocation-owned point reads needed to obtain it. A deliberately
inconsistent host pair retains the original reference and rejects structural
child access without evidence. Its former expectation that the unrelated
resolved digest became the child's exact identity encoded the defect and is
not retained as a correctness requirement.

The focused gate also includes existing literal-type admission/forbidden-type
expression negatives, gas-ledger controls, and selected-workflow provider
locality controls. It is not a full library or MyOS qualification.

Exact commands, per-owner XML counts, selected immutable manifest hashes,
source hashes, and raw red/green reports are recorded in the adjacent
`rooted-canonical-document-view-evidence.json` receipt and in:

`/Users/kamil/Documents/Projects/Blue/rooted-canonical-document-view-evidence.HyVo0V`

## Rejected shortcuts

- Do not cache or publish fake `4r...` content under that digest.
- Do not suppress BlueId verification or turn exact-node absence into success.
- Do not replace a canonical child with its expanded semantic graph.
- Do not substitute the old processor document after an earlier workflow step
  changes the working value.
- Do not manufacture a source-history prerequisite for this earlier exact-value
  transport failure, rerun independent sources, or alter attachment policy.
- Do not change Language/BEX APIs when their existing canonical/resolved value
  contract already expresses the required boundary.
