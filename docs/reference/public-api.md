# Public API reference

The normal application boundary is `blue.coordination.sdk`.
`BlueCoordination.inMemory()` is the single default: it owns an in-memory
Contracts 1.0 environment pinned to the release manifest bundled in the JAR.
Full signatures are in the generated Javadocs.

For a task-oriented walkthrough from authored documents through several
Timelines and operation-created managed members, read the
[SDK developer guide](../guides/developer-guide.md). This page is the concise
facade and result reference.

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

Hosts that key managed lineages by exact authored content can opt in with
`builder().contentDerivedDocumentIds()`. Under that policy the managed
`DocumentId` is the BlueId of the complete authored value before
initialization. A property at `/documentId` remains ordinary authored content:
it is neither read as the lineage selector nor inserted, removed, or rewritten.
It contributes to the exact authored BlueId like every other property. An
explicit caller-supplied `DocumentId` must agree with the derived identity.

`builder().exactNodeProvider(provider)` installs a read-only
`ExactNodeProvider` for static authored admission. Its single method is
`Optional<String> findExactContent(String blueId)`. Coordination requests only a
specific whole value named by a pure BlueId reference; it never enumerates the
provider. Returned serialized content is parsed defensively and
identity-verified before managed state is written. `ExactNodeProvider.empty()` and
`ExactNodeProvider.of(blueId, exactContent)` and
`ExactNodeProvider.of(exactValue)` cover the empty and one-value cases.

## Timelines and exact values

`timelines().local(accountId)` registers a Timeline whose id and actor account
are the same. `register(timelineId, accountId)` keeps them explicit and uses a
principal actor. The overload
`register(timelineId, accountId, TimelineActorKind.AGENT)` authors exact Agent
Actor evidence; `TimelineHandle.actorKind()` reports the stable choice.

`values().yaml(source)` resolves with the runtime's pinned Language release and
returns `ExactBlueValue`. An exact value exposes its authoritative BlueId and
cyclic-member status while retaining immutable verified content. Snapshot
scalar helpers provide exact long, text, and boolean reads by JSON Pointer.
`json()` returns detached serialized Blue JSON for persistence and diagnostic
adapters; callers receive no mutable reference to the retained exact value.

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
admission supports `.fromNow()`,
`.activation(ActivationPolicy.importFullHistory())`, and
`.activation(ActivationPolicy.importFromFrontier(exactEvidence))`.
Attach-current and passive-snapshot values remain vocabulary for future
occurrence evidence and are rejected at this boundary.

Frontier evidence encodes the exact retained external-order tuple as
`components: [timestamp, timelineId, entryBlueId]`. The tuple must equal one
retained journal entry; replay is strictly after it. Rc.3 has no typed
`EntryHandle` converter, so this is an advanced/provider integration boundary,
not evidence applications should reconstruct from append sequence numbers.

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
effective `Process Embedded` catalog to declare the canonical path. A bound
slot may be absent from authored content, in which case compilation installs
the preliminary managed reference. If content is materialized there, it must
agree exactly with the named target. Every effective concrete occurrence needs
one binding; missing, duplicate, extra, conflicting, or ambiguous bindings are
rejected. Language owns cyclic finalization and complete-proof verification;
the SDK only supplies the authored boundary.

One public Root is sufficient for its reachable active embedded members and
their Timeline sources. Those members remain independently targetable through
their handles. Mark another member as a public Root only when it needs an
independent externally authorized Root lane.

`documents().promotePublicRoot(memberId)` adds an already admitted member to
that Root/source catalog. Promotion is from-now catalog exposure only: it does
not append a Timeline Entry, run a process, change the exact document, create a
revision, or advance an epoch.

`ClosureHandle` exposes the authenticated closure identity,
`documents()` / `document(alias)`, `publicRootAliases()`, and the corresponding
`publicRoots()` handles. When the admission compiler retains authored
evidence—as the static authored API below does—
`authoredDocuments()` and `authoredDocument(alias)` expose the immutable exact
pre-initialization values. `occurrences()` exposes read-only
`ClosureOccurrenceSnapshot` values with source document/path, activation
generation, target document, expected target BlueId, and active state. These
values describe evidence already accepted during admission; they cannot author
or override graph state. Component snapshots, proof objects, and invocation
environments remain outside this handle.

## Static Process Embedded admission

For a bounded all-new graph already present in one authored Root value, the SDK
can discover the complete static closure before admission:

```java
ClosureHandle closure = blue.documents()
        .admitStaticProcessEmbedded(rootYaml);
```

The overload
`admitStaticProcessEmbedded(rootYaml, activationPolicy)` applies an existing
supported `ActivationPolicy` to the resulting closure; the one-argument form
uses `ActivationPolicy.fromNow()`. The policy controls temporal admission of
the all-new closure. It does not select an existing lineage or a historical
version for attachment.

Discovery follows only the effective `Process Embedded` `paths` and
`collectionPaths` catalog compiled from each exact member. A complete inline
value becomes another member. A pure `{blueId: ...}` value is resolved through
the configured `ExactNodeProvider`. Discovery then recurses, coalesces repeated
occurrences of the same exact authored value into one lineage, and delegates
the explicit bindings, public Root, compilation, initialization, proof, and
atomic publication to the ordinary closure-admission path. Every managed
`DocumentId` is the member's exact pre-initialization authored BlueId; any
authored `/documentId` value remains untouched content.

