# Inactive retarget: immutable input and historical rejection evidence

This isolated correction closes the historical known-red case in a focused
gate; it is not a full retarget qualification.
Coordination remains an isolated candidate based on
`04a1d8fb040a680a962d5d24e4c3cf3dae89ef99`; it is not exported as a completed fix.
The separate Language correction is clean commit
`7ec0fdaafff41ad8e41387e7e5647d5a800d807c`, following the original bounded
`80e0a3e5d11b80681fe48e563984f62f68c0f653` correction on pinned `80653645`.

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

## Historical known-red case: original C1 plus selected C0

P can also embed C at another path. C1 is then already an original frozen
primary view when the operation installs C0 at the inactive B path. It is not
legal to replace C1 with C0. The current additive historical-role mechanism
does not carry a freestanding second proof of the same document: Language
`RootedWitnessFrame.readExpansion` retains source proofs matching new primary
read views; already-present/live/original witness IDs are skipped. Older aliases
inside another newly captured source's complete proof are a different case.

`RootedFrozenHistoricalRetargetReproductionTest` keeps the normative
**REJECTED** expectation. On the original bounded correction it failed with
`NEEDS_RESOURCES` / `REQUIRED_EXACT_RESOURCES`; preceding assertions confirm all
heads, histories and both occurrence rows remain unchanged. This is not a green
outcome. Its XML is retained separately in
`build/retarget-evidence/frozen-target-red/`.

That observation initially suggested a second historical proof role. The narrower
follow-up instead uses the existing `ManagedOccurrenceEvidenceResolution`
contract: an exact host historical selection already identifies durable lineage
and selected epoch independently of the newer primary view. Legal active
historical replacement uses that same boundary today. In Coordination,
`ManagedOccurrenceResolver.resolveExplicitSelection` authenticates the selected
source, exact epoch and supplied BlueId against replayable retained state. A
numeric epoch range, or the C1 body alone, is not proof of C0.

The follow-up retains both original C1 and the inactive B row. It admits a
demand-linked foreign selection only for initialized epochs from zero through
the frozen source epoch; selection at that head must also match its exact BlueId.
An older selection uses the existing trusted host-resolution boundary. Language
then rejects only when the actual effect consumes that exact demand, at the
existing `MANAGED_OCCURRENCE_BINDING_MISSING` reconciliation point. This is
rejection evidence, never activation permission. No new API, role, schema,
source publication, alias suppression or early demand-discovery rejection is
introduced. Authored pre-initialization epoch −1 remains outside this initialized
rejection-witness rule.

The extended SDK reproduction observes the actual processor-issued demand,
derives the C0 retry identity, and requires that identity in the retained result.
It separately checks frozen C1, both original rows, rollback, positive completed
gas, no committed effects, replay and restart. It passes in the follow-up 13/13
SDK gate against the new clean immutable Language tuple.

## Proven legal-case boundary

The demonstrated C1/C0 proof gap is not reproduced by either of these legal
operations when P already embeds C1 at `/orders/other`:

- attaching saved C0 at the fresh path `/orders/fresh`;
- replacing an active B occurrence at `/orders/same` with saved C0.

`RootedFrozenSourceHistoricalAttachmentTest` retains both controls. They assert
APPLIED, unchanged original frozen C1, exact pending C0 output (generation 1 for
fresh attachment, generation 2 for active replacement), one retained catch-up
application, quiescence, and restart continuity. The existing `/orders/other`
occurrence and C's independent head/history remain unchanged. Pending C0 is
checked in the closure output; the public parent snapshot need not expose the
pending attachment before catch-up completes.

These legal paths use the existing pending-history representation: active
replacement obtains demand-linked historical retry evidence, while a fresh path
gets a prospective pending row. They do not require substituting C0 for frozen
C1 as an inactive-retarget rejection witness. This narrows the demonstrated gap;
it does not qualify every untested topology.

