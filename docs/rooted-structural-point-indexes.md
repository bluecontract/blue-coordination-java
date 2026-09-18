# Current rooted occurrence, topology and subscription point indexes

## Problem and example

The active graph is not enough to restore a rooted store. A reciprocal attachment can retain an inactive occurrence with a numbered or representation cursor and a pending terminal join. Its source must not overtake the join, while an unrelated source remains eligible. Reconstructing these rows from current heads or an eventual SCC would lose the actual frozen obligation. Similarly, a same-epoch representation publication can change exact subscription or component-state fields without adding a numbered revision.

## Stored families

The private row components retain the current indexes, preserving each AVL shape and the existing logical mutation counters:

- `StoredOccurrenceIndexes`: source/path, canonical and active order, occurrence and binding identities, touching-document, source and active-source buckets. Row decode uses the current Language verified occurrence constructor, including the complete historical and representation cursor.
- `StoredTopologyIndexes`: component-by-document, forward and reverse edges, the `rootedViews` flag, and **both** pending-join membership directions. The flag and separate roots must belong to one caller-pinned directory view.
- `StoredComponentStateIndexes`: stable lineage, exact state and document-member indexes. Rows retain every component field and the ordered complete cyclic proof wire. A selected read must match the already authenticated original publication's component bytes; a structurally valid constructor or matching asserted identity alone does not create authority.
- `StoredSubscriptionIndexes`: exact slot, identity, document-header and embedded-demand buckets, plus original operation counters. Original channel occurrence identities, source document, header and runtime-contribution BlueIds, graph/component generations and selector modes remain intact.

These are exact retained records, not reconstructed components or new semantic selection rules. The resident mutation algorithms are unchanged. Selected reads check their primary and reverse memberships; join candidates are also checked against the exact retained root snapshot. `SelectedDocument.retainedView` keeps that immutable baseline separate from the mutable working session, just as its retained lineage and generation are separate. One existing scoped session owner supplies the original views; no second session cache or eager catalog walk is introduced.

Each family root is supplied and published independently by the later owning directory. No combined global image or realm CAS is added. The explicit resident-partition conversion is not a production startup scan. Reopening an already stored descriptor assumes the same complete underlying byte store; it is not a relocation/copy protocol. Immutable staging into one store cannot make nodes written only to another copied store available.

## Limits and remaining integration

Map and row byte limits are physical bounds, not gas, transition or semantic limits. Point operations select index paths; selected buckets and proof records can still contain all their existing members, and explicit exhaustive APIs remain exhaustive. This is not a bounded-fanout or bounded-total-history claim. No directory predicate fence, generation guard, whole-store constructor or runtime publication code is replaced here. The later engine adapter must use these selected validation boundaries and preserve physical noncommitting failures.

Full original invocation input and standalone managed transition receipts use separate Language-owned transport. Their exact event capabilities must not be recreated by provider re-admission or by treating a public receipt as an original invocation. Full SDK restart is not claimed by these row components.

## Focused evidence

Occurrence feedback gate 4702 passed 11 tests (four storage controls and seven unchanged occurrence controls). It covers actual rooted admission rows, separately typed exact historical/representation cursor rows, old-root preservation, resident/stored delta counters, malformed identities, mixed memberships, absence and physical failure. Early feedback caught two fixture assumptions: admission had already made its occurrence active, and a copied backing store could not reopen nodes written only to its sibling copy. Neither was normalized into a storage success.

Structural feedback gate 31505 passed six of seven controls. Genuine completed cyclic proof bytes and same-epoch subscription mutations passed. The actual pending reciprocal join **failed** in the pre-existing Language result decoder, before index selection: `RootedProcessingContext.requireEntrySnapshot` rejected an original committed result's retained context against its expanded input snapshot. Direct result-only gate 37183 reproduced the same error without sessions or indexes. The exact original result binary and XML are retained outside the worktree; SHA256 `16ae6eb23e5ed79ce96869ae180a63733a8f2889fdea38b678f2b54846caec90`.

The pending-join cold control and direct original-result control remained unchanged. Language `f241be7ee031cace33ba37ae93e6801d9895d2af` corrects its decoder's distinction between retained original-entry context and the actually executed expanded input; this index patch does not bypass their association checks.

Final gate 30068 passed **54/54 tests plus Javadoc** in 51 seconds against the exact immutable Language `f241be7` / unchanged BEX `ab72af1` / Catalog `0b68744` tuple. It includes all new structural, occurrence and selected-document controls, and unchanged occurrence, persistent occurrence, component, pending-join eligibility and subscription families. The source hash before and after the gate was `b5e404902a02d4c8caa7aab711a8ecdfd5ce43d6d4d366790d53eb082428e0ec`. Only this qualification paragraph changed afterward. XML, original red evidence and the tested source snapshot are retained in `rooted-structural-index-evidence.kY1RMU` outside the worktree. This qualifies the physical row components, not fresh whole-SDK restoration or independent bucket publication.
