# Durable representation verification memo (candidate)

## Problem and bounded change

The parent-owned full fourteen-input SDK recording using Coordination
`8278eb77ee597dd906b737a7e0ffe7afbff7df8a` and Language `3491` attributed
2,623 of 4,153 execution samples (about 63%) to
`ManagedRepresentationHistory.at` → `ManagedRepresentationTransition` →
`ClosureEvidenceVerifier`. This is a sampled CPU observation, not a wall-time
percentage or a promised speedup. The original strict MyOS ring readiness
deadline remains the performance acceptance oracle.

For example, several history readers reconstruct the same two same-epoch
checkpoint transitions from exactly the same immutable PROCESS publications.
The Language constructor repeatedly verifies the closed snapshot, identities,
component proofs, source classification and reference-only change. Its output
is immutable; the constructor does not read the current store, external node
provider, clock or processor gas meter.

The private store-owned memo reuses only this successful constructor result.
The key compares the actual publication, original input and original result
objects by Java reference identity, and includes every other constructor
operand: source document, epoch, anchor receipt, predecessor position and
selected transition receipt. Identity-hash collisions still require complete
key equality. No durable record equality, public API, wire format, policy,
semantic result or tariff changes.

## Authority and lifecycle

Every `at()` still reads the current source/session and numbered anchor,
iterates and validates the ordered durable rows, resolves the actual current
publication, checks committing membership and original input availability,
checks rooted checkpoint authority, compares exact durable endpoints, and
reads/validates the actual next receipt or terminal head. Captured root/view,
source-prefix, target, occurrence and frozen-cutoff checks remain unchanged.
An external supplied transition is never inserted as authority.

Only an object already present in the store's exact durable publication map
may hit or populate the memo. A staged-on-entry, reconstructed or aborted
publication uses the ordinary uncached constructor, even if a concurrent
publication commits while it is being verified. Constructor exceptions are
not retained. Expensive cold construction runs outside the store monitor;
lookup and conditional insertion use short synchronized sections. Before
insertion the store rechecks both publication object membership and the
original memo instance. Clear replaces that instance, preventing pre-clear
in-flight construction from repopulating it. Restart-from-stores and close
clear it; cold reconstruction is semantically equivalent.

The current store's PROCESS inventory is append-only: duplicate publication
IDs are rejected; atomic replacement appends the retained object; document
removal, plan changes and the existing evidence-corruption test helpers retain
the publication index. Pre-swap failure never installs the replacement.
Restart rebuilds coordinators over the same store, and legacy rollback changes
readiness/removes newborn sessions without deleting PROCESS publications.
There is no current in-place reset/install path replacing this inventory.
Any future publication pruning or store-image replacement must invalidate the
memo as part of that operation.

The default limits are 2,048 entries and 4 MiB of conservative **additional
wrapper/key/derived-string accounting**, not an exact JVM allocation measure
and not a deep-heap or total-store memory bound. The transition retains only
input/result/selected receipt/document objects already retained by its durable
publication; temporary cloned verifier graphs are not cached. Oversized keys
fall back to uncached construction. Eviction removes only disposable memo
entries. No static cache, speculative publication, body copy, or cached chain
is introduced.

## Controls and evidence limits

`ManagedRepresentationVerificationMemoTest` uses the maintained real
checkpoint-only source/parent shape and verifies object reuse across separate
history readers; publication/input/result/scalar-key distinctions; repeated
invalid attempts; entry/weight eviction; detached accessor mutation; and
restart/close cold reconstruction with unchanged exact outputs, history,
events and gas receipts. Warm corruption controls retain all normal guards:
missing publication, missing rooted authority, altered row endpoint, missing
anchor, altered terminal head, forged supplied position, and an actual later
numbered successor. A real pre-swap failure and an uncommitted genuine
processor result exercise staged non-retention.

The change is a library implementation optimization because the repeated
constructor is inside the private retained-history reader. A host cannot skip
it without replacing library authority checks. Hash-only success tokens,
whole-chain caching, caching uncommitted proposals and weakening history
guards were rejected. Weak transition values would avoid retention assumptions
but may lose hot proofs on every collection; the narrower current-store
append-only lifetime supports bounded strong reuse instead.

No tests/builds have been run by the author for this candidate. Focused
controls, adjacent history/checkpoint/terminal-tail owners, full fourteen-input
SDK result/gas parity, and the original unmodified MyOS strict ring are pending
parent-owned qualification. Cache hits alone do not prove deadline acceptance.
