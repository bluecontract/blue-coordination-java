# Document execution instances

Status: local integration prototype. Native independent retirement/start,
instance fencing, exact archived history, rooted/source replay, original SDK
reconciliation, catalog roles and complete retained-consumer application have
focused source review with material findings closed. Aggregate routes have an
explicit post-retirement boundary. Full native clean-build qualification passed on 2026-09-21 (see below).
Candidate export and actual MyOS/JPA qualification are separate host steps.

## Identity and authority

A Coordination storage `Address` is the account execution domain. Independent
sessions and actual multi-owner publications coexist inside it. A
`DocumentInstanceRef(documentId, instanceId)` identifies an execution separately
from semantic DocumentId and exact Blue content. Neither instance IDs nor host
account/session IDs enter authored content or canonical processor receipts.

The logical storage path assigns the first execution a deterministic account-local
`initial/<digest-of-document-id>` identity. `INSTANCE_BINDING` is selected per
semantic document and retains positive tombstone revisions. `INSTANCE_IDENTITY`
retains the original document association and prevents instance reuse, including
reuse for another document. The internal ledger's start/retire methods are not
public lifecycle authorization: topology, supported history basis, pending work
and host-control conditions must be established by the runtime lifecycle caller.

A selected current session adds its exact instance binding to the detached
publication conditions. Selection of B does not read A's binding or a shared
account generation. Retained history checks only its original immutable instance
association; it does not require that instance to remain active.

## Exact archived positions

`RootedCoordinationStorage.LogicalScope.instancePosition(documentId)` captures a
`DocumentInstancePosition(instance, epoch, invocationIdentity)` for the current
library-produced publication. A position captured during an uncommitted attempt
becomes durable only if that attempt publishes successfully.

Every actual logical publication retains its complete session image in
`INSTANCE_HISTORY`, using the existing authenticated immutable object store.
The association includes instance, semantic document, numbered epoch and exact
canonical invocation identity. Cold reads authenticate all these fields and the
retained session/view. Callers cannot relabel arbitrary session bytes by
constructing a position.

Two reads deliberately have different roles:

- `retainedRevision(position)` returns the original numbered revision and its
  original canonical receipt. Same-epoch checkpoint representation changes do
  not rewrite this revision.
- `retainedRepresentation(position)` returns the exact checkpoint representation
  at that invocation, including verified cyclic member evidence. Two positions
  can share an epoch but have different exact representations.

These reads neither follow today's head nor acquire its active-binding condition.
Old numbered revisions and old invocation representations therefore remain
stable when a later head is published. Immutable bytes and canonical receipt
payloads may be shared; the persisted instance association supplies authority.

## Original publication validation and consumption

Logical admission and closure receipts retain a native `INSTANCE_PUBLICATION`
association with each original owner's authenticated archived position. Cold
receipt validation opens those original sessions, including numbered revisions,
inside the protected attempt. Missing associations or lazy artifact failures
retire the attempt. Ordinary re-admission remains idempotent. This association
preserves the original canonical publication. A separate native execution receipt
and archived owner association, keyed by observer instance plus canonical
publication identity, records actual repeated execution. Original canonical rows
are never overwritten to make a new instance eligible. The current rooted stage
path can execute the same accepted entry for A2, then exhaust it after cold reopen.

Numbered receipt rows and their head cursors are instance-specific. Shared
canonical receipt identities retain a native original-instance membership. Start
copies the authenticated numbered prefix and exact archived representation; it
does not append those receipts as if representation-only updates never happened.

SDK admission replay checks each original member's own retirement marker. A
still-active original member keeps its ordinary live handle/current state. A
retired member returns the original archived admission outcome, history and
authored metadata. That historical handle cannot select a replacement for
processing or operation submission. Explicit current document lookup returns the
new active instance. Original closure execution/invocation reads keep selecting
the original canonical publication.

`processing().recordedResult(instance, entryBlueId)` reads a native observer's
recorded rooted-stage outcome; `originalResult(entryBlueId)` reads the original
command observer's recorded outcome. These reads do not rerun zero-attempt
diagnostics against today's head. Targeted submission records its original
instance separately from input eligibility. Waiting results can advance; completed
results remain stable. Source-result context and aggregate-route disposition are documented below;
full native qualification is recorded below.

Feeder pending, terminal and frontier maps select the exact instances of their
semantic lane roots. Initial executions retain the existing storage keys; new
instances get separate native keys. Original progress remains retained, and an
explicit current-key inventory selects current instance values. Point processing
of B never reads A's binding or that inventory. Stage revalidates every selected
instance, including read-only cached progress, so one owner cannot mix old and
replacement execution contexts.

