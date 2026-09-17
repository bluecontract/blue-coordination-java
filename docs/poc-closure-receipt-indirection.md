# POC: small closure-index nodes, complete retained receipts

Status: the original `8c2ae43` candidate passed 223 library controls and 133 MyOS
controls, but the full ring failed its first-cycle readiness deadline. The
one-shot prepared-encoding follow-up below is provisional and has not been run.
Language/BEX/Catalog pins remain unchanged. Neither change is part of the
independently reviewed baseline.

## Problem and example

`publication/closure` stores complete publication receipts in a persistent AVL
map. Previously every node contained the full receipt value. Rebranching a node
after a sibling insertion changed only its child handles, but copied that whole
receipt into a new immutable object.

The preserved PostgreSQL ring diagnostic contains one unchanged 1,217,821-byte
receipt in 39 different nodes, and one 6,711,167-byte receipt in five nodes. These
counts include obsolete retained roots, not only currently reachable nodes.
Changing the node really changes its physical address, so a whole-object cache
does not remove this repetition.

## Correction

Retain the **same complete receipt envelope** as a separate immutable object.
The closure-index node contains a small descriptor: physical SHA-256 address and
exact encoded length. The map algorithm and PMN1 node format remain unchanged;
only its closure-value binding becomes `publication-index-row/closure/2`.

`prepareForStorage` retains the existing required views/results and then the exact
receipt object before a node may name it. Canonical `encode`/`decode` do not write.
Structural rebranching copies the descriptor, without opening the receipt.
Selecting a value performs a bounded point read, checks length and address, then
uses the existing complete receipt decoder, canonical check and owner-local view
registration. Actual publication-key and sibling-index membership checks remain.

A private single-entry memo preserves a **fully decoded and canonical-checked**
receipt's descriptor through round trips. Merely preparing or retaining a fresh
receipt does not certify its object identity: fresh encoding still takes the
ordinary validation/encoding path. This conservative boundary avoids assuming
deep ownership from public result types alone. In `8c2ae43` it costs an additional
fresh encoding, addressed by the one-shot follow-up below. The memo owns no
map of payloads, is cleared on close, and is neither a host-supplied certificate
nor publication authority. Existing verified receipt/result caches remain usable.

## Safety and trade-offs

- No changes to authored content, BlueIds, root selection, input order, epochs,
  catch-up semantics, emitted events, logical gas or failure policy.
- A new selected receipt needs one additional point read; the existing host byte
  cache may serve it. This trades an address lookup for avoiding large copies in
  every changed ancestor. No measured end-to-end speedup is claimed yet.
- The full receipt's original size bound still applies. A small reference is not
  permission to materialize an unlimited object or skip verification.
- Failed prewrites cannot publish a new root. Missing/conflicting/corrupt selected
  objects fail; an encoded address alone is not evidence of successful retention.
- Referenced objects are part of physical reachability. Future garbage collection
  must follow these references. The POC currently retains immutable objects; this
  change adds no deletion/GC mechanism.
- This is a private physical-format change. Old inline roots explicitly fail the
  binding check; they are not silently reinterpreted. New qualification uses a
  fresh POC schema and tests restart with the new format. Previous schemas and
  JARs remain preserved. No production migration is introduced.

## Rejected alternatives for this batch

A generic new persistent-map format is unnecessary: only the measured large
closure values need indirection now. Enlarging cache capacity cannot deduplicate
different physical node addresses. SELECT-before-INSERT can reduce duplicate
return traffic but cannot eliminate new nodes containing the same large value;
its previously unqualified patch is preserved separately, not enabled here.

This also does not solve the growing complete session revision envelope or define
a general durable verified-history-prefix API. Those costs must remain visible.

## Verification

The changed production sources passed a lightweight Java 17 compilation against
the previously qualified packaged dependencies. This is not a Gradle qualification
or a test result. The source-shape inventory is 313 Java sources / 85,291 lines
(+1 private source, +108 lines); public API inventories and per-source caps remain
unchanged.

`StoredClosureReceiptReferenceCodecTest` adds nine controls for valid large
receipts, insertion/deletion/rebalancing, bounded cold restoration, complete
result/receipt equality, missing/corrupt/foreign/oversized data, failed write and
bad acknowledgment, old-format rejection, owned descriptor bytes and close,
and the distinction between a prepared fresh value and a decoded certificate.

Qualification must also retain the complete existing publication/index/session,
engine/SDK, cache, gas and physical-failure owners, then bind the candidate into
MyOS for the full ring/restart and maintained paired acceptance corpus. A small
component test or interrupted diagnostic is not whole-POC acceptance.

Evidence: `processing-measurement13/RETENTION-FOLLOWUP.md` and
`ring01/physical-map-value-duplication.json` under the separate POC evidence root.

## One-shot prepared-encoding follow-up

**Problem / example.** `prepareForStorage` encoded a fresh receipt to retain its
payload. Map canonicalization then independently encoded the same fresh value
to derive the reference. Each fresh payload encode can call `viewAddress`, which
fully encodes each referenced historical view. Indirection removed large copies
from ancestor nodes but introduced this additional encoding pass. Its measured
share of the ring delay is not established.

**Correction.** A package-private `PreparedEncoding` holds either an ordinary
prepared value awaiting encoding or privately owned bytes for one mutation. The
default preserves prepare-key, prepare-value, canonical-key, canonical-value
ordering. The closure specialization prepares dependencies, encodes the payload
once, checks payload/descriptor bounds, retains it and verifies its exact ACK.
It supplies only the resulting descriptor bytes. The map still bounds/copies
those bytes, decodes them through the full reference/payload reader and compares
the canonical re-encoding before writing its node. Failed preparation yields no
usable encoding and failed canonicalization yields no new index root.

