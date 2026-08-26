# Counter example

This is the smallest normal SDK flow: register a Timeline, admit one ordinary
public Root, execute targeted operations, inspect terminal results, and read a
READY snapshot.

```java
try (BlueCoordination blue = BlueCoordination.inMemory()) {
    TimelineHandle alice = blue.timelines().register(
            "examples/counter/alice", "alice");

    DocumentHandle counter = blue.documents().admit(
            ManagedDocument.yaml("counter", counterYaml)
                    .publicRoot()
                    .fromNow());

    EntryResult plusThree = blue.operations().on(counter)
            .from(alice)
            .call("increment")
            .through("ownerChannel")
            .requestYaml("amount: 3")
            .execute();

    EntryResult minusOne = blue.operations().on(counter)
            .from(alice)
            .call("decrement")
            .through("ownerChannel")
            .requestYaml("amount: 1")
            .execute();

    assert plusThree.applied();
    assert minusOne.applied();
    assert counter.snapshot().longAt("/counter") == 2L;
    assert counter.snapshot().epoch() == 2L;
}
```

Each call is constructed after the previous call commits, so its exact target
is current. `execute()` appends and drains through its entry. To demonstrate
append/process separation, replace one `execute()` with `submit()`, verify the
snapshot is unchanged, call `blue.processing().drain()`, and retrieve the result
with `drain.entry(submitted)`.

The executable version of this exact increment-then-decrement story is
[`SdkAcceptanceTest.counterAppliesPlusThreeThenMinusOne`](../../src/test/java/blue/coordination/sdk/SdkAcceptanceTest.java).
Continue with the [SDK developer guide](../guides/developer-guide.md) for
embedded members, cycles, exact provider entries, and managed drafts.
