# Public API example

The built-JAR consumer test in the Round 13A patch is the authoritative
executable example.

Conceptually:

```java
try (CoordinationEngine engine = CoordinationEngine.inMemory()) {
    Timeline owner = engine.registerTimeline(
            "examples/playground/five-occurrence/host",
            "playground-owner");

    engine.registerTimeline(
            "examples/playground/five-occurrence/alpha",
            "playground-feed-alpha");
    engine.registerTimeline(
            "examples/playground/five-occurrence/beta",
            "playground-feed-beta");
    engine.registerTimeline(
            "examples/playground/five-occurrence/gamma",
            "playground-feed-gamma");

    DocumentId host = DocumentId.of(
            "playground-five-occurrence-host");
    engine.startDocument(host, hostYaml);

    ExactValue alpha = engine.exactValue(alphaInitialGameYaml);
    ExactValue beta = engine.exactValue(betaInitialGameYaml);
    ExactValue gamma = engine.exactValue(gammaInitialGameYaml);

    ExactValue request = engine.exactValue("""
            documents:
              betaSecond:
                blueId: %s
              alphaSecond:
                blueId: %s
              gamma:
                blueId: %s
              betaFirst:
                blueId: %s
              alphaFirst:
                blueId: %s
            """.formatted(
            beta.blueId(),
            alpha.blueId(),
            gamma.blueId(),
            beta.blueId(),
            alpha.blueId()));

    TimelineEntry entry = engine.append(
            owner,
            Operation.exact(
                    "attachFiveDocuments",
                    "ownerChannel",
                    request));

    engine.drainThrough(entry.sourceOrderKey());

    DocumentSnapshot ready = engine.document(host);
    assert ready.embeddedChildren().size() == 5;
    assert engine.metrics().documentCount() == 4;
}
```

The request contains five collection occurrences but only three stable child
`DocumentId` values and three retained exact child bodies. Representation must
not alter semantics: an equivalent inline request should produce the same final
state, histories, event order, and gas. The reference form is preferred for the
Playground because it avoids parsing and retaining duplicate large bodies.
