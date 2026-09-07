# Post-remediation fixes — N1–N5

> Authorized 2026-09-07. N1–N5 implemented and verified on the existing working branches.
> R6 stack/depth work remains excluded. No Phase3 adapter, release or API freeze is added.

This addresses the five findings in the [post-remediation review](../review/post-remediation-review-2026-09-07.md).
That report and its original diagnostic output remain historical evidence. These fixes build on
the already local [R1–R5 candidate](final-review-remediation.md), not a replacement processing model.
PostgreSQL implementation and schema changes belong only to MyOS Simple.

## Behavioral changes

| Finding | Required correction | Contrasting regression |
|---|---|---|
| N1 | Verify selected initialization/frontier evidence against independent producer authority; retain the required read cut and bind actually interpreted preparation authority into settlement identity. | Same/different producer policy; hot/cold evidence; missing/wrong basis; unchanged producer execution and observer gas; managed replay with a matching-actor read-only child; identity separation, retirement/failure and returning-join controls. |
| N2 | Use one metadata-publication rule for early exits and target progress accompanying producer operations. | Attributed-only fences; unrelated source heads; missing target entry; explicit empty entry; legacy-only compatibility; failed-source metadata. |
| N3 | Apply the existing transient cyclic-reference restriction to `schema.blueId` as well as ordinary references. | Field/type/schema × identity/event/patch; exact-carried and ordinary-reference controls; actual Compute rollback, repeat gas and successful later input. |
| N4 | Persist item-local discovery Holds and retry scheduling, and continue servicing other fanouts/backfills. | Heterogeneous proof sizes; cold restart; no proof reads during backoff; capacity repair; late failed attempt cannot overwrite successful progress; unknown faults still escape. |
| N5 | Refuse ordinary work release while a retained plan owns that work generation. | Release before/after retain; both lock orders; joined work; cold reconciliation; same-key retry without duplicate commit. |

### Independent producer authority

The host obtains expected source execution bases from independently authenticated admission/prefix
context. The offered receipt cannot authorize itself. This applies to used initialization evidence
and its borrowed initialization dependencies, as well as retained external outcomes and selected
frontiers. Missing required authority requests evidence; contradictory authority rejects the cut.
Used includes runtime offers selected by explicit `FULL_HISTORY` choices, even when the selected
creation branch is not executed; it does not include wholly unrelated initialization inventory.
Core verifies selected borrowed dependencies with one memoized traversal. Individual initialization
verification checks its own producer group; the orchestrator/session verifies the borrowed groups.

The consumer still pays its own installation/observation work. Source initialization is neither
reexecuted nor remetered under a consumer policy. A genuine returning join retains its common-policy
requirement. Compatibility overloads for explicitly fixed-policy Contracts callers do not authorize
cross-policy evidence implicitly.

The new cases exposed two consequences that are included in this correction:

- A retained frontier can require a source header without borrowing that source's executable
  program. Managed imports retain that header, but only selected external-program/failure owners
  supply direct deliveries. A matching channel on a read-only header must not reexecute its source.
- Two authorized source initialization policies can produce equal init0 bodies but different
  source operation identities. The creator's original seed alone did not distinguish those
  inputs: the executed counterexample produced one operation key with different gas-trace/program
  identities, including after codec restoration. Final settlement now needs separate interpreted
  initialization/frontier authority, including work observed before failure or retirement. It is
  not an external consumed operation, a source-publication obligation or an event-seed change.

The low-level explicit-map admission overload also requires imported initialization operations
to be bound in the invocation's existing semantic predecessor map. Core already supplies these
bindings. Rejecting an unbound call avoids silently changing its invocation identity inside the
processor; the old explicitly fixed-policy overload remains available.

The settlement evidence has closed `INITIALIZATION` / `FRONTIER` rows, canonically sorted by kind
name and identity. Success/failure codecs retain these facts, and receipt restoration recomputes
the group identity and checks agreement with the retained capability. Groups with no interpreted
preparation preserve the previous empty-evidence constructor. This is one current development
format, not support for parallel specification profiles; old diagnostic archives remain evidence
of their original candidate rather than a promise to replay them on the changed stack.

### Metadata publication authority

An attributed target entry wins over the legacy global fence list, including an explicitly empty
target entry. If attribution is in use but the target is absent, return a `group-read-fences` need.
Only a wholly empty owner map retains the legacy metadata fallback; fence-free pure evaluation
remains supported. Independent source-head fences are not added to target metadata publication.
This does not introduce a processor invocation, semantic gas, business epoch or output event.

### Operational host progress

Discovery retry state is physical scheduling data. It neither skips semantic input nor changes
source receipts, gas or occurrence eligibility. MyOS's V4 schema adds item-attempt generations and
Hold diagnostics to the existing indexed discovery queues; it is not a Coordination migration API.
Public page calls respect persisted backoff before reading proofs. A successful page fences off a
late failure from an older attempt, and healthy items continue receiving bounded service.

