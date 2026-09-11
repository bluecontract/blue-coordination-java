# Final Language candidate identity binding

## Problem and example

The fixes-only Coordination candidate was first qualified using a development
Language export whose implementation already contained the inactive-retarget
correction, but whose Contracts release manifest still named its predecessor.
The final Language candidate `2ce3e66bcec5bc064b9b80f5877c6f6e3c51fe08`
corrects that implementation-identity binding. Its maintained exporter declares
Contracts release identity
`sha256:d7e878bd320dd53837fc4298210b2a4d301fae02b9993dfa98ac652714c774eb`.

For example, selecting this exact Language export and the unchanged BEX and
Repository sources fails Coordination configuration while the SDK profile still
declares `sha256:156b58c6a19ab94cd3d1759dbbd115963c6d345c69511113798824cb5c749ced`.
The `development-catalog.gradle` guard correctly rejects the mismatch before
compilation or execution. A matching version string alone is insufficient.

## Correction and scope

Bind the SDK's `contractsRelease` property and its exact `SdkCoreSeamsTest`
expectation to the authenticated final Language manifest. The specification,
aggregate fixture-package, gas, cyclic finalizer and proof-verifier identities
remain unchanged. No Java runtime code, processing policy, public API, dependency
guard, test selection, or acceptance threshold changes in this correction.

The active topology evidence's JSON `inputs.contractsRelease` and corresponding
Markdown input line receive the same single-literal substitution. Their owning
`CyclicTopologyIdentityEvidenceTest.Recorder.document()` reads this field
directly from `BundledContracts10Release.manifest()`. Every other byte of those
two artifacts is preserved: scenarios, exact states, histories, events, gas,
repeat counts, failures and boundary qualifications are not replaced. This is
a mechanical input-metadata update, not newly generated runtime evidence.
Historical rc.7, rc.8 and rc.9 release notes keep their original identities.

## Why this boundary

Contracts release identity binds implementation sources as well as the frozen
semantic package. The reviewed Language manifest correction changes three
implementation-source hashes and the derived release identity; it does not
replace the conformance fixture outputs. Coordination must name the actual
selected release instead of bypassing authentication or retaining stale metadata.
Updating semantic golden output from a new run would not be justified by this
dependency-binding mismatch and is deliberately outside this change.

## Evidence and remaining gates

The initial configuration failure is retained in
`rooted-final-baseline-exports.mPVtoK/coordination-dry-run.log`. That invocation
ran no tests and created no Coordination export. Language's new immutable
development manifest is
`sha256:00c748cd653a0b584a681affc39717b7f5361f330e95b221cb14d986376052a6`;
its maintained assembly and closed-repository verification passed. Unchanged
BEX and Repository sources were separately exported against those exact bytes.

This binding change is checked by exact source/metadata comparison and the
maintained export guard, not by a new test run in this step. Full final-tuple
MyOS resident acceptance remains required before either library PR merges.
Existing library gates, topology runtime-evidence verification and eventual
published-dependency qualification remain mandatory; no export asserts release
readiness. No push, merge, tag or public release is part of this correction.
