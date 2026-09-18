# Complete selected SDK assembly

For the current d220-based source port and pending acceptance, see
[the baseline port registry](rooted-external-state-baseline-port.md).
Qualification receipts below remain historical and pinned to their original sources.

## Problem and exact boundary

Retaining five SDK maps is not enough to reopen a running application: handles
belong to their original SDK owner, and results/intents depend on the actual
engine, journal, execution policy, and registered Timeline actors. Conversely,
rebuilding those maps by registering Timelines and appending old inputs would
perform new work and lose retained failure/attempt and insertion identity.

`RootedCoordinationStorage` binds the existing complete `RootedEngineStorage`
scope and the five point-backed SDK maps under a real new `BlueCoordination`
owner. It uses the existing closed codecs; it does not add a processor,
semantic policy, writable proof factory, or journal replay mode.

## Named selections and lifecycle

The public selection contains independent named slots:

- `sdk/configuration`: exact Language/Contracts identities, bundled-release
  identities, content-derived-ID setting, and actual execution policy.
- `sdk/TIMELINES`, `sdk/INTENTS`, `sdk/RESULTS`, `sdk/ENTRIES`, and
  `sdk/SOURCE_RESULTS`: one bounded, versioned descriptor frame each, containing
  the key/order map roots and original next-insertion sequence.
- `engine/<name>`: each complete engine selection slot, retained without
  collapsing the independent families into one publication root.

Each SDK family frame repeats its exact configuration binding and checks its
kind/name. Missing, swapped, extra SDK, corrupt, differently bound, and negative
sequence frames fail noncommitting. The actual engine validates its complete
engine family set and actual bootstrap; a caller-supplied label is insufficient.
Selected point reads continue to check actual journal rows, Timeline actors,
original intent operands, complete source keys, and the live SDK owner.

`open(objects, limits, selection, provider, journal)` performs actual complete
engine installation, creates a fresh SDK owner, and installs its five lazy maps.
The provider is scoped by that SDK owner, not reused from the old owner.
`Scope.coordination()` returns the ordinary existing SDK API.

`Scope.documentHandle(id)` checks only retained catalog membership and creates
an actual fresh-owner handle, including pending/non-ready managed documents.
It does not hydrate or certify a current session body. Ordinary document reads
and execution still enforce their exact state/readiness checks; the ordinary
`documents().require` contract is unchanged. `Scope.timelineHandle(id)` reads one
installed SDK registration (including registered-empty Timelines) without
registering anything. Both lookups reject use after their owner closes.

`PersistentOrderedMap.containsKey` deliberately retains its existing value
validation behavior. The new metadata-only engine lookup uses a separate
package-private key-path traversal: authenticated node/shape/key checks and
absence callbacks remain, but projecting a session value is unnecessary for
membership. Tests check cold present/absent and corrupt-path cases, unchanged
ordinary `containsKey`, and a genuinely pending reciprocal join with zero
session-body reads before its later explicit readiness audit.

The cold foreign-handle control found that `process(root, entry)` validated the
entry's owner but only used the root's ID. It now applies the same root-owner
guard as `processNext(root)` before any engine access. This enforces the existing
owner-bound contract in `docs/reference/public-api.md`; no root selection or
processing semantics change.

`Scope.stage()` runs under the SDK lock, retains immutable dependencies, and
returns named descriptors. It does **not** commit them. The host must pin a
coherent journal/selection, retain all selected membership and read predicates,
validate them at publication, and atomically publish the affected named slots
and journal mutations. These descriptors do not authorize one global realm CAS
or serialization of disjoint owners. Failure may leave unreachable immutable
prewrites, not a published application transition.

A physical failure **during SDK execution, including PROCESS, requires discarding
the entire scope without publishing it**. Existing transitions can update one
runtime-owned component before a later physical map write fails. This facade
does not add a mutable in-memory transaction or make such a scope retry-safe.
The host must reopen the still-committed named selection and matching journal
before retrying the operation. A failure confined to immutable prewrites during
`stage()` can retry staging the already successful working state in the same
scope. That limited retry rule must not be applied to an execution failure.

`retainPartition` is explicitly a one-time resident conversion of a caller-owned
partition. It enumerates that partition's resident maps; ordinary cold open and
stage do not call it. The original owner stays open and is not changed by SDK
map installation. The journal must be retained independently under the same
selection boundary, preserving its real revision and availability.

Closing the storage scope retires the SDK, engine, and every owned scope even
when cleanup fails. Host object stores and journal lifecycle remain host-owned.
Using either an old handle or a closed restored owner remains invalid.

All limits are explicit physical byte/index/scope bounds. They neither alter
semantic gas nor introduce a new legal closure limit. SDK live values remain
identity-pinned; five maps can conservatively retain at most
`5 * pinnedEntries * maximumRowBytes` encoded point-row bytes, separately from
descriptor, index-node, and point-cache charges. This is not a JVM heap measure.

## Verification status

The real engine dependency is `1fceadbd5d64e449481bcfb2a4ebf55644fc4a74`,
independently qualified 39/39 plus Javadoc. This donor merges it at `11fb736` and
uses the exact immutable Language `8542285144a8969f157d73e73292398885105c46`,
BEX `ab72af14ee54c6123e6d80349956af373a887680`, and Catalog
`0b68744ba6456ef312d1ba87a27096bd2ac5341f` bindings. No stub engine, partial
installation, replay-generated state, or synthetic stored session is used.

