package blue.coordination.integration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Shared UTF-8 resource loading for executable test documents. */
final class TestResources {
    private TestResources() {
    }

    static String read(String name) throws IOException {
        try (var input = TestResources.class.getClassLoader()
                .getResourceAsStream(name)) {
            if (input == null) {
                throw new IOException("Missing test resource: " + name);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
