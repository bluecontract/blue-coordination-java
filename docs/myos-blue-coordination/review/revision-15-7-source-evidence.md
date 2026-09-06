# Revision 15.7 — current-source evidence and equivalence limits

> Documentation-only appendix · 2026-09-06 · No runtime tests executed for this appendix

This appendix separates inspected source behavior, assertions already present in tests,
and hand-derived requirements for the proposed independent-lineage boundary.
It supports [21 — semantic equivalence and source reuse](../21-semantic-equivalence-and-source-reuse.md),
the EQ cases in [11 — validation](../11-validation-plan.md), and their mandatory mapping in
[16 — run manifest](../16-scenario-run-manifest.md). It is not a new specification profile.

## 1. Provenance and notation

The inspected local HEADs are Coordination `20fca9fd9934f367612c27348b8b6532d4029672`
and Language `be2260217d1dbab0c7b60bcbd28073a5955e2b7b`.
These are local baseline observations, not a claim that remote branches were checked.
All line references below describe the local source inspected on the date above.

`C:` means a path relative to `blue-coordination-java/`; `L:` means a path relative to
the sibling `blue-language-java/`. `LC:` abbreviates Language's
`blue-contracts-core/src/main/java/blue/language/processor/` directory.
`SPEC` means `L:blue-contracts-core/src/main/resources/specifications/blue-contracts-and-processor-specification-1.0.md`.
Test assertions are evidence of intended covered behavior, not fresh execution results.

## 2. Authored identity does not erase admission and history inputs

The SDK explicitly distinguishes semantic activation policies:
`C:src/main/java/blue/coordination/sdk/ActivationPolicy.java:12–19,95–101`:

```java
/** A new managed lineage begins when it is attached. */
FROM_NOW,
```

The conversion maps `FROM_NOW` to `ActivationMode.BIRTH_AT_ATTACHMENT`.
This is an explicit semantic choice in the current SDK, not permission for a cache hit,
database row, or first worker to choose a different birth boundary.

`C:src/main/java/blue/coordination/internal/ManagedOccurrenceResolver.java:333–385`
rejects unknown initialized/progressed content as `UNPROVEN_MANAGED_HISTORY`.
For a new authored draft, it derives `DocumentId.of(suppliedBlueId)` and returns
`TargetKind.NEW_AUTHORED` at position `-1`, with a draft requiring initialization.
The existing-lineage branch at `:479–535` first recognizes exact current content,
then distinguishes replayable authored position `-1`, initialized position `0`,
and retained epochs; ambiguous matching historical positions are rejected.

This is observable dispatch between different logical inputs, not merely two physical
ways to read one unchanged invocation. A physically evicted but durably admitted lineage
must retain or recover its admission/history authority; losing it is not a semantic reset.

`C:src/test/java/blue/coordination/sdk/SdkRetainedManagedEpochCatchUpTest.java:48–100`
explicitly admits B, advances it, then attaches its authored initial value to A.
It expects A to observe initialization and the later change, without executing B again:

```java
assertEquals(sourceHistory, b.history().stream()
        .map(revision -> revision.after().blueId())
        .toList(), "source history must not be reprocessed");
```

The same test expects retained source epochs `[0,1]` and A's cursor to advance from `-1`
to next epoch `2` (`:89–96`). Its other cases distinguish initialized, retained, and current
selections. It does not establish equality between this prior authorized admission and
a previously nonexistent source newly initialized inside a different parent invocation.

## 3. Initialization belongs to an invocation; receipt gas is an attribution

`LC:closure/ClosureExecutionSession.java:1083–1102` binds a newly activated child's
pending initialization cause to `owner.workIdentity()`.
Its event constructor at `:1581–1583` includes the containing invocation:

```java
long eventOrdinal = nextEventOrdinal++;
String occurrenceIdentity = IDENTITIES.eventOccurrenceIdentity(
        input.invocationIdentity(), eventOrdinal, eventBlueId);
```

An earlier committed initialization and a newly executed initialization can therefore
have different occurrence identities even when the event payload BlueId is identical.
`C:src/test/java/blue/coordination/sdk/SdkManagedDraftAcceptanceTest.java:745–812`
asserts creator-local initialization work, parent epoch `1`, and new child epoch `0`.
`C:src/test/java/blue/coordination/internal/ContractsPublicInitializationTopologyTest.java:211–282`
asserts that a later member's initialization failure rolls back every marker and publication.
These are current atomic-invocation facts, not existing support for an independently
published source initialization detached from a failed creator.

`LC:closure/ClosureProcessResult.java:1118–1150` partitions the invocation's gas trace:

```java
DocumentId residualOwner = managedTransitionReceipts.get(0)
        .documentId();
```

Trace entries without a receipt-bearing document owner go to this first receipt
(`:1131–1135`). SPEC `:1396–1406` explicitly requires the receipt sum to equal `totalGas`.
Consequently `admittedGas` is not a context-independent source initialization tariff,
nor an instruction that every consumer must recharge the producer's entire receipt.

Revision 15.7's frozen initialization policy/candidate accounting remains a proposal.
For the *same* logical invocation, source work 80 plus parent work 20 under limit 90
cannot become a successful warm-cache invocation costing only 20. Conversely, a prior
authorized source operation and a later import must first be compared as different operations,
including their individual gas, identities and aggregate accounting—not declared equal by fiat.

## 4. What the existing warm/cold test actually establishes

`L:blue-contracts-core/src/test/java/blue/language/processor/closure/FullLifecycleAdmissionTest.java:2191–2238`
constructs one admission input and calls `admitClosureWithLifecycleQueue(input)` twice.
The warm run performs fewer provider fetches; the test requires exact result, work-identity
and finalization parity. Its `assertExactParity` helper at `:3587–3614` includes gas and receipts.

