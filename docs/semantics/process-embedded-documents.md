# Managed Process Embedded documents

The only document-dependency model is the effective `Process Embedded` graph
derived from `paths` and `collectionPaths`. Ordinary nested maps, lists, and
large values remain inline unless that effective contract establishes a process
boundary. The runtime does not add a second authored link protocol, target set,
wave model, consistency mode, or SCC planner.

Every processable document has a stable `DocumentId` and immutable epoch
history. A state BlueId identifies one exact state, not the continuing history.
The same DocumentId may occur under several parent paths: its direct Timeline
transition runs once, while each occurrence applies the resulting epochs through
its own cursor. Different DocumentIds remain independent even when their current
state BlueIds are equal.

`EmbeddingBinding` contains immutable topology and activation identity.
`EmbeddedEpochCursor` contains progress as replaceable persisted state. This
separation keeps graph snapshots stable and lets one parent lag or retry without
changing another parent or the child history.

A containing document applies a child epoch through exact frozen PROCESS. The
engine supplies a private `EmbeddedEpochInput` with parent/path/generation
evidence, old and new child BlueIds, and ordered `EventOccurrence` records. The
parent processor verifies the old state, performs replacement, runs reactions,
and commits its new epoch. The host never pre-mutates the child field, and the
input is never a synthetic provider Timeline Entry.

Activation policy is admission metadata, not another field on the canonical
`Process Embedded` contract. A new occurrence may be born at attachment, import
complete history, import from a verified frontier, attach a proven current
state, or remain a passive snapshot. A document with no effective contracts and
no processable descendants remains ordinary content unless explicitly admitted
as a managed process.

Removing an occurrence retires its binding and source projection but preserves
history. Re-adding the path creates a new activation generation and cursor. A
known current child state attaches directly; a known older epoch catches up
through missing epochs; an unknown divergent state fails closed or requires an
explicit fork. Cycles are rejected before topology or receipts publish.

This managed epoch/history behavior is the Round 10.1 Coordination temporal
profile. It uses frozen Language, Contracts, BEX, and Repository transitions but
does not claim these cross-document histories are already normative Contracts
1.0 behavior.
