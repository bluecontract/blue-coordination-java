# Imported receipt events with a deferred terminal tail

This test-only branch starts at unchanged Coordination
`ef3965bd87b39f3dcb047aaaf0e6a5db3a285137`. Production, build limits and public
APIs are byte-identical to that base. It awaits the separately qualified
Language correction and a coherent immutable development tuple; no tests have
yet run here.

## Proven diagnosis, not a new history policy

The original seven ring/chord inputs fail directly in the SDK at C8 publication
for A's second saved-C occurrence. A temporary Coordination accommodation
allowed that publication but failed later when the C8 terminal representation
could not activate against the newly numbered C9. Neither change was qualified
for shipping. Diagnostic04 establishes why C9 was produced:

- C has no local work and no new source events.
- Work 0 rewrites A's historical reference. Work 1 delivers the exact original
  C8 receipt event to A, matched by source, occurrence identity/ordinal and event
  BlueId.
- Both C changes consist solely of `/peers/a/blueId` reencoding at those work
  boundaries, yet the resulting source epoch changes from 8 to 9.
- The cause carries an authenticated future representation successor and
  therefore deliberately has not completed activation.

Language's existing imported-receipt source-epoch exception is gated by
completed activation. The maintained
`FullLifecycleAdmissionTest.managedRevisionEventAcceptsAuthenticatedSameEpochComponentRebind`
already establishes the reference-only exception after activation. A successor
carrier defers activation while still importing the same exact receipt events.
The separately proposed private Language correction must preserve that
classification without exempting local source changes, new source events or
unexplained body changes. It must not remove the terminal epoch-equality guard.

The complete diagnostic archive is
`/Users/kamil/Documents/Projects/Blue/rooted-focused-followup-evidence.jtpYIX/library-duplicate-occurrence-diagnostic-04-complete.tar.gz`,
SHA-256 `2d8fe81214a4421ee48723862871935e80b1d69bfcf64a269c4e1a60ad094800`.
The old C9 is real stored evidence of the failing implementation, not a normative
requirement that a corrected execution fabricate that same extra revision. No
existing receipt is deleted or rewritten: qualification starts a fresh runtime.

## Preserved acceptance scope

The SDK owner preserves all three original authored IDs, the first seven exact
Timeline entry IDs/timestamps/predecessors and their unchanged public business
assertions. It follows all fourteen Python ring/chord inputs: duplicate C event
delivery, three detaches, a disconnected emit, saved-A reconnect with exactly
one new C forwarding event, and the final one-recipient emit. Exact topology,
all-three READY/current-body equality, original receipt prefixes, source events,
transport-only no-repeat/gas checks and final resident store restart remain.

Only assertions depending on the unqualified Coordination accommodation were
removed: its private frozen-cursor probe and the requirement that erroneous C9
exist. A new test-only probe authenticates the actual published C8 work through
its retained application/publication. It requires the pending future cursor,
exact original receipt event, both reference-only boundaries, no C-local work or
source event, unchanged C8 epoch and the real A-local counter/epoch advance.
The original C8 receipt must survive later genuine inputs and restart. Input
eight may legitimately create the next source revision; there is no global
prohibition on C9 or suppression of real later work.

The original failing work identity
`sha256:ad52e5167de64e398379661886855ae77046500944b92bc0be983a1d6b19339c`
is diagnostic evidence from the old release binding, not a new-candidate golden.
The probe selects exactly one seventh-input numbered C8 carrier for A's
`/peers/c2`, with its authenticated successor and A49 consumer fence. Work and
Contracts receipt identities are checked against the actual invocation; the
Contracts cursor uses the source revision receipt identity, while retained
Coordination history uses the enclosing managed receipt identity. All authored
and external Timeline entry identity pins remain unchanged.

The old dirty diagnostic donor is preserved separately at
`rooted-duplicate-occurrence-history-fix`; its +85 production lines and source
shape increase are not copied here. No root-local/managed verifier changes,
new receipt authority, source-head rewind, gas tariff changes or increased HTTP
deadlines are part of this candidate. The later-input finite selected-turn
bound remains 256 because reconnect can require 94 retained publications;
the original seven retain 64. This is not a semantic gas limit.

Required next gate: this complete SDK owner against original ef3965 production
plus the new sealed Language/BEX/Catalog binding, then adjacent historical/local
controls and the original full Python HTTP owner. A green focused result would
not by itself replace full MyOS product and HTTP acceptance.
