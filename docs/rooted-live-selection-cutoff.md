# Order-bounded LIVE selection

## Problem

The original MyOS ring/reconnect owner on MyOS `ae24f25`, Coordination `28d5744`,
and Language `430ee393` reached its unchanged 600-second READY deadline on input
13. It had not executed input 14 or restart. The retained run is a failure, not
acceptance. A sampled writer stack in `witness-memo-ring-thread-01.txt` passes
through `auditNextProcessingSelection`, `nextRootLiveInput`, rooted context
binding, snapshot verification, and canonical hashing. This identifies an
observed call path; it does not assign a fraction of elapsed time or prove GC
pressure or an isolated speedup.

`RootedCheckpointDriver.baseSelection` already knows the canonical registered
and root-local historical candidates before searching LIVE. Its existing
comparisons give retained work priority at equal source order. Nevertheless,
the unbounded search constructs a complete LIVE invocation even when that
invocation cannot win the comparison.

## Change and preserved boundary

The driver passes the earliest actual retained `sourceOrder` as an optional
**exclusive** bound to the internal LIVE search. The sorted entry loop stops
before capturing a root for an entry at or after that bound. Earlier entries
still undergo every existing routing, newness, exact-input, rooted binding,
terminal-publication, and declared-birth rejection check. The original
two-argument method delegates to an unbounded search.

The bound is the source receipt/local-step order, not the later attachment
barrier, and does not skip any work that could win the existing comparator.
No selection result is cached; no gas, public API, source fence, readiness,
join eligibility, publication or authentication rule changes. This is not a
shortcut for a source that has only a join-fenced LIVE input: that path remains
unchanged. It also does not deduplicate immutable history or cache by an
unchanged root head. Dormant selected source views and terminal records can
change independently of that head.

## Qualification

The new `RootedLiveSelectionCutoffTest` is authored but **not run**. It checks:

- no root capture when the first real entry equals the exclusive bound, using
  the existing routing-surface metric and an unbounded positive control;
- earlier NO_MATCH and retained rejected entries do not hide the next LIVE
  input; the later bounded-out input remains available, including after restart;
- an earlier LIVE input precedes genuine registered source history;
- the same exact entry loses the LIVE tie to its genuine retained source
  receipt, then remains pending and is processed normally;
- borrowed source isolation, positive actual gas, final values and retained
  history/restart checks.

Adjacent maintained controls remain necessary: `RootedSelectionCostTest`,
`RootedEligibilitySelectionTest`, `RootedTransportSelectionTest`,
`RootedSlicedSelectionTest`, `RootedJoinEligibilityTest`, and the automatic join
owners. Full SDK and original MyOS ring acceptance are pending. No deadlines,
assertions, exclusions or test budgets have been relaxed.
