# Executable review evidence

These are diagnostic witnesses for the [final implementation review](../final-implementation-review-2026-09-06.md),
not production sources, public API examples, or passing acceptance tests. They intentionally reuse
package-local test fixtures from the reviewed commits. Their captured failures establish findings;
future regression tests must assert the corrected behavior instead.

| Source | Captured output | Finding / observed result |
|---|---|---|
| [PolicyWitness.java](PolicyWitness.java) | [stdout](PolicyWitness.stdout.txt) | R1: fresh S succeeds at 9,859 gas; original S fails at its 5,000 limit; cached failure changes A's result. |
| [OriginalDescendantWitness.java](OriginalDescendantWitness.java) | [stdout](OriginalDescendantWitness.stdout.txt) | R2: original X observes 0 at F5; reconstructed fresh X observes 5; authentic cached X is preserved. |
| [ComputeSchemaWitness.java](ComputeSchemaWitness.java) | [stdout](ComputeSchemaWitness.stdout.txt) | R3: five schema keywords escape as operational failures on identical retries; invalid-BlueId control passes. |
| [HostReviewProbe.java](HostReviewProbe.java) | [stdout](HostReviewProbe.stdout.txt) | R4/R5: stranded retained generation and healthy-root starvation on real PostgreSQL. |
| [SccDepthWitness.java](SccDepthWitness.java) | [stdout](SccDepthWitness.stdout.txt) | R6: deep acyclic chains overflow the default JVM stack. |

No Gradle build ran during this diagnostic review. Classes were compiled into temporary directories
against existing final artifacts and compiled fixtures. The three semantic library witnesses and
the depth probe were independently repeated by the coordinating reviewer. The host reproduction
was run by the host review track and its source, SQL schedule and output were inspected by the
coordinating reviewer. Captured console output can contain benign SLF4J binding warnings.

## Local reproduction without rebuilding

Run the library commands from the Coordination implementation worktree, beside the Language and
BEX implementation worktrees. The classpath archive below has an earlier run-name, but contains
paths to the final rebuilt JARs; the JAR digests were checked against the final gate. It is ignored
local build evidence, not a file delivered in Git. On a new machine first build the exact reviewed
source set and its test fixtures, then supply an equivalent classpath; do not assume the historical
absolute paths in the archive are portable.

```sh
review_evidence="$PWD/docs/myos-blue-coordination/review/final-implementation-review-2026-09-06"
review_classes="$(mktemp -d -t blue-final-review)"
review_cp="$(sed -n '2p' build/readiness-evidence/coordination-final-affected-20260906-pECpT9/worker-classpath.txt)"
review_depth_cp="../blue-language-java/blue-contracts-core/build/classes/java/test:$review_cp"

javac -proc:none -cp "$review_cp" -d "$review_classes" \
  "$review_evidence/PolicyWitness.java" \
  "$review_evidence/OriginalDescendantWitness.java" \
  "$review_evidence/ComputeSchemaWitness.java"
javac -proc:none -cp "$review_depth_cp" -d "$review_classes" "$review_evidence/SccDepthWitness.java"

java -cp "$review_classes:$review_cp" blue.coordination.external.PolicyWitness
java -cp "$review_classes:$review_cp" blue.coordination.external.OriginalDescendantWitness
java -cp "$review_classes:$review_cp" blue.coordination.external.ComputeSchemaWitness
java -cp "$review_classes:$review_depth_cp" blue.language.processor.closure.SccDepthWitness
```

The policy and original-admission probes include assertions for their observed divergence. The
schema and depth probes print their observations; a zero process exit is **not** an acceptance
verdict. In particular, `StackOverflowError` thresholds vary by JVM stack size and execution mode.
The depth probe needs the additional Contracts test-class directory, not only the external
Coordination worker classpath.

For the host probe, run from MyOS Simple with its documented disposable local PostgreSQL available
on loopback port 15432. The Java source uses the already documented local test database defaults;
never point it at a shared or production database. Set `review_evidence` to this directory's
absolute path and use:

```sh
review_host_classes="$(mktemp -d -t blue-host-review)"
review_host_cp="$(sed -n '2p' durable-library-smoke/build/readiness-evidence/remediation-seven-20260906-pTE4eo/worker-classpath.txt)"
javac -proc:none -cp "$review_host_cp" -d "$review_host_classes" "$review_evidence/HostReviewProbe.java"
java -cp "$review_host_classes:$review_host_cp" blue.myos.mini.durable.HostReviewProbe
```

The host probe creates a fresh `dh_review_<uuid>` schema, applies actual V1/V2 durable migrations,
and drops only that exact, validated schema in `finally`. The recorded run used PostgreSQL 17.10;
read-only cleanup verification found no remaining review schema. The deliberate manual
reconciliation/PAUSED cleanup between its two scenarios only isolates their fixtures; it is not
part of the automatic recovery claimed to fail.

## Host evidence qualification

R4 invokes actual `PostgresCommitStore.retain` and `PostgresWorkStore.recoverLeases`. A JDBC proxy
pauses retention after the work lock, before inserting the plan. A semantically true advisory-lock
InitPlan in the recovery statement fixes its snapshot before retention commits, while allowing
row locking after that commit. All original predicates and `FOR UPDATE SKIP LOCKED` remain.
This is controlled scheduling, not uninstrumented load testing. A simple blocked-row reproduction
would be invalid because SKIP LOCKED omits the locked row.

R5 uses unmodified prepare/stage/seal/activate/read/materialize APIs. Fixture verifiers check
storage consistency and deliberately do not claim to verify Coordination business semantics.
The proof-capacity failure repeats on three cold readers before service-turn updates. READY was
executed; analogous waits/fanout/temporal paths were inspected only.

## Artifact provenance

The reviewed implementation commits are in the main report. Final JAR SHA-256 values used by the
library witnesses and the prior actual-library/PostgreSQL handshake are:

| Artifact | SHA-256 |
|---|---|
| Coordination | `47699c5ebc5e14b9c2a35db930d4f5c57de23783f3b0dbb42ffe6820d43e7f0a` |
| Contracts | `9cbb07fde98bc9573144806cf3cac08bc003380a0360058e3ae34407de29d95e` |
| Language core | `79eb389ddb43d7771cffad7badc53405494982b149e873856446be62d93ac9e3` |
| Language model | `f6435d291082b8feab945f703757f698d241ea1e711b80b9be36d0fb302342aa` |
| BEX core | `723a961f2b1131a68678dfdcc012649f4c8bae577f136c99ea4f08647511c3d1` |
| BEX Contracts | `2cedbb63899947138befaf2dc07b52066be105f107353ce8421d06cf879c879b` |

The host compiled-input archive from its 75-test gate has SHA-256
`dbedc50e73ba60bd866621183074dc9d7656ed02af13711a2dfcbffa93f9fa5f`.
The seven-test handshake's compiled-input archive has SHA-256
`faf618bb087bff9a66e12f26a9ef48d4dbfe4b10efba379c8a012611c749d8ea`.
Raw JARs, build archives, test reports and database data remain local; only these small source
witnesses and their captured text output are part of the documentation commit.
