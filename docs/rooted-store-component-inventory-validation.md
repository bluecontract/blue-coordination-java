# Rooted component inventory validation during eager restoration

The POC `coordination-cold-receipt-group02` gate on
`bf8ca5c842cd8e420a2a1332ab73e47b653cb370` ran 49 cases with one failure.
The real same-epoch represented-target rejection passed its representation,
operation and rejection assertions, then failed during eager `StoreState`
restoration, before the rejected-receipt validator. The exception was
`Component states are not in target-before-source condensation order`.

`ComponentStateInventory.statesFor` intentionally emits rooted publication
proofs in canonical scalar order of each proof's first member. Independent
roots retain their own authenticated partitions; their global reference union
is not another authoritative SCC partition or one current semantic DAG.
`withSessions` passed that correct inventory to a legacy global condensation
order check. The extra operation declarations changed the content-derived
parent identity and exposed the conflicting orders. Changing those identifiers
or sorting rooted proofs into a global execution order would conceal the defect.

The eager constructor now selects the matching existing inventory contract:
rooted proofs require canonical first-member scalar order and every member's
exact indexed component association; non-rooted proofs still require the
unchanged target-before-source condensation order. Duplicate lineage/state,
overlapping membership, session/subscription head and generation, and all
receipt/history checks remain active. No global SCC is recomputed for rooted
validation and no proof, wire format, public API, scheduler or logical gas rule
changes. This correction is confined to the POC branch, not the independently
qualified baseline.

`InMemoryDocumentStoreStateTest` reuses the actual two-publication representation
fixture: valid rooted inventory retains original proofs and receipt history;
reversed rooted order and foreign indexed membership fail; legacy correct order
passes and its reversal still fails. Forged index variants are never installed
in or used to execute an SDK. The existing
`PublicationReceiptStorageCodecTest.rejectedTargetCanBeAnExactRetainedSameEpochRepresentation`
keeps both eager and cold restoration assertions unchanged. New native results
remain pending the parent's source-bound grouped gate.
