# Immutable Coordination handoff

The dynamic-evolution handoff is a closed, invocation-owned Maven repository.
It contains only the Coordination POM, runtime JAR, sources JAR, Javadoc JAR,
their SHA-256 companions, and a manifest plus its SHA-256 companion. The
manifest binds the exact source commit, every resolved runtime dependency, and
the complete semantic identity of the immutable Contracts repository used to
build it.

The source worktree must be clean and `coordinationSourceCommit` must identify
`HEAD`. The target must be an absolute path outside the source tree. A second
identical invocation is accepted byte-for-byte; an invocation producing any
different byte is rejected without replacing the existing target.

```bash
./gradlew dynamicEvolutionCoordinationHandoff \
  -PblueDependencyMode=immutable-staged-contracts \
  -PblueContractsRepository=/absolute/contracts/repository \
  -PblueContractsManifestSha256=sha256:<contracts-manifest> \
  -PcoordinationSourceCommit=<coordination-head> \
  -PcoordinationStagedRepository=/absolute/invocation/repository
```

`stagedCoordinationConsumer` runs a separate Gradle build. That build makes
`blue.coordination` exclusive to the Coordination handoff and `blue.language`
exclusive to the Contracts handoff, verifies the resolved JAR bytes against
both manifests, compiles only against the published Coordination coordinate,
and executes a public-SDK operation. Maven Local, composite substitution, and
remote fallback for either protected group are not permitted.

`verifyAcceptedBaseApiCompatibility` independently builds accepted base
`c6f9c80d0a33c6c209c7ba3d2b8bff89a223fc5f`, inventories public and protected
JVM descriptors across the API, SDK, and processor packages, and rejects any
class or member removal. Additions are reported but allowed.
