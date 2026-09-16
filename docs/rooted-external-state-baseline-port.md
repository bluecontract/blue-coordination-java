# External-state port onto the qualified rooted baseline

## Source and status

### Combined bounded reuse batch (15 September 2026; implemented, not yet qualified)

The pinned POC stack remains independent from the baseline reconciliation task.
The last qualified component tuple is Coordination `8c2ae43`, Language `35a20b0c`
and MyOS `2f11e19`: **223 library tests / 31 owners** and **133 MyOS controls /
11 owners** passed. The full durable ring/restart did not pass; it stopped at
first-cycle readiness after 681 seconds. These component results do not qualify
the newer source below or prove that a patch caused the observed slowdown.

Three private corrections form one follow-up batch:

| Observed problem | Correction and exact validity boundary |
| --- | --- |
| Receipt indirection serializes the same fresh payload again between dependency retention and canonical map encoding. | [One-shot prepared encoding](poc-closure-receipt-indirection.md#one-shot-prepared-encoding-follow-up) supplies this mutation's owned descriptor; full canonical decoding and every actual new-write acknowledgment remain. |
| One root-selection decision independently captures the graph for local-history and LIVE selection. | [Paired selection](poc-rooted-selection-capture-reuse.md) uses one library-created capture under existing engine/adapter synchronization. No capture survives into another decision; candidate order, source checks and publication fences remain. |
| Session staging freshly reads/authenticates a known immutable view and then PUTs it again. | [Known-view retention](poc-authenticated-view-retention.md) omits only that duplicate dependency write within the call. Every byte still counts towards bounds/conflict checks, and the complete mutable session is always serialized and acknowledged. |

The early paired diagnostic contains the same one-Contracts-call / three-host-
publication shape, not three completed historical applications. It confirms the
extra receipt pass, duplicate view traffic and repeated capture calls, but does
not establish a whole-ring speedup. The extra receipt pass alone cost about
52 ms in that window and cannot explain the earlier many-minute readiness delay.

Production inventory is **313 sources / 85,358 lines**: +26 lines in three
existing private sources after the one-shot checkpoint. Public API/SDK inventories,
storage formats for these follow-ups, protocol budgets and processing semantics
are unchanged. Eleven new controls across the combined batch, strengthened
existing controls and related root/join regression owners form a planned single
**251-test / 36-owner** group before exact-artifact MyOS verification. That count
is an inventory, not an executed result. No full POC acceptance is claimed.

### One-shot prepared receipt encoding (15 September 2026; provisional, not qualified)

The receipt-indirection candidate `8c2ae43` passed its 223-case/31-owner library
gate and MyOS's 133 focused controls, but the full ring's first-cycle readiness
still timed out. Those results do not qualify this follow-up or prove that
indirection caused the observed slowdown.

Static tracing found an additional full fresh receipt encode between dependency
preparation and map canonicalization. Its view-address callbacks also encode
retained historical views. The [one-shot correction](poc-closure-receipt-indirection.md#one-shot-prepared-encoding-follow-up)
captures one owned descriptor during preparation, retains the exact payload with
its ACK checks, and passes that descriptor through the unchanged bounded
canonical decode/re-encode path. Generic preparation ordering and independent
pure encoding remain unchanged; no fresh receipt identity memo is introduced.

Five new controls in the existing map/receipt owners cover exact call ordering,
fresh encoder counts and split-path byte parity, owned/one-use prepared bytes,
canonical/size rejection, snapshot semantics and later fresh-input validation.
They have not run. Existing corruption, bad-ACK, failed-write/old-root, cold
history/result/gas and mutable-session controls remain required. Production shape
is **313 sources / 85,332 lines** (+41 in three private files); public API/SDK and
per-source limits, wire formats, scheduling and logical processing are unchanged.
Measured end-to-end benefit is pending; no upstream/baseline branch is changed.

### Closure-index receipt indirection (15 September 2026; implemented, qualification pending)

The measured durable staging path copies complete receipts into newly rebranched
index nodes. One unchanged 1.2 MB receipt occurs in 39 physical nodes in the
preserved diagnostic. [The POC correction](poc-closure-receipt-indirection.md)
retains the existing complete receipt once and stores a small address/length
descriptor in closure nodes. This changes only the private closure-value storage
binding, not the processing algorithm, semantic identities, gas or failure rules.
Nine new controls and the affected existing owners await qualification, followed
by the full MyOS ring/restart. No upstream/baseline update is included.

### Persisted cyclic provider-pair verification (15 September 2026; implemented, qualification pending)

| Problem / example | Solution and rationale | Boundaries / qualification |
| --- | --- | --- |
| Tuple10 passed its 182-case library gate but the durable ring again exceeded the unchanged 600-second second-chord readiness window. Its 60-second profile classified 160 of 626 writer execution samples as other provider verification. Repeated rooted-state captures read the same cyclic member wire body and complete proof, then independently repeat the pure pairing calculation. | The closed storage decoder freshly reads, authenticates and decodes both selected records. The existing weighted L1 memoizes only successful verification of the exact member + wire frame + complete proof frame + codec profile. Every return is a fresh detached Node; no Node/proof or whole-entry cache is added. | Current record, identity and proof checks precede every hit. Either local body or local master-proof overlay retains the original cold behavior. Missing/corrupt/changed records remain failures; over-budget keys fall back without a new admission limit. No public API, Language change, protocol or gas change. Eight new direct-provider controls plus the existing 26-owner regression group await the parent-owned gate and exact-tuple MyOS verification. |

See [persisted cyclic-pair verification reuse](poc-stored-cyclic-pair-verification-reuse.md).
This POC-only follow-up adds 73 net production Java lines in four existing files:
312 sources / 85,183 lines; API/SDK inventories remain at 92 types. The preceding
tuple10 library PASS does not qualify this new change or settle MyOS acceptance.

### Missing nested-result and whole-object cache propagation (15 September 2026; qualification pending)

| Problem / concrete evidence | Correction and rationale | Boundaries and qualification |
| --- | --- | --- |
| Ring09's bounded 32-object / 69.5 MB census contained five different complete cause frames but an identical 727,299-byte inner result in two records. Whole-cause caching cannot reuse that shared inner frame. The profile attributed 155 nested result decode samples to execution-evidence paths, which bypassed the direct-result helper. | Configure every private execution-evidence reader with Language's final complete-result codec and opaque verified-frame reuse port. Replace the old outer result wrapper, rather than nesting two caches and risking single-flight self-join. One existing host-weighted L1 retains the library-issued frame, with exact result identity as a reverse lookup subject. Account for the token's retained frame bytes as well as the cache's bytes. | No whole-cause, invocation, retry, demand or mutable-owner interning. Language remains responsible for full first decode, canonical validation, exact frame/profile matching and identity-bound encoding. Enclosing type/coordinate checks and unconditional committing-result ownership checks remain outside reuse. |
| The same recording attributed 106 of 117 exact-value decode samples to `WholeObjectStorage`'s canonical/provider lanes, versus three directly to `SessionRecordCodec`. The document-session inline cache was not propagated to whole-object readers. | Pass the same cache to `WholeObjectStorage`; reuse only its two immutable exact-value lanes under the existing session exact-frame/profile family. | Outer physical reads/digests, pinned indexes, purpose, mutable cyclic wire bodies, and current member/proof checks remain fresh. No new whole-entry, Node or proof cache. |

See [nested result and whole-object reuse](poc-nested-result-and-whole-object-reuse.md).
This is an isolated POC follow-up with **97 net production Java lines**, 312 source
files and 85,110 lines; Coordination API/SDK inventories stay at 92 types.
Language has a separately documented additive opaque result-retention port.
Six nested-result controls, four whole-object controls, the existing complete
19-owner regression group and five related storage/SDK owners are prepared
(expected 182 tests / 26 owners); no new tuple PASS is claimed until the
parent-owned grouped gate and actual MyOS run complete. The observer added to the
existing SDK graph fixture is test-only and does not change its processing rules.

### Complete terminal-result frame reuse (15 September 2026; qualification pending)

| Problem / example | Correction and boundary | Qualification |
| --- | --- | --- |
| Different stored views, publication/admission receipts and completed source responses repeat the same full Contracts result. Only original-result rows used decoded L1; the other readers reconstructed it again, and canonical writers repeated its serialization. | One private typed `StoredClosureResultCodec` shares the existing canonical, exact-frame/decode-profile L1 across all seven private wiring sites. Only completely decoded/canonical-checked result identities reuse encoding. New processor outputs retain ordinary encoding. Original-result rows unconditionally require a committing result **after** shared decode, even if a receipt populated a valid rollback frame. All physical reads, enclosing owner/publication checks, selected membership, per-owner scope charges and exact member registration remain. No public API, wire, gas, epoch, order or processing-rule change. | Nine actual-result controls plus cross-consumer and completed-source/new-owner controls are implemented, not yet run. Existing 18-owner / 135-test batch expands to 19 owners / expected 146 tests, subject to fresh native discovery. Exact artifact rebinding and full MyOS qualification remain required. |

See [complete-result reuse](poc-complete-result-frame-reuse.md) for measured
scope, constructor wiring, ownership and rejected alternatives. Production
inventory is 312 Java files / 85,013 lines (+1 private file, +47 lines versus
`7c7901c`); 92 public API/SDK types and existing per-source caps remain unchanged.
This is a POC-only follow-up, not a change to a frozen baseline branch or PR.
The coordinated qualification tuple also includes Language's pure verification
certificate for every actual owned snapshot in a fully accepted decoded DAG,
and MyOS's test-only PostgreSQL snapshot-container selector. It does **not** add
the previously considered result-to-output graph field. These are separately
owned changes; their exact source/artifact tuple must be qualified together.

### Runtime L1 follow-up (14 September 2026)

The isolated `codex/poc-runtime-l1` candidate, based on `45cd6bec`, adds
[host-lifetime decoded artifact reuse](poc-runtime-decoded-artifacts.md). The
problem is repeated reconstruction of the same retained history between cold
command owners. The additive `RootedCoordinationStorage.Cache` and `open` overload
allow host-owned estimated weighted-LRU capacity without sharing mutable owners,
changing semantic processing or adding a database dependency. Complete receipt
dependency manifests preserve per-owner alias registration, scope accounting,
physical integrity and current publication checks. Source-shape accounting adds
one internal source and 388 lines; the caps track this implementation inventory,
not protocol or gas limits. Focused tests and MyOS qualification are pending.

### Inline exact-frame reuse (15 September 2026; implemented, qualification pending)

| Problem / example | Correction and boundary | Qualification |
| --- | --- | --- |
| Reopening a session reuses its rooted view but reconstructs identical inline exact values from revision/event/layout rows, including cyclic or resolver evidence. | Share only fully decoded/canonical-certified immutable `ExactValue` frames through the existing private host-lifetime weighted L1. Complete bytes and byte/depth limits identify a frame; encoding reuse requires the exact retained object identity. Preserve ordinary first-decode validation and all enclosing session/owner/publication checks. No new public API, format, Language change or logical gas/processing change. | Five `SessionRecordExactValueReuseTest` controls and the strengthened actual-session reuse control are implemented, not yet executed/qualified. Grouped library regression/API/shape guards and exact-source MyOS E2E remain required. |

See [canonical-frame reuse](poc-canonical-frame-reuse.md#inline-exact-value-reuse-implemented-qualification-pending)
for the cache boundary and fallback behavior. The preceding MyOS cache-capacity
run does not include or qualify this library follow-up. Measured production
inventory is 311 Java files / 84,966 lines; only 17 Java lines are added, with no
public API or production file-count increase.

This POC branch starts from qualified Coordination
`d220a50bb320fba67a6215ec0efdfd8ed3104928`, not from the previous POC algorithm.
The donor is `48b0347ce689b2d16ed876333d7ce96f3dcc5d0c`; only its physical-storage
delta after `e2ac0dbb39e232ab88b67bf184b52895c785b163` is ported. The old MyOS
checkpoint `871b256268a920680554147f60b685687969406a` pinned that donor and
Language `8542285144a8969f157d73e73292398885105c46`. The new Language storage
port is `b8fd6f484deed71e4f0d283a33b16fddf15fafef`, based on qualified `e28ce805`.
BEX `ab72af14` and Catalog `0b68744b` source remain unchanged; new immutable
exports must bind the new Language artifact.

This document records the source port, not a new PASS receipt. Historical
component results in the imported storage documents apply only to their named
old sources. Parent-owned grouped compilation, tests, Javadoc and architecture
guards, followed by host E2E, remain required on the new pinned tuple. No release
or existing baseline PR is changed by this POC branch.

The first pinned port candidate `e8005bebcc1ab2101676d140199cd62b4d56e81d`
completed a 57-owner grouped gate: **283 tests, 280 passed, three failed,
zero errors/skips**. Javadoc, implementation-shape and SDK API-boundary checks
passed. The corrections below are a subsequent source candidate, not a claim
that those failed tests or the broader POC have passed. The archived first-run
XML/HTML is in the external `coordination-attempt01-reports` evidence directory.

## First grouped correction: preserve existing publication evidence

| Failure / example | Correction and why it is required | Regression |
| --- | --- | --- |
| A submitted local source step decodes to a new `ManagedOccurrenceBinding` instance. That class has no value `equals`, so Java object equality rejected an otherwise equal complete row. | Compare the complete canonical occurrence bytes, including cursor fields; retain the bound/original input, shared view, fresh reselection and lost-acknowledgement assertions. This is a test oracle correction, not reduced identity checking. | `SourceDiscoveryStorageCodecTest.rootedRetainedSourceWorkKeepsItsCapturedHistoricalViewAndIsNotLiveWork` |
| A FULL_HISTORY parent imports an existing source at S1, while its replay lower bound is BEGINNING. The port's new stored-source constructor reapplied a bounded-admission frontier check and rejected the valid S1 endpoint even during live admission. | Preserve the already selected admission policy in live capture and recover it from the existing hash-bound history descriptor on cold open. FULL_HISTORY retains its exact source publication proof without treating the replay sentinel as the source endpoint. FROM_NOW/FROM_FRONTIER remain bounded; unknown modes and foreign/unpublished sources fail closed. No admission default or protocol behavior changes. | `StoredOccurrenceIndexesTest.actualRootedAdmissionRowsReopenWithoutProviderOrOtherSessions`; `DocumentSessionStorageTest.fullHistoryAdmissionKeepsTheExactLaterSourceEndpointAcrossColdOpen` and malformed/bounded controls |
| A complete cold diamond store rejected a retained PROCESS result whose final checkpoint changes its body without advancing the Contracts work epoch. Existing live publication records that verified settlement at Coordination E+1, but the port's read guard recognized only E and same-epoch component representation. | Accept only the existing verified checkpoint-settlement shape at **exactly E+1**, supported revision kind and exact predecessor/after body. Reuse the live settlement predicate and receipt mapper: the actual transition, commit companion, cause, ordered events, source order and admitted gas must reproduce the retained receipt identity. Never search later heads, rewrite result epochs, or treat a matching body alone as proof. | `ContractsCheckpointSettlementPublicationTest` actual direct/indirect settlement controls, missing E+1, receipt/companion/cause/gas/event/predecessor/kind mismatches and non-settlement work-state rejection; `RootedLocalStepStorageCodecTest` full cold diamond |

The last change closes a physical read/publication-validation asymmetry; the
live processor, witness choice, event ordering and logical gas are unchanged.
The diamond's positive control still rebuilds and validates the complete actual
store. Its deliberately stale anchor/peer controls use session-only stores
without newer global receipts, so rejection must come from
`restored.requireCurrentInput` itself, not from constructing an inconsistent
global store before that assertion. These isolated controls do not claim a
valid complete durable publication or execute against the forged lookup.

This correction adds **73 production lines** in three existing sources:
**84,028 → 84,101** total, still **308 production sources / 92 public API source
types**. The implementation-size guard is updated to that measured inventory;
retained-file, protocol, closure, gas and runtime resource limits are unchanged.

## What is preserved and what is added

The d220 rooted graph, exact operation boundary, source prerequisites, explicit
root selection, cutoff fairness, witness/peer selection, publication ownership,
event ordering, tight-budget gas and failure rules remain the reference.
Neither specifications/fixtures/release identity nor old POC algorithm changes
are imported. The conflicting old provider-admission test/document retain d220
content. The root anchor still comes from the actual local historical step;
the fallback scans retained catalog keys instead of hydrating every session.

| Required physical boundary | Concrete problem and ported solution | Regression owners |
| --- | --- | --- |
| Immutable exact objects and journal | Reopening an Order must retain its original Blue representations, cyclic proofs, exact entries, predecessor order and duplicate outcome, not reconstruct them from a final body. Host byte and journal ports use library-owned bounded codecs. | `WholeObjectStorageTest`, `StoredWholeObjectIndexTest`, `StoredTimelineJournalTest`, `JournalFixtureBodyCodecTest` |
| Complete indexed document state | Persisting only current Agreement loses same-epoch views, original receipts, history targets, subscriptions and pending work. Lazy persistent maps/logs retain all 46 document-store families and identity-shared rooted views. | `DocumentSessionStorageTest`, `StoredDocumentStoreTest`, `Stored*IndexesTest`, persistent map/log owners |
| Control, feeder and pending state | A crash between slices must not reset a failed root's isolation, active cutoff, original demand or retained source operation. Explicit control and pending families restore these exact records. | `EngineControlStorageCodecTest`, `EnginePendingStorageTest`, `StoredFeederProgressTest`, `SourceDiscoveryStorageCodecTest` |
| Fresh engine and SDK owner | Registering Timelines/replaying commands to reconstruct SDK maps can repeat work or accept handles from a closed owner. Complete assembly restores actual indexed maps, returns fresh handles, and keeps ordinary owner checks. | `RootedEngineStorageTest`, `RootedCoordinationStorageTest`, `SdkRuntimePointMapsTest`, `SdkInstallationStorageTest` |
| Physical failure boundary | A failed store read/write is not a deterministic processor rejection. Physical exceptions remain noncommitting; execution failure requires discarding the scoped runtime and reopening the still-published selection. | `RootedPhysicalFailureClassificationTest`, storage failure/rollback controls |

The complete assembly is `RootedCoordinationStorage.open` → existing SDK
execution → `Scope.stage`. Staging writes immutable objects and returns named
descriptors. It **does not publish** them. The host must supply a coherent
selection and matching journal, validate selected rows/predicates and atomically
publish the resulting descriptors and journal mutations. A stage-only failed
prewrite can retry on that scope; a physical failure during execution cannot.
See [SDK lifecycle](rooted-sdk-storage-factory.md) and
[engine assembly](rooted-runtime-storage-factory.md).

## Necessary adaptation: retained submitted historical steps

The donor stored `RootedLocalHistory.Step` as six independent values. d220 makes
it opaque and binds an original pre-binding input to an exact captured view.
A terminal can additionally retain `originalCapture` and `peerPrefixes`.
Recreating the old record would omit both the input association and peer fences.

Example: Agreement's historical A11 contains D2, while a fresh terminal may
select independently completed D14. Cold restoration must keep A11/D2's original
proof, the new terminal input, and the exact D14 publication fence. It must not
replace a frozen D2 operand with a later head or silently drop a peer's guard.

`RootedLocalStepStorageCodec` stores the complete original input, bound cohort,
captured snapshot/documents/view/anchor, recursive original capture and exact
peer-step proofs. Shared views are interned by the existing storage scope. The
same closed Step constructor performs the Language binding again, and its full
bound bytes must equal the stored input. Maximum byte/depth and canonical-frame
checks fail closed. No provider or processor is invoked while decoding.

This is **retained submitted-step verification evidence**, not a fresh routing
capability: transient captured routes are not reconstructed. `routes()` fails
closed on a restored capture. The existing source coordinator freshly prepares
uncommitted work before execution; only an already committed exact publication
allows submitted evidence to reconcile a lost acknowledgement without selecting
a later head. This restriction also prevents a restored step from silently
entering fresh terminal-peer acquisition with invented routes.

The prepared source frame is versioned `source-prepared/2`; old development `/1`
frames are rejected, not heuristically migrated. New deployments must start from
the new coherent selection. No published persistent-format compatibility is
claimed for the previous experiment.

A registered terminal may contain both historical work and its local calculation:
that is **one selected operation**, not two. The source can be an original SCC
owner even when the registered work names an acquired consumer. Its committed
transition count is verified against the complete result's actual owners, not a
hardcoded `1`. No new operation or gas policy follows from this codec correction.
The retained-step cutoff check uses the original anchor, exactly as the live
source selector does. A source receipt's order may be later for an authenticated
frozen return owner; physical decoding must not impose a stricter receipt-order
rule than `RootedLocalHistory.capture` and the source selector. This alignment
was found by source review, not claimed as a new executed reproduction.

Regressions added/strengthened:

- `RootedLocalStepStorageCodecTest.coldTerminalRetainsOriginalCaptureAndSelectedPeerFencesWithoutInventingRoutes`:
  actual qualified diamond, cold original/peer proof, exact input round-trip,
  stale anchor and peer rejection, no provider read or routing fabrication.
- `SourceDiscoveryStorageCodecTest` local and managed controls: uncommitted cold
  submitted proof is freshly selected, source publication leaves parent unchanged,
  and dropping the response memo reconciles the original publication without repeats.
- Existing malformed/truncated/bounded frames and source-result association
  controls remain. Fresh native results, including these added controls, are not
  inferred from the old donor receipts.

## Durable-field audit against d220

| d220 state | Retained location |
| --- | --- |
| `RootedProcessingSchedule`: historical turn, yielded roots, isolated exact work | `EngineControlStorageCodec` / `RootedProcessingSchedule.StorageState` |
| Feeder terminal progress/frontiers, suspended demand and rejected births | Control frame plus `StoredFeederProgress` maps |
| Journal drain handled entries/frontier | `ContractsJournalDrainCoordinator.StorageState` in control frame |
| Deferred/isolated managed consumers and managed-vs-Journal turn | Control frame, reinstated by `ContractsRecoveryState` |
| Source pending/submitted/completed and append-time draft/selection plans | `EnginePendingStorage`, exact source/cohort/plan/result codecs |
| SDK Timelines, intents, entry handles, operation results and source results | Five owner-scoped `SdkRuntimePointMaps` families |
| New opaque local Step original input, original capture and peer prefixes | New local-step codec described above |

The d220 cutoff correction changes how existing scheduling fields are used; those
fields are not reset on cold open. `RootedEligibilityCache` and
`ManagedRepresentationVerificationMemo` are disposable physical read memos,
not authority: fresh runtimes start them empty and perform the existing exact
validation. Their omission can increase work, not alter a logical result.

## Public API and quality inventory

Measured production source inventory: **254 → 308 files**, **74,262 → 84,028
lines**, public boundary types **87 → 92**. The five new public boundary types are
`TimelineJournalStore`, `TimelineJournalStorageException`,
`CoordinationImmutableObjectStore`, `CoordinationObjectStorageException`, and
`RootedCoordinationStorage`. The retained-managed per-file cap remains 1,166
lines (actual maximum 1,025). These are physical implementation-size guardrails,
not protocol limits.

Three named cross-package implementation bridges are explicitly allowed:
`ExactValueStorageCodec`, `InsertionOrderedStorage`, `RootedEngineStorage`.
The advanced storage facade's signatures may reference only the two host ports
and `RootedEngineStorage.Limits` / `InsertionOrderedStorage.Limits`; the ordinary
SDK boundary and deny-by-default checks for every other internal/API/processor
type remain active. This is not a blanket exemption for storage SDK signatures.

## Remaining POC acceptance

### Measured follow-up: complete historical verification reuse

[Historical verification reuse](rooted-history-verification-reuse.md) records the
late ring/chord work attribution and the corresponding internal-only correction:
reuse one fully authenticated chain inside a verification call and preserve the
existing publication decoder's canonical-byte association in the process cache.
Physical membership reads, dependency adoption, full-chain suffix validation and
logical semantics remain unchanged. The combined library gate passed 73/73;
the final cold-path refinement passed its 29/29 affected storage tests separately.
Full PostgreSQL ring/restart still requires its recorded gate. No full POC
acceptance or controlled speedup is claimed.

### Profile-driven follow-up after the568 paired affected gate

Coordination568 passed322/322 tests in its broader POC storage/rooted group.
On the sealed resulting tuple, MyOS44db passed35/35 affected cases in resident
mode and30/35 externally; the same five cyclic/history READY deadlines fail
only externally. These are same-oracle paired runs, not a fresh full170 corpus
or identical-input whole-ledger differential proof.

[Decode-local canonical payload reuse](rooted-view-canonical-payload-reuse.md)
addresses repeated validation found in a separately instrumented original ring
case. The candidate stays on a new branch from568; its validation and performance
results are pending. No result from568 retroactively qualifies this change.

### Follow-up after the complete MyOS 25eacea physical campaign

The complete product inventories are resident169/170 and external160/170, not
full external acceptance. The next grouped library correction is confined to
this POC branch:

- [Receipt-aware rejection validation](rooted-publication-receipt-storage.md):
  cold/eager restoration must validate the authenticated original pre-state for
  a charged host-rejected managed draft, not require speculative unpublished
  after-heads. The isolated RED gate reproduced both positive-gas cases.
- [Bounded immutable receipt/proof reuse](rooted-cold-publication-reuse.md):
  repeated cold reads formerly decoded complete new receipt objects and defeated
  pure proof memoization. Restore exact owner-local typed identity with bounded
  payload retention and eviction-coupled proof lifetime, preserving first-read
  and selected-history checks. The same RED group reproduced failed reuse.
- [Rooted eager-store inventory validation](rooted-store-component-inventory-validation.md):
  the actual same-epoch rejection regression exposes conflicting retained-proof
  and legacy global condensation orders during eager reconstruction. Honor the
  existing rooted canonical inventory and exact indexed membership; retain the
  unchanged non-rooted check. Do not reorder a rooted calculation or change its
  component proofs to satisfy a physical restore guard.

The RED group was 18 invocations, 3 expected failures, production still at
fdf3485; raw evidence is retained separately. New direct corruption, same-epoch,
cache lifetime and SDK recovery controls are additional candidate tests, not
retroactively counted as pre-fix executions. Neither correction changes public
API, wire format, processing policy, gas or release artifacts. Final grouped and
paired results must identify the successor source explicitly. The measured
source inventory above describes the original port, not these new files.

Candidate15f2c8c adds one private production source and194 lines:
**308 → 309 sources;84,101 → 84,295 lines;92 public API source types unchanged**.
The first grouped gate retained all46 cases and exposed the unchanged previous
size ceiling; update that implementation-size inventory to these exact measured
values. Do not change the1,166-line retained-file limit, public API bound, any
processing/gas limit, or remove architecture checks. This is reviewed accounting
for the physical cache and rejection-validation implementation, not a runtime
capacity increase. First grouped results/fixture corrections remain separate
from final qualification.

The second group at bf8ca5c executed 49 cases: 48 PASS, 1 failure, no errors/skips.
The remaining failure precedes cold-open/rejection validation and reproduces
the eager inventory mismatch above. Its correction adds 24 lines to the existing
store source: **84,295 → 84,319**, still 309 sources/92 public API types. Three
new isolated inventory controls supplement the unchanged real same-epoch
rejection regression. This constructor mismatch is also present in the frozen
d220 source; the POC exercised it while qualifying complete retained-state
reconstruction. The focused correction is kept outside that frozen baseline,
with no claim that its new tests already passed there or that baseline PRs now
contain it. Subsequent native qualification must bind the new POC commit.

Run all ported storage owners together plus d220 root/source/cutoff/witness
controls, then the complete library and paired MyOS scenario gates. Full host
publication, rollback/lost acknowledgement, fresh-process restart and dependency
resolution must use the exact new artifacts. The donor's explicit post-split
replay limitation test is historical input to this qualification, not authority
to reintroduce an old algorithm limitation if d220 differs.

Lazy storage alone is not proof of bounded working memory or many-worker
scalability. Session history, explicit catalog inventories and global work
selection still have material costs. The POC must measure cold BlueId reads,
per-stage I/O/codec/PROCESS/publication cost, hot reuse, large rooted graphs,
cycles and many-entry closure sequences. Host scheduling and conflict fencing
remain separate from this library-agnostic port.
