# Current Coordination release blockers

This is the live fail-closed status for the generic Blue Coordination 1.0
release candidate. Generated evidence under
`build/reports/coordination-release` is authoritative when it is newer than
this document.

The working/development surface is verified independently with:

```bash
./gradlew coordinationWorkingVerification \
  --offline --no-daemon -PtestJfr=false
```

Its exact external-blocker catalog is
`gradle/coordination-external-blockers.json`; the generated working report and
local artifact lock are under `build/reports/coordination-working`. A green
working gate does not relax this document's strict public-release boundary.

## Round-two verification update — 2026-08-06

The round-two work is bound to Language
`c3d58561220e6de6be6e302cb16799c1a1b5159f`, BEX
`3ebd2d93be7f24ce44840f0aba02b1c40c27f5f8`, and Repository
`63be6b7d8d2752b5a8c90f38e672859e9b3949a1`. The focused round-two MyOS
suite is green, including all four Wadowice branches, but the strict gates are
not green and no current-source `workingReady: true` report exists.

`coordinationExamplesVerification` currently passes 43 of 51 tests. The eight
failures are the three Operation Mandate and five PawStart cases. In the frozen
Language input, sparse external-subscription projection retains the typed
Mandate subscription spine while pruning its required instance
`/target/initialDocument` branch; transient resolution then rejects the sparse
typed value. Fixing that projection belongs to the frozen Language repository,
not to a weakened MyOS fixture.

The current external-blocker catalog is also stale relative to these sibling
inputs. `coordinationWorkingVerification` declared 509 probes but executed 507;
143 historical probes now pass, 367 outcomes do not match their catalogued ABI
fingerprints, and zero probes are classified as exact current blockers. The
generated diagnostic is
`build/reports/coordination-working/external-blockers.json`. The catalog must be
reviewed and regenerated from a full current-source run; changed outcomes must
not simply be relabelled as blockers to make the gate green.

The older exact-count snapshot below is retained as historical context only.
Its 954/445/509 partition and rc.18 sibling identities are not current
round-two evidence.

## Previous exact local source boundary

The build uses only the adjacent composite builds:

```text
blue-language-java    3.1.0-rc.18  a3b38ca9a1d0b9ca8527b26d23b05cfdbc6af7d9
  blue-contracts-core JAR sha256  9fdc03c12b7da8262bddec59a7230b548a33683c211b602311a27266bc2ffcd0
blue-bex-java         1.1.0-rc.2   c3e36c65b9928c5ae7ef0d839b56ff35a0b70d97
blue-repository-java  3.0.0-rc.17  63be6b7d8d2752b5a8c90f38e672859e9b3949a1
```

`settings.gradle` fails when any sibling is absent. It substitutes only the
focused local Language and BEX projects and consumes the Repository as an
exact hash-verified JAR from a clean immutable materialization of the local
commit. No remote Blue artifact is a fallback in local mode. Coordination
does not modify those repositories, generated Repository classes, or
`.cz.toml`.

The clean BEX working receipt is bound to the same Language checkout and
records 906/906 passing tests, zero failures, zero skips, and
`workingReady = true`. Its declared future published Language request is not
yet aligned with the selected local release source:

```text
expected  blue.language:blue-language-java:3.1.0-rc.18
requested blue.language:blue-language-java:3.1.0-rc.19
```

Composite selection is intentionally separate from publication compatibility.
The local working gate uses the exact verified source modules;
`verifyPublishedDependencyAlignment` remains a strict-release check until the
upstream published coordinate is aligned.

## Current Contracts API boundary

The required runtime-neutral operations are present at the locked Language
commit. Coordination calls these public services directly:

```text
BlueContracts.runtimeAccess()
BlueContracts.subscriptionSurfaceProjection()
BlueContracts.indexedDeliveryEvaluator()
BlueContracts.currentRootDeliveryPlanDeriver(...)
BlueContracts.effectiveFragmentationCatalog(...)
BlueContracts.processForPlatformCommit(...)
```

Subscription projection, indexed delivery, exact reference materialization,
and platform-commit preparation are therefore not external blockers. A
fail-closed placeholder for one of these operations is a Coordination defect
and is not accepted by either release gate.

