# Preserve exact provider-authored identity during source admission

## Problem and example

MyOS's packaged `ProviderAuthoredReferencePublicApiAcceptanceTest` exposed a source
admission failure: `Source compiler changed the exact authored identity`.
The SDK supports two distinct ingress operations: preparing authored processing
Source and storing exact provider content. Their resulting BlueIds need not be
equal. A parent can legitimately attach the exact provider-authored value while
another lineage prepared through the processing-Source ingress already exists.

The source discovery path authenticated the provider value, then serialized and
parsed it again as new processing Source. That second preparation changed its
identity. This is an admission implementation bug, not a reason to merge those
lineages or change either public ingress contract.

## Correction

The static admission compiler now has a package-private exact-authored entry
point. Source discovery passes its already-authenticated `ExactValue` directly.
The public Source compiler still performs its original preparation, and the
assertion that the compiled source has the requested document ID remains.
No gas, history, publication ownership, or attachment policy changes.

The regression creates both supported ingress forms, attaches the provider form,
executes the separate source-owned admission, resumes the parent, and processes a
later source update. It checks that admission initializes the exact provider
lineage once, the parent observes the later update, and the distinct processing-
Source lineage remains unchanged. Normal exact-content authentication is retained;
accepting a newly computed replacement ID was rejected as an incorrect fix.

## Evidence and boundary

The direct SDK owner is
`RootedExactProviderAdmissionTest.sourceAdmissionPreservesTheProviderAuthoredIdentity`;
the original application owner is
`ProviderAuthoredReferencePublicApiAcceptanceTest.providerAuthoredReferencePublishesOnceAndRestartsWithoutReinterpretation`.
The former isolates the
library defect without HTTP or persistence; the latter verifies the user path.

The new focused test failed before the correction with the same identity error.
After the correction, 40 focused SDK/source-admission controls and strict
Javadocs passed, using Language `bd09c281`, BEX `ab72af14`, and Catalog `0b68744b`
with matching immutable dependency bindings. This is not full application or
durable-mode acceptance. The original packaged HTTP scenario is a separate gate.

Archived evidence under `rooted-exact-source-admission-evidence.v9OqkF`:

- `red-provider-identity.tar.gz`, SHA-256
  `69de1c2ad6cf0e5a377ea3d25b330d5646ccc15c1c140273dfe95c15e17c4c2a`.
- `final-source-admission-40-pass.tar.gz`, SHA-256
  `dd982c3ca195b6a31e94f90b9be8ef02aa03c2551308b700893ae74fa0b07547`.
