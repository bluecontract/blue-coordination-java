# Blue Coordination SDK developer guide

This is the canonical application-development guide for the published
`blue.coordination:blue-coordination-java:3.0.0-rc.4` artifact and the
unpublished `3.0.0-rc.6` source profile. It starts with authored Blue documents
and follows them through admission, exact Timeline processing, managed embedded
documents, cycles, later operations, topology expansion, retained managed-epoch
catch-up, results, and failure handling. Sections marked rc.6 do not describe
the Maven Central rc.4 JAR.

Use the application-facing `blue.coordination.sdk` package. The older
`blue.coordination.api.CoordinationEngine` surface is an advanced host and
migration boundary; its plain `inMemory()` factory has different, legacy
acyclic semantics.

## Scope and suitability

`BlueCoordination.inMemory()` is appropriate when an application needs:

- deterministic execution of authored Blue contracts;
- stable managed identities for independently evolving documents;
- exact Timeline journaling and environment-derived routing;
- `Process Embedded` graphs, including bounded cycles;
- atomic publication of one connected affected closure; and
- a compact, local runtime for development or a bounded external pilot.

It is not a database or a production service host. Both candidate profiles are
one JVM, in-memory, and sequential. A process crash loses their stores. Durable
recovery, provider completeness, production authorization and tenant isolation,
parallel/distributed scheduling, durable outbox recovery, and a stable latency
SLA remain outside this candidate. The [Known limitations](../limitations.md)
page owns the published rc.4 boundary; the retained-epoch semantic page lists
the rc.6 source profile's explicit non-goals.

## Install and own the runtime

Use Java 17 or newer. For the published rc.4 artifact, resolve only from Maven
Central:

```groovy
repositories {
    mavenCentral()
}

dependencies {
    implementation 'blue.coordination:blue-coordination-java:3.0.0-rc.4'
}
```

The retained managed-epoch work exists only in the `3.0.0-rc.6` source. That
source is staged, unpublished, non-production, and has no Maven Central install
coordinate. Build it against the invocation-owned immutable Language/Contracts
`3.1.0-rc.23` stage (the published rc.22 baseline plus the additive transition
receipt surface):

```bash
./gradlew --no-daemon --no-build-cache clean releaseCheck \
  -PtestJavaVersion=17 \
  -PblueDependencyMode=immutable-staged-contracts \
  -PblueContractsVersion=3.1.0-rc.23 \
  -PblueContractsRepository=/absolute/path/to/invocation-owned/contracts-repository \
  -PblueContractsManifestSha256=sha256:<64-lowercase-hex>
```

The repository must be closed, immutable, outside the Coordination source tree,
and authenticated by its exact manifest. Maven Local, composite substitution,
a mutable sibling checkout, and remote fallback for `blue.language` are not
valid rc.6 inputs. This command documents the required gate shape; it does not
claim that a gate has run or authorize publication.

For a clean, verified, committed checkout, the
[immutable Coordination handoff](../development/immutable-staged-coordination.md)
exports the rc.6 JAR and metadata to a separate invocation-owned repository and
runs its isolated staged consumer. That handoff remains unpublished evidence,
not a Maven Central installation.

One `BlueCoordination` instance owns its Timelines, documents, drafts, entries,
and results. Close it when the application scope ends:

```java
import blue.coordination.sdk.BlueCoordination;

try (BlueCoordination blue = BlueCoordination.inMemory()) {
    // Register Timelines, admit documents, and process entries here.
}
```

Timeline, document, entry, and draft handles are owner-bound and cannot cross
runtime instances. `ExactBlueValue` is different: it is immutable content
evidence and carries no runtime-owner token. Reuse exact values across owners
only deliberately and only when the environments use compatible exact release
identities; the SDK does not reject that combination as an owner mismatch.

## Mental model

| Concept | Meaning |
| --- | --- |
| Authored document | Exact Blue value containing application state and effective contracts. |
| `DocumentId` | Stable managed lineage across revisions; it is not a state BlueId. |
| BlueId | Identity of one exact immutable value or state. |
| Managed occurrence | One effective `Process Embedded` path from a source lineage to a target lineage. |
| Managed closure | Complete set of initially admitted lineages and occurrence bindings, including cycles. |
| Public Root | Externally authorized entry point whose reachable active embedded Timelines form one source surface. |
| Timeline | Append-only authenticated source. It is not a workflow stage or caller-controlled work queue. |
| Timeline Entry | Exact journaled envelope containing a message such as an Operation Request. |
| Operation call | SDK-built exact Timeline Entry intentionally targeted at one current document revision. |
| Broadcast event | Complete caller-supplied Timeline Entry routed by active Channels without a caller-supplied recipient set. |
| Document draft | Exact evidence for a genuinely new managed lineage created by an operation. |

Three rules prevent most integration mistakes:

1. `DocumentId` identifies continuing history; BlueId identifies one exact
   state.
2. Only effective `Process Embedded.paths` and direct stable-key members under
   `collectionPaths` become separate managed documents. Other nested content
   stays inline.
3. Append stores an exact entry; processing later derives recipients from
   active Channels. The caller never supplies the final recipient set.

