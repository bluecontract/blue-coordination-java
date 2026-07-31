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

## Exact local source boundary

The build uses only the adjacent composite builds:

```text
blue-language-java    3.1.0-rc.18  9706b604d54d59e843f2d0540c1a892470d1aa5c
blue-bex-java         1.1.0-rc.2   395c484111f8c4e9e0e98d2db7f1c5b0777bd5a8
blue-repository-java  3.0.0-rc.17  63be6b7d8d2752b5a8c90f38e672859e9b3949a1
```

`settings.gradle` fails when any sibling is absent and substitutes all three
published module coordinates with these local projects. Coordination does not
modify those repositories, generated Repository classes, or `.cz.toml`.

The nested BEX publication request is not aligned with the selected Language
source:

```text
expected  blue.language:blue-language-java:3.1.0-rc.18
requested blue.language:blue-language-java:3.1.0-rc.19
```

Composite selection is intentionally separate from publication compatibility.
`verifyPublishedDependencyAlignment` must remain red until the upstream
coordinate is aligned.

The local Language runtime also has a reproduced multi-handler checkpoint
commit defect. `ChannelRunner` can queue checkpoint writes against different
stale `ContractBundle` snapshots; a later handler group then recreates
`/contracts/checkpoint` and erases an aggregate Channel checkpoint written by
an earlier group. Coordination keeps the aggregate/direct-child assertions
red with a `Language checkpoint coalescing defect` diagnostic. It does not
pre-seed marker state or add a second checkpoint commit path to hide the
Language-owned atomic transition defect.

## Fixed Repository evidence

The immutable local Repository manifest is:

```text
repositoryVersion       1.3.0
repositoryVersionBlueId msCV6VLe4Y1hayq2RnPbuzqZbroowpfBKexXoXBirZq
catalog entries         1107
verified                233
failed                  874
```

The current bound-source audit is written to
`build/reports/coordination-release/fixed-repository.json`. It verifies exact
source bytes and identities and never installs aliases or regenerated
definitions.

Representative blockers that stop before Coordination behavior include:

```text
Coordination/Chat Workflow Operation
  INVALID_EVIDENCE at /channel

Mandate/Mandate
  INVALID_EVIDENCE at /timelineId

Mandate/Mandate Authority Confirmed
  INVALID_EVIDENCE at /timestampUs

Mandate/Mandate Terminated
  INVALID_EVIDENCE at /reason
```

Consequently the three executable Chat Workflow integration cases and
`coord-mand-01` through `coord-mand-06` cannot reach their Coordination
handlers. The six remaining Mandate eligibility fixtures use immutable
caller-supplied evidence and remain independently executable. Supplying
hand-authored replacement type content, relaxing schema validation, or
aliasing an identity would fabricate dependency evidence and is prohibited.

## Coordination implementation status

The candidate implements and characterizes:

- explicit current-Root compatibility and indexed planning modes;
- immutable subscription snapshots, deltas, serialization, and exact
  activation intervals;
- sparse indexed candidate selection with exact compatibility revalidation;
- source-owned external eligibility and checkpoints with same-scope target
  routing;
- canonical document/event fragments, exact edge occurrences,
  reconstruction, and duplicate-admission verification;
- processing preparation with two semantic inputs and out-of-band evidence;
- arbitrary registered Timeline subtypes without a concrete whitelist;
- Sequential/Chat workflow support, hosted BEX, static updates, event
  triggering, declarative termination, and deterministic rollback;
- persistence-neutral Mandate eligibility helpers;
- manifest-backed portable gas, separate host quotas, and deterministic
  infinite-work cut-off;
- the closed 65 behavior, 14 gas, and 7 host-quota case inventory;
- the 32-variant complex embedded determinism flagship;
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
