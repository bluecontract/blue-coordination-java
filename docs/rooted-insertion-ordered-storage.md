# Selected insertion-ordered record storage

## Problem and example

Engine plans and source-discovery records currently use `LinkedHashMap`, whose
iteration order is insertion order, not key order. Replacing a value preserves
its original position; deleting and reinserting the key moves it to the end.
Some plan classes also use object identity equality. Consequently,
`remove(key, previouslyReturnedPlan)` must see the same owned instance, even
after unrelated records have been read. Reconstructing a structurally equal
value on every lookup or evicting that instance silently changes the contract.

`StoredInsertionOrderedMap` supplies a package-private physical component for
these existing maps. It does not yet replace any engine/SDK field.

## Exact physical behavior

Two authenticated AVL indexes retain key → (insertion sequence, payload
address/length) and insertion sequence → key. The selected snapshot retains both
root descriptors and the next insertion sequence. Each payload binds the
format/version, family, key ordering/codec identities, value codec identity,
exact encoded key, and complete closed-codec value bytes. No reflection, Java
serialization, arbitrary type registry, PROCESS, or provider authority factory
is involved.

An insert prepares immutable value dependencies only through the codec's
existing mutation-only `prepareForStorage` hook. It then writes the addressed
payload and both changed index paths, installing neither map root until all
writes succeed. Removal similarly stages both roots. Replacement retains its
old sequence. A failed write can leave unreachable immutable objects, but not
half a local map mutation. Clear retains the physical binding and next sequence.

Opening authenticates the root metadata and last sequence path, without reading
payloads or enumerating records. `size` and `isEmpty` use retained root metadata
without further object reads. Key iteration and iterator lookahead read only
index paths. `get`, entry/value iteration, and operations that return a previous
value decode only their selected records. Selected reads cross-check both index
memberships and verify payload hash, exact key, binding and canonical bytes.
Canonical checks never call `prepareForStorage` and never write objects.

Each opened map is one explicit `AutoCloseable` owner scope. A put pins the
actual supplied value instance; a first read pins its decoded instance. Pins
are never evicted. Replacement/removal releases that row's pin and close
releases all pins and map roots. If the configured pin-entry or encoded-byte
budget would be exceeded, the operation fails noncommitting before returning a
different instance or installing new roots. Byte charging counts retained
payload-frame bytes, not measured JVM heap. Metadata-node bounds and payload
bounds remain separate. No implicit global cache or semantic map-size limit is
introduced.

The supported records and keys are non-null; keys must be immutable and ordered
consistently with equality. Values are immutable closed records: an actual
state change must use `put`, not mutate a previously stored object in place.
Record codecs and any handle-rebinding context belong to the selected owner;
sharing a storage configuration across owners must not reuse a foreign runtime
capability. Basic operations are synchronized; this is an ordinary `Map`, not
a new concurrent-map protocol.

## Remaining owner integration

The caller must authenticate and fence each map's two roots and counter with its
actual selected read predicates, and close scopes at the owning runtime's end.
This component does not authorize one global publication root/CAS, authenticate
the semantic provenance of caller-supplied descriptors, or install engine/SDK
state. Opening does not globally scrub all cross-index rows: selected operations
fail closed if their paired rows differ. Complete cold factory wiring remains
separate work.

## Focused qualification

Java 17, offline, one Gradle worker: **15 tests passed**, zero failures/errors/
skips, plus Javadoc, in 16 seconds. Seven new tests exercise randomized
`LinkedHashMap` parity, exact identity/conditional removal, non-evicting pin
limits, cold point locality, no read-time prewrite, every physical write failure
in insertion/removal, missing/corrupt/noncanonical payloads, mixed roots,
counter validation, iterator mutation order and close. Eight existing stored
route/source tests also passed; these are owner reruns, not additional distinct
coverage beyond their original gate.

Source base: Coordination `ddb48275b9097179d15b5d2607dc4b2c09581716` plus this
isolated component. Matching immutable dependency sources: Language
`8542285144a8969f157d73e73292398885105c46`, BEX
`ab72af14ee54c6123e6d80349956af373a887680`, Catalog
`0b68744ba6456ef312d1ba87a27096bd2ac5341f`. This is not full SDK cold-recovery or
application qualification.
