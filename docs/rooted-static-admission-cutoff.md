# Static admission keeps its selected source publication

## Problem and exact example

A source is admitted, then processes entry E and publishes epoch 1. A new
`FROM_NOW` static parent embeds that source, either inline or by its authored
BlueId. The admission frontier is E, and the admission retains the existing
source's exact epoch-1 head without changing its session. Its pending historical
application subsequently failed: `rootedViewBefore(E)` selected epoch 0, while
the admitted parent correctly held epoch 1. The numbered terminal-source guard
rejected these different source positions. A MyOS READY timeout was the later
symptom, not grounds for extending a deadline.

The direct inline SDK reproducer fails on the original implementation with
`Numbered terminal source differs from its frozen source publication`, selected
epoch 1 versus frozen epoch 0. The reference variant requires an exact-wire
provider value, not unresolved authored YAML; both maintained variants exercise
the corrected admission and catch-up path.

## Correction and authority

Rooted `FROM_NOW` admission captures the actual retained `RootedDocumentView`
for each existing selected source. Capture checks the admission input's exact
epoch/BlueId, the frontier, and membership in that source's real committed
publication history. The immutable position references are retained with the
new document's admission-owned history facts in the same atomic transaction.
Initial catch-up planning, subsequent per-consumer planning, and rooted
invocation capture use these positions only at that original admission
boundary. Each use still verifies retained publication membership. Existing
co-owned publication authority remains checked first.

This implements the existing admission-selected anchor, consistent with
RCP-CAUSE-01/02 and the FROM_NOW lower-exclusive activation entry in RCP-ID-01.
The source position includes publications already applied at that entry; future
live input eligibility remains strictly after the activation entry. This is
physical evidence retention, not a new history-basis field or semantic hash.
There is no public API, specification, gas, or tariff change.

## Why not change the comparison?

Dynamic attachments continue to use the complete input's strictly-before
cutoff. Replacing `<` by `<=` globally would change that rule. Looking up an
inclusive current source later would also permit later same-epoch positions to
extend the original admission goal. An epoch or BlueId alone cannot identify
such a position. Removing the terminal guard or changing the parent to use an
older source would conceal the inconsistency instead of preserving the
admission's exact evidence.

The change is scoped to rooted FROM_NOW static admission. Other admission
policies keep their current behavior. The retained references structurally share
existing immutable views; they neither duplicate source histories nor authorize
publishing an independently owned source. Restart from the owning stores retains
the same references. This is not a new external-storage restoration API.

## Focused controls

`RootedStaticAdmissionCutoffTest` covers inline and exact-reference reuse at the
equal frontier, a later numbered source publication excluded from the frozen
interval, and a same-epoch representation target preserved across a later source
publication and store restart. Retained applications compare actual status,
gas, gas-trace identity, and output-closure identity against the existing real
materialized reference calculation. Independent source histories remain exact.

The nested representation fixture first independently materializes the matching
source entry: without that step its calculated forward dependency differs from
the durable source and the existing admission expansion guard correctly rejects
it before this repair applies. No guard is bypassed.

Focused qualification: 35 tests and Javadoc passed in 1m14s using Java 17 and
the immutable local baseline upstreams (Language 80653645, BEX ab72af14,
Catalog 0b68744b). The six owners are the four new controls plus the unchanged
`RootedFrozenFrontierTest` (2), `RootedAdmissionBasisTest` (18),
`RootedCheckpointRepresentationSafetyTest` (6), `RootedTerminalTailSdkBoundaryTest`
(2), and `RootedAdmissionIndexLocalityTest` (3). No full matrix, immutable export,
or MyOS result is implied by this library slice.