The entire discovered member set must be new. Discovery and provider identity
verification complete before the first managed write. If a pure reference is
unavailable, the SDK throws a typed `CoordinationException` with code
`NEEDS_RESOURCES` and exact `blueId`, `sourceDocumentId`, and `sourcePath`
details; no member is admitted. A host may retain that demand, supply the exact
resource, and retry the same admission. If the provider returns content with a
different BlueId, admission fails with `INVALID_DOCUMENT_IDENTITY`, also before
managed state is written.

This API is deliberately static and all-new. It does not scan arbitrary object
shape for children, attach an existing lineage, choose a historical child
state, reserve a future occurrence, or discover an occurrence first created by
an operation. It also does not infer a post-operation affected closure or add a
dynamic cycle; those cases require a different semantic API.

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

Construct dependent operation calls after earlier work commits.
`on(DocumentHandle)` captures exact target evidence when the call is built, so
a prebuilt later call can become `STALE` after an intervening transition.

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

Under the default legacy identity policy, the draft's exact initial value must
contain a text `/documentId` equal to its supplied stable `DocumentId`. Under
`contentDerivedDocumentIds()`, the supplied `DocumentId` must instead equal the
complete initial value's BlueId; `/documentId` is ordinary untouched content
and may be absent or have any authored shape. In either mode the SDK verifies
owner identity, exact request value, canonical effective `Process Embedded`
paths, and complete result agreement. A single draft may be bound at several
paths to express one stable lineage with multiple occurrences. Every
expectation in one call is sourced from the current operation target; create a
nested new child graph through sequential applied operations, not by assuming
one call can declare edges between drafts. All new heads and topology changes
publish atomically with the parent result; a terminal failure leaves no partial
expansion.

This candidate supports only new `FROM_NOW` lineages. Imported-state evidence
created with `draft.atEpoch(...)` and historical, frontier, attach-current, or
passive operation-result activation fail closed. The SDK never emulates this
lane through legacy child/parent admission.

Put every member that already exists—including every initially known cycle—in
the initial `ManagedClosure`. The draft API adds exact new lineages from the
operation target; it is not a general builder for a new multi-member cyclic
closure with imported history. Managed-draft path preflight currently requires
an independently processable, non-cyclic operation target whose effective
`Process Embedded` catalog does not cross a cyclic-set member. Either condition
is rejected before append.

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

The supported envelope has a positive signed-64-bit timestamp, a routable
Operation Request with nonblank operation and channel and a present request,
and matching text `/timeline/timelineId` and `/actor/accountId`. Later entries
on the same provider Timeline must name the accepted current predecessor and
increase timestamp. `onBehalfOf` requires a provider-backed Mandate resolver
and is rejected by the bundled in-memory profile.

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

For dependent business steps, build and `execute()` each operation only after
the prior result. Several `submit()` calls followed by one drain are appropriate
for intentional append batching or independent provider entries. Across
Timelines, the environment's canonical source order wins; Java submission order
is not a workflow scheduler.

## Results and reads

`EntryDisposition` contains `APPLIED`, `NO_MATCH`, `STALE`, `MIXED`,
`REJECTED`, `NEEDS_RESOURCES`, `GAS_LIMIT_EXCEEDED`,
`PORTABLE_LIMIT_EXCEEDED`, and `BLOCKED`.

One appended entry can affect disconnected closures independently.
`EntryResult.closures()` therefore retains each `ClosureResult`, its committed
`DocumentChange` values, public events, processing statistics, and diagnostic.
The aggregate disposition is `MIXED` when terminal closure dispositions differ.
`ProcessingStats` reports gas, committed transitions, documents opened, exact
document-step order, an elapsed field, and named counters. Current host elapsed
time is populated on aggregate `DrainResult.stats()`; entry and closure elapsed
fields remain zero and are not per-entry latency measurements.

For an applied managed closure, `ClosureResult.managedSurfaceEvidence()`
retains the exact committed processor and host publication delta. Alongside
contract, graph, component, and Channel/subscription transitions,
`operationRouteChanges()` reports deterministic `ADD`, `REMOVE`, or `REPLACE`
changes from the prepared `OperationRouteIndex` reconciliation. Each route
side identifies the managed `DocumentId`, scope, operation, Channel, and
accepted Timeline/actor sources. Rejected, suspended, and rolled-back closures
expose no route changes; replay uses the route delta retained in the durable
publication receipt.

`DocumentHandle.snapshot()` is READY-only and exposes application state,
DocumentId, epoch, BlueId, exact content, and public events. `history()` returns
immutable application-safe revisions. Physical objects, topology generations,
proofs, and storage layout are not part of the normal snapshot.

Branch on `Diagnostic.code()`, not message text. A sequence of entries is not
one global transaction: a committing connected affected closure has one atomic
publication boundary, while disconnected closures and later entries may commit
independently.

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

`auditTimelineEntry(blueId)`, `auditTimeline(timelineId)`, and
`auditTimelineEntries()` return immutable `TimelineEntrySnapshot` values from
the engine's canonical journal, including entries appended through
`rawEngine()`. Each snapshot retains the exact whole entry, Timeline identity,
predecessor identity, operation/channel, timestamp, and global/per-Timeline
sequence numbers.

`auditOperationRoutes(documentId)` returns the immutable compiled Root-scoped
operation surface. Each `OperationRouteSnapshot` carries the operation,
channel, optional exact effective request pattern, and deterministically
ordered accepted Timeline/actor sources. This is a read-only compiler view: it
does not append or process work. Executable repository subtypes of Sequential
Workflow Operation are included; generic handlers and the bare Operation base
are not reported as callable routes.

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
