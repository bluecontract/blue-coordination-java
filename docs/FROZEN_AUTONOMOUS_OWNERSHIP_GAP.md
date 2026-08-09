# Frozen autonomous ownership API gap

## Experiment outcome

Round 9 temporarily replaced the parent processing ownership projection with
the identity-preserving parent Root shell and required:

```text
processing Root BlueId == semantic Root BlueId
```

The experiment was rejected and completely reverted. The required focused run
executed six tests; five failed:

```text
ExactProcessingRootIdentityProbeTest
AutonomousRootIsolationTest
AutonomousChildOwnershipGuardTest
ExistingEmbeddedDocumentCatchUpTest
NestedEmbeddedCatchUpTest
```

Every failure occurred while deriving the processor-managed parent delivery:

```text
InvalidExecutionEvidenceException:
Retained active External Channel surface does not match the exact Root
(omitted=1, extra=0)
```

`SharedAutonomousChildTwoParentsTest` passed because its parent fixture uses
direct structural child materialization and does not invoke a parent-owned
embedded-revision workflow.

## Exact probe evidence

For `ExactProcessingRootIdentityProbeTest` at the rejected parent revision:

```text
document                 embedded-parent-B
semantic Root BlueId     DxuR4ZFzD9YvC63Eboyf7pDmdU5evWBh6ET47kfidayZ
processing Root BlueId   DxuR4ZFzD9YvC63Eboyf7pDmdU5evWBh6ET47kfidayZ
selected occurrence      /child
indexed parent channels  //coordinationEmbeddedChannel, //ownerChannel
verifier difference      omitted=1, extra=0
```

The exact Root exposes the embedded child's external channel surface after the
provider materializes `/child`. That child-owned channel is intentionally not
present in the parent's active subscription intervals, so the frozen verifier
rejects the delivery as incomplete before `processForPlatformCommit` executes.

The probe counters were:

```text
completed frozen PROCESS invocations        1
parent external attachment invocations      1
completed parent revision invocations       0
child source PROCESS invocations             0
parent revision applications                 0
published public child/parent events         0
journal rollbacks                            1
transaction retries                          1
```

The first frozen call is the parent attachment. The second, processor-managed
parent revision is rejected during delivery derivation and therefore never
increments the completed frozen-call counter. Transaction rollback restores
the parent to epoch 0 and removes the staged child session/link.

## Frozen public API inspected

The compact host uses these public boundaries:

```text
currentRootDeliveryPlanDeriver(...).derive(root, eventReference)
PlatformProcessInvocation.builder().deliveryPlan(...).nodeProvider(...)
processForPlatformCommit(root, eventReference, invocation)
subscriptionSurfaceProjection().projectInitial(...)
```

`PlatformProcessInvocation` accepts a delivery plan and `NodeProvider`, but no
autonomous-ownership mask. The delivery verifier evaluates the complete exact
Root surface. A pure reference is representation-invariant for BlueId, and the
provider can materialize it, but representation invariance does not change
ownership: materialization makes the child's contracts visible to the parent
verifier.

## Smallest missing capability

The frozen Contracts API needs a first-class ownership boundary equivalent to:

```text
process this exact semantic Root
while excluding these autonomous child-owned subscription surfaces
```

The boundary must affect delivery-surface verification and execution ownership
without changing the Root's exact identity or hiding ordinary child data from
parent semantics. Until that capability exists, the compact engine keeps the
explicit `autonomousOwnershipProjection` and documents that its processing
Root may differ in BlueId from the complete semantic Root.

No host-side planner, projector, or fallback processor was added in response.
