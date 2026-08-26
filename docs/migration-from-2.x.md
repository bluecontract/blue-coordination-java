# Migration from 2.x

Version 3 is a breaking application-API reset. New and migrated application
code uses `blue.coordination.sdk.BlueCoordination`; it must not migrate to the
plain `CoordinationEngine.inMemory()` factory, which retains the earlier
acyclic compatibility profile.

The removed 2.x generic planner, fragmentation, session-store, subscription
delivery, fast-path, and demo-host surfaces have no compatibility wrappers.

## Migration sequence

1. Upgrade the application baseline to Java 17 or newer.
2. Create one `BlueCoordination.inMemory()` owner for each coherent runtime
   environment.
3. Register every Timeline explicitly with `timelines().local(accountId)` or
   `timelines().register(timelineId, accountId)`.
4. Give every continuing managed lineage a stable `DocumentId`.
5. Replace 2.x document/session startup with `ManagedDocument` admission or one
   complete `ManagedClosure` containing every initially known managed member
   and occurrence binding.
6. Replace caller-directed processing with exact targeted operation calls or
   deliberate complete-entry broadcasts.
7. Separate append from processing deliberately: use `submit()` plus
   `processing().drain()`, or `execute()` for append-and-drain through one
   entry.
8. Replace mutable/session reads with READY-only `DocumentHandle.snapshot()`
   and immutable `history()`.
9. Handle `EntryDisposition` and stable `Diagnostic.code()` explicitly.
10. Re-evaluate deployment assumptions against the in-memory bounded-pilot
    limitations.

## API replacement map

| 2.x or low-level pattern | Version 3 application replacement |
| --- | --- |
| generic engine/session owner | `BlueCoordination.inMemory()` |
| `registerTimeline(id, actor)` | `timelines().register(id, actor)` |
| legacy `startDocument(...)` | `documents().admit(ManagedDocument...)` |
| hand-built closure/proof input | `documents().admit(ManagedClosure...)` |
| `Operation.yaml(...)` / `Operation.exact(...)` | `operations().on(document)...requestYaml(...)` / structured request builder |
| caller-selected dispatch target or recipient | exact document target evidence plus environment-derived recipients |
| `appendTimelineEntry(Node)` | `events().from(timeline).exact(value).submit()` |
| `engine.drain()` / `drainThrough(...)` | `processing().drain()` or call `.execute()` |
| collapsed `onlyOutcome()` | `DrainResult.entry(handle)` and `EntryResult.closures()` |
| mutable/session document read | `DocumentHandle.snapshot()` and `history()` |
| low-level audit read | explicit `blue.advanced().auditDocument(id)` |

The complete package and ownership mapping is maintained in the
[SDK migration and ownership ledger](reference/sdk-migration-and-ownership.md).

## Document topology

Model document dependencies only with effective `Process Embedded.paths` and
direct stable-key members under `collectionPaths`. Do not migrate application
links into a second Coordination relationship graph.

If several managed documents, shared children, or cycles already exist, admit
them together:

```java
var closure = blue.documents().admit(
        ManagedClosure.builder()
                .document("a", aId, yamlA)
                .document("b", bId, yamlB)
                .bindOccurrence("a", "/b", "b")
                .bindOccurrence("b", "/a", "a")
                .publicRoot("a")
                .fromNow()
                .build());
```

The caller supplies stable member and occurrence-lineage evidence, not SCCs,
cyclic BlueIds, proofs, or direct recipient sets.

## Operations and external entries

Use targeted operations when the application owns command construction:

```java
EntryResult result = blue.operations()
        .on(order)
        .from(alice)
        .call("confirm")
        .through("ownerChannel")
        .requestYaml("reason: approved")
        .execute();
```

Use `events()` only when a provider supplied a complete exact Timeline Entry:

```java
EntryResult result = blue.events()
        .from(alice)
        .exact(blue.values().yaml(entryYaml))
        .execute();
```

Neither path lets the caller name final recipients. Active Channels and the
selected Contracts profile derive them. A valid unmatched broadcast is
terminal `NO_MATCH`; a missing exact operation target is `REJECTED` with
`TARGET_DOCUMENT_NOT_FOUND`.

Construct dependent operation calls after earlier calls commit. The SDK binds
the exact current target when `.on(handle)` is evaluated, so prebuilt future
calls can become `STALE`.

## Temporal admission

Choose activation explicitly. Top-level document/closure admission supports:

- `.fromNow()`;
- `.activation(ActivationPolicy.importFullHistory())`; and
- `.activation(ActivationPolicy.importFromFrontier(exactEvidence))`.

Operation-created managed children are narrower in rc.3: only genuinely new
`FROM_NOW` lineages with exact draft/request/path evidence are supported.
Imported draft epochs and historical/frontier/attach-current/passive
operation-result activation fail closed.

## Removed assumptions

- Append does not process and does not retain a caller-supplied recipient list.
- Java submission order across Timelines is not a workflow schedule; canonical
  source order is authoritative during a batch drain.
- A sequence of entries is not one whole-engine transaction. One committing
  connected affected closure has an atomic publication boundary; disconnected
  closures and later entries can commit independently.
- `DocumentId` is continuing lineage identity; BlueId is one exact state.
- The bundled runtime does not provide process-restart durability.

Continue with the [SDK developer guide](guides/developer-guide.md) for complete
application workflows and [Known limitations](limitations.md) before choosing a
deployment profile.
