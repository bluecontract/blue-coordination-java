# SDK migration and ownership ledger

This ledger fixes the application boundary for the `3.0.0-rc.2` SDK freeze
candidate. It is normative for package ownership and migration guidance, but it
does not replace the Contracts 1.0 specification.

```text
candidate: 3.0.0-rc.2
distribution: local staged repository only
normal default: BlueCoordination.inMemory() -> Contracts 1.0
implementationConformanceClaimed: false
```

## Default-profile decision

| Entry point | Intended caller | Semantics | Status |
| --- | --- | --- | --- |
| `BlueCoordination.inMemory()` | normal application | bundled Contracts 1.0 release, authored admission, dynamic public Roots | default |
| `BlueCoordination.builder().release(...)` | controlled host/evidence tooling | Contracts 1.0 with explicit exact release identities | advanced |
| `blue.advanced().rawEngine()` | migrating host integrator | low-level engine owned by the SDK runtime | advanced escape hatch |
| `CoordinationEngine.inMemoryContracts10(...)` | existing Contracts host | explicit identities, roots, closure inputs and receipts | compatibility |
| `CoordinationEngine.inMemory()` | existing pre-Contracts host | earlier acyclic Process Embedded profile | legacy compatibility; not the SDK default |

The two `inMemory()` names are not interchangeable. New application examples,
consumer fixtures, and Javadocs start at `BlueCoordination`.

## Package ownership

| Package | Owner and stability | Permitted use |
| --- | --- | --- |
| `blue.coordination.sdk` | application-facing SDK | normal application imports and built/staged-JAR consumer tests |
| `blue.coordination.api` | low-level host compatibility | advanced integration, existing-host migration, and SDK `DocumentId` interop |
| `blue.coordination.processor` | semantic integration | assembling retained Contracts/BEX processors; not ordinary application code |
| `blue.coordination.internal` | implementation | no application imports; exact build-governed public allowlist only |
| `testFixtures` source set/artifact | test support | library/conformance tests only; never the main runtime JAR |

Within the SDK, `SdkCoordinationRuntime`, `SdkDrainResultMapper`, and
`SdkPreconditions` are package-private implementation details. Public normal
signatures must not expose `ClosureInvocationInput`, affected-closure or
component snapshots, managed occurrence bindings, complete cyclic proofs,
closure environments, execution policies, processor types, internal types, or
raw Blue nodes.

The normal facade may expose stable SDK values and the retained stable
`blue.coordination.api.DocumentId`. Low-level types are reachable only after an
explicit `advanced()` choice.

## Semantic ownership

| Concern | Owning layer | SDK responsibility |
| --- | --- | --- |
| authored document resolution and effective `Process Embedded` catalog | Language/Contracts | pass authored YAML and surface validation failures |
| managed occurrence lineage | Contracts model | collect source/path/target aliases and verify exact agreement |
| cyclic finalization and proof verification | pinned Language/Contracts runtime | invoke; never reimplement or accept caller SCC oracles |
| canonical entry and closure scheduling | Coordination engine | delegate; never introduce a facade queue or graph |
| gas weights, limits, trace, and rollback | Contracts | preserve typed results and exact statistics |
| atomic multi-document publication | Coordination store/Contracts adapter | expose independent immutable closure results |
| target evidence | selected Contracts/Repository profile | bind exact document evidence; never accept final recipient sets |
| public broadcast | Coordination environment | keep explicit through `events()` and preserve terminal `NO_MATCH` |
| physical storage/proofs/topology generations | advanced diagnostics | exclude from normal snapshots |

## Migration map

| Existing low-level pattern | SDK replacement | Notes |
| --- | --- | --- |
| `CoordinationEngine.inMemoryContracts10(configuration)` | `BlueCoordination.inMemory()` | bundled identities; roots authorized during authored admission |
| `registerTimeline(id, actor)` | `timelines().register(id, actor)` | `local(account)` is the equal-id convenience |
| `startDocument(...)` in legacy mode | `documents().admit(ManagedDocument...)` | compiles a one-member Contracts closure; requires public Root and activation |
| hand-built `ClosureInvocationInput` | `documents().admit(ManagedClosure...)` | application supplies authored members and lineage bindings only |
| `Operation.yaml/exact` plus `append` | `operations().on(document)...submit()` | target is exact evidence; append still does no PROCESS |
| `appendTimelineEntry(Node)` | `events().from(timeline).exact(value).submit()` | exact value must be a complete matching Timeline Entry envelope |
| `engine.drain()` plus receipt parsing | `processing().drain()` and `DrainResult` | preserves canonical order and disconnected closure outcomes |
| `onlyOutcome()` | `DrainResult.entry(handle)` / `EntryResult.closures()` | `NO_MATCH` is terminal and multi-closure results are not collapsed |
| `engine.document(id)` | `DocumentHandle.snapshot()` | READY-only application state without physical layout |
| `auditDocument(id)` | `advanced().auditDocument(id)` | explicit non-READY operational read |
| raw release SHA strings in normal construction | bundled release manifest | explicit SHA pairs remain builder/advanced only |

Migration is additive. Existing hosts can keep the low-level boundary while
moving one workflow at a time, but they must not mix handles or semantics from
the legacy and SDK runtimes.

## Managed-draft gap

`ManagedDocumentDraft`, `RequestBuilder.managed(...)`, and
`expectOccurrence(...)` reserve the intended SDK vocabulary. They do not claim
that rc.2 can admit an operation-produced managed child. Such a call fails
before append with `UNSUPPORTED_MANAGED_DRAFT_ADMISSION`.

Completion requires a real host-invocation bridge that keeps exact request
content separate from stable managed identity and activation evidence, verifies
the resulting effective occurrence path and exact state, rejects zero or
ambiguous matches, and admits the affected closure atomically. The bridge may
not call the legacy child/parent path or create a second dependency graph.

Until that exists, the Order-draft and five-child/duplicate-lineage acceptance
gates remain open and `implementationConformanceClaimed` remains false.

## Candidate and release ownership

The rc.2 coordinate is consumed only from the file repository supplied by
`-PblueStagingRepository`. The `staged-artifact` lane owns dependency isolation,
exact component versions, Java 17/21 extracted consumers, and candidate
artifact checks. Historical rc.1 staging and receipts remain under their
existing tasks and are not rewritten.

Passing the SDK artifact lane means the local bytes are coherent. It does not
authorize upload, Maven Local publication, Git push, tag creation, or an
implementation-conformance claim.
