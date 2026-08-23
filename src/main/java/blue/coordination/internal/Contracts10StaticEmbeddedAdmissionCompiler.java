package blue.coordination.internal;

import blue.coordination.api.CoordinationEngine;
import blue.coordination.api.CoordinationErrorCode;
import blue.coordination.api.CoordinationException;
import blue.coordination.api.DocumentId;
import blue.coordination.api.ExactValue;
import blue.coordination.sdk.ExactNodeProvider;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import blue.language.model.NodePathEditor;
import blue.language.codec.jackson.UncheckedObjectMapper;
import blue.language.model.wire.JsonPointer;
import blue.language.processor.EffectiveFragmentationCatalog;
import blue.language.provider.NodeProvider;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves the bounded static Process Embedded admission subset and delegates
 * the resulting explicit closure to {@link Contracts10AuthoredClosureCompiler}.
 *
 * <p>The resolver admits only one immutable authored Root and the all-new exact
 * members reachable from its effective static occurrence catalog. It never
 * scans a value for candidate children, creates operation-time occurrences,
 * reconstructs historical state, or performs component/cycle analysis.</p>
 */
public final class Contracts10StaticEmbeddedAdmissionCompiler {
    private static final String ROOT_ALIAS = "root";
    private static final Pattern EMBEDDED_PATH_DIAGNOSTIC = Pattern.compile(
            "embedded path\\s+([^\\s,;]+)",
            Pattern.CASE_INSENSITIVE);

    private final Contracts10AuthoredClosureCompiler explicitCompiler;

    public Contracts10StaticEmbeddedAdmissionCompiler(
            DefaultCoordinationEngine engine) {
        explicitCompiler = new Contracts10AuthoredClosureCompiler(
                Objects.requireNonNull(engine, "engine"));
    }

    /** Resolves and compiles one exact authored static Root without mutation. */
    public CompiledStaticAdmission compile(
            String authoredYaml,
            ExactNodeProvider provider) {
        return compile(
                authoredYaml,
                provider,
                Contracts10AuthoredClosureCompiler.ActivationInputs.fromNow());
    }

