package blue.coordination.engine;

import blue.language.model.Node;
import blue.language.processor.BlueContracts;
import org.junit.jupiter.api.Test;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Integrity and compilation checks for the public engine documentation set. */
final class EngineDocumentationTest {

    private static final Path DOC_ROOT = Paths.get("docs", "engine");
    private static final List<String> REQUIRED_DOCUMENTS = Arrays.asList(
            "start-here.md",
            "session-and-epoch-model.md",
            "admission-and-attachment.md",
            "fragment-store-spi.md",
            "session-store-spi.md",
            "planning-and-prefetch.md",
            "atomic-commit.md",
            "in-memory-demo.md",
            "database-host-integration.md",
            "owned-occurrences-vs-autonomous-documents.md",
            "performance-evidence.md");
    private static final Pattern COMPILE_EXAMPLE = Pattern.compile(
            "<!--\\s*compile-example:([A-Za-z_$][A-Za-z0-9_$]*)\\s*-->"
                    + "\\s*```java\\s*\\R([\\s\\S]*?)\\R```",
            Pattern.MULTILINE);
    private static final Pattern MARKDOWN_LINK = Pattern.compile(
            "\\[[^]]+\\]\\(([^)]+\\.md(?:#[^)]+)?)\\)");

    @Test
    void shouldPublishEveryRequiredEngineGuide() throws IOException {
        // given
        List<String> missing = new ArrayList<String>();
        List<String> empty = new ArrayList<String>();

        // when
        for (String name : REQUIRED_DOCUMENTS) {
            Path document = DOC_ROOT.resolve(name);
            if (!Files.isRegularFile(document)) {
                missing.add(name);
            } else if (read(document).trim().isEmpty()) {
                empty.add(name);
            }
        }

        // then
        assertTrue(missing.isEmpty(), "Missing engine guides: " + missing);
        assertTrue(empty.isEmpty(), "Empty engine guides: " + empty);
    }

    @Test
    void shouldResolveEveryRelativeEngineGuideLink() throws IOException {
        // given
        List<String> broken = new ArrayList<String>();

        // when
        for (String name : REQUIRED_DOCUMENTS) {
            Path source = DOC_ROOT.resolve(name);
            Matcher links = MARKDOWN_LINK.matcher(read(source));
            while (links.find()) {
                String target = links.group(1);
                int anchor = target.indexOf('#');
                String relative = anchor < 0
                        ? target
                        : target.substring(0, anchor);
                Path resolved = source.getParent().resolve(relative)
                        .normalize();
                if (!Files.isRegularFile(resolved)) {
                    broken.add(name + " -> " + target);
                }
            }
        }

        // then
        assertTrue(broken.isEmpty(), "Broken engine guide links: " + broken);
    }

    @Test
    void shouldCompileEveryMarkedJavaExample() throws Exception {
        // given
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "Documentation examples require a JDK");
        Path sourceDirectory = Files.createTempDirectory(
                "coordination-engine-doc-sources-");
        Path outputDirectory = Files.createTempDirectory(
                "coordination-engine-doc-classes-");
        List<File> sources = extractExamples(sourceDirectory);
        DiagnosticCollector<JavaFileObject> diagnostics =
                new DiagnosticCollector<JavaFileObject>();

        // when
        boolean compiled;
        try (StandardJavaFileManager files = compiler.getStandardFileManager(
                diagnostics, null, StandardCharsets.UTF_8)) {
            Iterable<? extends JavaFileObject> units =
                    files.getJavaFileObjectsFromFiles(sources);
            List<String> options = Arrays.asList(
                    "-proc:none",
                    "-source", "8",
                    "-target", "8",
                    "-classpath", compilationClassPath(),
                    "-d", outputDirectory.toString());
            compiled = compiler.getTask(
                    null, files, diagnostics, options, null, units).call();
        }

