# Owned occurrences versus autonomous documents

The core processing engine manages Root sessions and owned embedded
occurrences inside those Roots. Cross-session delivery is deliberately a host
responsibility. Coordination's experimental myOS host now implements that
responsibility for explicitly identified logical documents; it does not change
the single-session semantics of `CoordinationProcessingEngine`.
Understanding that boundary is essential when modeling embedded collections or
interpreting subscription rows.

## Owned embedded occurrences

A member selected by a `Process Embedded.collectionPaths` declaration is an
occurrence owned by one Root. Its lifecycle and processing context are defined
by more than the child's content BlueId:

- owning Root session;
- canonical occurrence path and provenance;
- collection/member key;
- activation interval;
- inherited scope chain and matching evidence.

The same child BlueId can appear at two collection keys or in two Root
sessions. Immutable body bytes may be shared, but these are distinct
occurrences. They can have different activation histories, delivery evidence,
and surrounding Root state.

Adding a member activates it only after the creating event's order. Removing
it retires that occurrence. Re-adding equal content creates a new activation
interval and therefore a new occurrence lineage; content equality does not
resurrect the old interval.

## One Root PROCESS and commit

Delivery to an owned occurrence is planned within its Root session. The
Language processor evaluates the selected scope chain as part of one Root
PROCESS call. The resulting child effects, Root document, subscription update,
gas, and Root events are bound into one `CoordinationTransition` and one
session CAS.

An owned occurrence has no independent:

- `DocumentSessionId`;
- epoch sequence or revision CAS;
- committed external-order frontier;
- durable Root outbox;
- removal transaction;
- cross-session scheduler.

Do not create a session row per collection member while also treating the
member as an owned occurrence. That would introduce two authorities for one
lifecycle.

## Subscriptions are occurrence evidence

The projected subscription snapshot records exact occurrence identity and
activation evidence for indexed delivery. The host index returns ordered
occurrence keys for one target Root session. It does not establish independent
ownership of child content, and matching a child BlueId is not enough to select
an occurrence.

No dynamic “look up the current parent channel” behavior is implied. Channel,
timeline, workflow, and matching rules are resolved through the deterministic
Root processing model and its prepared delivery evidence.

## Separate Root sessions sharing content

If two autonomous business aggregates happen to have equal Root or child
content, admit them under different `DocumentSessionId` values. The fragment
store can deduplicate equal immutable bodies. Their session store records,
epochs, frontiers, subscriptions, events, outboxes, and removal states remain
independent. Processing one session never propagates to the other.

## Experimental host-level autonomous-document protocol

The myOS test host demonstrates a separate protocol above the engine. It owns
stable logical-document and Root-session identities, explicit managed links,
cycle validation, a rebuildable cross-session route index, a journal
high-water mark, bounded deterministic fan-out, and per-entry/per-session
delivery receipts. One Timeline entry can therefore advance several Root
sessions, with one atomic CAS/outbox boundary per Root and resumable partial
completion across Roots. Content equality alone never establishes a managed
relationship.

Dynamic managed-link changes are derived from the prospective PROCESS result.
The in-memory host invokes a side-effect-free transition publication guard
before the session CAS: child identity and content are verified and the full
prospective topology is cycle-checked. A rejection leaves the session, route
index, topology, inverse Timeline index, initialization receipts, and Root
outbox unchanged. Successful publication then reconciles the already-validated
forward and inverse host links while the myOS runtime publication lock is held.
Immutable transition fragments may remain deduplicated after a rejected guard;
they carry no mutable session authority.

That protocol lives in the `myosDemoTest` source set and is verified by the
myOS campaign; it is not part of the core engine's public API. The core engine
still advances exactly one session per call and never performs hidden
cross-session propagation. A production host must supply durable storage,
transactions, access control, retention, backpressure, and recovery policies
for the same explicit ownership model.

## Modeling rule of thumb

Use an owned occurrence when its mutations and lifecycle should commit with one
Root aggregate. Use a separate Root session when it needs an independent
revision, order frontier, authority, outbox, or removal lifecycle. A reference
between separate sessions is data unless an explicit host protocol, such as the
experimental myOS environment, admits the relationship and routes to it.

This boundary is a release truthfulness requirement. Core-engine tests for
owned occurrences are not proof of a production autonomous-document service;
the experimental host evidence is reported separately.