## Choose the application path

| Starting point | Admission | Input path |
| --- | --- | --- |
| One document with no managed embedded boundary | `ManagedDocument` | `operations()` or `events()` |
| Several known managed members, shared members, or a cycle | one complete `ManagedClosure` | `operations()` or `events()` |
| Logical operation and intended current document | existing admission | `operations().on(...)` |
| Complete provider-authored Timeline Entry | existing admission | `events().exact(...)` |
| New managed child created by an operation | existing admission plus `ManagedDocumentDraft` | `request.managed(...)` and `expectOccurrence(...)` |
| Existing current or retained managed state installed by an operation (rc.6 source) | existing managed lineage and exact occurrence value | automatic exact matching; `selectManagedEpoch(...)` only for ambiguity |
| Existing source history that must be replayed | top-level admission with an import policy | `importFullHistory()` or `importFromFrontier(...)` |

If all managed members already exist, admit all of them in the initial closure.
Do not pretend an existing lineage is newly created merely to use the draft API.
Conversely, do not invent a future document at admission time if it genuinely
comes into existence only as an operation result.

## Author the documents

An application document normally contains:

- stable business state;
- one or more Timeline Channels describing accepted sources;
- operations or workflows bound to those Channels; and
- a `Process Embedded` contract when selected fields are independent managed
  lineages.

For example, an Order that separately manages Payment and Shipment members can
declare:

```yaml
documentId: order-42
status: draft
contracts:
  embedded:
    type: Process Embedded
    paths:
      - /payment
      - /shipment
  salesChannel:
    type: Coordination/Timeline Channel
    timeline:
      type: MyOS/MyOS Timeline
      timelineId: orders/42/sales
    actor:
      type: MyOS/Principal Actor
      accountId: alice
  confirm:
    type: Coordination/Sequential Workflow Operation
    channel: salesChannel
    request: {}
    steps:
      - type: Coordination/Compute
        do:
          - $appendChange: {op: replace, path: /status, val: confirmed}
          - $return: true
```

The corresponding Timeline handle must use exactly `orders/42/sales` and
`alice`. Operation and Channel names passed to the SDK must exactly match the
authored catalog.

### External and embedded Channels

A `Coordination/Timeline Channel` selects matching external Timeline Entries.
An `Embedded Node Channel` reacts to an exact event emitted by a managed member
at one occurrence path:

```yaml
fromPayment:
  type: Embedded Node Channel
  sourcePath: /payment
  event:
    type: Coordination/Event
    kind: Payment Captured
recordPayment:
  type: Coordination/Sequential Workflow
  channel: fromPayment
  event:
    type: Coordination/Event
    kind: Payment Captured
  steps:
    - type: Coordination/Compute
      do:
        - $appendChange: {op: replace, path: /paid, val: true}
        - $return: true
```

This is how a containing document observes a child's public event. The child is
processed from its own exact state and receives no ambient parent identity or
containing path. Do not replace Embedded Node Channels with a second caller-
maintained reverse-containment graph.

### Request values

Use `requestYaml(...)` for one ordinary authored request. Use the structured
builder's `exact(field, value)` when an existing exact value must be preserved
without YAML reserialization. Reserve `managed(field, draft)` for a genuinely
new lineage and pair it with every required `expectOccurrence(...)` path.

An occurrence path is a canonical JSON Pointer. It cannot be the empty Root
pointer. `collectionPaths` selects only direct stable-key members; it does not
give list positions stable lineage identity or authorize arbitrary collection
reshaping.

## Use case 1: process an existing document and exact entry

This path applies when the application already has an authored document or a
complete set of authored members and receives a complete Timeline Entry from a
provider.

The snippets below keep authored YAML in named variables for readability. The
complete compile-tested flow is in
[`SdkDeveloperGuideTest`](../../src/consumerTest/java/blue/coordination/consumer/SdkDeveloperGuideTest.java),
with the Order, Payment, Shipment, Route, Receipt, and provider-entry documents
kept as readable files under
[`src/consumerTest/resources/developer-guide`](../../src/consumerTest/resources/developer-guide/order.yaml).

### Admit an ordinary document

When there is no effective managed embedded boundary:

```java
import blue.coordination.api.DocumentId;
import blue.coordination.sdk.DocumentHandle;
import blue.coordination.sdk.ManagedDocument;

DocumentHandle order = blue.documents().admit(
        ManagedDocument.yaml(DocumentId.of("order-42"), orderYaml)
                .publicRoot()
                .fromNow());
```

`publicRoot()` explicitly authorizes external routing. `fromNow()` establishes
the document at the current journal frontier; entries appended before this
admission are not replayed.

### Admit embedded members and cycles

If the document has any effective managed members, enumerate every independent
lineage and every effective occurrence in one complete closure:

