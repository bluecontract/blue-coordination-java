# Inactive retarget: bounded correction and remaining gap

This is an **incomplete correction family**, not a complete retarget qualification.
Coordination remains an isolated candidate based on
`04a1d8fb040a680a962d5d24e4c3cf3dae89ef99`; it is not exported as a completed fix.
The separate Language correction is clean commit
`80e0a3e5d11b80681fe48e563984f62f68c0f653` on the pinned `80653645` baseline.

## Scenario and normative result

P attached B at a collection path, then removed it. The inactive successor
still belongs to B. Installing saved initialized C0 at that path must reject:
Contracts §5.6 prohibits inactive prospective/retirement-successor retarget.
Same-lineage B reactivation is legal and reuses the reserved generation. A
separate active B-to-C `REBIND` is legal and allocates generation +1.

The prior read-expansion code replaced the inactive B input row with C. The
unchanged Language guard correctly threw `Read expansion changed an original
occurrence`. Preserving B alone then exposed a repeated resource-demand stop.

## Supported bounded correction

For an unchanged original inactive, pending-null B row, keep its exact input
identity. If initialized C is absent from original frozen membership, capture
the already resolved, non-negative source epoch's authenticated complete
publication as a **rejection-only read witness**. Verify exact epoch/BlueId,
public/Contracts receipt linkage, actual retained publication and membership in
the source's publication prefix before the attachment's logical boundary.
Preserve every original document and row during expansion. Valid attachments,
same-lineage history, active retarget and pending receipt-event retarget retain
the existing boundary-head capture path.

The Language retry admits only exact demand-linked foreign rejection evidence,
then rejects at actual effect consumption with
`MANAGED_OCCURRENCE_BINDING_MISSING`. It never activates/rebinds that inactive
row, starts joined source work, or publishes the historical source. SDK controls
assert completed nonzero gas, no committed transitions/events/checkpoints/
receipts/companion, unchanged source heads/history and reservation, replay and
restart. A separate legal active retarget verifies output-only `REBIND`,
generation +1, future live observation and the exact G/G−1 gas boundary.

Early rejection based only on matching bytes was deliberately rejected. A saved
B epoch can equal C's current bytes. Language's full processor regression proves
the same input succeeds with B's historical selector and rejects with C's exact
selector. Original-input and retry/output continuity guards remain intact.

## Remaining ordinary case: original C1 plus selected C0

P can also embed C at another path. C1 is then already an original frozen
primary view when the operation installs C0 at the inactive B path. It is not
legal to replace C1 with C0. The current additive historical-role mechanism
does not carry a freestanding second proof of the same document: Language
`RootedWitnessFrame.readExpansion` retains source proofs matching new primary
read views; already-present/live/original witness IDs are skipped. Older aliases
inside another newly captured source's complete proof are a different case.

`RootedFrozenHistoricalRetargetReproductionTest` is executable and intentionally
keeps the normative **REJECTED** expectation. It currently fails with
`NEEDS_RESOURCES` / `REQUIRED_EXACT_RESOURCES`; preceding assertions confirm all
heads, histories and both occurrence rows remain unchanged. This is not a green
outcome. Its XML is retained separately in
`build/retarget-evidence/frozen-target-red/`.

Completing that case needs an explicit design for authenticating a second exact
historical role without replacing original C1, while preserving demand identity,
lineage choice, ordering, authority and completed gas. No such API/schema/policy
change is attempted here. The existing factory already accepts a list of source
proof snapshots, so a new public method signature is not proven necessary;
however its current one-source-role-per-DocumentId model and verification rules
cannot express this case unchanged. Whether a compatible internal role extension
is sufficient needs a separate serialization/recovery and authority design.
Authored pre-initialization epoch −1 is likewise not
covered by the new initialized-publication rejection witness.

## Evidence and reproduction

The direct SDK witness was red on unchanged production: the original-input
exception, with the separate active-retarget control passing. The new-target
historical negative now passes against the corrected immutable upstream tuple.
The final supported-scope gate passed 8/8 tests (3 + 4 + 1), explicitly selecting
these owners only:

```
blue.coordination.sdk.RootedRetargetInputTest
blue.coordination.sdk.RootedRecreatedCollectionOccurrenceTest
blue.coordination.sdk.RootedReadOnlyReferenceRebindTest
```

Run the known-red owner separately; do not aggregate it into a green-family
claim. Exact commands, hashes, transitive bindings and source-clean export audit
are in `/Users/kamil/Documents/Projects/Blue/rooted-retarget-development.1tsGn1/`.
Language's separate focused gate was 32/32. No full library matrix or MyOS test
ran in this correction lane.

For the old MyOS fixture, preserve its invalid inactive-C operation as a rejected
entry, reactivate B at the same reserved generation, then perform a distinct
supported active C-current-head retarget at generation +1. Do not simply change
the expected inactive generation to C. Historical C catch-up assertions do not
belong to an active CURRENT_HEAD retarget. The already-frozen C1/C0 reproduction
must remain visible until the separate proof-transport question is resolved.
