# Revision15.6 review-refinement outcome

> **Revision:** 15.6 · **Date:** 2026-09-06 · **Stage:** design and review; no implementation approval
> [Package](../README.md) · [Decision traces](../20-decision-traces.md) · [Source evidence](revision-15-6-source-evidence.md)

## Outcome

Keep independent source/consumer processing: Agreement commits once, then each Order consumes retained
history and commits independently. This refinement supplies concrete rules for several previously
underspecified normal cases and aligns the tutorial, API sketch, schemas and planned scenario vectors.
It does **not** claim that all conceptual questions are closed or that G1 has passed.

## Applied changes

| Area | Selected proposal and expected control |
|---|---|
| One receipt, multiple placements | Freeze the pre-operation group; stage every after-pin before any handler; dispatch normal reference-update work in containing-occurrence order; admit the complete incoming batch, then drain normal queues. Alias-read control expects1 absent local mutation. Keep each required charge. |
| Placement created during import | It cannot enlarge the frozen group. If it survives successful creator publication, record a distinct causal-successor catch-up obligation for that activation only. No old-placement redelivery or live obligation after rollback/create-then-retire. |
| Nested relay | Authenticate original relays and actual own emissions in one immediate-producer result/batch companion. Root consumes Parent's batch/cursor once; original Source provenance and composed routes survive. Preserve actual queue order, including reference-update G before incoming E where applicable. |
| Source initialization | Freeze source initialization execution policy separately from parent budgets. Prepare non-authoritative complete evidence separately. Successful embedded first creation uses a bounded parent/X publication companion; X keeps its own initial stream and commit authority. Failed parent publishes no orphan. Standalone admission needs no fictitious parent. |
| Failed managed import | Failed r1 keeps the successful cursor at r0; already-due r2 remains owed/blocked. A later no-read detach is not automatically eligible and cannot silently cancel that history. |
| Representation-only change | Bind the required coupled representation scope in the triggering result/delta with unchanged business epochs/source positions. No new business receipt, synthetic event/fanout or gas reset. |
| Performance and acceptance | Vary P placements in one consumer independently from N consumers; count decode/hash/verification/copy work as well as source calls. An insufficient resource cap needs an actual releaser. Partial E1a does not waive G1's required CORE positive cases. |

The Java changes are a documentation sketch: selected group/relay evidence, a fixed source policy,
separate initialization preparation, and bounded creation/representation companions. They are not
production interfaces or runtime implementation. Avoid widening these into a generic genesis,
transaction-group or public proof-hierarchy framework.

## Decisions deliberately not disguised as implementation details

**BIRTH:** identical authored X, FROM_NOW atT10 versusT20, and source E15. Equal labels do not give one
compatible source history; earlier parent failure also matters under no-orphan publication. Choose
an explicit canonical source-history origin or a deterministic birth-at-attachment rule with sufficient
accepted intent/outcome authority. This revision chooses neither. Hold before unproved publication;
that is a safety control, not a positive liveness or compatibility solution.

**REPAIR:** managed r1@T10 fails, r2@T20 is already due, detach D@T30 does not read X. Define D's exact
rollback-state view, eligibility and the old activation's owed-obligation disposition. The future-only
retirement rule cannot silently erase already-due r2. Without a chosen rule D remains held; keep the
positive repair requirement visible. The existing consumed external failure/detach/new-input control
is separate and remains supported.

These are not the only remaining proof boundaries: competing multi-source/feedback ordering,
supported cyclic exact-value scopes, constructor/gas mapping, concrete complete frontier queries,
and creator-local reads depending on a not-yet-committed catch-up still need their specified rules
and independent vectors before accepting the dependent implementation paths. Required finite CORE
feedback/cycle fixtures cannot be replaced with an unsupported hold.

## Test plan and evidence discipline

The existing catalog remains53 schedules and9 designated subcases; profiles remain39/49/53/8.
New controls live inside the existing scenario families: group alias/update visibility, new-placement
successor, multi-hop relay and earlier G, equal-policy birth bounds, parent-budget independence,
failed-r1/r2/D30 repair safety versus positive decision, representation-only publication and P scaling.
Cards still require goal, Given/When, core result, durable post-state, forbidden outcomes, overlays
and measured budgets. These are planned tests, not executed fixtures.

The retained r15.2 experiment and its evidence are unchanged. No runtime/library/MyOS implementation,
processing test, PostgreSQL test or benchmark was run as part of this revision. Documentation QA is
limited to structural validation, reference-sketch compilation and archive integrity; it cannot prove
determinism, correctness or performance.

Structural checks completed for this snapshot: Java17 reference-sketch compilation, all3 JSON Schema
meta-validations,44 active Markdown files with390 local links and35 anchors, and parsing all12 Mermaid
blocks. The19 protected runtime/test/build/historical-evidence/archive files and the existing tracked
diff remain unchanged. The package manifest and archive are checked separately during packaging.

## Review handoff

Use the r15.6 ZIP and [updated prompt](gpt-pro-review-prompt.md), not an older profile of the design.
Review the selected traces directly, and propose concrete BIRTH/REPAIR semantics instead of treating
safe waiting or an opaque evidence field as resolution. The user alone decides when implementation
starts. Delivery remains libraries → target-shaped PostgreSQL MyOS Mini → integrated verification,
then iterations based on measured results; there is no release, migration or API freeze.
