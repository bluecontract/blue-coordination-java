# Library optimization: outcomes before MyOS confirmation

Status: acceptance plan with a qualified SDK storage regression for P1.
It does not claim the long MyOS graph case is solved. Physical storage changes
are described separately in [granular persistence](poc-granular-persistence-design.md).

## Why this gate is needed

A passing functional test or a faster isolated helper is not sufficient evidence
that an optimization removes meaningful work from the real durable path.
The batch29 selected MyOS command still performed 125 complete-result decodes,
622 publication-reference decodes and 2,210 object GETs / 714,305,558 bytes.
Those GET counters are library object-port traffic, not PostgreSQL network bytes.
Actual processor-core CPU was 0.291 s; complete-result decoding was 19.278 s CPU.
The enclosing receipt-decoding scope was 39.801 s CPU and includes nested work:
these times must not be added. Representation-history lookup was only 0.193 s CPU.

Therefore a history-lookup improvement alone is not a demonstrated solution to
this slow command. Each package must name its intended reduction before coding,
then demonstrate that reduction on actual library processing/storage paths.

## One optimization card per package

Record: exact workload and input/source hashes; current redundant work; change;
hard mechanism/scaling target; semantic equality oracle; timings by phase;
unchanged costs and exclusions; baseline/candidate results; and the narrow MyOS
confirmation to run. Keep failed measurements and test attempts.

The same test/input must run before and after. An old implementation must fail
the new mechanism budget for the expected reason, while both versions retain the
same correct semantic outputs. A test setup or functional failure is not a valid
performance baseline. Do not weaken equality or skip required history to improve
the measurement. A controlled old-key/new-key experiment isolates that code
change; it is not a claim about every difference between historical releases.

## Concrete packages and targets

| Package | Library workload | Hard target |
| --- | --- | --- |
| **P1: duplicate result reconstruction** | Real rooted SDK publications, retained immutable bytes, producer close, fresh readers with MyOS's 40 MiB record / 32 MiB index-value bounds and one shared bounded cache | While a fitting complete frame remains retained, changing only the reader byte cap causes **zero additional full result reconstructions** and no second retained copy of that frame. Ordinary cold verification remains |
| **P2: session history access** | Fixed small graph; 5/20/50 epochs initially, cold reopen, select one specified position and append one successor; include multiple representation positions within an epoch | **Zero unrequested historical payload decodes and zero rewrites of unchanged historical payloads**. Current-session descriptor stays bounded; index path work is reported separately |
| **P3: ordered catch-up windows** | Import K positions from a much longer N-position history, crossing page and same-epoch boundaries | Exactly K required positions processed in order, with no unrelated-position payload reads or restarting the prefix for each window. Bounded window residency |
| **P4: result/view granularity** | Fixed history length; increasing graph width; read a fixed set of required members/event rows or update d members | No reconstruction of the entire result envelope for a selective read. Read required payloads/proofs plus index paths; write changed payloads/index paths without rewriting unchanged members |

"Required" includes a fixture-specific proof/boundary set declared **before** the
measurement. It cannot be expanded afterward to justify eager reads. Some SCC
proofs and real operations require the whole selected component: count that work
explicitly rather than promising constant cost for arbitrary graphs. Large
individual bodies need separate coverage; per-document records alone do not
establish bounded record size.

### P1: tests must use the actual integration configuration

The previous `RuntimeDecodedArtifactsTest` cross-reader test used 32 MiB for both
the session record and publication value. Likewise the maintained SDK fixture
had a 40 MiB **map node** bound but a 32 MiB **record** bound. Neither reproduced
MyOS's 40/32 reader mismatch. Payloads need not approach either limit to reproduce
it: different cache-family keys alone caused the duplicated reconstruction.

Keep direct-codec tests for exact decoder counts and safety. Add
`RootedStorageResultProfileIntegrationTest` using actual SDK-produced embedded
history, `RootedCoordinationStorage.retainPartition/open`, fresh owners and
ordinary session/publication readers. No fake processor or supplied result proof.

The controlled direct-codec budget for F distinct frames is 2F cold decodes under
the former separated families versus F with compatible-family reuse; alternating
warm reads add none when capacity is sufficient. The SDK test must also prove
actual reader wiring and exact cross-reader result identity. Its **total** cache
loads include other artifact families and must not be labelled result decodes.

Pair the real 40/32 configuration with a 40/40 control on identical stored input.
Report costs even if the expected old-key reuse assertion fails. Retained capacity
and actual evictions must be reported: eviction legitimately permits another
decode and cannot be hidden by calling a pressure-heavy run "warm".

P1 is not expected to eliminate session-prefix reads, host projection capture,
database latency, full work-selection scans or first verification of new results.
The observed 124 overlapping profile loads do not guarantee that exactly 124
loads disappear from a pressure-heavy application run.

## Measurement phases and safety controls

Separate fixture construction/warm-up from the selected operation. Measure:

1. Open selected durable state and load required session/history.
2. Select next work and prepare historical dependencies.
3. Execute the required rooted operation/import.
4. Prepare immutable writes and publication descriptors.
5. Reopen and verify the same next action/result.

