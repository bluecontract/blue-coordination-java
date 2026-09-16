# Exact source-discovery recovery

The results below belong to the original donor. The current d220-based port adds
opaque local-step/peer proofs and prepared-frame v2; see
[the current adaptation](rooted-external-state-baseline-port.md#necessary-adaptation-retained-submitted-historical-steps).

## Problem and example

An Order can stop on a processor-issued demand for an authored Agreement. The
host selects a separate Agreement admission or progress operation, and may lose
the response after that source operation commits. Recreating the demand from its
public fields, selecting against a later head, or admitting the Agreement again
does not restore the original operation.

## Change

The private `SourceDiscoveryStorageCodec` retains the actual stopped cohort,
original input/retry/attempt and selected demand member. A submitted selection
retains its exact admission, LIVE batch, managed historical work, or rooted
historical step, together with the original completeness boundary and descriptor.
Shared rooted views use the existing explicit restoration scope. Decoding does
not call a provider, select work, run Contracts, or publish either document.

The admission compiler and coordinator expose only package-private storage
access. The exact occurrence mapping is shared without changing its bytes.
Reference equality for the selected demand is reconstructed from its original
attempt, not accepted from an independently constructed public lookalike.

The original submitted operation can therefore reconcile against its real
committed receipt after response loss. A final document body or a freshly
compiled admission is not an alternative: neither identifies that operation's
original input, gas, or retained publication evidence.

## Verification and boundary

Java 17, coherent immutable Language `8542285144a8969f157d73e73292398885105c46`,
BEX `ab72af14ee54c6123e6d80349956af373a887680`, Catalog
`0b68744ba6456ef312d1ba87a27096bd2ac5341f`: **27/27 plus Javadoc**, 34 seconds.
Six new controls cover cold pending admission after producer closure, exact LIVE
selection, managed historical and rooted historical selections, malformed/wrong
authority, and actual cold-row admission with lost-response reconciliation.
The last control compares full source evidence and verifies unchanged parent
state and no second admission. The group also contains 17 unchanged SDK source
prerequisite controls and four existing occurrence-index controls.

This is exact component storage, not a complete cold engine. The completed-result
map, publication indexes, source-map installation and engine/SDK factory still
need to be connected to one authenticated host publication. The existing
incomplete-control export guard remains in place.

Raw results: `rooted-source-discovery-evidence.4dEp5s/source-discovery-27-pass.tar.gz`.
