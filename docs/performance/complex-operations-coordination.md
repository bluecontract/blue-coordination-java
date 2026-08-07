# Complex Coordination verification

This document describes the current, non-time-based performance and locality
proofs. It intentionally contains no published Blue dependency coordinates:
the build requires the sibling composites at `../blue-language-java`,
`../blue-bex-java`, and `../blue-repository-java`.

## What is measured

The performance contract is semantic locality, deterministic work, and bounded
resource use—not elapsed time on one machine.

- `CoordinationDocumentSplitterLocalityTest` proves provider demand is
  proportional to the selected scope spine and executable bodies.
- `CoordinationDocumentSplitterDeepLocalityTest` proves exact reconstruction,
  shared-body deduplication, and zero demand for cold sibling roots.
- `CoordinationDocumentSplitterProcessingMatrixTest` compares inline,
  reference, partial, and splitter-produced representations.
- `CoordinationComplexEmbeddedDeterminismFlagshipTest` runs the
  Root/Emb1/Emb2/Emb3 walkthrough across representation, cache, and provider
  variants while large decoy branches dominate stored bytes. It compares the
  complete final Root value independently from its BlueId, proves two equal
  emitted Event values remain two ordered occurrences, and verifies the
  original causal Event at all four scopes.
- `CoordinationInfiniteLoopSafetyTest` proves live gas termination, admitted
  trace prefixes, atomic rollback, and deterministic retry without wall-clock
  timeouts.
- `CoordinationHostQuotaRuntimeTest` and
  `CoordinationHostQuotaFixtureTest` exercise named splitter and Mandate
  diagnostics through production entry points. These counters enforce
  preparation/provider limits and never contribute to portable PROCESS gas.

The fixed-scope flagship writes executable-derived evidence to
`build/reports/coordination-flagship/trace.md` only after both runtime variants
and all 32 matrix runs pass in the same invocation. The nested collection
walkthrough uses a separate structured JSON contract and cannot treat that
fixed-scope Markdown as its input. Loop prefixes are written to
`build/reports/coordination-loops/trace-prefixes.json`.

## Required invariants

Equivalent inputs must produce the same:

- status, resulting Root identity and value;
- Root-only public event identities and order;
- the complete original causal Event and timestamp captured at
  Root/Emb1/Emb2/Emb3;
- both occurrences of an identical emitted Event in enqueue, dequeue, handler,
  and delivery order;
- checkpoint subject;
- semantic and provider demand sets;
- selected executable-body identities and canonical bytes per identity;
- named gas trace and total.

Strict providers must report zero forbidden demands. Cache state and physical
representation may change provider calls, but cannot change semantic results or
portable gas. The flagship report records sorted selected-body BlueIds,
`BlueId|canonical-bytes` entries, aggregate selected bytes, and the per-run
selected body/byte totals. This prevents an equal aggregate size from masking a
different selected executable closure.

## Verification

```bash
./gradlew \
  coordinationFlagshipTest \
  coordinationLoopSafetyTest \
  selectiveCoordinationProcessingTest \
  verifyReproducibleArchives \
  --offline --no-daemon
```

The hard release graph is `finalCoordinationVerification`. It produces the
identity-bound final report only when every required suite, binary/API check,
Java 8 check, and reproducibility check passes. Closed conformance additionally
requires the same-run executable receipt at
`build/reports/coordination-conformance/results.json`; a package inventory or
structural fixture parse cannot stand in for execution. The receipt separates
14 portable process-gas fixtures from 7 nonportable host-quota fixtures.
Current local-composite integration blockers, when present, are recorded
precisely in `docs/final-coordination-implementation-blockers.md`; they are
never converted into a partial-success report.

The runtime-gas scaling proof executes a worst-case 129-member Timeline
aggregate and retains all 516 ordered entries: 129 member visits, 129 header
reads, 129 Timeline comparisons, and 129 Actor comparisons. Language's
portable value of 256 bounds distinct counter kinds in one child catalog; it
does not cap repeated staged trace entries. Coordination therefore preserves
the exact charge-before-work order and failure prefix without batching,
reordering, or hiding work.

## Per-operation elapsed-time diagnostics

Elapsed time is diagnostic evidence, not a portable pass/fail budget. Capture
one exact MyOS run with the optional monotonic recorder:

```bash
./gradlew coordinationMyosDemoTest \
  --tests blue.coordination.examples.WadowiceHotelDinnerOrderExampleTest \
  -Dmyos.demo.operationTiming="$PWD/build/reports/myos-demo-examples/operation-timing.json" \
  -PtestJfr=false --offline --no-daemon
```

The JVM writes the report once at shutdown. Each operation records append,
route lookup, affected Root count, complete PROCESS time, and one delivery per
Root. Delivery detail includes indexed planning, selected-bundle loading,
Contracts PROCESS, retained-reference materialization, subscription
projection, fragment-transition planning, commit, backend batch/body/byte
counts, and unattributed time. Nanosecond values come from `System.nanoTime`
around the live call sites; convert them to seconds for presentation, but keep
the original integers when comparing phases within that exact run.
