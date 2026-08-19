# Blue Coordination SDK freeze receipt

Generated: 2026-08-19T22:21:28Z  
Candidate: `blue.coordination:blue-coordination-java:3.0.0-rc.2`  
Receipt state: `FINAL_WITH_CHARACTERIZED_CAPABILITY_BLOCKERS`

## Verdict

The recovered cyclic-topology sources and reports are genuine, the additive
Contracts SDK supported subset is green, and the exact local staged dependency
graph is consumable from Java 17 and Java 21. The candidate was staged only in
the explicit file repository at
`/private/tmp/blue-coordination-sdk-freeze.A5R15s/staged-repository`.

This is **not** a release-ready or production-ready implementation-conformance
receipt:

```text
implementationConformanceClaimed = false
releaseReady = false
performanceClaim = NONE
```

Operation-produced managed-child admission is deliberately fail-closed with
`UNSUPPORTED_MANAGED_DRAFT_ADMISSION`. Required acceptance cases 12 (Order
draft into `/orders`) and 13 (five children with duplicate managed lineages)
therefore remain unresolved.

No package was remotely published or installed into Maven Local. No Git branch,
commit, or tag was pushed, and no remote release was created.

## Exact source bindings

All listed worktrees were clean when this receipt was drafted.

| Component | Branch | Commit |
| --- | --- | --- |
| Language | `codex/cyclic-topology-language` | `d4a0379053e1a716395349c40fa403ee993796ff` |
| BEX topology source | `codex/cyclic-topology-bex` | `821fe877fef5b04a729b7422cdda05a7ace55a1f` |
| BEX staging | `codex/coordination-sdk-staging-bex` | `d91c4c69e9aa463f0ae5ab9033ddf802ef9f8263` |
| Specification | `codex/contracts-1.0-spec` | `5dc8096276652156e248c9c018a0850fcd8dbdbb` |
| Repository source | `feat/current-repository-api` | `2fcf29bf060ed114c971194adb6f8b747899aee2` |
| Repository staging | `codex/coordination-sdk-staging-repository` | `d305821bd813e77d46b7e559f03c0c6c902353f2` |
| Coordination recovered topology base | `codex/cyclic-topology-coordination` | `d6075717061ae59a87906d075b9bd30f9fb95e65` |
| Coordination SDK candidate | `codex/coordination-sdk-freeze` | `2b6219a1a6846283aeacb674b5b08288faca23e4` |

The prompt-named Coordination evidence commit `f245270` is present and was
exported. `d607571` is its direct evidence-finalization descendant and is the
SDK branch base. The Coordination SDK range has 11 small commits and changes
74 files. The BEX staging delta changes five files; the Repository staging
delta changes one file. `changed-files.sha256` hashes every file in those three
deltas plus the bound external evidence and artifact files.

## Bundled release identities

These values are shipped in the candidate JAR's bundled Contracts 1.0 release
manifest:

| Identity | Value |
| --- | --- |
| Blue Language specification | `sha256:01b038b64e3f0a9a11f3f70d544a63ff78a01d5169f1a03f8b8629cf73645a7d` |
| Contracts specification | `sha256:dfb444962a5a17b3a6519e8d148c2bf4a975a921b1fcb1277710052caaecd930` |
| Contracts release | `sha256:7e6c3717bc28d21ebadec9f81725913e944bb3b9b70094531f19f10510a10e50` |
| Fixture package | `sha256:071cecb68e1c4dcec2dbb0895de928629281d2b0a18f3e8a83a41a720e621bfa` |
| Gas manifest | `sha256:03219c42eb3696ef8727fe8ae226c8a5eb4a6126859ba744f571d892c409626a` |
| Cyclic finalizer implementation | `sha256:0b4bd3bbe4380faa52d14bc6baf8bb0a6dbc01acc576985676155ea0115969b4` |
| Cyclic proof verifier implementation | `sha256:eb0501a25ec5ac6a18fc86584c0afb6ecc2e6c1201c723f28ec56c80a2ae3bc5` |

The exact package contains 167 ordinary fixtures and 67 closure fixtures (234
Contracts fixtures total); both source-bound corpora are green and there were
no normative fixture mutations. The wider Language release-conformance total
is 387 fixtures (153 Language plus 234 Contracts). Coordination's staged gate
does not itself rerun the Language fixture harness, so this receipt binds the
exact Language commit and fixture-package identity without relabeling it as a
new candidate implementation-conformance execution.

