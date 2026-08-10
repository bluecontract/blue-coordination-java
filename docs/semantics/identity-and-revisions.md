# Identity and revisions

Every retained whole value has a verified BlueId. `ExactValue` keeps the frozen
value and, when available, the original resolved snapshot. Public reads return
immutable values or detached node copies.

`DocumentSnapshot` distinguishes authored initial identity from current exact
state. It also exposes immutable physical-object, embedded-child, boundary, and
routing evidence for diagnostics.

`DocumentId` and state BlueId are deliberately different. One `DocumentId` has
an ordered epoch history with potentially many state BlueIds. Different
DocumentIds remain independent even when their exact current state has the same
BlueId. When an existing managed document is attached, a verified current state
attaches directly, a verified historical state catches up through later known
epochs, and an unknown divergent state fails closed or requires an explicit
fork.

`DocumentRevision` records document identity, epoch, root application order,
revision kind, before/after exact values, source Timeline Entry, catch-up cause,
emitted events, and processing gas. Initialization has no source entry; a
Timeline revision always has one.

One immutable `EmbeddingBinding` names a parent occurrence, child DocumentId,
path, activation generation, admitted child state, and attachment evidence.
`EmbeddedEpochCursor` separately names the latest child epoch incorporated by
that occurrence. Removing and re-adding a path creates a new activation
generation and a fresh cursor without deleting immutable child audit history.

The journal owns global and per-Timeline sequence numbers. Failed append parsing
does not consume either sequence or logical time. Failed top-level admission
does not publish a document, route, object, or metric fact.
