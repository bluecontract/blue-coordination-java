# Diagnostic traces for semantic equivalence and source reuse

> **Design baseline:** 15.12 · **Status:** diagnostic expectations; implementation evidence tracked separately
> [Boundary](18-independent-lineage-processing.md) · [Edge contracts](19-semantic-edge-contracts.md) · [Scenarios](09-scenarios.md)

These are hand-derived diagnostic expectations, not an executed-fixture report.
[22 — Selected processing kernel](22-processing-kernel.md) has highest precedence. Sections A–C
retain incoming-link anchors but state the selected sequential-placement, staged-history and
observation-program rules below. References to baseline gaps explain the Phase1 repairs, not a
competing target. [24](24-phase-1-library-summary.md) and the
[readiness record](implementation/phase-1-2-readiness.md) describe actual implementation/verification.
Do not count each selected outcome below as a passing test without its corresponding run evidence.

## A. One receipt, two placements: all read-pins first

**Given:** Parent has `/left` and `/right`, both observing Source at counter0. One committed Source
receipt changes counter to1 and emits E. Parent's E handler through `/left` records `/right/counter`.
There is no user-authored mutation of either placement in this control.

**Selected:** update placements in canonical authored occurrence order. /left's reference-update
handler runs to completion while /right remains0; after /right also advances, the later E handler
reads1. The heading retains an old anchor, not an all-pins barrier. Complete membership and separate
cursors preserve the entire one-origin reaction under its owned consumer scope.

**Required diagnostic controls:**

1. Read `/right/counter` in the `/left` reference-update handler: expect0; its later E handler expects1.
2. Let the first handler remove/retarget `/right`, or replace `/right`'s update handler. Specify exact
   before/after evidence, frozen dispatch authority, permitted successor and terminal outcome.
3. Verify that an already applied reference patch is never applied again merely to dispatch its
   retained notification. Do not silently skip valid frozen work because a later mutation occurred.
4. Keep the imported E1/E2 retirement control: E1 removing a path does not universally cancel valid
   E2 work already admitted under the actual Contracts rules. Removal before batch admission is a
   different boundary; do not turn an actual target failure into a fabricated success.

All required occurrence verification, update, handler, queue and finalization charges remain logical
work. One reaction origin at one causal position owns the complete reaction for its consumer scope,
including eligible direct/transitive paths and upstream projections. A page, cache hit, worker order
or newly invented flat charge cannot split it or choose the result. Implement and verify the selected
constructors and gas mapping against these hand-derived traces.

The ordinary DAG fan-in control also remains: Root embeds Source directly and through Parent. Root
can receive distinct direct/ancestor observations and Parent's genuinely new F. Preserve the logical
FIFO and complete prerequisites; an arbitrary ordering of whole source-result batches is not proof.

## B. Creating a placement while consuming a receipt

**Given:** the frozen group contains `/a`. Its E handler adds `/b` to the same Source and selects
history containing the current source receipt r1. `/b` did not exist at group selection.

**Selected trace:** /b does not join the already frozen event. Its canonical initialization/selected
initial view and required synchronous lifecycle/update work are available before a permitted creator
read. Historical source epochs enter a separately ordered attachment lane; they are not collapsed
into that creator epoch. An authored-from-origin initial read sees initialization, not a caught-up
parent shadow. FROM_NOW instead installs the exact view selected at its declared logical boundary.

Missing the required initial view holds the creator; missing a later receipt body holds the later
lane. The creator never waits for work that requires its own commit. Source history selected for /b
does not replay to previously consumed /a merely because /b appeared. Pending selected history and
live delivery have the kernel's no-gap handoff, not simultaneous duplicate admission.

The committed attachment owns its exact fixed-cut lane; its identity derives from semantic creation,
not h or a scan page. Creator failure or create-then-retire publishes no orphan live successor.
Repeated actual creation/activation work is metered, not suppressed by event-value or document-level
deduplication. Verify these explicit managed-API rules, including their changes from current code.

## C. Nested relay is not a new emission

**Given:** Root embeds Parent, which embeds Source. Source emits E. Parent handles E and emits F.

