# Migrating from the previous Language API

The current stack replaces the mutable monolithic `Blue` runtime with focused,
immutable services.

## Dependency changes

Use focused coordinates:

```text
blue-language-model
blue-language-core
blue-language-mapping
blue-contracts-core
blue-bex-core
blue-bex-contracts
```

Do not substitute a module coordinate with an included-build root project.
Do not retain the aggregate Language or BEX coordinate as an accidental
production dependency.

## Source changes

| Previous pattern | Current pattern |
|---|---|
| mutable `blue.language.Blue` ownership | immutable `BlueLanguage` plus `BlueContracts` |
| `blue.language.NodeProvider` | `blue.language.provider.NodeProvider` |
| `blue.language.utils.*` | focused `identity`, `model.wire`, `codec.jackson`, `graph`, or processor utilities |
| post-build processor registration | `ContractProcessorRegistryBuilder` or `DocumentProcessor.Builder` before `build()` |
| `ProcessingMetricsSink` callbacks | typed `ProcessingObserver` observations |
| monolithic BEX types | `blue.bex.api` plus modular contracts/runtime modules |
| `BexEngine.Builder.blue(...)` | exact shared `BlueLanguage` supplied to the modular host boundary |

## Collection migration

Do not parse `Process Embedded.paths` in Coordination. Ask Contracts for
`EffectiveFragmentationCatalog.scopePlansByScope()` and consume
`EmbeddedScopePlanView`. Preserve explicit versus collection-member origin,
raw key, and escaped concrete path.

## Split-package removal

Every Coordination implementation class must use a `blue.coordination.*`
package. Package-private Language access is not a migration technique. The
source guard in `LatestLanguageArchitectureTest` fails when a production class
or import crosses that boundary.

## Verification

Run exact sibling input verification first, then compile, focused tests, the
ordinary suite, architecture checks, Java 8 bytecode, and report generation.
A missing public operation is a narrowly documented blocker; it is never a
reason to restore compatibility classes.
