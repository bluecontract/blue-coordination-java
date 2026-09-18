# Rooted Timeline journal: physical storage boundary

## Problem and example

The current rooted engine owns an in-memory Timeline journal. An exact external
entry has a canonical predecessor, a strictly increasing microsecond timestamp
within its Timeline, canonical external order, and one append position. Loading
only its JSON body into a fresh engine does not restore those indexes, duplicate
identity, rollback revision, or historical completeness state.

For example, reopening after A@100, B@50, A@200 must retain that append order but
select B@50 first by external order. Appending A@150 or A with a different
predecessor must still fail. A previously accepted exact entry remains a
duplicate, not another append. A physical read failure cannot mean empty history.

## Small physical seam, unchanged current decisions

`TimelineJournalStore` exposes a closeable pinned read view, addressed entry and
index reads, and an atomic expected-state mutation. `DefaultTimelineJournal`
performs the existing append, duplicate, predecessor, timestamp, cutoff,
availability, and rollback decisions over that view. Selected rows are checked
against their exact event, request and all index coordinates. Physical faults,
inconsistent rows and stale ownership throw `TimelineJournalStorageException`,
a noncommitting exception. Read views are closed before a physical mutation.

The existing `InMemoryTimelineJournal` type remains the default thin facade, so
rooted coordinators and SDK selection code are unchanged. Its backing store
holds the same indexes, without copying them when opening a read view.

Two current rooted details intentionally differ from the old donor:

* Row validation uses the current entry factory's profile: rooted source order
  binds the canonical Timeline BlueId; legacy order binds the Timeline name.
  It never changes an existing order tuple during restoration.
* `BEGINNING` admission retains the current provider-availability and empty
  authoritative-journal checks, evaluated within one pinned view. Semantic
  unavailable/invalid provider dispositions are distinct from storage failures.

The engine gains only a package-private journal test factory. It does not
restore documents, clocks, scheduler state, resolver evidence, or whole objects.
The original factory and rooted capture/publication rules are not replaced.
The two existing engine error translators pass through journal storage faults;
the first focused run proved that otherwise a physical read failure becomes a
semantic Coordination error. All other error translation remains unchanged.

## Provenance and qualification

The narrowly selected donor is `current-runtime-durable-coordination` at
`a2577c7`: TimelineJournalStore/StorageException, TimelineJournal,
DefaultTimelineJournal, InMemoryTimelineJournalStore/facade, and the
StoredTimelineJournalTest, FileTimelineJournalStore, JournalFixtureBodyCodec and
its tests. No persistent map stack, old engine implementation, SDK builder,
backend mode or provider recovery is ported.

The file adapter is a **test fixture**: it atomically replaces a manifest and
retains immutable detached row bodies. It materializes lightweight metadata and
uses linear metadata lookup; it is not a scalable production database adapter.
Its body codec rejects construction forms it cannot preserve and is not the
production full resolver-snapshot format. Rooted order coordinates are stored
explicitly rather than reconstructed using legacy Timeline names.

Focused qualification passed: 41 tests across six owners, zero failures/errors/
skips, plus Javadoc (native run 61677, 1m15s). The 18 component controls include
unchanged historical cursor tests, stored journal tests and rooted canonical-
order/BEGINNING reopen controls; the 23 SDK controls are current
RootedJournalCutoff, RootedAdmissionBasis and RootedSlicedSelection owners.
Source/docs SHA-256 was unchanged during the final run:
`27c7054869ddfe7df632fb24cde13c56a30dfa4ed3fdb6ea21c25905595ed38e`.
The initial 40/41 result retained the physical-exception translation regression;
an intervening Gradle option-placement failure executed no tests. Both are
archived separately from the final green evidence. This paragraph was updated
after the gate; production and test sources were not changed.

Dependencies remain the immutable e2ac-base
upstream tuple (Language 7ec0fdaa, BEX ab72af14, Catalog 0b68744b); no export or
published pin is changed.

## Boundaries and rejected shortcuts

Point reads load only the selected row and bounded adjacent/index evidence;
whole-history audit and historical scanning remain proportional to requested
history. An expected-state fence serializes this journal's canonical admission
order. It is **not** a whole-realm checkpoint CAS or a claim that independent
root publications are integrated. A fresh owner can reopen journal state,
but journal rows alone do not restore a whole-object/provider graph or an SDK.
The host must bind a journal to its immutable runtime/configuration profile;
the physical State does not replace that binding. Opening a journal does not
scan all old rows, and wrong-profile validation occurs when a row is selected.

Rejected shortcuts include treating missing transport evidence as completeness,
replaying appends to rebuild indexes, replacing rooted Timeline-BlueId order with
names, and copying an old complete engine. Full root/runtime restart and joined
host command publication remain separate work. This milestone is not fresh-SDK
end-to-end persistence or a production concurrency/scaling qualification.
