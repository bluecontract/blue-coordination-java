# One-Root processing

Coordination operates inside the Contracts invariant:

```text
PROCESS(Root, Event) -> ProcessResult
```

Root and Event, together with exact verified delivery evidence and the frozen
runtime configuration, determine one result. Fragment inventories,
subscription snapshots, indexes, caches, and prefetch suggestions are derived
evidence; none is an additional semantic input.

## Invocation sequence

1. Admit the exact Root and Event, inline or as verified references.
2. Validate exact delivery evidence against the pre-event active surface.
3. Execute selected Channels and Handlers in deterministic order.
4. Apply workflow effects to one invocation-owned working Root.
5. Queue and process caused events inside the same invocation.
6. Commit at most one resulting Root, Root-owned public events, subscription
   delta, and checkpoints atomically.

An error or gas exhaustion commits none of those semantic effects.

## Embedded ownership

An embedded scope is an occurrence inside Root. It can own Channels,
Handlers, workflow state, and checkpoints, but it is not an independently
versioned child document. There is no child compare-and-swap or child outbox
inside the semantic processor.

## Public event boundary

Caused events emitted by embedded handlers can drive ancestors and other
selected scopes according to registered Channels. They remain internal unless
Root owns the emission. This prevents physical slicing from changing the
public event list.

## Host responsibilities

The host supplies revision allocation, persistence, exact provider evidence,
subscription index publication, checkpoint storage, compare-and-swap, and an
outbox. Those operations wrap the prepared platform commit; they do not alter
the deterministic processor.
