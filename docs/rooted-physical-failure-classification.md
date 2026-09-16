# Rooted exact-provider failure classification

## Problem and concrete evidence

A real SDK attachment of an authored child invokes the configured exact-node
provider while compiling source-owned admission evidence. If that provider throws
`CoordinationObjectStorageException`, two generic exception wrappers previously
converted the same physical outage to `CoordinationException`: the static embedded
admission provider wrapper, then the engine dispatch translator. This is not
semantic missing content or invalid authored evidence.

The original focused test was red against `e6a4e32`: one test failed on
2026-09-11 at 02:39 UTC because the original `NoncommittingExecutionException`
was not propagated. The retained red archive is
`rooted-physical-failure-evidence.Yxa1pg/red-provider-outage.tar.gz`, SHA-256
`f7fcea30627f28e00fdde9539acdaf12d1c75516e25ad22a845b3a3ffdbd5ee9`.

## Narrow correction

Both wrappers now return the original `NoncommittingExecutionException` unchanged.
The dispatch rule includes the already-protected Timeline journal storage subtype.
Ordinary missing evidence, invalid content, and all semantic processing rules are
unchanged. There is no new provider or storage injection API.

The genuine provider test submits exactly one Timeline entry, observes the same
outage instance, and verifies unchanged head and receipt history. After restoring
provider availability it processes the same entry, executes the independently
selected source admission, and resumes the parent. Exact entry/head/closure and
full receipt identities, gas counters, and document step order match a fresh
no-outage run, including the normal post-attachment checkpoint. A final idle retry
has zero gas and committed transitions and leaves that complete history unchanged.

## Recovery integration boundary

This proves reachable provider failure handling, not a complete cold engine factory.
`DefaultCoordinationEngine.requireDocument` still wraps generic runtime failures
from the currently resident store as `DOCUMENT_NOT_FOUND`. The future selected
external-session adapter must preserve noncommitting failures there and add an
actual missing/corrupt/outage session-row test before claiming cold recovery.
There is no fake public injection seam solely to exercise an uninstalled adapter.

The sibling legacy `translateStartFailure` route is outside this rooted operation
control. `ManagedOccurrenceResolver`'s generic draft-constructor wrapper does not
currently perform an external provider lookup; it was not changed speculatively.

## Qualification

The focused gate uses the immutable Language `bd09c281` event/result storage
artifact with unchanged BEX `ab72af14` and Catalog `0b68744b`, rebuilt against that
Language artifact. It selects the new physical-failure control, rooted source
prerequisite and observation owners, and stored Timeline journal controls, plus
strict Javadocs. The final gate passed 31/31 tests (13 journal, 11 prerequisite,
6 observation, 1 outage), with zero failures, errors, or skips, plus Javadocs in
33 seconds. XML timestamps are 2026-09-11 02:53:10–02:53:21 UTC. These are focused
controls, not a full Coordination or cold-application acceptance claim.
