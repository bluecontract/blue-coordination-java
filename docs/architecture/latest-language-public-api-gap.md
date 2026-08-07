# Resolved Contracts public API boundary

This report supersedes the earlier public-API gap report. The required
runtime-neutral operations now exist in the locked local Contracts build.
Coordination must use them directly; a fail-closed placeholder that reports
one of these operations as absent is a Coordination defect, not an accepted
external blocker.

## Exact verified inputs

The dependency gate is bound to the local inputs selected by
`gradle/blue-sibling-lock.properties`:

```text
Language and implementation: a3b38ca9a1d0b9ca8527b26d23b05cfdbc6af7d9
blue-contracts-core JAR:      sha256:9fdc03c12b7da8262bddec59a7230b548a33683c211b602311a27266bc2ffcd0
BEX:                          c3e36c65b9928c5ae7ef0d839b56ff35a0b70d97
BEX working receipt:          sha256:b915d6722e7da63705e765431d60d895c69b7dc654f30ad8a6528ebeb33cfd84
Repository:                   63be6b7d8d2752b5a8c90f38e672859e9b3949a1
```

Language's checkout and verified implementation commit are identical. The
Language and BEX checkouts are clean. The Repository input is a clean,
immutable materialization of that local commit, and its exact local JAR is
hash-verified; the dirty user-owned Repository working tree is never compiled.

## Public operations Coordination consumes

The configured `BlueContracts` instance supplies these public boundaries:

- `runtimeAccess()` supplies immutable runtime access for custom processors;
- `subscriptionSurfaceProjection()` performs initial projection and
  incremental interval updates;
- `indexedDeliveryEvaluator()` authoritatively verifies an ordered candidate
  set, including exact, omitted, extra, duplicate, wrong-order, and stale
  revision cases;
- `currentRootDeliveryPlanDeriver(...)` derives the compatibility plan from
  the same current Root semantics;
- `effectiveFragmentationCatalog(...)` supplies the canonical structured cut
  catalog, including stable-key collection members and nested scopes;
- `processForPlatformCommit(...)` prepares the processing result for the
  host's atomic platform commit and post-commit activation boundary.

Those services also cover exact reference materialization and semantic output
admission. Coordination must not mirror their registries, loaders, matching,
processing, or gas logic, and it must not restore classes under
`blue.language.*` to gain package-private access.

## Evidence rule

API availability is not itself evidence that a Coordination lane passed. Each
lane must execute against the exact local dependency lock and publish its own
same-run result. In particular, subscription projection, indexed delivery,
pure-reference header materialization, Operation Request routing, hosted
Compute output admission, and platform-commit activation may no longer be
classified as unavailable public APIs. A failure in one of those lanes keeps
the release red and must retain its actual diagnostic.

## Separate immutable Repository compatibility

The locked Repository revision remains an independent input. Any removed ABI
or historical registry-evidence mismatch reproduced from that immutable
revision must remain separately classified and must not be hidden with a
remote artifact, identity alias, provider trust bypass, or generated-class
patch. Conversely, Repository evidence cannot be used to excuse a failure in
one of the now-public Contracts operations above.