**Snapshot boundary.** The one-shot frame captures the value at encoding time;
later caller mutation does not alter its privately owned bytes. This does not
promise detection of concurrent changes after capture. A later independent
`encode(fresh)` still performs ordinary encoding and can reject changed input;
successful preparation never certifies that fresh Java identity. Each prepared
frame is consumed once and is not retained in a cache. Mutable sessions and
subsequent map mutations still require their own ordinary preparation.

**Why this approach.** Reintroducing the removed fresh-identity memo would assume
deep immutability not established by public result types. The one-shot frame
does not. It also avoids a view-address memo surviving unrelated encoding calls.
Unlike a naive combined prepare-and-encode default, it preserves generic
preparation/canonicalization ordering and associated failure/prewrite behavior.
There is no new public API, host trust flag, physical format or processing rule.

**Unqualified controls.** Five additional methods in
`StoredPersistentOrderedMapTest` and `StoredClosureReceiptReferenceCodecTest`
check ordering; detached and one-use bytes; size/canonical rejection before
node writes; actual snapshot mutation; the real fresh payload encoder changing
from two calls to one compared with the previous split path; exact root/payload
and complete receipt/result parity; and independent later fresh-input rejection.
Existing receipt bad-ACK/failure/old-root and cold corruption controls now execute
the new mutation path without changing their expected outcomes. No new tests or
JVMs have been run for this follow-up. Its exact source inventory is 313 Java
files / 85,332 lines, +41 lines in three existing private production files.

## Complete dependency-preparation frame reuse (cost36 follow-up)

The original 300-second ring gate still stopped at seven of fourteen authored
operations after the receipt point-lookup and batch-selection changes. Its
60-second JFR contained 310/767 worker execution samples inside snapshot
verification: 175 encoding-only, 73 decoding-only, and 62 without a visible
codec caller. These are inclusive samples, not operation counts or exclusive
timings. Fresh result encoding remained a measured cost.

One remaining duplicate was in the **payload preparation**, inside the previous
one-shot descriptor optimization: `StoredPublicationIndexes` completely encoded
the publication with `scope::retainView` to retain dependencies, discarded the
frame, and `StoredClosureReceiptReferenceCodec` immediately encoded it again.
The second pass could also re-encode historical views merely to derive addresses.

The payload codec now supplies the first complete frame through the existing
one-shot `PreparedEncoding`. The reference codec consumes that frame, bounds it,
retains it and checks the exact acknowledgment as before. Result-row retention
still completes before the payload is written. Explicit `prepareForStorage`
keeps its existing equivalent-value behavior. Independent later encoding,
selected physical reads, cold decoding and canonical checks are unchanged.

The focused control
`productionPreparationEncodesTheCompletePublicationOnceRatherThanDiscardingItsFirstFrame`
compares the real previous split path with the production path: **two full
publication encodes become one**, with identical receipt bytes and physical root.
It then cold-opens both outputs, checks the complete result/receipt and asserts no
read-time writes. Existing corruption, missing payload, bounds, bad acknowledgment,
rollback and fresh-identity controls apply to the same path. Qualification and
the next original-ring result are recorded separately; this is not an E2E claim.

No persistent fresh-result identity cache is added. Public result constructors
can retain `Node` subclasses through a virtual `clone()` method; a successful
encode alone must not become indefinite deep-immutability or decoded-result
authority. One-shot owned bytes avoid that assumption. No Language/BEX change,
new storage format, public policy or logical processing rule is involved.

## Packet-local invocation-frame reuse (cost36 follow-up)

**Problem / example.** A genuine rooted historical-representation publication
stores the same original invocation twice: once in its managed-surface evidence
and once in its terminal evidence. The `RootedTerminalTailSdkScenario` probe
produced a 1,397,258-byte receipt. With the production `RootedStorageCache` enabled,
instrumentation delegated every existing cache lookup and counted only actual
Language full-encoder fallbacks: four result encodes, one for the new result and
three for the retained source result. Two source-result encodes came from those
identical invocation slots. No duplicated historical-work slot was observed in
that receipt, so this patch does not add a work-encoding memo.

**Correction.** During one receipt encode, the first successful invocation frame
is reused only for the same Java input object. Equal but distinct inputs each
take the normal encoder path. During one receipt decode, the first fully decoded
invocation is reused only for byte-for-byte identical second input frames.
Neither scope survives the call or a failure. This eliminates one invocation
encode/decode; the same production-cache probe confirmed full result encodes
decreased from four to three with the same 1,397,258-byte frame. A warm result cache may already avoid some
nested result encoding; it does not itself avoid the invocation snapshot work.

**Safety.** These two receipt slots are read/written before any retained-view
callback. Terminal binding/result checks and complete-envelope canonical byte
equality still run. Changed second-frame bytes take their own full decoder path;
an equal hash, logical identity, or host assertion is insufficient. The restored
alias matches the original live receipt, whose two roles already share the input.
The ordinary outer byte/depth limits and every selected-view authority check
remain unchanged. No global object cache, fresh-result certificate, public API,
physical format, gas or scheduling change is introduced.

`PublicationInputFrameReuseTest` uses real SDK LIVE and historical publications
to measure shared versus equal-distinct inputs, preserve exact receipt bytes,
check cold canonical round trips, distinguish changed second frames, and check
failure/retry and tight envelope bounds. The new four-test owner passed in
`cost36/check07`; the 27 existing receipt/reference controls passed in `check06`.
The initial new failure test expected a view-retention callback that this fixture
does not need; it was corrected to exercise an actual late outer-frame size
failure and retry. Production code did not change after `check06`. This is narrow
qualification, not full-graph performance or whole-POC acceptance. Diagnostic evidence lives at
`processing-measurement13/cost36/receipt-encode-probe01` in the POC evidence root.
