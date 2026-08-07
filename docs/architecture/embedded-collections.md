# Embedded collections

`Process Embedded.collectionPaths` declares object-compatible collections of
embedded process occurrences. It complements `paths`; it does not replace it.

## Stable keys, not positions

For a declaration `/lessons`, each direct ordinary member becomes one scope:

```text
/lessons/algebra
/lessons/geometry
/lessons/key~1with~0escapes
```

Stable object keys survive insertion and removal of neighboring members. List
positions do not: inserting element zero changes every later position. That is
why lists and list positions are not scope identities in this release.

Keys are ordered by Unicode code point. Runtime Pointer escaping is applied
exactly once (`~` becomes `~0`, `/` becomes `~1`). The raw key is retained as
provenance.

## Not a wildcard

`collectionPaths: [/lessons]` means “the direct stable-key members of this
object.” It does not mean `/lessons/*`, does not recursively select arbitrary
descendants, and does not enable wildcard Runtime Pointers. A declaration
containing `*`, a list, a scalar, or a reserved field fails closed.

## Occurrence isolation

Identity of content and identity of an occurrence are different facts. If
both keys point to the same child BlueId:

```text
/lessons/algebra  -> ChildBlueId
/lessons/geometry -> ChildBlueId
```

the canonical fragment can be stored once, but there are still two scope
occurrences. Each has independent Channels, subscriptions, checkpoints,
activation interval, and mutable state in the containing Root.

The same Timeline definition may likewise be reused across many occurrences.
Its immutable definition is shared; its occurrence state is not.

## Nested plans

Collection members can themselves declare exact and collection children. The
effective catalog walks plans root first and returns absolute concrete paths.
Overlap, a repeated concrete boundary, cyclic scope traversal, or a path
through `/contracts` is rejected.

## Activation and retirement

The active subscription surface is evaluated before event processing. A
member added by event `E` is therefore committed as part of the resulting
Root but does not participate in `E`. It becomes active for later events.

Removing a member retires its occurrence. Re-adding the same key creates a
fresh interval; an old checkpoint or subscription cannot leak into the new
lineage.

## Channel-specific targeting

Collection membership declares which scopes are active. It does not invent a
generic `targetKey` field. Timeline, Operation Request, and any host-defined
Channel keep their registered matching and targeting rules.

## Root-only events

Events emitted inside an embedded occurrence can cause further processing
inside the same invocation. Only emissions owned by Root appear in the public
`ProcessResult.events` list. Collection members are not child sessions and do
not publish independent commit results.

## Slicing preserves identity

Replacing exact embedded content with its verified pure reference is a
physical representation change. Inline, referenced, partial, cold, warm, and
batched variants must produce identical resulting Root, public events, gas,
trace, checkpoints, and subscription transitions.
