# Five managed occurrences backed by three lineages

This current SDK example demonstrates one operation that creates three managed
documents but embeds them at five effective paths:

```text
/children/alphaFirst  ----\
                           >---- alpha DocumentId
/children/alphaSecond ----/

/children/betaFirst   ----\
                           >---- beta DocumentId
/children/betaSecond  ----/

/children/gamma       ---------- gamma DocumentId
```

The host declares `/children` under
`Process Embedded.collectionPaths`. Its operation writes the exact managed
request fields into those five direct stable-key members. Each `alphaYaml`,
`betaYaml`, and `gammaYaml` value below contains a text `/documentId` equal to
the `DocumentId` passed to `documents().draft(...)`.

```java
try (BlueCoordination blue = BlueCoordination.inMemory()) {
    TimelineHandle owner = blue.timelines().register(
            "examples/playground/five-occurrence/host",
            "playground-owner");

    DocumentHandle host = blue.documents().admit(
            ManagedDocument.yaml("playground-host", hostYaml)
                    .publicRoot()
                    .fromNow());

    ManagedDocumentDraft alpha = blue.documents().draft(
            DocumentId.of("alpha"),
            blue.values().yaml(alphaYaml));
    ManagedDocumentDraft beta = blue.documents().draft(
            DocumentId.of("beta"),
            blue.values().yaml(betaYaml));
    ManagedDocumentDraft gamma = blue.documents().draft(
            DocumentId.of("gamma"),
            blue.values().yaml(gammaYaml));

    EntryResult result = blue.operations().on(host)
            .from(owner)
            .call("createChildren")
            .through("ownerChannel")
            .request(request -> request
                    .managed("alpha", alpha)
                    .managed("beta", beta)
                    .managed("gamma", gamma))
            .expectOccurrence("/children/alphaFirst", alpha)
            .expectOccurrence("/children/alphaSecond", alpha)
            .expectOccurrence("/children/betaFirst", beta)
            .expectOccurrence("/children/betaSecond", beta)
            .expectOccurrence("/children/gamma", gamma)
            .activation(ActivationPolicy.fromNow())
            .execute();

    assert result.applied();
    assert blue.documents().require(DocumentId.of("alpha"))
            .snapshot().ready();
    assert blue.documents().require(DocumentId.of("beta"))
            .snapshot().ready();
    assert blue.documents().require(DocumentId.of("gamma"))
            .snapshot().ready();
}
```

Every managed request draft needs at least one expectation, every expectation
must name matching request evidence, and the resulting exact value must occur at
every declared effective path. Missing, additional, ambiguous, wrong-value, or
wrong-owner evidence fails closed. A terminal failure publishes neither the
new lineages nor partial host topology.

All five expected edges are sourced from `host`. One call cannot also declare
an edge from Alpha to another new draft; attach that next member in a later
applied operation on Alpha, or put an interdependent initial topology in one
`ManagedClosure`.

The five occurrences do not duplicate retained lineage histories. Alpha and
Beta each have one stable `DocumentId` and one independently evolving history,
even though each appears twice.

The complete executable operation YAML, declaration-order permutations,
failure matrix, atomic rollback assertions, and structural evidence are in
[`SdkManagedDraftAcceptanceTest`](../../src/test/java/blue/coordination/sdk/SdkManagedDraftAcceptanceTest.java).
For the full application journey, read the
[SDK developer guide](../guides/developer-guide.md).
