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
  -PblueContractsVersion=3.1.0-rc.23 \
  -PblueContractsRepository=/absolute/contracts/repository \
  -PblueContractsManifestSha256=sha256:<contracts-manifest> \
  -PcoordinationSourceCommit=<coordination-head> \
  -PcoordinationStagedRepository=/absolute/invocation/repository
```

`stagedCoordinationConsumer` aggregates two isolated Gradle builds:
`stagedCoordinationConsumerJava17` and
`stagedCoordinationConsumerJava21`. Both compile the consumer to Java 17
bytecode, launch its tests on the named runtime, and assert the actual runtime
feature version. Each build makes `blue.coordination` exclusive to the
Coordination handoff and `blue.language` exclusive to the Contracts handoff,
verifies the resolved JAR bytes against both manifests, compiles only against
the published Coordination coordinate, and executes the public-SDK operation
and retained managed-epoch smoke. Maven Local, composite substitution, and
remote fallback for either protected group are not permitted. The lane
receipts are `build/reports/dynamic-evolution/staged-consumer-java17.json` and
`staged-consumer-java21.json`; their aggregate is `staged-consumers.json`.

`verifyAcceptedBaseApiCompatibility` resolves only the non-transitive runtime
JAR for the published Central coordinate
`blue.coordination:blue-coordination-java:3.0.0-rc.4`. It rejects the artifact
unless its SHA-256 is
`a8dc99f5bcc62902496ee3bdb5e8f085b78d9781e7928ef20f5a83b65581cd34`.
That published artifact has source provenance commit
`acf02f3ce7c141e4fc9eabb6f19dacde471e30f4`. The gate inventories public and
protected JVM descriptors from that exact JAR across the API, SDK, and
processor packages, then rejects any class or member removal. Additions are
reported but allowed; no local checkout or source rebuild supplies the
compatibility baseline.
