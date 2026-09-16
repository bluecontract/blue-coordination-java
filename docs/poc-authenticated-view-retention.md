# Freshly authenticated view dependencies during session retention

## Problem and example

A restored Order session can reference an already retained immutable Agreement view.
`DocumentSessionStorage.OpenScope.retain` freshly reads that view, checks its record
limit and SHA-256 address, then used to submit those exact bytes to `putIfAbsent`
again. Two sessions retaining the same newly produced immutable view in one explicit
retention stage similarly wrote it twice, although the second session first read and
authenticated the first successful write. The closed diagnostic found rooted-view
duplicates among the dominant duplicate byte families; it does not establish the
speedup of this correction.

## Correction

Keep the freshly authenticated dependency addresses in a private set for **one
`retain` call**. Their exact bytes still enter the ordinary staged-record map,
deduplication/conflict checks and complete scope-byte accounting. Only their
redundant PUT is omitted after all records have serialized successfully.

The current mutable session is always serialized completely and written with an
exact acknowledgement, even when its address is unchanged. New views and all other
actual writes keep their original acknowledgements. The standalone
`retainView` and ordinary unscoped session retention paths are unchanged.

The only eligible evidence comes from this scope's decoded view identities or an
identity successfully retained earlier in the current explicit retention stage.
Each use requires a fresh read through the immutable-object port and its exact
address check; this does not force a SQL query when the host safely caches that
port. No cross-attempt presence cache, mutable
session identity memo, new format, public API or semantic/publication authority is
introduced. A failed retention does not register newly produced identities for
later reuse.

## Why this boundary

The store already retains immutable dependencies behind published references and
untouched index subtrees. Once the exact existing dependency was authenticated,
repeating PUT is not required to establish its presence. This relies on the same
immutable-object lifecycle: the host does not mutate or remove retained objects
between selection and publication. It is not permission to tolerate missing or
corrupt bytes on the next selected read. Root, session and publication fences are
unchanged. Administrative physical restore must retire the relevant owners/store.

This deliberately changes the former `StoredDocumentIndexesTest` policy assertion
that every known view receives another PUT. It does **not** weaken its corruption,
old-root, mutable-row or failed actual-write assertions. Storage corruption after
the authenticated read is outside that immutable lifecycle; this path no longer
performs a redundant write as an additional observation of such corruption.

## Controls and qualification

The two existing working-session controls now require one first write for a shared
new view, and no repeated write for a freshly authenticated known view. They retain
distinct session owners, status-only changes, cold restoration, old-root preservation
and actual failed-write/retry checks.

Four added `DocumentSessionStorageTest` controls cover:

- Fresh read on each retention and an acknowledged complete mutable session.
- Missing, corrupt and unavailable known views failing before any session prewrite.
- Known view bytes still counting towards scope limits, exact-boundary success and
  selected physical record limits.
- Bad first acknowledgement, failed retry, mandatory successful first retention,
  stage-local reuse and evidence not escaping to the next stage.

These controls are prepared, not yet run. No performance or full-ring acceptance
claim follows from this source change.
