# Admission and attachment

Admission turns an exact document (including an authored pure reference that
can be materialized by the configured provider) into epoch-zero managed state.
`CoordinationProcessingEngine.addDocument` materializes the document, splits
it, verifies and admits its immutable fragments, stores a body-free inventory,
projects the initial subscriptions, constructs epoch zero, and delegates the
authoritative decision to `CoordinationSessionStore.admit`.

The session ID is host supplied. It is not the Root BlueId and should be stable
across restarts and retries.

## Registration modes

`DocumentRegistration` carries a session ID, exact document, activation
frontier, `RegistrationMode`, and optional claimed epoch.

- `OPEN_OR_CREATE` is the normal idempotent path: create if absent, otherwise
  attach when the exact state is recognized.
- `CREATE_ONLY` requires absence and conflicts with an existing session.
- `ATTACH_EXISTING` requires an existing session.
- `FORK_FROM_EXACT_STATE` expresses a host intent, but the current reference
  in-memory store does not implement a special fork transaction. A production
  store must not advertise fork semantics without its own verified-lineage and
  new-session policy.

The simple host path uses `DocumentRegistration.openOrCreate`:

<!-- compile-example:AdmissionExample -->
```java
package docs.engine.examples;

import blue.coordination.engine.CoordinationProcessingEngine;
import blue.coordination.engine.api.DocumentAdmissionResult;
import blue.coordination.engine.api.DocumentRegistration;
import blue.coordination.engine.api.DocumentSessionId;
import blue.language.model.Node;
import blue.language.processor.ExternalOrderKey;

import java.util.Arrays;

public final class AdmissionExample {
    private AdmissionExample() {
    }

    public static DocumentSessionId admit(
            CoordinationProcessingEngine engine,
            Node exactDocument) {
        DocumentSessionId id = DocumentSessionId.of("customer-contract-42");
        ExternalOrderKey frontier = ExternalOrderKey.of(
                Arrays.<Object>asList(0L, "admission", id.value()));
        DocumentAdmissionResult result = engine.addDocument(
                DocumentRegistration.openOrCreate(
                        id, exactDocument, frontier));
        if (!result.succeeded()) {
            throw new IllegalStateException(
                    result.status() + ": "
                            + result.diagnostic().orElse("no diagnostic"));
        }
        return result.session().get().sessionId();
    }
}
```

## Result statuses

Only `CREATED`, `ATTACHED_CURRENT`, and `ATTACHED_TO_CURRENT` are successful and
therefore expose a session snapshot.

- `CREATED`: the store atomically created the current record and epoch zero.
- `ATTACHED_CURRENT`: the supplied Root is already current.
- `ATTACHED_TO_CURRENT`: the supplied Root is recognized as a historical
  epoch; the result attaches the caller to the current session snapshot, not to
  a mutable historical branch.
- `CONFLICT`: mode, existence, or claimed historical state is incompatible.
- `FORK_REQUIRED`: the caller claimed an unknown state newer than the current
  session; the store refuses to fast-forward it.
- `VERIFIED_LINEAGE_REQUIRED`: an unknown state has no sufficient claim.

The diagnostic is explanatory data, not a stable programmatic status. Branch
on the enum.

## Idempotence and unknown states

Retries may repeat fragment admission before the authoritative session
decision. This is safe only because fragment storage is content addressed and
conflicting bytes for the same identity fail closed. Re-admitting the same
current Root should attach without creating another epoch.

An arbitrary exact document with the same session ID is not authority to move
that session. The reference store recognizes the current Root and known
historical Roots. Unknown content requires verified lineage or an explicit
host-level fork design. In particular, a claimed future epoch does not permit
an in-place fast-forward.

## Activation frontier

The activation frontier becomes the initial committed order boundary and the
frontier used to project initial occurrence subscriptions. The first process
request must carry a key strictly greater than it. Choose a durable canonical
tuple policy and use the same comparison policy for every producer.

## What admission does not do

Admission does not create a cross-session relationship, schedule events,
resolve autonomous child ownership, or make a historical state current. It
also does not make fragment retention dependent on session lifetime. These
boundaries keep immutable content deduplication separate from lifecycle state.

