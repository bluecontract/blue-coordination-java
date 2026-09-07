# Retained history provider adapter

This development API exports committed history from a live SDK owner. It does
not add retained-history import or fresh-process recovery to the SDK.

```java
KeyPair keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
RetainedHistoryProvider provider = RetainedHistoryProvider.from(source.advanced(), keys);
var request = RetainedHistoryProvider.Request.fresh(sourceId, firstEpoch, 64);
var page = provider.read(request);
var verified = page.verify(request, authoredInitialBlueId,
        expectedPredecessorBlueId, trustedProducerPublicKey);
// Persist/application-commit coordination belongs to the host.
var receipts = verified.receipts();
```

`source.advanced().retainedHistoryProvider(keys)` exposes the same producer in
the integrated SDK. Pin the key and lineage/attachment identities separately
from the response. The provider/page types live in `blue.coordination.api`, reached through the
advanced SDK boundary; normal SDK signatures expose no low-level receipt types.
The public page constructor is an untrusted transport
constructor; `verify` returns the authenticated view. A key included in an HTTP
response is not independently trusted. The host owns key storage, tenant
authorization, transport encoding and request/byte/time budgets.

For C2, the exact Java boundary is `RetainedHistoryProvider.Request`,
`RetainedHistoryProvider.read`, `RetainedHistoryPage`, and
`RetainedHistoryPage.verify`. Preserve each canonical `api.ManagedEpochReceipt`
with its complete exact document and ordered event occurrences. Do not rebuild
events from the public outbox or business-field equality. Persist the request
challenge if a request must survive retry. Treat `Unavailable` as a retry of
the same cursor, and invalid signatures/links as rejected evidence. Do not
advance a durable consumer cursor merely because a page verified.

The signed head is a point-in-time observation. Read again with a fresh
challenge to observe live extension; the existing managed catch-up engine
continues extending its target. No retained source workflows or public events
are replayed by reads. Source commits remain committed when a consumer fails.

The exact wire transcript and verification rules are in
[the transport draft](../../specifications/retained-history-provider-transport-1.0-draft.md).
Direct-chain pages do not export component-representation rebind evidence.
The adapter also does not export Contracts transition/commit bodies for use as
a runtime importer. Those remain an explicit integration requirement.

Labs 05 and 09 remain red for public construction: ordinary Timeline calls
change the checkpoint and exact BlueId. This adapter can preserve authentic
equal-state epochs and `EVENT_ONLY` receipts when an authorized producer
actually retains them; it cannot manufacture either case. Existing private
synthetic test helpers are not exposed. A genuine producer/runtime creation
path and verified importer are still required before those labs can be green.

The independent check uses the exact published Coordination 3.0.0-rc.5,
Language/Contracts 3.1.0-rc.23, BEX 1.1.0-rc.4 and Repository 3.0.0-rc.21 tuple:

```bash
./gradlew --offline --no-daemon -p smoke-tests/retained-provider test
```

It compiles only the transport adapter against the published producer JAR,
checks every resolved Blue version, and records artifact SHA-256 values in
the smoke build's `verified-dependencies.json`. It is evidence for this
provider boundary, not for the complete candidate source tree.