The complete factory owner passed 5/5 in the focused 29-second gate:

- Original producer closes; a fresh SDK restores counter/history/retained
  results, rejects foreign handles, executes the same second invocation, stages
  twice in one scope, and cold-reopens both the earlier and later selections.
- Missing engine families and mismatched actual configuration fail before any
  submission; either SDK-first or scope-first close retires the complete owner.
- A stopped parent retains its original failure. Independent source admission
  and LIVE execution survive another cold open without reprocessing. The same
  parent entry resumes, then exact source E0 and E1 application attempts,
  receipts, gas/order, final parent history/head, and unchanged source history
  match the resident reference.
- A reciprocal cycle captures the pending join and then completed epoch-zero
  join, closes its producer, performs finite A/B work under a new owner, stages
  and cold-opens again, and detaches. Complete terminal result transport, public
  histories, checkpoints, event identities, gas and final heads match resident
  execution. Only aggregate drain wall-clock elapsed time is excluded from
  cross-run equality; no semantic stat or nested attempt is normalized.

An APPLIED attachment does not imply its authored cursor -1 has consumed the
source's retained epochs. Both resident and cold execution expose the prior
READY parent head until the separate E0/E1 history steps finish. The test now
asserts this intermediate state instead of incorrectly demanding a new READY
head immediately after entry acknowledgement.

The separately named `assertExistingExplicitPostSplitReplayLimitation`
reproduction records an existing resident limitation: directly supplying the
same detach entry after its topology split throws at
`ContractsClosureAdapter.captureDormantDependencies` while finding an SCC
component. Both resident and cold runs throw the same type and preserve exact
heads/history/publication evidence. This is **not** a successful explicit replay
claim. The supported canonical no-work drain is separately checked for zero
gas/transitions and exact retained publication lookup. Such a drain may return
empty acknowledgement entries, and its latest SDK convenience entry result is
not a replacement for the original exact publication receipt.

Initial physical test capacities were corrected after an exact join result
component measured 549,850 encoded bytes, exceeding the proposed 256 KiB index
value bound. The tests use the already qualified engine owner's 32 MiB complete
record and 40 MiB node/log capacities, without changing any semantic gas budget,
heap setting, deadline, or legal closure size. A descriptor-bound negative uses
the codec's valid 128-byte minimum, not an invalid 32-byte constructor bound.

The final broader focused gate passed **46/46, zero failures/errors/skips, plus
Javadoc in 42 seconds**, with identical all-file hashes before and after:
factory 5, descriptors 5, SDK point maps 6, SDK point rows 7, SDK installation 2,
insertion-ordered maps 7, key-only membership 1, complete engine 4, legacy
engine lifecycle 3, and document-session state epochs 6. These are overlapping
controls, not additional tests to sum with the earlier component gates.
Results and earlier diagnostic reds are retained separately under
`rooted-sdk-factory-evidence.47yfoU`. The final tested source archive is SHA-256
`1e7cba1bbdb3466b165346a120c1186727c25e1a20e1331a49c9fed4576b03d8`;
the complete XML/HTML/Javadoc archive is SHA-256
`3623d8786eb575c86c4d975822219864f0e2e8557a609904ce4f80e8bd126a00`.
Only this qualification paragraph was updated after that frozen gate. These byte
fixtures qualify real SDK/engine restoration, not PostgreSQL publication
atomicity, a new JVM process, or full-corpus acceptance. Actual PostgreSQL
journal/slot publication is the following independent host gate.

## Host integration follow-up: selected identity inventories

The host's existing scoped callbacks occasionally need the complete identity
inventory, including pending documents and registered-empty Timelines. Reading
that inventory from newer mutable projection rows after selecting older SDK
descriptors could mix publications. Requiring each document through its READY
snapshot would instead load bodies and exclude valid pending sessions.

`Scope.documentIds()` and `Scope.timelineIds()` now expose detached, sorted keys
from the selected library-owned indexes. They load neither session bodies nor
the journal and perform no registration. This is a minimal storage API bridge,
not a processing-rule change. Both calls remain explicit O(catalog-size)
inventory operations; a scalable next-work selector must not call them for every
document. Returned IDs are not execution authority; ordinary selected body and
ownership checks still apply.

The follow-up gate passed **34/34 plus Javadoc** on 11 September 2026: complete
SDK factory 5, descriptor 5, complete engine 4, ordered-map 20. Added assertions
cover pending membership, registered-empty Timelines, zero session/journal body
reads and retired owners. Evidence:
`/Users/kamil/Documents/Projects/Blue/rooted-sdk-inventory-evidence.ho3V3Z`;
`gate34.tar.gz` SHA-256
`2f3373d7ca56967aaa7f8217d8cb4cd8bf49616ae9e5b89e2c6e380a035b58b8`,
source SHA-256
`957f4d715a0027ed2b44ba581c912cac3d0aadcba7fa26475076aebead981e4f`.
These overlapping test totals must not be added to the prior 46-test gate.
