# Counter example

The Counter acceptance document defines Alice's increment channel and Bob's
decrement channel. Register both Timelines, start the document, dispatch
`increment amount: 3`, dispatch `decrement amount: 1`, and read `/counter` from
the immutable snapshot. The result is `2`, the document epoch is `2`, and the
work counters show exactly two frozen PROCESS invocations and zero generic
fragments.

The executable release-owned coverage is
`CoreBehaviorIntegrationTest.counterRoutesAliceAndBobAndProducesTwo`; see the
quickstart in the repository README for application code. Historical per-step
timings remain in `../blue-basic`.
