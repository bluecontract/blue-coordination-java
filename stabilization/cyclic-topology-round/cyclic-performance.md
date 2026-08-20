# Cyclic performance acceptance

- Overall: **FAIL**
- Authoritative: **true**
- Implementation conformance claimed: **false**
- Hardware baseline: `stabilization/cyclic-topology-round/baseline.json` (`1cfcbb840c8fcfd0244e2fdb44277fbf68eeeaf3a7efdbed866a4b78b3c4a5d2`, PASS)
- Generated: 2026-08-19T17:34:51.205355Z

## Frozen inputs

- Language specification: `sha256:01b038b64e3f0a9a11f3f70d544a63ff78a01d5169f1a03f8b8629cf73645a7d`
- Contracts specification: `sha256:dfb444962a5a17b3a6519e8d148c2bf4a975a921b1fcb1277710052caaecd930`
- Contracts release: `sha256:7e6c3717bc28d21ebadec9f81725913e944bb3b9b70094531f19f10510a10e50`

## Hardware and JVM identity

- Runtime OS: Mac OS X 26.5.2 (`aarch64`)
- Runtime JVM: Oracle Corporation 17.0.10 (`Java HotSpot(TM) 64-Bit Server VM`)
- Runtime processors / max heap: 16 / 2147483648 bytes
- JVM arguments: `[-Dblue.coordination.cyclicPerformance.output=/private/tmp/blue-contracts-1.0-consolidation.btw1Cr/worktrees/blue-contract-java/stabilization/cyclic-topology-round, -Dblue.coordination.cyclicPerformance.samples=50, -Dblue.coordination.cyclicPerformance.warmups=20, -Duser.timezone=UTC, -XX:+UseG1GC, -Xms2g, -Xmx2g, -Dfile.encoding=UTF-8, -Duser.country=US, -Duser.language=en, -Duser.variant]`
- Baseline comparison: **PASS**; mismatches: `[]`
- Actual hardware: MacBook Pro Mac15,9, Apple M3 Max, 16 logical cores, 64 GB
- Actual OS build / JDK home: macOS 26.5.2 (25F84) / /Library/Java/JavaVirtualMachines/jdk-17.jdk/Contents/Home

The raw BEX result is **UNOBSERVABLE** at this boundary. The exact observable result projection is compared without representing it as raw BEX equality.

## Shape results

| Shape | Measured | Setup p95 | Admission p95 | Process p50 | Process p95 | Total p50 | Total p95 | Release gate | Semantic | Gas | BEX projection |
|---|---:|---:|---:|---:|---:|---:|---:|---|---|---|---|
|two-member-finite-cycle|50|284893958|277252000|652753833|667336916|683270458|698536916|PASS|PASS|PASS|PASS|
|three-member-ring|50|391553792|384161917|910449458|920156792|941872166|952255667|PASS|PASS|PASS|PASS|
|five-member-shared-anchor|50|673523833|666641042|2273245167|2301077667|2304853084|2332671458|PASS|PASS|PASS|PASS|
|two-disjoint-two-member-cycles|50|550685750|543774750|1291979667|1311160334|1323037833|1344114459|NOT_APPLICABLE|PASS|PASS|PASS|
|five-member-plus-1000-unrelated|50|51982734625|51976046875|2286105250|2306223333|2317463125|2336946083|NOT_APPLICABLE|PASS|PASS|PASS|
|cycle-detachment-and-dissolution|50|552378584|545138209|1106215500|1153295916|1168600584|1216267833|NOT_APPLICABLE|PASS|PASS|PASS|

## Phase distributions

### two-member-finite-cycle

| Phase | Status | p50 | p95 | max |
|---|---|---:|---:|---:|
|operation.wall|PASS|683270458|698536916|720232875|
|append.wall|PASS|31035625|32880750|34770125|
|drain.wall|PASS|652756958|667339500|688258542|
|drain.reported|PASS|652753833|667336916|688255666|
|append.total|PASS|31025500|32871459|34759375|
|process.routeLookup|PASS|75666|113667|119042|
|contracts.closure.planConstruction|PASS|601500|718958|778250|
|contracts.closure.processor|PASS|616884125|630518000|652366917|
|contracts.closure.resultValidation|PASS|50917|71250|74416|
|contracts.closure.publication|PASS|35513500|36905042|37633375|
|contracts.closure.managedDocumentStepInclusive|PASS|399008876|409990583|435968292|
|contracts.closure.managedDocumentStepExclusive|PASS|379987875|390557626|415327917|
|contracts.closure.componentFinalizationProof|PASS|25741958|27211499|27920417|
|contracts.closure.successfulResultAssembly|PASS|15582792|17170292|17530208|
|host.residual|PASS|134832|190041|337791|