```java
import blue.coordination.api.DocumentId;
import blue.coordination.sdk.ClosureHandle;
import blue.coordination.sdk.ManagedClosure;

DocumentId orderId = DocumentId.of("order-42");
DocumentId paymentId = DocumentId.of("payment-42");
DocumentId shipmentId = DocumentId.of("shipment-42");
DocumentId routeId = DocumentId.of("route-42");

ClosureHandle closure = blue.documents().admit(
        ManagedClosure.builder()
                .document("order", orderId, orderYaml)
                .document("payment", paymentId, paymentYaml)
                .document("shipment", shipmentId, shipmentYaml)
                .document("route", routeId, routeYaml)
                .bindOccurrence("order", "/payment", "payment")
                .bindOccurrence("order", "/shipment", "shipment")
                .bindOccurrence("shipment", "/route", "route")
                .bindOccurrence("route", "/shipment", "shipment")
                .publicRoot("order")
                .fromNow()
                .build());
```

The last two bindings create a Shipment-Route cycle, reachable from the
acyclic Order Root. Do not recursively serialize an infinite object and do not
calculate SCCs, cyclic BlueIds, components, or proofs. The SDK validates the
authored occurrence catalog and exact target agreement; pinned
Language/Contracts code derives and verifies the finite cyclic closure.

The high-level SDK does not infer stable lineage identities by walking one
arbitrary nested object. The caller supplies one member and `DocumentId` per
independently managed lineage plus every source/path/target binding. The same
target alias may appear at several paths when several occurrences share one
lineage. Two different `DocumentId` values stay independent even if their exact
content is equal.

Your original root may already contain the finite exact Payment or Shipment at
the selected path, or the selected slot may be absent. Supply the independently
managed member separately in both cases. A materialized slot must agree exactly
with the bound target; an absent slot is replaced by the verified managed
reference during closure compilation. There is deliberately no automatic
recursive import that guesses stable lineage identities. In a cycle, use finite
member documents plus occurrence bindings rather than trying to inline an
infinitely recursive object.

One public Order Root is enough for its reachable active embedded Timelines.
Mark Payment or Shipment as additional public Roots only when they require
independent externally authorized Root lanes.

### Register the source and execute the provider entry

Register the exact Timeline/actor pair represented by the envelope:

```java
var sales = blue.timelines().register("orders/42/sales", "alice");

var exactEntry = blue.values().yaml("""
        type: Coordination/Timeline Entry
        timeline:
          type: MyOS/MyOS Timeline
          timelineId: orders/42/sales
        timestamp: 2100000000000001
        actor:
          type: MyOS/Principal Actor
          accountId: alice
        message:
          type: Coordination/Operation Request
          operation: confirm
          channel: salesChannel
          request: {}
        """);

var result = blue.events()
        .from(sales)
        .exact(exactEntry)
        .execute();
```

The envelope must resolve to a routable Timeline Entry with a positive 64-bit
timestamp, matching `timelineId` and `accountId`, a nonblank operation and
channel, and a present request. On later entries from the same provider
Timeline, the declared `prevEntry` must name the accepted current head and the
timestamp must increase. `onBehalfOf` is rejected by this in-memory profile
because it has no provider-backed Mandate resolver. A selected Timeline/actor
mismatch is an `IllegalArgumentException` before append.

`events()` is deliberately broadcast/environment routing. A valid entry that
matches no active Channel returns `NO_MATCH`; this is terminal and is not an
engine exception.

If the application has logical operation data rather than a complete provider
envelope, use the targeted operation API instead. It constructs the exact
Timeline Entry and journal evidence for the selected source:

```java
var result = blue.operations()
        .on(closure.document("order"))
        .from(sales)
        .call("confirm")
        .through("salesChannel")
        .execute();
```

### Inspect the result and coherent state

Always inspect the terminal disposition:

```java
if (!result.applied()) {
    if (result.diagnostic().present()) {
        System.err.println(result.diagnostic().code());
        System.err.println(result.diagnostic().message());
        System.err.println(result.diagnostic().details());
    }
    throw new IllegalStateException(
            "Entry finished as " + result.disposition());
}

var orderAfter = closure.document("order").snapshot();
System.out.println(orderAfter.epoch());
System.out.println(orderAfter.blueId());
System.out.println(orderAfter.textAt("/status"));
```

`DocumentHandle.snapshot()` returns only application-readable `READY` state.
`history()` returns immutable revisions. Use `blue.advanced()` only for explicit
operational inspection of non-READY state or retained occurrence metadata.

## Use case 2: evolve an initial closure through several Timelines

This path applies when all initial lineages are known, different actors later
operate on different members, and operations may create more managed members.

### Establish the initial graph

Use the complete Order/Payment/Shipment/Route closure above, then keep its
handles:

```java
var order = closure.document("order");
var payment = closure.document("payment");
var shipment = closure.document("shipment");

var sales = blue.timelines().register("orders/42/sales", "alice");
var billing = blue.timelines().register("orders/42/billing", "bob");
var logistics = blue.timelines().register("orders/42/logistics", "carol");
var receiptWorker = blue.timelines().register("receipts/42/worker", "dave");
```

Payment, Shipment, and the future Receipt documents must declare Channels whose
Timeline and actor values match the handles used below.

### Execute causally dependent operations in order

