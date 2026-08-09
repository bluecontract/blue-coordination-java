# Autonomous documents

A top-level start creates one independently managed document identified by
`DocumentId`. Ordinary nested maps, lists, and large PayNote values stay inline.
Only a field whose effective contract is `Process Embedded` becomes a separate
autonomous document session.

Attaching a child records a parent occurrence, not ownership of the child's
state. The child processes its source Timeline Entry once; each linked parent
receives an exact processor-managed child-revision event. A parent operation
that tries to mutate child-owned state fails before publication.

If the child `DocumentId` already exists, the supplied value must have the same
authored initial BlueId. Supplying a later current state or a conflicting
initial state fails atomically. Multiple parents can safely reuse the same
child, and concurrent unseen-child attachment converges on one session.

Removal deletes the inverse propagation edge. Reattachment resumes from the
committed child epoch, and cycle-closing edges fail before links or receipts are
published.