## Local staged coordinates and artifact hashes

Each row is `main JAR / POM / Gradle module metadata / sources JAR / Javadoc
JAR`, all SHA-256.

| Coordinate | JAR | POM | Module | Sources | Javadocs |
| --- | --- | --- | --- | --- | --- |
| `blue.language:blue-conformance:3.1.0-rc.20` | `db1a398958d02c8b80d04cba0f3997d72f14c5965c106a356d7043d8b92b9e10` | `45e8b67f94ded3e1d8752eddce00c16b4fb4052b4e8d93eff41807b250fcd6a8` | `303d6b618cf176ea8f5337341b48cb7cea67548602003fdc16e159f0adadc024` | `1f12c4102de76659b9df1d5ad86938076bb0d877bdf70b2149966352ee786af8` | `b8adde1a0ea7208954f633fae2ec30fbbaa4da2ef08d70ec9808256ee23ef430` |
| `blue.language:blue-contracts-core:3.1.0-rc.20` | `6452b1271877d73736068fe0411d27d4f737dfb2f44900e31ab407d2169aba20` | `0bba60df373eb66c052c104cd2515889b6a33dc571d96ce66c46e91d1075d685` | `c4847234a27047836f689f14a021aef101bedbe96fc34564396ab3ec7de48f22` | `989bf0ecdbe65e71e9c7b402bb5f7ec8d782d3c7f9903179711d9b999f18dc51` | `61559c362c248aa048c58de50046d938fcdbbf431dc0bb8f4156893fb8cf1c3f` |
| `blue.language:blue-language-core:3.1.0-rc.20` | `8d7167254a39132e7a494561ed966748918c138c08f0967ccc3e841edba0b1f0` | `e45c0f87e3cff13795cbd9aa800c72cb714a3d5f78e814845c4960f3073a00ed` | `575f2e22af188336e6b9b8ff7b04084fa116e3cee6dca2d13c031eadd4e84581` | `74ad60632833c303f42b95e8c4122b4c4fd16f61b76c121c04c28786819f929d` | `bec768b95b3d7c8887c360f74f71dc27f8d95121956dc7e834face6ffa33d6a5` |
| `blue.language:blue-language-ipfs:3.1.0-rc.20` | `bec7355f39a109c4fe6dfc5f9970232dc0a75cd8e5b4ab055abc311314d24c8e` | `435e07124b36ccb8dfff346f5bdf62e60208335084d521580cf4ff3dd71210be` | `873f8f5105145da514f8cd5a5c286db209ed17567895b52d6f040a751b2da4de` | `a7fd62c141303410d1dba27904e6114afd3a9b997b44493be951b8d1c1a3eab9` | `cf7101f7be0450ba957a40667aa64a127663e42a79d3d1e95f4e03812f400ca8` |
| `blue.language:blue-language-java:3.1.0-rc.20` | `0de1584be094515ddd27938819464dc024a993c7eb06e4145cac129ad5bbfed0` | `d1592a32bf4556117476be331c3453d848ef81bf8a6da07155fc16953db1291d` | `15b5fabda72d05ad5fc40074c3ec18d7ff79ffc8b932a58e2016d4977acc931f` | `68d1069c56f754c2e76f208a4126a967533cc91059062c2e86b70e098f33a518` | `3dffbe1e6614edf10e1dee47f6c2840fde9c69ed50f7e03f9e88ccc39dc41908` |
| `blue.language:blue-language-mapping:3.1.0-rc.20` | `d9141d5c611bde7eb6a21bce3dc4bc0df7d8167f013eeaef2a365dd0a6af329b` | `247c7ed579152bd66df286ec00946540141e91d229df5b89d021ee9b0dbd3feb` | `3398ef16f4c085b8cac95ddf1c284533ee839de97d80f67eb933cc3de9468e54` | `05ddbc700dd0635927ac6e8b2edb93e778d92c1312c3504539d6799c9e2079db` | `53dbf28cab3d343bac70ba20b4eb1683c61346c6c789eac7527b86ceaeb6ffa1` |
| `blue.language:blue-language-model:3.1.0-rc.20` | `ef55be8331147442b858474add4782489d993568effe30202a9c4a8b014d5bd8` | `4f33c99eed160d9e5650288e92e5f39ffdc49eae4428b60009e4db52ee34bbe2` | `d1f808f910b2117f90f961da29980b0edcba50a5446614f34900965757f81f0a` | `84b48c13cff2594230a23cc248a7c00e7b2d0cb3b352cc90347039035ab472e6` | `f098f3ebd4ed87ee0088e7b170940db033a0b9f2e106815c7fd3472ce73b365a` |
| `blue.bex:blue-bex-contracts:1.1.0-rc.3` | `18fcce8af029debc5e8d446d28fbf6d3de52cb4bae3952d1232303ba3ee37537` | `49348f416f133ad167454cc866441e56077193302f9e0da03aaa5cbaa58a94c8` | `259356f92ad5145c3e452ca1bbd5d5aa38184121e6e36af76a1b0223096b4548` | `24d1ddd90c1376775a964618d0a565cab0b4f671c2307318497e7c4e70abc6ec` | `73579f13e452293d8084d06bdca082b2c5c627e37f477a769be31b9f213ebd4f` |
| `blue.bex:blue-bex-core:1.1.0-rc.3` | `612637c316afe9f7e211f9e31aa03a47da772065b2f895c9851ef04b098a978e` | `07d69aba9bdfb2694966587ff150e7d030078ea67cdae2c2d98645394e7f7c43` | `eba2558674d6e6e03a00ea81d5fb9a8d7ba97c1ff7336f9a62984f62596969d0` | `88a77248bb97f5f53f6849a409c945bc06309a9d1ac8bb2defb4eb514d71d04e` | `6116925c9415078a18f0b00b1ead1f18e1e6e73ad9c0965bff45904250f9f2e2` |
| `blue.bex:blue-bex-java:1.1.0-rc.3` | `c6deada2fac53b8ea6523dbda77597b128006674616f140f04df23264c6d1aa3` | `217e82c1c0ebf27ac0c279ecf1de7a6faa1092c7078f3af735b716472052855a` | `1fb8878f109c91bbcdbc3aa29f36d1c9e4c048d0357d011d146d12db60a1e042` | `c39806d158cd696e501240eed2c9e3c7ae73db706c6b52e204a2ba248f1d7ac5` | `c6deada2fac53b8ea6523dbda77597b128006674616f140f04df23264c6d1aa3` |
| `blue.repo:blue-repo-java:3.0.0-rc.21` | `c5bea287b3714db1478b058b18197b2626675a17bf420bee3672eaa14d7fae38` | `d8841891b363f4d129c5d0fa27918dce90cfca1dbe47d7a0af6de6972c9308eb` | `291fb13ecb8d904ebd082344312e69c0304a6d8bfc9cbc589696dec7e3bea6a1` | `95596afb7e2a3a6c8a127fcd6b32fd8addae00aca2e4b2be0a5227afdf899488` | `327f9ea0c6ab33de963584865625bd8db7c9714fd7dde45015dc5a2ddb59e8bf` |
| `blue.coordination:blue-coordination-java:3.0.0-rc.2` | `8f71c0dc6fef128639d8a63467dec3ee1628bb303540ae149f170512a73024fa` | `2d008123fb5fe17ad81e187cb705ab89133b03f99cfd7437f8f480d9fe22264b` | `6967dc45d16209654c346efb20d03fe51762a9d8ec7a7deb2e36ccb6a046fc54` | `0e1fdcfb9b41ef94b091e23a524ebb3565fbfd2be9b15f69aaadff4192ebd831` | `78f2b1ade77e9746a186847809708379038430f6f1a220387dd83d82cf29ec9a` |

