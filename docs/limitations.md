# Known limitations

`3.0.0-rc.4` is the bounded external-pilot release candidate published to
Maven Central. It is not stable, production-ready, or a production MyOS
runtime.

The repository is now versioned `3.0.0-rc.6`. Its retained managed-epoch
profile is staged, unpublished, and non-production. It is built only against
an invocation-owned immutable Blue Language/Contracts `3.1.0-rc.23` Maven
stage derived from the exact published `3.1.0-rc.22` baseline plus additive
managed-transition receipts. Nothing on this page turns that source profile
into a published artifact or revises the historical rc.4 release claims.

Read the [SDK developer guide](guides/developer-guide.md) for supported
application flows. This page owns the current capability boundaries and
non-claims.

## Capability matrix

| Capability | Published rc.4 | Staged rc.6 source |
| --- | --- | --- |
| Ordinary authored document admission | supported | supported |
| Complete initially known managed closure | supported | supported |
| Initially known bounded cycles | supported | supported |
| Several occurrences sharing one stable lineage | supported | supported, with occurrence-specific catch-up cursors |
| Exact document-targeted operations | supported | supported |
| Complete provider-authored broadcast entries | supported | supported |
| Append/process separation and canonical sequential drain | supported | supported |
| Top-level `FROM_NOW` admission | supported | supported |
| Top-level full-history/frontier import | supported with exact in-memory provider evidence | supported with the same boundary |
| Operation-created new managed lineage | exact `FROM_NOW` draft only | exact `FROM_NOW` draft only |
| Nested handler attaches an existing lineage during catch-up | not applicable | supported through typed exact demand/resolution and barrier extension |
| Nested handler creates a newly authored lineage during catch-up | not applicable | unsupported; returns typed publication failure and durably blocks the exact plan/barrier without partial publication |
| Attach an existing lineage at its current state | unsupported by the historical managed-draft profile | supported by exact automatic matching |
| Attach authored-initial, epoch-zero, or retained source state | unsupported | supported through immutable source epoch receipts |
| Ambiguous repeated retained state | unsupported | requires `ManagedEpochSelector` |
| Several missing exact values in one retained application | unsupported | retained as typed per-demand issues; the same work resumes only after required content is verified |
| Event-only managed epochs and duplicate Root event occurrences | not exposed as catch-up input | preserved by complete source receipts |
| Independent catch-up for repeated occurrences and several parents | unsupported | supported with per-occurrence plans and cursors |
| Retained catch-up through a bounded cycle | unsupported | supported through ordinary closure/cyclic processing |
| Cyclic source successor proof | not exposed as retained catch-up input | requires a separately retained authenticated complete proof; missing/unavailable waits and invalid evidence blocks before PROCESS |
| Same-epoch component representation change | not applicable | bounded to the Contracts-authenticated, eventless finite two-member cycle; a larger merge requiring source-epoch change fails closed |
| Reinitialize or replay the source during catch-up | not applicable | never supported or performed |
| SDK work-bounded drain/resume | unsupported | supported with retained fair external/managed turns between selected entries and committed PROCESS transitions |
| Host-preleased exact processing slice | unsupported | read-only fair selection plus journal-only or exact managed targeted invocation; selection is revalidated before mutation |
| Operation-created lineage from or across a cyclic-set member | preflight rejects it | the new-draft restriction remains; retained attachment uses the ordinary proven-lineage path |
| General operation-created multi-member cyclic closure | not claimed | not claimed |
| Direct stable-key `collectionPaths` members | supported | supported |
| Stable identity for arbitrary list positions/reshaping | unsupported | unsupported |
| Fresh-process persistence and recovery | unsupported | unsupported |
| External durable provider-completeness adapter | unsupported | unsupported |
| Provider-backed Mandate resolution | unsupported | unsupported |
| Parallel or distributed scheduling | unsupported | unsupported |
| Production tenant/auth isolation and operations | unsupported | unsupported |
| Stable latency or throughput SLA | not claimed | not claimed |

## Managed topology

In the published rc.4 artifact, operation-result managed admission supports
only genuinely new `FROM_NOW` lineages with exact draft/request evidence and a
complete set of effective occurrence paths. Imported draft epochs and
full-history, frontier, attach-current, or passive operation-result activation
fail closed. There is no fallback to legacy child/parent admission.

Put every member that already exists, including every initially known cycle, in
one complete `ManagedClosure`. The rc.4 draft API does not claim arbitrary
creation of a new multi-member cyclic group from one operation result.
Managed-draft path preflight also requires an independently processable,
non-cyclic operation target whose own effective catalog does not cross a
cyclic-set member. A cyclic member—or an acyclic ancestor whose selected
catalog crosses into the component—cannot create the new managed occurrence in
this candidate. Use a cycle-free owning member or admit the complete topology
initially.

`Process Embedded.collectionPaths` covers direct stable-key members. General
list-position identity and arbitrary collection reshaping are not implied.
Same-invocation remove-then-re-add remains outside the published rc.4 claim.

