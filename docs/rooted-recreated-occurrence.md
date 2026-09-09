# Re-adding a removed collection source from saved history

The public SDK regression starts an empty collection, adds saved initialized S0,
observes one source event, removes the key, and re-adds the identical saved S0.
It must reuse the inactive successor generation, import the retained source event
once for that new occurrence, and receive the next LIVE event. The source head,
retained receipt prefix and original input row must remain unchanged by catch-up.

The old tuple rejected the re-add with `MANAGED_OCCURRENCE_BINDING_MISSING`.
Language's corrected demand discovery then exposed an attempted input mutation:
automatic augmentation replaced the committed reservation, and
`RootedInputExpansion` rejected it. That check is retained unchanged.

The adapter now passes the selected historical position through the existing
demand-bound processing retry. Only a matching inactive input reservation for the
same lineage enters this branch. Language verifies and consumes the selection at
the real demand boundary; the read expansion keeps the original binding intact.
No publication ownership, timestamp frontier, receipt, gas tariff, or cause format
changes are made. This is the RCP-GRAPH-05 lifecycle, not a new history exception.

`RootedRecreatedCollectionOccurrenceTest` covers the saved-reference lifecycle,
immutable input row, restart while pending, subsequent LIVE delivery, foreign
lineage rejection, and gas at the measured success boundary and one below it.
These tests are required development checks, not a substitute for final component
and MyOS product acceptance. Exact results are recorded in the integration campaign.

The focused eight-test SDK run passed against Language
`d49ab0ba0a8d1dc0989c0cf04326a6d48784aa0b`, BEX
`925d7f04562ec62983a59efee00677ef8af77257`, and catalog
`4ba5f6f77292d8f0178ecbaec696bf165ddd448a`. It includes the three new tests,
both frozen-frontier schedules, the unchanged five-successor saved-A5 cyclic join,
and both independently materialized gas boundary tests. The old exported tuple's
saved-reference rejection and the intermediate read-expansion rejection are retained
as before-repair evidence; neither is counted as a passing run.
