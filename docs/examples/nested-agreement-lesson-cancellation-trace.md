<!-- GENERATED FILE: tools/publish-nested-agreement-trace.js -->

# Nested agreement flagship evidence

Evidence status: `failed`

Run: `coordination-release-evidence-2026-08-03`

Finished: `2026-08-03T13:20:33.024Z`

Source trace SHA-256: `ff771a14035b83365eefc4403949f14f84cce68c0b205ed41b46b91626199410`

This file is generated from the structured trace named above.
Structural results and PROCESS runtime results are separate evidence lanes.
structural lane does not imply that any PROCESS scenario executed.

## Evidence lanes

| Lane | Status | Declared | Attempted | Completed | Diagnostic |
|---|---|---:|---:|---:|---|
| structural | passed | 1 | 1 | 1 |  |
| A-root-only | notExecuted | 1 | 0 | 0 |  |
| B-one-lesson | notExecuted | 1 | 0 | 0 |  |
| C-deep-cancellation | failed | 2 | 2 | 0 | Immutable Repository bytecode failed before PROCESS with java.lang.NoClassDefFoundError: blue/language/NodeProvider. |
| D-sibling-agreement | notExecuted | 1 | 0 | 0 |  |
| E-add-member | notExecuted | 2 | 0 | 0 |  |
| F-remove-readd | notExecuted | 1 | 0 | 0 |  |
| G-shared-initial-child | notExecuted | 1 | 0 | 0 |  |
| H-frozen-membership | notExecuted | 1 | 0 | 0 |  |
| I-invalid-surfaces | notExecuted | 11 | 0 | 0 |  |

## Observed structural scope plan

```json
{
  "collectionPaths": [
    "/agreements",
    "/lessons",
    "/paymentProcesses",
    "/cancellations"
  ],
  "concreteEmbeddedOccurrences": 10,
  "nestedScopes": true,
  "rfc6901EscapedMemberKeys": true,
  "stableObjectKeys": true
}
```

## Observed structural fragment inventory

```json
{
  "canonicalExactFragments": true,
  "collectionDeclarationProvenance": true,
  "onePhysicalFragmentPerBlueId": true,
  "sharedBlueIdIndependentOccurrences": true
}
```

## Observed structural reconstruction

```json
{
  "exactNodeWireForm": true,
  "exactRootBlueId": true
}
```

## PROCESS runtime result boundary

No PROCESS event sequence, resulting Root, public event, subscription
transition, gas trace, or provider-demand result is published for this
`failed` trace. Scenarios with zero attempts were not executed.
The structural sections above, when present, are representation evidence
only and are not runtime-semantic evidence.

