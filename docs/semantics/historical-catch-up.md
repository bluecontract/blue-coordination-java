# Historical catch-up

An attachment captures the exact append frontier and source order key. The
runtime first admits or reuses the child, then brings the parent through every
child revision relevant at that frontier. Each application has a monotonic root
application order and retains the attachment cause.

Existing children are never reprocessed: the parent consumes their committed
revision history. Unseen children process historical source entries exactly
once through the attachment frontier. Entries appended later remain outside
that frontier even if their user timestamp is older.

Nested catch-up runs from the deepest child outward. A root does not become
`READY` until all required child revisions are reflected. Live child revisions
then propagate once along each active parent edge.

This completeness proof is limited to the in-memory journal. Import from an
external frontier fails closed until a durable provider can prove cursor and
history completeness.