Retained work must finish full-plan reconciliation before ordinary release. Checking under the
same work lock as retention prevents a completed retain from becoming invisible after a generation
change. Rejecting the release leaves every member of a joined plan intact.

## Verification

Focused tests run before one stable affected-library gate. Host-only PostgreSQL tests can run
independently; composite library builds are serialized. After the libraries stop changing, the
actual-library/PostgreSQL smoke uses those exact artifacts and V1–V4 host schema.

### Stable affected-library gate

The final combined gate passed in 2 minutes 11 seconds, zero failures/errors/skips:

| Suite | Passed | Scope |
|---|---:|---|
| Contracts | 571 | Complete Contracts test task, including initialization, identity and returning-join controls. |
| Language | 251 | Schema plus style, static safety, kernel and module-ownership architecture controls. |
| BEX | 944 | Complete BEX conformance test task, including cyclic schema and missing-evidence controls. |
| Coordination | 100 | Complete external API package, including actual creation/import and success/failure receipt tests. |
| Total | 1866 | Final affected-library checks; focused runs are not added again. |

```sh
./gradlew :blue-language-java:blue-contracts-core:test \
  :blue-language-java:test --tests '*Schema*' --tests '*SourceStyleConventionsTest' \
  --tests '*ProcessorStaticSafetyTest' --tests '*ContractsKernelArchitectureTest' \
  --tests '*PhaseFourModuleOwnershipArchitectureTest' \
  :blue-bex-java:blue-bex-conformance:test test --tests 'blue.coordination.external.*' \
  --include-build ../blue-language-java --include-build ../blue-bex-java \
  --max-workers=2 --console=plain --continue
```

Public API inventory/union generation and Contracts API-baseline comparison also passed (no removed
baseline API members). Regenerating the ownership records changed only the API relocation ledger:
two nested `SameOriginGroupEvidence.SourceEvidence` / `Kind` types, with no module relocation.
The documented Java boundary compiled against production-only libraries, without PostgreSQL,
host or test-runner dependencies. Exact commands, JUnit, source patches/new files, nine production
jars and hashes are in Coordination's local `build/readiness-evidence/n1-n5-stable-husqw7/` archive.

Focused runs preceded this gate. Initial fixture corrections concerned a cyclic schema proof,
canonical component ordering, comparing gas across differently authored documents and supplying
newly required authority to an existing contradiction test. The managed frontier cut and producer
identity collision found while extending those tests required production fixes, not weaker assertions.
The executed identity counterexample is retained in `build/readiness-evidence/n1-identity-alias-20260907-qjV7aw/`;
the later 19-Contracts/20-Coordination focused pass and strengthened frontier/init cross-policy
controls are in `build/readiness-evidence/n1-interpreted-evidence-focus-20260907-6SzXCI/`.

### Host gate and integration handshake

MyOS Simple's complete `./gradlew durableHostTest --max-workers=2 --console=plain` passed 99 tests
(48 host, 32 prefix, 19 maintenance), zero failures/errors/skips, in 57 seconds. Its frozen evidence
is `build/readiness-evidence/n4-n5-host-20260907-fZKjVL/` in MyOS Simple. PostgreSQL schema and
implementation belong to that host, never the libraries.

The final actual-library handshake passed 7/7, zero failures/errors/skips, in 20 seconds:
`../gradlew test --max-workers=2 --console=plain` from MyOS Simple's `durable-library-smoke`.
All nine production library jar hashes exactly match the stable gate above; production compilation
and jar tasks were up-to-date. Its archive, `durable-library-smoke/build/readiness-evidence/n1-n5-seven-TimpxG/`,
contains the actual worker classpath and hashes, source/compiled inputs, reports, jars, and V1–V4
schema. It covers the existing seven library/host restart, lazy evidence, failure and interrupted
M1/M2 cases; it is not a general graph adapter. The final total is **1972 passing checks**:
1866 library + 99 host + 7 integration. Focused runs are not added again.

Independent bounded review found no remaining actionable issue after the private-initialization
realm guard was restored. The simultaneous private-realm/fresh-same-lineage combination was checked
statically, not through a new dedicated fixture; nested replay and attempt invalidation have existing
automated controls. The earlier 4114-test review regression is baseline evidence, not a substitute
for testing these new fixes. None of these checks constitute full Phase3 graph-adapter or large-scale
performance acceptance. R6 stack/depth repair remains excluded; decimal arithmetic is unchanged.

Delivery commits are recorded in the [source map](source-commits.md). Raw local verification archives
are not committed. The user's unrelated MyOS example remains excluded and unchanged, SHA-256
`73ac193047a9dd0e64be158f05c524849fd66cbc335b127fbb199cda72f82a4c`.
