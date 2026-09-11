# Acyclic imported-event forwarding diagnostic

Status: the parent-owned acyclic gate passed both tests on unchanged
`cf35914e9cf3a5ea09150c28c58a97b6277db159`: 2/2, 20.036 JUnit seconds,
30 seconds Gradle time. The test source remains byte-identical at SHA-256
`a430088affd230eb19844e41c36c8738b0efafc5306b5e4895791333dc5a296a`.
This is not a proposed runtime repair or proof of the cause of the full ring's
final B counter mismatch. The parent reports nine retained calculation lines
per schedule; their sorted invocation/gas/owner/observed/event data are identical.
Archive: `rooted-successor-language-evidence.GlIOBx/import-forwarding-01-evidence.tar.gz`,
SHA-256 `0e122ec6a190776a06ec2e45b92d273f6dd1bbef5a276e810398e9ff839fc4b5`.

`RootedImportedEventForwardingTest` uses the unchanged maintained Graph Token
template, the current bundled `RootedSdkFixture` profile, one shared Timeline,
and three exact-version operation inputs:

1. At 100, B attaches authored C. Settle only B; the graph is B → C.
2. At 200, A emits `{to: C, next: B}`. Settle only A. Neither B nor C yet has a
   path to A. Both observed counters must remain zero.
3. At 300, C attaches saved-authored A. The graph is B → C → A, with no cycle.
   C imports A's genuine retained history. Compare C-first and B-first selected
   root processing, including bounded canonical `processNext(root)` follow-ups.

The oracle comes from the template's actual `receive` workflow: an addressed
token increments that receiver's `/observed`; if `next != stop`, it emits one
new token addressed to `next`, with `next=stop`. Thus C receives A's retained
token and emits to B; B's own view must then receive C's token. Expected final
independent counts are A0/B1/C1, not copied from an observed runtime output.

RCP-HISTORY-02 exposes recorded source events to the importing consumer without
republishing the source emission. RCP-PROGRESS-02 separates old direct input
newness from resulting internal reactions. RCP-OWN-01/02/04 exclude an incoming
observer from C's publication owners but retain borrowed-child origin-labelled
effects for routing within B's own calculation. Therefore C-only processing
must never write independent B, while B-owned processing may legitimately react
through its local C → A view without publishing C or A.

Every newly retained calculation is compared with the existing fresh
`RootedCalculationFixture.materializedReference` on its exact immutable input.
Only genuine authored values and complete published source receipts provide
exact resources; the fresh runtime has no live provider/session access. The
test compares complete result, event, receipt, checkpoint and companion identities
and actual gas/trace, and requires the publication owner set to be the selected
singleton. It also retains exact source histories/events, records C's forwarding
event identities, checks real READY/current agreement and performs a store restart.

This reduction has no historical copy of B inside A, unlike the full reconnect
cycle. A pass would establish the base forwarding/publication-isolation path,
not disprove a collision between an actual owner B and an immutable historical
witness B in the full scenario. A failure must first be attributed to the exact
retained calculation, actual C forwarding receipt, B occurrence progress and
materialized comparison; no source or handler policy may be changed on this
draft alone. No deadlines, gas rules, production source or published artifacts
are changed.

## Separate four-input historical-witness variation

`RootedHistoricalWitnessForwardingTest` is a new sibling class; neither passing
acyclic method nor its helper code is modified. It adds A → B at 150 between
B → C at 100 and A's token emission at 200. A must actually settle the nested
B → C view before emitting. C then attaches saved-authored A at 300. Thus A's
retained history contains B, while the late C → A attachment eventually closes
the three-node ring. The test tries C-first and B-first receiving roots.

Before the closing input, A's calculations must leave independent B/C unchanged.
During closing history, that assertion is no longer valid once the actual live
SCC expands ownership. The test instead requires original entry owners to remain
owned, allows only the selected singleton or the complete three-node SCC (the
only cycles possible in this graph), and preserves all old numbered receipt
prefixes. It checks the final three active edges and all actual READY heads.
After the first root settles, each actual root receives canonical `processNext`
calls; the test never resubmits a consumed exact input merely to force B work.

The final B+1 oracle is contingent on the demonstrated exact chain: A emitted
one token addressed C; C imported it through its single new occurrence and
emitted one new token addressed B; B has the one active containing route C → B
and no other matching token. The test verifies the two actual source token
records before asserting A0/B1/C1. It does not demand that C's pre-activation
invocation directly writes B, nor that a historical witness B runs as a primary
owner. It requires the complete settled rooted processing to retain the real
B reaction, under RCP-PROGRESS-02, HISTORY-02 and OWN-02/04. Every retained
calculation keeps complete identity/gas comparisons against fresh execution of
the same rooted input, as distinguished below.

### Initial reference-fixture failure and bounded correction

The first parent-owned cycle gate completed 2/2 failures, 18.889 JUnit seconds
and 25 seconds Gradle time. Both prefixes through A's emission passed. Both
methods then failed the output-closure-identity comparison at the first closing
EXTERNAL calculation, before any historical import, forwarding-event assertion
or B-counter assertion. This run is **not evidence of a missing B reaction**.
Archive: `rooted-successor-language-evidence.GlIOBx/witness-forwarding-01-evidence.tar.gz`,
SHA-256 `24a40da4a1b15ec10229b75d395616d4f90fd80d7fc3b0bed7665acaebede409`.

The existing `materializedReference` rebuilds a plain `processClosure` input,
discarding the original private rooted binding. In Language,
`ClosureExecutionSession` then installs neither `RootedOwnershipTracker` nor
`RootedWitnessFrame`, and its current witness set becomes null. Thus the two
calculations do not have the same graph/event-classification context when the
closing input introduces a historical witness. The earlier no-witness matches
do not establish equivalence for that new shape. No production defect follows
from this comparison failure, and the existing helper and passing acyclic
class remain unchanged.

