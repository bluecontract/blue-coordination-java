# Adding a workflow step

A Sequential Workflow step changes the invocation-owned working Root or emits
a caused event. It executes inside the existing one-Root transaction.

## Steps

1. Add the immutable step model and exact Repository type identity.
2. Implement a step executor under
   `blue.coordination.processor.workflow`.
3. Register the executor in the Coordination workflow runner before the
   registry generation is frozen.
4. Declare any executable-body boundary through the current Contracts
   registration metadata so the effective fragmentation catalog can expose
   it.
5. Materialize only the selected step body. Never preload sibling steps or
   unrelated collection branches.
6. Charge portable gas at the semantic owner exactly once and use operational
   observations for host metrics.
7. Return effects to the workflow state; do not commit a child Root.

## Tests

Prove deterministic order, rollback, portable gas, trace order, cold-body
locality, inline/reference equivalence, and Java 8 bytecode. A step that emits
an event must also prove that embedded emissions remain internal unless Root
owns them.

For Compute, use the modular BEX host boundary with the exact borrowed
`BlueLanguage`. Do not call the removed `BexEngine.Builder.blue(...)` adapter.
