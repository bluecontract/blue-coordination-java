# Round 13 five-occurrence same-machine A/B

> **NO_POSTFLIGHT_TOOLING_CHANGES:** The tracked importer and receipt generator are the same frozen file, and the runner, current Round12NbaEvidence, candidate execution and harness surfaces, fixtures, locks, wrapper, composites, baseline archive and project, Java runtime, and physical host must match their exact preflight bindings through postflight, receipt generation, and independent verification. No postflight tooling or test-lane substitution is permitted.
>
> Postflight tooling changes: **no**. The campaign importer and receipt generator are the same frozen file: `e33c17c94f8cec762980375005e7632e1a7cd05b378adbfd38376d2fe721bf50`. Current Round12NbaEvidence: `98887e7b42b7f1c15f550dff7178ed241bd53e44c85b4702a2abe2106f45c07c` (24321 bytes, mode 0644).

Schema: `blue-coordination-round13-five-occurrence-runtime-v2`.

Campaign: `85fd124f-3235-4874-9143-fc379b37b924`. Entry Blue ID: `ENeZoCCotNoHUvJBTpg6JYu9854HP7rAbDNEGUJubeLW`.

Warmups: 3. Samples: 30 baseline + 30 candidate. Observed order: odd A/B, even B/A.

| Variant | Span | p50 ms | p95 ms | max ms |
| --- | --- | ---: | ---: | ---: |
| baseline | requestPreparationNanos | 96.757125 | 109.855584 | 122.837208 |
| baseline | appendNanos | 16.782541 | 19.348042 | 20.221625 |
| baseline | routeNanos | 0.062375 | 0.075208 | 0.076375 |
| baseline | embeddedInputPreparationNanos | 34.646542 | 37.111500 | 37.795750 |
| baseline | hostBeforeFrozenNanos | 39.582125 | 41.871376 | 44.445083 |
| baseline | processFrozenNanos | 2795.280292 | 2897.255416 | 2971.856500 |
| baseline | initializationFrozenNanos | 282.107709 | 307.495041 | 332.215668 |
| baseline | frozenNanos | 3079.054084 | 3187.656792 | 3252.091709 |
| baseline | hostAfterFrozenNanos | 405.517207 | 425.483790 | 425.643793 |
| baseline | initializationHostBeforeFrozenNanos | 107.375959 | 117.220625 | 121.929334 |
| baseline | initializationHostAfterFrozenNanos | 104.437751 | 116.218292 | 116.446376 |
| baseline | graphPublicationNanos | 554.138959 | 609.396334 | 632.298751 |
| baseline | coordinationHostNanos | 841.987252 | 893.884918 | 901.694875 |
| baseline | totalNanos | 3922.524333 | 4073.318000 | 4093.335417 |
| candidate | requestPreparationNanos | 96.784375 | 100.347792 | 101.985625 |
| candidate | appendNanos | 16.881958 | 18.680667 | 32.729000 |
| candidate | routeNanos | 0.061625 | 0.097583 | 0.137333 |
| candidate | embeddedInputPreparationNanos | 34.757083 | 36.940292 | 37.382377 |
| candidate | hostBeforeFrozenNanos | 39.558458 | 42.090460 | 45.703667 |
| candidate | processFrozenNanos | 2783.524999 | 2833.506916 | 2906.517667 |
| candidate | initializationFrozenNanos | 281.715751 | 303.047416 | 304.281708 |
| candidate | frozenNanos | 3065.391458 | 3132.828916 | 3210.799375 |
| candidate | hostAfterFrozenNanos | 402.915208 | 413.814374 | 423.630876 |
| candidate | initializationHostBeforeFrozenNanos | 108.290333 | 112.242209 | 119.242000 |
| candidate | initializationHostAfterFrozenNanos | 104.654584 | 109.720083 | 110.207584 |
| candidate | graphPublicationNanos | 552.389793 | 583.484999 | 622.061500 |
| candidate | coordinationHostNanos | 843.715582 | 872.356126 | 898.613917 |
| candidate | totalNanos | 3907.544583 | 3998.664166 | 4109.413292 |

## Structural counts

| Count | Baseline (every row) | Candidate (every row) |
| --- | ---: | ---: |
| wholeObjectsAdded | 4 | 4 |
| wholeObjectInsertions | 42 | 42 |
| wholeObjectDuplicates | 31 | 31 |
| journalEntries | 1 | 1 |
| documentCount | 4 | 4 |
| routeRows | 13 | 13 |
| embeddingBindings | 5 | 5 |
| childSessionsCreated | 3 | 3 |
| childSessionsReused | 2 | 2 |
| childInitializationCalls | 3 | 3 |
| childInitializationRevisions | 3 | 3 |
| externalProcessCalls | 1 | 1 |
| embeddedEpochProcessCalls | 5 | 5 |
| initializationApplications | 5 | 5 |
| initializationEventsForwarded | 5 | 5 |
| parentApplications | 5 | 5 |
| frozenProcessCalls | 6 | 6 |
| hostInitializationEvents | 5 | 5 |
| alphaInitializationEvents | 2 | 2 |
| betaInitializationEvents | 2 | 2 |
| gammaInitializationEvents | 1 | 1 |
| hostRevisionApplications | 5 | 5 |

## Forbidden-work counters

| Counter | Baseline (every row) | Candidate (every row) |
| --- | ---: | ---: |
| UNRELATED_DOCUMENT_READS | 0 | 0 |
| REQUEST_FRAGMENTS | 0 | 0 |
| TIMELINE_ENTRY_FRAGMENTS | 0 | 0 |
| ORDINARY_NODE_FRAGMENTS | 0 | 0 |
| FULL_ENVIRONMENT_SCANS | 0 | 0 |
| SOURCE_REPLAYS_PER_PARENT | 0 | 0 |
| POST_PROCESS_FULL_PROJECTIONS | 0 | 0 |
| PARENT_PROCESS_RERUNS_ON_GRAPH_RETRY | unavailable in Archive.zip | 0 |
| CHILD_PROCESS_RERUNS_ON_PARENT_RETRY | unavailable in Archive.zip | 0 |

## Candidate gates

| Candidate gate | Observed p95 ms | Preferred | Hard |
| --- | ---: | --- | --- |
| appendP95 | 18.680667 | MISS | FAIL |
| routeP95 | 0.097583 | MISS | PASS |
| coordinationHostP95 | 872.356126 | MISS | FAIL |
| totalP95 | 3998.664166 | MISS | PASS |

Overall: `FAIL`.