        // then
        assertEquals(8, sources.size(),
                "Every intended engine example must remain compile-checked");
        assertTrue(compiled, formatDiagnostics(diagnostics));
    }

    @Test
    void shouldDescribeThePublicPerInvocationContractsBoundary()
            throws IOException {
        // given
        String start = read(DOC_ROOT.resolve("start-here.md"));
        String planning = read(DOC_ROOT.resolve(
                "planning-and-prefetch.md"));

        // when
        boolean namesInvocation = start.contains(
                "PlatformProcessInvocation");
        boolean namesPublicOperation = planning.contains(
                "BlueContracts.processForPlatformCommit");
        boolean bindsDeliveryPlan = planning.contains(
                "plan.preparedDelivery().deliveryPlan()");
        boolean bindsExactProvider = planning.contains(
                "loadedBundle.exactProvider()");

        // then
        assertTrue(namesInvocation,
                "The engine guide must name the immutable invocation");
        assertTrue(namesPublicOperation,
                "The engine guide must name the public Contracts operation");
        assertTrue(bindsDeliveryPlan,
                "The guide must bind the plan's exact delivery evidence");
        assertTrue(bindsExactProvider,
                "The guide must bind the request-local provider");
    }

    @Test
    void shouldNotPublishTheRetiredContractsApiGap() throws IOException {
        // given
        String planning = read(DOC_ROOT.resolve(
                "planning-and-prefetch.md"));
        String performance = read(DOC_ROOT.resolve(
                "performance-evidence.md"));

        // when
        boolean claimsUnavailableEvidence = planning.contains(
                "ExecutionEvidenceUnavailableException");
        boolean claimsMissingProviderParameter = planning.contains(
                "no per-call provider parameter");
        boolean claimsProcessCannotBeMeasured = performance.contains(
                "PROCESS, commit, latency, throughput, and request-local "
                        + "physical-read samples remain zero");

        // then
        assertFalse(claimsUnavailableEvidence,
                "The retired construction-time evidence gap must stay gone");
        assertFalse(claimsMissingProviderParameter,
                "The public invocation now accepts the exact provider");
        assertFalse(claimsProcessCannotBeMeasured,
                "Completed invocations may publish physical measurements");
    }

    @Test
    void shouldKeepCommitOwnershipAndReleaseBoundariesExplicit()
            throws IOException {
        // given
        String start = normalizeWhitespace(
                read(DOC_ROOT.resolve("start-here.md")));
        String atomic = normalizeWhitespace(
                read(DOC_ROOT.resolve("atomic-commit.md")));
        String database = normalizeWhitespace(read(DOC_ROOT.resolve(
                "database-host-integration.md")));
        String ownership = normalizeWhitespace(read(DOC_ROOT.resolve(
                "owned-occurrences-vs-autonomous-documents.md")));
        String performance = normalizeWhitespace(read(DOC_ROOT.resolve(
                "performance-evidence.md")));

        // when
        boolean workingNotRc = start.contains(
                "not a declaration that Coordination is a public release candidate");
        boolean repositoryBlockersSeparate = start.contains(
                "Repository required-closure blockers are tracked separately");
        boolean noDistributedTransaction = atomic.contains(
                "does not claim that an arbitrary fragment database and session database participate in one distributed transaction");
        boolean immutableBatch = database.contains(
                "one immutable fragment-body batch write");
        boolean authoritativeCas = database.contains(
                "one compact authoritative session CAS");
        boolean oneSessionPerCall = ownership.contains(
                "advances exactly one session per call");
        boolean autonomousHostBoundary = performance.contains(
                "Autonomous-document fan-out belongs to the host layer");

        // then
        assertTrue(workingNotRc, "The working-engine status must stay explicit");
        assertTrue(repositoryBlockersSeparate,
                "Repository closure must stay a separate release gate");
        assertTrue(noDistributedTransaction,
                "Atomic commit must not imply distributed atomicity");
        assertTrue(immutableBatch,
                "The database guide must state the immutable batch shape");
        assertTrue(authoritativeCas,
                "The database guide must state the authoritative CAS shape");
        assertTrue(oneSessionPerCall,
                "The engine must not imply cross-session propagation");
        assertTrue(autonomousHostBoundary,
                "Autonomous fan-out must stay an explicit host boundary");
        assertFalse(ownership.contains("autonomous shared documents have shipped"),
                "Deferred functionality must not be presented as shipped");
    }

    private static List<File> extractExamples(Path directory)
            throws IOException {
        List<File> result = new ArrayList<File>();
        Set<String> classNames = new LinkedHashSet<String>();
        for (String document : REQUIRED_DOCUMENTS) {
            Matcher matcher = COMPILE_EXAMPLE.matcher(
                    read(DOC_ROOT.resolve(document)));
            while (matcher.find()) {
                String className = matcher.group(1);
                if (!classNames.add(className)) {
                    throw new IllegalStateException(
                            "Duplicate documentation example: " + className);
                }
                Path source = directory.resolve(className + ".java");
                Files.write(source, matcher.group(2).getBytes(
                        StandardCharsets.UTF_8));
                result.add(source.toFile());
            }
        }
        return result;
    }

    private static String compilationClassPath() throws URISyntaxException {
        Set<String> entries = new LinkedHashSet<String>();
        String configured = System.getProperty("java.class.path", "");
        if (!configured.isEmpty()) {
            entries.addAll(Arrays.asList(configured.split(
                    Pattern.quote(File.pathSeparator))));
        }
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        while (loader != null) {
            if (loader instanceof URLClassLoader) {
                for (URL url : ((URLClassLoader) loader).getURLs()) {
                    if ("file".equals(url.getProtocol())) {
                        entries.add(Paths.get(url.toURI()).toString());
                    }
                }
            }
            loader = loader.getParent();
        }
        addCodeSource(entries, CoordinationProcessingEngine.class);
        addCodeSource(entries, BlueContracts.class);
        addCodeSource(entries, Node.class);
        return join(entries, File.pathSeparator);
    }

    private static void addCodeSource(Set<String> entries, Class<?> type)
            throws URISyntaxException {
        CodeSource source = type.getProtectionDomain().getCodeSource();
        if (source != null && source.getLocation() != null) {
            entries.add(Paths.get(source.getLocation().toURI()).toString());
        }
    }

    private static String join(Set<String> values, String separator) {
        StringBuilder result = new StringBuilder();
        for (String value : values) {
            if (value == null || value.isEmpty()) continue;
            if (result.length() > 0) result.append(separator);
            result.append(value);
        }
        return result.toString();
    }

    private static String formatDiagnostics(
            DiagnosticCollector<JavaFileObject> diagnostics) {
        StringBuilder result = new StringBuilder(
                "Documentation examples did not compile:\n");
        for (Diagnostic<? extends JavaFileObject> diagnostic
                : diagnostics.getDiagnostics()) {
            result.append(diagnostic.getKind())
                    .append(" at ")
                    .append(diagnostic.getSource() == null
                            ? "<unknown>"
                            : diagnostic.getSource().getName())
                    .append(':')
                    .append(diagnostic.getLineNumber())
                    .append(" - ")
                    .append(diagnostic.getMessage(null))
                    .append('\n');
        }
        return result.toString();
    }

    private static String read(Path path) throws IOException {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    private static String normalizeWhitespace(String value) {
        return value.replaceAll("\\s+", " ").trim();
    }
}
