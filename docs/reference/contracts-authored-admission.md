# Contracts authored admission boundary

## Current low-level host boundary

Contracts mode currently admits a complete typed
`ClosureInvocationInput.Operation.ADMIT_CLOSURE` through
`CoordinationEngine.admitContractsClosure(input, policy, verifiedFrontier)`.
This is an expert host boundary, not an ordinary authored-document API. Before
the call, the host must freeze and supply the complete affected-closure
snapshot: exact document states, verified `Process Embedded` occurrence rows,
the graph-derived component partition, complete cyclic proofs, public Roots,
the execution policy, and the Contracts/Language environment identities.

The call does not trust asserted identities. Contracts recomputes and verifies
the invocation, graph, component, proof, policy, and environment evidence, and
Coordination publishes all admitted members atomically. Admission contains no
direct deliveries, and neither this method nor the test-only authored facade
accepts a caller-selected recipient set.

`Contracts10ScenarioBuilder` is test-only characterization support. It authors
exact documents and `Process Embedded.paths` or `collectionPaths`, derives
occurrence bindings from those locations in a frozen environment, runs the real
component finalizer and proof verifier, and then creates the same low-level
input. Its `expectedComponent(...)` value is a literal test oracle checked
against the derived partition; it is not graph evidence passed to Contracts.

## Future high-level API sketch (non-normative, unmerged)

A future application boundary could have a shape similar to:

```java
ContractsClosureAdmissionReceipt admitContractsDocuments(
        List<AuthoredManagedDocument> documents,
        AdmissionPolicy policy,
        VerifiedFrontier verifiedFrontier);
```

The exact type and signature require a separate API design review. The
essential ownership split should remain:

- the caller supplies authored documents and, for later processing, exact
  external Timeline Entries;
- the frozen environment discovers active `Process Embedded` occurrences and
  derives any event targets;
- Language/Contracts derives and verifies occurrence bindings, the graph,
  components, cyclic identities, and proofs;
- Coordination performs one atomic closure admission/publication;
- the caller never supplies SCCs, direct recipients, or route snapshots.

This proposal does not change the current public API or its fail-closed
Contracts 1.0 behavior.
