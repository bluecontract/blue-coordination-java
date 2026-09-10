package blue.coordination.verification;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import org.junit.platform.engine.discovery.ClassNameFilter;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.engine.support.descriptor.MethodSource;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;

/** Discovers executable classes, including parameterized methods, without running tests. */
public final class TestClassInventory {
    private TestClassInventory() { }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            throw new IllegalArgumentException("Expected output file and compiled test directories");
        }
        var request = LauncherDiscoveryRequestBuilder.request()
                .filters(ClassNameFilter.includeClassNamePatterns(".*"));
        for (int index = 1; index < args.length; index++) {
            request.selectors(DiscoverySelectors.selectClasspathRoots(Set.of(Path.of(args[index]))));
        }
        var plan = LauncherFactory.create().discover(request.build());
        var classes = new TreeSet<String>();
        for (var root : plan.getRoots()) {
            for (var test : plan.getDescendants(root)) {
                if (test.getSource().orElse(null) instanceof MethodSource method) {
                    classes.add(method.getClassName());
                }
            }
        }
        if (classes.isEmpty()) {
            throw new IllegalStateException("No executable test classes discovered");
        }
        Path output = Path.of(args[0]);
        Files.createDirectories(output.getParent());
        Files.writeString(output, String.join("\n", classes) + "\n", StandardCharsets.UTF_8);
    }
}
