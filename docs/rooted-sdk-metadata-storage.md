# Exact rooted SDK metadata component

## Problem and boundary

An engine document/session snapshot alone cannot restore the SDK's retained
submission behavior. `SdkCoordinationRuntime` separately owns registered Timeline
handles (including actor kind), targeted/broadcast entry intents, retained entry
results, the core journal entries required by later processing, and completed
source-history processing results. Losing these maps can change a repeated
submission or omit an original noncommitting gas result even when document heads
look correct.

This change adds a package-internal, bounded, versioned metadata component. It is
not a complete SDK restore API or a new semantic authority. A future engine
restore must supply the exact verified engine before nonempty metadata can be
installed; the current runtime still constructs a new engine normally. No
registration, append, PROCESS, command replay or provider replay substitutes for
engine restoration here.

## Preserved state and validation

`SdkStorageCodec` explicitly transports the five maps and the exact runtime
configuration: language/contracts identities, selected Contracts execution
policy, content-derived document-ID mode, bundled-release mode and all five
bundled release manifest identities. Current SDK result DTOs include complete
root-local and managed attempts, representations, failures, rejected gas
prefixes, dispositions and diagnostics. These maps contain SDK presentation
records, not raw Language `ClosureProcessResult`; raw result and witness storage
belongs to the engine/session component.

Physical frames use the Language exact envelope and Coordination exact-value
codec, owned byte copies, bounded counts/depth, closed tags, exact UTF-16 strings,
arbitrary integer order-key components, canonical re-encoding and EOF/checksum
validation. There is no reflection, Java serialization or dynamic class loading.
The explicit DTO mappings selectively reuse the earlier experimental codec;
its old engine checkpoint and unrooted result reconstruction are not reused.

Install requires pristine SDK metadata and validates every field before mutating
the new owner. Configuration is compared with the actual engine bootstrap, not
an externally asserted policy. The new point-only `auditRegisteredTimeline`
distinguishes a registered empty Timeline from an absent one; the actual
`ContractsRuntimeBinding` rejects non-Contracts engines. Neither registers data.

Core entries are stored as complete data snapshots, never reconstructed with a
plain `TimelineEntry` constructor. Install resolves each through the restored
engine's verified journal and compares exact value, request, order keys, actor,
operation, channel and sequence/timestamp coordinates. Result/source-result
handles are rebound to a fresh SDK owner and checked against the same journal.
Every submitted intent must have its required core entry. Advanced engine
projections may legitimately have result entries without an SDK submission;
the maps are not forced to have identical key sets. No new promise is made that
such advanced handles support submission-only operations.

Missing, malformed, mismatched or unsupported physical state fails
noncommitting, before installation changes any metadata. Existing foreign-owner
handle rejection remains unchanged. Close releases the source-result map too.

## Focused evidence and limits

On 11 September 2026, `SdkStorageCodecTest` passed **7/7 tests**, zero failures,
errors or skips, together with strict Javadoc in 17 seconds. The exact immutable
tuple was Language `d2ce037d`, BEX `ab72af1` and Catalog `0b68744`, with BEX and
Catalog re-exported against that Language artifact. Java 17, offline, one worker
and no parallel tasks were used.

The genuine SDK fixtures cover a rooted cyclic GAS_LIMIT_EXCEEDED result and a
source-owned LIVE result with its separately retained blocked parent, file-byte
roundtrip, fresh handle ownership, exact gas/diagnostic/history preservation and
unchanged PROCESS timer. Additional controls cover targeted intents, actor
kinds, registered-empty Timelines, pristine empty-owner install, configuration
and missing-engine rejection, malformed/oversize bytes, mutable-copy isolation
and a missing submitted core entry. A separately labeled DTO control exercises
all managed failure/representation fields; it is not a manufactured processor
publication proof.

This gate does not establish nonempty restored-engine installation, fresh-JVM
whole SDK recovery or duplicate execution after recovery. Those require the
remaining engine and document/session storage components and actual cold tests.
The event-evidence no-provider correction is independently qualifying in
Language; this SDK metadata gate does not claim it qualifies that later artifact.
