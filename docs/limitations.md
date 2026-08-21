# Known limitations

`3.0.0-rc.3` is a bounded external-pilot release candidate from Maven Central.
It is not stable, production-ready, or a production MyOS runtime.

Read the [SDK developer guide](guides/developer-guide.md) for supported
application flows. This page owns the current capability boundaries and
non-claims.

## Capability matrix

| Capability | Rc.3 status |
| --- | --- |
| Ordinary authored document admission | supported |
| Complete initially known managed closure | supported |
| Initially known bounded cycles | supported |
| Several occurrences sharing one stable lineage | supported |
| Exact document-targeted operations | supported |
| Complete provider-authored broadcast entries | supported |
| Append/process separation and canonical sequential drain | supported |
| Top-level `FROM_NOW` admission | supported |
| Top-level full-history/frontier import | supported with exact provider evidence |
| Operation-created new managed lineage | supported only as exact `FROM_NOW` draft |
| Operation-created lineage from or across a cyclic-set member | unsupported; preflight rejects it |
| Operation-created imported/historical lineage | unsupported; fails closed |
| General operation-created multi-member cyclic closure | not claimed |
| Direct stable-key `collectionPaths` members | supported |
| Stable identity for arbitrary list positions/reshaping | unsupported |
| Fresh-process persistence and recovery | unsupported |
| External durable provider-completeness adapter | unsupported |
| Provider-backed Mandate resolution | unsupported |
| Parallel or distributed scheduling | unsupported |
| Production tenant/auth isolation and operations | unsupported |
| Stable latency or throughput SLA | not claimed |

## Managed topology

Operation-result managed admission supports only genuinely new `FROM_NOW`
lineages with exact draft/request evidence and a complete set of effective
occurrence paths. Imported draft epochs and full-history, frontier,
attach-current, or passive operation-result activation fail closed. There is no
fallback to legacy child/parent admission.

Put every member that already exists, including every initially known cycle, in
one complete `ManagedClosure`. The rc.3 draft API does not claim arbitrary
creation of a new multi-member cyclic group from one operation result.
Managed-draft path preflight also requires an independently processable,
non-cyclic operation target whose own effective catalog does not cross a
cyclic-set member. A cyclic member—or an acyclic ancestor whose selected
catalog crosses into the component—cannot create the new managed occurrence in
this candidate. Use a cycle-free owning member or admit the complete topology
initially.

`Process Embedded.collectionPaths` covers direct stable-key members. General
list-position identity and arbitrary collection reshaping are not implied.
Same-invocation remove-then-re-add and retargeting one retained occurrence to a
different lineage remain unsupported.

Managed embedded-document epochs and broad historical synchronization remain a
next-version Coordination temporal profile rather than frozen Contracts 1.0
semantics. Top-level history import is bounded by the current in-memory provider
evidence; operation-created historical import is not available.

## Runtime and durability

The supported profile is one JVM, in-memory, and sequential. Journal
completeness is proven only for the current in-memory journal. A process crash
loses document, journal, topology, route, checkpoint, receipt, and outbox state.

Coordinator reconstruction inside the same live engine is possible while its
typed in-memory stores survive. That is not a fresh engine reconstructed from a
serialized store. Exact cross-process recovery requires a future durable
adapter capable of persisting and validating all journal, document, graph,
barrier, cursor, entry-frame, receipt, and provider-completeness evidence.

The copy-on-write connected-closure publication seam is currently package
internal and in-memory. It proves selected-head/topology CAS and one-swap
rollback for the supported Contracts path; it is not a distributed transaction
protocol. A sequence of Timeline Entries is not one global transaction, and
disconnected closures can commit independently.

## Provider and authorization

The pinned generic Timeline Entry has no universal literal `documentId` field.
Exact targeted SDK operations use profile-specific target evidence; complete
provider entries remain environment-routed. A universal provider targeting
profile requires an upstream field or runtime hook.

General Mandate eligibility requires exact Mandate state at the entry's source
order. The in-memory engine has no provider-backed resolver and does not invent
that evidence. Authority-bearing `onBehalfOf` entries therefore fail closed.

Production MyOS still requires durable stores, exact restart recovery,
authorization and tenant isolation, provider completeness, outbox recovery,
operational backpressure, credential management, redaction, and production
observability. Those are separate host/profile phases and are not simulated by
the SDK.

## Scheduling and performance

Drain is intentionally sequential. Parallel document processing, leasing,
distributed scheduling, a second SCC planner, and caller-selected recipient
sets are out of scope. Across Timelines, canonical external source order—not
Java submission order—is authoritative.

The normal SDK does not expose `DrainBudget`. The advanced low-level budget
bounds selected entries and committed PROCESS transitions; it cannot preempt
one frozen processor call or epoch-zero INITIALIZE and is not a wall-clock
deadline.

No stable latency or throughput SLA is claimed. Large-document time can be
dominated by pinned Language/Contracts/BEX delivery-plan derivation and
platform commit. Caching or bypassing exact frozen verification is not a
permitted semantic shortcut.

## Historical evidence

The Round 13 performance exception and exact latency numbers belong only to the
historical [`3.0.0-rc.1` report](releases/3.0.0-rc.1-test-report.md). That policy
does not authorize rc.3 or a stable release and is not a current performance
claim.
