# Completed root-stage boundary

`DefaultCoordinationEngine.processRootInputStage` and `processNextRootStage`
finish the same selected protocol work as their existing counterparts but omit
the subsequent `rootedReadiness` selection. The existing convenience methods
retain their behavior. Direct input, selected LIVE, retained local history and
managed historical selection use the shared execution bodies.

These are internal assembly bridges, not the completed public durable-storage
API. Their `ProcessingDrainReceipt.quiescent()` describes completion of the
selected work only: it does not assert that no later work is eligible. A host
must not map that flag to command completion. Source prerequisite/admission
gateways and public SDK result retention still require integration and review.

The boundary does not convert an incomplete physical failure into a protocol
checkpoint. A throwing mutable invocation must be discarded; only a fully
returned stage can be considered for preparation. Preparation and host atomic
publication remain separate, required operations.

`RootedCompletedStageBoundaryTest` executes real source operations with a
fault armed only at optional readiness, covers supplied and selected input,
and preserves the legacy negative control. Its transaction fault is injected
at the actual rooted publication `BEFORE_SWAP` boundary. The cold-storage test
`completedStageCanBeStoredAndColdReopenedBeforeReadiness` retains the completed
history before reaching the fault, destroys the original SDK owner, reopens
from bytes and the exact journal, and checks that no input is appended again.

This increment changes no wire codec or normal public SDK signature. It adds
39 production lines against the selected source (whose measured count before
this change was 87,763), yielding 87,802 lines, with 327 production sources and
96 public api/sdk source types. The existing shape gate remains enabled with
that exact ceiling. These checks are boundary evidence, not complete V12 or
concurrent publication qualification.