The rooted `processNextRoot`/rooted drain paths select input from root-local
history and feeder progress independently of the global transport completion
cursor. Global journal terminal/frontier facts therefore remain intact. This
reasoning does not cover the non-rooted journal drain, which uses its global
frontier to suppress input. Public replacement must not expose that path as
supported without an additional contract and controls.

## Instance-specific source history and occurrence roles

Source admission, ordered history, exact-state locator and epoch-position keys now
include the execution reference. An explicit retained source reference reads its
own boundary history without selecting today's active binding. Historical
semantic lookup can be supplied that same original-instance selection.

Every logical publication captures native occurrence associations for each
observer instance, canonical occurrence/binding identity and activation generation.
The generation association keeps the same target instance when cursor updates
produce another binding identity. Existing occurrences do not follow a replacement.
A role captured before any local source execution can later resolve only the
first deterministic source instance; it never resolves a later replacement.
A new occurrence aimed at a replacement needs explicit target selection and
currently reports that missing capability rather than guessing.

Attachment capture, historical occurrence resolution and source discovery now
carry their actual observing owners into source-history lookup. Missing native
associations fail closed. Source-input eligibility uses the selected retained
session for nonexact historical operation targets, and its native occurrence
associations for forward target histories. It cannot use the replacement's
lineage as a fallback. Conflicting retained instance roles report the exact
target and roles. If a retained source needs further source-owned work, discovery
and join prerequisites report the original retired instance and the required
boundary instead of selecting a replacement for execution. Fully witnessed retired historical intervals now have native cold-application
controls, including terminal activation without replacement authority. Source execution context and aggregate guards are described below; full native qualification is recorded below.

## Lifecycle integration boundary

The selected design keeps active semantic indexes and a native retained-instance
archive. Occurrence target associations must retain both source instance and
original target instance/history role. Pending work, consumption and replay need
observer-specific instance context. A broad all-attempt owner tag would couple B
to A after joint work and is not the design.

`RootedCoordinationStorage.LogicalScope.retireInstance(expectedRef)` prepares
retirement in a fresh dedicated owner. Ordinary SDK facade, handles and inventory
access cannot be mixed with lifecycle selection; misuse invalidates the attempt.
The method returns `DocumentInstanceRetirement` with `PREPARED` or
`BLOCKED_POLICY_REQUIRED`, its exact retained position and concrete blockers.
`PREPARED` is tentative: call `stage()`, then let the host conditionally publish
that packet atomically with its own removal/control rows. Abandoning the packet
leaves active authority intact.

Retirement requires a singleton acyclic owner, no active incoming dependent,
pending join, unresolved catch-up plan, pending local historical work or source
work. Immutable historical references alone are not incoming live dependencies.
Pending requests retain native requesting-instance and source-instance
associations. Both memberships are indexed: an unresolved B request for A blocks
A even before B publishes an active edge, and a concurrent request insertion
invalidates A's prior absence predicate. An unrelated C query remains independent;
old A1 requests never become A2 requests. Missing association metadata poisons
the attempt. These records describe execution authority only and do not alter
canonical request or accepted-input eligibility.

A blocked selection produces no mutations. The native transition removes only
that owner's active session, lineage, outgoing topology, subscriptions, routes,
root memberships and scheduling entries. It retains archived sessions, original
publication associations and canonical history. Tombstoning the exact binding
fences previously prepared work; other owners keep their independent conditions.

`LogicalScope.startInstance(nextRef, basisPosition)` requires a fresh dedicated
owner, an absent active binding and a never-used instance ID. `PREPARED` remains
tentative until the host publishes; `BLOCKED_BASIS` reports concrete unsupported
basis prerequisites without mutation. The exact basis must belong to the same
semantic document. Current controls reject a cyclic starting basis and install a
real acyclic post-split basis without restoring B's old component projection.
Abandoned/competing starts and durable identity nonreuse are covered.

The selected observer inherits original occurrence roles. Fully witnessed retained-target historical application is supported through its
original instance association. The live-role starting guard remains conservative
for actual new execution/join prerequisites; another active instance alone is
not a reason to block an immutable historical reference.
Only continuations needing undecided new work/rejoin may remain policy-blocked. Active-cycle retirement,
implicit retargeting and evidence collection remain outside the decided policy.

## Reproduction and evidence scope