```java
requireApplied(blue.operations()
        .on(order)
        .from(sales)
        .call("confirm")
        .through("salesChannel")
        .execute());

requireApplied(blue.operations()
        .on(order)
        .from(sales)
        .call("complete")
        .through("salesChannel")
        .execute());

requireApplied(blue.operations()
        .on(payment)
        .from(billing)
        .call("capture")
        .through("billingChannel")
        .requestYaml("amount: 12500")
        .execute());

requireApplied(blue.operations()
        .on(shipment)
        .from(logistics)
        .call("dispatch")
        .through("logisticsChannel")
        .requestYaml("carrier: DHL")
        .execute());
```

`operations().on(handle)` captures the document's exact current target. Build
each dependent call after the previous result commits. Prebuilding several
calls against the same earlier state can make later calls terminal `STALE`.

### Create and embed a new managed document

Suppose the acyclic Payment member declares `/receipts` under
`Process Embedded.collectionPaths`, and its `createReceipt` operation writes
the exact request field to `/receipts/primary`:

```yaml
receipts: {}
contracts:
  embedded:
    type: Process Embedded
    collectionPaths:
      - /receipts
  createReceipt:
    type: Coordination/Sequential Workflow Operation
    channel: billingChannel
    request:
      receipt: {}
    steps:
      - type: Coordination/Compute
        do:
          - $appendChange:
              op: add
              path: /receipts/primary
              val: {$binding: event/message/request/receipt}
          - $return: true
```

Create an exact draft, place that same draft in the request, and declare every
effective result occurrence:

```java
import blue.coordination.sdk.ActivationPolicy;

DocumentId receiptId = DocumentId.of("receipt-42-primary");
var receiptDraft = blue.documents().draft(
        receiptId,
        blue.values().yaml(receiptYaml));

requireApplied(blue.operations()
        .on(payment)
        .from(billing)
        .call("createReceipt")
        .through("billingChannel")
        .request(request -> request.managed("receipt", receiptDraft))
        .expectOccurrence("/receipts/primary", receiptDraft)
        .activation(ActivationPolicy.fromNow())
        .execute());
```

The runtime verifies all of the following before publishing the expansion:

- the draft belongs to this `BlueCoordination` instance;
- its `DocumentId` is a new managed lineage;
- the draft's exact initial value contains a text `/documentId` equal to that
  `DocumentId`;
- the request contains its exact initial value;
- every declared path is canonical and effective under `Process Embedded`;
- the operation result installs that exact value at every expected path; and
- there are no missing, additional, duplicate, or ambiguous bindings.

One draft may be expected at several paths to represent several occurrences of
the same stable lineage. Use separate drafts and `DocumentId` values for
independent children.

Every expectation in one call is an edge from that call's current operation
target to a new draft. The current API has no source-draft alias with which one
call could declare a new child-of-a-new-child edge. To grow a nested acyclic
topology, first attach the new parent, wait for `APPLIED`, obtain its
`DocumentHandle`, and then execute another managed-draft operation on that
handle. Put a topology that must already contain several interdependent members
or a cycle in the initial `ManagedClosure` instead.

This authored-expansion limitation also applies inside retained catch-up:
catch-up may resolve and propagate an already-existing managed lineage at a
nested path, but it may not create a newly authored nested lineage.

```java
DocumentHandle newParent = blue.documents().require(parentId);
newParent = blue.documents().promotePublicRoot(parentId);
ManagedDocumentDraft grandchild = blue.documents().draft(
        grandchildId, blue.values().yaml(grandchildYaml));

requireApplied(blue.operations()
        .on(newParent)
        .from(parentTimeline)
        .call("createChild")
        .through("ownerChannel")
        .request(request -> request.managed("child", grandchild))
        .expectOccurrence("/children/primary", grandchild)
        .activation(ActivationPolicy.fromNow())
        .execute());
```

The public-SDK acceptance test
[`SdkManagedDraftAcceptanceTest.appliedManagedParentCanCreateAManagedGrandchild`](../../src/test/java/blue/coordination/sdk/SdkManagedDraftAcceptanceTest.java)
proves both sequential publications and exact parent/child occurrence heads.

The parent result, new document head, occurrences, routes, and connected
topology publish atomically. A terminal failure leaves no partial child or
partial topology expansion.

The draft carries the exact authored initial value, not a promise that the
child's READY BlueId will remain identical. Epoch-zero initialization can
legitimately transform that value or emit lifecycle events; the stable
`DocumentId` remains the lineage identity.

Retrieve the new lineage after the managed expansion. An operation-created
member is managed but is not automatically a public Root. Promote it before
addressing one of its operations directly. Promotion changes only the Root
admission set: it does not append a Timeline Entry, advance the member epoch,
or change its exact document head.

```java
var receipt = blue.documents().require(receiptId);
receipt = blue.documents().promotePublicRoot(receiptId);

requireApplied(blue.operations()
        .on(receipt)
        .from(receiptWorker)
        .call("markSent")
        .through("workerChannel")
        .execute());
```

This ordering is part of the current forward-only profile. A directly selected
embedded member without typed incoming demand can advance without opening its
ancestors. The current scalar graph-generation contract does not subsequently
merge that advanced forward closure back into a wider ancestor invocation. Run
any remaining ancestor operation before independently advancing a descendant,
or model the required upstream participation with explicit typed demand.
Advancing the descendant also does not mutate the ancestor's exact embedded
value behind its back: that occurrence remains the version the ancestor last
published until an authorized ancestor transition replaces it.

