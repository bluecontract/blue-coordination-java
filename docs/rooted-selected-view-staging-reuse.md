# Selected immutable view bytes during session staging

## Problem and measured example

The host recording `myos-worker-phase-diagnostic-external-01.jfr` (SHA-256
`c3e97d75aafbe2e10208a4d2fcda700714065ea825ea6dc74726a1470558de49`)
contains 234 completed `SDK_STAGE` phases totalling 64.743793 seconds; the
reciprocal detach/reattach writer accounts for 16.204 seconds. Its 18-case run
failed three original completion assertions. These are inclusive instrumented
wall times, not CPU attribution: the recording contains no CPU, socket or lock
samples. They justify investigating staging, not claiming a speedup or a fix.

At Coordination `3fd0d5e`, `StoredDocumentIndexes.WorkingSessions.stage` retains
every selected current session, including in-place mutations not represented by
an index put. `DocumentSessionStorage.retain` creates a fresh identity memo and
fully encodes every historical `RootedDocumentView` referenced by that session.
The same live `OpenScope` already associates each successfully decoded immutable
view identity with its exact authenticated storage address. Discarding this
association at stage causes avoidable re-encoding of those unchanged envelopes.

A subsequent combined phase/CPU diagnostic on the same host/library tuple
retained `myos-worker-phase-profile-external-02.jfr` (SHA-256
`da90b10091b00eb1bd78206dbf8ecd57c3ae8d6fd157fad7aec7783e134fdba5`).
Of 288 writer Java samples attributed to `SDK_STAGE`, 177 include
`DocumentSessionStorage.encodeView`, 169 include its retain lambda, and 258
include snapshot verification. These overlapping samples independently locate
serialization/validation in the measured stage; they are not additive wall-time
shares. The bounded recording retained only 07:50:46–07:52:35 UTC, not its first
78 seconds; 287 of the stage samples have truncated 64-frame stacks. No complete
three-scenario CPU attribution or controlled speedup is claimed.

## Solution and authority boundary

`OpenScope.retain(session)` holds the existing scope monitor through retention.
For each exact view object in that scope's identity-to-address map it obtains
the addressed bytes through the existing bounded, defensive, SHA-256-checked
read. Those bytes enter the ordinary per-retain record collection. Unknown
objects, foreign-scope objects, newly produced views and `withPublishedHeads`
derivatives still take the complete ordinary encoder path. Equal heads, epochs,
results or addresses asserted by a caller are never a reuse key.

Only successful `loadView` decoding registers this association. Staging neither
registers new views nor retains new bytes or graphs. The package-private optional
full-encode observer is used only by test construction; production installs no
observer, counter or history and exposes no metric/public API.

Every mutable session field still passes through `storedState` and the unchanged
session encoder: history and readiness, subscriptions, transitions, view-position
aliases, state indexes, layouts, status, epochs and application sequence. Record
formats and bytes are unchanged. Every referenced unique record still counts
toward the same per-retain byte bound, including reused records. All records in
one session are prepared before that session's first immutable prewrite; every
`putIfAbsent`, byte comparison and write acknowledgement remains mandatory.
The enclosing stage's pre-existing multi-session prewrite/publication boundary
is unchanged: a failed stage may leave immutable unreferenced bytes, never a new
published descriptor. Host publication fences are still required.

When the addressed read reports disappearance, unavailability, an oversized
return or corruption while the selected scope is alive, staging fails with a
noncommitting physical read error. It must not silently fall back to re-encoding, repair the
backing record, become valid absence, or become a semantic processing rejection.
This also assumes the existing authenticated-store/SHA-256 address contract;
reuse does not add an alternative authority or promise that a host's immutable
byte-cache hit proves current backing-database availability. Closing a scope clears its existing
identity tables and rejects further retention through it.

## Rationale, rejected alternatives and limits

The selected immutable view is already fully decoded with nested codec,
cross-field and canonical checks. Re-reading its authenticated bytes preserves
that envelope without repeating its serialization. A private per-retain identity
memo still deduplicates repeated aliases; no additional long-lived cache or
memory allowance is introduced. The new read copy is bounded by the same
maximum record size and retires with the per-retain record collection.

Retaining whole mutable sessions, dirty flags, head-based equality, lending
another scope's address map, omitting acknowledgements, or raising bounds would
change the safety argument and are deliberately excluded. Opening/cold decode,
selection, semantic execution, gas, providers, admission, other index families
and global publication topology are unchanged. The standalone `retain` export
path still performs ordinary encoding. This does not remove full history from
session rows or assert constant-time staging.

## Controls and qualification status

New `DocumentSessionViewReuseTest` uses actual rooted SDK producers and counts
the full view encoder branch. It checks zero full encodes for known history with
identical complete record bytes and all acknowledgements; shared admission-source
aliases; mutable status/readiness and an actual new revision; foreign/closed
scope and derived-view fallback; repeated reads after failure; record/scope byte
bounds; defensive byte ownership; and failed early/late acknowledgements.

`StoredDocumentIndexesTest` additionally exercises the actual `WorkingSessions`
stage path, selected-view authenticated reads/writes, in-place status retention,
corruption, failed acknowledgements and the unchanged old selected descriptor.
Its existing replacement/removal, ownership and index controls are preserved.

All changes are an unqualified source candidate. The parent-owned grouped gate
should run the complete owners `DocumentSessionViewReuseTest`,
`DocumentSessionStorageTest`, `StoredDocumentIndexesTest`,
`StoredDocumentStoreTest`, `RootedEngineStorageTest` and
`RootedCoordinationStorageTest`, plus the existing API/shape/Javadoc checks.
No JVM, build, export or test was run while implementing this candidate.
Paired host qualification on a newly sealed tuple remains required; no timeout,
gas allowance, semantic expectation or baseline is changed.

Parent source review expands the grouped gate with the complete retained local
step, publication receipt/reuse, exact-gas and checkpoint-representation owners.
The measured production inventory remains 309 files and changes from 84,338 to
84,370 lines (+32). Only that implementation-size ceiling is updated; public API,
per-file caps and all processing/storage limits remain unchanged.
