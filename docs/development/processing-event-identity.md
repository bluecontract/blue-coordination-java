# Original processing-event identity

The companion change for [development#22](https://github.com/bluecontract/development/issues/22)
uses `ProcessorExecutionContext.exactProcessEventIdentityEvidence()` to retain
the original event's admission-proved BlueId in hosted BEX. Contracts can retain
a Source cursor whose representation hash differs from that identity. Calling
`BexValues.frozen` alone loses the independent proof; `$nodeBlueId` can then
return the wrong identity even though member reads still see the original
event.

`BexWorkflowContextFactory` now binds the cursor and proved ID together with
`BexValues.exact`. Workflow observations carry the same explicit identity.
Legacy standalone contexts without retained evidence keep their snapshot
fallback, and an absent or unused processing event remains undefined.

`BexProcessingEventBindingTest` exercises the real BEX `$nodeBlueId` operator,
inline nominal Source types with list content, expanded annotations, the
standalone fallback and absence. `ProcessingEventIdentityEvidenceTest` checks
that diagnostic observations preserve the proved ID and reject changed
identity or content.

## Upstream dependency

This source change requires the companion Contracts accessor, first published
in Language `3.1.0-rc.26`. The published dependency pins and both exact
dependency lockfiles select that release through the existing
[dependency lanes](build-and-test.md); no mutable local path is used.

The initial diagnosis compiled all Coordination production sources against
sealed copies of the modified Language/Contracts JARs, published BEX
`1.1.0-rc.6` and Repository `3.0.0-rc.22`. It passed the focused binding and
observer regressions, a real managed external → triggered → document-update
closure through the production BEX context factory, and the original SDK
example from development#22. This is diagnostic validation, not a release
gate or a claim that the published dependency graph contains the new API.