The Coordination test-fixtures JAR SHA-256 is
`f6e61fd4ac620b366995dc061569c738ec55f9e4d899b4ab81f61cb76d8b93a6`.

Source ZIP hashes:

- Language: `45728c6b4d75c28fb8961240437a1b8319133c7239c57b4fe8c8352dea38111d`
  (`blue-language-java-3.1.0-rc.20-SNAPSHOT-source-release.zip`; the exact
  archive name is retained from the verified Language lane).
- BEX: `7e1acbc1ad2bc431ef643bb0bd1936a623e37a8b6ee69ae0e762f7a6355a43f8`.
- Coordination: `d1e5200634f2be548228499ccc10945c658dca55880b688a476f51f6f5e13c56`.
- Repository: its staging lane did not create a separate source ZIP; the
  sources and Javadocs JARs are bound above. This absence is explicit rather
  than silently represented as a passing ZIP gate.

## Recovered topology evidence

`PROVENANCE.md` SHA-256 is
`db1f29f226ec031f1153e7acdd683264c1fc3da3387511d5ca7f7fd16e395a8d`;
the export `SHA256SUMS` SHA-256 is
`f3b0ed16d7a6eda76162048a28c54e3674eedd2e2c488a77fe483b5124d4a7f6`.
That manifest binds complete Git bundles and exact source archives for
Language, BEX, Coordination (`f245270` and `d607571`), specification, and
Repository, plus the recovered coverage, identity, performance, and prior
receipt evidence.