```sh
./gradlew test \
  --tests blue.coordination.internal.LogicalDocumentInstancesTest \
  --tests blue.coordination.internal.LogicalInstanceProgressTest \
  --tests blue.coordination.internal.LogicalPublicationInstancesTest \
  --tests blue.coordination.internal.StoredHistoricalSourcesTest \
  --tests blue.coordination.api.storage.CoordinationRecordContractTest \
  --tests blue.coordination.sdk.LogicalCoordinationStorageTest \
  --tests blue.coordination.sdk.LogicalInstanceHistoryTest \
  --tests blue.coordination.sdk.LogicalInstanceRetirementTest \
  --tests blue.coordination.sdk.LogicalInstanceStartTest \
  --tests blue.coordination.internal.LogicalReceiptInstancesTest \
  --tests blue.coordination.sdk.RootedCycleEntrypointTest \
  --no-daemon
```

The controls cover exact generations/ABA/nonreuse, independent B publications,
forged history associations, a retained reader across a newer head, cold actual
multi-owner cycle publication, a genuine same-epoch cyclic representation update,
and a real full-owner split followed by A-only retirement/start and A2-only work,
preserving old cyclic receipts/representations and B. Controls also cover abandoned preparation,
mixed-owner misuse, stale A work, independent B work and a newly committed live
dependency invalidating an already prepared retirement. These native controls are complemented by the source-context and retained-consumer
controls below. Full native/JAR qualification is recorded below; actual MyOS JPA publication
remains a separate required host qualification. New retired-source live execution or
rejoin remains outside the decided policy, rather than an incomplete historical
read capability.

### Catalog authority across restart (IS-01)

Starting an instance preserves the public-root exposure of the last retired
instance. It does not promote an embedded-only document. Explicit catalog
promotion before retirement is preserved even when the selected starting basis
predates promotion. Retirement selects the actual native catalog membership and
retains its boolean role with the exact instance and binding generation in the
same publication packet. A concurrent promotion invalidates that packet.
Restart requires this retained role to match the immediately preceding retirement;
missing or stale role metadata fails the attempt closed. Catalog authority is
independent of immutable execution-view root presentation and of canonical Blue
content.

### Retained consumers and structural endpoints

A nonterminal catch-up plan does not itself prove that its source must remain
live. Retirement can retain a foreign consumer's interval when native evidence
proves its exact original occurrence, activation generation and cursor, owning
barrier and exclusive cutoff, complete original source position, source-input
completeness and every numbered receipt in that interval. Earlier unprocessed
accepted source input, a real pending source request or an unresolved join still
blocks retirement. Missing physical evidence fails the attempt closed.

Historical work order, receipt, representation-chain, causal-entry and next-work
reads select the consumer's original source instance. Rooted application capture
fences the actual publishing owners and reads immutable dependencies through the
frozen source view. It does not select a replacement's session/binding merely
because the source has the same semantic DocumentId. The original instance's
numbered evidence remains unchanged.

Incoming topology membership is structural, not execution authority. Retirement
authenticates every active incoming occurrence's native target association. A
reference to retired A1 does not become a dependency on active A2. The native
index retains the foreign owner's forward/reverse memberships and a singleton
structural endpoint when they survive retirement; the removed instance has no
session, active binding, outgoing live memberships or control state. Restart can
replace that endpoint only through the already authenticated absent-instance
lifecycle path. Occurrence membership and exact association conditions protect
this decision against insertion and role rewrites, including an unchanged Boolean
semantic edge. A later consumer detach removes its own retained membership.

`LogicalRetainedInstanceApplicationTest` exercises A1 with two receipts, B's
historical attachment, A1 retirement/A2 start, both cold historical applications,
intervening A2 advances, later A2-only work, A2 retirement, A3 start and retirement,
and B detach with no active A instance. Missing original revision bytes poison
stage/prepare; an earlier unprocessed input still blocks retirement. These are
native prefix controls, not MyOS/JPA qualification. New retired-source execution
or implicit live rejoin remains unsupported; no A2 target is inferred from an
old occurrence.

### Source execution context (reviewed native prefix)

A canonical `SourceHistoryPrerequisite` contains no execution-instance dimension.
Two observers can therefore have byte-identical descriptors while owning distinct
native work. The selected extension adds an instance-bound source request alongside
the unchanged descriptor: exact requesting-owner instances and the original source
instance. Current rooted selection returns this authenticated context; execution,
observation and SDK outcome lookup accept it explicitly. Descriptor-only calls
continue to resolve the original request for lost-response reconciliation.

The context is operational storage authority, never canonical document/receipt
content. Native pending, submitted/completed and SDK source-result records must
separate it physically. Binding validates the actual suspended request and source
occurrence role, not just whether supplied instance identities once existed.
Forged/mixed roles must reject before source execution and cannot be staged.
Original completed outcomes remain readable after retirement without selecting a
replacement head. Current accepted-entry eligibility remains unchanged. Resident
engines retain their ordinary descriptor API behavior. `SourceHistoryRequest` and overloads on the advanced SDK now implement this
boundary. Current `sourceHistoryRequests(root)` returns exact authenticated
context. Descriptor-only calls retain original reconciliation. Native pending and
submitted/completed records use instance-specific physical keys; canonical
prerequisite and invocation bytes stay unchanged. Stored original stage fences
and execution archive projections avoid replacement-head reads during replay.