    /** Resolves and compiles one exact authored static Root without mutation. */
    public CompiledStaticAdmission compile(
            String authoredYaml,
            ExactNodeProvider provider,
            Contracts10AuthoredClosureCompiler.ActivationInputs activation) {
        String source = requireText(authoredYaml, "authoredYaml");
        ExactNodeProvider exactProvider = Objects.requireNonNull(
                provider, "provider");
        Contracts10AuthoredClosureCompiler.ActivationInputs selectedActivation =
                Objects.requireNonNull(activation, "activation");
        EngineMetrics metrics = new EngineMetrics();
        WholeObjectStore objects = new WholeObjectStore(metrics);
        VerifiedExactNodeProvider providerLeaf =
                new VerifiedExactNodeProvider(exactProvider);
        try (BlueRuntime runtime = BlueRuntime.create(
                objects, metrics, providerLeaf)) {
            ExactValue rootExact = runtime.exactSource(
                    source,
                    objects,
                    "contracts10-static-authored-root");
            DocumentId rootId = DocumentId.of(rootExact.blueId());

            LinkedHashMap<DocumentId, Node> exactMembers =
                    new LinkedHashMap<>();
            LinkedHashMap<String, DocumentId> memberByBlueId =
                    new LinkedHashMap<>();
            ArrayList<DiscoveredOccurrence> occurrences = new ArrayList<>();
            exactMembers.put(rootId, rootExact.copyNode());
            memberByBlueId.put(rootExact.blueId(), rootId);

            ArrayList<DocumentId> queue = new ArrayList<>();
            queue.add(rootId);
            for (int cursor = 0; cursor < queue.size(); cursor++) {
                DocumentId sourceId = queue.get(cursor);
                Node sourceBody = exactMembers.get(sourceId);
                ExactValue sourceExact = objects.put(
                        sourceBody,
                        "contracts10-static-member");
                if (!sourceId.value().equals(sourceExact.blueId())) {
                    throw new IllegalStateException(
                            "Static member identity changed before compilation: "
                                    + sourceId + " != " + sourceExact.blueId());
                }
                EffectiveFragmentationCatalog catalog;
                try {
                    catalog = runtime.effectiveFragmentationCatalog(
                            sourceExact.blueId());
                } catch (RuntimeException catalogFailure) {
                    throw translateCatalogFailure(
                            catalogFailure,
                            sourceId,
                            sourceBody,
                            providerLeaf);
                }
                for (String path : ownedConcretePaths(catalog)) {
                    Node occurrence = NodePathEditor.getOrNull(
                            sourceBody, path);
                    if (occurrence == null) {
                        throw new IllegalStateException(
                                "Effective Process Embedded catalog named an "
                                        + "absent value at " + sourceId + path);
                    }
                    ResolvedTarget target = resolveTarget(
                            occurrence,
                            sourceId,
                            path,
                            providerLeaf,
                            memberByBlueId);
                    DocumentId targetId = memberByBlueId.get(
                            target.blueId());
                    if (targetId == null) {
                        targetId = DocumentId.of(target.blueId());
                        memberByBlueId.put(target.blueId(), targetId);
                        exactMembers.put(targetId, target.body());
                        objects.put(
                                ExactValue.verified(
                                        target.blueId(), target.body()),
                                "contracts10-static-discovered-member");
                        queue.add(targetId);
                    }
                    occurrences.add(new DiscoveredOccurrence(
                            sourceId, path, targetId));
                }
            }

            LinkedHashMap<DocumentId, String> aliasesByDocument =
                    aliases(exactMembers.keySet(), rootId);
            LinkedHashMap<String, DocumentId> documentsByAlias =
                    new LinkedHashMap<>();
            ArrayList<Contracts10AuthoredClosureCompiler.AuthoredDocument>
                    documents = new ArrayList<>();
            Map<DocumentId, List<DiscoveredOccurrence>> bySource =
                    occurrencesBySource(occurrences);
            for (Map.Entry<DocumentId, Node> member
                    : exactMembers.entrySet()) {
                Node compilerReady = compilerReadyBody(
                        member.getKey(),
                        member.getValue(),
                        exactMembers,
                        bySource);
                String alias = aliasesByDocument.get(member.getKey());
                documentsByAlias.put(alias, member.getKey());
                documents.add(new Contracts10AuthoredClosureCompiler
                        .AuthoredDocument(
                                member.getKey(),
                                runtime.nodeToYaml(compilerReady)));
            }
            List<Contracts10AuthoredClosureCompiler.OccurrenceBinding>
                    bindings = occurrences.stream()
                            .map(occurrence -> new
                                    Contracts10AuthoredClosureCompiler
                                            .OccurrenceBinding(
                                            aliasesByDocument.get(occurrence
                                                    .sourceDocumentId()),
                                            occurrence.path(),
                                            aliasesByDocument.get(occurrence
                                                    .targetDocumentId())))
                            .toList();
            Contracts10AuthoredClosureCompiler.CompilationRequest request =
                    new Contracts10AuthoredClosureCompiler.CompilationRequest(
                            documents,
                            documentsByAlias,
                            bindings,
                            Set.of(rootId),
                            selectedActivation,
                            "contracts10-static-process-embedded-admission");
            Contracts10AuthoredClosureCompiler.CompiledClosure compiled =
                    explicitCompiler.compilePreverifiedContentIdentified(
                            request);
            return new CompiledStaticAdmission(
                    compiled,
                    rootId,
                    documentsByAlias,
                    exactMembers,
                    occurrences);
        }
    }

    private static ResolvedTarget resolveTarget(
            Node occurrence,
            DocumentId sourceDocumentId,
            String sourcePath,
            VerifiedExactNodeProvider provider,
            Map<String, DocumentId> knownMembers) {
        Node selected = Objects.requireNonNull(
                occurrence, "occurrence");
        if (!selected.isReferenceOnly()) {
            ExactValue exact = ExactValue.verified(selected);
            return new ResolvedTarget(exact.blueId(), exact.copyNode());
        }
        String requiredBlueId = selected.getBlueId();
        if (knownMembers.containsKey(requiredBlueId)) {
            return new ResolvedTarget(requiredBlueId, null);
        }
        Optional<Node> supplied;
        try {
            supplied = provider.findExactNode(requiredBlueId);
        } catch (ProviderValueException invalid) {
            throw invalidProviderValue(
                    invalid.expectedBlueId(),
                    invalid.actualBlueId(),
                    sourceDocumentId,
                    sourcePath,
                    invalid.getMessage());
        } catch (CoordinationException failure) {
            throw failure;
        } catch (RuntimeException unavailable) {
            throw missingResource(
                    requiredBlueId,
                    sourceDocumentId,
                    sourcePath,
                    unavailable);
        }
        if (supplied.isEmpty()) {
            throw missingResource(
                    requiredBlueId,
                    sourceDocumentId,
                    sourcePath,
                    null);
        }
        Node body = Objects.requireNonNull(
                supplied.orElseThrow(), "provider exact node").clone();
        if (body.isReferenceOnly()) {
            throw invalidProviderValue(
                    requiredBlueId,
                    null,
                    sourceDocumentId,
                    sourcePath,
                    "provider returned another pure reference");
        }
        ExactValue exact = ExactValue.verified(body);
        if (!requiredBlueId.equals(exact.blueId())) {
            throw invalidProviderValue(
                    requiredBlueId,
                    exact.blueId(),
                    sourceDocumentId,
                    sourcePath,
                    "provider returned content with a different exact BlueId");
        }
        return new ResolvedTarget(requiredBlueId, exact.copyNode());
    }

