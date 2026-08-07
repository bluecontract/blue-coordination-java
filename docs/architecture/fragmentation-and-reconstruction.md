# Fragmentation and reconstruction

Fragmentation changes storage and transport shape only. The direct BlueId of
the authored Root, event, embedded scopes, and executable bodies remains the
identity boundary.

## Catalog input

`CoordinationDocumentSplitter` consumes
`EffectiveFragmentationCatalog.scopePlansByScope()` from Language. It does not
scan `Process Embedded` declarations itself. For every active scope, the
`EmbeddedScopePlanView` provides:

- explicit declaration paths;
- collection declaration paths;
- canonical direct member keys;
- concrete absolute child paths;
- `EXPLICIT` or `COLLECTION_MEMBER` origin for each path.

Registered executable-body boundaries come from the same effective catalog.
An unselected body can therefore be cut without being loaded.

## Canonical inventory

There is one canonical physical fragment per BlueId. Multiple edge
occurrences can point to it. Each edge records enough provenance to explain
why it was cut:

```text
parent fragment identity
child fragment identity
absolute concrete scope/path
edge kind
declaration origin
collection declaration path, when applicable
raw collection key, when applicable
```

Runtime Pointer escaping applies to the concrete path, while the unescaped
raw key remains available for diagnostics.

## Pure references and provider outcomes

A pure reference is accepted only when exact materialization verifies to the
requested BlueId. Provider outcomes remain distinct:

- `NOT_FOUND`: content is not present in the provider domain;
- `UNAVAILABLE`: content may exist but cannot currently be supplied;
- `INVALID_EVIDENCE`: supplied bytes or proof do not bind the requested ID.

Coordination does not map these outcomes to one generic miss and does not
trust content merely because a provider returned it.

## Reconstruction and admission

Reconstruction starts from the pure Root reference, verifies each demanded
fragment, and follows admitted edges. A fragment inventory is admitted
atomically: if two supplied fragments claim the same BlueId with different
canonical content, the entire inventory is rejected.

Opaque cyclic member edges stay proof-bound. Reconstruction does not invent a
direct identity for one member of a cyclic set.

## Locality

Processing should load only the selected scope chain and caused executable
bodies. Unrelated collection members, sibling workflows, and large decoy
bodies remain cold. Re-splitting the resulting Root supplies the next event
without requiring a full reconstruction pass.

The executable specifications are the splitter, admission, deep-locality,
processing-matrix, and flagship tests under
`src/test/java/blue/coordination/processor`.
