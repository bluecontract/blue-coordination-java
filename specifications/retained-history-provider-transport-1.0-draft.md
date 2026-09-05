# Retained history provider transport 1.0 — development extension

This extension defines evidence transport only. It does not authorize importing
source history into a Coordination engine, replacing Contracts verification,
or reconstructing receipts. It is separate from the published rc.5 specification.

An application must independently trust the producer's Ed25519 public key and
bind the source DocumentId, authored-initial BlueId and attachment predecessor.
A self-consistent receipt hash alone is not execution authority. The supplied
runtime producer signs only receipts read from its own committed store.

Each request identifies one source DocumentId, the first nonnegative epoch,
a limit between 1 and 256, and a challenge. A new read uses a fresh challenge.
An exact retry may reuse its request; a reused response must not be used to
claim a newly observed source head.

A response signs the request, authored-initial identity, captured head epoch
and BlueId, predecessor BlueId, and the complete ordered receipt identities.
The signature algorithm is Ed25519. The transcript is the sequence emitted by
`RetainedHistoryPage.transcript`: Java DataOutputStream `writeUTF` of the domain
`blue-coordination-retained-history-page/1.0`, source DocumentId; `writeLong`
of first epoch; `writeInt` of limit; `writeUTF` of challenge and authored initial;
`writeLong` of head epoch; `writeUTF` of head and predecessor BlueIds;
`writeInt` of receipt count; then `writeUTF` of every canonical receipt identity.
Integer encodings are big endian; `writeUTF` uses length-prefixed modified UTF-8.

The count must equal `min(limit, headEpoch - firstEpoch + 1)`. Receipt epochs
must be contiguous and increasing, with the exact requested source identity.
Every non-initialization receipt must link to its predecessor's after BlueId.
Epoch zero retains the separate authored-initial anchor. A page reaching the
captured head must have that exact final BlueId. The consumer verifies the
request, lineage anchor, predecessor and signature before using any receipt.

Receipt identity binds complete ordered source emissions, including distinct
ordinals for repeated identical payloads. Content equality never collapses
epochs, event occurrences, or managed occurrences. `TIMELINE_ENTRY` is not
reclassified as `EVENT_ONLY` by this protocol.

Missing retained evidence, an unavailable requested epoch, or a head changing
during capture yields `RetainedHistoryProvider.Unavailable`. No partial page
is signed. This is not invalid evidence or proof of historical absence.
Malformed, reordered, duplicated, gapped, differently attributed or wrongly
signed responses fail verification. A failed read/verification changes no
source or consumer state.

A page head is a captured observation, not a frozen catch-up target. The
consumer must request again when evaluating live readiness. The adapter owns
no durable application cursor, receipt import, event publication, or database.
It performs at most `limit + 1` source-receipt reads and two local head reads
per successful request. It never scans unrelated lineages or runs PROCESS.
This is an execution-work bound, not a byte, wall-clock, or semantic-gas bound.

Component representation changes that break a direct receipt chain require
additional authenticated component evidence and are outside this transport
profile. A signature on public receipt identities does not substitute for a
Contracts transition body, initialization evidence, complete cyclic proof, or
atomic publication contract required by a future runtime importer.
