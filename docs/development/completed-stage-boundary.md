# Completed root-stage boundary

`DefaultCoordinationEngine.processRootInputStage` and `processNextRootStage`
finish the same selected protocol work as their existing counterparts but omit
the subsequent `rootedReadiness` selection. The existing convenience methods
retain their behavior. Direct input, selected LIVE, retained local history and
managed historical selection use the shared execution bodies.

The engine methods are internal assembly bridges. Their
`ProcessingDrainReceipt.quiescent()` describes completion of the
selected work only: it does not assert that no later work is eligible. A host
must not map that flag to command completion.

The SDK exposes `ProcessingGateway.processStage(root, input)` and
`processNextStage(root)` returning `ProcessingStageResult`. This result deliberately
has no quiescence flag. Its closed dispositions distinguish a completed current
stage, prerequisite wait, no runnable selection, and a noncommitting publication
failure. Existing exact entry, gas, managed and retained-local evidence remains
available, and successful materialization retains entry results in the SDK map
before return. NO_WORK still needs atomic host wake protection. COMPLETED is
not durable publication, READY output, or whole-command completion.

The existing source-prerequisite gateway was also inspected: it executes either
one admission or `executeRootSelection`, then retains that exact result. It does
not call `rootedReadiness` or retry the requesting consumer in that call. Its
full durable owner/source/admission integration remains outstanding.

The boundary does not convert an incomplete physical failure into a protocol
checkpoint. A throwing mutable invocation must be discarded; only a fully
returned stage can be considered for preparation. Preparation and host atomic
publication remain separate, required operations.

`RootedCompletedStageBoundaryTest` executes real source operations with a
fault armed only at optional readiness, covers supplied and selected input,
and preserves the legacy negative control. Its transaction fault is injected
at the actual rooted publication `BEFORE_SWAP` boundary. The cold-storage test
`completedStageCanBeStoredAndColdReopenedBeforeReadiness` retains the completed
history and SDK entry result before reaching the fault, destroys the original
SDK owner, reopens from bytes and the exact journal, and checks that no input
is appended again. Public gateway controls also reject foreign/closed owners.

The original internal-boundary increment changed no wire codec or normal public SDK signature. It added
39 production lines against the selected source (whose measured count before
this change was 87,763), yielding 87,802 lines, with 327 production sources and
96 public api/sdk source types. The existing shape gate remains enabled with
that exact ceiling. The subsequent additive SDK gateway/result increment updates
the measured ceiling again and adds one public source type; existing signatures
remain intact. These checks are boundary evidence, not complete V12 or concurrent
publication qualification. The granular runtime adapters, prepared stage encoding
and host continuation/finalization lifecycle remain required.
