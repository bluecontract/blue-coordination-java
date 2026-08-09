# Identity and revisions

Every retained whole value has a verified BlueId. `ExactValue` keeps the frozen
value and, when available, the original resolved snapshot. Public reads return
immutable values or detached node copies.

`DocumentSnapshot` distinguishes authored initial identity from current exact
state. It also exposes immutable physical-object, embedded-child, boundary, and
routing evidence for diagnostics.

`DocumentRevision` records document identity, epoch, root application order,
revision kind, before/after exact values, source Timeline Entry, catch-up cause,
emitted events, and processing gas. Initialization has no source entry; a
Timeline revision always has one.

The journal owns global and per-Timeline sequence numbers. Failed append parsing
does not consume either sequence or logical time. Failed top-level admission
does not publish a document, route, object, or metric fact.
