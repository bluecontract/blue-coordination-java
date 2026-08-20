# Cyclic performance acceptance

- Overall: **FAIL**
- Authoritative: **false**
- Implementation conformance claimed: **false**
- Hardware baseline: `stabilization/cyclic-topology-round/baseline.json` (`1cfcbb840c8fcfd0244e2fdb44277fbf68eeeaf3a7efdbed866a4b78b3c4a5d2`, PASS)
- Generated: 2026-08-20T04:16:49.843914Z

## Frozen inputs

- Language specification: `sha256:01b038b64e3f0a9a11f3f70d544a63ff78a01d5169f1a03f8b8629cf73645a7d`
- Contracts specification: `sha256:dfb444962a5a17b3a6519e8d148c2bf4a975a921b1fcb1277710052caaecd930`
- Contracts release: `sha256:7e6c3717bc28d21ebadec9f81725913e944bb3b9b70094531f19f10510a10e50`

## Hardware and JVM identity

- Runtime OS: Mac OS X 26.5.2 (`aarch64`)
- Runtime JVM: Oracle Corporation 17.0.10 (`Java HotSpot(TM) 64-Bit Server VM`)
- Runtime processors / max heap: 16 / 2147483648 bytes
- JVM arguments: `[-Dblue.coordination.cyclicPerformance.output=build/reports/cyclic-performance-rc3-smoke, -Dblue.coordination.cyclicPerformance.samples=1, -Dblue.coordination.cyclicPerformance.warmups=0, -Duser.timezone=UTC, -XX:+UseG1GC, -Xms2g, -Xmx2g, -Dfile.encoding=UTF-8, -Duser.country=US, -Duser.language=en, -Duser.variant]`
- Baseline comparison: **PASS**; mismatches: `[]`
- Actual hardware: MacBook Pro Mac15,9, Apple M3 Max, 16 logical cores, 64 GB
- Actual OS build / JDK home: macOS 26.5.2 (25F84) / /Library/Java/JavaVirtualMachines/jdk-17.jdk/Contents/Home

The raw BEX result is **UNOBSERVABLE** at this boundary. The exact observable result projection is compared without representing it as raw BEX equality.

## Shape results

| Shape | Measured | Setup p95 | Admission p95 | Process p50 | Process p95 | Total p50 | Total p95 | Release gate | Semantic | Gas | BEX projection |
|---|---:|---:|---:|---:|---:|---:|---:|---|---|---|---|
|two-member-finite-cycle|1|1198914125|786549042|1013600125|1013600125|1059053167|1059053167|NOT_APPLICABLE|UNOBSERVABLE|UNOBSERVABLE|UNOBSERVABLE|
|three-member-ring|1|475958875|463411125|986055625|986055625|1022954208|1022954208|NOT_APPLICABLE|UNOBSERVABLE|UNOBSERVABLE|UNOBSERVABLE|
|five-member-shared-anchor|1|669092000|658865625|2384834541|2384834541|2417329083|2417329083|NOT_APPLICABLE|UNOBSERVABLE|UNOBSERVABLE|UNOBSERVABLE|
|two-disjoint-two-member-cycles|1|532311750|524112333|1363710708|1363710708|1394673917|1394673917|NOT_APPLICABLE|UNOBSERVABLE|UNOBSERVABLE|UNOBSERVABLE|
|five-member-plus-1000-unrelated|1|48685820708|48677245125|2343055583|2343055583|2373201000|2373201000|NOT_APPLICABLE|UNOBSERVABLE|UNOBSERVABLE|UNOBSERVABLE|
|cycle-detachment-and-dissolution|1|504395624|496226833|1110064417|1110064417|1170091584|1170091584|NOT_APPLICABLE|UNOBSERVABLE|UNOBSERVABLE|UNOBSERVABLE|

## Phase distributions

### two-member-finite-cycle

| Phase | Status | p50 | p95 | max |
|---|---|---:|---:|---:|
|operation.wall|PASS|1059053167|1059053167|1059053167|
|append.wall|PASS|45194042|45194042|45194042|
|drain.wall|PASS|1013858834|1013858834|1013858834|
|drain.reported|PASS|1013600125|1013600125|1013600125|
|append.total|PASS|44444875|44444875|44444875|
|process.routeLookup|PASS|1774666|1774666|1774666|
|contracts.closure.planConstruction|PASS|4703583|4703583|4703583|
|contracts.closure.processor|PASS|942902750|942902750|942902750|
|contracts.closure.resultValidation|PASS|215375|215375|215375|
|contracts.closure.publication|PASS|56263958|56263958|56263958|
|contracts.closure.managedDocumentStepInclusive|PASS|533204417|533204417|533204417|
|contracts.closure.managedDocumentStepExclusive|PASS|508229709|508229709|508229709|
|contracts.closure.componentFinalizationProof|PASS|33126208|33126208|33126208|
|contracts.closure.successfulResultAssembly|PASS|25837708|25837708|25837708|
|host.residual|PASS|7739793|7739793|7739793|