Use a helper that reports diagnostics instead of assuming success:

```java
private static void requireApplied(EntryResult result) {
    if (!result.applied()) {
        throw new IllegalStateException(
                result.disposition() + ": " + result.diagnostic());
    }
}
```

Operation-produced draft admission in published rc.4 supports only a genuinely
new `FROM_NOW` lineage. `draft.atEpoch(...)` and full-history, frontier,
attach-current, or passive draft activation fail closed. The rc.6 retained
profile does not weaken that draft rule; it adds a different path for an exact
value already proven in managed lineage history. If a new group must already be
cyclic at birth, include the complete group in the initial `ManagedClosure`;
the draft API is not a general dynamic cyclic closure builder. Draft-path
preflight also requires an independently processable, non-cyclic operation
target whose own effective `Process Embedded` catalog does not cross a
cyclic-set member. Create new members from a cycle-free owning member, as
Payment does above. A cyclic-set target—or an otherwise acyclic target whose
selected catalog crosses into the Shipment-Route cycle—is rejected before
append.

### Remove and reactivate an existing occurrence

An authored operation may remove an active managed path. The runtime preserves
the target history and records an inactive same-lineage occurrence successor;
it does not delete the managed document. A later operation can re-add that same
lineage by placing its current exact value back at the declared path:

```java
requireApplied(blue.operations()
        .on(container)
        .from(control)
        .call("readd")
        .through("controlChannel")
        .request(request -> request.exact("peer", peer.exact()))
        .execute());
```

This is reactivation of an already known lineage, not draft creation. Existing
cycle detach and later re-add are supported and receive freshly authenticated
cyclic identities. Same-invocation remove-then-re-add remains unsupported in
the published rc.4 profile. In the rc.6 source profile, a later occurrence
generation may be reattached or retargeted to another proven managed lineage;
its catch-up plan and cursor remain distinct from the retired generation.
Operational tooling can inspect target lineage, activation generation, and
active status through
`blue.advanced().auditManagedOccurrence(sourceId, path)`.

### Attach an existing managed epoch (rc.6 source profile)

Pass the exact existing state through the ordinary operation request. Do not
construct a `ManagedDocumentDraft` and do not push a hand-built event list into
the consumer:

```java
ExactBlueValue retained = source.history()
        .get(Math.toIntExact(retainedEpoch)).after();

EntryResult attached = blue.operations()
        .on(consumer)
        .from(ownerTimeline)
        .call("attach")
        .through("ownerChannel")
        .request(request -> request.exact("child", retained))
        .execute();
```

The automatic resolver compares the exact value with the indexed lineage
history. A current match opens no plan. An authored-initial match starts at
sentinel epoch `-1` and applies initialization plus every retained successor;
an epoch-zero match applies epochs `1..frontier`; and a retained epoch `e`
applies `e + 1..frontier`. The source's original initialization, Timeline
processing, history, and receipts are read-only throughout.

The authored `-1` value is only a selector/cursor position before the first
source transition. It is never a durable `ManagedEpochReceipt` epoch: the first
real receipt is epoch `0`, and receipt audit accepts only non-negative epochs.

If the same BlueId occurs at several non-current source epochs, select the
intended occurrence position explicitly:

```java
EntryResult attached = blue.operations()
        .on(consumer)
        .from(ownerTimeline)
        .call("attach")
        .through("ownerChannel")
        .request(request -> request.exact("child", retained))
        .selectManagedEpoch(
                "/child", source.id(), retainedEpoch, retained.blueId())
        .execute();
```

`ManagedEpochSelector` binds source `DocumentId`, epoch, expected BlueId, and
target path to the captured operation target. It resolves ambiguity only; a
missing lineage, wrong epoch/BlueId, unused path, or conflicting selector fails
closed. Several BlueIds needed by one closure are resolved independently and
produce one occurrence plan per path/generation. If complete referenced content
is missing, retain every typed demand, register the verified immutable values
through the exact-node provider, and retry the same work. Do not push events,
occurrence bindings, or a parent document patch directly into the process.

When `submit()` plus a bounded drain stops after the attachment transition, the
entry may be `APPLIED` while the consumer is not yet READY. Its committed head
retains the new graph, its previous ready head remains the ordinary read
surface, and one barrier aggregates all plans introduced by that exact cause.
Drain to quiescence, or continue with deterministic `DrainBudget` calls, before
expecting `consumer.snapshot()` to expose the caught-up state. Source epochs
committed while the barrier remains active extend its required frontier and
cannot be overtaken by a later direct consumer entry.

The existing scheduler retains a deterministic lane turn under repeated small
budgets such as `DrainBudget(1, 1)`: continuing finite source work and its
catch-up cursor both advance, direct consumer work still cannot overtake the
barrier, and the consumer eventually becomes READY after the source stops. A
source that never stops leaves the consumer truthfully CATCHING_UP.

