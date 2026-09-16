# Rooted engine control storage subcomponent

## Exact problem

Reopening document heads alone does not reproduce the next SDK selection. The
engine also retains Timeline registrations/actor kinds, logical and application
clocks, configured public roots, lane-local terminal progress, and fair-turn /
failed-work isolation state. For example, forgetting a terminal feeder ticket
can repeat a terminal gas failure; forgetting isolated historical work can prevent
an independent live root from progressing. Neither is repaired by replaying the
journal as part of storage restoration.

## Bounded implementation

The package-private `EngineControlStorageCodec` stores actual captured engine
configuration, all Timeline registrations (including registered-empty actor
Timelines), both clocks, public roots, rooted yielded/isolated-work state,
managed deferred/isolated consumers and turn, complete terminal feeder tickets /
frontiers, and journal terminal keys / contiguous frontier. Original insertion
order is preserved where retained. The existing selection and publication
algorithms are unchanged.

The physical envelope has an explicit format version, checksum, exact UTF-16
strings, arbitrary `BigInteger` order components, bounded counts and complete
canonical re-encoding. Duplicate map keys, duplicate set entries, invalid
booleans and inconsistent feeder frontier coverage are rejected. Decode is
physical/noncommitting and does not call a provider or processor.

Detached scheduler objects can be reconstructed from this state. There is no
public complete-engine restore API and no installation into a live engine in
this slice. Future assembly must authenticate its pinned document-store,
Timeline-journal, object-store and route/source-index components together and
validate their selected bindings. A checksum is not a publication fence.
The component is bounded by configured bytes; encoding these maps is not a
constant-time/scoped index lookup or a no-global-control-scan claim.

## Explicit remaining authority and index boundaries

Capture fails closed instead of omitting any of these nonempty states:

- `RootedSourceDiscoveryCoordinator.pending`, `completed`, `submitted`: exact
  original `CohortInvocation`, suspended `ClosureAttemptResult`, selected emitted
  `ManagedOccurrenceEvidenceDemand`, authored body/cutoff, compiled admission or
  rooted step/completeness, and complete source result. They provide live
  correlation and response-loss recovery; an empty reconstructed map is wrong.
- `ContractsRootFeederWindow.DurableState.pendingByLane` and `rejectedBirths`:
  full typed resource barriers and `RootedDeclaredBirthRejection` containing
  selected/executed inputs, suspended attempt, exact issues and retry count.
- Adapter-retained `ContractsManagedDraftPlan` and
  `ContractsManagedEpochSelectionPlan` maps.

The next Language-owned storage boundary must preserve original
`ClosureInvocationInput`, optional `ClosureProcessRetryInput`, complete-or-
suspended `ClosureAttemptResult`, original typed demand construction, and the
private `ManagedOccurrenceEvidenceDemand.emittedInvocationIdentity`. Public
demand constructors intentionally do not mint that authority. Restored source
selection must reference the same demand instance from the restored attempt;
the birth-rejection authenticator must still prove the original exact input.
Do not substitute a copied public demand, fake body presence, a newly executed
parent, or journal replay. Current terminal-result storage alone is insufficient.

Documents, sessions, occurrences, lineage, rooted views, join candidates,
component/generation/subscription indexes, outbox/checkpoints, receipts and
catch-up plans remain the document-store owner's component. The route index and
active-source Timeline union also need exact retained index roots or a separately
qualified rebuild; this codec does not quietly hydrate every session to derive
them. Disposable coordinators, observers and failure injectors are runtime-local.
Selected-but-unrecorded feeder tickets remain intentionally transient as before.
The legacy non-Contracts `SequentialDrainCoordinator` state is outside this
rooted-profile codec and is rejected, not represented as empty rooted state.

## Qualification

The final source-stable focused gate on 11 September 2026 passed **25/25 tests**,
zero failures/errors/skips, with strict Javadoc in 48 seconds: new control 4,
SDK metadata 7, existing feeder window 8, rooted global driver 4 and rooted
journal cutoff 2. Java 17, offline, no parallel tasks and one worker were used.
The exact immutable tuple is Language `bd09c281`, BEX `ab72af1` and Catalog
`0b68744`, with both downstream artifact bindings rebuilt against that Language.

New controls capture a genuine processed rooted document, preserve exact
registered-empty actor identity/clocks/roots/head/history, round-trip file bytes,
reconstruct independent scheduler objects, retain arbitrary large order integers
and malformed UTF-16, reject physical damage/size/cross-field errors, and prove a
genuine pending source demand is refused without disappearing. The explicit DTO
fairness/frontier control is transport evidence, not a manufactured publication.
The first compile exposed a local lambda capture error; the first executable
gate exposed only the new test's incorrect spelling of `MyOS/MyOS Agent Actor`.
Both were corrected before the final gate; no runtime semantics were changed.

This is a qualified physical subcomponent, **not complete cold SDK execution**.
Unsupported suspension/source shapes must be completed and real cold continuation,
duplicate/lost-response, provider-free restore and publication-fence controls
must pass before making that claim.
