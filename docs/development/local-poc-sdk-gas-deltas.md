# Reviewed local POC SDK gas snapshots

These are local development expectations, not a published release or a second semantic profile.
The owning Language runtime now meters actual reverse-route expansion and containing placement.
Weights and the 100000 shared limit are unchanged. All changed literals were reviewed independently
against the graph, runtime counters and existing tariff before installation.

| SDK witness | Prior → reviewed gas | Owning explanation |
|---|---|---|
| Finite two-member ring | 1364 → 1380 | Three patches × one containing route × (2 expansion + 2 placement), plus two events × one route × 2 = 16. |
| Finite three-member ring | 1787 → 1831 | Four patches × two routes × 4, plus three events × two routes × 2 = 44. |
| Five-member shared-anchor graph | 3768 → 3968 | Four vertex-simple reverse prefixes per emitter: eight patches × four routes × 4, plus nine events × four routes × 2 = 200. |
| Two disconnected rings | 2746 → 2778 | Two independent increases of 16; each result is 1373 → 1389. |
| Detach | 736 → 754 | Two patches still observed through the surviving containing edge add 8; the second acyclic patch causes one actual reference update, costing 10. |
| Re-add | 1260 → 1268 | Two placement routes add 8; joint cyclic finalization adds no extra containing-reference charge. |
| Post-detach ordinary call | 707 → 707 | No reverse observer remains for this emitter. No additional propagation charge. |

The loop snapshots change their exact cutoff, not their limit or rollback law:

- Plain loop: 742 → 731 started steps and 99967 → 99998 admitted gas. The reviewed tariff gives
  `265 + 365 * 273 + 88 = 99998`: fixed work, 365 completed alternating pairs, then the last A
  prefix. The next `workflowStepExecuted` charge of 3 cannot fit the remaining 2. The former tariff
  also reconstructs exactly: `265 + 370 * 269 + 139 + 33 = 99967`, before a rejected handler call.
- Dynamic loop: 712 → 701 started steps and 99997 → 99998 gas. Nonrepeating counters cost 457;
  the admitted repeating work at N=701 costs `142 * N - 1 = 99541`. The next dequeue costs 5,
  with only 2 remaining. Previously step 712 entered after its dequeue at 99997, then could not
  afford scope opening. Mandatory route work accounts for the earlier cutoff.

The temporary diagnostic run intentionally failed after collecting exact gas/count comparisons;
it did not accept new oracles. All other business-state, order, event, identity, rollback, repair
and fresh-run parity assertions completed. Every captured gas value equals its counter sum.
The two fresh plain-loop runs also matched their entire `GasLoopEvidence` records, not only gas.
The diagnostic switch and deferred assertions were removed after review. Normal exact assertions
remain and must pass a normal rerun.

Retained local evidence: `build/readiness-evidence/sdk-diagnostic-20260906-DjTq4d/` (JUnit and HTML).
This diagnostic run is not a passing regression. Cyclic aggregate artifact review is separate.
