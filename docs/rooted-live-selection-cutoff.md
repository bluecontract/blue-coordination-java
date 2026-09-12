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

The first focused batch on `8388354` completed **19/20**, not PASS. Its new owner
passed 3/4 methods; the earlier-LIVE continuation failed with
`Managed application input fences changed` at the unchanged consumer-head CAS.
The complete archive is `live-selection-cutoff-focus-01.tar.gz`, SHA-256
`8f261220199a1a3648e2802c3fb2f285bab98bc6074463cab76c22e141d0be8b`.

That test had already made `/other` an active forward child and submitted its
eligible LIVE5, then explicitly processed the parent's attach20. This violated
`ProcessingGateway.process`'s caller-established ordered-input-window
precondition. LIVE5 subsequently changed the consumer representation after
history work had captured attach20's consumer fence. The old unbounded driver
would make the same order comparison; the observed CAS failure is not evidence
that the cutoff changed selection. The passing equal-order test used the same
unsupported overtaking setup, so both setups are corrected. No CAS guard or
production behavior is changed to accommodate them, and the red archive remains.

The corrected `RootedLiveSelectionCutoffTest` is authored but **not run**. It checks:

- no root capture when the first real entry equals the exclusive bound, using
  the existing routing-surface metric and an unbounded positive control;
- earlier NO_MATCH and retained rejected entries do not hide the next LIVE
  input; the later bounded-out input remains available, including after restart;
- an independent root's LIVE5 is returned by the strict bounded search against
  a genuine pending source receipt11. After one actual prior historical step
  yields the fair turn, normal public audit/drain selects and publishes LIVE5,
  followed by the consumer's remaining history11;
- an independent receiver's next LIVE is the same exact entry as that source
  receipt. The bounded search excludes it while the unbounded search still
  returns it. Each root then publishes its own work under valid caller ordering;
- source and independent-root isolation on every historical application,
  positive actual gas, final values and complete retained history/restart checks.

These are genuine independent-root inputs and receipts, not a claim that the
same-root LIVE/history coexistence attempted by the original fixture is reachable
under a caller-established ordered window. The direct first-entry test still
proves zero capture versus a genuine unbounded positive. The equal-order scenario
has one earlier completed entry, so one root capture is legitimate; it does not
pretend that all prefix inspection is avoided. The production same-root tie
comparisons remain mechanically unchanged, and cross-root fairness is preserved
rather than replaced by a global timestamp-only policy.

Adjacent maintained controls remain necessary: `RootedSelectionCostTest`,
`RootedEligibilitySelectionTest`, `RootedTransportSelectionTest`,
`RootedSlicedSelectionTest`, `RootedJoinEligibilityTest`, and the automatic join
owners. Full SDK and original MyOS ring acceptance are pending. No deadlines,
assertions, exclusions or test budgets have been relaxed.