For each applicable phase record object GET/PUT counts and bytes, cold decodes,
retained entries/estimated weight/evictions, CPU and wall time. Allocation/live
heap requires a dedicated measurement; encoded-byte counters are not heap size.
Do not sum overlapping nested scope times. No universal percentage speedup or
latency threshold is justified until paired repeated measurements exist.

Run cold/cache-disabled, warm, evicted and fresh-owner/restart controls. Compare
exact histories and same-epoch positions, BlueIds, events/order, complete results,
gas/charge trace and tight-budget failures. Keep selected missing/corrupt bytes,
wrong depth, undersized bounds, foreign ownership and failed publication controls.

The representative workload family must include acyclic sharing, a small cycle,
detach/reattach with historical catch-up, and long history independently of graph
width. Reuse existing verified scenario fixtures. P1's minimal same-epoch embedded
fixture is a focused reproduction, not a replacement for this broader family.

## Advancement rule

Do not start another long MyOS experiment merely because code compiles or unit
tests pass. First publish a short library result: **before cost → after cost,
unchanged outputs, remaining cost**. If the expected reduction is absent, fix the
library hypothesis/test here; do not ask the application run to discover why.

Then run the corresponding short MyOS durable confirmation with the exact pinned
libraries; use the long scenario once the relevant reductions are established.
MyOS still owns SQL, transaction, scheduling and projection costs. If these dominate
after the library change, give them a separate host optimization card rather than
claiming the library test predicts whole-application latency.

## First completed library experiment: P1

On 2026-09-16, the same `RootedStorageResultProfileIntegrationTest` ran against
the old and new Coordination cache-family key on identical Language
`7cf583e7c5a02446d83575677e46a97785748f74`, BEX `ab72af14` and Catalog `0b68744`
local immutable artifacts. Source snapshots differ only in
`StoredClosureResultCodec.java` (the family-key line). This isolates the change;
it is not a comparison of entire historical releases.

The real source/parent SDK fixture has five same-epoch representation positions.
It saves SDK/journal bytes, closes the producer, opens three independent readers
with shared bounded L1, processes one new tick against a resident oracle, stages
the result and reopens it. The identical 40/40 control isolates the effect of the
40/32 configuration. No database or fake processor substitutes for these codecs
or SDK operations; the object store retains detached byte arrays.

| Measured item, 40/32 variant | Before | After |
| --- | ---: | ---: |
| Exact frames observed through both readers | 5 | 5 |
| Frames acquiring duplicate result instances | 5 | **0** |
| Distinct result instances sampled across all seven observed frames | 12 | **7** |
| All-family cache loads over the whole fixture | 46 | 38 |
| Cache evictions | 0 | 0 |
| Object GET calls, including test verification | 8,484 | 8,484 |
| Object GET bytes, including test verification | 51,160,789 | 51,160,789 |
| PUT calls / candidate bytes | 131 / 1,844,680 | 131 / 1,844,680 |

Both before and after satisfy the exact result/history/event/gas comparisons and
the next-operation/reopen oracle. The old run fails only the final duplicate-frame
budget; the raw failed receipt is preserved. The 40/40 control remains unchanged
(zero duplicates, seven sampled instances, 38 all-family loads).

This proves P1 removed the observed cross-profile duplication. It does **not**
prove fewer physical reads or granular session/history recovery. "Sampled
instances" is not a census of every nested result or cache entry. Cache loads
include other families, not just full result decodes.

Timing is auxiliary in this first fixture: `process-next-tick` also performs the
full correctness oracle; `reopen-new-tick` times verification after opening;
per-owner verification warms additional artifacts. Neither those phase names nor
the whole JUnit duration should be reported as user-operation latency. A future
timing benchmark must separate verification from the measured path and compare
repeated, equivalently warmed runs. The hard P1 oracle is the eliminated duplicate
work, not a claimed wall-time speedup.

The changed Coordination tuple passed **117/117 tests across 15 complete owners**
plus dependency/API/SDK/Javadoc/production-shape guards. Language passed
**121/121 across 14 owners** plus its API/Javadoc/package guards. These are focused
library qualifications, not current MyOS/full-suite acceptance. The unchanged
BEX export's report-inventory discrepancy is separately retained in the evidence;
no failed report was relabelled as passing.

Evidence under `processing-measurement13/profile30/` in the POC evidence archive:
`coordination-mechanism-before01/`, `coordination-controls-after01/`,
`language-controls02/` and `library-mechanism-comparison.json`.
The next storage package must pass P2/P3's independent read/write budgets before
another long MyOS run is used as confirmation. P2's library gate now passes;
P3/P4 remain unimplemented, and MyOS confirmation has its own gate.
P2 has a real SDK baseline probe and an exact session/lineage implementation
card: [indexed session history](poc-indexed-session-history-plan.md). The baseline
5/20/50-epoch probe exposed growing cold-read/staging cost; the qualified indexed
implementation now passes P2's selective payload budgets and exact equality
oracles. This does not qualify P3/P4 or all metadata/audit costs. Its subsequent
45-minute original long MyOS graph remained incomplete; the implementation card
records phase-specific profiles and the next audit/selection measurement gap.
