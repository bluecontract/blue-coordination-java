# Selected working-session authority reuse

## Problem and example

A receipt can select the same pinned document repeatedly while reconstructing a
captured history. Each `WorkingSessions` selection previously called strict
`Owner.find` again, decoding the primary lineage and every retained-state reverse
bucket even though the complete selection had already passed in this opened view.
The closed history-reuse18 diagnostic sampled this membership path in 220 of 730
writer execution samples. That attribution identifies redundant work, not a
measured speedup or a successful full-ring run.

## Correction

`WorkingSessions` keeps a private positive fact only after ordinary `Owner.find`
and the owning store's selected-document crosslink check both succeed. The fact
is bound to this working scope's fixed address, lineage and generation indexes,
the exact document key and the selected address. A hit still checks the address
delivered by the current projected row; it returns the same already-owned mutable
session without reinterpreting its retained authority. The fact records authority
from before work, not a certificate for subsequent session mutations.

The registry cannot exceed the existing selected-session owner bound. There is no
absence certificate, eviction policy, global cache, host trust flag, public API,
wire change or new publication authority. New or replaced in-memory rows are
ordinary working values and do not inherit original-row facts. Different pinned
roots require a different working scope. Standalone `Owner.find` remains strict.

At the start of `Opened.stage`, every selected original is strictly rechecked
against the captured raw indexes and owning-store crosslinks before any admission,
closure, index or session prewrite. Replaced and removed originals are included.
Direct `WorkingSessions.stage` performs the same preflight before its own writes;
the complete store therefore also checks again at that inner entry. Every stage
attempt clears all positive facts in `finally`, including outer failures before
session staging, and close clears them. Existing view reauthentication, complete
mutable-session encoding, exact acknowledgements, roots and fences remain intact.

## Why this boundary

The immutable-object contract does not permit ordinary mutation or deletion of
retained addressed objects during an opened view. Under that lifecycle, a completed
library-issued selection fact remains true for its exact pinned roots. Reusing the
fact avoids repeatedly proving the same historical memberships without changing
logical history, equality, processing decisions, gas or index comparison counts.

This deliberately does **not** promise identical physical-corruption detection
timing. After out-of-band deletion or corruption, a warm working-session read may
finish from its captured fact without reading the affected lineage bucket again.
A fresh scope, standalone strict owner read, or the next staging preflight detects
that affected selected authority. Preflight is not a transaction or a concurrent
tamper fence: administrative restore/deletion must retire active owners, and the
host must preserve immutable objects between validation and publication. Untouched,
unselected objects are not scanned by this correction.

## Controls and qualification

Six added tests in the existing `StoredDocumentIndexesTest` and
`StoredDocumentStoreTest` owners cover positive reuse and its bound; strict absence
and standalone reads; cold, malformed, stale, misbound and changed-root selections;
failed membership and failed owning-store checks followed by retry; the explicit
out-of-band corruption boundary; direct and outer preflight before any PUT for
removed/replaced originals; receipt-stage failure before session staging; successful
and failed stage retirement; closed projections; exact retry roots and mutable rows.
Existing complete cold-processor, same-epoch history, corruption, acknowledgement
and retry controls are unchanged. The first combined gate on Language `5404d670`
passed all 95 tests across eleven complete owners on 2026-09-15, including all six
new controls, with zero failures, errors or skips. API/Javadoc/dependency checks
and local artifact verification passed. The overall command failed only because
the production-line accounting had not included this correction's measured +34
lines. Its allowance is updated from 85,358 to the measured 85,392; source-file,
public-API and per-source caps are unchanged. This is not a protocol, document or
gas limit. The follow-up reruns that shape check and local export only; production
and test hashes remain identical, so the passing tests are not rerun or relabelled
as fresh follow-up results. Full MyOS/ring acceptance is still pending.
