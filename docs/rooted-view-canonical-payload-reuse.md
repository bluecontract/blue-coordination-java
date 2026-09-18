# Decode-local reuse of verified rooted-view payloads

Status: implementation candidate on `codex/poc-storage-codec-reuse`, based on
Coordination `568fc760260a804b40d9977913d7ffbfe6cd1202`. No qualification or
speedup is claimed yet. The frozen baseline and its PR are unchanged.

## Problem and evidence

The MyOS three-document ring completes with the resident runtime but misses its
maintained READY deadline with PostgreSQL/external state. A separately labelled
JFR diagnostic used unchanged MyOS `44db736`, the same Coordination568 artifacts,
the same384MiB application heap, and the original whole test. Its86-second
recording contains2049 writer execution samples; many visibly traverse nested
snapshot/result validation during `DocumentSessionStorage.decodeView`. These are
sampled stacks, not a wall-time decomposition or proof of eventual completion.
The original failure and raw recording are retained in the POC evidence directory
under `myos-poc-568-external-ring-jfr-02` and `myos-poc-568-jfr-ring.3te0ff`.

For example, reading one stored A1 view already invokes the Language codecs to
fully decode and canonical-round-trip its result, original snapshot and retained
snapshot. The previous outer validation then serialized those same immutable
members again, including their semantic snapshot checks, simply to reconstruct
the surrounding view envelope. Snapshot cross-link comparisons also re-encoded
the already verified side of the comparison.

## Change and correctness argument

Keep the three original nested payload byte arrays **only inside this decode**.
Each nested decoder still performs its complete validation and canonical check.
The original snapshot must still match the result's derived snapshot; the
retained snapshot must still match the independently derived publication witness.
`RootedDocumentView.restoreStored` still checks exact bodies, positions, routes,
components and publication owners. Those independent checks are not memoized.

For the final complete-envelope round trip, reuse only the bytes already proved
canonical for those exact immutable members. Encode every outer field normally
and compare the complete bytes, preserving route/map order, framing and trailing-
data rejection. Equality to the derived result/retained snapshot uses the verified
input bytes on one side; the independently derived side is still encoded and
checked. This is the same equality, not a BlueId-only substitute.

The payload holder is private and cannot be supplied by a caller. It cannot
escape into the returned view, another owner or publication. Existing record
bounds still apply; there is no new long-lived byte cache, public API, wire-format,
semantic, gas, scheduling or admission change. Ordinary retention and immutable-
write acknowledgements are unchanged.

## Alternatives and verification

An owner-wide view-encoding cache would require broader lifecycle/byte accounting
and would primarily address a different call path. Dropping snapshot validation
or accepting a matching hash alone would weaken correctness. Neither is done.
Language's separate per-call storage optimization may remove additional nested
duplication; this change does not depend on a new Language signature or format.

`DocumentSessionStorageTest.verifiedNestedViewPayloadsKeepFullCrossLinksAndCanonicalEnvelopeChecks`
uses real before/after processor results. It gives deliberately mixed result or
retained-snapshot records their **correct physical hashes**, and also tests damaged
nested bytes, a noncanonical outer boolean and trailing data. They must fail before
a valid read in the same scope; exact re-encoding, defensive Node copies and a
failed write acknowledgement remain checked. Existing complete-history, original
event, same-epoch representation, source-sharing, selected-only read, bounds and
next-root-action controls are unchanged.

Run the complete affected codec/storage owners together, then the unchanged
paired MyOS failures on the resulting sealed library tuple. Preserve all
deadlines, gas, heap and behavioral assertions. A reduced duplicate-work count is
not by itself an end-to-end speedup or full external acceptance.

Measured implementation inventory:309 production sources,84,338 lines (+19),
92 public API source types. The implementation-size ceiling records that exact
delta; processing/storage/runtime capacity limits are unchanged.