The new short source-recovery rerun passed 18/18 focused topology tests under
Java 17 in 4m36s. No trustworthy exact 18-method selector was preserved;
`exactMethodInventoryPreserved=false`. This receipt does not invent names. It
separately binds the earlier committed selected gate: 15 classes, 57 tests,
57/57 green in 7m15s, with its exact command preserved in the recovered
`final-receipt.json`.

Exact document-step orders, gas, final component membership, document BlueIds,
topology identities, and structural counters remain in the immutable recovered
identity and coverage reports. Their relevant hashes are:

| Evidence | SHA-256 |
| --- | --- |
| `CYCLIC_TOPOLOGY_COVERAGE.md` | `5d6350a2303fbaca9924bdd0d67c8deda434a27a658987afd407db697e18c646` |
| `cyclic-topology-coverage.json` | `60e2111a404df56561c94e9fd26f16e6e681898e2c390d8d835e12ac9de4dc52` |
| `cyclic-topology-identities.md` | `a23f9b1c19630e9ca47afb7f2c675c3d233eda998453a52f9159db6fefea860f` |
| `cyclic-topology-identities.json` | `10b4e7b4788773ebd23915eafebf6790182d5557901b24e8f9f3c0d487b63470` |
| `cyclic-performance.md` | `8fadbc5780ee0d7f23c7b047c2a3c1c408323450b1ecf68c14e270cfe3cc88e9` |
| `cyclic-performance.json` | `63d65fc48f219c4e2f9be58ccb4028b4af0798e4a8d7585bdc20b4090984cd7e` |

The 73-minute campaign was not rerun. Its committed release wall targets and
1,000-unrelated semantic/gas equality passed, but it retained blockers for 57
broad global-state traversals, 100 broadly traversed entries, and raw BEX
cold/warm equality being unobservable. This receipt makes no latency SLA or
performance-conformance claim.

## SDK and staged verification

| Gate | Outcome | Count | Duration |
| --- | --- | ---: | ---: |
| Language clean Java 17 build | PASS | 2,859 tests | 11m37s |
| Direct closure corpus | PASS | 81 test invocations / 67 fixtures | 2m35s |
| BEX clean compatibility/reproducibility | PASS | 911 tests | 32s |
| Language local stage | PASS | 73 tasks | 15s |
| BEX local stage | PASS | 78 tasks | 24s |
| Repository local stage | PASS | 58 tests | 15s |
| Focused SDK supported subset | PASS | 23 tests | 1m28s |
| SDK detach/re-add addition | PASS | 2 tests | 1m01s |
| Focused built-JAR SDK consumer | PASS | 1 test | 12s |
| Full staged Java 17 primary corpus | PASS | 361 unit + 91 integration + 7 consumer + 14 scenario = 473 | see note |
| Extracted staged consumer, Java 17 | PASS | compile/run lane | combined verification below |
| Extracted staged consumer, Java 21 | PASS | compile/run lane | combined verification below |
| Repaired extracted-consumer verification | PASS | both JVM lanes | 22s |
| Terminal `sdkFreezeArtifactCheck` | PASS | artifact/dependency graph | 13s |
| Full staged Java 21 `releaseCheck` | PASS | 361 unit + 91 integration + 7 consumer + 14 scenario = 473 | 21m00s |

The exact Java 17 staged command was:

```text
./gradlew --no-daemon --max-workers=1 sdkFreezeArtifactCheck -PblueDependencyMode=staged-artifact -PblueStagingRepository=/private/tmp/blue-coordination-sdk-freeze.A5R15s/staged-repository -PblueSpecRoot=/private/tmp/blue-contracts-1.0-consolidation.btw1Cr/worktrees/blue-spec/latest -PtestJavaVersion=17 --no-parallel --no-build-cache --console=plain
```

