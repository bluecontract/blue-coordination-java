# Private session revision delta storage

The private `blue-coordination/document-session-storage/2` frame replaces inline
revision bodies with an ordered list of SHA-256 addresses. Each address selects a
complete `blue-coordination/document-revision-storage/1` frame, including exact
before/after values, original source and causal evidence, events, gas and any
managed-epoch receipt. All other session fields retain their existing encoding.
This is a physical recovery format change, not a semantic receipt or public wire
format change.

An explicitly opened runtime scope remembers the immutable revision objects it
fully decoded. A retention stage may also remember objects it successfully
prewrote. A detached session copy shares those immutable objects. Appending epoch
N+1 therefore writes the new revision frame and current session metadata, without
rewriting the N old revision bodies. A same-epoch representation change writes
metadata only when every selected dependency is already retained. Metadata still
contains O(N) ordered references and indexes; current/ready layouts and other
existing metadata are still encoded normally. This does not claim O(delta) reads,
hashing, session invariant checks, or total operation cost.

Reuse never follows an asserted epoch, identifier, host flag or watermark. Every
selected old revision is physically read under the current record bound and its
content address authenticated before retention skips its write. Every new open
also reads and authenticates each selected revision, even on a same-owner or
process-cache hit. Missing, corrupt, oversized or unavailable bytes fail. A
process-cache artifact is admitted only after the complete revision codec and
canonical byte round trip succeed; it is not evidence of session membership.
Current session restoration, contiguous history, document/epoch/order binding,
indexes, same-epoch representation history, layouts, rooted links and the complete
session canonical envelope are checked independently on every open.

All revision and view dependency bytes count toward the restoration scope limit;
all distinct selected dependencies plus metadata count toward the retention scope
limit, including old dependencies that need no PUT. Each frame has the ordinary
record bound. Separating revisions allows total history to exceed a single-record
bound, but not the aggregate scope bound. All serialization and current dependency
checks finish before any PUT. Every current session metadata write still requires
an exact acknowledgement. Failed retention supplies no stage-local evidence;
closing the stage or scope discards its address associations. Prewritten immutable
orphans after a later failure do not publish a session reference.

The reader continues to accept `document-session-storage/1`, including its original
inline canonical verification. Its first subsequent retention writes the complete
revision set in the new format; no old-format reference is changed in place.
Unscoped retention and newly constructed equivalent objects take the ordinary full
encode/prewrite fallback. Cache-disabled, evicted and cold-process operation remain
valid. Neither version is an engine/SDK publication authority.

Controls are in `DocumentSessionRevisionStorageTest` (delta physical bytes/PUTs,
same-epoch representation, warm missing/tampered/unavailable bytes, scope accounting,
legacy migration, correctly addressed malformed/misbound records, failed metadata
acknowledgement and retry) and `DocumentSessionStorageTest` (real rooted processing,
original event evidence, cold next-action equivalence and current view validation).
