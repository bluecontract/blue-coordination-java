# Adding a Channel

A Channel defines subscription, acceptance, payload, targeting, dependency,
and checkpoint behavior. `collectionPaths` only determines where Channel
occurrences are active; it does not provide Channel targeting.

## Steps

1. Define the contract model in the immutable Repository catalog and bind it
   to an exact type identity.
2. Implement the current Contracts Channel processor interfaces using focused
   `blue-contracts-core` APIs.
3. Register the processor on
   `ContractProcessorRegistryBuilder` before building the registry generation.
4. If the Channel is a Timeline subtype, use
   `CoordinationProcessors.registerTimelineSubtype(...)` on that same builder.
5. Define deterministic subscription keys and ensure complete acceptance is
   re-evaluated after index preselection.
6. Bind checkpoint domain and subject to the exact source occurrence.
7. Keep target dispatch headers immutable and executable bodies lazy.

## Required tests

Use Given–When–Then tests named with `should`. Cover inline and pure-reference
headers, accepted and rejected events, two collection occurrences sharing one
definition, wrong-target rejection, cold unrelated bodies, provider outcome
distinctions, and compatibility/indexed planner agreement.

Do not edit Language or add a class under `blue.language.*` to gain access to
its internals. If the registered Channel law cannot be evaluated through a
public API, classify the exact gap.
