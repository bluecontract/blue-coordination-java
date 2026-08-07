# Subscription projection and indexed delivery

An external subscription snapshot is an immutable projection of active
Channel occurrences for one exact Root revision. It is an acceleration and
persistence value; it is not another input to `PROCESS`.

## Snapshot identity

The persisted schema is
`blue.coordination/subscription-snapshot/2.0`. Version 2.0 makes scope-origin
provenance part of the canonical digest; version 1.0 is rejected instead of
being guessed or silently upgraded. A snapshot binds:

- exact Root BlueId and host revision;
- activation frontier;
- Language/Contracts runtime registry identity;
- Coordination registry identity;
- projection algorithm and schema versions;
- canonically ordered active occurrences;
- Process Embedded topology and directly pruned scopes;
- its own canonical digest.

It contains header and dependency identities but never executable bodies or
provider transport state. Rehydration recomputes the digest and rejects any
drift.

## Collection occurrences

Projection begins from Language's effective scope plan. An explicit embedded
path yields one occurrence. A collection declaration yields one occurrence
for each direct stable key. The occurrence key includes the concrete scope,
so the same child BlueId at `/lessons/algebra` and `/lessons/geometry` remains
two independently active occurrences.

The stored record retains declaring scope, explicit or collection declaration
path, raw key, escaped concrete path, and `ROOT`, `EXPLICIT`, or
`COLLECTION_MEMBER` origin. Retained and retired intervals preserve these
fields exactly; provenance drift fails closed. Targeting is still defined by
the concrete Channel runtime; `collectionPaths` does not define a generic
event address.

## Incremental lifecycle

An update compares the complete prior active surface with the exact resulting
Root and the host's strictly advancing order key:

```text
unchanged  same occurrence and effective header/dependencies
retired    absent or replaced at the new revision
added      newly active occurrence with a fresh interval
```

A member created while event `E` runs is absent from the pre-event surface.
It activates after commit and cannot consume `E`. Removing and later re-adding
the same key creates a new interval even if the child BlueId is identical.

## Indexed planning

The application index returns an exact, ordered candidate occurrence set.
Coordination rejects duplicates, omissions, extras, stale revisions, wrong
order keys, runtime-identity drift, and evidence that does not bind the
requested Root or Event.

For every candidate, the registered Language/Contracts Channel functions must
authoritatively re-evaluate:

```text
subscription keys -> PRESELECTS -> ACCEPTS -> target -> dependencies
                    -> checkpoint domain/subject -> delivery evidence
```

The index is never trusted to decide acceptance. A compatibility planner and
the indexed planner must produce the same semantic delivery plan for the same
current Root and Event.

## Public API status

The locked Language/Contracts release exposes the runtime-neutral services
through `BlueContracts.subscriptionSurfaceProjection()`,
`BlueContracts.indexedDeliveryEvaluator()`, and
`BlueContracts.currentRootDeliveryPlanDeriver(...)`. Coordination delegates
the authoritative projection and Channel-function evaluation to those public
services. It does not retain an unavailable placeholder and does not add
classes under `blue.language.*`.

The exact resolved boundary and evidence rule are recorded in
[latest-language-public-api-gap.md](latest-language-public-api-gap.md).
