package blue.coordination.examples.support;

import blue.coordination.engine.memory.DemoTransition;

import java.util.Objects;

/** One committed example delivery with exact engine locality diagnostics. */
public record MyOsDemoResult(MyOsDemoEntry entry, DemoTransition delivery) {

    public MyOsDemoResult {
        Objects.requireNonNull(entry, "entry");
        Objects.requireNonNull(delivery, "delivery");
    }
}