## Evidence and reproduction

The direct SDK witness was red on unchanged production: the original-input
exception, with the separate active-retarget control passing. The new-target
historical negative now passes against the corrected immutable upstream tuple.
The original supported-scope gate passed 8/8 tests (3 + 4 + 1), explicitly selecting
these owners only:

```
blue.coordination.sdk.RootedRetargetInputTest
blue.coordination.sdk.RootedRecreatedCollectionOccurrenceTest
blue.coordination.sdk.RootedReadOnlyReferenceRebindTest
```

That gate excluded the then-known-red owner; do not represent it as a complete
green family. Exact commands, hashes, transitive bindings and source-clean export audit
are in `/Users/kamil/Documents/Projects/Blue/rooted-retarget-development.1tsGn1/`.
Language's original separate focused gate was 32/32. No full library matrix or
MyOS test ran in this correction lane.

The separate legal-boundary diagnostic passed **4/4** on production commit
`c16d2445dcfece1b0c190b4389e08633e9b10239`, against the same corrected immutable
upstream tuple, using Java 17 and one Gradle worker. Its exact filter was
`RootedFrozenHistoricalLegalDiagnosticTest` (the two retained controls) plus
`RootedMultipleHistoricalOccurrencesTest` (two existing controls). It ran in
`/Users/kamil/Documents/Projects/Blue/worktrees/rooted-retarget-legal-diagnostic`.
The diagnostic XML SHA-256 is
`49ad52a01e50c99c23cd2d4e5330f95f634aa8cb0a15f0e71e13bf38cbb8e049`;
the existing-owner XML SHA-256 is
`1e48ff420df6442a7dc0aebe719dda24bdcdef89e1a0d9941c46890acd8bcb75`.
The maintained class differs only in its name and class comment; all test/helper
bodies and assertions are unchanged. No rerun is claimed for that naming-only
retention. `EVIDENCE-RECEIPT.json` in the evidence root above records the exact
run, source hashes, class-name mapping and XML paths separately from the earlier
8/8 gate. That receipt remains an accurate historical partial result and is not
rewritten by the follow-up. Coordination is not exported there.

## Follow-up focused gate

Language's four selected owners now pass **34/34**, including the unchanged
original-input expansion guard and new historical foreign/alias controls in
inline and reference forms. Future and authored epochs, absent source, wrong
path, forged demand identity, wrong cause and equal-epoch wrong BlueId are
rejected. The exact host historical membership boundary above is essential:
Language cannot independently prove a historical body's membership from C1 alone.

The SDK gate passes **13/13** across the prior three owners (8 tests),
`RootedFrozenHistoricalRetargetReproductionTest` (1),
`RootedFrozenSourceHistoricalAttachmentTest` (2) and
`RootedMultipleHistoricalOccurrencesTest` (2). The former failure now reaches
the normative terminal rejection. Its retained execution identity equals the
retry derived from the actual processor demand and exact C epoch-zero selection;
the immutable input still contains C epoch one. The control additionally proves
unchanged independent source history, no output effects, replay and restart.

Commands, complete XML, source hashes and fresh immutable upstream manifests
are retained in
`/Users/kamil/Documents/Projects/Blue/rooted-retarget-historical-development.zE0Rg1/`.
The three exports bind clean Language `7ec0fdaa`, unchanged BEX `ab72af14` and
unchanged catalog `0b68744b` sources. The old evidence root and baseline
candidate/artifacts were not modified. Coordination is not exported by this
gate. These focused results do not qualify the full library or release matrix.

For the old MyOS fixture, preserve its invalid inactive-C operation as a rejected
entry, reactivate B at the same reserved generation, then perform a distinct
supported active C-current-head retarget at generation +1. Do not simply change
the expected inactive generation to C. Historical C catch-up assertions do not
belong to an active CURRENT_HEAD retarget. The already-frozen C1/C0 reproduction
remains a maintained normative regression.
