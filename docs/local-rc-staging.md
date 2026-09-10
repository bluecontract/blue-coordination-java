# Immutable local RC integration

Use `-PblueDependencyMode=immutable-local-rc-contracts` and
`-PblueDevelopmentVersion=3.0.0-rc.6` to package the local Coordination RC.
This explicit mode requires Language `3.1.0-rc.N`, BEX `1.1.0-rc.N`, and
catalog `3.0.0-rc.N` from their immutable local RC repositories. For the historical RC6
closeout the selected upstream versions were `3.1.0-rc.24`, `1.1.0-rc.5` and
`3.0.0-rc.22` respectively.

Supply each upstream's exact `Version`, `Repository`, `SourceCommit` and
`ManifestSha256` properties using the existing `blueContracts`, `blueBex` and
`blueRepository` prefixes. Every source commit and tree must be exact and clean,
all manifests must report Java 17 and `LOCAL_RC`, and all publication paths,
checksums and file inventories are verified. BEX and catalog must bind the
selected Language manifest; the SDK profile must bind its Contracts release.

Run `assembleImmutableStagedCoordinationRepository` with
`-PcoordinationSourceCommit=<exact-clean-HEAD>` and
`-PcoordinationStagedRepository=/absolute/new/repository`. The export retains
`blue-coordination-staged-dependency-repository/1.0`, records the explicit RC
mode and `LOCAL_RC` purpose, and makes no release-readiness claim. Existing
bytes are immutable. No publication task is invoked.

`stagedCoordinationConsumer` verifies the packaged API on Java 17 and Java 21.
Its repositories isolate all four Blue groups, including the exact catalog.
Source-archive verification propagates the same full tuple. These checks must
be combined with full component and final packaged MyOS acceptance evidence;
a successful export alone does not establish release readiness.