`LogicalSourceInstanceReplayTest` reproduces the missing original source SDK
outcome, then covers actual source processing, A1 retirement/A2 start and replay,
cold original stage/result reconciliation without A2 SESSION/binding conditions,
and a forged A2-for-A1 context poisoning stage/prepare. The exact byte-identical
request collision test is a lower-level storage control using a real stopped
invocation and an explicitly installed replacement ledger reference; it is not a
public lifecycle authorization test. It proves distinct pending/submitted keys,
current requester membership, and unchanged original descriptor reconciliation.
Resident source API and actual incoming request retirement controls remain
covered. Full native qualification is recorded below; aggregate disposition is documented next.

### Aggregate execution boundary and ordinary re-admission

After any native instance retirement, legacy aggregate `drain`, `drainThrough`,
journal drain and direct managed-application drain reject with
`INSTANCE_SCOPED_PROCESSING_REQUIRED`. SDK convenience operation/event `execute`
checks this before appending. Use root-specific stages and authenticated source
requests. Selected stages that already carry one exact root context remain
available. Read-only original outcome/history reconciliation remains available.

Only aggregate routes select the native first-retirement-marker/absence
predicate. There is no mutable account generation. An aggregate attempt prepared
before concurrent retirement conflicts; independently prepared rooted B work
still publishes. Rejection closes the attempt before append/processing can be
staged, and ordinary pre-lifecycle aggregate execution remains supported.
`LogicalAggregateLifecycleGuardTest` verifies these boundaries and later A2 rooted
processing. Full native qualification is recorded below.

The original `MyosSessionReplacementContractTest` diagnostic expected counter 0
from ordinary re-admission and failed at baseline (observed 7). Its baseline
source and failing XML remain preserved in the run's checkpoint01 evidence. The
versioned test now explicitly asserts 7 as the unchanged ordinary compatibility
contract, alongside Alice/B and Bob/A independence. This is not weakened
replacement acceptance: dedicated public lifecycle tests perform actual
retirement/start and verify the declared starting basis, old work fencing,
original history and per-instance replay separately.

### Descriptor observation compatibility

Descriptor-only result lookup returns empty for an unknown or mismatched request,
without poisoning an otherwise valid read scope. A positive original context
whose retained metadata is missing still fails closed. Observing a fresh source
prerequisite binds that actual native request before returning its descriptor, so
the caller can process it directly without an additional list operation. These
are compatibility repairs to HOST-01 context propagation, not new execution
policy. `LogicalSourceRequestObservationTest` reproduces both failures and checks
unknown lookup, known result, fresh descriptor execution and typed cold result.
The two observation tests and both source replay tests pass. Source replay SR-01
and the aggregate guard have completed focused source review; full native qualification passed, while actual MyOS qualification remains pending.

### Public host capacity configuration

`RootedStorageBounds(Engine, Sdk).toLimits()` constructs rooted storage capacities
using public SDK types. Nested `Index` supplies insertion-index capacities. Every
value is explicit and delegates to the existing engine/SDK validation; there are
no execution-policy defaults. Existing `RootedCoordinationStorage.Limits` and
`SdkLimits` constructors remain compatible. The public JAR-only lifecycle
consumer uses this facade and the public logical-record/immutable-byte ports.

## Native qualification on 2026-09-21

The settled implementation passed the complete native build:

```bash
./gradlew clean build --continue -PtestMaxParallelForks=4 --max-workers=4 --no-daemon --console=plain
```

The run executed 1,755 unit tests (339 classes), 18 built-JAR consumer tests
(six classes), and 95 integration tests (42 classes), with zero failures, errors
or skips. The build also passed production/API/test-shape gates, complete test
execution-scope validation and topology identity evidence. The public consumer
uses only the built JAR and public capacity facade.

The first complete run exposed seven legacy original-rejection result retention
regressions and two lower-level fixtures missing native instance/history setup.
The correction retains the original rejection byte assertions and supplies
native publication/initial-instance setup in those two fixtures. Test phase
labels and exact source/API shape inventories were updated; no broad exemption
or public rejection assertion was removed. These are supporting HOST-01 repairs,
not additional lifecycle policy.

Exported candidate manifests bind the later clean source commit/tree and artifact
hashes. This native result is not a MyOS database/publication result, a release
readiness claim, or permission for new retired-source execution/rejoin.