The separate test-only `freshRootedReference` now executes the original exact
`ClosureInvocationInput`, including its Language-owned private binding, in a
fresh runtime populated only with the same explicit authenticated immutable
resources. It does not infer ownership, consult the live host provider, execute
host source selection, or reuse a prior result. This checks fresh-execution
parity of the **same full rooted input**, not an independent unrooted semantic
machine. Every result/event/receipt/checkpoint/companion identity and gas/trace
assertion remains. Before invoking this helper the test also requires the
retained input identity to equal the actual executed result identity: a distinct
retry would require its exact retained retry envelope, not guessed resolutions.
Any remaining output mismatch prints compact document/occurrence comparisons;
the pre-closing diagnostic no longer dumps complete nested document bodies.
The corrected reference then compiled and passed every reached comparison in
the parent-owned cycle02 gate: C-first passed, while B-first reached a separate
publication rejection. The run completed 2 tests with 1 failure, 30.247 JUnit
seconds. Archive: `rooted-successor-language-evidence.GlIOBx/witness-forwarding-02-evidence.tar.gz`,
SHA-256 `92c24e7651b102343e4637306e9448515692cc63c3e86d0f70038c8199c9c472`.

### Cycle02 publication conflict, not forwarding evidence

C-first imports the terminal A4-to-A5 receipt, expands actual ownership to
A/B/C, emits C's one token to B, and ends at A0/B1/C1. B-first imports A0 through
A4 into its local C view, then rejects the next publication: local C is at
epoch 6, while independent C is still at its original epoch 0. The owner check
refuses to replace that independently published head with a root-local result.
There is no observed concurrent writer. RCP-OWN-03 requires the exact causal
view and publication fence for newly co-owned sources and permits a retained
prerequisite/conflict; it does not guarantee that repeatedly selecting B alone
can settle this join. Consequently this rejection is not evidence of a missing
B event, and it does not justify weakening a fence or a general confluence rule.

The original B-first test name and final assertions remain unchanged. A new
catch-and-rethrow diagnostic reports its typed Coordination error code, original
cause class, pre/post independent heads and receipt histories, the actual
selected local work/input/fences, subsequent root selection, explicit C
eligibility, and canonical next selection. It asserts no independently
published head/history changed on the failed call, then rethrows the original
failure. Diagnostic selection reads execute no processing or recovery. The
question whether that conflict can be resolved through existing source-owned
processing remains open; no retry or source reselection is added to the test.

### Separate nonterminal-source discriminator (cycle03 executed)

`nonterminalSourceEventStillRequiresTheForwardingReaction` adds one supported
`A.touch` input at 250 to the C-first sequence, before C attaches A at 300.
The unchanged template increments only `/touches` and emits no event. The test
requires one genuine later numbered A receipt, exactly one original A token,
unchanged independent B/C, and zero observed counts before the attachment.
Thus the event-bearing A receipt is below the frozen selected source endpoint,
unlike the passing four-input terminal-receipt case. No epoch is relabelled.

Every reached calculation still compares complete result identities and
gas/trace against its exact fresh rooted input. Actual owner sets and source
tokens are recorded before the final A0/B1/C1 assertion. That nonterminal count
is an investigative hypothesis, not a newly established semantic rule: the
test must first establish the C forwarding occurrence and the actual receiving
ownership/witness state. It neither assumes that a historical witness executes
as an owner nor claims arbitrary root-schedule confluence. Diagnostics now call
`pendingHistoricalEpoch` the applied cursor, not an endpoint; the selected input
source epoch and the pre-attachment frozen source endpoint are reported
separately. The parent-owned cycle03 run compiled and executed all three
methods: terminal C-first passes; nonterminal C-first fails only at the B count;
B-first retains the publication conflict. Totals3 tests,1 pass/2 failures,
47.118 JUnit seconds. Nonterminal actual counts are A0/B0/C1, with the exact
new C token present, all heads READY, all edges active and every reached
fresh-rooted identity/gas comparison passing. B's actual pre-join selection is
the original LIVE300 (`HgWUG5gsFmWCS84v3C7KE995QVACy6P5oNM8J4h8fTE5`);
after the co-owned join all its work lanes are empty, without B entering its
own LIVE300 calculation. This is an obligation-discharge concern, not authority
to deliver into an immutable witness. In B-first, the rejected call preserves
all heads/history, explicit C is blocked and canonical selection returns the
same B local work. No fence is relaxed.

Archive `rooted-successor-language-evidence.GlIOBx/witness-forwarding-03-evidence.tar.gz`,
SHA-256 `5121a93c8179bcd8a617b774b57a9f655f02bdfac326b19c35d7e06103766a5c`.
The published RC9 comparison is separate and not yet qualified. Changing the
oracle or runtime requires establishing the prescribed discharge rule.

This four-input case is diagnostic, not a copy of the full fourteen-input ring.
In particular it has no duplicate occurrence and does not establish the full
scenario's already-advanced B Timeline checkpoint. A's emission at 200 is also
its last source step here; the full ring imports that old event before a later
A frontier. Activation may therefore precede forwarding in this mini, whereas
the long case may still have a historical B witness at emission. Compact
managed-cause diagnostics retain the imported epoch, applied cursor and
successor presence to distinguish these cases. The two explicit scheduling
examples are not a claim of general confluence for arbitrary root choices.
If it passes, it cannot
exclude a witness/owner collision or old-direct-input/new-reaction distinction
that requires those additional prior conditions. No production change is
justified until the shorter or original exact calculation isolates a real loss.
