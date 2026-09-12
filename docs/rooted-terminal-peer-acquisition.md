# Terminal-time causal peer acquisition (candidate)

This candidate starts at Coordination `9f71dff6efeece23938ee5dec4a74839cad5107a` in an isolated worktree. It requires Language's new `ClosureEvidenceFactory.rootedWitnessSelection` API, implemented at `00b745fea8b83eca4a2cfc7558048cf3ecff600d`. No build, test, export, or acceptance result is claimed here.

## Problem and concrete evidence

The original-stage peer selection/preflight is insufficient. In the archived 835-gas pair, both B originals fail after charging 828 with the same rejected charge, but the early invocation freezes closure `879e4f…` and trace `e7a759…`, while the prepared invocation freezes `24e476…` and trace `64f2c4…`. The successful-result preflight cannot prevent this failed expanded input from becoming terminal. The same-policy 836/837 cases and equal final charges do not excuse that difference.

The dormant reconnect independently demonstrates the ownership problem. Its real B-local C-to-D token sees D5 as an immutable witness outside the live calculating reach. D's own operation receives the token exactly once and publishes D23. At the final join, B23 still contains D5 while D23 contains B5. A17 and C21 agree in both authentic views. Neither missing reactions nor a need to rerun D's work explains this conflict. The original head/CAS guards correctly prevent publication of the stale peer.

## Proposed solution

Keep original LIVE input selection historical and ordinary, irrespective of independently published same-cause peer work. Remove peer frontloading, original-stage receiver ordering, and the post-success preflight. A completed original attempt, including a gas failure, follows the ordinary retained result and gas path.

At a genuine registered terminal, the normal scheduler first proves every receiving root has discharged its earlier/same-cause obligations. It gathers their actual local terminals without executing them. The unchanged `matches` and `captureRootedJoin` checks authenticate each candidate against the registered work, receipt, original external cause, frozen source, occurrence, cursor, consumer position, and boundary. Each selected peer must have a successful independently owned same-cause prefix, its exact current publication view, and its complete retained publication chain.

Only an immutable peer may be selected. A calculating dependency cannot be replaced by its independently published result. The frozen source and pending consumer are not acquisition candidates. The new Language factory retains the original root/calculating values, old full source proofs (for example A17 with D5), and entry-root presentation while selecting the independently proven D23 primary and its source-owned rows. This forms a **new terminal operation**, not a retry or mutation of an entered LIVE input.

The new private capture holds the original verified capture and every selected peer's original step/publication proof. It rechecks both on use. `RootedLocalHistory` then constructs the same terminal cause/work on that fresh snapshot; the ordinary registered capturer authenticates it again. Existing current-head, complete owner/occurrence/component, source-prefix, receipt, and atomic publication/CAS checks remain in force. No existing peer work is executed or charged again.

Unsupported fixed-inventory selection is an explicit nonterminal prerequisite: the candidate is excluded, not replaced with an ambient head and not confused with an exact no-op. Exact no-op comparison includes body, lifecycle, source-owned rows/cursor, epoch, BlueId, component generation, and current graph generation. Malformed proof failures are not blanket-caught. Added/removed occurrence IDs or new endpoints require further evidence; this candidate does not claim to solve them.

## Normative reason and scope

RCP OWN03 requires the exact causal view and publication fence before a proved live ownership expansion, preserving initial operation context/cause/meter. OWN02 keeps independently published source proof immutable. CAUSE01 freezes the historical endpoint, CAUSE03 and section 8.4 preserve calculation/failure behavior, and section 8.5 permits parent-first processing. This is library work because the host cannot change witness roles, fabricate a new verified input, reconcile Contracts ownership, or bypass the atomic publisher.

Rejected alternatives are blanket source-first scheduling, changing D5 inside A17's historical proof, replaying old tokens after join, changing source epochs, loosening CAS, discarding successful preflight gas, and broadening `rootedReadExpansion` to replace original primaries. The existing read-expansion contract remains unchanged. There is no new Coordination public API, durable record format, tariff, or cache.

## Prepared verification, not executed

The dormant regression and all business count/history/token/restart oracles remain unchanged. The absent-source diamond retains both physical schedules, reversed content-derived ordering, effect-free control, and exact 835/836/837 ledger comparisons. The ledger now asserts and compares actual processor-attempt/automatic-retry counts. Original LIVE calibration explicitly requires historical D, not the already completed peer primary. Former preflight-only assertions are replaced by charged ordinary original publication and zero-charge exact replay before/after restart.

Two new terminal controls use the actual normal selector and ordinary publisher: late measured G−1 after real retained WORK with no partial store change; and wrong original cause/later boundary, protected owner/frozen-source rejection, before-swap rollback, exact retry, same-input fresh-runtime result/gas parity, unchanged reaction counts, and replay/restart. Compact record rendering preserves full equality and JSON charge ledgers without megabyte assertion bodies. The later-boundary negative is not a claim that a future peer body was independently executed. Unsupported inventory and same-epoch no-op branches currently have source review plus the Language factory controls, not a new end-to-end Coordination fixture; those limits remain explicit.

Parent-owned first gate, after binding the exact new immutable Language artifact using the maintained dependency manifest properties:

```sh
./gradlew test --no-daemon --max-workers=1 \
  --tests blue.coordination.sdk.RootedDormantPeerReconnectTest \
  --tests blue.coordination.sdk.RootedDiamondPeerSchedulingTest
```

Then run the adjacent automatic/witness/SCC-entrypoint/publication safety controls and `RootedDuplicateOccurrenceHistoryReproductionTest.savedOriginalDuplicateOccurrenceKeepsItsFrozenRepresentationHistory`, followed by the full library shape/architecture/conformance gates and unchanged original MyOS acceptance. Preserve the parent runner's exact `blueDependencyMode`, `blueContractsVersion`, `blueContractsRepository`, and `blueContractsManifestSha256`; do not use an old runtime artifact, source composite, capability override, or invented release identity. No merge-ready or performance claim is implied by this draft.
