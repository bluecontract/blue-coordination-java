# Public API reference

The normal application boundary is `blue.coordination.sdk`.
`BlueCoordination.inMemory()` is the single default: it owns an in-memory
Contracts 1.0 environment pinned to the release manifest bundled in the JAR.
Full signatures are in the generated Javadocs.

`blue.coordination.api` remains available for advanced host integration and
legacy migration. Its plain `CoordinationEngine.inMemory()` factory is the
earlier acyclic compatibility profile and must not be treated as equivalent to
the SDK default.

## Runtime owner and catalogs

`BlueCoordination` is `AutoCloseable` and exposes these owned catalogs:

- `timelines()` registers authenticated local Timeline handles.
- `documents()` admits and reads managed lineages and complete closures.
- `operations()` starts exact document-targeted operation calls.
- `events()` starts deliberate broadcast Timeline Entry admission.
- `processing()` performs a canonical drain of submitted work.
- `values()` resolves authored YAML to an immutable exact value.
- `advanced()` exposes explicit diagnostics and low-level compatibility.

Handles are owner-bound. Passing a Timeline, document, draft, entry, or other
owned value to a different `BlueCoordination` instance fails instead of
silently crossing environments.

`BlueCoordination.builder().release(languageIdentity, contractsIdentity)` is
an advanced custom-release option. Both values must be lowercase `sha256:`
identities. Ordinary callers use `inMemory()` and never type release hashes.

## Timelines and exact values

`timelines().local(accountId)` registers a Timeline whose id and actor account
are the same. `register(timelineId, accountId)` keeps them explicit.

`values().yaml(source)` resolves with the runtime's pinned Language release and
returns `ExactBlueValue`. An exact value exposes its authoritative BlueId and
cyclic-member status while retaining immutable verified content. Snapshot
scalar helpers provide exact long, text, and boolean reads by JSON Pointer.

## Ordinary document admission

An ordinary top-level document is authored, explicitly authorized as a public
Root, and given a temporal policy:

```java
DocumentHandle order = blue.documents().admit(
        ManagedDocument.yaml("order-123", orderYaml)
                .publicRoot()
                .fromNow());
```

The SDK resolves the authored value, compiles a one-member complete closure,
authenticates its public Root, and atomically admits it through Contracts. It
does not seed a legacy singleton session. A top-level definition without
`publicRoot()` or an explicit activation policy fails closed. Top-level SDK
admission currently supports `fromNow()`, `importFullHistory()`, and
`importFromFrontier(exactEvidence)`; attach-current and passive-snapshot values
remain vocabulary for future occurrence evidence and are rejected at this
boundary.

## Complete closure admission

```java
ClosureHandle closure = blue.documents().admit(
        ManagedClosure.builder()
                .document("a", yamlA)
                .document("b", yamlB)
                .bindOccurrence("a", "/b", "b")
                .bindOccurrence("b", "/a", "a")
                .publicRoot("a")
                .fromNow()
                .build());
```

Aliases are immutable construction names; each member has a stable
`DocumentId`. `bindOccurrence(sourceAlias, path, targetAlias)` is managed
lineage evidence, not an authored graph. The SDK requires the source's
effective `Process Embedded` catalog to declare the canonical path, verifies
that the exact value at that path agrees with the target, and rejects missing,
duplicate, extra, or ambiguous bindings. Language owns cyclic finalization and
complete-proof verification; the SDK only supplies the authored boundary.

`ClosureHandle` exposes the authenticated closure identity, members by alias,
and public Roots. It does not expose component snapshots, occurrence internals,
proof objects, or invocation environments.

## Targeted operations

```java
EntryResult result = blue.operations().on(order)
        .from(alice)
        .call("attachPayNoteAsCustomer")
        .through("customerChannel")
        .request(request -> request.exact("payNote", payNote))
        .execute();
```

`on(DocumentHandle)` binds the exact current state. `on(DocumentId)` can name a
currently absent lineage so execution returns a terminal `REJECTED` result with
`TARGET_DOCUMENT_NOT_FOUND`; constructing the call does not throw merely
because the target is missing. A changed exact target returns `STALE`.
Missing operations, target Channels, and source/Channel matches return precise
diagnostics such as `OPERATION_NOT_FOUND`, `TARGET_CHANNEL_NOT_FOUND`, and
`TARGET_CHANNEL_SOURCE_MISMATCH`.

The target is evidence used by the selected Contracts profile. The caller does
not supply the final recipient set, and an Order-specific request is not
silently converted into a broadcast.

