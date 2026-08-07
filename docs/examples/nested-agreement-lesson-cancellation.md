# Nested agreement, lesson, and cancellation evidence guide

The current public Contracts APIs expose every generic operation required by
the nested PROCESS scenarios below. Describing a scenario still does not
assert that it ran: only a generated trace from a passing same-run runtime
lane may publish a resulting Root, public event, subscription transition, gas
trace, or provider demand. A dependency failure must retain its actual
diagnostic and may not be recast as a missing public API.

## Structurally verified document shape

`CoordinationNestedEmbeddedCollectionFlagshipStructuralTest` builds and
verifies this shape:

```text
Agreement Portfolio Root
└── agreements (collectionPaths)
    ├── agreement-a
    │   ├── lessons (collectionPaths)
    │   │   ├── lesson-a
    │   │   │   └── cancellations (collectionPaths)
    │   │   │       ├── cancel-a
    │   │   │       └── cancel-b
    │   │   └── lesson-b
    │   └── paymentProcesses (collectionPaths)
    │       ├── payment-a
    │       └── payment/b~retry
    └── agreement-b
        ├── lessons (collectionPaths)
        │   └── lesson-c
        └── paymentProcesses (collectionPaths)
            └── payment-c
```

That structural lane checks the exact current `EmbeddedScopePlanView`, raw
member keys versus escaped JSON-pointer segments, collection provenance,
fragment occurrence identity, and exact reconstruction. It is representation
evidence only. It does not prove handler order, gas, subscriptions, or any
PROCESS result, and it is not evidence that scenarios A–I all executed.

## Required PROCESS scenarios

The release prompt requires the following runtime lanes. Their two focused
16-cell halves are now semantically green, but publication remains pending
until one full-class invocation produces and validates the exact 32-row trace.

### A — Root only

A Root-targeted operation must change Root without opening any agreement,
lesson, cancellation, payment process, or child workflow body.

### B — Confirm one lesson

The target is
`/agreements/agreement-a/lessons/lesson-a`. Only the Root → agreement-a →
lesson-a chain may open; unrelated branches must remain cold.

### C — Deep cancellation

The target is
`/agreements/agreement-a/lessons/lesson-a/cancellations/cancel-a`. The required
assertions cover exact child-to-Root causality and two public-event variants:
descendant-only emissions expose no public event, while explicit Root emission
exposes exactly D1 then D2.

### D — Sibling agreement

The target is agreement-b/lesson-c and must demand no agreement-a fragment or
workflow body.

### E — Add lesson-d

The creating event must commit the new member without letting it participate
in that event. A later event must open only the newly active lesson chain.

### F — Remove and re-add lesson-b

Reusing the same key and initial child BlueId must create a fresh occurrence
interval and checkpoint lineage after the previous interval is retired.

### G — Shared initial child identity

Processing lesson-a must not mutate lesson-b even when both occurrences start
from the same exact child BlueId.

### H — Frozen membership

A sibling added by a deep reaction must not be entered, initialized, accepted,
or checkpointed during the invocation that created it.

### I — Invalid surfaces

Focused cases must reject invalid collection shapes, wildcards, reserved
paths, duplicate or overlapping concrete boundaries, unavailable or invalid
evidence, and occurrence-limit exhaustion with exact rollback and diagnostics.

## Runtime probe boundary

`CoordinationComplexEmbeddedDeterminismFlagshipTest` is the executable
nested-collection PROCESS matrix. Its concrete selected spine is Root →
`agreement-a` → `lesson-a` → `cancel-a`; it also contains cold `lesson-b`,
payment, `agreement-b`, and `lesson-c` branches declared through stable-key
`collectionPaths`. The two public-event variants cover 32 representation and
provider runs and assert the selected identity spine, cold sibling identities,
on-demand listener bodies, final Root, Root-only events, gas, named trace, and
forbidden demands.

The engine-resume lane consumes the public per-invocation Contracts API. Its
inline and reference-backed controls both reach PROCESS, so an absent provider
handoff is no longer an accepted explanation for a red representation. Both
focused 16-cell halves pass the semantic assertions (32/32 cases in total),
including reference-backed nested mutation. That split execution is strong
diagnostic evidence, but it is not a substitute for the required
single-invocation receipt. Until the full class runs once and its exact 32-row
trace parses successfully, the publishable runtime receipt remains pending and
this guide does not invent result rows. The separate structural flagship
remains useful evidence for collection catalogs, fragment provenance, and
reconstruction, but it is not PROCESS evidence.

The checked-in generated trace predates this engine-resume run and remains a
historical receipt of its own source JSON. Regenerate it only from a new
schema-valid same-run trace; do not edit the generated result to resemble a
passing matrix.

The now-public lower-layer boundary and evidence rule are recorded in
[`../architecture/latest-language-public-api-gap.md`](../architecture/latest-language-public-api-gap.md).
Coordination delegates to those public Contracts services. Only an actually
completed same-run trace can turn the runtime lane green.

## Machine-readable evidence contract

`tools/publish-nested-agreement-trace.js` accepts a structured trace conforming
to
`src/test/resources/coordination/nested-agreement-flagship-trace.schema.json`.
The trace has independent structural and runtime lanes with declared,
attempted, and completed counts.

For a blocked, failed, or not-executed runtime trace, the contract forbids all
PROCESS result fields. The generated walkthrough therefore cannot display an
event sequence, resulting Root, subscription transition, gas trace, or
provider demand for a scenario that did not complete. For a passing trace, all
declared scenarios in every runtime lane must have completed.

Publish an actual structured trace with:

```bash
node tools/publish-nested-agreement-trace.js \
  --input build/reports/latest-language-embedded-collections/flagship-trace.json \
  --output docs/examples/nested-agreement-lesson-cancellation-trace.md
```

The generated file records the exact source SHA-256. It must not be replaced
with expected values copied from this guide.