### three-member-ring

| Phase | Status | p50 | p95 | max |
|---|---|---:|---:|---:|
|operation.wall|PASS|941872166|952255667|964004875|
|append.wall|PASS|31308958|32555584|33136250|
|drain.wall|PASS|910452917|920160417|932332625|
|drain.reported|PASS|910449458|920156792|932329584|
|append.total|PASS|31294375|32544250|33128542|
|process.routeLookup|PASS|75750|95709|121083|
|contracts.closure.planConstruction|PASS|680375|753834|895250|
|contracts.closure.processor|PASS|860544708|869583042|882018833|
|contracts.closure.resultValidation|PASS|37625|59292|78542|
|contracts.closure.publication|PASS|49161125|51155916|51498750|
|contracts.closure.managedDocumentStepInclusive|PASS|590591583|596390959|600340793|
|contracts.closure.managedDocumentStepExclusive|PASS|556357957|562220043|566332501|
|contracts.closure.componentFinalizationProof|PASS|43541999|44924501|45239709|
|contracts.closure.successfulResultAssembly|PASS|20922417|21701250|43527375|
|host.residual|PASS|138126|216041|237042|

### five-member-shared-anchor

| Phase | Status | p50 | p95 | max |
|---|---|---:|---:|---:|
|operation.wall|PASS|2304853084|2332671458|2332754584|
|append.wall|PASS|31195042|32836000|33972666|
|drain.wall|PASS|2273249333|2301081209|2302006709|
|drain.reported|PASS|2273245167|2301077667|2302002166|
|append.total|PASS|31180375|32826666|33964250|
|process.routeLookup|PASS|66750|90042|133625|
|contracts.closure.planConstruction|PASS|874500|1010708|1068167|
|contracts.closure.processor|PASS|2189094583|2215532500|2216884750|
|contracts.closure.resultValidation|PASS|43291|56417|61292|
|contracts.closure.publication|PASS|83505292|86826625|87826875|
|contracts.closure.managedDocumentStepInclusive|PASS|1771146916|1791672834|1797370627|
|contracts.closure.managedDocumentStepExclusive|PASS|1646196917|1664509000|1672356001|
|contracts.closure.componentFinalizationProof|PASS|140902211|143420457|146558583|
|contracts.closure.successfulResultAssembly|PASS|38653458|40133041|40991459|
|host.residual|PASS|140166|175376|209501|

### two-disjoint-two-member-cycles

| Phase | Status | p50 | p95 | max |
|---|---|---:|---:|---:|
|operation.wall|PASS|1323037833|1344114459|1358655833|
|append.wall|PASS|31266000|32905083|33318125|
|drain.wall|PASS|1291983791|1311167084|1326551542|
|drain.reported|PASS|1291979667|1311160334|1326545584|
|append.total|PASS|31258750|32895500|33299917|
|process.routeLookup|PASS|78875|118875|207042|
|contracts.closure.planConstruction|PASS|980667|1223250|1344458|
|contracts.closure.processor|PASS|1220486958|1238253542|1252168166|
|contracts.closure.resultValidation|PASS|85708|100750|122875|
|contracts.closure.publication|PASS|69783417|72443333|72877459|
|contracts.closure.managedDocumentStepInclusive|PASS|792081083|803902125|811396917|
|contracts.closure.managedDocumentStepExclusive|PASS|754018502|765170708|772992376|
|contracts.closure.componentFinalizationProof|PASS|51319875|53124041|53141626|
|contracts.closure.successfulResultAssembly|PASS|30716916|32206916|32812125|
|host.residual|PASS|214750|284835|430125|

### five-member-plus-1000-unrelated

