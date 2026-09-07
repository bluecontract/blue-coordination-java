# Post-remediation host diagnostics

Read-only review, 2026-09-06 (Europe/Warsaw). No production/test-source changes,
Gradle invocation, commits, or branch changes. These standalone Java artifacts
execute the existing compiled MyOS Simple storage methods against isolated
PostgreSQL schemas, which their fixtures drop on completion. They do not claim
to be new Coordination semantic tests or a full test-suite rerun.

## Confirmed: discovery head-of-line blocking remains after R5

`DiscoveryHoldProbe.java` uses the existing `PostgresMaintenanceTest` fixture's
private builder through reflection to prepare two fully committed roots, P and Q.
All evaluation, retention, activation, materialization and discovery behavior is
the real implementation. Its injected recovery adapter throws if asked to
evaluate or recommit anything.

Both fanout rows are first materialized at the default 4 MiB proof capacity.
The process-equivalent fresh database session then uses a smaller valid capacity,
1,300 bytes. Q's proof is 1,268 bytes; P's is 2,257 bytes. Thus the counterexample
explicitly assumes a restart/reconfiguration with a reduced physical proof
budget, not changing a root or its business semantics.

The first held fanout aborts `PostgresDiscoveryStore.maintain` before the healthy
fanout or any backfills. Its `next_attempt` is never moved because the read fails
before `fanoutPage` enters its progress transaction. Repeated `DurableHost.recover`
still services other prefix families (R5 works there), but repeatedly reloads P
and never discovers Q's recipient. Directly servicing Q at the same capacity
succeeds immediately. This is a residual item-local fairness gap adjacent to R5,
not the deferred F07 recipient-range algorithm.

Exact diagnostic stdout, excluding Hikari lifecycle logs:

```text
capacity=1300 blockedProofBytes=2257 healthyProofBytes=1268 initialFanouts=2
recover1=Prefix read exceeds configured proof capacity healthyDeliveries=0 physicalReads=40
recover2=Prefix read exceeds configured proof capacity healthyDeliveries=0 physicalReads=4
recover3=Prefix read exceeds configured proof capacity healthyDeliveries=0 physicalReads=4
rootsUnchanged=true fanoutCursorsUnchanged=true commits=2 settledAccountUnits=0
heldFanoutStillDue=true
positiveControlDirectHealthyFanout=1 healthyDeliveries=1
```

Primary locations (MyOS Simple): `PostgresDiscoveryStore.java:302–314`,
`PostgresDiscoveryStore.java:138–143`, `PostgresPrefixStore.java:313–316`;
`DurableHost.java:77–95` isolates families, not discovery items.
Suggested regression: two fanouts plus an independent backfill, one typed Hold;
verify item-specific durable backoff, unchanged cursor and source authority,
healthy progress, cold retry and stale-attempt protection. Do not suppress unknown
exceptions or add semantic retries to repair a committed source.

## Confirmed: public release can orphan a retained attempt

`RetainedReleaseProbe.java` supplies a controlled storage-only result verifier,
then executes retain → release → claim → same-key retain → cold recovery through
the actual stores. Releasing leased work changes its generation without marking
its retained plan invalid. Automatic recovery finds plans only through matching
current work generations, so it never reconciles that old attempt. The retry's
material and logical CommitKey remain the same; only its work fence changes.

```text
generations original=1 retry=3
sameKeyRetry=Earlier attempt requires reconciliation
coldRecover=1 activePlanStillPresent=true workState=READY
postRecoverySameKeyRetry=Earlier attempt requires reconciliation
manualReconcile=INVALIDATED
positiveControlAfterManualReconcile=COMMITTED
```

Primary locations: `PostgresWorkStore.java:212–218` and `249–254`,
`DurableHost.java:69–75`, `PostgresCommitStore.java:70–74`.
Suggested fix/test: under the work lock, refuse ordinary release while an active
retained attempt owns that generation, or route it through full-set reconciliation.
Preserve normal pre-retain release and joined-work safety. This is P2 public-API
liveness: `DurableHost.step` does not currently call `release`, and the witness
does not demonstrate duplicate commit or corruption of a committed result.

## Policy/coverage note, not a confirmed security finding

