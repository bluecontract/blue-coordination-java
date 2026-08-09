# Host time versus frozen semantic time

User-visible dispatch time contains two materially different costs:

- frozen semantic time: Contracts resolution, workflow execution, and BEX;
- Coordination host time: exact routing, immutable object retention, layout
  updates, revision publication, receipts, and catch-up orchestration.

Metrics report these phases separately. A multi-second PayNote operation may be
dominated by frozen semantics while Coordination host work remains tens of
milliseconds. Performance gates therefore evaluate append, route lookup,
frozen PROCESS, layout, companion-delta commit, unattributed host overhead, and
total time independently.

The standalone `blue-basic` project retains historical Counter, whole-request,
1-vs-61 workflow, Wadowice PayNote and NBA timing campaigns. Its percentile
campaign uses 200 samples for append/routing micro-paths and 30 for
processing/catch-up paths. These metrics are diagnostic evidence only. The
library's own integration and scenario suites assert the corresponding semantic
invariants, including zero generic fragments and embedded-only cuts, and are
the suites enforced by `releaseCheck`.
