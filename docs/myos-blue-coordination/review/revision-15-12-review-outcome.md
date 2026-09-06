# Revision15.12: final composition fixes and authorized implementation

> **Status:** design corrections applied; Phase1/2 implementation authorized and in progress, not accepted

The user authorized the final review corrections and two supervised parallel implementation tracks.
[23](../23-first-implementation-execution-plan.md) gives the concrete workspaces, checkpoints,
incremental test policy and Phase3-readiness gate. [22](../22-processing-kernel.md) remains algorithm
authority. Earlier outcomes and test evidence are unchanged historical records.

## Applied decisions

1. **Whole conditional-attempt invalidation.** Ownership/gas monotonicity applies inside a valid
   attempt. An attempt relying on unfinished independent producer B binds that assumption. If B
   fails, invalidate the entire dependent attempt, including later topology admissions, meters,
   candidate failures and outputs, then reconstruct still-due own seeds. Never undo public work or
   re-evaluate B's rejection using cheaper cleanup state. The AC61+B51=112 witness and dependent
   gas-failure variant constrain the implementation. Validated/internalized assumptions are required
   for publication; explanatory failed-prefix evidence is not another semantic settlement.
2. **Per-producer Entry-site alignment.** A composed reaction does not prealign all source pins.
   Align each at its first canonical Entry/consumption site with ordinary placement updates/FIFO.
   RootA0/B0, A1→2 before B1→2 gives B0 in both /a callbacks. The concrete static fixture follows
   dependency-first ordering; Root's direct seed after both sources sees2/2, rather than being
   artificially moved ahead of A to manufacture a0/0 read. Preserve
   alias/retirement, diamond cell reuse and eventless/net-zero controls.
3. **Publication boundary.** Per-stream order and dependencies between distinct operations are
   required. A coupled operation's canonical A1,B1,A2 stays in its authenticated result, without
   promising that separately delivered lineage batches reproduce that network interleaving. Never
   create cyclic same-operation outbox prerequisites or derive semantic order from sink arrival.
4. **Documentation drift.** Recognized semantic RUNTIME_FATAL continuation and the explicit endless
   valid-import repair limitation are synchronized. Equal-provenance receipts imply successive
   consumer operations only at distinct logical reaction positions; converging same-position
   producers are composed. Existing53 schedules/nine subcases retain their identities.

## Implementation, not another planning-only loop

One library agent owns actual Coordination/Language/Contracts/BEX changes in isolated localnext-based
worktrees. One host agent owns `myos-simple` on `feat/coordination-with-external-state`, based on local
main. Both use GPT-6 Astra Extra High. The supervisor handles boundary coordination, separable repairs,
diff/test review and corrective follow-ups. Original exploratory changes and user examples are preserved.

Each agent owns its full track, with three internal checkpoints and targeted tests. Full impacted
regression runs at final candidates, not after each small edit. A shared real-library/PG restart
handshake establishes adapter readiness; Phase3 full integration, examples and decisive performance
measurements remain the next delivery stage. Future iterations are expected, not a reason to skip
library or database correctness tests now.

No readiness, G1/G2 pass or production claim is made by this revision. Runtime reports must name
actual code, commands, counts/timing, failures and omissions. Structural documentation QA remains
separate from executed behavioral evidence.
