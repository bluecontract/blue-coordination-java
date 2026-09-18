# Batched exact work observations (POC follow-up)

## Problem and measured example

The durable MyOS projection of one retained graph step requested 18 work IDs.
Packet memoization reduced those requests to eight distinct SDK audits, but six
misses in the direct work index independently invoked the rooted fallback scan.
The eight audits cost 3.896 seconds in the measured cost35 replay. This is not a
reason to change work ordering or assume that a non-selected item is absent.

## Change

`CoordinationEngine.auditManagedEpochApplicationWorks(List<String>)` and its
`AdvancedCoordination` wrapper return an immutable map with one explicit
`Optional` for every distinct requested identity. The existing individual API
remains unchanged. Default implementations can delegate to it.

The rooted engine reads the direct work index first, preserving its precedence.
If any requested identities are absent there, one synchronized call performs
one rooted fallback scan and selects matching local work exactly as the old
individual query did. Unknown IDs remain explicitly empty. This does not use
the globally next work item as a substitute for all requested identities.

There is no retained cross-call selection cache. A later call observes document,
journal and provider changes normally. No execution, claim, admission, gas or
scheduling-turn advancement is introduced by batching.

## Host use and verification

MyOS collects pending work-ID questions during projection capture and flushes
them together before detaching the packet. Publication still verifies that all
required keys were captured; uncaptured is an error, not absence.

The real rooted local-history fixture checks individual/batch equality,
duplicates, absent IDs, immutable results, unchanged heads/history, and a new
batch after the old work is consumed and the engine restored. Host controls
check one batch for distinct pending IDs and rejection of missing captured keys.
Full acceptance and elapsed-time conclusions must be recorded separately after
the unchanged long graph and regression runs; this document is not a PASS claim.

Rejected shortcut: caching by document ID or work ID across arbitrary engine
calls. That would require proving the complete evidence basis unchanged,
including newly available provider data, and is unnecessary for this reduction.