| Phase | Status | p50 | p95 | max |
|---|---|---:|---:|---:|
|operation.wall|PASS|2317463125|2336946083|2346263708|
|append.wall|PASS|31000416|33087666|33875500|
|drain.wall|PASS|2286109667|2306228458|2314370500|
|drain.reported|PASS|2286105250|2306223333|2314365750|
|append.total|PASS|30995042|33084375|33869792|
|process.routeLookup|PASS|61542|79125|109125|
|contracts.closure.planConstruction|PASS|824000|1034083|1102542|
|contracts.closure.processor|PASS|2185441500|2206434916|2212103167|
|contracts.closure.resultValidation|PASS|24875|56292|120208|
|contracts.closure.publication|PASS|98927541|101244417|101876792|
|contracts.closure.managedDocumentStepInclusive|PASS|1768764873|1781805752|1790104792|
|contracts.closure.managedDocumentStepExclusive|PASS|1642707958|1656999001|1662727793|
|contracts.closure.componentFinalizationProof|PASS|141275416|143828292|145211291|
|contracts.closure.successfulResultAssembly|PASS|38488417|39944542|40292833|
|host.residual|PASS|139874|220125|341291|

### cycle-detachment-and-dissolution

| Phase | Status | p50 | p95 | max |
|---|---|---:|---:|---:|
|operation.wall|PASS|1168600584|1216267833|1231632125|
|append.wall|PASS|62049541|64387500|72916916|
|drain.wall|PASS|1106225000|1153301667|1168589750|
|drain.reported|PASS|1106215500|1153295916|1168582958|
|append.total|PASS|62041417|64379791|72908875|
|process.routeLookup|PASS|118668|151125|186001|
|contracts.closure.planConstruction|PASS|1432501|1787709|2941875|
|contracts.closure.processor|PASS|966731709|1011540917|1024673334|
|contracts.closure.resultValidation|PASS|49708|65750|85166|
|contracts.closure.publication|PASS|138960000|143930000|149248208|
|contracts.closure.managedDocumentStepInclusive|PASS|371970167|388947750|393148167|
|contracts.closure.managedDocumentStepExclusive|PASS|355151334|371769457|372237959|
|contracts.closure.componentFinalizationProof|PASS|24611874|28119083|33877582|
|contracts.closure.successfulResultAssembly|PASS|35123292|38113625|39355667|
|host.residual|PASS|187331|219583|253583|


## Campaign gates

- **PASS** `authoritative-reference-configuration`: The 20/50 run uses the required Java 17, 2 GiB heap, G1, locale/timezone, and frozen reference machine.
- **PASS** `hardware-baseline-binding`: Runtime hardware/JVM evidence is bound to stabilization/cyclic-topology-round/baseline.json.
- **PASS** `plus-1000-warm-total-wall-overhead`: p95 locality end-to-end operation wall versus p95 five-member end-to-end operation wall
- **PASS** `plus-1000-affected-semantic-equality`: Corresponding iterations use identical affected IDs, timeline, timestamp, operation, and closure; only the 1,000 unrelated documents differ.
- **PASS** `plus-1000-affected-gas-equality`: Corresponding iterations use identical affected IDs, timeline, timestamp, operation, and closure; only the 1,000 unrelated documents differ.
- **PASS** `plus-1000-observable-result-equality`: Corresponding iterations use identical affected IDs, timeline, timestamp, operation, and closure; only the 1,000 unrelated documents differ.
- **UNOBSERVABLE** `raw-bex-cold-warm-equality`: Raw BEX results are not exposed; every shape separately gates its exact observable BEX projection.
- **NOT_APPLICABLE** `implementation-conformance-claim`: Campaign-local gates cannot promote the global implementation-conformance claim; the required staged/published exact-package lane is disabled by policy.

## Observed blockers

- **UNOBSERVABLE** `raw-bex-cold-warm-equality`: Raw BEX results are not exposed; every shape separately gates its exact observable BEX projection.
- **FAIL** `broad-global-state-traversals`: This is the release-blocking broad traversal gate; the narrow FULL_ENVIRONMENT_SCANS counter is not a substitute.
- **FAIL** `broad-global-state-entries-traversed`: expected exact equality
- **UNOBSERVABLE** `raw-bex-result-equality-observability`: No raw BEX result fingerprint is exposed at the public engine boundary; only the exact observable closure projection is compared.

Raw samples, phase observability, counters, exact fingerprints, machine/JVM identity, and every gate are retained in `cyclic-performance.json`.
