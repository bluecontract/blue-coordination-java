# Preserve the diamond diagnostic under terminal-time witness selection

## Problem and exact evidence

The native Java 17 run on Coordination `905ad89e` and Language `e28ce805`
reaches the unchanged D-before-B setup: independent A11, historical D2 inside
A's proof, and independently completed D14. The diagnostic then throws
`IllegalArgumentException: Exact retained source or original primary changed`
at `RootedDiamondAcquisitionProbe.exact:189`, called from
`publishExpandedOriginal:111` and the test at line 70.

That assertion expected D14 in B's original LIVE read expansion. Production
correctly returned historical D2. The already implemented terminal-time change
(`bbd06a0`, documented in `rooted-terminal-peer-acquisition.md`) deliberately
keeps original LIVE operands independent of physical peer completion and selects
new immutable peer positions only when admitting a fresh eligible terminal.
The old helper also manually published raw local terminal input, bypassing that
new selection and any still-required receiving-root original work.

## Test-mechanics correction, not a production or protocol change

Keep the same test identity, authored fixture, T300 entry, D-before-B preparation,
frozen A11 endpoint, prior histories and business expectations. Execute B's
original and all subsequent selected work through normal SDK `processNext`.
Use a fixed root-call preference and the existing pure root-selection audit;
do not add a scheduler or manually designate joint owners.

Read each actual publication's immutable input and complete execution through
the maintained Advanced API. For registered work, identify its publication by
the exact durable work/application/receipt relationship. Enumerate external,
root-local and registered attempts without filtering failures or suspensions.
Reject even zero-closure failed/suspended entry lanes. An empty entry must be an
explicitly zero-effect transport NO_MATCH. A nonempty NO_MATCH may describe an
unrepeated targeted selector while other work committed; every such closure must
still be APPLIED and independently verified. B's original must be APPLIED.
A post-publication waiting boundary or one-step budget pause is not
misclassified as failure, but its aggregate diagnostic and every actual attempt
remain checked.
Require retained input identity to equal actual executed identity; a different
retry input is an explicit failure, not a base-input replay shortcut. Recalculate
each exact input in a fresh Contracts runtime and compare the original full
invocation/result/output/events/receipts/checkpoint/companion/gas/trace oracles.

Independent selection assertions require A11/D2 in the original despite the
already available D14, then the genuine registered terminal containing D14 while
preserving all A11-owned occurrence rows, including its D2 source reference.
The original rooted context/delivery-basis identities and unchanged D2 result
position remain asserted. Every actual publication's retained logical boundary
must be at or before the original exact T300 order. These use existing retained
evidence, not a new progress policy or scheduler. Actual terminal eligibility may require A's
original first; its omission was an obsolete test mechanism, not a business
guarantee. The selected terminal entry root remains the one chosen by production.
The negative control rejects replacing D2 after it is part of the fully resolved
frozen original. It does not claim the generic Language factory alone determines
Coordination's initial choice for a member absent from the raw B/C input.

Unchanged final oracles: `[0,1,1,2]`, exact token multiplicities, all old history
prefixes, frozen source/plan checks until completion, COMPLETE/READY status,
restart store equality and post-restart quiescence. No disabled test, increased
budget/timeout, epoch relabeling, discarded failure or gas waiver is introduced.

The sibling schedule/G835–G837, late terminal gas failure, rollback/retry/restart,
dormant reconnect and three-node publication safety tests remain intact. This
correction's qualification belongs to the source- and artifact-bound external
receipt, not a predicted result in this document.