`ReservationAuthorizationProbe.java` fixes an actual PostgreSQL source-row lock
interleaving. A reservation call authenticates, blocks on the source control,
then publishes after authorization revocation has committed. The next call,
started after revocation, correctly rejects.

```text
revocationCommittedBeforeSourceUnlock=true
postRevocationReservationCount=1
positiveControlCallStartedAfterRevocation=SecurityException
```

`PostgresDiscoveryStore.reserve` (`84–99`) lacks the in-transaction authorization
fence used by business commit and registration. This is only a defect if the
intended policy forbids completion of an already-authorized in-flight physical
reservation after revocation; call-entry authorization may be the intended rule.
No broader security conclusion is drawn without that policy.

## Exact execution

Working directory: `/Users/kamil/Documents/Projects/Blue/myos-simple`.
The diagnostic used existing host-test compiled classes and the actual retained
Gradle worker argument file; it did not run Gradle. Each named class below was
compiled and run separately with this command, replacing `DiscoveryHoldProbe`
with `RetainedReleaseProbe` or `ReservationAuthorizationProbe` for those runs:

```sh
review_classes=$(mktemp -d /tmp/myos-post-review-XXXXXX)
review_classpath=$(sed -n '2p' /Users/kamil/.gradle/.tmp/gradle-worker-classpath16940208097965408460txt)
javac --release 21 -cp "$review_classpath" -d "$review_classes" /Users/kamil/Documents/Projects/Blue/worktrees/coordination-external-state/blue-coordination-java/docs/myos-blue-coordination/review/post-remediation-review-2026-09-06/host/DiscoveryHoldProbe.java
java -cp "$review_classes:$review_classpath" blue.myos.mini.durable.DiscoveryHoldProbe
```

All three exited 0. The reflection-based fixtures emitted five javac warnings
about absent `org.apiguardian.api.API$Status` annotation classes; runtime checks
completed successfully. To rerun elsewhere, set `review_classpath` to the current
MyOS Simple **host test runtime classpath**, including compiled main/test classes
and schema resources. Discovery and authorization probes use existing test-only
fixture setup/cleanup and therefore require `PostgresMaintenanceTest` and
`PostgresTestPool`. The release probe uses `PostgresTestPool` only.

The executed key host class SHA-256 values exactly match the preserved R4/R5
88-test archive under MyOS Simple
`build/readiness-evidence/r4-r5-host-20260906-A4dS26/host-classes/`:

```text
84a3c68162e13a124e026bcfd3e2691fdfc161d38db1e885564b4588acad929a PostgresWorkStore.class
a559151404f26d32c46e3759d38fd25af5270b810d1dcf53c881a0e0dc86e608 PostgresDiscoveryStore.class
f994c2bda95c8204f16ae8787574838410e78cc4eb967ae6c481b252b7a3965a DurableHost.class
af7e592f31eb05aceabeffe65a05831c2cd30ef5c3da23163d3cc82649c05ba5 PostgresPrefixStore.class
```

## Audited scope and remaining gaps

Read the complete durable storage/host classes, V1/V2/V3 schema, actual
`CanonicalInitializationAdapter` and `LibraryContentStore`, host handoff docs,
and relevant fault/restart tests. Covered work/account fences, retained plans and
aliases, atomic publication, source success/failure/metadata identities,
registration/backfill/retirement/reservations, prefix staging/sealing/activation,
all maintenance families, exact Timeline/provider proofs and selector ordering,
outbox membership/causality/receiver deduplication, and bootstrap/API composition.

No new semantic-integrity defect was established in the narrow actual-library
initialization adapter. Its general input/import/topology mapping and operational
pumps remain Phase3 work; seven thin library/PG bridges are not seven deployed
application flows. F05/F07 and excluded R6 stack-depth work are not new findings.

No fresh scale benchmark, full suite, or adversarial JDBC stress sweep was run.
Known F07 historical-recipient scans and reporting-only aggregate metrics remain
separate from the demonstrated liveness failures. In particular, per-query LIMIT
alone is not fresh proof of total examined-row bounds. The PostgreSQL witnesses
are focused storage/API evidence, not proof of all application combinations.