See [Retained managed-epoch catch-up](../semantics/retained-managed-epoch-catch-up.md)
for the receipt, ordering, failure, cycle, and audit invariants.

## Ordering, `execute()`, and batching

A Timeline is an append-only source, not a workflow stage. Calling Sales,
Billing, and Logistics in a Java sequence does not make those Timeline types a
workflow definition. The authored contracts and accepted source evidence remain
authoritative.

Every operation and event call is single-use:

- `execute()` appends and drains canonically through that entry. It includes
  older eligible work and cannot overtake it.
- `submit()` validates and appends only. No document state changes until a
  later drain.

The explicit split is:

```java
EntryHandle submitted = call.submit();

// State is unchanged here.

DrainResult drained = blue.processing().drain();
EntryResult result = drained.entry(submitted);
```

Use sequential `execute()` calls when later work depends on an earlier state or
newly created document. Use several `submit()` calls followed by one `drain()`
only when append/process separation is intentional and the entries do not rely
on stale exact targets.

Across provider Timelines, a batch drain uses canonical external source order,
not the order in which Java happened to call `submit()`. Within one Timeline,
the predecessor chain and monotonically increasing timestamps define its
accepted order. For complete provider entries, the canonical cross-Timeline key
is timestamp, Timeline ID, then entry BlueId. SDK-built local operation calls
receive monotonic engine timestamps, so sequential `execute()` calls retain
their causal call order.

## Admission and history policies

Choose temporal semantics explicitly:

| Policy | Meaning | Top-level admission | Operation-created draft |
| --- | --- | --- | --- |
| `fromNow()` | Begin at admission/attachment; do not replay earlier entries. | supported | supported and required |
| `importFullHistory()` | Start eligibility at the retained full-history frontier; a later drain processes eligible retained entries. | supported | unsupported in rc.4 |
| `importFromFrontier(evidence)` | Start eligibility strictly after verified exact frontier evidence; a later drain processes eligible retained entries. | supported | unsupported in rc.4 |
| `attachCurrentState()` | Attach a lineage proven current through the cutoff. | rejected at current high-level admission boundary | unsupported |
| `passiveSnapshot()` | Retain exact evidence without a live process. | rejected at current high-level admission boundary | unsupported |

This table describes document/closure and new-draft admission policy. The rc.6
operation path for an existing exact managed state is position-based catch-up,
not a new `ActivationPolicy` and not Timeline-history replay.

One `ManagedClosure` selects one activation policy for every initially admitted
member. `importFromFrontier(...)` accepts exact provider frontier evidence in
this shape:

```java
ExactBlueValue frontier = blue.values().yaml("""
        components:
          - 2100000000000001
          - orders/42/sales
          - <exact-frontier-entry-blue-id>
        """);

var imported = ManagedDocument.yaml(orderId, orderYaml)
        .publicRoot()
        .activation(ActivationPolicy.importFromFrontier(frontier));
```

The three components are the retained entry's signed-64-bit timestamp,
Timeline ID, and exact entry BlueId—the canonical external order tuple. The
tuple must match retained journal evidence exactly, and replay is strictly
after it. A fabricated or non-retained tuple is rejected. The rc.4 SDK has no
typed `EntryHandle`-to-frontier converter, so treat this as an advanced/provider
integration unless the provider already supplies and persists that evidence;
do not derive it from Java append order or sequence numbers.

Contracts closure admission publishes the initialized epoch-zero documents as
`READY` and returns; it does not run a processing drain inside `admit(...)`.
With a history-import policy, call `processing().drain()` afterward to process
eligible retained entries. Consequently, a snapshot taken immediately after
admission can show the initialized state before that later drain applies
history. Only history still eligible in the current in-memory feeder can be
processed; an entry already advanced past the environment's completed frontier
is not resurrected by admitting another Root.

For `fromNow()`, admit the document or closure before appending the first entry
that it should observe. If provider entries already exist and must be replayed,
use a supported import policy and supply the required completeness/frontier
evidence. Do not append historical work first and expect `fromNow()` to replay
it.

Initialization is a processor-owned epoch-zero revision with no fabricated
source Timeline Entry. Later external revisions retain their real source entry.
The executable full-history and exact-frontier lifecycle checks are in
[`SdkAcceptanceTest`](../../src/test/java/blue/coordination/sdk/SdkAcceptanceTest.java).

## Results, diagnostics, and reads

Programmer/evidence problems can fail before append with
`IllegalArgumentException` or `UnsupportedOperationException`: examples include
foreign-owner handles, incomplete managed-draft request/expectation pairs, and
unsupported draft activation. These failures consume no journal sequence.
After a valid append, processing outcomes are represented by `EntryResult`.

`EntryDisposition` is the first decision point:

| Disposition | Application meaning |
| --- | --- |
| `APPLIED` | The selected work committed successfully. |
| `NO_MATCH` | A valid broadcast had no accepting active Channel. |
| `STALE` | Exact target or publication evidence changed before commit. Re-read before deciding whether to retry. |
| `REJECTED` | Input, target, operation, Channel, or profile evidence is terminally invalid. |
| `NEEDS_RESOURCES` | Processing cannot yet prove a required resource or frontier; the lane remains pending. |
| `GAS_LIMIT_EXCEEDED` / `PORTABLE_LIMIT_EXCEEDED` | A deterministic execution bound terminated the work. |
| `BLOCKED` | Processing did not reach a terminal result for the entry. |
| `MIXED` | One entry produced different terminal outcomes across disconnected closures. Inspect each closure. |