**Selected:** compose the authenticated upstream observation programs sharing the same reaction
origin into Root's complete reaction. Execute their recorded update/enqueue/delivery boundaries with
Root's own continuations/FIFO. A final-Parent relay batch or always-Parent-after read is insufficient.

**Established ordinary control:** Source's E can reach Parent and Root without Parent re-emitting
it. Root receives the original event at the composed source path. If Parent emits F while observing
E, F is a new occurrence and cannot jump the remaining deliveries of E. A terminating Parent can
stop its own reaction while Root still observes the frozen E. An earlier reference-update G and an
already queued E2 retain their actual FIFO positions; there is no universal 'relays first' phase.

**Historical managed gap (repaired in Phase1):** `activeContainingOccurrences` selected only direct bindings; Parent's own
receipt does not contain original E merely because Parent observed it. The Root-only profile does
not justify losing Root's authored observation after storage factorization. Require the paired
three-level trace under [21 §1](21-semantic-equivalence-and-source-reuse.md#1-one-logical-contracts-semantics).

The original occurrence owns finite frozen routing authority without fabricated Parent emissions.
Observation programs authenticate exact views, enqueue/delivery addresses, paths and completion
ownership. In cycles, routes are vertex-simple per occurrence: a DocumentId does not repeat, including
the emitter; distinct alias routes remain distinct. New handler emissions start new routes inside
one owned workflow gas/rollback scope. Transport alone cannot create an extra echo loop.

Final-source-after alone is also insufficient: Source Triggered E1→x1 and E2→x2 lets Parent read
[1,2]; final2 plus a flat batch gives [2,2]. Likewise 0→1→0 can have observable intermediate updates.
Retain these controls from [21 §3](21-semantic-equivalence-and-source-reuse.md#3-a-final-receipt-is-not-complete-observation-evidence),
not a universal snapshot at every emission. An operation's rollback publishes no tentative effects;
physical reconstruction may not change event identities, history, gas or the defined commit boundary.

**Separate-input control:** Timeline input I1 produces Source epoch n+1; Parent's corresponding
complete reaction sets y=1 and emits F1. Timeline input I2 produces Source epoch n+2; Parent's later
reaction sets y=2 and emits F2. Root sees the corresponding historical Parent views: F1 reads 1,
F2 reads 2. A physically newer Parent head cannot replace the earlier authorized view.

**One-input nested FIFO control:** one operation has already queued internal E1 and E2. Parent
handles E1 by setting y=1/emitting F1, and E2 by setting y=2/emitting F2. The queue is E1,E2,F1,F2,
not E1,F1,E2,F2. Root's original-E1/E2 reads after Parent's reactions are 1/2; its F1/F2 document reads
are 2/2. F1's immutable payload may still contain 1. No extra epoch is created merely for each internal
event. Retained evidence must preserve both controls rather than merging their operation boundaries.

<a id="d-initialization-policy-and-publication-birth-selection-remains-open"></a>

## D. Canonical source origin and publication

**Selected BIRTH rule.** The historical heading/anchor is retained for incoming links; the source
origin decision is now closed. Authored X has one canonical initialization and FULL_HISTORY source
history under the frozen semantic environment, independently of the first Order and physical
materialization. Initialization identity binds its kind, X's authored DocumentId/exact value and
the frozen initialization policy/environment, or the canonical complete authored scope for a real
coupled initialization cycle. It excludes introducing-parent identity and time.

**Explicit owning-library change:** current FROM_NOW/BIRTH_AT_ATTACHMENT can select a new source
history. The proposed integration instead uses FROM_NOW, FROM_FRONTIER and FULL_HISTORY only for
the observer's attachment selection. FROM_NOW carries an accepted logical cut, not a worker clock.
This is not an assertion that the current enum already means that. Source history is never truncated
or reborn by an observer choice; the DocumentId is unchanged.

**Required concrete trace:** X starts at0 and has an applicable E15 changing it to5.

1. Order A attaches atT10; Order B attaches atT20. Both name the same source X.
2. X's canonical history contains E15 in either worker order, including when X is first physically
   materialized afterT20. A later materialization must reconstruct the same history.
3. FULL_HISTORY attachment imports the eligible prior observations. FROM_NOW@T20 selects the
   corresponding source view and excludes earlier event replay for that occurrence; it does not
   remove E15 from X or change A's selection. Ordinary reference-update/lifecycle reactions still
   follow the selected operation rules, so no shadow-field assignment is invented.
4. Canonical source initialization uses its own fixed gas policy and retains its own gas. Each Order
   pays its own import/reference/reaction gas. Source80 plus parent20 under separate limits90 succeeds
   in cold and warm cases; this intentionally differs from the old combined100/limit90 invocation.
   Real cyclic workflows cannot split their shared meter this way.

**Publication and failure trace:** preparation yields immutable non-authoritative initialization/
source-history candidates. A successful authorized introducing operation promotes its required
canonical candidates atomically with its own result and delivery/registration basis. Source batches,
identities and charges remain source-owned; promotion is idempotent by canonical operation identity,
not a new semantic operation owned by whichever parent wins a SQL race.

If A fails before that publication, it publishes no new source authority, source event or source
initialization charge. A's own failure accounting remains. B can later promote the same canonical
X and history, including E15. If X had already been independently admitted, A's failure cannot erase
its history or charges. Standalone admission authorizes the same source origin without a parent.
Compatible concurrent promotions reuse the same prefix; inconsistent source origin/history evidence
is rejected instead of selecting a different source birth. An unknown or lost authority is not
permission for another initialization. Recomputed candidates/cache misses add physical work only.

This rule deliberately changes current NEW_AUTHORED-in-parent ownership and source-history selection.
The reference must compare the newly selected source and observer operations and their exact gas,
IDs, histories and outcomes, not normalize away differences while claiming unchanged old closure
semantics. Constructor implementation and conformance tests belong to the separately approved
library phase; no released BlueIds or arbitrary new gas weights are invented here.

## E. Failed r1, pending r2, later detach

**Given:** r1@T10 fails during managed import; r2@T20 is already due; D@T30 wants to detach without
reading source content. The source and a healthy sibling have committed normally.

**Selected trace:** use independently owned source/consumer operations, with source values r0=0,
r1=1, r2=2. Keep the consumer's actual rollback state and successful view r0 after r1's terminal gas
failure. Record that exact operation/delivery as terminally handled; do not record an epoch or
successful import for it. Source and healthy siblings remain committed.

1. Select r2 in canonical order, authenticating original source continuity r0→r1→r2 and the exact
   failed-r1 disposition. Do not require a fictional successful consumer r1 view.
2. Within r2's one consumer invocation, align the actual containing reference0 to r2's exact
   source-before1 through an ordinary Document Update. Run its synchronous continuations and keep
   their normal queue/lifecycle effects. Then replay r2's authenticated observable transition
   sequence1→2. This replaces r15.8's direct0→2 control; alignment is neither silent nor a separate
   epoch/commit. It consumes the same r2 gas budget as the subsequent replay.
3. Keep the immutable source receipt and original r2 events unchanged. Do not fabricate a source
   receipt0→2, re-execute the source, replay r1's source events or resume r1's failed private queue.
   Alignment may cause new consumer update reactions, even if the same handler ran in failed r1.
4. Success publishes one complete consumer result and its actual successful view/epoch. Gas failure
   during alignment or replay rolls back **all** r2 effects to actual view0 and records r2's terminal
   disposition. No tentative alignment view becomes authoritative.
5. D@T30 runs when due after earlier operations have terminal outcomes. It sees the actual last
   successful state/view, retires the exact activation and affects future eligibility under the
   normal lifecycle rules. D cannot skip unresolved r2 or retroactively erase due work.

**Boundary controls:**

- **Event before update:** r2 first emits/delivers E while source remains1, then later changes to2.
  The consumer aligns0→1 before r2 replay; E reads1, not0 or final2. Preserve FIFO ordering of any
  emissions from alignment and r2's actual admission points; do not reset or separately drain a
  private queue merely because the retained evidence arrived in another page.
- **No business change:** r2's before/after business value is1. Alignment0→1 is still a real consumer
  update; only r2's original source events, if any, are replayed. Exact checkpoint/receipt rules still
  apply; equal business fields alone do not imply equal exact BlueIds.
- **Alignment fails again:** an update to1 starts the authored loop. r2 fails during alignment,
  even if its final value2 would not start that loop. This is intentional, deterministic execution
  of the selected rule, not a promise that a later receipt repairs a failed consumer automatically.
  Later canonically due repair/detach can still run after the preceding terminal outcomes.
- **Read between receipts:** after failed r1@T10, a local external read@T15 precedes r2@T20. Its exact
  embedded reference still reads0; the independent source history already contains1. Do not import
  future r2, silently refresh the reference, or wait for a successful consumer r1 epoch that does not
  exist. Test this separately from the event-maintained local shadow field.
- **Normal successful history:** if r1 succeeded, r2 starts with the matching source-before reference
  and adds no alignment update. Keep one proper operation per chronological step, including the
  required intra-step observations; pages never coalesce those steps.
- **Page/range independence:** run the same history live and from cold historical catch-up, with
  page sizes1,2 and a larger range, plus restart after r1's failure. Require the same outcomes, source
  event identities, consumer histories and gas. Never stop/discard a range merely because one step
  failed, nor force every source receipt ahead of a canonically earlier local input.

If r2 is waiting for source history, content or provider completeness, it is not terminally handled:
D does not overtake it. An attachment may own a required ordered catch-up prefix before a later
dependent input; that semantic prefix, not a physical import range or worker page, determines what
must finish first. Finishing means each required operation has its specified terminal outcome, not
that every operation succeeded. Later successful steps may have business effects despite an earlier
failure. A workflow requiring stronger all-or-nothing business behavior must express that dependency
within its proper logical operation/state; the host cannot infer it from a batch boundary.

The user-authored repair is a new input, not a retry of the same gas-exhausting invocation. A real
coupled workflow still rolls back its complete shared-gas scope. The selected consuming failure is
`GAS_LIMIT_EXCEEDED` or recognized deterministic semantic `RUNTIME_FATAL`; other statuses retain their
operation-kind laws, without general skip-on-error.

This is an explicit owning-library change implemented in Phase1, with its scoped verification
recorded separately from these hand-derived traces. The explicit gap
validation and alignment must not become a host bypass of the ordinary beforeBlueId guard.

The existing consumed external failure → detach → new input control is separate and remains supported.
Neither a historical replay of that external input nor a database rebuild changes its disposition.

## F. Representation, scaling and acceptance

A verified same-epoch cyclic representation companion belongs to its actual triggering operation's
complete result/delta. It binds all required members, exact before/after representations, unchanged
business epochs/source-receipt positions and owning finalization evidence. Publish the required
representation scope atomically. It creates no new business application/epoch/receipt, synthetic
event/fanout or gas reset. The receipt-plus-fanout requirement concerns new business/source-history
revisions, not every representation re-encoding. Genuine gas charged by the triggering operation
is still retained. Unsupported mixed-cut cyclic finalization holds; it does not receive a fake PASS.

Measure N consumers and P placements in one consumer separately. Vary P with N=1; a group cannot be
split into per-page gas operations when it exceeds an operational resource cap. A hold must expose
the actual larger need/releaser, not retry forever with an unchanged insufficient cap. Also measure
source receipt/body decoding, hashing, verification and copies separately from source business calls.
Also vary observable transitions U within one operation independently of N and P. Required N consumer
reactions legitimately cost N units of logical work; removing that work is not the optimization.
Avoid redundant source execution, full-history copying and unrelated-document scans. Independent
source commit must not wait for fan-out enumeration or completion. The host uses consistent indexes
and bounded, fair scheduling so 1,000, 10,000 and 100,000 consumers increase useful work/backlog without
unbounded memory, transactions or instability. Total fan-out latency has no universal seconds bound;
measure source commit latency, sustained consumer throughput, lag and resource isolation separately.

Real cyclic workflows retain one shared logical invocation meter and atomic rollback; separately
committed fresh-meter hops or arbitrary later feedback rounds are not compatible optimizations.
Neither representing a cycle nor observing an event makes it a new application emission.

E1a may establish a smaller initial slice only after separate implementation approval. G1 still
requires its exact CORE positive fixtures, including finite feedback/cycle and recovery controls.
Green independent-reader examples do not prove the selected observation program, grouping, BIRTH,
alignment or factorized SCC scope. Implement the kernel and its exact constructors, then verify the
positive/negative witnesses; SQL batching and performance calibration cannot choose other outcomes.
