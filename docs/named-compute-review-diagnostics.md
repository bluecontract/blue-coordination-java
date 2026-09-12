# Named Compute upstream-review diagnostics

Test-only candidate; not executed. Branch `codex/diagnose-named-compute-review`
starts at Coordination PR #18 head
`b5f7767de95ad494b46667b7e91369c7ab3c6a41`, descending from its remote `next`
base `0a047461bbc9e97a969ae1021bd86964eadd4c8c`. No production files, dependency
locks, release metadata or other worktrees were changed.

Use this PR's published dependencies unchanged: Language/Contracts
`3.1.0-rc.25`, BEX `1.1.0-rc.6`, Catalog `3.0.0-rc.22`. Language PR #33 is not
required for these diagnostics; PR #34 is deliberately excluded.

## Bounded parent-owned run

Run `./gradlew test --tests blue.coordination.sdk.SdkNamedComputeDefinitionTest
--tests blue.coordination.processor.workflow.ComputeProgramNormalizerTest
--continue --max-workers=1 -PtestMaxParallelForks=1` as one invocation. The SDK
class retains the owner's existing tests and adds 16 parameterized cases. The
normalizer class is unchanged and supplies existing controls. Preserve complete
XML/stdout, including `NAMED_COMPUTE_DECLARATION_INPUT` records; a failure before
the target assertion is not proof of either review claim.

## 1. Root-pointer static types

[Review comment](https://github.com/bluecontract/blue-coordination-java/pull/18#discussion_r3989375936):
`definition: /` passes `/` to static validation, which concatenates another `/`
and may read `//functions/.../type`.

`rootAndNestedDefinitionPointersUseTheSameStaticTypeRules` compares root `/`
with `/library`, using the owner's actual coffee workflow and exact static-type
provider values. Valid types must emit exactly the expected typed event. Invalid
types containing `$add` must reach the established static-type compiler rejection,
not fail because a pointer was unresolved. Both success and rejection are
specified independently of observed results; no fallback or mocked reader exists.

## 2. Explicit declaration versus inherited absence

[Review comment](https://github.com/bluecontract/blue-coordination-java/pull/18#discussion_r3989375930):
`omitInheritedEmptyMap` uses structural equality, not authored-field provenance.
The owner's synthetic normalizer tests do not cover an explicit declaration
identical to the inherited Dictionary declaration.

`exactProviderDefinitionKeepsContainerMeaningAcrossNamedAndExactSelection` uses
one exact provider definition, selected either through the named working-document
occurrence or directly by its exact BlueId. For each of `constants` and
`functions`, absent fields and explicit empty maps are successful controls;
an explicitly present `{type: Dictionary}` must be rejected under BEX §2.4's
plain-name-container rule. Every successful control emits the same one event.
The exact provider value is preprocessed and hashed without Source minimization,
so preparation cannot silently erase the disputed field.

The diagnostic also logs the separately Source-canonicalized value. This matters:
if ordinary Source canonicalization makes an explicit inherited-only declaration
equivalent to omission, authored text alone cannot justify distinct runtime
behavior. Interpret the actual retained occurrence and the exact-reference
control before prescribing a provenance fix. No new semantic rule is assumed.

## Integration boundary

Do not rewrite or remotely merge the owner's PR. Qualify its three commits
`b97889a`, `5d8af72`, `b5f7767` as one existing implementation; any confirmed
correction should be a separately attributable follow-up with these reproductions.
The current PR's published-dependency build failures are reported as RC-authority
preparation failures, not test evidence. Parent owns execution and result recording.