## Fresh suite partition and fixed Repository evidence

The fresh full evidence run executes 954 tests with no skips:

```text
Coordination working surface                  445 passed
exact immutable Repository probes             509 failed as catalogued
unclassified failures                           0
Coordination/API-placeholder failure families   0
```

`gradle/coordination-external-blockers.json` is generated from the complete
JUnit XML rather than from historical totals. It accepts only these exact
failure families for the locked local Repository commit:

```text
repository-node-provider-abi
  489 probes
  java.lang.NoClassDefFoundError
  logical message prefix: blue/language/NodeProvider

repository-historical-registry-blueid-mismatch
   20 probes
  java.lang.IllegalArgumentException
  logical message prefix:
    Historical registry source src/main/resources/registry/
```

The first family is immutable Repository bytecode linked to the removed
Language ABI. The second is historical Repository registry content that no
longer calculates to its requested BlueIds under the current Language
environment. The generator rejects skipped, duplicate, malformed,
unclassified, omitted, or extra failures. The current bound-source audit is
written to `build/reports/coordination-release/fixed-repository.json`; it
never installs aliases or regenerated definitions.

Supplying a remote artifact, hand-authored replacement content, generated
compatibility bytecode, relaxed evidence validation, or an identity alias
would fabricate dependency evidence and is prohibited.

## Coordination implementation status

The candidate implements and characterizes:

- direct public Contracts hosting for runtime access, projection, indexed
  verification, current-Root derivation, catalog access, and platform commit;
- explicit current-Root compatibility and indexed planning modes with exact,
  omitted, extra, duplicate, wrong-order, and stale-revision candidates;
- immutable subscription snapshots, deltas, serialization, and exact
  activation intervals, including post-commit activation, retirement, and
  fresh re-addition intervals;
- structured `collectionPaths` handling with stable object keys, nested
  embedded scopes, declaration provenance, and RFC 6901 member escaping;
- sparse indexed candidate selection with exact compatibility revalidation;
- source-owned external eligibility and checkpoints with same-scope target
  routing;
- canonical document/event fragments, exact collection-edge occurrences,
  selected-chain materialization, reconstruction, and duplicate-admission
  verification;
- processing preparation with two semantic inputs and out-of-band evidence;
- arbitrary registered Timeline subtypes without a concrete whitelist;
- Sequential/Chat workflow support, hosted BEX, static updates, event
  triggering, declarative termination, and deterministic rollback;
- persistence-neutral Mandate eligibility helpers;
- manifest-backed portable gas, separate host quotas, and deterministic
  infinite-work cut-off;
- the closed 65 behavior, 14 gas, and 7 host-quota case inventory;
- the green pure-reference representation matrix, including final Root,
  gas/named-trace equality, selected-body locality, and zero forbidden
  demands;
- the green nested Agreement/Lesson/Cancellation structural flagship for
  collection plans, provenance, canonical fragments, and reconstruction;
- an executable 32-run nested PROCESS matrix for the same selected identity
  spine whose three test methods are currently attempted but stop at the
  catalogued immutable Repository ABI before PROCESS; no runtime result is
  claimed from those blocked attempts;
- Java 8, binary compatibility, public API, locality/JMH, archive, and
  reproducibility gates;
- always-truthful baseline and final release reports.

The conformance package remains a candidate while dependency evidence is red.
Its declared identity must be refreshed whenever any fixture changes; the
integrity test recomputes it over every package byte.

## Verification

The hard command is:

```bash
./gradlew finalCoordinationVerification \
  --offline --no-daemon -PtestJfr=false
```

It always finalizes:

```text
build/reports/coordination-release/final.json
build/reports/coordination-release/final.md
```

The report may set `releaseEligible` to `true` only when every same-run gate,
all 86 conformance cases, all 32 flagship variants, the 516-entry trace,
sibling locks and publication alignment, fixed Repository evidence, API and
bytecode checks, and Coordination-owned archive reproducibility are green.
Until then it records exact failed, skipped, and not-executed cases and keeps
the candidate red.