Branch on `Diagnostic.code()`, not message text. Preserve the immutable details
in redacted operational logs. Common targeted-operation codes include
`TARGET_DOCUMENT_NOT_FOUND`, `OPERATION_NOT_FOUND`,
`TARGET_CHANNEL_NOT_FOUND`, and `TARGET_CHANNEL_SOURCE_MISMATCH`.

`EntryResult.closures()` preserves independent closure outcomes.
`ClosureResult.changes()` identifies committed document changes,
`EntryResult.publicEvents()` contains exact aggregate public events, and
`stats()` reports gas, transition counts, documents opened, canonical
document-step order, an elapsed field, and named counters.
Current host elapsed time is populated on aggregate `DrainResult.stats()`;
entry/closure elapsed fields remain zero and must not be treated as per-entry
latency measurements.

A suspended ordinary closure exposes all exact resources through
`ClosureResult.resourceDemands()` and its bounded retry progress through
`automaticRetryCount()`. Managed demands include the optional typed
`managedResolutionStatus()` plus display-only diagnostic. Persist every demand
when one closure needs several BlueIds; do not treat the first satisfied value
as permission to publish a partial result.

In the rc.6 source profile, `DrainResult.managedEpochApplications()` contains
the exact application receipts newly committed by that drain.
`managedEpochApplicationAttempts()` also exposes non-committing atomic attempts
and response-loss replays, with their exact work and Contracts attempt result.
For a suspended automatic occurrence-resolution attempt, persist
`automaticRetryCount()` plus every `managedOccurrenceResolutionIssues()` row.
Join each issue to the processor resource demand by `demandIdentity()` and
branch on its closed `ResolutionStatus`; `diagnostic()` is display text only.

Use:

- `snapshot()` for current READY application state;
- `history()` for immutable revisions and their source/cause evidence;
- `values().yaml(...)` for immutable exact request or entry values; and
- `advanced()` only when explicit host diagnostics are required.

During rc.6 retained catch-up, `snapshot()` continues to return the last READY
head. Use `advanced().auditDocument(id)` to inspect a newer committed head and
`auditManagedDocumentReadiness(id)` to compare committed and ready epochs,
status, waiting diagnostics, and active barrier identities. The remaining
read-only methods inspect source receipts, occurrence plans, barriers, due work,
and committed application receipts:

```java
var readiness = blue.advanced()
        .auditManagedDocumentReadiness(consumer.id())
        .orElseThrow();
var plans = blue.advanced().auditManagedCatchUpPlans(consumer.id());
var sourceReceipts = blue.advanced().auditManagedEpochs(source.id());
```

Use identity-specific `auditManagedEpochReceipt(...)`,
`auditManagedCatchUpPlan(...)`, `auditManagedCatchUpBarrier(...)`,
`auditManagedEpochApplicationWork(...)`, and
`auditManagedEpochApplicationReceipt(...)` when correlating an operational
trace. These calls do not mutate or repair state.

`DocumentSnapshot.publicEvents()` contains the current/latest revision's public
events, not a concatenation of every event in history. Read revision histories
when the application needs all retained transition events.

## Atomicity, retries, and process failure

Do not describe a sequence of several Timeline Entries as one global
transaction. Append and processing have separate boundaries, and disconnected
closures can commit independently.

The stronger guarantee is narrower: one committing connected affected closure
is built off-store, CAS-fenced by exact heads and topology generations, and
published in one copy-on-write swap. New managed-child expansion uses that same
boundary. A stale fence or pre-publication failure exposes none of the staged
changes.

Receipts and commit companions prevent the engine from re-executing an already
committed delivery when it resumes or reconciles the same retained journal
entry. This is not an application-level idempotency key for a newly built
targeted call: rebuilding `operations().on(...)` creates another Timeline Entry
with another timestamp and can apply the business operation again.

For rc.6 managed catch-up, one successful application transaction publishes the
consumer revision, complete receipt, occurrence cursor, plan/barrier state, and
`ManagedEpochApplicationReceipt` together. A non-committing processor result
publishes none of them and leaves the same work retryable. Missing source epoch
evidence becomes `WAITING_FOR_HISTORY`; missing or mismatched typed transition
evidence becomes `BLOCKED` before PROCESS. In both cases the cursor and heads
stay unchanged and unrelated lanes may continue.

A suspended processor attempt can carry several exact-content demands. Retain
the complete typed set and bounded automatic retry count; resume the same work
only after required exact values are available. Ambiguous lineage/epoch,
unproven history, explicit-state mismatch, and invalid authored content are
fail-closed statuses, not instructions to upload arbitrary bytes.

A waiting or blocked sibling in the same consumer/barrier gates newly due work
for that consumer so it cannot overtake the missing evidence; other consumers
remain independently schedulable. When evidence is repaired, the exact
registered-but-unapplied work identity is requeued by restoring both pending
and due indexes without replacing its immutable audit record. Contradictory
index state fails closed.

