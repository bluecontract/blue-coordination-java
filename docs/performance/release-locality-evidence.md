# Coordination release performance and locality evidence

Wall-clock measurements are observational evidence only. The correctness gates
remain exact identity, provider-demand, gas, and trace equivalence.

## JMH measurements

`SubscriptionProjectionPlanningBenchmark` measures complete initial
subscription projection and sparse indexed planning at 10, 100, 1,000, and
10,000 active Timeline Channels. Exactly one Channel matches. Its auxiliary
counters record snapshot occurrences and encoded bytes, candidate count, exact
provider demands and returned bytes; fixture output records the projection
digest and delivery-plan identity.

`FragmentAdmissionBenchmark` measures:

- Event splitting followed by first admission;
- first admission of an already split inventory;
- idempotent repeat admission with canonical-byte verification.

It uses 10, 100, and 1,000 payload leaves. Auxiliary counters record the
fragment count, encoded inventory bytes, admitted fragments, and idempotent
duplicates. Fixture output records the inventory identity. The configured JMH
GC profiler supplies allocation evidence, while JSON results retain the
elapsed-time distribution.

Run:

```text
./gradlew jmh
```

The machine-readable output is
`build/reports/jmh/jmh-results.json`; derived CSV and Markdown summaries are
written beside it.

## Deterministic locality and semantic gates

The following tests deliberately avoid timing assertions:

| Required shape | Executable evidence |
|---|---|
| Deep embedding with one selected leaf and unrelated siblings | `CoordinationDocumentSplitterDeepLocalityTest.shouldDemandOnlySelectedChainsAndAllowListedBodies` |
| Selected operation bodies versus large decoys, exact demand bytes, and no forbidden reads | `CoordinationDocumentSplitterLocalityTest.shouldDemandOnlySelectedSpineAndBodiesFromProvider` |
| Inline/reference/direct, cold/warm provider matrix | `CoordinationDocumentSplitterProcessingMatrixTest.shouldPreserveProcessSemanticsAcrossSplitRepresentations` |
| Inline/reference/partial/fragmented and cold/warm/batched/one-fragment flagship variants | `CoordinationComplexEmbeddedDeterminismFlagshipTest` |
| Large finite Composite membership, exact gas, and full ordered trace | `CoordinationRuntimeGasScalingTest.shouldRetainTheFullTraceForA129MemberCompositeScan` |
| Large All-Timelines projection surface | `TimelineSubscriptionProjectionTest` (513-member projection case) |
| PROCESS gas and trace equivalence across physical representations | `CoordinationRuntimeGasIntegrationTest.shouldProduceTheSameLogicalTraceForEquivalentRuntimeSessions` and the flagship report |

Together these tests report exact provider request order and bytes, selected
versus total graph bytes, fragment counts, plan and final Root identities,
PROCESS gas, and ordered trace without converting latency into a semantic
assertion.
