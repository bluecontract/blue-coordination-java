# Counter example

The Counter acceptance document defines Alice's increment channel and Bob's
decrement channel. Register both Timelines, start the document, append
`increment amount: 3` and `decrement amount: 1`, then call `drain()`. The
environment selects both entries in canonical order; neither append identifies a
recipient or calls PROCESS. Read `/counter` from the immutable snapshot. The
result is `2`, the document epoch is `2`, and structural counters show exactly
two external PROCESS invocations and zero generic fragments.

The executable release-owned coverage is
`CoreBehaviorIntegrationTest.counterRoutesAliceAndBobExactlyOnceWithoutGenericSplitting`;
see the quickstart in the repository README for application code. Historical per-step
timings remain in `../blue-basic`.