Its first enclosing artifact-gate attempt ran 22m20s. The complete 473-test
`releaseCheck` was green before that attempt failed only at the original
same-directory Java 21 consumer orchestration. Therefore 22m20s is recorded as
the enclosing attempt duration, not misrepresented as an isolated
`releaseCheck` duration. Commit `2b6219a` isolated the consumer JVM lanes; the
repaired two-JVM consumer verification passed in 22s and the terminal artifact
check passed in 13s from clean HEAD.

The extracted consumer reports are independently bound:

- Java 17:
  `123b30c52ce898cf70861a9eed490ef0a3af545c57a1578b65c059f25843e2fe`.
- Java 21:
  `946d3fcb5adc75e6170a61f5be693a3efae5f286e83cd4e47a2756c3d6ad1b7c`.

Both resolve only the staged file repository and the exact component graph:
Coordination `3.0.0-rc.2`, Language `3.1.0-rc.20`, Repository `3.0.0-rc.21`,
and BEX `1.1.0-rc.3`. No sibling composite or Maven Local supplied those
consumer bytes.

The exact full staged Java 21 command was:

```text
./gradlew --no-daemon --max-workers=1 releaseCheck --rerun-tasks -PblueDependencyMode=staged-artifact -PblueStagingRepository=/private/tmp/blue-coordination-sdk-freeze.A5R15s/staged-repository -PblueSpecRoot=/private/tmp/blue-contracts-1.0-consolidation.btw1Cr/worktrees/blue-spec/latest -PtestJavaVersion=21 --no-parallel --no-build-cache --console=plain
```

It completed `BUILD SUCCESSFUL` in 21m00s with 35/35 actionable tasks
executed. XML reports bind 473/473 tests, with zero failures, errors, or skips.
The extracted source-archive smoke child completed `BUILD SUCCESSFUL` in 5s,
and the staged dependency graph was exact.

## Required SDK acceptance matrix

| # | Requirement | Status |
| ---: | --- | --- |
| 1 | Counter +3/-1 | PASS |
| 2 | Targeted Order does not process standalone PayNote | PASS |
| 3 | Valid unaccepted broadcast is terminal `NO_MATCH` | PASS |
| 4 | Missing exact target is precise `REJECTED` | PASS |
| 5 | Finite A-B-A | PASS |
| 6 | Finite A-B-C-A | PASS |
| 7 | Five-member shared-A SCC | PASS |
| 8 | Two disconnected SCCs | PASS |
| 9 | Gas-loop rollback and exact retry | PASS |
| 10 | Detach breaks loop and later call terminates | PASS |
| 11 | Remove/re-add has fresh activation identity | PASS |
| 12 | Create Order draft into `/orders` | **BLOCKED: `UNSUPPORTED_MANAGED_DRAFT_ADMISSION`** |
| 13 | Five child occurrences and duplicate managed lineages | **BLOCKED: `UNSUPPORTED_MANAGED_DRAFT_ADMISSION`** |
| 14 | Append-only `submit()` and separate `drain()` parity | PASS |
| 15 | Consumer compiles only against built/staged JARs | PASS |

The fail-closed managed-draft characterization occurs before append and leaves
host state unchanged. It is not a simulated pass through the legacy engine.
Completion requires a real Contracts host-invocation bridge that keeps exact
request content separate from stable managed identity and activation evidence,
validates the effective result occurrence path and exact state, rejects zero or
ambiguous matches, and admits the affected closure atomically.

## Remaining release and production gates

1. Implement the real managed-draft host-invocation bridge and make acceptance
   cases 12 and 13 pass without changing Contracts semantics.
2. Re-execute the complete artifact-bound acceptance/fixture corpus before any
   review of `implementationConformanceClaimed=true`.
3. Treat durable stores, fresh-process recovery, provider completeness,
   Mandates, tenant isolation, outbox recovery, backpressure, and operational
   scaling as separate production-profile work. The current candidate is
   single-JVM, in-memory, sequential, Root-scope, and has no stable latency SLA.

The local staged bytes are credible for controlled testing of the supported
subset. They are not authorization to publish and are not a production MyOS
release.
