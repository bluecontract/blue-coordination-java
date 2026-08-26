# Start here

Blue Coordination's normal application boundary is
`blue.coordination.sdk.BlueCoordination`. Do not begin new application code
with `CoordinationEngine.inMemory()`; that identically named low-level factory
selects the earlier acyclic compatibility profile.

## Five-minute path

1. Use Java 17 or newer.
2. Resolve `blue.coordination:blue-coordination-java:3.0.0-rc.4` from Maven
   Central.
3. Create one `BlueCoordination.inMemory()` owner in a try-with-resources
   block.
4. Register each exact Timeline ID and actor account used by authored
   Channels.
5. Admit either one ordinary `ManagedDocument` or every initially known
   managed lineage in one complete `ManagedClosure`.
6. Use `operations().on(document)` for a logical operation aimed at a current
   document. Use `events().exact(entry)` only when a provider already supplied
   a complete Timeline Entry envelope.
7. Use `execute()` for append-and-drain or `submit()` followed by
   `processing().drain()` when append/process separation is intentional.
8. Check `EntryDisposition` and `Diagnostic`, then read coherent READY state
   through `DocumentHandle.snapshot()`.

The [SDK developer guide](docs/guides/developer-guide.md) explains every step
and walks through the two principal application flows:

- admitting an existing document or complete cyclic closure and processing a
  provider-authored exact Timeline Entry; and
- admitting all initial members, executing causally ordered operations against
  different subdocuments and Timelines, and atomically creating more managed
  members with `ManagedDocumentDraft`.

## Core decisions

| Question | Choose |
| --- | --- |
| No effective managed `Process Embedded` member? | `ManagedDocument` |
| Managed children, shared lineages, or cycles already exist? | one complete `ManagedClosure` |
| Input is logical operation data? | `operations().on(...)` |
| Input is a complete exact Timeline Entry? | `events().exact(...)` |
| Operation creates a genuinely new managed child? | `request.managed(...)`, every `expectOccurrence(...)`, and `fromNow()` |
| Later work depends on the previous result? | sequential `execute()` calls built against fresh handles |
| Independent entries are being ingested in a batch? | `submit()` each, then one canonical `drain()` |

Only effective `Process Embedded.paths` and direct stable-key members under
`collectionPaths` are independently managed. Ordinary nested values remain
inline. Cycles use the same processing model: supply finite stable member and
occurrence bindings and let Language/Contracts derive and verify cyclic
identity evidence.

Operation-created managed lineages support only new `FROM_NOW` children in
rc.4. Imported draft epochs and historical/frontier/attach-current/passive
operation-result activation fail closed. Draft-path preflight also requires a
non-cyclic, independently processable operation target whose effective
`Process Embedded` catalog does not cross a cyclic-set member. If a cyclic
group already exists or must be cyclic at birth, include it in the initial
closure; create later members from a cycle-free owning member.

## Runtime boundary

This candidate is appropriate for deterministic local processing and bounded
external pilots. It is one JVM, in-memory, and sequential. It does not provide
fresh-process durability, a provider-completeness adapter, provider-backed
Mandate resolution, distributed scheduling, production tenant isolation,
durable outbox recovery, or a stable latency SLA.

Read next:

- [Complete SDK developer guide](docs/guides/developer-guide.md)
- [Documentation index](docs/README.md)
- [Public API reference](docs/reference/public-api.md)
- [Process Embedded semantics](docs/semantics/process-embedded-documents.md)
- [Failure and retry model](docs/operations/failure-model.md)
- [Known limitations](docs/limitations.md)
- [Migration from 2.x](docs/migration-from-2.x.md)

Repository contributors should continue with
[Build and test](docs/development/build-and-test.md) and
[Test strategy](docs/development/test-strategy.md). Release maintainers should
use the [Release process](docs/development/releasing.md); historical rc.1
evidence is retained under `docs/releases/` but is not the application starting
point.
