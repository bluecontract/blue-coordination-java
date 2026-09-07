# Final-review remediation — R1–R5

> Authorized 2026-09-06. R1–R5 implemented and verified locally at this checkpoint; subsequent delivery is recorded in [the source map](source-commits.md).
> R6 (recursive graph traversal / stack overflow) is explicitly outside this repair round.

This follows the [final implementation review](../review/final-implementation-review-2026-09-06.md).
The historical report and its diagnostic output remain unchanged evidence of the reviewed commits.
This work preserves the selected processing model and existing working branches; it does not
start the general Phase3 adapter or expand F05/F07. PostgreSQL remains a MyOS host concern, never
a library dependency.

## Scope and ownership

| Finding | Repair scope | Verification obligation |
|---|---|---|
| R1 | Authenticate the original producer policy before fresh execution as well as retained-result reuse. | Equal logical source/observer outcomes with fresh, cached and cold evidence; preserve independent budgets and valid coupled execution. |
| R2 | Authenticate each fresh independent producer's original attachment choices during reconstruction. | Standalone source versus parent reconstruction, original frontier/empty choices, missing authority and cold restoration. |
| R3 | Type pure authored-schema conversion failures at the owning boundary. | Real Compute settlement/rollback plus schema-kind/range controls; operational failures remain nonconsuming. |
| R4 | Serialize lease recovery with retained-attempt authority under the work lock. | Controlled retain/recovery handoff, contention, restart and discoverable retry. |
| R5 | Isolate blocked prefix maintenance roots and preserve their exact retained authority. | Healthy-root progress, cold restart, capacity repair and resumption across maintenance families. |

R1/R2 are one coherent source-admission repair with separate regression stories. A source and an
independent observer may have different authorized policies; genuinely joined processing still
obeys its common-context rule. An offered hash is not independent admission authority. A source
must not acquire the parent's choices just because its result is not cached.

During selected external-input evaluation, when original input authority is supplied together with
a retained result, it must agree with that result too. Same-origin success/failure capabilities retain complete per-owner original selection
identities, including explicit empty and unused choices. The codecs preserve the binding. Current
scoped producer APIs without that binding remain distinct from a verified empty selection; a named
need is returned if the supplied original root requires that missing proof.

R3 must not broaden generic Java exception classification. R4/R5 must not skip semantic input,
change gas, discard retained output, or reevaluate an already committed operation.

R3 does not change decimal arithmetic or introduce a new numeric representation. For example,
`minimum: 0.5` remains valid, an integral decimal count remains valid, and a fractional `minItems`
was already invalid. The repair changes that last case from a generic conversion exception into
an owned deterministic schema failure. Representation/resource failures remain operational.
Unsupported per-keyword reference acquisition is not implemented by this repair: unresolved
keyword references/type-supplied values must retain their prior noncommitting behavior, while
existing whole-schema reference acquisition remains supported and tested. At the generic runtime
port used by the control, absent whole-schema content is an unclassified noncommitting exception,
not a typed `NeedEvidence`; supplying the body permits the same input to succeed. Per-keyword
numeric references are not acquired by the current resolver even when that body is available.
These controls protect the existing behavior; this round does not add that acquisition feature.

## Execution and verification

The source-admission, schema and host repairs are developed in parallel. Builds that share the
library worktrees are serialized. Focused reproductions run first, followed by one stable affected
regression and one actual-library/PostgreSQL handshake after the library artifacts stop changing.
The independent PostgreSQL foundation suite can run alongside library development.

The verification record distinguishes reproduced failures, repaired focused controls, final
regression counts and the exact artifacts used by the host handshake. Earlier green suites are
not substituted for tests of the new combinations.

### Verification checkpoints

- R3's first focused gate passed 110 tests (88 Language, 14 BEX, 8 actual Compute), zero
  failures/errors/skips, in 14 seconds. This followed an eight-test Compute reproduction in which
  the seven new malformed-schema cases failed and the existing control passed. Subsequent
  representation/reference refinements subsequently passed 133 focused tests (107 Language,
  16 BEX, 10 actual Compute); they are also included in the stable gate below.
- The combined R1/R2 and final R3 focused gate passed 202 tests. It verifies original-frontier
  reconstruction, independent source budgets, missing authority, exact operation/gas parity,
  cached success/failure bindings including unused choices, cold restoration, dynamic three-party
  joins and noncommitting missing/cross-policy returning participants. The final co-owned-root
  check was added afterward and passed in the stable external suite below.
- R4/R5 passed the complete independent PostgreSQL foundation suite: 88 tests (45 host, 32 prefix,
  11 maintenance), zero failures/errors/skips, in 55 seconds. The race and all four blocked-root
  families were first reproduced red. Tests include fresh child JVMs, retained backoff, exact
  resumption, stale-worker fencing, family isolation and explicit transaction-isolation control.
  The local evidence archive is MyOS Simple
  `build/readiness-evidence/r4-r5-host-20260906-A4dS26/`. This suite does not build the libraries;
  its result is not the final actual-library/PostgreSQL handshake.

### Stable library gate

One combined run passed in 1 minute 41 seconds, with zero failures/errors/skips:

| Suite | Passed | Scope |
|---|---:|---|
| Contracts | 566 | Complete Contracts test task. |
| Language | 251 | Schema tests plus style, static safety, kernel and module-ownership architecture controls. |
| BEX | 161 | Authored/unclassified boundaries, value identity, engine conformance and structured-reference evidence. |
| Coordination | 81 | Complete external API test package, including the final co-owned original-root check. |
| Total | 1059 | This is not a full run of every repository's test catalog. |

Public API inventory/union generation and Contracts API-baseline comparison passed. Regenerating
the module-ownership ledger produced no diff. The documented Java API example compiled against
the final production library classpath, without host, PostgreSQL or test-runner dependencies.
The local archive `build/readiness-evidence/r1-r5-final-20260906-ZiROHS/` preserves exact commands,
JUnit reports, source patches/new files, production jars and SHA-256 hashes in `manifest.json`.
The original red report and witnesses remain immutable.

### Actual-library/PostgreSQL handshake

The final `durable-library-smoke` run passed 7 tests with zero failures/errors/skips in 21 seconds.
All included production compilation and jar tasks were up-to-date, using the stable library
candidate above. The hashes of all nine included library jars match the stable gate archive.
The MyOS archive `durable-library-smoke/build/readiness-evidence/final-r1-r5-seven-20260906-eOCSTM/`
retains the actual worker classpath, reports, jars, compiled/source snapshots and tested V1–V3
schema hashes. This exercises the actual library/host seam, not a second SQL-aware interpreter.
R4/R5's 88-test foundation gate is separate from these 7 tests and the 1059 library checks above;
focused checkpoints are not added again. The total final verification is 1154 passing checks.
The thin handshake is not the general Phase3 adapter or proof of the complete graph scenario catalog.

Code and English documentation were local changes on the existing working branches at this checkpoint.
No new commit, push, release, branch switch or Phase3 implementation was performed during its verification;
the later N1–N5 round and delivery are recorded separately. The
pre-existing untracked MyOS example is unchanged and excluded.

## Deferred by user decision

No stack/depth repair, iterative SCC rewrite, new depth capacity rule or R6 acceptance gate is
included. The existing diagnostic remains recorded, but does not block this repair round. Other
deliberately planned Phase3 integration and measurement work remains in the
[Phase3 plan](../25-phase-3-integration-plan.md).
