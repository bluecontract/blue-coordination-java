# Exact rooted document-session storage

## Problem and executable examples

A current rooted session is not its latest document BlueId. It contains a separate READY layout and topology, numbered receipts, same-epoch component representations, admission-selected source positions, and processor-authenticated dependency views. Replaying input or rebuilding a view from the latest member heads can change its historical witnesses, ownership, gas, or source cutoff.

Two concrete examples drive this component:

* A parent processes two unmatched source events locally. Its numbered history remains at epoch zero, while two checkpoint-containing-reference representations advance. Cold restoration must retain both positions before the next root-local event, without publishing another source epoch.
* A FROM_NOW static admission keeps a source view selected exactly at its frontier. The source session and the consumer admission must reference the same immutable view instance after either session-open order. `rootedPublicationPrefix` intentionally uses that exact retained-position membership, not endpoint BlueId equality.

## Storage and ownership

`DocumentSessionStorage` is package-private. It writes one versioned session record and separately addressed complete rooted-view records through `CoordinationImmutableObjectStore`. The owning runtime must atomically publish the returned session address with all its other indexes. This component has no commit operation, mutable directory, engine builder, or SDK mode.

Every record is SHA-256 addressed, maximum-byte checked before decoding, and compared to canonical re-encoding. Every record serializes successfully before the first immutable prewrite. A failed later prewrite can leave unreachable bytes, but returns no publishable new reference and does not mutate a session. An already published reference remains usable.

One explicit `OpenScope` belongs to one selected runtime/read lifetime. It lazily interns immutable rooted views by their complete authenticated physical record address. It does not scan another session or the realm. Independently opened sessions share source views within that scope, but mutable sessions themselves are always detached. Closing the scope prevents further opens; live sessions continue to hold their already restored immutable values. A new selected runtime must use a new scope, not a global ever-growing interner.

## Complete retained state

The session record retains both layouts and all their exact semantic/processing/shell bodies, boundary and occurrence rows, authored planning identities, rules, collection diagnostics, routes and request patterns. It also retains active subscription dependency evidence; every numbered revision with exact before/after, source entry, causal order, events and receipt; terminal entry and transition identities; ordered same-epoch transitions; StateEpochs first/ambiguous indexes; current/READY/graph/application positions, status, frontier and READY topology; the original rooted history descriptor and admission identities; admission-source references; and the current view plus every ordered view position and its clamped boundary. Insertion order is retained where the original state explicitly uses ordered maps or sets. Absent pre-publication rooted state stays absent.

Each rooted-view record retains the complete original processor result, both original and retained witness snapshots, subscription inventory and embedded demand rows, diagnostic operation counters, routes, exact owned published heads, and logical boundary. Language owns exact/frozen nodes, resolver snapshots, terminal result and witness-snapshot codecs. Coordination does not replace those codecs with flat Node JSON.

Restoration validates contiguous history, the independently retained StateEpochs and terminal indexes, same-epoch representation chains, layout/head membership, exact clamped boundary sequence, and current-view membership. Cross-store facts such as the full READY occurrence inventory remain the responsibility of the future complete-runtime owning state; this component does not invent them from a layout.

The retained snapshot is not rebuilt as authority. A deterministic validation oracle invokes the existing `rootedRetainedSnapshot` with the **complete original restored processor result** and exact recorded owned epochs, compares complete snapshot codec bytes including private witness roles, then retains the explicitly stored snapshot. No PROCESS, publication, or substitution of current heads occurs.

## Necessary cold equality correction

`ComponentSnapshot` does not implement value equality. The old `RootedDocumentView.matchesCapture` list equality therefore rejected independently decoded but identical components. Cold capture now compares every ordered component field: both identities, generation, kind, ordered document/BlueId members, master, proof identity and complete declared proof wire form. Hash strings alone do not hide altered proof bytes. No ordering or witness check is removed.

## Bounds and remaining runtime boundary

Limits bound an individual encoded record, physical codec traversal, and total encoded view bytes retained by a restoration scope. Backreferences do not imply a new logical graph-depth limit. This first component fully materializes the selected session history and its referenced views. Its memory and retention work are proportional to that selected history; it is not an indexed-history or bounded-total-runtime-memory claim.

Full engine/SDK recovery still needs atomically associated journal, topology, root scheduling, catch-up plans/work, subscriptions, publication receipts, provider and verification state, plus the selected root directory. The next-action tests replace decoded sessions in an otherwise unchanged actual engine fixture. They are per-session cold-restoration controls, **not fresh-process SDK E2E**.

The initial d2ce Language terminal codec re-admitted some typed event evidence on decode and could consult its runtime provider or replace the original Frozen construction mode. Language's bd09c281 result format 2 retains the original processor-issued event evidence. Session restoration now uses its pure `decode(bytes)` API and accepts no processor runtime or provider. The Language component's actual typed Source/semantic-admission controls retain complete original frozen bytes after the producing provider closes; session controls additionally restore a genuinely emitting session after its producing runtime closes, compare complete result codec bytes (including private Frozen event evidence), and assert zero external provider reads during session restoration.

## Focused evidence

Final native gate 94630 passed **30/30 tests plus Javadoc** in 1m42 on exact Language bd09c281, unchanged BEX ab72af14 and catalog 0b68744b rebuilt against that Language. All failures, errors and skips were zero. The source-file hash before and after was `575d63509ac1522a96edae5ea4edeabc6d242c79ea3a840d9afb281991b86e5c`; only qualification documentation changed afterward. Twelve session controls plus the eighteen unchanged rooted controls below include malformed admission-source positions and the producer-closed exact event round trip. The preceding gate 79265 stopped at test compilation because a new test accessed Language's private event capability; it was corrected to compare the complete public result-codec bytes, with no public API expansion or production change.

Native gate 61135 passed **28/28 tests plus Javadoc** in 1m30 on exact Language d2ce037d, unchanged BEX ab72af14 and catalog 0b68744b rebuilt against that Language. The source-file hash before and after was `f25104d10d3df278eb2fe3753800bc996960e53e27879d31f1d313b8f680fdac`. This initial proof precedes the pure event decoder binding and an additional malformed admission-source position control.

The selected owners were DocumentSessionStorageTest (10), DocumentSessionStateEpochsTest (6), RootedRetainedSourceWireTest (2), RootedTerminalOwnerFenceTest (2), RootedJournalCutoffTest (2), RootedStaticAdmissionCutoffTest (4), and RootedTerminalTailSdkBoundaryTest (2). The ten new controls cover actual counter and checkpoint-reference continuation, independent component reallocation/corruption, static source aliasing, READY separation, recurrent state ambiguity, missing/corrupt/unavailable bytes, malformed indexes/boundaries, selected-only reads, byte limits and failed later prewrites. Existing rooted history/cutoff and terminal-join owners remain unchanged. This is focused component qualification on the stated initial tuple; it is not full SDK recovery or a release-wide qualification.

Old `SessionHistoryStorage` was reviewed only for history-index validation ideas. No legacy engine, persistent-map stack, scheduling semantics, or old SDK runtime format is ported.