The staged rc.6 source does not weaken the new-draft rule. It adds a separate
path for an exact value already proven in managed lineage history. An
unambiguous current, authored-initial, epoch-zero, or retained state is matched
automatically. A repeated historical state requires an exact
`ManagedEpochSelector` naming the source `DocumentId`, epoch, BlueId, and target
path. A later detach/re-add or retarget receives a new activation generation,
plan, and cursor; a retired occurrence cursor is never reused.

One ordinary affected closure may demand several exact BlueIds. The staged
profile reports every unresolved demand and matching status in the immutable
managed-application attempt; it does not accept a host-pushed partial document
or event list. Verified content is supplied through the exact-node provider and
the same canonical work retries only after its required set is available.
Ambiguity or selector mismatch remains a semantic failure, not a content-upload
request.

A directly selected embedded member with no typed incoming demand processes
only its forward affected closure; it does not implicitly pull ancestors into
that invocation. The staged profile captures and fences each participating
document's exact durable graph generation, so a later wider invocation can
merge independently advanced cohorts when their document heads, component
evidence, and subscriptions still match exactly. Any genuine mismatch remains
stale. Coordination does not weaken graph-generation CAS or invent upstream
participation without typed demand.

Managed embedded-document epochs and broad historical synchronization are not
capabilities of the published rc.4 artifact or frozen Contracts 1.0 semantics.
The staged rc.6 source implements the narrower retained managed-epoch profile
described in
[Retained managed-epoch catch-up](semantics/retained-managed-epoch-catch-up.md).
It applies complete immutable receipts from the already processed source
lineage through each consumer occurrence. It never reinitializes the source or
replays the source's Timeline entries, Operations, handlers, PROCESS work, or
public outbox. A cyclic receipt after-state additionally depends on a complete
authenticated proof retained outside the receipt in the exact-provider store.
This includes a same-state eventless application epoch later used as a source.
A proof miss or temporary outage is a typed same-cursor wait; invalid proof is a
typed block, and all are detected before PROCESS. A narrow eventless
representation-only update is accepted for the proven finite two-member cycle:
the distinct source member may receive the shared cyclic representation at its
unchanged local epoch when the exact Contracts result and commit companion
authenticate it. It never rewrites source history or a receipt. This does not
authorize arbitrary indirect peers; a merge with an already cyclic multi-member
source component fails closed when it would require a source epoch to advance
or be reinterpreted, and direct, eventful, or larger unproven changes remain
rejected. Top-level history import remains bounded by the current in-memory
provider evidence; a
`ManagedDocumentDraft` still cannot import a caller-authored historical lineage.

## Runtime and durability

Both profiles are one JVM, in-memory, and sequential. Journal completeness is
proven only for the current in-memory journal. A process crash loses document,
journal, topology, route, checkpoint, receipt, and outbox state. For rc.6 that
list also includes managed source/application receipts, occurrence plans,
barriers, readiness heads, and due-work indexes.

Coordinator reconstruction inside the same live engine is possible while its
typed in-memory stores survive. In the rc.6 source, a committed catch-up
application receipt also makes response-loss reconciliation idempotent without
calling PROCESS again. Neither behavior is a fresh engine reconstructed from a
serialized store. Exact cross-process recovery requires a future durable
adapter capable of atomically persisting and validating all journal, document,
graph, work-index, barrier, cursor, entry-frame, source/application receipt,
route/outbox, and provider-completeness evidence. Cyclic provider completeness
includes the exact member body and complete proof, restored before dependent
work becomes runnable. The current same-live restart fixture does not test that
fresh-process restoration.

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
the SDK. In particular, the bundled in-memory exact/proof store survives only a
control-plane `restartFromStores()` call, not process exit; no current artifact
claims a durable external cyclic-proof adapter.

## Scheduling and performance

Drain is intentionally sequential. Parallel document processing, leasing,
distributed scheduling, a second SCC planner, and caller-selected recipient
sets are out of scope. Retained catch-up reuses ordinary closure/cyclic
processing; it does not introduce a parent-recursion engine. Across Timelines,
canonical external source order—not Java submission order—is authoritative.

The published rc.4 normal SDK does not expose `DrainBudget`; only its advanced
low-level boundary has the work limit. The staged rc.6 SDK adds
`processing().drain(new DrainBudget(...))`. In either boundary the budget
counts selected entries and committed PROCESS transitions. It cannot preempt a
frozen processor call or epoch-zero INITIALIZE and is not a wall-clock
deadline.

No stable latency or throughput SLA is claimed. Large-document time can be
dominated by pinned Language/Contracts/BEX delivery-plan derivation and
platform commit. Caching or bypassing exact frozen verification is not a
permitted semantic shortcut.

## Historical evidence

The Round 13 performance exception and exact latency numbers belong only to the
historical [`3.0.0-rc.1` report](releases/3.0.0-rc.1-test-report.md). That policy
does not authorize rc.4 or a stable release and is not a current performance
claim.
