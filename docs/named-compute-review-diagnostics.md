# Named Compute upstream-review diagnostics

Isolated upstream candidate and necessary follow-up. Branch `codex/diagnose-named-compute-review`
starts at Coordination PR #18 head
`b5f7767de95ad494b46667b7e91369c7ab3c6a41`, descending from its remote `next`
base `0a047461bbc9e97a969ae1021bd86964eadd4c8c`. The original red run used
test-only commit `3e00e71fc7acbda41563b6b7052ec6171590f2f6`. The follow-up below
is not yet qualified. Dependency locks and release metadata remain unchanged.

The original diagnostic used this PR's published dependencies unchanged: Language/Contracts
`3.1.0-rc.25`, BEX `1.1.0-rc.6`, Catalog `3.0.0-rc.22`. Language PR #33 is not
required for those diagnostics; PR #34 is deliberately excluded. The new
follow-up requires the additive Contracts capability described below; stage
that exact local Language/Contracts candidate before testing Coordination.

## Bounded parent-owned run

Run `./gradlew test --tests blue.coordination.sdk.SdkNamedComputeDefinitionTest
--tests blue.coordination.processor.workflow.ComputeProgramNormalizerTest
--continue --max-workers=1 -PtestMaxParallelForks=1` as one invocation. The SDK
class retains the owner's existing tests and adds 16 parameterized cases. The
normalizer class supplies existing controls plus two presence-aware rejection
controls in the follow-up. Preserve complete
XML/stdout, including `NAMED_COMPUTE_DECLARATION_INPUT` records; a failure before
the target assertion is not proof of either review claim.

The follow-up adds another 12 SDK cases: absent/empty versus explicit
declarations, reached through referenced parents, inherited containers, and
partial local overlays of inherited containers. Existing successful workflows,
escaped pointers, static rejection and changed-definition tests remain enabled.

## Recorded original result

`upstream-review-named-01`: **74 tests, 4 failures**. Both root-pointer cases
failed at the incorrect `//functions/...` path; `/library` controls passed.
Named exact-provider declarations failed the required rejection assertion for
`constants` and `functions`; exact-reference selectors rejected the same values.
Absent and empty-map controls passed. Archive SHA-256:
`a0b3aaf9c2767c255a54bb0f1ec87b41914fd26f8efcd5262963ede2b269cb0e`.

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

## Necessary follow-up and scope

1. **Pointer composition:** use `PointerUtils.appendPointer` for every static
   type child path. `/` plus `functions` is `/functions`, not `//functions`;
   existing escaping rules also apply. No new selector policy is introduced.
2. **Presence-aware normalization:** preserve `constants`/`functions` supplied
   by exact contributions to the selected definition. Only absent fields may
   undergo the upstream inherited-empty-declaration omission. Explicit empty
   maps retain their valid empty object payload; `{type: Dictionary}` is not an
   empty object payload. Ordinary Source
   canonicalization is not reversed: discarded authored text is not evidence.
3. **Cache identity:** add this two-bit presence classification to the existing
   immutable Compute plan key and bump the private normalizer version. An
   identical effective view cannot borrow a plan compiled under different exact
   field presence. Working-document selection is evaluated on each invocation.

### Why an additive Language/Contracts capability is necessary

`WorkingDocument.canonicalAt` and snapshot Source indexes are structural reads:
they do not cross a reference or recover a type-provided nested occurrence.
`resolvedAt` crosses those boundaries but intentionally returns effective state,
not exact field presence. `SelectedExecutableBody` permits verified reads within
the executing `steps`, not arbitrary sibling references named by a string.
Using any of these as a provider bypass would break their existing contracts.

The narrowly scoped addition is
`WorkingDocument.sourceContributionsAt(String): List<FrozenNode>`. It returns
immutable exact contributions, containing ancestors before local overlays, to
one current working occurrence. It does not synthesize a new Source value or
identity. The selected node's own type defaults are not direct contributions.
References use the invocation's verified manager; managed references remain
path/identity-bound. Earlier working patches are visible. Unrelated siblings
are not traversed or resolved. List paths select effective indices, accounting
for inherited prefix, Source append/positional replacement, and final canonical
payloads; raw overlay offsets are not semantic indices.

Seven new Contracts tests cover already-expanded reference provenance, partial
inherited containers, working read-your-writes and closure of the preview,
effective inherited/replaced/appended list slots, nested item/value-type contributions,
and managed evidence reads.
This capability is additive public API, not an undocumented internal cast. No
BEX language, event ordering, gas schedule, storage, or closure rule changes.

The rejected alternative is retaining the upstream equality-only heuristic:
the recorded named/direct execution divergence proves it cannot determine
presence. Resolving the whole graph or reconstructing authored text from an
effective node is unnecessary and would not establish exact provenance.

## Integration boundary

Do not rewrite or remotely merge the owner's PR. Qualify its three commits
`b97889a`, `5d8af72`, `b5f7767` as one existing implementation; any confirmed
correction should be a separately attributable follow-up with these reproductions.
The current PR's published-dependency build failures are reported as RC-authority
preparation failures, not test evidence. Parent owns execution and result recording.
