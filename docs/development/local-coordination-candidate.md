# Local Coordination candidates

The Coordination-only candidate lane exports a clean committed source revision
under `3.0.0-dev.<40-character-commit>`. It retains the normal published Language,
BEX and type-repository dependencies and their dependency lock. It does not
change `.cz.toml`, release tags or published coordinates.

After committing changes, invoke `exportCoordinationCandidate` with
`-PcoordinationCandidateCommit=<exact HEAD>` and
`-PcoordinationCandidateRepository=<new output directory outside the checkout>`.
The output parent must already exist. An existing output directory is rejected.
The task reuses `stageCoordinationCandidatePublications`, checks the publication
POM, artifact contents and published dependency isolation, and emits runtime,
source and Javadoc JARs, the POM, `candidate.json` and its SHA-256 sidecar.

The manifest binds the clean Git commit/tree, each exported artifact and the
resolved standalone runtime dependency graph. The runtime JAR also records its
candidate commit/tree and version in its manifest. Source changes during a build
invalidate export. Candidate builds reject Maven publication, Maven Local and
JReleaser task graphs before execution.

Export is artifact preparation, not qualification. Run the relevant standalone
tests and `releaseCheck` separately and bind their evidence to the same source
and candidate. Mini must verify its resolved and packaged candidate bytes and
its own dependency graph; matching Blue coordinates alone does not establish
compatibility with host-managed third-party dependency versions.