This is strong coverage for physical caches under an unchanged logical input.
It does not compare `NEW_AUTHORED` initialization with earlier retained-history admission,
nor prove the proposed independent-source initialization accounting or shared-source API.
SPEC `:1993–2006,5122,5349` likewise separates representation/cache behavior from semantic work.

## 5. Ordinary ancestor FIFO is not the current managed immediate-binding selector

`L:src/test/java/blue/language/processor/InternalEventOccurrenceFifoTest.java:51–130`
asserts this ordinary PROCESS order, including delivery to the root through the middle scope:

```java
"leaf:T:A",
"mid:E:A",
"root:E:A",
"leaf:T:B",
"root:E:B",
"leaf:T:C",
"root:E:C"
```

The same file `:96–116` checks original event identity with the composed `/mid/leaf` path.
Its frozen-ancestor case at `:135–185` preserves already admitted occurrence delivery.
By contrast, `LC:closure/ClosureExecutionSession.java:3605–3641` collects managed bindings
whose `targetDocumentId` equals the emitting document and orders those immediate bindings.
That selector alone is not an ordinary PROCESS ancestor traversal.

Therefore A→B→C with C emitting E and B emitting nothing needs an explicit proved mapping.
Mandatory parent re-emission is not justified by this evidence: it could invent an emission,
alter provenance, reorder work, or duplicate an original ancestor delivery.
This is the equivalence obligation in 21 and EQ5, not a claim that all current paths agree.

## 6. Observable updates and the buffered-effects control

`LC:ScopeMutationExecutor.java:36–57` freezes dispatch before a patch and routes its updates
in the `continueAfterPatch` continuation. `LC:BatchPatchResult.java:291–310` retains records,
including patch-time before/after values when a later patch overlaps:

```java
FrozenNode before = record.beforeAtPatchTime();
```

Thus `0→1→2`, and especially `0→1→0`, cannot generally be replaced with one final
reference replacement when a containing document observes the nested Document Updates.
Equality of the final business value is not equality of work, gas, checkpoints or whole BlueIds.

The contrasting control matters. `LC:BufferedContractEffectExecutor.java:41–85` states:

```java
/** Applies patches, then events, then the optional termination request. */
```

Its patch loop completes before its event loop; `:181–182` enqueues application events.
A single handler buffering both patches before both emitted events can correctly expose
the final value to both later event handlers. There is no universal snapshot at each
source-code `emit` call. That control must not be confused with separate triggered E1/E2
handlers whose updates and ancestor observations interleave under the reference queue.

The current managed import at `LC:closure/ClosureExecutionSession.java:386–416` verifies
and installs one exact after-reference, drains its update work, then at `:428–450` admits
the receipt's root-event list with preserved occurrence identity and one target binding.
SPEC `:1388–1394` says these receipts expose managed Root emissions, not all transient deep work.
Accordingly final-after plus a flat Root-event list does not itself prove preservation of
the intermediate observations and ancestor queue order required by EQ1, EQ3, EQ4 and EQ5.
`SemanticObservationEvidence` is a minimum review seam, not a finished constructor or VM trace.

## 7. Terminal external failure and failed managed import are different current paths

`C:src/test/java/blue/coordination/sdk/SdkAcceptanceTest.java:578–686` covers a gas-exhausted
cyclic input, unchanged states/history, a later successful `detach`, and a successful later call.
`C:src/test/java/blue/coordination/internal/ContractsPublicLoopAndIsolationTest.java:197–226`
also asserts a durable non-commit feeder disposition without automatic endless retry.

In contrast, `C:src/main/java/blue/coordination/internal/ManagedEpochApplicationExecutor.java:185–201`
rolls back an incomplete/noncommitting import attempt without a successful application projection.
`C:src/main/java/blue/coordination/internal/CatchUpPlanStore.java:152–178` records evidence or
publication failure at the same cursor, including `BLOCKED` publication outcomes.
Neither fact proves the desired r15.7 managed terminal-progress mapping by itself.
Failed r1 must not fabricate its successful view/cursor; later r2 and an eligible repair
need the explicit continuity law required by EQ8, not permanent blocking as the target behavior.

## 8. Hand-derived obligations, not newly executed tests

The following labels belong to 11 and 16; their semantic rationale is in 21.
They are required paired checks, with hand-derived expectations still requiring implementation evidence.

| Case | What the evidence requires us to distinguish |
|---|---|
| EQ1 | Triggered E1/E2 intermediate reads `[1,2]`, not blanket final `[2,2]`. |
| EQ2 | Buffered-effects control legitimately reading `[2,2]`. |
| EQ3 | Per-patch and net-zero observable updates, not only endpoint equality. |
| EQ4 | P1 admitted during E1 before F2 admitted during E2; no flat-batch reordering. |
| EQ5 | Ordinary ancestor observation versus managed immediate-binding delivery. |
| EQ6 | Same-input physical reuse parity versus genuinely different admission/history inputs. |
| EQ7 | Frozen original recipients plus creator-local initialization and caused work where required. |
| EQ8 | Honest failed-operation projection and later eligible progress without pretend success. |

The objective remains one independently processed Agreement and many independently committed Orders.
Moving the source/consumer ownership boundary is a deliberate library semantic change, not a claim
that the current all-member rollback boundary already supports that ownership. Within a genuinely
shared core feedback invocation, current shared gas and atomic failure still matter. The first
implementation must prove the narrower observation and ownership mapping before optimizing it.
