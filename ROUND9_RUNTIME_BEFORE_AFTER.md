# Coordination `basicTest` Round 9 runtime report

## Outcome

Every Round 9 hard performance gate passes. Exact append and route lookup stay
sub-millisecond, Counter host overhead stays below 1 ms p95, and every large
scenario host span remains below its hard gate. Request, Timeline Entry, and
ordinary-node fragment calls are zero; complete post-PROCESS subscription
projections are zero.

The comparison baseline is the integrated Round 8 report supplied in the
Round 9 kit. Round 9 values were measured from the permanent source after the
identity-shell experiment was fully reverted.

## Thirty-sample campaign

| Scenario | Round 8 p95 | Round 9 p95 | Change | Round 9 result |
|---|---:|---:|---:|---|
| Tiny exact append | 0.128 ms | 0.120 ms | -6.4% | PASS |
| PayNote-sized exact append | 0.134 ms | 0.096 ms | -28.6% | PASS |
| One-Root route lookup | 0.023 ms | 0.023 ms | -2.2% | PASS |
| Counter frozen PROCESS | 263.986 ms | 256.287 ms | -2.9% | Frozen floor |
| Counter Coordination host | 0.877 ms | 0.846 ms | -3.5% | PASS |
| One versus 61 workflows, host delta | 2.571 ms | 2.798 ms | +8.8% | PASS |
| Existing child, 20 revisions | 41.297 ms | 39.289 ms | -4.9% | PASS |
| Late child, 20 source entries | 106.820 ms | 103.454 ms | -3.2% | Diagnostic target miss |
| Nested Root -> Emb1 -> Emb2 | 85.860 ms | 82.930 ms | -3.4% | Diagnostic target miss |
| NBA catch-up, five revisions | 93.645 ms | 93.138 ms | -0.5% | PASS |
| Live child fan-out to two parents | 32.333 ms | 33.536 ms | +3.7% | Diagnostic target miss |

The three diagnostic misses predate Round 9 and are not closure hard gates.
All three remain explicit failures in the generated campaign report; none
regressed by 20%.

## Strict large-document host gates

| Operation | Round 8 host | Round 9 host | Hard gate | Result |
|---|---:|---:|---:|---|
| Large host, cold | 12.653 ms | 12.133 ms | 25 ms | PASS |
| Large host, warm | 12.460 ms | 12.145 ms | 25 ms | PASS |
| PayNote authorization #1 + parent | 49.098 ms | 48.258 ms | 75 ms | PASS |
| PayNote authorization #2 + parent | 32.664 ms | 33.272 ms | 75 ms | PASS |
| Restaurant confirmation + parent | 31.234 ms | 31.531 ms | 75 ms | PASS |
| Attach and initialize PayNote | 673.730 ms | 647.403 ms | 800 ms | PASS |

The separate strict append parity run measured 0.062 ms tiny p95 and 0.071 ms
PayNote p95. The workflow scaling test measured 0.541 ms host p95 with one
workflow, 3.338 ms with 61 workflows, and a 2.797 ms delta.

## Frozen and user-visible latency

| Operation | Round 8 total | Round 9 total | Round 9 frozen | Round 9 host* |
|---|---:|---:|---:|---:|
| Large host, cold | 3,550.451 ms | 3,429.796 ms | 3,381.359 ms | 12.133 ms |
| Large host, warm | 3,561.825 ms | 3,437.406 ms | 3,388.683 ms | 12.145 ms |
| PayNote authorization #1 + parent | 5,575.373 ms | 5,374.147 ms | 5,288.068 ms | 48.258 ms |
| PayNote authorization #2 + parent | 5,378.096 ms | 5,158.185 ms | 5,088.384 ms | 33.272 ms |
| Restaurant confirmation + parent | 5,527.959 ms | 5,294.413 ms | 5,225.703 ms | 31.531 ms |
| Attach and initialize PayNote | 8,969.291 ms | 8,619.464 ms | 7,934.261 ms | 647.403 ms |

`*` Append and embedded-only layout are measured separately. For example,
PayNote authorization #1 also spends 31.516 ms appending and 5.576 ms in
layout. Frozen processing accounts for 98.4% of the complete 5.374-second
parent step. Attach spends 92.1% inside frozen processing.

## Work-shape evidence

The runtime campaign used 30 document samples and observed one exact commit
companion per successful frozen call. The strict large-host run observed:

```text
post-PROCESS complete projections       0
request splitter calls                  0
Timeline Entry splitter calls           0
ordinary-node splitter calls            0
```

No JFR run was required because every Round 9 hard gate passed. Detailed raw
campaign evidence is generated at
`build/reports/basicTest/runtime-comparison.{md,json}`.
