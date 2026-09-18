# Rooted object-store physical slice

## Problem and concrete example

The e2ac0db rooted runtime has resident canonical exact bodies, independently
selected provider representations, and complete cyclic proofs. An inline child
and its provider-reference shell can share a BlueId. Saving one arbitrary body
under that BlueId therefore cannot restore the selected canonical/provider pair.
Saving only a cyclic member cannot restore the verifier's complete proof either.

This component adds a cold, named-object backing under the existing
`WholeObjectStore`. It does **not** add SDK persistence, restore document sessions,
publish an index, or execute commands during reopening. The existing in-memory
constructor remains the default. Rooted selection, identity verification,
publication obligations and gas rules are unchanged.

## Smallest boundary

`CoordinationImmutableObjectStore` accepts SHA-256-addressed opaque bytes and a
per-read allocation bound. The library checks exact write acknowledgement,
returned size and digest. Unknown metadata is absence; missing bytes referenced
by known metadata, corrupt records and I/O failure are noncommitting failures.

Internal `WholeObjectStorage.Index` is a caller-owned, immutable selected read
view: named entry address, named master-proof address, one master's complete
provider-member keys, and object count. Opening it reads no bodies. The owning
state/publication must authenticate and pin that metadata, keep it immutable for
the read scope, and atomically publish complete changed references and member
index additions. This slice deliberately does not prescribe its schema, root
descriptor, publication protocol, SQL, or SDK builder.

`WholeObjectStorage.retain` writes individually addressed entry/proof records and
returns only changed reference metadata. An entry keeps canonical value,
provider value, optional wire-preserving cyclic member, and diagnostic purpose.
Their values may have the same BlueId but different physical addresses across
versions. Complete master proof records stay separate. Prewrites are not
publication; interrupted preparation leaves only unreachable immutable bytes.

`WholeObjectStore` layers its existing mutation maps over the pinned backing.
Cold reads never populate those mutation maps. Nested marks restore only local
changes, exposing the earlier pinned values again. The cyclic compatibility
check reads members of that exact master, not an inventory of unrelated bodies.
Component retention rolls back its local delta if a late backing read fails.

## Exact scope and remaining limitation

This is **complete ExactValue/body/provider/proof component qualification, not SDK
recovery**. The original component at `e197fa6` deliberately refused resolver
snapshots on Language 7ec. The next slice uses Language-owned exact storage codecs
and removes the temporary 314-line body codec. `ExactValueStorageCodec` retains
ordinary frozen values, complete resolver snapshots, and independently verified
cyclic values as explicit, versioned lanes. Whole-object entries/proofs use v2;
old experimental byte formats are not silently reinterpreted.

For example, a resolved PaymentInstruction type has source, canonical and resolved
forms plus type-identity/provenance evidence. Rebuilding from just two bodies loses
that evidence despite retaining the same BlueId. A cold read now restores the
original snapshot without resolving its provider again. Mixed frozen construction
modes and shared children also survive; mutable cyclic wire/proof nodes use exact
Node transport rather than a normalizing frozen conversion.

Bounds remain physical host limits, never semantic gas outcomes. Unsupported
host-specific Java enums fail closed; no arbitrary class loading is used. A
transport roundtrip still does not make invalid semantic content valid: the
unpaired-surrogate control roundtrips transport and then checks that semantic
identity construction rejects it. Root/session/history, Timeline/intent,
scheduler, source-demand correlation and atomic host publication remain separate
integration work.

## Reuse and rejected shortcuts

The optional backing/overlay hooks are selectively reused from the earlier
`current-runtime-durable-coordination` physical slice. The byte/body encoding
pattern comes from `myos-coordination-external-state` at d4c7f47. No old semantic
selector, admission policy, SDK checkpoint format, persistent-map runtime or
whole-namespace CAS is imported.

Rejected alternatives: first-body-wins by BlueId loses representation selection;
flattening provider and canonical lanes loses inline reads; storing an asserted
cyclic BlueId loses proof; dropping resolver snapshots loses authority; scanning
or serializing every body defeats cold named reads; replaying the command ledger
is not restoration. None is used as a passing substitute.

## Proof

The focused owner covers new-instance canonical/provider separation, pinned old
views, reference upgrades, nested rollback, cyclic member/proof recovery, late
read failure atomicity, missing/corrupt selected bytes, unrelated corrupt bytes
remaining unread, complete resolver evidence, exact resolved/schema metadata, allocation
limits and false immutable acknowledgement.

One real rooted SDK scenario obtains an authored source body from the resident
provider and from a new cold-backed provider. It performs the same static
admission and selected live operation in newly constructed resident SDKs,
comparing complete result identities, the full ordered charge trace, and G/G−1
rollback. It demonstrates provider parity, **not restoration of SDK state**.
Existing resident object-store, rooted retained-source-wire and root-local
recovery owners run unchanged alongside it.

Exact source, dependency binding, commands, XML counts and source-stability
checks are recorded in [the component evidence receipt](rooted-whole-object-storage-evidence.json).

The complete exact-value follow-up ran on Language `d2ce037d`, with BEX `ab72af1`
and Catalog `0b68744` rebuilt unchanged against that exact Language artifact.
On 2026-09-11, 23/23 object-store controls plus Javadoc passed. This includes a
real resolver-produced PaymentInstruction snapshot reopened without its provider,
mixed frozen construction/sharing, canonical/provider separation, cyclic wire and
proofs, and the existing actual rooted SDK `G` / `G - 1` provider-parity control.
The first development run rejected the new test's fieldless Node before storage;
the fixture now uses Language's explicit empty-object value. No production rule
was relaxed. The earlier body-only receipt remains evidence for its original
source, not a claim about this changed Language tuple.
