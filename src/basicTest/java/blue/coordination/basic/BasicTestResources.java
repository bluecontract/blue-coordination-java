package blue.coordination.basic;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** UTF-8 resource loading for executable basic-test documents. */
final class BasicTestResources {
    private BasicTestResources() {
    }

    static String read(String name) throws IOException {
        try (var input = BasicTestResources.class.getClassLoader()
                .getResourceAsStream(name)) {
            if (input == null) {
                throw new IOException("Missing test resource: " + name);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
