# Full-lifecycle admission Phase 2 semantic delta

## Verdict

All 32 topology records are classified: 22 remain in the strict baseline set,
nine are intentional admission-lane evidence, and `P5.1.merge-two-cycles` is a
necessary carry-forward from normal full-lifecycle admission. There is no
unclassified scenario.

The historical package under `stabilization/cyclic-topology-round` remains
immutable. The candidate also binds the accepted Phase-1 Contracts release;
identity fields mechanically derived from that release are provenance changes,
not additional semantic findings.

## Classification

| Class | Count | Records |
| --- | ---: | --- |
| Strictly stable baseline | 22 | P2.1, P2.3, P2.4; both P3.1 records; all three P3.3 records; P4 pre-detach, partial detach, dissolution, post-detach, re-add, reformed probe, frozen edge, later occurrence; P5.2-P5.5; P7 ordinary nested scope and cyclic root-only operation |
| Intended normal/compatibility admission evidence | 9 | P4 initial; P6 static three-member, both static-order forms, both dynamic-topology forms, late failure, bounded C-CLO08; P7 cyclic root-only admission |
| Causal admission carry-forward | 1 | P5.1 merge two cycles |

The machine-readable file lists every exact scenario ID.

## Why P5.1 changes

P5.1 constructs two already-finalized SCCs (`A-B` and `C-D`), marks all four
members `initialized=false`, adds an inactive `A -> C` occurrence, admits that
closure, and only then activates the occurrence. The production lane now calls
`admitClosureWithLifecycleQueue`. That API is the normative admission entry;
the old `admitClosure` API is explicitly bounded compatibility and does not
execute a complete lifecycle queue.

The complete session selects initialization by SCC, freezes that component's
current exact heads, drains lifecycle/event work, installs its marker batch,
and finalizes before selecting the next SCC. This is materially different from
the bounded session's single all-document marker batch. The non-root `C-D`
component is therefore an admitted, re-finalized current component before the
later merge:

- baseline C/D: `DoPfEZXNg49tyiBBoL8wWHGw1mVjwCdY67xG3ZYmTJa8#0/#1`;
- full-lifecycle C/D: `DS8sFKWh65uaXrHYfVwekPeuDGmQ6Wdn7XxwBD2ASNjn#1/#0`.

The merge must consume those exact current heads, so its MASTER, member
indices, cyclic proof, binding identities, closure identities, and
representation-sensitive gas trace necessarily cascade. The exact scalar
change is `1149 -> 1170` gas, `280 -> 292` trace entries, and `597 -> 605`
admitted gas for the one merge work occurrence.

The semantics do not drift: the result is still atomic, successful, committed,
and quiescent; work order is still only A; component identity remains
`sha256:18583238f080c03aad3b234101d08cef1bb012c620d27c9a7f10bfc5b4fa0de3`;
generation remains 2; the member set and all six occurrence lineages, source
paths, targets, and graph-change kinds/order remain the same; no public event
is emitted.

Most importantly, a lane-isolated capture with the historical release inputs
held byte-identical reproduced this P5.1 delta. The other records outside the
admission/P6/P7 set stayed exact. That isolates the cause to normative
admission rather than an unrelated processing regression.

## Source anchors

- `BlueClosureContracts.admitClosure` documents bounded, nonconforming
  compatibility; `admitClosureWithLifecycleQueue` documents normative complete
  admission.
- `ClosureExecutionSession.executeAdmissionCause`,
  `runPendingInitializationBatch`, `runPendingInitializationComponent`, and
  `installInitializationMarkers` own deterministic SCC initialization and the
  per-component marker/finalization boundary.
- `ContractsPublicComponentMergeSplitTest.mergeAdmission` constructs the two
  SCCs, inactive A-to-C occurrence, and four uninitialized snapshots; the test
  then admits before invoking the merge.

The exact classification and protected invariants are in
`full-lifecycle-admission-semantic-delta.json`.