    private static RuntimeException translateCatalogFailure(
            RuntimeException failure,
            DocumentId sourceDocumentId,
            Node sourceBody,
            VerifiedExactNodeProvider provider) {
        ProviderValueException providerFailure = provider.lastFailure();
        if (providerFailure != null) {
            String path = embeddedPath(failure);
            return invalidProviderValue(
                    providerFailure.expectedBlueId(),
                    providerFailure.actualBlueId(),
                    sourceDocumentId,
                    path == null ? "/" : path,
                    providerFailure.getMessage());
        }
        String path = embeddedPath(failure);
        if (path == null) {
            return failure;
        }
        Node selected = NodePathEditor.getOrNull(sourceBody, path);
        if (selected == null || !selected.isReferenceOnly()) {
            return failure;
        }
        return missingResource(
                selected.getBlueId(), sourceDocumentId, path, failure);
    }

    private static String embeddedPath(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            String message = current.getMessage();
            if (message != null) {
                Matcher matcher = EMBEDDED_PATH_DIAGNOSTIC.matcher(message);
                if (matcher.find()) {
                    try {
                        return JsonPointer.canonicalize(matcher.group(1));
                    } catch (IllegalArgumentException ignored) {
                        return null;
                    }
                }
            }
            current = current.getCause();
        }
        return null;
    }

    private static CoordinationException missingResource(
            String blueId,
            DocumentId sourceDocumentId,
            String sourcePath,
            Throwable cause) {
        Map<String, String> details = resourceDetails(
                blueId, sourceDocumentId, sourcePath);
        return new CoordinationException(
                CoordinationErrorCode.NEEDS_RESOURCES,
                "Exact Blue node " + blueId + " is required by "
                        + sourceDocumentId + " at " + sourcePath,
                cause,
                details);
    }

    private static CoordinationException invalidProviderValue(
            String expectedBlueId,
            String actualBlueId,
            DocumentId sourceDocumentId,
            String sourcePath,
            String diagnostic) {
        LinkedHashMap<String, String> details = new LinkedHashMap<>(
                resourceDetails(
                        expectedBlueId, sourceDocumentId, sourcePath));
        if (actualBlueId != null) {
            details.put("actualBlueId", actualBlueId);
        }
        return new CoordinationException(
                CoordinationErrorCode.INVALID_DOCUMENT_IDENTITY,
                diagnostic + " for " + expectedBlueId + " required by "
                        + sourceDocumentId + " at " + sourcePath,
                null,
                details);
    }

    private static Map<String, String> resourceDetails(
            String blueId,
            DocumentId sourceDocumentId,
            String sourcePath) {
        LinkedHashMap<String, String> details = new LinkedHashMap<>();
        details.put("blueId", requireText(blueId, "blueId"));
        details.put("sourceDocumentId", Objects.requireNonNull(
                sourceDocumentId, "sourceDocumentId").value());
        details.put("sourcePath", JsonPointer.canonicalize(
                Objects.requireNonNull(sourcePath, "sourcePath")));
        return Collections.unmodifiableMap(details);
    }

    private static List<String> ownedConcretePaths(
            EffectiveFragmentationCatalog catalog) {
        ArrayList<String> candidates = new ArrayList<>(Objects.requireNonNull(
                        catalog, "catalog").scopePlansByScope().values()
                .stream()
                .flatMap(plan -> plan.concreteChildPaths().stream())
                .map(JsonPointer::canonicalize)
                .filter(path -> !path.isEmpty())
                .distinct()
                .toList());
        candidates.sort(Comparator
                .comparingInt((String path) -> JsonPointer.split(path).size())
                .thenComparing(EmbeddingBinding.TEXT_ORDER));
        ArrayList<String> owned = new ArrayList<>();
        for (String candidate : candidates) {
            boolean behindManagedBoundary = owned.stream().anyMatch(
                    boundary -> descendantOf(candidate, boundary));
            if (!behindManagedBoundary) {
                owned.add(candidate);
            }
        }
        owned.sort(EmbeddingBinding.TEXT_ORDER);
        return List.copyOf(owned);
    }

    private static boolean descendantOf(String candidate, String ancestor) {
        List<String> child = JsonPointer.split(candidate);
        List<String> parent = JsonPointer.split(ancestor);
        return child.size() > parent.size()
                && child.subList(0, parent.size()).equals(parent);
    }

    private static LinkedHashMap<DocumentId, String> aliases(
            Set<DocumentId> documentIds,
            DocumentId rootId) {
        LinkedHashMap<DocumentId, String> aliases = new LinkedHashMap<>();
        int generated = 0;
        for (DocumentId documentId : documentIds) {
            String alias = documentId.equals(rootId)
                    ? ROOT_ALIAS
                    : "embedded-" + generated++;
            aliases.put(documentId, alias);
        }
        return aliases;
    }

    private static Map<DocumentId, List<DiscoveredOccurrence>>
            occurrencesBySource(List<DiscoveredOccurrence> occurrences) {
        LinkedHashMap<DocumentId, List<DiscoveredOccurrence>> result =
                new LinkedHashMap<>();
        for (DiscoveredOccurrence occurrence : occurrences) {
            result.computeIfAbsent(
                    occurrence.sourceDocumentId(),
                    ignored -> new ArrayList<>()).add(occurrence);
        }
        result.replaceAll((ignored, rows) -> List.copyOf(rows));
        return Collections.unmodifiableMap(result);
    }

    private static Node compilerReadyBody(
            DocumentId sourceDocumentId,
            Node sourceBody,
            Map<DocumentId, Node> exactMembers,
            Map<DocumentId, List<DiscoveredOccurrence>> bySource) {
        Node ready = Objects.requireNonNull(sourceBody, "sourceBody").clone();
        for (DiscoveredOccurrence occurrence : bySource.getOrDefault(
                sourceDocumentId, List.of())) {
            Node target = exactMembers.get(occurrence.targetDocumentId());
            if (target == null) {
                throw new IllegalStateException(
                        "Missing static target "
                                + occurrence.targetDocumentId());
            }
            Node representedTarget = target.clone();
            for (DiscoveredOccurrence nested : bySource.getOrDefault(
                    occurrence.targetDocumentId(), List.of())) {
                NodePathEditor.put(
                        representedTarget,
                        nested.path(),
                        preliminaryReference(nested.targetDocumentId()));
            }
            representedTarget.blueId(preliminaryBlueId(
                    occurrence.targetDocumentId()));
            NodePathEditor.put(ready, occurrence.path(), representedTarget);
        }
        return ready;
    }

    private static Node preliminaryReference(DocumentId target) {
        return new Node().blueId(preliminaryBlueId(target));
    }

    private static String preliminaryBlueId(DocumentId target) {
        return DirectBlueIdCalculator.calculateBlueId(
                new Node().value("scenario-target:" + Objects.requireNonNull(
                        target, "target").value()));
    }

    private static String requireText(String value, String label) {
        String checked = Objects.requireNonNull(value, label).trim();
        if (checked.isEmpty()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return checked;
    }

    /** Immutable result retaining the explicit compiler output and SDK labels. */
    public static final class CompiledStaticAdmission {
        private final Contracts10AuthoredClosureCompiler.CompiledClosure
                compiledClosure;
        private final DocumentId rootDocumentId;
        private final Map<String, DocumentId> documentsByAlias;
        private final Map<DocumentId, Node> authoredDocuments;
        private final List<DiscoveredOccurrence> occurrences;

        private CompiledStaticAdmission(
                Contracts10AuthoredClosureCompiler.CompiledClosure compiled,
                DocumentId rootDocumentId,
                Map<String, DocumentId> documentsByAlias,
                Map<DocumentId, Node> authoredDocuments,
                List<DiscoveredOccurrence> occurrences) {
            this.compiledClosure = Objects.requireNonNull(
                    compiled, "compiled");
            this.rootDocumentId = Objects.requireNonNull(
                    rootDocumentId, "rootDocumentId");
            this.documentsByAlias = Collections.unmodifiableMap(
                    new LinkedHashMap<>(Objects.requireNonNull(
                            documentsByAlias, "documentsByAlias")));
            LinkedHashMap<DocumentId, Node> authored = new LinkedHashMap<>();
            Objects.requireNonNull(authoredDocuments, "authoredDocuments")
                    .forEach((documentId, body) -> authored.put(
                            Objects.requireNonNull(documentId, "documentId"),
                            Objects.requireNonNull(body, "authored document")
                                    .clone()));
            this.authoredDocuments = Collections.unmodifiableMap(authored);
            this.occurrences = List.copyOf(Objects.requireNonNull(
                    occurrences, "occurrences"));
        }

        public Contracts10AuthoredClosureCompiler.CompiledClosure
                compiledClosure() {
            return compiledClosure;
        }

        public DocumentId rootDocumentId() {
            return rootDocumentId;
        }

        public Map<String, DocumentId> documentsByAlias() {
            return documentsByAlias;
        }

        public Node authoredDocument(DocumentId documentId) {
            Node selected = authoredDocuments.get(Objects.requireNonNull(
                    documentId, "documentId"));
            if (selected == null) {
                throw new IllegalArgumentException(
                        "Unknown static authored document " + documentId);
            }
            return selected.clone();
        }

        public int occurrenceCount() {
            return occurrences.size();
        }
    }

    private record ResolvedTarget(String blueId, Node body) {
        private ResolvedTarget {
            blueId = requireText(blueId, "blueId");
            body = body == null ? null : body.clone();
        }

        @Override
        public Node body() {
            return body == null ? null : body.clone();
        }
    }

    private record DiscoveredOccurrence(
            DocumentId sourceDocumentId,
            String path,
            DocumentId targetDocumentId) {
        private DiscoveredOccurrence {
            sourceDocumentId = Objects.requireNonNull(
                    sourceDocumentId, "sourceDocumentId");
            path = JsonPointer.canonicalize(Objects.requireNonNull(
                    path, "path"));
            targetDocumentId = Objects.requireNonNull(
                    targetDocumentId, "targetDocumentId");
        }
    }

    /** Read-only verified adapter installed ahead of bundled provider leaves. */
    private static final class VerifiedExactNodeProvider
            implements NodeProvider {
        private final ExactNodeProvider delegate;
        private final Map<String, Node> verified = new LinkedHashMap<>();
        private ProviderValueException lastFailure;

        private VerifiedExactNodeProvider(ExactNodeProvider delegate) {
            this.delegate = Objects.requireNonNull(delegate, "delegate");
        }

        @Override
        public synchronized List<Node> fetchByBlueId(String blueId) {
            Optional<Node> found = findExactNode(blueId);
            return found.map(node -> List.of(node.clone()))
                    .orElseGet(List::of);
        }

        private synchronized Optional<Node> findExactNode(String blueId) {
            String selected = requireText(blueId, "blueId");
            Node retained = verified.get(selected);
            if (retained != null) {
                return Optional.of(retained.clone());
            }
            Optional<String> supplied;
            try {
                supplied = Objects.requireNonNull(
                        delegate.findExactContent(selected),
                        "provider result");
            } catch (CoordinationException failure) {
                throw failure;
            } catch (RuntimeException invalid) {
                lastFailure = new ProviderValueException(
                        selected,
                        null,
                        "provider could not decode the retained exact node",
                        invalid);
                throw lastFailure;
            }
            if (supplied.isEmpty()) {
                return Optional.empty();
            }
            String serialized = Objects.requireNonNull(
                    supplied.orElseThrow(), "provider exact content");
            Node body;
            try {
                body = UncheckedObjectMapper.YAML_MAPPER.readValue(
                        serialized, Node.class);
            } catch (RuntimeException invalid) {
                lastFailure = new ProviderValueException(
                        selected,
                        null,
                        "provider could not decode the retained exact value",
                        invalid);
                throw lastFailure;
            }
            if (body.isReferenceOnly()) {
                lastFailure = new ProviderValueException(
                        selected,
                        null,
                        "provider returned another pure reference");
                throw lastFailure;
            }
            ExactValue exact = ExactValue.verified(body);
            if (!selected.equals(exact.blueId())) {
                lastFailure = new ProviderValueException(
                        selected,
                        exact.blueId(),
                        "provider returned content with a different exact "
                                + "BlueId");
                throw lastFailure;
            }
            verified.put(selected, exact.copyNode());
            return Optional.of(exact.copyNode());
        }

        private synchronized ProviderValueException lastFailure() {
            return lastFailure;
        }
    }

    private static final class ProviderValueException
            extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private final String expectedBlueId;
        private final String actualBlueId;

        private ProviderValueException(
                String expectedBlueId,
                String actualBlueId,
                String message) {
            this(expectedBlueId, actualBlueId, message, null);
        }

        private ProviderValueException(
                String expectedBlueId,
                String actualBlueId,
                String message,
                Throwable cause) {
            super(message, cause);
            this.expectedBlueId = requireText(
                    expectedBlueId, "expectedBlueId");
            this.actualBlueId = actualBlueId;
        }

        private String expectedBlueId() {
            return expectedBlueId;
        }

        private String actualBlueId() {
            return actualBlueId;
        }
    }
}
