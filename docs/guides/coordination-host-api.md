# Coordination API — interfaces and host usage

Based on `coordination-integration-prototype`, commit `b26344fb9dc72a01aae560208e6ec8802d8703db` (baseline: `3.0.0-rc.15`). Prototype additions are marked separately. This describes the existing API, not completed MyOS Java/JPA qualification.

**Coordination selects eligible work, establishes the required document views and history, executes it, and identifies what must be published together. The host supplies workers, execution authority, durable storage, transactions and recovery.**

## Main interfaces

| Interface / method | What it provides | How the host uses it |
| --- | --- | --- |
| `BlueCoordination` | Application facade for documents, Timelines, entries, operations, processing and exact values. | Register Timelines, admit documents, accept inputs and read results. A durable host obtains the facade from a fresh scope. |
| `events().from(timeline).exact(entry).submit()` | Acceptance of the original Timeline Entry without executing it. | Separate input acceptance from worker execution. Supports general messages and Operation Requests without synthetic operation wrappers. Acceptance becomes durable only after publication. |
| `processing().selectNextStage(root)` / `selectNextStageThrough(root, entry)` | Selection of the next eligible work; `Through` bounds it by the supplied entry's order while including earlier prerequisites. | The host provides capacity; Coordination determines what may execute. `selectStage(root, entry)` selects a specific input after the caller establishes the ordered window. |
| `SelectedProcessingStage` | A scope-bound, thread-affine selection with `context()` and single-use `execute()`. | Inspect and acquire the required owner authority before execution. The token itself is neither a durable work ticket nor a host claim. |
| `ProcessingStageContext` | Exact causes, known publication owners and their predecessor, history and topology facts. | Bind the calculation to the correct work and conditions that must remain valid at publication. |
| `ProcessingStageResult` | Complete-stage evidence, final owners, disposition, measurements and topology-related selection invalidation. | Prepare host history, progress, dependencies and effects. Fence the complete owner set, including owners acquired during execution. This result is not a database commit. |
| `ProcessingStageStorage` | Encoding/decoding of detached results and stable selection/result identities. | Retain evidence for publication, recovery and audit without retaining a live SDK or rerunning execution. |
| `CoordinationRecordStore` | Coherent reads, conditional atomic publication and reconciliation of an uncertain commit. | Implement the main persistence boundary. The adapter must publish Coordination changes and corresponding host changes in one transaction. |
| `CoordinationRecordAttempt` | Automatic tracking of point, range and absence observations, plus pending mutations. | Collect the calculation's dependencies. `prepare(...)` detaches the publication packet and closes its read snapshot. |
| `CoordinationRecords.Publication` | An immutable packet of conditions, mutations, required artifacts and evidence, with an ID and digest. | Publish or retry the exact packet without invoking business logic again. |
| `CoordinationImmutableObjectStore` | Bounded reads and writes of immutable bytes addressed by physical SHA-256. | Store state/history artifacts. This differs from `ExactNodeProvider`, which supplies Blue content identified by BlueId. |
| `RootedCoordinationStorage.openLogical(...)` | A fresh SDK over a coherent, tracked attempt, without rebuilding state through command replay. | Isolate worker calculations and reopen retained state after restart. The overload without a separate journal includes the journal in the same attempt. |
| `LogicalScope.stage()` | Flush pending library changes into the attempt and retire the SDK owner. | Finish calculation before `prepare(...)`. **This does not publish a database transaction.** |

Supporting gateways: `documents()` admits documents/closures, `operations()` constructs targeted calls, and `snapshot()` / `history()` expose state and revisions. `inspectReadiness()` separates observation of subsequent work from the completed result; it is not a durable command-completion receipt.

## Storage contract and execution lifecycle

```java
ReadScope open(Address address);
Resolution publish(Publication publication);
Reconciliation reconcile(Address address, String publicationId, Bytes digest);
```

- **Coherent reads:** every read in an attempt observes the same snapshot, including data loaded later.
- **Versioned absence:** never-existing records differ from deleted records with tombstones; deletion/recreation cannot revive stale assumptions.
- **Complete predicates:** a truncated result page cannot establish that earlier work or another owner does not exist.
- **Complete publication:** validate every condition and artifact; atomically apply all required Coordination and host changes. No database write lock spans business execution.
- **Uncertain commit:** `UNKNOWN` requires reconciliation of the original ID and digest. It is distinct from `CONFLICT`; do not immediately repeat execution. `COMMITTED` / `ALREADY_COMMITTED` confirm the original publication; a conflict requires a fresh read and selection.

Typical lifecycle:

```text
open → openLogical → select → acquire host authority → execute
     → retain result evidence → stage → prepare
     → atomically publish Coordination + host changes
```

The host may accumulate several **complete** outcomes before publication. Check time/count budgets between operations, never between workflows within one entry or inside an atomic graph/catch-up operation. A crash loses the calculations in an unpublished window.

| Stage disposition | Host meaning |
| --- | --- |
| `COMPLETED` | Calculation finished; its outcome still requires publication. The business outcome may also be a deterministic refusal. |
| `WAITING` | Specific evidence or earlier work is required; retain the dependency and a continuation obligation. |
| `NO_WORK` | This selection found no runnable work; it does not establish global quiescence or command completion. |
| `NONCOMMITTING` | Discard the mutable attempt; do not publish partial state. |

## History and prototype additions

`ManagedEpochReceipt` describes an established source transition. `ManagedEpochApplicationReceipt` describes a particular consumer's application of that transition. Source history and consumer import progress are separate facts.

| API | What it provides / host usage |
| --- | --- |
| `advanced().sourceHistoryPrerequisites(root)` / `selectSourceHistoryStage(...)` | Dependencies on missing source work and its separate selection/execution. The host publishes the source outcome, then separately resumes the parent. Sufficient existing history does not require recalculating the source. |
| **Prototype:** `SourceHistoryRequest`, `sourceHistoryRequests(root)` and corresponding overloads | Bind a prerequisite to its original requesting instances and source instance; replacing the source does not automatically redirect old work. |
| **Prototype:** `DocumentInstanceRef` / `instancePosition(...)` | Distinguish document lineage from an execution and identify an exact instance/epoch/invocation position. Host identities do not change Blue content. |
| **Prototype:** `retainedRevision(...)` / `retainedRepresentation(...)` | Read the original numbered revision/receipt or exact checkpoint representation independently of today's head. |
| **Prototype:** `recordedResult(instance, entryBlueId)` / `originalResult(entryBlueId)` | Read that instance's result or the original command observer's result without recomputing against current state. |
| **Prototype:** `retireInstance(...)` / `startInstance(...)` | Explicitly end an execution instance and prepare a new one from a retained position. Dedicated scopes, blocker checks and publication are required; `PREPARED` is not a commit. |

**Mapping MyOS session deletion to `retireInstance()` is not settled.** A processing session and retained history can have different lifecycles. This table describes prototype capabilities, not a requirement to retire an instance whenever the host deletes a session. In the current prototype, processing after retirement must use root-specific stages and instance-bound source requests; legacy aggregate drain/execute paths are explicitly guarded.

Sources: [processing](../../src/main/java/blue/coordination/sdk/ProcessingGateway.java), [stage result](../../src/main/java/blue/coordination/sdk/ProcessingStageResult.java), [SDK storage](../../src/main/java/blue/coordination/sdk/RootedCoordinationStorage.java), [adapter contract](../../src/main/java/blue/coordination/api/storage/CoordinationRecordStore.java), [execution instances](../reference/document-instances.md).
