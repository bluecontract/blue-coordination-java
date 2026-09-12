# Diamond context diagnostic D03

This test-only branch starts at clean Coordination
`8278eb77ee597dd906b737a7e0ffe7afbff7df8a`. It copies the frozen D02 acquisition
test/probe from the previous diagnostic; no production Coordination code changes.
The sole helper adaptation passes authenticated `RootedWitnessFrame.State`
through the package-private graph constructor instead of reducing it to a set
of source identities. It follows the proposed Language private signature.

The real `ClosureEvidenceFactory.rootedReadExpansion` still constructs the input.
The observation helper cannot authorize a different primary or bypass any check.
The probe retains original B2/C0 and supplies separately verified A11/D14, where
A11 references D2. It then attempts the original PROCESS/publication and full
settlement checks. Getting beyond read expansion alone is not a full pass.

D02's failed archive remains unchanged. This is a diagnostic, not yet a
normative scheduling change or baseline acceptance. Run only after pinning and
exporting the exact reviewed Language context-fix candidate; do not use an
unrecorded mutable dependency or relabel old D02 output.

## Exact Language binding

The metadata-only follow-up to diagnostic source
`23c0256c677f2d1d48589e8baf45c634f36e891b` selects clean Language
`430ee3936af1e7d73bc82032484e31c83851cec3`, tree
`121d82a3efc98ba86be38565d14d3639e4beedc4`, DEVELOPMENT version
`3.1.0-dev.430ee3936af1e7d73bc82032484e31c83851cec3`.
Its generated Contracts release is
`sha256:14a9653062c2b4d456c54313db55d22fe92573bd5bcf4f3e87b50400918116f1`.
Only the active `contractsRelease` property and its matching exact
`SdkCoreSeamsTest` literal change. Contracts specification, fixture package,
gas, finalizer/verifier identities and every existing test assertion remain
unchanged; no Coordination runtime or diagnostic behavior changes.

Language's staged 383-file Contracts package changes only its release manifest
(eight implementation hashes and derived release identity); all other 382
files are byte-identical. Its 578/578 Contracts test pass belongs to the prior
runtime/test source `64f368c0`, before this metadata rebind, not to a downstream
Coordination run. The new Language/BEX/Catalog exports and exact manifest inputs
must be verified before executing this diagnostic. No test or export has run
as part of this Coordination binding change. D03 settlement, original MyOS
acceptance and complete library qualification remain pending.
