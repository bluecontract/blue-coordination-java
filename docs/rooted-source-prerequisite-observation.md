# Observing retained source-prerequisite authority

## Ambiguity and concrete schedule

A real stopped rooted PROCESS discovers an authored source C and emits an
ADMISSION prerequisite for parent P at its frozen cutoff. Before the host
captures that command, another caller can independently admit C and process
its earlier inputs. P has not changed or retried. The existing
`sourceHistoryPrerequisites(P)` correctly returns no remaining action, but the
same empty list also means that P's requesting authority has gone stale.
Previously that harmless selection read removed satisfied correlation, making
those two outcomes impossible to distinguish afterward.

## Minimal query contract

Both `AdvancedCoordination` and the concrete engine expose:

```java
SourceHistoryPrerequisiteObservation observeSourceHistoryPrerequisite(
        SourceHistoryPrerequisite previouslyEmitted);
```

The observation contains `status` and `Optional<SourceHistoryPrerequisite>
pending`, with these closed outcomes:

- `PENDING`: the original requester remains current and `pending` is a newly
  selected exact descriptor. It can be executable source work or explicit WAIT.
- `SATISFIED`: actual retained requesting correlation remains current and the
  existing source selector proves complete required history strictly below the
  frozen cutoff. `pending` is empty.
- `STALE`: actual requesting correlation is missing, its owner fences changed,
  or its exact logical requester already has a terminal publication receipt.
  `pending` is empty. Absence is never interpreted as satisfaction.

The query verifies the original requesting root, invocation, processor-issued
demand, source lineage, authored BlueId and cutoff against the retained stopped
attempt. A changed root/source/authored identity/cutoff at a retained key throws
`IllegalArgumentException`; an unknown invocation/demand key is STALE. Physical
selection identity, work, source head, journal/route revision and surface fields
may have changed: observation refreshes those fields, while execution continues
to require the complete exact current descriptor. It grants no new authority.

Satisfied correlation survives calls to the old selections method and source
resolution. Stale correlation can still be removed. No success is manufactured
after correlation is lost or when a separate runtime never observed the actual
processor demand. Runtime reconstruction must restore genuine processing
correlation; a serialized descriptor alone is not proof.

The existing owner epoch/BlueId/graph fences remain in place. A point read of
the existing rooted terminal publication key additionally detects a consumed
requester, including terminal rejection with no changed owned head. This is the
same key already used by rooted publication/replay, not a new graph scan or a
new source-birth policy.

Observation does not execute or retry P, admit/process C, or change calculation,
gas, ordering, receipt, or publication semantics. It uses the existing source
preparation/completeness checks. Provider reads may retain immutable exact
evidence; the SDK brackets them in a fresh lookup scope and closes that scope
on success or exception, as for the existing selection API.

## Rejected alternatives

Empty-list-as-ready conflates satisfaction with stale/missing authority.
Retaining the old physical descriptor as an execution permit incorrectly
freezes operational journal/route/source fences. Re-executing P just to learn
readiness changes work and metering. A new identity hash would merely duplicate
the already retained frozen operands and would not prove satisfaction; no new
proof-looking identity or publication protocol is introduced.

## Focused controls

`RootedSourcePrerequisiteObservationTest` uses actual SDK discovery, independent
source admission/progress, and the original entry. It covers satisfaction while
P stays unchanged, harmless old-query compatibility, refreshed pending
descriptors, explicit completeness waits, absent and forged authority, provider
scope cleanup after exceptional queries, and terminal rejection under the same
requester with unchanged owned head. Each genuine observation also preserves the
existing actual PROCESS-boundary phase timer, including exceptional paths; the
fixture first asserts that its real stopped PROCESS made that timer nonzero.
The terminal negative calibrates successful G in a separate fixture, then uses
one fixed G−1 policy for both the original stopped attempt and terminal retry.
Existing `RootedSourcePrerequisiteTest`
and `RootedSourceDiscoveryTest` remain the execution/selection compatibility
owners. Verification is focused SDK tests plus Javadoc on Java 17 against the
immutable baseline Language/BEX/catalog tuple; no export or full suite is claimed.

The final focused gate passed **20/20**: Observation 6, existing Prerequisite 11,
and Discovery 3. Javadoc passed. A separate causal red run removed only the
terminal-ledger guard: the unchanged-head terminal requester incorrectly
reported SATISFIED instead of STALE (1 test, 1 expected failure). The guard was
restored for green. This is not a full library or downstream application gate.

Exact commands, immutable upstream pins and per-file XML hashes are retained in
`build/source-prerequisite-observation-evidence/receipt.json`; green XMLs are in
its `green/` sibling and the causal red XML in `terminal-fence-red/`. Green XML
SHA-256 values, in the owner order above, are:

```
3abef4e9b5bf5619cd4b45f404a228140e255633aad13e1f5dbc11df1dbde1ec
a1a2e44624cbde427984bd9381212d16fc9aea886a6e8acdd6e5381002c19e4c
cc7c4f5522b4100ae6bfb8f6b3c7b9c975327f2d61b597d2738b7a015fdb36d6
```
