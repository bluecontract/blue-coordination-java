# One NBA Game shared by several containing documents

This is a retained temporal-profile semantic scenario, not the normal rc.3
operation-created draft tutorial. Use it to understand stable lineage reuse and
historical parent synchronization. For current SDK admission and dynamic
`FROM_NOW` children, use the
[SDK developer guide](../guides/developer-guide.md).

The scenario uses only the graph derived from `Process Embedded`.

```text
Host 1 ----\
            >---- Game document history
Host 2 -----|
Host 3 ----/
```

The Game has one stable `DocumentId` and one epoch stream:

```text
epoch 0  initialization, emits NBA/Game Initialized
epoch 1  Game Started
epoch 2  home scoring play
epoch 3  away scoring play
epoch 4  Game Ended, emits NBA/Game Ended
```

Host 1 starts before the Game exists in the environment. Its attachment
transition creates the Game session. The Game initializes once, and Host 1
applies the initialization epoch and its event.

After the Game progresses, Host 2 starts and embeds the same original Game
state with the same `DocumentId`. The environment resolves that state to the
existing Game history, creates no new Game session, performs no second
initialization, and applies the already committed epochs to Host 2 in order.

Later Game transitions are executed once. Each host independently applies the
resulting child epoch. Both hosts observe the final `NBA/Game Ended` event.

After the Game is Final, Host 3 embeds the same original state and stable
`DocumentId`. It consumes all five retained epochs—including initialization
and ending events—without advancing, reinitializing, or replaying the Game.

Required structural evidence:

```text
Game sessions created                  1
Game sessions reused                   1
Game initialization revisions          1
Game source PROCESS per source entry    1
Initialization epoch applications       2
Initialization event occurrences        2
Parent epoch applications              10
Request/entry/ordinary fragments         0
Source replay per parent                 0
```

The post-Final extension adds one reuse, five parent applications, one retained
initialization-event application, and one retained ending-event application;
the Game still has one session, one initialization, and five revisions.

The retained temporal compatibility profile also characterizes dynamic
activation from previously inline content. Do not infer from that scenario that
the rc.3 SDK imports arbitrary existing or historical children from operation
results. Its supported application lane requires a genuinely new exact
`ManagedDocumentDraft`, complete `expectOccurrence(...)` evidence, and
`FROM_NOW` activation.

For several collection members, initialization order is canonical absolute
Runtime Pointer order using Unicode code points. It is not insertion order,
Java UTF-16 `String` order, or BlueId order.
