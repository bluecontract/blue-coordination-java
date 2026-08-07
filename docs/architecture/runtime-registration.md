# Runtime registration

Coordination extends one immutable Contracts registry generation. It does not
maintain a process-global registry and does not mutate a built `BlueLanguage`
or `BlueContracts` service.

## Focused composition

The application creates an exact provider and one `BlueLanguage`. It then
configures a `ContractProcessorRegistryBuilder` with
`CoordinationProcessors.configure(...)` and passes the built registry to
`BlueContracts.builder(language.processing())`.

The production dependency surface is deliberately focused:

```text
blue-language-model
blue-language-core
blue-language-mapping
blue-contracts-core
blue-bex-core
blue-bex-contracts
exact hash-verified local blue-repo-java binary
```

The Language and BEX aggregate projects are orchestration roots, not runtime
dependencies.

## Ownership

`BlueLanguage` owns Language caches and processing scopes. `BlueContracts`
borrows the Language processing bridge and owns its Contracts processor.
Coordination owns neither service. Hosted BEX borrows the exact same Language
runtime through `CoordinationProcessorOptions.language(...)`.

Close in reverse construction order:

```text
BlueContracts.close()
BlueLanguage.close()
```

Caller-supplied BEX engines and workflow runners also remain caller-owned.

## Registration contents

Coordination registers concrete processors for Timeline Channels, Operations,
Sequential Workflows, workflow Operations, and the modular BEX Compute step.
Repository model scanning supplies Java mappings; exact type identities still
come from verified provider content and the frozen runtime registry.

Timeline Channel subtypes are explicit host choices. Register a subtype on the
same builder before it is built. Runtime type evidence, rather than a Java
class-name allowlist, remains authoritative.

## Observation

`ProcessingObserver` is an operational boundary. Observations may count work,
record high-water marks, or export diagnostics, but they are failure-isolated
and absent from the semantic result. `CoordinationProcessors.observers(...)`
combines observers without allowing one observer failure to reach processing.

BEX metrics use the modular BEX metrics sink and are mapped to current
Contracts observations. No removed `ProcessingMetricsSink` compatibility
surface is required.

## Registration does not select delivery architecture

Processor registration and external delivery planning are separate choices.
An indexed host persists subscription snapshots and prepares exact evidence.
A small compatibility host may opt into a current-Root planner when that
public boundary is available. Neither choice changes Channel semantics.

The executable registration checks live in
`CoordinationProcessorsTest`, `BexModularApiMigrationTest`, and
`LatestLanguageArchitectureTest`.