### three-member-ring

| Phase | Status | p50 | p95 | max |
|---|---|---:|---:|---:|
|operation.wall|PASS|1022954208|1022954208|1022954208|
|append.wall|PASS|36894708|36894708|36894708|
|drain.wall|PASS|986059375|986059375|986059375|
|drain.reported|PASS|986055625|986055625|986055625|
|append.total|PASS|36882083|36882083|36882083|
|process.routeLookup|PASS|90916|90916|90916|
|contracts.closure.planConstruction|PASS|1117375|1117375|1117375|
|contracts.closure.processor|PASS|933969125|933969125|933969125|
|contracts.closure.resultValidation|PASS|107167|107167|107167|
|contracts.closure.publication|PASS|50576125|50576125|50576125|
|contracts.closure.managedDocumentStepInclusive|PASS|567184416|567184416|567184416|
|contracts.closure.managedDocumentStepExclusive|PASS|532104082|532104082|532104082|
|contracts.closure.componentFinalizationProof|PASS|44834876|44834876|44834876|
|contracts.closure.successfulResultAssembly|PASS|23814542|23814542|23814542|
|host.residual|PASS|194917|194917|194917|

### five-member-shared-anchor

| Phase | Status | p50 | p95 | max |
|---|---|---:|---:|---:|
|operation.wall|PASS|2417329083|2417329083|2417329083|
|append.wall|PASS|32487791|32487791|32487791|
|drain.wall|PASS|2384841208|2384841208|2384841208|
|drain.reported|PASS|2384834541|2384834541|2384834541|
|append.total|PASS|32478708|32478708|32478708|
|process.routeLookup|PASS|87375|87375|87375|
|contracts.closure.planConstruction|PASS|1058292|1058292|1058292|
|contracts.closure.processor|PASS|2299945292|2299945292|2299945292|
|contracts.closure.resultValidation|PASS|89667|89667|89667|
|contracts.closure.publication|PASS|83408333|83408333|83408333|
|contracts.closure.managedDocumentStepInclusive|PASS|1669577460|1669577460|1669577460|
|contracts.closure.managedDocumentStepExclusive|PASS|1544070334|1544070334|1544070334|
|contracts.closure.componentFinalizationProof|PASS|141355751|141355751|141355751|
|contracts.closure.successfulResultAssembly|PASS|40239292|40239292|40239292|
|host.residual|PASS|245582|245582|245582|

### two-disjoint-two-member-cycles

| Phase | Status | p50 | p95 | max |
|---|---|---:|---:|---:|
|operation.wall|PASS|1394673917|1394673917|1394673917|
|append.wall|PASS|30958917|30958917|30958917|
|drain.wall|PASS|1363714917|1363714917|1363714917|
|drain.reported|PASS|1363710708|1363710708|1363710708|
|append.total|PASS|30950042|30950042|30950042|
|process.routeLookup|PASS|115959|115959|115959|
|contracts.closure.planConstruction|PASS|1306417|1306417|1306417|
|contracts.closure.processor|PASS|1291858333|1291858333|1291858333|
|contracts.closure.resultValidation|PASS|174125|174125|174125|
|contracts.closure.publication|PASS|68976583|68976583|68976583|
|contracts.closure.managedDocumentStepInclusive|PASS|747830666|747830666|747830666|
|contracts.closure.managedDocumentStepExclusive|PASS|710521250|710521250|710521250|
|contracts.closure.componentFinalizationProof|PASS|51397750|51397750|51397750|
|contracts.closure.successfulResultAssembly|PASS|32068832|32068832|32068832|
|host.residual|PASS|1279291|1279291|1279291|

### five-member-plus-1000-unrelated

