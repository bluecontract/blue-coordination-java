# Contracts authored admission boundary

The normal application boundary is the shipped `blue.coordination.sdk`
authored-admission API. Applications provide authored documents, stable managed
lineages, effective occurrence bindings, public Roots, and an activation
policy. They do not construct closure snapshots, graph components, cyclic
proofs, invocation identities, or recipient sets.

For an end-to-end walkthrough, start with the
[SDK developer guide](../guides/developer-guide.md). This page explains the
ownership boundary between that API and the retained expert host seam.

## Ordinary authored admission

Use `ManagedDocument` when one top-level document has no effective managed
embedded occurrence:

```java
DocumentHandle order = blue.documents().admit(
        ManagedDocument.yaml(orderId, orderYaml)
                .publicRoot()
                .fromNow());
```

The SDK resolves the authored value with the bundled release, compiles a
one-member complete Contracts closure, verifies the selected activation, and
atomically publishes the public Root. It never seeds a legacy singleton session
and presents it as Contracts evidence.

## Complete authored closure admission

Use `ManagedClosure` when managed members, shared lineages, repeated
occurrences, or cycles are already known:

```java
ClosureHandle closure = blue.documents().admit(
        ManagedClosure.builder()
                .document("a", aId, yamlA)
                .document("b", bId, yamlB)
                .bindOccurrence("a", "/b", "b")
                .bindOccurrence("b", "/a", "a")
                .publicRoot("a")
                .fromNow()
                .build());
```

Each alias is a construction name and each `DocumentId` is one stable managed
lineage. Each binding names one source alias, canonical effective
`Process Embedded` occurrence path, and target alias. A target may be reused by
several bindings when several occurrences share the same lineage.

The authored compiler:

1. resolves every member with the pinned Language release;
2. derives each effective `Process Embedded.paths` and `collectionPaths`
   catalog;
3. requires a complete, unique binding for every effective concrete
   occurrence;
4. installs a preliminary managed reference when an authored bound slot is
   absent;
5. verifies exact target agreement when a value or reference is already
   materialized at the slot;
6. rejects undeclared, missing, duplicate, extra, conflicting, and ambiguous
   bindings;
7. delegates occurrence identities, graph/component derivation, cyclic
   finalization, and complete-proof verification to the pinned
   Language/Contracts runtime; and
8. admits all-new members, occurrences, components, routes, and the admission
   receipt in one copy-on-write publication.

Caller assertions are never trusted as graph or proof evidence. Cycles select
no alternate Coordination mode and require no caller-built SCC list.

## Public Roots and source surface

At least one member must be a public Root. A public Root authorizes one external
lane whose source surface is the union of its own active Timeline subscriptions
and those of every reachable active embedded member. Exact route selection can
still target one embedded member directly.

Mark several public Roots only when the application intentionally needs
independent authorized Root lanes. Public Root membership is not a requirement
for every embedded document to receive its own matching Timeline work.

## Operation-produced members

An operation may introduce a genuinely new managed lineage through
`ManagedDocumentDraft`, `request.managed(...)`, and a complete set of
`expectOccurrence(...)` declarations. That is incremental affected-closure
expansion, not a second authored graph API. The SDK verifies exact request and
result agreement and publishes the new head and topology with the parent result.
The draft's exact initial `/documentId` must equal its stable `DocumentId`.
Every expected edge in one call is sourced from the current operation target;
attach nested new members in sequential applied calls or admit an already
interdependent topology as one initial closure.

Rc.3 supports only new `FROM_NOW` operation-produced lineages. Existing members
and initially known cycles belong in the initial `ManagedClosure`. Imported
draft epochs and historical/frontier/attach-current/passive operation-result
activation fail closed. The operation target must also be independently
processable and non-cyclic for managed-draft path preflight, and its effective
catalog cannot cross a cyclic-set member. Add later members from a cycle-free
owning member rather than from or across an existing cyclic component.

## Expert low-level host boundary

`CoordinationEngine.admitContractsClosure(input, policy, verifiedFrontier)` is
retained for an advanced host that already owns complete typed Contracts
evidence. Before that call, the host must freeze and supply the complete
`ClosureInvocationInput.Operation.ADMIT_CLOSURE`: exact document states,
verified occurrence rows, graph-derived component partition, complete cyclic
proofs, public Roots, execution policy, and exact Contracts/Language
environment identities.

The call still does not trust asserted identities. Contracts recomputes and
verifies the invocation, graph, components, proofs, policy, and environment,
and Coordination atomically publishes all admitted members. Admission contains
no direct deliveries and accepts no caller-selected recipient set.

Normal applications must not recreate this input. Use
`BlueCoordination.inMemory()`, `ManagedDocument`, and `ManagedClosure`; reserve
the raw engine for an explicit host-migration requirement described in the
[SDK migration and ownership ledger](sdk-migration-and-ownership.md).

## Test-only characterization support

`Contracts10ScenarioBuilder` is test-only. It authors exact documents and
effective occurrence catalogs, derives bindings in a frozen environment, runs
the real component finalizer and proof verifier, and creates the same low-level
input. Its `expectedComponent(...)` value is a literal test oracle checked
against the derived partition; it is not graph evidence passed to Contracts.
