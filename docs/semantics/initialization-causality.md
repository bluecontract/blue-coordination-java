# Initialization causality and embedded lifecycle events

Initialization is a deterministic processor-managed document transition. It is
not an externally authored Timeline Entry and must not be assigned a fabricated
provider timestamp.

Every initialization revision nevertheless carries exact causal evidence:

- the document-local epoch and application order;
- the canonical external-order frontier at which the document became active;
- a real causal BlueId: a retained top-level admission cause or attachment entry;
- `CatchUpCause` for a document activated through `Process Embedded`;
- ordered Root events emitted by lifecycle handlers;
- exact frozen processing gas.

For a host that adds a processable child, the order is:

```text
host initialization
host attachment transition E
child initialization caused by E
host application of the child initialization epoch
historical child epochs before E, when required
live entries after E
```

The child never processes the attachment entry itself. Initialization events are
stored once on the child's epoch. Every containing document applies that same
immutable epoch through the processor-owned embedded input. A second host that
later embeds the same `DocumentId` reuses the existing child session and applies
the retained initialization epoch; it does not initialize the child again.

Application reads remain closed until graph publication, initialization,
catch-up, and all required child-epoch applications are complete. Audit reads
may inspect a committed `CATCHING_UP` state.

## Time model

Initialization has no external source timestamp because no Timeline Provider
published it. It has two deterministic coordinates instead:

```text
causal source order
    The admission or attachment frontier that made initialization required.

document application order
    The position of the initialization transition in that document's history.
```

An internal monotonic value used to identify the exact processor-owned input is
not an external timestamp and must never participate in cross-provider order.

Top-level admission retains an internal `Coordination/Document Admission Cause`
whole object. Its BlueId binds the stable `DocumentId`, exact authored state,
admission policy, and typed admission-frontier tuple. Retries reuse that BlueId;
embedded initialization instead retains the exact attachment entry BlueId.
