# Confirmed behavior corrections — 12 September 2026

The user confirms the intended behavior below and reports CTO confirmation for
the inactive-attachment case. These are bug corrections, not optional policies
to select. The prior published/local specifications and tests contain the
inactive-retarget restriction; their presence is evidence of the defective
baseline, not a reason to retain that restriction.

## 1. Preserve the real reaction when a cycle joins

In the [five-input reproduction](rooted-cyclic-join-live-obligation.md), C imports
A's earlier event and emits a new event addressed to B. B must perform its
required reaction. Adding an eventless A step before the attachment must not
make that reaction disappear. Expected final counters remain **A0/B1/C1**;
the original long ring's **A4/B2/C3** expectation remains unchanged.

Both the candidate and unchanged upstream RC9 produce the wrong nonterminal
result. The remaining engineering problem is preserving/executing B's original
obligation through ownership expansion. It is not a request for permission to
discard that obligation. A correct fix must also retain exact input identities,
ordered effects, logical gas, atomic ownership/publication and retry behavior.
Making immutable historical witnesses mutable, resetting gas, changing the
business oracle or bypassing publication fences is not an acceptable repair.

## 2. Detach B, then attach C at the same path

These operations are allowed when their ordinary protocol preconditions hold:

| Sequence at `/agreement` | Intended result |
| --- | --- |
| Detach B; later attach B | Successful same-lineage reattachment. |
| Replace active B with C atomically | Successful different-lineage attachment. |
| Detach B; later attach C | **Successful different-lineage attachment.** |
| Attach C at a fresh path | Successful new attachment. |

The third row is not a same-invocation detach/re-add claim. Its correction does
not authorize rewriting prior history, changing an immutable input row, or
aliasing C to B's lineage. A prior B reservation must not permanently prohibit
an otherwise valid C attachment. The committing processor must establish C's
new binding/occurrence generation and requested historical interval under exact
evidence; B's old history and already-frozen deliveries retain their identities.

### Consequences for the current changeset

- The earlier **rejection-only** correction is superseded as a final solution.
  Its successful rejection tests do not prove the desired behavior. Keep their
  old archives as historical evidence; replace the relevant semantic oracles
  with successful C attachment, ordered catch-up and later C reaction checks.
- Reassess the private Language rejection-evidence exception and Coordination's
  special rejection-only capture. Retain only machinery needed by the legal
  attachment or by independent exact-input/authentication invariants. Do not
  preserve an exception merely because it made the old negative test pass.
- The MyOS workaround that first rejects C, reattaches B, then replaces active B
  with C is not the intended user path. Test the direct detach-B/attach-C sequence.
  The original
  `RetainedManagedEpochCatchUpIntegrationTest.detachReaddAndRetargetRetireOldOccurrenceGenerations`
  already expected C catch-up from epoch zero, active C generation3 after
  B generation1, detach/re-add B generation2, and another removal reserving
  generation3, with completed histories
  for both sources. Commit `4de829b` changed it to the workaround under the old
  prohibition. Restore that user-path oracle; retain independent source and
  restart/authentication checks that remain valid.
- Update Contracts occurrence continuity and receipt rules, their maintained
  specification mirrors and affected fixtures together with implementation.
  Old exact released artifacts remain immutable. Rebind/regenerate the new
  candidate through the supported process and document real changed outcomes.
- The earlier clean/quality passes remain valid for their exact old source and
  semantics. They do not qualify this new behavior. Re-run focused causal and
  boundary tests first, then the final coherent library/MyOS acceptance tuple.

Required controls include inline/BlueId C, authored/initialized/historical C,
a newer C already embedded at another path, same-lineage reattachment, active
replacement, cold/warm evidence, suspension/retry/restart, duplicate deliveries,
gas-boundary rollback and cyclic joining. Failure must leave the original B
reservation and all independent source histories intact; success must not reuse
B's occurrence identity for C.

## Status

The legal detached-retarget runtime/specification correction is implemented on
separate candidate branches. Language's focused proof/PROCESS controls pass
94/94. Supported release-fixture regeneration changes derived identities, not
the existing executable fixtures' outcomes, gas amounts or ordering. Generated
documentation and its prerequisite conformance checks also pass with the
explicit Python 3.13 tool runtime. This is not yet the final clean library or
MyOS acceptance gate.

Coordination's legal-retarget adapter and restored MyOS test are committed;
their execution against the new sealed Language tuple remains required. The
cyclic correction has separately demonstrated the real all-owner publication,
exact-reference result/gas and resident restart for the five-input case under
an explicitly arranged valid schedule. Automatic scheduling, both root-start
orders, multi-interior cycles and the original complete ring still require
verification. A local publication proof is not a substitute for those tests.

The former candidate and its evidence remain preserved. No new behavioral
decision from the user or CTO is needed to classify either reproduced failure
as a bug. Library merge/release remains conditional on the complete coherent
baseline acceptance, including the original MyOS assertions.