`requestYaml(yaml)` supplies one ordinary authored request. The structured
request builder uses `exact(field, value)` to preserve whole exact values.

## Managed drafts produced by operations

`documents().draft(id, exactInitial)` creates immutable stable-lineage evidence
for a new managed occurrence. Supply the same draft in the exact request and
declare every effective result path that must bind it:

```java
ManagedDocumentDraft child = blue.documents().draft(
        childId, blue.values().yaml(childYaml));

EntryResult result = blue.operations().on(parent)
        .from(alice)
        .call("createChild")
        .through("ownerChannel")
        .request(request -> request.managed("child", child))
        .expectOccurrence("/children/child-456", child)
        .activation(ActivationPolicy.fromNow())
        .execute();
```

The SDK verifies owner identity, exact request value, canonical effective
`Process Embedded` paths, and complete result agreement. A single draft may be
bound at several paths to express one stable lineage with multiple
occurrences. All new heads and topology changes publish atomically with the
parent result; a terminal failure leaves no partial expansion.

This candidate supports only new `FROM_NOW` lineages. Imported-state evidence
created with `draft.atEpoch(...)` and historical, frontier, attach-current, or
passive operation-result activation fail closed. The SDK never emulates this
lane through legacy child/parent admission.

## Broadcast events

```java
EntryResult result = blue.events()
        .from(alice)
        .exact(completeTimelineEntry)
        .execute();
```

`exact(...)` accepts a complete exact Timeline Entry envelope whose source
Timeline and actor agree with the selected handle. Broadcast is explicit and
still environment-routed. A valid entry accepted by no active Channel is a
terminal `NO_MATCH`, not an empty low-level receipt error.

## Append and process separation

Every operation and event call is single-use and supports:

- `submit()`: validate and append exactly once without PROCESS;
- `execute()`: append, then canonically drain through that entry.

`execute()` includes earlier eligible entries and cannot overtake them. The
portable split is:

```java
EntryHandle submitted = call.submit();
DrainResult drained = blue.processing().drain();
EntryResult result = drained.entry(submitted);
```

`DrainResult.entries()` is in canonical processing order. `find(handle)` keeps
absence distinct from `NO_MATCH`; `entry(handle)` requires a result in that
specific drain. Drain-wide state distinguishes quiescent, paused, and blocked
frontiers.

## Results and reads

`EntryDisposition` contains `APPLIED`, `NO_MATCH`, `STALE`, `MIXED`,
`REJECTED`, `NEEDS_RESOURCES`, `GAS_LIMIT_EXCEEDED`,
`PORTABLE_LIMIT_EXCEEDED`, and `BLOCKED`.

One appended entry can affect disconnected closures independently.
`EntryResult.closures()` therefore retains each `ClosureResult`, its committed
`DocumentChange` values, public events, processing statistics, and diagnostic.
The aggregate disposition is `MIXED` when terminal closure dispositions differ.
`ProcessingStats` reports gas, committed transitions, documents opened, exact
document-step order, elapsed time, and named counters.

`DocumentHandle.snapshot()` is READY-only and exposes application state,
DocumentId, epoch, BlueId, exact content, and public events. `history()` returns
immutable application-safe revisions. Physical objects, topology generations,
proofs, and storage layout are not part of the normal snapshot.

## Advanced boundary

`AdvancedCoordination.rawEngine()` returns the owned low-level
`CoordinationEngine` for a host that must migrate an existing integration.
`auditDocument(id)` deliberately permits non-READY reads. Advanced identity
accessors expose the exact Language, Contracts, fixture package, gas manifest,
cyclic finalizer, and proof-verifier identities used by evidence tooling.

`auditManagedOccurrence(sourceId, occurrencePath)` returns an optional
`ManagedOccurrenceAudit` for a retained occurrence row. The value contains the
target `DocumentId`, positive activation generation, and active/inactive flag;
it intentionally omits component snapshots, proof values, and mutable
inventory internals.

Low-level types such as `ClosureInvocationInput`, occurrence bindings,
component/closure snapshots, cyclic proofs, closure environments, and execution
policies are not permitted in normal SDK signatures.

## Dependency and package surface

The POM exposes Contracts and BEX artifacts at compile scope where retained
advanced API and processor signatures require their types. Repository and
Bouncy Castle remain runtime implementation dependencies. Every coordinate is
exact and dependency-locked.

`blue.coordination.processor` is an advanced semantic-integration surface.
`blue.coordination.internal` is not application API and may change between
release candidates. The exact package ownership and migration policy are in the
[SDK migration and ownership ledger](sdk-migration-and-ownership.md).
