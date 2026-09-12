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
