# Same-cause diamond peer prefixes (candidate)

This isolated Coordination branch starts from
`20e81185a56a9fb910c9d4c14ff8411d7886bbfe`, with Language `430ee393`.
It does not change the frozen exported candidates, public API, specification,
tariffs, source numbering, or the strictly earlier source-prerequisite rule.

## Confirmed path and private implementation

The retained D03 diagnostic completed the real diamond A→B→C, A→D→C,
then C→A at the original input 300. It preserved A11 and its complete old-D2
proof, independently published D's same-cause prefix D14, and added D14 only
where B's original B2/C0 input had no D primary. The existing terminal publisher
then committed the proved joint result. D03 passed in 43.081 seconds, but its
manual schedule and expansion were not normal SDK scheduling.

The candidate orders only independent receiving prefixes of the same proved
pending join. Canonical document order selects earlier peers first; later
receivers wait before their original LIVE executes while an earlier peer still
has prerequisite work. Already selected one-way dependencies are not peers.
Direct selected-root processing uses the same retained-fence check as drain.
No source timestamp is changed and no later external input becomes eligible.

At the first actual read expansion, an absent peer may be supplied at its own
independently published prefix only after authenticating the exact original
cause, boundary, complete publication chain and matching registered terminal.
The unchanged managed-epoch capturer verifies the plan, receipt, occurrence,
consumer fences and frozen source. Full source and peer proofs are supplied
separately. An existing primary is never replaced; the added peer stays an
immutable witness until Language proves the live join. Publication still uses
the existing original-owner, exact-head and compare-and-swap checks.

For an observer explicitly called before the original receiver has published,
prepublication preparation can calculate a successful rooted result that
discloses the pending cyclic return. A typed private `CohortAdmission` checks
that verified topology and the real still-eligible original receiving input.
The result is either an admitted processing outcome or a receiving-publication
prerequisite, never a completed attempt relabelled as a rejection. It does not invent a source
publication, resource demand, owner set, or terminal decision. Rooted execution
returns blocked with no committed transition; the feeder ticket remains
nonterminal. Ordinary drain can select the real original receiver. The tentative
calculation creates no idempotency receipt, epoch, checkpoint or public outbox.
This check is independent of whether PROCESS requested resources or emitted an
event. It does not serialize ordinary one-way observers or single-interior joins.

The host calculation is speculative until this admission check finishes. When
admitted, its prepared result is reused without a second PROCESS call and is
charged normally, including every actual completed rolling-back or rejected
attempt. A pending prerequisite has no admitted attempt or logical gas charge;
repeating its discovery cannot change the original entry's eventual logical
gas. Physical work is not erased: existing PROCESS counters/timers still record
it, and raw diagnostic `contracts.rootedJoinPreflight.calculations`, `.blocked`,
and the `contracts.rootedJoinPreflight` phase timer identify preparation and
waiting probes. These metrics are not a new public API or a semantic gas tariff.
No result cache, terminal memo, or feeder completion is populated by a wait.

## Qualification and remaining boundary

`RootedDiamondPeerSchedulingTest` uses normal public processing and drain,
compares exact settled history, retained gas and aggregate call gas for the same input under
different call orders, checks a separately identified reversed peer order,
and covers an effect-free diamond. Frozen source receipts, plan coordinates,
event multiplicity, restart, no-progress blocked calls and physical probe metric
deltas remain explicit. `RootedManagedDrainGasReportingTest` remains a required
adjacent control for charged genuine failures, all execution lanes and replay.
The old acquisition probe now requires the production expansion to select the
proved peer, before its independent same-input reconstruction.

No tests or builds have run for this candidate. Parent-owned focused gates and
downstream MyOS qualification are pending. A no-demand reconnect whose dormant
capture has already frozen D2 is a separate unresolved continuation case:
the prepublication wait can prevent premature publication, but this candidate
does not replace that existing primary or claim reconnect convergence. The next
investigation is the eager dormant capture before input freeze, not a late
head substitution or a new Language contract.
