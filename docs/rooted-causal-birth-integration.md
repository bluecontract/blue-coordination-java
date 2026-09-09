# Rooted causal child creation

The SDK's declared managed draft now reaches the processor as an unresolved
prospective occurrence. Only the actual resource demand from that exact attempt
is passed to the existing `withProspectiveBirths` factory. The complete attempt
is replayed under the original gas policy. A host declaration or an absent
database row does not grant publication ownership.

For an owned birth, the atomic publication retains the child's initialization
receipt, routes, selected view and `CREATED_IN_OPERATION` history basis. The
basis binds the frozen entry operation, canonical declared birth occurrence and
exact causal order. Existing owners retain their head and graph-generation
fences; new owners require absence fences. Failed processor work records only
its terminal outcome and leaves all birth and parent state unpublished.

When a parent calculates an embedded source's creation operation, the new child
is retained in that parent's local selected view. Its source remains unchanged.
Independent source processing subsequently publishes the child exactly once.
If that publication happened first, local reproduction requires the same exact
authored child, birth occurrence, original causal receipt and birth companion.
It does not import the child's current head or classify unrelated stored content
as a newborn.

Validation uses real SDK operations and production store boundaries:

- `RootedCreatedChildTest`: direct creation and independent child processing in
  both schedules; parent-local source creation in both schedules; identical
  final heads and immutable receipts; restart before independent publication;
  late gas failure with no published child or parent progress.
- `RootedBirthPublicationTest`: exact present/absent subscription fences, seven
  missing/conflicting/unrelated/already-present fence negatives, and lost-response
  replay without duplicate birth publication.
- Unchanged `RootedGasBoundaryTest` and `RootedPublicationAuthorityTest` pass,
  including the independent materialized gas reference and owner mutations.

All 11 tests in that focused run passed. Evidence is retained in the integration
campaign at `evidence/sdk-rooted-created-child-owned-rollback` and its adjacent
Gradle log. An earlier broader run executed 24 tests: 22 passed; the birth gas
failure is fixed here, while the pre-existing empty `FROM_NOW` admission failure
in `ClosureSubscriptionInventoryTest.rebasesUnmentionedRowOnlyFromItsExactCapturedGraphFence`
remains unresolved. No admission grammar, gas tariff, footprint limit or release
gate was relaxed.

This is a development integration checkpoint. Bare authored-source discovery,
the complete production adapter, all 34 production obligations, packaged MyOS
birth/restart verification and the final RC gates remain required.

## Bare-source prerequisites and original operation targets

A newly resolved authored value without an explicit causal-birth declaration
now remains `UNPROVEN_MANAGED_HISTORY` / `NEEDS_RESOURCES` until its established
history is available. It cannot reach publication as an unowned document with
no source lineage. Wrong content under the requested BlueId still fails exact
identity verification; content availability alone does not create receipts.

`RootedSourceDiscoveryTest.missingHistoryRemainsAWaitUntilCommittedEvidenceIsSupplied`
proves missing-body wait, strict wrong-body rejection, body-only history wait,
restart while pending, independent FULL_HISTORY source admission and processing,
then the original attachment's separate -1→0 and 0→1 applications. It checks
counter 5, unchanged source receipts and exact retained restart state.
This is a resource-continuation proof, not completion of automatic discovery;
the two cold-source/suspension acquisition regressions remain failing obligations.

That test also exposed non-exact routing dropping an entry addressed to the
source's original authored ID. The routing inventory now includes the session's
exact retained authored identity. The exact-version branch still accepts only
the selected current head. `RootedExactVersionRoutingTest` checks the original-ID
positive, exact-original rejection and unrelated-ID rejection.