| Phase | Status | p50 | p95 | max |
|---|---|---:|---:|---:|
|operation.wall|PASS|2373201000|2373201000|2373201000|
|append.wall|PASS|30138834|30138834|30138834|
|drain.wall|PASS|2343062084|2343062084|2343062084|
|drain.reported|PASS|2343055583|2343055583|2343055583|
|append.total|PASS|30122167|30122167|30122167|
|process.routeLookup|PASS|64625|64625|64625|
|contracts.closure.planConstruction|PASS|1000334|1000334|1000334|
|contracts.closure.processor|PASS|2244831625|2244831625|2244831625|
|contracts.closure.resultValidation|PASS|54541|54541|54541|
|contracts.closure.publication|PASS|96945292|96945292|96945292|
|contracts.closure.managedDocumentStepInclusive|PASS|1625965166|1625965166|1625965166|
|contracts.closure.managedDocumentStepExclusive|PASS|1505129082|1505129082|1505129082|
|contracts.closure.componentFinalizationProof|PASS|136639167|136639167|136639167|
|contracts.closure.successfulResultAssembly|PASS|39410709|39410709|39410709|
|host.residual|PASS|159166|159166|159166|

### cycle-detachment-and-dissolution

| Phase | Status | p50 | p95 | max |
|---|---|---:|---:|---:|
|operation.wall|PASS|1170091584|1170091584|1170091584|
|append.wall|PASS|60015416|60015416|60015416|
|drain.wall|PASS|1110075875|1110075875|1110075875|
|drain.reported|PASS|1110064417|1110064417|1110064417|
|append.total|PASS|59997584|59997584|59997584|
|process.routeLookup|PASS|141416|141416|141416|
|contracts.closure.planConstruction|PASS|1608125|1608125|1608125|
|contracts.closure.processor|PASS|976231251|976231251|976231251|
|contracts.closure.resultValidation|PASS|128709|128709|128709|
|contracts.closure.publication|PASS|131570291|131570291|131570291|
|contracts.closure.managedDocumentStepInclusive|PASS|409592374|409592374|409592374|
|contracts.closure.managedDocumentStepExclusive|PASS|393356415|393356415|393356415|
|contracts.closure.componentFinalizationProof|PASS|24241292|24241292|24241292|
|contracts.closure.successfulResultAssembly|PASS|37224166|37224166|37224166|
|host.residual|PASS|384625|384625|384625|


## Campaign gates

- **NOT_APPLICABLE** `authoritative-reference-configuration`: Iteration-count overrides are smoke-only and cannot be authoritative.
- **PASS** `hardware-baseline-binding`: Runtime hardware/JVM evidence is bound to stabilization/cyclic-topology-round/baseline.json.
- **NOT_APPLICABLE** `plus-1000-warm-total-wall-overhead`: Non-default iteration counts make this smoke evidence non-authoritative.
- **PASS** `plus-1000-affected-semantic-equality`: Corresponding iterations use identical affected IDs, timeline, timestamp, operation, and closure; only the 1,000 unrelated documents differ.
- **PASS** `plus-1000-affected-gas-equality`: Corresponding iterations use identical affected IDs, timeline, timestamp, operation, and closure; only the 1,000 unrelated documents differ.
- **PASS** `plus-1000-observable-result-equality`: Corresponding iterations use identical affected IDs, timeline, timestamp, operation, and closure; only the 1,000 unrelated documents differ.
- **UNOBSERVABLE** `raw-bex-cold-warm-equality`: Raw BEX results are not exposed; every shape separately gates its exact observable BEX projection.
- **NOT_APPLICABLE** `implementation-conformance-claim`: Campaign-local gates cannot promote the global implementation-conformance claim; the required staged/published exact-package lane is disabled by policy.

## Observed blockers

- **UNOBSERVABLE** `raw-bex-cold-warm-equality`: Raw BEX results are not exposed; every shape separately gates its exact observable BEX projection.
- **UNOBSERVABLE** `exact-contracts-semantic-cold-warm-equality`: Cold/warm equality cannot be established by comparing one iteration with itself.
- **UNOBSERVABLE** `exact-contracts-gas-cold-warm-equality`: Cold/warm equality cannot be established by comparing one iteration with itself.
- **UNOBSERVABLE** `exact-observable-bex-projection-cold-warm-equality`: Cold/warm equality cannot be established by comparing one iteration with itself.
- **FAIL** `broad-global-state-traversals`: This is the release-blocking broad traversal gate; the narrow FULL_ENVIRONMENT_SCANS counter is not a substitute.
- **FAIL** `broad-global-state-entries-traversed`: expected exact equality
- **UNOBSERVABLE** `raw-bex-result-equality-observability`: No raw BEX result fingerprint is exposed at the public engine boundary; only the exact observable closure projection is compared.

Raw samples, phase observability, counters, exact fingerprints, machine/JVM identity, and every gate are retained in `cyclic-performance.json`.