Occurrence retirement cancels a non-complete (including blocked) plan for that
generation. A completed plan snapshot is immutable audit evidence and remains
unchanged.

In the proven finite two-member cycle, the distinct source member may receive
the shared cyclic representation at the same local epoch only when the
eventless Contracts result and commit companion authenticate that exact update.
This updates current component/continuation evidence without rewriting source
revisions, receipts, or events. It is not authority over arbitrary indirect
peers: a merge with an already cyclic multi-member source component fails
closed when it would require a source epoch to advance or be reinterpreted. A
direct target, eventful transition, missing/malformed proof, or larger unproven
merge rejects the publication atomically.

If the transaction commits immediately before route publication loses its
response, the application and closure publication receipts are authoritative.
Retry or same-engine control-plane reconstruction repairs the route projection
without a second PROCESS call. This still does not survive destruction of the
in-memory stores.

Retry only after inspecting the disposition and diagnostic. For `STALE`,
re-read the current handle/state and intentionally rebuild the operation if the
business intent is still valid. Terminal validation and gas failures should not
be blindly retried. If `execute()` throws after an ambiguous timeout or lost
response, do not automatically rebuild the call; reconcile application state or
use an application-owned idempotency mechanism first. `submit()` gives the
caller an `EntryHandle` before processing and therefore makes the append/process
boundary explicit, while resubmitting the identical complete provider entry is
journal-idempotent by its exact BlueId. Neither mechanism supplies
fresh-process recovery in this in-memory candidate.

These guarantees are in-memory. They do not survive a process crash without a
future durable adapter that persists the complete typed journal, document,
topology, barrier, receipt, and provider-completeness state.

## Application testing checklist

For each workflow, test through `blue.coordination.sdk`:

1. ordinary admission or complete closure admission;
2. every effective `Process Embedded` path and shared-lineage occurrence;
3. cycle admission and a terminating cycle transition when cycles are used;
4. each Timeline/actor/Channel match and mismatch;
5. targeted operation success, missing target, and stale target behavior;
6. exact external entry success and terminal `NO_MATCH`;
7. `submit()` state-before-drain and `execute()` parity where batching matters;
8. dynamic draft success plus missing/extra/wrong-path rollback cases;
9. for rc.6 retained attachment, authored-initial/epoch-zero/retained/current
   selection, repeated-BlueId selector ambiguity, plan/barrier progression,
   committed-versus-ready reads, live extension, and duplicate event
   occurrences;
10. disposition, diagnostic code, changed documents, public events, and READY
   snapshots; and
11. shutdown/recreation assumptions, making the in-memory durability boundary
    explicit.

When contributing tests to this repository, every `@Test` contains exactly one
meaningful lowercase `// given`, `// when`, `// then` sequence. The complete
project gate is described in [Build and test](../development/build-and-test.md).

## Production-readiness checklist

Before using the runtime beyond a bounded pilot, answer all of these explicitly:

- Where are the journal, document heads, topology inventory, receipts, and
  outbox persisted?
- How is provider completeness and predecessor evidence proven after restart?
- Who resolves Mandates and exact source-time authorization?
- How are tenant isolation, credentials, redaction, and audit retention
  enforced?
- How are blocked lanes, resource needs, backpressure, and retries observed?
- What recovery test proves no duplicate processing after a process crash?
- What workload-specific latency and gas limits have been measured?

The bundled in-memory runtime does not answer those production-host questions.

## Continue reading

- [Public API reference](../reference/public-api.md) for the published rc.4 and
  shared signatures/result vocabulary; the retained-epoch page lists the rc.6
  additions.
- [Managed Process Embedded documents](../semantics/process-embedded-documents.md)
  for lineage, occurrence, cycle, and publication semantics.
- [Historical catch-up](../semantics/historical-catch-up.md) for frontier and
  replay rules.
- [Retained managed-epoch catch-up](../semantics/retained-managed-epoch-catch-up.md)
  for rc.6 operation-triggered source-epoch plans, receipts, and readiness.
- [Identity and revisions](../semantics/identity-and-revisions.md) for
  `DocumentId`, BlueId, epochs, and revision history.
- [Failure and retry model](../operations/failure-model.md) for commit and
  recovery boundaries.
- [Compact engine architecture](../architecture/compact-engine.md) for the
  internal data flow without application-only details.
- [SDK migration and ownership ledger](../reference/sdk-migration-and-ownership.md)
  when replacing a low-level or 2.x integration.
- [Known limitations](../limitations.md) for the exact rc.4 non-claims.

The two principal walkthroughs in this guide are executable as the built-JAR-only
[`SdkDeveloperGuideTest`](../../src/consumerTest/java/blue/coordination/consumer/SdkDeveloperGuideTest.java).
The broader public-SDK evidence is `SdkAcceptanceTest` for ordinary, targeted,
broadcast, and cyclic behavior and `SdkManagedDraftAcceptanceTest` for
operation-produced managed lineages; both are linked from the
[documentation index](../README.md).
