# Dormant proof and same-cause peer acquisition (test-only proposal)

This isolated branch starts at Coordination
`cf97d6d07b767b82b6935ca2bfb6db12ab0ade9c`. It changes no production code,
dependency, gas policy, source fixture, or existing test. The new test has not
been compiled or executed. A passing setup or diagnostic is not diamond
settlement acceptance.

## Problem and executable control

The existing diamond is A→B→C and A→D→C; C later attaches saved authored A.
The new control first performs and settles C.attach(A) at 10 and C.detach(a)
at 20, before constructing that diamond. It then keeps the existing public
attachment, two A token emissions, eventless tail, and C reattachment sequence.
All inputs use the maintained graph template, FULL_HISTORY admission, one
Timeline, exact-version requests, and the release's normal execution policy.
No snapshot, occurrence row, source receipt, or result is manufactured.

The two extra operations leave a genuine retired C.a row. The test reads its
reserved generation rather than assuming an epoch or copying old input IDs.
Before processing the reconnect, the ordinary adapter capture demonstrates
that B's snapshot already contains A and D through dormant evidence. It logs
their exact identities and verifies that the retired row is inactive with no
historical cursor. This is a capture observation, not a dispatched PROCESS.

The final oracle remains A=0, B=1, D=1, C=2 with both exact origin-labelled
forwarding tokens. Normal public drain must reach quiescence within 160
selections. BLOCKED is a failure, not an acceptable result. The test preserves
every pre-reconnect receipt, the original frozen A frontier and C plan
generation, successful retained gas identities, all-ready status, and
head/history/gas/plan stability over restart and a zero-work drain. No claim
of zero resource demands is made without observing the actual execution.

## Proposed selection boundary, not an implemented repair

`ContractsClosureAdapter.captureDormantDependencies` runs before
`processClosure` creates the new input. It currently refreshes the whole
dormant A frontier, including old peer D. Later read expansion intentionally
cannot replace an already selected D primary. The candidate investigation is
to choose a proved peer primary at this earlier capture boundary instead.

1. Preserve the requested root's original current owner SCC, active forward
   domain, pending historical positions, direct deliveries, cause, and policy.
   Derive peer eligibility from absence from the live forward domain, not
   absence from all retained proof records. A pending historical dependency is
   still an obligation, not a freely replaceable retired record.
2. Require a real retained C join fence for the exact original external cause
   and complete order key. Group candidate peers by their verified owner SCC,
   preserve canonical component ordering, and reject a group intersecting the
   live/pending receiving domain or lacking the proved prospective return.
   This does not justify delaying arbitrary one-way observers.
3. For a preceding independent peer, obtain its actual local terminal using
   the raw retained selector. Use unchanged `captureRootedJoin` to authenticate
   canonical C work, plan, source receipt, complete original cause/boundary,
   occurrence/cursor and current consumer fences. Require the peer's successful
   independently published prefix, exact current head and publication chain;
   its frozen A source must equal the registered source in every existing field.
4. Only then propose D's exact completed same-cause prefix as the primary,
   while A's whole authenticated proof still contains old D. Preserve the
   complete retired-row inventory and every original active/pending view. The
   later real live join, not this read choice, grants ownership. Normal PROCESS,
   source validation, owner acquisition, gas and atomic publication still run.
5. If the prerequisite is not available, report the actual same-cause receiver
   work. Do not pretend A lacks historical source evidence, execute another root
   inside a point call, change an order key, or publish a speculative prefix.

The proposed authentication path need not recurse into LIVE capture:
`captureRootedJoin` calls the ordinary retained capturer, and the peer's local
selector uses `captureRootedState`, not `captureDormantDependencies`. Calling
the full LIVE/root scheduler from inside dormant capture would reintroduce
recursion and is not proposed. Competing applicable fences must all be checked.

## Concrete construction prerequisite still open

Language 430's existing mixed-witness entrypoint is
`ClosureEvidenceFactory.rootedReadExpansion(original, ..., sourceProofs)`.
It correctly preserves every original primary and occurrence. B's valid
retired-row snapshot already contains A and D, so that factory cannot replace
old D with the proposed peer prefix. `RootedInputExpansion.verify` rejects the
change even if Coordination has proved the peer's publication.

A plain replacement snapshot is not an alternative: `AffectedClosureSnapshot`
requires both endpoints of every row and a complete matching component
partition; ordinary graph/finalization would bind A's old D pointer to the new
D primary before the separate witness contexts exist. `RootedWitnessFrame.bind`
verifies that input before assigning roles. Its read-expansion factory can
authenticate separate source proofs only after a valid original rooted input
exists. Dropping the retired row or its required endpoint is not authorized by
the absent-source-path exception in `verifyOccurrenceValues`.

Therefore a production patch must first demonstrate a supported fresh-input
construction that installs both complete witness contexts without replacing an
already admitted primary or dropping inventory. This note does not claim an
existing factory call solves that step, and does not claim a general protocol
gap. If a narrowly authenticated Language construction seam is necessary, it
requires a separately evidenced library change and review. No Language change
is included here.

## Existing obligations and next gate

RCP-SCOPE-04 distinguishes immutable proof from a live dependency;
RCP-OWN-01/02/03 preserves original ownership and requires exact causal-view
acquisition at a real join. RCP-CAUSE-01/03 preserves the frozen source interval
and fixed-input semantics. These rules do not allow an ambient newer head.
Contracts retains inactive rows in complete inventory and verifies their exact
endpoints; absence of work does not make the records disposable.

The original helper was introduced by `156b829` for actual detached-source
topology changes, not merely the subsequently corrected same-invocation
reservation rule. `RootedDetachedPairReconnectTest`'s new-child-at-attachment
and future-topology exclusion cases remain required, along with recreated
occurrence generations, same-SCC entrypoints, source-first gas/failure parity,
and the original fourteen-input ring. They are not replaced by this reduction.

Parent-owned first selector (reuse the frozen candidate's exact dependencies):

```sh
./gradlew test --tests blue.coordination.sdk.RootedDormantPeerReconnectTest \
  --no-daemon --max-workers=1
```

Do not update dependency pins, enable artifact-write mode, or raise budgets to
make the diagnostic run. Archive a prefix failure as such; it is not evidence
that the final reconnect oracle was reached.
