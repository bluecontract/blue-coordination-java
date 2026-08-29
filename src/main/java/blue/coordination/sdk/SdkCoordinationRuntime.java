package blue.coordination.sdk;

import blue.coordination.api.ContractsClosureAdmissionReceipt;
import blue.coordination.api.ContractsExecutionPolicy;
import blue.coordination.api.CoordinationEngine;
import blue.coordination.api.CoordinationErrorCode;
import blue.coordination.api.CoordinationException;
import blue.coordination.api.DocumentId;
import blue.coordination.api.ExactValue;
import blue.coordination.api.Operation;
import blue.coordination.api.ProcessingDrainReceipt;
import blue.coordination.api.SessionStatus;
import blue.coordination.api.Timeline;
import blue.coordination.api.TimelineAppendReceipt;
import blue.coordination.api.TimelineEntry;
import blue.coordination.internal.BundledContracts10Release;
import blue.coordination.internal.Contracts10AuthoredClosureCompiler;
import blue.coordination.internal.Contracts10StaticEmbeddedAdmissionCompiler;
import blue.coordination.internal.ContractsManagedEpochSelectionPlan;
import blue.coordination.internal.ContractsManagedDraftPlan;
import blue.coordination.internal.DefaultCoordinationEngine;
import blue.language.model.Node;
import blue.language.model.NodePathEditor;
import blue.language.processor.ExternalOrderKey;
import blue.language.processor.ProcessorDiagnostic;
import blue.language.processor.closure.ClosureProcessResult;
import blue.language.processor.closure.ClosureResourceDemand;
import blue.language.snapshot.FrozenNode;

import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Package-private owner-safe adapter over the advanced Contracts engine. */
final class SdkCoordinationRuntime implements AutoCloseable {

    private final Object owner;
    private final DefaultCoordinationEngine engine;
    private final Contracts10AuthoredClosureCompiler compiler;
    private final Contracts10StaticEmbeddedAdmissionCompiler staticCompiler;
    private final SdkDrainResultMapper mapper;
    private final ScopedExactNodeProvider exactNodeProvider;
    private final boolean contentDerivedDocumentIds;
    private final String languageSpecificationIdentity;
    private final String contractsSpecificationIdentity;
    private final boolean bundledRelease;
    private final Map<String, TimelineHandle> timelines =
            new LinkedHashMap<>();
    private final Map<String, EntryIntent> intents = new LinkedHashMap<>();
    private final Map<String, EntryResult> retainedResults =
            new LinkedHashMap<>();
    private boolean closed;

    private SdkCoordinationRuntime(
            Object owner,
            String languageIdentity,
            String contractsIdentity,
            ExactNodeProvider exactNodeProvider,
            boolean contentDerivedDocumentIds,
            ContractsExecutionPolicy contractsExecutionPolicy) {
        this.owner = Objects.requireNonNull(owner, "owner");
        this.exactNodeProvider = new ScopedExactNodeProvider(
                Objects.requireNonNull(
                        exactNodeProvider, "exactNodeProvider"));
        this.contentDerivedDocumentIds = contentDerivedDocumentIds;
        BundledContracts10Release.Manifest bundled =
                BundledContracts10Release.manifest();
        String language = languageIdentity == null
                ? bundled.blueLanguageSpecification()
                : languageIdentity;
        String contracts = contractsIdentity == null
                ? bundled.contractsSpecification()
                : contractsIdentity;
        languageSpecificationIdentity = language;
        contractsSpecificationIdentity = contracts;
        bundledRelease = languageIdentity == null;
        engine = DefaultCoordinationEngine.createContracts10Sdk(
                language,
                contracts,
                this.exactNodeProvider,
                Objects.requireNonNull(
                        contractsExecutionPolicy,
                        "contractsExecutionPolicy"));
        compiler = new Contracts10AuthoredClosureCompiler(engine);
        staticCompiler = new Contracts10StaticEmbeddedAdmissionCompiler(engine);
        mapper = new SdkDrainResultMapper(this, engine);
    }

    static SdkCoordinationRuntime create(
            Object owner,
            String languageIdentity,
            String contractsIdentity,
            ExactNodeProvider exactNodeProvider,
            boolean contentDerivedDocumentIds) {
        return create(
                owner,
                languageIdentity,
                contractsIdentity,
                exactNodeProvider,
                contentDerivedDocumentIds,
                ContractsExecutionPolicy.releaseDefault());
    }

    static SdkCoordinationRuntime create(
            Object owner,
            String languageIdentity,
            String contractsIdentity,
            ExactNodeProvider exactNodeProvider,
            boolean contentDerivedDocumentIds,
            ContractsExecutionPolicy contractsExecutionPolicy) {
        return new SdkCoordinationRuntime(
                owner,
                languageIdentity,
                contractsIdentity,
                exactNodeProvider,
                contentDerivedDocumentIds,
                contractsExecutionPolicy);
    }

    synchronized CoordinationEngine engine() {
        ensureOpen();
        return engine;
    }

    synchronized Optional<ManagedOccurrenceAudit> auditManagedOccurrence(
            DocumentId sourceDocumentId,
            String sourcePath) {
        ensureOpen();
        return engine.auditManagedOccurrence(
                        Objects.requireNonNull(
                                sourceDocumentId, "sourceDocumentId"),
                        SdkPreconditions.requireOccurrencePath(sourcePath))
                .map(audit -> new ManagedOccurrenceAudit(
                        audit.targetDocumentId(),
                        audit.activationGeneration(),
                        audit.active()));
    }

    synchronized Optional<ManagedEpochReceipt> auditManagedEpoch(
            DocumentId documentId,
            long epoch) {
        ensureOpen();
        return engine.auditManagedEpoch(
                        Objects.requireNonNull(documentId, "documentId"), epoch)
                .map(this::publicManagedEpochReceipt);
    }

    synchronized List<ManagedEpochReceipt> auditManagedEpochs(
            DocumentId documentId) {
        ensureOpen();
        return engine.auditManagedEpochs(Objects.requireNonNull(
                        documentId, "documentId"))
                .stream()
                .map(this::publicManagedEpochReceipt)
                .toList();
    }

    synchronized Optional<ManagedEpochReceipt> auditManagedEpochReceipt(
            String receiptIdentity) {
        ensureOpen();
        return engine.auditManagedEpochReceipt(receiptIdentity)
                .map(this::publicManagedEpochReceipt);
    }

    synchronized List<OperationRouteSnapshot> auditOperationRoutes(
            DocumentId documentId) {
        ensureOpen();
        return engine.auditOperationRoutes(Objects.requireNonNull(
                        documentId, "documentId"))
                .stream()
                .map(route -> new OperationRouteSnapshot(
                        route.scopePath(),
                        route.operation(),
                        route.channel(),
                        route.requestPattern().map(ExactBlueValue::wrap),
                        route.acceptedSources().stream()
                                .map(source -> new TimelineSourceSnapshot(
                                        source.timelineId(),
                                        source.actorId()))
                                .toList()))
                .toList();
    }

    String languageSpecificationIdentity() {
        return languageSpecificationIdentity;
    }

    String contractsSpecificationIdentity() {
        return contractsSpecificationIdentity;
    }

    Optional<String> bundledIdentity(String name) {
        if (!bundledRelease) {
            return Optional.empty();
        }
        BundledContracts10Release.Manifest manifest =
                BundledContracts10Release.manifest();
        return Optional.of(switch (name) {
            case "release" -> manifest.contractsRelease();
            case "fixtures" -> manifest.fixturePackage();
            case "gas" -> manifest.gasManifest();
            case "finalizer" -> manifest.cyclicFinalizer();
            case "verifier" -> manifest.cyclicProofVerifier();
            default -> throw new IllegalArgumentException(
                    "Unknown bundled identity " + name);
        });
    }

    synchronized TimelineHandle registerTimeline(
            String timelineId,
            String accountId) {
        return registerTimeline(
                timelineId, accountId, TimelineActorKind.PRINCIPAL);
    }

    synchronized TimelineHandle registerTimeline(
            String timelineId,
            String accountId,
            TimelineActorKind actorKind) {
        ensureOpen();
        TimelineActorKind kind = Objects.requireNonNull(
                actorKind, "actorKind");
        Timeline registered = engine.registerTimeline(
                requireText(timelineId, "timelineId"),
                requireText(accountId, "accountId"));
        engine.registerTimelineActorType(
                registered.timelineId(), kind.blueType());
        TimelineHandle handle = new TimelineHandle(
                owner, registered.timelineId(), registered.actorId(), kind);
        TimelineHandle prior = timelines.putIfAbsent(
                registered.timelineId(), handle);
        if (prior != null && prior.actorKind() != kind) {
            throw new IllegalArgumentException(
                    "Timeline " + registered.timelineId()
                            + " already uses " + prior.actorKind());
        }
        return prior == null ? handle : prior;
    }

    synchronized ExactBlueValue exactValue(String sourceYaml) {
        ensureOpen();
        return ExactBlueValue.wrap(engine.exactValue(
                Objects.requireNonNull(sourceYaml, "sourceYaml")));
    }

    synchronized ExactBlueValue exactProviderValue(String sourceYaml) {
        ensureOpen();
        return ExactBlueValue.wrap(engine.exactProviderValue(
                Objects.requireNonNull(sourceYaml, "sourceYaml")));
    }

    synchronized ManagedDocumentDraft draft(
            DocumentId id,
            ExactBlueValue initial) {
        ensureOpen();
        return new ManagedDocumentDraft(owner, id, initial);
    }

    synchronized DocumentHandle admit(ManagedDocument definition) {
        ensureOpen();
        ManagedDocument selected = Objects.requireNonNull(
                definition, "definition");
        if (!selected.isPublicRoot()) {
            throw new IllegalArgumentException(
                    "TOP_LEVEL_ADMISSION_REQUIRES_PUBLIC_ROOT: "
                            + selected.id());
        }
        String alias = selected.id().value();
        Contracts10AuthoredClosureCompiler.CompilationRequest request =
                new Contracts10AuthoredClosureCompiler.CompilationRequest(
                        List.of(new Contracts10AuthoredClosureCompiler
                                .AuthoredDocument(
                                        selected.id(),
                                        selected.authoredYaml())),
                        Map.of(alias, selected.id()),
                        List.of(),
                        Set.of(selected.id()),
                        activationInputs(selected.activationPolicy()));
        Contracts10AuthoredClosureCompiler.CompiledClosure compiled =
                contentDerivedDocumentIds
                        ? compiler.compileContentIdentified(request)
                        : compiler.compile(request);
        admitCompiled(compiled, Set.of(selected.id()));
        return requireDocument(selected.id());
    }

    synchronized ClosureHandle admitStaticProcessEmbedded(
            String authoredYaml) {
        return admitStaticProcessEmbedded(
                authoredYaml, ActivationPolicy.fromNow(), List.of());
    }

    synchronized ClosureHandle admitStaticProcessEmbedded(
            String authoredYaml,
            ActivationPolicy activationPolicy) {
        return admitStaticProcessEmbedded(
                authoredYaml, activationPolicy, List.of());
    }

    synchronized ClosureHandle admitStaticProcessEmbedded(
            String authoredYaml,
            List<ManagedEpochSelector> selectors) {
        return admitStaticProcessEmbedded(
                authoredYaml, ActivationPolicy.fromNow(), selectors);
    }

    synchronized ClosureHandle admitStaticProcessEmbedded(
            String authoredYaml,
            ActivationPolicy activationPolicy,
            List<ManagedEpochSelector> selectors) {
        ensureOpen();
        List<ManagedEpochSelector> selectedSelectors = List.copyOf(
                Objects.requireNonNull(selectors, "selectors"));
        exactNodeProvider.beginLookupScope();
        try {
            return admitStaticProcessEmbeddedScoped(
                    authoredYaml, activationPolicy, selectedSelectors);
        } finally {
            exactNodeProvider.endLookupScope();
        }
    }

    private ClosureHandle admitStaticProcessEmbeddedScoped(
            String authoredYaml,
            ActivationPolicy activationPolicy,
            List<ManagedEpochSelector> selectors) {
        Contracts10StaticEmbeddedAdmissionCompiler.CompiledStaticAdmission
                selected = staticCompiler.compile(
                        Objects.requireNonNull(authoredYaml, "authoredYaml"),
                        exactNodeProvider,
                        activationInputs(Objects.requireNonNull(
                                activationPolicy, "activationPolicy")));
        DocumentId rootId = selected.rootDocumentId();
        engine.authorizeContractsPublicRoots(Set.of(rootId));
        Contracts10AuthoredClosureCompiler.ActivationInputs activation =
                selected.activationInputs();
        ContractsManagedEpochSelectionPlan selectionPlan =
                staticManagedEpochSelectionPlan(
                        rootId,
                        selectors);
        ContractsClosureAdmissionReceipt receipt = selectionPlan == null
                ? engine.admitContractsClosure(
                        selected.invocation(),
                        activation.policy(),
                        activation.verifiedFrontier(),
                        exactNodeProvider)
                : engine.admitContractsClosure(
                        selected.invocation(),
                        activation.policy(),
                        activation.verifiedFrontier(),
                        exactNodeProvider,
                        selectionPlan);
        requirePublishedAdmission(receipt);
        ClosureProcessResult result = receipt.attempt().processResult();
        List<ClosureOccurrenceSnapshot> occurrences = result
                .occurrenceBindings().stream()
                .map(binding -> new ClosureOccurrenceSnapshot(
                        DocumentId.of(binding.sourceDocumentId().value()),
                        binding.sourcePath(),
                        binding.activationGeneration(),
                        DocumentId.of(binding.targetDocumentId().value()),
                        binding.expectedTargetBlueId(),
                        binding.active()))
                .toList();
        LinkedHashMap<String, DocumentId> aliases = staticAdmissionAliases(
                rootId, result, occurrences);
        LinkedHashMap<String, DocumentHandle> handles = new LinkedHashMap<>();
        LinkedHashMap<String, ExactBlueValue> authored = new LinkedHashMap<>();
        aliases.forEach((alias, documentId) -> {
            handles.put(alias, requireDocument(documentId));
            ExactValue initial = engine.history(documentId).get(0).before()
                    .orElseThrow(() -> new IllegalStateException(
                            "Static admission lost authored history for "
                                    + documentId));
            authored.put(alias, ExactBlueValue.wrap(initial));
        });
        return new ClosureHandle(
                owner,
                result.outputClosureIdentity(),
                handles,
                Set.of("root"),
                authored,
                occurrences);
    }

    private static ContractsManagedEpochSelectionPlan
            staticManagedEpochSelectionPlan(
                    DocumentId rootId,
                    List<ManagedEpochSelector> selectors) {
        List<ManagedEpochSelector> selected = List.copyOf(
                Objects.requireNonNull(selectors, "selectors"));
        if (selected.isEmpty()) {
            return null;
        }
        return new ContractsManagedEpochSelectionPlan(
                Objects.requireNonNull(rootId, "rootId"),
                -1L,
                rootId.value(),
                selected.stream()
                        .map(selector -> new ContractsManagedEpochSelectionPlan
                                .Selection(
                                        selector.sourceDocumentId(),
                                        selector.sourceEpoch(),
                                        selector.expectedSourceBlueId(),
                                        selector.targetOccurrencePath()))
                        .toList());
    }

    private static LinkedHashMap<String, DocumentId> staticAdmissionAliases(
            DocumentId rootId,
            ClosureProcessResult result,
            List<ClosureOccurrenceSnapshot> occurrences) {
        Comparator<ClosureOccurrenceSnapshot> occurrenceOrder = Comparator
                .comparing(ClosureOccurrenceSnapshot::sourcePath)
                .thenComparing(row -> row.targetDocumentId().value())
                .thenComparing(ClosureOccurrenceSnapshot::expectedTargetBlueId);
        LinkedHashMap<DocumentId, List<ClosureOccurrenceSnapshot>> outgoing =
                new LinkedHashMap<>();
        occurrences.forEach(row -> outgoing
                .computeIfAbsent(row.sourceDocumentId(), ignored ->
                        new ArrayList<>())
                .add(row));
        outgoing.values().forEach(rows -> rows.sort(occurrenceOrder));

        LinkedHashMap<String, DocumentId> aliases = new LinkedHashMap<>();
        LinkedHashSet<DocumentId> visited = new LinkedHashSet<>();
        ArrayDeque<DocumentId> queue = new ArrayDeque<>();
        aliases.put("root", rootId);
        visited.add(rootId);
        queue.add(rootId);
        int embedded = 0;
        while (!queue.isEmpty()) {
            DocumentId source = queue.removeFirst();
            for (ClosureOccurrenceSnapshot row
                    : outgoing.getOrDefault(source, List.of())) {
                if (visited.add(row.targetDocumentId())) {
                    aliases.put("embedded-" + embedded++,
                            row.targetDocumentId());
                    queue.addLast(row.targetDocumentId());
                }
            }
        }
        ArrayList<DocumentId> remaining = result.resultingDocuments().stream()
                .map(document -> DocumentId.of(
                        document.documentId().value()))
                .filter(visited::add)
                .sorted(Comparator.comparing(DocumentId::value))
                .collect(java.util.stream.Collectors.toCollection(
                        ArrayList::new));
        for (DocumentId documentId : remaining) {
            aliases.put("embedded-" + embedded++, documentId);
        }
        return aliases;
    }

    synchronized ClosureHandle admit(ManagedClosure definition) {
        ensureOpen();
        ManagedClosure selected = Objects.requireNonNull(
                definition, "definition");
        ArrayList<Contracts10AuthoredClosureCompiler.AuthoredDocument>
                documents = new ArrayList<>();
        LinkedHashMap<String, DocumentId> aliases = new LinkedHashMap<>();
        selected.members().forEach((alias, member) -> {
            documents.add(new Contracts10AuthoredClosureCompiler
                    .AuthoredDocument(member.id(), member.authoredYaml()));
            aliases.put(alias, member.id());
        });
        List<Contracts10AuthoredClosureCompiler.OccurrenceBinding> bindings =
                selected.bindings().stream()
                        .map(binding -> new Contracts10AuthoredClosureCompiler
                                .OccurrenceBinding(
                                        binding.sourceAlias(),
                                        binding.path(),
                                        binding.targetAlias()))
                        .toList();
        LinkedHashSet<DocumentId> roots = new LinkedHashSet<>();
        selected.publicRoots().forEach(alias -> roots.add(
                selected.members().get(alias).id()));
        Contracts10AuthoredClosureCompiler.CompilationRequest request =
                new Contracts10AuthoredClosureCompiler.CompilationRequest(
                        documents,
                        aliases,
                        bindings,
                        roots,
                        activationInputs(selected.activationPolicy()));
        Contracts10AuthoredClosureCompiler.CompiledClosure compiled =
                contentDerivedDocumentIds
                        ? compiler.compileContentIdentified(request)
                        : compiler.compile(request);
        ContractsClosureAdmissionReceipt receipt = admitCompiled(
                compiled, roots);
        LinkedHashMap<String, DocumentHandle> handles = new LinkedHashMap<>();
        selected.members().forEach((alias, member) -> handles.put(
                alias, requireDocument(member.id())));
        return new ClosureHandle(
                owner,
                receipt.attempt().processResult().outputClosureIdentity(),
                handles,
                selected.publicRoots());
    }

    synchronized DocumentHandle requireDocument(DocumentId id) {
        ensureOpen();
        engine.document(Objects.requireNonNull(id, "id"));
        return new SdkDocumentHandle(this, id);
    }

    synchronized DocumentHandle promotePublicRoot(DocumentId id) {
        ensureOpen();
        DocumentId selected = Objects.requireNonNull(id, "id");
        engine.promoteContractsPublicRoot(selected);
        return new SdkDocumentHandle(this, selected);
    }

    synchronized TargetSelection selectTarget(DocumentHandle document) {
        ensureOpen();
        DocumentHandle selected = Objects.requireNonNull(
                document, "document");
        if (!(selected instanceof SdkDocumentHandle handle)
                || handle.runtime != this) {
            throw new IllegalArgumentException(
                    "Document handle belongs to another Coordination instance");
        }
        DocumentId id = handle.id();
        blue.coordination.api.DocumentSnapshot snapshot = engine.document(id);
        return new TargetSelection(
                id,
                ExactBlueValue.wrap(snapshot.current()),
                snapshot.epoch(),
                true,
                requireExactTarget(snapshot),
                null);
    }

    synchronized TargetSelection selectTarget(DocumentId documentId) {
        ensureOpen();
        DocumentId id = Objects.requireNonNull(documentId, "documentId");
        try {
            blue.coordination.api.DocumentSnapshot snapshot =
                    engine.document(id);
            return new TargetSelection(
                    id,
                    ExactBlueValue.wrap(snapshot.current()),
                    snapshot.epoch(),
                    true,
                    requireExactTarget(snapshot),
                    null);
        } catch (CoordinationException failure) {
            ExactBlueValue lineageEvidence = ExactBlueValue.wrap(
                    ExactValue.verified(new Node().properties(
                            "documentId", new Node().value(id.value()))));
            return new TargetSelection(
                    id,
                    lineageEvidence,
                    -1L,
                    false,
                    true,
                    "document is not managed");
        }
    }

    private boolean requireExactTarget(
            blue.coordination.api.DocumentSnapshot snapshot) {
        return snapshot.status() != SessionStatus.CATCHING_UP;
    }

    synchronized EntryHandle submitOperation(OperationCall call) {
        ensureOpen();
        return appendOperation(Objects.requireNonNull(call, "call"));
    }

    synchronized EntryResult executeOperation(OperationCall call) {
        ensureOpen();
        EntryHandle handle = appendOperation(
                Objects.requireNonNull(call, "call"));
        TimelineEntry entry = requireCoreEntry(handle);
        return terminalResult(handle, engine.drainThrough(
                entry.sourceOrderKey()));
    }

    synchronized EntryHandle submitEvent(EventCall call) {
        ensureOpen();
        return appendEvent(Objects.requireNonNull(call, "call"));
    }

    synchronized EntryResult executeEvent(EventCall call) {
        ensureOpen();
        EntryHandle handle = appendEvent(Objects.requireNonNull(call, "call"));
        TimelineEntry entry = requireCoreEntry(handle);
        return terminalResult(handle, engine.drainThrough(
                entry.sourceOrderKey()));
    }

    synchronized DrainResult drain() {
        ensureOpen();
        return retain(mapper.map(engine.drain()));
    }

    synchronized DrainResult drain(DrainBudget budget) {
        ensureOpen();
        DrainBudget selected = Objects.requireNonNull(budget, "budget");
        return retain(mapper.map(engine.drain(
                new CoordinationEngine.DrainBudget(
                        selected.maxCommittedProcessTransitions(),
                        selected.maxSelectedEntries()))));
    }

    synchronized DrainResult drainJournal(DrainBudget budget) {
        ensureOpen();
        DrainBudget selected = Objects.requireNonNull(budget, "budget");
        return retain(mapper.map(engine.drainJournal(
                new CoordinationEngine.DrainBudget(
                        selected.maxCommittedProcessTransitions(),
                        selected.maxSelectedEntries()))));
    }

    synchronized DrainResult drainManagedEpochApplication(
            String expectedWorkIdentity) {
        ensureOpen();
        return retain(mapper.map(engine.drainManagedEpochApplication(
                Objects.requireNonNull(
                        expectedWorkIdentity, "expectedWorkIdentity"))));
    }

    synchronized EntryIntent intent(String entryBlueId) {
        return intents.getOrDefault(
                Objects.requireNonNull(entryBlueId, "entryBlueId"),
                EntryIntent.broadcast());
    }

    synchronized TimelineEntry retainedCoreEntry(String entryBlueId) {
        String selected = Objects.requireNonNull(entryBlueId, "entryBlueId");
        CoreEntryRef ref = coreEntries.get(selected);
        return ref == null
                ? engine.auditTimelineEntry(selected).orElse(null)
                : ref.entry();
    }

    synchronized Optional<TimelineEntrySnapshot> auditTimelineEntry(
            String entryBlueId) {
        ensureOpen();
        return engine.auditTimelineEntry(Objects.requireNonNull(
                        entryBlueId, "entryBlueId"))
                .map(this::publicTimelineEntry);
    }

    synchronized List<TimelineEntrySnapshot> auditTimelineEntries() {
        ensureOpen();
        return engine.auditTimelineEntries().stream()
                .map(this::publicTimelineEntry)
                .toList();
    }

    synchronized List<TimelineEntrySnapshot> auditTimeline(
            String timelineId) {
        ensureOpen();
        String selected = requireText(timelineId, "timelineId");
        return engine.auditTimeline(selected).stream()
                .map(this::publicTimelineEntry)
                .toList();
    }

    synchronized EntryHandle handle(TimelineEntry entry) {
        TimelineHandle timeline = timelineHandle(entry);
        return new EntryHandle(
                owner,
                timeline,
                entry.blueId(),
                entry.globalSequence(),
                entry.timelineSequence());
    }

    private TimelineHandle timelineHandle(TimelineEntry entry) {
        return timelines.computeIfAbsent(
                entry.timeline().timelineId(),
                ignored -> new TimelineHandle(
                        owner,
                        entry.timeline().timelineId(),
                        entry.timeline().actorId(),
                        engine.timelineActorKind(entry.timeline().timelineId())
                                .equals("MyOS/MyOS Agent Actor")
                                ? TimelineActorKind.AGENT
                                : TimelineActorKind.PRINCIPAL));
    }

    private TimelineEntrySnapshot publicTimelineEntry(TimelineEntry entry) {
        FrozenNode previous = entry.exactEvent().canonicalAt("/prevEntry");
        String previousBlueId = previous == null
                ? null
                : previous.isReferenceOnly()
                        ? previous.getReferenceBlueId()
                        : previous.blueId();
        return new TimelineEntrySnapshot(
                ExactBlueValue.wrap(entry.exactEvent()),
                timelineHandle(entry),
                Optional.ofNullable(previousBlueId),
                entry.operation(),
                entry.channel(),
                entry.timestampMicros(),
                entry.globalSequence(),
                entry.timelineSequence());
    }

    synchronized EntryHandle lightweightHandle(String blueId) {
        return new EntryHandle(owner, blueId);
    }

    synchronized List<DocumentRevision> history(DocumentId id) {
        ensureOpen();
        long readyEpoch = engine.document(id).epoch();
        return engine.history(id).stream()
                .filter(revision -> revision.epoch() <= readyEpoch)
                .map(this::publicRevision)
                .toList();
    }

    synchronized DocumentSnapshot snapshot(DocumentId id) {
        ensureOpen();
        blue.coordination.api.DocumentSnapshot snapshot =
                engine.document(id);
        return new DocumentSnapshot(
                id,
                snapshot.epoch(),
                true,
                ExactBlueValue.wrap(snapshot.current()),
                publicEventsAt(id, snapshot.epoch()));
    }

    synchronized ExactBlueValue current(DocumentId id) {
        ensureOpen();
        return ExactBlueValue.wrap(engine.document(id).current());
    }

    synchronized Optional<ExactNodeEvidence> auditExactNodeEvidence(
            DocumentId id) {
        ensureOpen();
        DocumentId selected = Objects.requireNonNull(id, "id");
        if (engine.auditManagedDocumentReadiness(selected).isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(ExactBlueValue.wrap(
                engine.auditDocument(selected).current()).providerEvidence());
    }

    @Override
    public synchronized void close() {
        if (!closed) {
            closed = true;
            engine.close();
            timelines.clear();
            intents.clear();
            retainedResults.clear();
            coreEntries.clear();
        }
    }

    private ContractsClosureAdmissionReceipt admitCompiled(
            Contracts10AuthoredClosureCompiler.CompiledClosure compiled,
            Set<DocumentId> roots) {
        engine.authorizeContractsPublicRoots(roots);
        Contracts10AuthoredClosureCompiler.ActivationInputs activation =
                compiled.activationInputs();
        ContractsClosureAdmissionReceipt receipt =
                engine.admitContractsClosure(
                        compiled.invocation(),
                        activation.policy(),
                        activation.verifiedFrontier());
        requirePublishedAdmission(receipt);
        return receipt;
    }

    private static void requirePublishedAdmission(
            ContractsClosureAdmissionReceipt receipt) {
        if (receipt.published()) {
            return;
        }
        if (!receipt.attempt().isComplete()) {
            LinkedHashMap<String, String> details = new LinkedHashMap<>();
            List<String> required = receipt.attempt().requiredExactBlueIds();
            if (!required.isEmpty()) {
                details.put("blueId", required.get(0));
            }
            List<ClosureResourceDemand> demands =
                    receipt.attempt().resourceDemands();
            if (!demands.isEmpty()) {
                ClosureResourceDemand first = demands.get(0);
                details.put("sourceDocumentId",
                        first.sourceDocumentId().value());
                details.put("sourcePath", first.sourcePath());
                details.putIfAbsent("blueId", first.suppliedValueBlueId());
            }
            throw new CoordinationException(
                    CoordinationErrorCode.NEEDS_RESOURCES,
                    "ADMISSION_NEEDS_RESOURCES: "
                            + required,
                    null,
                    details);
        }
        throw admissionRejected(receipt.attempt().processResult());
    }

    private static CoordinationException admissionRejected(
            ClosureProcessResult result) {
        ProcessorDiagnostic diagnostic = result.diagnostic();
        String status = result.status().wireValue();
        String message = diagnostic == null
                || diagnostic.message() == null
                || diagnostic.message().isBlank()
                ? "Contracts admission rejected with " + status
                : diagnostic.message();
        LinkedHashMap<String, String> details = new LinkedHashMap<>();
        details.put("processorStatus", status);
        details.put("invocationIdentity", result.invocationIdentity());
        if (diagnostic != null) {
            details.put("processorCategory",
                    diagnostic.category().name());
        }
        return new CoordinationException(
                CoordinationErrorCode.FROZEN_PROCESSING_FAILED,
                message,
                null,
                details,
                result.status(),
                diagnostic);
    }

    private EntryHandle appendOperation(OperationCall call) {
        requireOwned(call.timeline());
        ManagedDraftEvidence managed = managedDraftEvidence(call);
        ContractsManagedEpochSelectionPlan epochSelections =
                managedEpochSelectionPlan(call);
        ExactValue request;
        if (call.requestYaml() != null) {
            request = engine.exactValue(call.requestYaml());
        } else if (call.request() != null) {
            request = call.request().exactRequest(engine);
        } else {
            request = engine.exactValue("{}");
        }
        TargetSelection target = call.target();
        Operation operation = Operation.exact(
                call.operation(), call.channel(), request)
                .targeting(
                        target.exact().unwrap(),
                        target.requireExactDocumentVersion());
        Timeline timeline = new Timeline(
                call.timeline().id(), call.timeline().accountId());
        TimelineEntry appended;
        if ((managed == null && epochSelections == null)
                || !target.presentAtSelection()) {
            // Preserve the ordinary precise zero-attempt target diagnostic.
            // A missing target cannot own an affected closure or new lineage.
            appended = engine.append(timeline, operation);
        } else {
            ContractsManagedDraftPlan draftPlan = managed == null
                    ? null : new ContractsManagedDraftPlan(
                            target.id(),
                            target.epochAtSelection(),
                            target.exact().blueId(),
                            managed.drafts(),
                            managed.requestFields(),
                            managed.expectedOccurrences());
            if (draftPlan != null && epochSelections != null) {
                appended = engine.append(
                        timeline,
                        operation,
                        draftPlan,
                        epochSelections);
            } else if (draftPlan != null) {
                appended = engine.append(timeline, operation, draftPlan);
            } else {
                appended = engine.append(
                        timeline, operation, epochSelections);
            }
        }
        intents.put(appended.blueId(), EntryIntent.targeted(
                target,
                call.operation(),
                call.channel(),
                call.timeline()));
        return retainCoreEntry(appended);
    }

    private ContractsManagedEpochSelectionPlan managedEpochSelectionPlan(
            OperationCall call) {
        List<ManagedEpochSelector> selectors = call.managedEpochSelectors();
        if (selectors.isEmpty()) {
            return null;
        }
        TargetSelection target = call.target();
        if (!target.presentAtSelection()) {
            throw new IllegalArgumentException(
                    "MANAGED_EPOCH_SELECTOR_TARGET_MISMATCH: absent "
                            + target.id());
        }
        return new ContractsManagedEpochSelectionPlan(
                target.id(),
                target.epochAtSelection(),
                target.exact().blueId(),
                selectors.stream()
                        .map(selector -> new ContractsManagedEpochSelectionPlan
                                .Selection(
                                        selector.sourceDocumentId(),
                                        selector.sourceEpoch(),
                                        selector.expectedSourceBlueId(),
                                        selector.targetOccurrencePath()))
                        .toList());
    }

    private ManagedDraftEvidence managedDraftEvidence(OperationCall call) {
        RequestBuilder request = call.request();
        Map<String, ManagedDocumentDraft> requestDrafts = request == null
                ? Map.of()
                : request.managedEvidence();
        List<OperationCall.OccurrenceExpectation> expectations =
                call.expectations();
        if (requestDrafts.isEmpty() && expectations.isEmpty()) {
            return null;
        }
        if (requestDrafts.isEmpty()) {
            throw new IllegalArgumentException(
                    "MANAGED_OCCURRENCE_DRAFT_NOT_REQUESTED: every expected "
                            + "occurrence must name a managed request draft");
        }
        if (expectations.isEmpty()) {
            throw new IllegalArgumentException(
                    "MANAGED_DRAFT_NOT_EXPECTED: every managed request draft "
                            + "must have an expected occurrence");
        }

        LinkedHashMap<DocumentId, ContractsManagedDraftPlan.ManagedDraft>
                drafts = new LinkedHashMap<>();
        LinkedHashMap<String, DocumentId> requestFields =
                new LinkedHashMap<>();
        requestDrafts.forEach((field, draft) -> {
            ManagedDocumentDraft selected = requireOwnedDraft(draft);
            if (selected.id().equals(call.target().id())) {
                throw new IllegalArgumentException(
                        "MANAGED_DRAFT_TARGET_COLLISION: a new draft cannot "
                                + "reuse the operation target lineage");
            }
            ContractsManagedDraftPlan.ManagedDraft exact = managedDraft(
                    selected);
            ContractsManagedDraftPlan.ManagedDraft prior = drafts.putIfAbsent(
                    selected.id(), exact);
            if (prior != null && !sameManagedDraft(prior, exact)) {
                throw new IllegalArgumentException(
                        "MANAGED_DRAFT_IDENTITY_CONFLICT: request fields "
                                + "supply different evidence for "
                                + selected.id());
            }
            requestFields.put(field, selected.id());
        });

        LinkedHashSet<String> paths = new LinkedHashSet<>();
        LinkedHashSet<DocumentId> expectedDrafts = new LinkedHashSet<>();
        ArrayList<ContractsManagedDraftPlan.ExpectedOccurrence> occurrences =
                new ArrayList<>();
        for (OperationCall.OccurrenceExpectation expectation : expectations) {
            ManagedDocumentDraft draft = requireOwnedDraft(
                    expectation.draft());
            ContractsManagedDraftPlan.ManagedDraft requested = drafts.get(
                    draft.id());
            ContractsManagedDraftPlan.ManagedDraft expected = managedDraft(
                    draft);
            if (requested == null || !sameManagedDraft(requested, expected)) {
                throw new IllegalArgumentException(
                        "MANAGED_OCCURRENCE_DRAFT_NOT_REQUESTED: "
                                + expectation.path() + " names draft "
                                + draft.id() + " without matching managed "
                                + "request evidence");
            }
            if (!paths.add(expectation.path())) {
                throw new IllegalArgumentException(
                        "DUPLICATE_MANAGED_OCCURRENCE_PATH: "
                                + expectation.path());
            }
            ActivationPolicy policy = expectation.resolvedPolicy(
                    call.activation());
            if (policy.kind() != ActivationPolicy.Kind.FROM_NOW
                    || policy.activationMode()
                    != blue.coordination.api.ActivationMode
                            .BIRTH_AT_ATTACHMENT) {
                throw new UnsupportedOperationException(
                        "UNSUPPORTED_MANAGED_DRAFT_ACTIVATION_POLICY: "
                                + policy.kind()
                                + "; only FROM_NOW is currently supported");
            }
            expectedDrafts.add(draft.id());
            occurrences.add(new ContractsManagedDraftPlan.ExpectedOccurrence(
                    expectation.path(),
                    draft.id(),
                    policy.activationMode()));
        }
        if (!expectedDrafts.equals(drafts.keySet())) {
            LinkedHashSet<DocumentId> missing = new LinkedHashSet<>(
                    drafts.keySet());
            missing.removeAll(expectedDrafts);
            throw new IllegalArgumentException(
                    "MANAGED_DRAFT_NOT_EXPECTED: " + missing);
        }
        return new ManagedDraftEvidence(
                drafts, requestFields, occurrences);
    }

    private ManagedDocumentDraft requireOwnedDraft(
            ManagedDocumentDraft draft) {
        ManagedDocumentDraft selected = Objects.requireNonNull(
                draft, "draft");
        if (selected.owner() != owner) {
            throw new IllegalArgumentException(
                    "MANAGED_DRAFT_OWNER_MISMATCH: draft " + selected.id()
                            + " belongs to another Coordination instance");
        }
        return selected;
    }

    private ContractsManagedDraftPlan.ManagedDraft managedDraft(
            ManagedDocumentDraft draft) {
        if (draft.knownEpoch().isPresent()) {
            throw new UnsupportedOperationException(
                    "UNSUPPORTED_MANAGED_DRAFT_IMPORT: draft " + draft.id()
                            + " pins historical epoch "
                            + draft.knownEpoch().getAsLong()
                            + "; only new FROM_NOW lineages are currently "
                            + "supported");
        }
        return new ContractsManagedDraftPlan.ManagedDraft(
                draft.id(),
                draft.initial().unwrap(),
                null,
                contentDerivedDocumentIds);
    }

    private static boolean sameManagedDraft(
            ContractsManagedDraftPlan.ManagedDraft left,
            ContractsManagedDraftPlan.ManagedDraft right) {
        return left.documentId().equals(right.documentId())
                && left.initial().sameExactValue(right.initial())
                && Objects.equals(left.knownEpoch(), right.knownEpoch())
                && left.contentDerivedIdentity()
                == right.contentDerivedIdentity();
    }

    private EntryHandle appendEvent(EventCall call) {
        requireOwned(call.timeline());
        Node exactEnvelope = call.event().unwrap().copyNode();
        String timelineId = requiredText(
                exactEnvelope, "/timeline/timelineId");
        String actorId = requiredText(exactEnvelope, "/actor/accountId");
        if (!timelineId.equals(call.timeline().id())
                || !actorId.equals(call.timeline().accountId())) {
            throw new IllegalArgumentException(
                    "Exact event does not belong to the selected Timeline");
        }
        TimelineAppendReceipt receipt = engine.appendTimelineEntry(
                exactEnvelope);
        TimelineEntry entry = receipt.entry();
        if (!entry.timeline().timelineId().equals(call.timeline().id())
                || !entry.timeline().actorId().equals(
                        call.timeline().accountId())) {
            throw new IllegalArgumentException(
                    "Exact event does not belong to the selected Timeline");
        }
        intents.putIfAbsent(entry.blueId(), EntryIntent.broadcast());
        return retainCoreEntry(entry);
    }

    private EntryResult terminalResult(
            EntryHandle handle,
            ProcessingDrainReceipt receipt) {
        DrainResult drained = retain(mapper.map(receipt));
        EntryResult result = drained.find(handle).orElse(
                retainedResults.get(handle.blueId()));
        if (result != null) {
            return result;
        }
        Diagnostic diagnostic = new Diagnostic(
                "ENTRY_NOT_TERMINAL",
                "Processing did not reach the submitted entry",
                Map.of("entryBlueId", handle.blueId()));
        return new EntryResult(
                handle,
                EntryDisposition.BLOCKED,
                List.of(),
                List.of(),
                ProcessingStats.zero(),
                diagnostic);
    }

    private DrainResult retain(DrainResult result) {
        result.entries().forEach(entry -> retainedResults.put(
                entry.entry().blueId(), entry));
        return result;
    }

    private TimelineEntry requireCoreEntry(EntryHandle handle) {
        // The handle was just appended. Reconstructing its order by scanning
        // advanced journal state is intentionally unavailable, so retain the
        // exact entry at append time through the lightweight local map.
        CoreEntryRef ref = coreEntries.get(handle.blueId());
        if (ref == null) {
            throw new IllegalStateException(
                    "Missing appended entry evidence " + handle.blueId());
        }
        return ref.entry();
    }

    private final Map<String, CoreEntryRef> coreEntries =
            new LinkedHashMap<>();

    private EntryHandle retainCoreEntry(TimelineEntry entry) {
        coreEntries.put(entry.blueId(), new CoreEntryRef(entry));
        return handle(entry);
    }

    private DocumentRevision publicRevision(
            blue.coordination.api.DocumentRevision revision) {
        EntryHandle source = revision.sourceEntry()
                .map(this::handle)
                .orElse(null);
        List<PublicEvent> events = revision.emittedEvents().stream()
                .map(event -> new PublicEvent(
                        ExactBlueValue.wrap(ExactValue.verified(event)),
                        revision.documentId(),
                        null))
                .toList();
        ManagedEpochReceipt managedReceipt = revision.managedEpochReceipt()
                .map(this::publicManagedEpochReceipt)
                .orElse(null);
        return new DocumentRevision(
                revision.documentId(),
                revision.epoch(),
                DocumentRevision.Kind.valueOf(revision.kind().name()),
                revision.kind()
                        == blue.coordination.api.DocumentRevision.Kind
                                .INITIALIZATION
                        ? null
                        : revision.before().map(ExactBlueValue::wrap)
                                .orElse(null),
                ExactBlueValue.wrap(revision.after()),
                source,
                events,
                revision.processingGas(),
                managedReceipt);
    }

    private ManagedEpochReceipt publicManagedEpochReceipt(
            blue.coordination.api.ManagedEpochReceipt receipt) {
        TimelineEntrySnapshot source = receipt.sourceEntry()
                .map(this::publicTimelineEntry)
                .orElse(null);
        return new ManagedEpochReceipt(receipt, source);
    }

    private List<PublicEvent> publicEventsAt(DocumentId id, long epoch) {
        blue.coordination.api.DocumentRevision revision = engine.history(id)
                .stream()
                .filter(candidate -> candidate.epoch() == epoch)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "READY revision " + epoch + " is missing for " + id));
        return revision.emittedEvents().stream()
                .map(event -> new PublicEvent(
                        ExactBlueValue.wrap(ExactValue.verified(event)),
                        id,
                        null))
                .toList();
    }

    private Contracts10AuthoredClosureCompiler.ActivationInputs
            activationInputs(ActivationPolicy policy) {
        return switch (Objects.requireNonNull(policy, "policy").kind()) {
            case FROM_NOW -> Contracts10AuthoredClosureCompiler
                    .ActivationInputs.fromNow();
            case IMPORT_FULL_HISTORY -> Contracts10AuthoredClosureCompiler
                    .ActivationInputs.fullHistory();
            case IMPORT_FROM_FRONTIER -> Contracts10AuthoredClosureCompiler
                    .ActivationInputs.fromFrontier(frontier(
                            policy.frontierEvidence().orElseThrow()));
            case ATTACH_CURRENT_STATE, PASSIVE_SNAPSHOT ->
                    throw new IllegalArgumentException(
                            "UNSUPPORTED_TOP_LEVEL_ACTIVATION_POLICY: "
                                    + policy.kind());
        };
    }

    private static ExternalOrderKey frontier(ExactBlueValue evidence) {
        Node root = evidence.unwrap().copyNode();
        Node components = NodePathEditor.getOrNull(root, "/components");
        Node tuple = components == null ? root : components;
        List<Node> items = tuple.getItems();
        ArrayList<Object> values = new ArrayList<>();
        if (items == null) {
            values.add(frontierScalar(tuple));
        } else {
            items.forEach(item -> values.add(frontierScalar(item)));
        }
        return ExternalOrderKey.of(values);
    }

    private static Object frontierScalar(Node node) {
        Object value = node.getValue();
        if (value instanceof BigInteger || value instanceof Byte
                || value instanceof Short || value instanceof Integer
                || value instanceof Long || value instanceof String) {
            return value;
        }
        throw new IllegalArgumentException(
                "INVALID_FRONTIER_EVIDENCE: components must be Integer or Text");
    }

    private static String requiredText(Node root, String path) {
        Node selected = NodePathEditor.getOrNull(root, path);
        if (selected == null || !(selected.getValue() instanceof String text)
                || text.isBlank()) {
            throw new IllegalArgumentException(
                    "Exact Timeline Entry has no text " + path);
        }
        return text;
    }

    private void requireOwned(TimelineHandle timeline) {
        if (Objects.requireNonNull(timeline, "timeline").owner() != owner) {
            throw new IllegalArgumentException(
                    "Timeline handle belongs to another Coordination instance");
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("BlueCoordination is closed");
        }
    }

    private static String requireText(String value, String label) {
        String checked = Objects.requireNonNull(value, label).trim();
        if (checked.isEmpty()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return checked;
    }

    record TargetSelection(
            DocumentId id,
            ExactBlueValue exact,
            long epochAtSelection,
            boolean presentAtSelection,
            boolean requireExactDocumentVersion,
            String selectionFailure) {
        TargetSelection {
            id = Objects.requireNonNull(id, "id");
            exact = Objects.requireNonNull(exact, "exact");
            if (presentAtSelection != (epochAtSelection >= 0L)) {
                throw new IllegalArgumentException(
                        "Target epoch presence does not match selection");
            }
        }
    }

    private record ManagedDraftEvidence(
            Map<DocumentId, ContractsManagedDraftPlan.ManagedDraft> drafts,
            Map<String, DocumentId> requestFields,
            List<ContractsManagedDraftPlan.ExpectedOccurrence>
                    expectedOccurrences) {
        private ManagedDraftEvidence {
            drafts = Map.copyOf(Objects.requireNonNull(drafts, "drafts"));
            requestFields = Map.copyOf(Objects.requireNonNull(
                    requestFields, "requestFields"));
            expectedOccurrences = List.copyOf(Objects.requireNonNull(
                    expectedOccurrences, "expectedOccurrences"));
        }
    }

    record EntryIntent(
            boolean targeted,
            DocumentId targetId,
            String expectedTargetBlueId,
            boolean targetPresentAtSubmission,
            String operation,
            String channel,
            String timelineId,
            String actorId) {
        static EntryIntent targeted(
                TargetSelection target,
                String operation,
                String channel,
                TimelineHandle timeline) {
            return new EntryIntent(
                    true,
                    target.id(),
                    target.exact().blueId(),
                    target.presentAtSelection(),
                    operation,
                    channel,
                    timeline.id(),
                    timeline.accountId());
        }

        static EntryIntent broadcast() {
            return new EntryIntent(
                    false, null, null, false,
                    null, null, null, null);
        }
    }

    private record CoreEntryRef(TimelineEntry entry) {
        private CoreEntryRef {
            entry = Objects.requireNonNull(entry, "entry");
        }
    }

    /** Deduplicates provider transport reads within one static admission. */
    private static final class ScopedExactNodeProvider
            implements ExactNodeProvider {
        private final ExactNodeProvider delegate;
        private Map<String, Optional<ExactNodeEvidence>> lookupScope;

        private ScopedExactNodeProvider(ExactNodeProvider delegate) {
            this.delegate = Objects.requireNonNull(delegate, "delegate");
        }

        private synchronized void beginLookupScope() {
            if (lookupScope != null) {
                throw new IllegalStateException(
                        "Exact-node provider lookup scope is already active");
            }
            lookupScope = new LinkedHashMap<>();
        }

        private synchronized void endLookupScope() {
            lookupScope = null;
        }

        @Override
        public synchronized Optional<String> findExactContent(String blueId) {
            return findExactEvidence(blueId)
                    .map(ExactNodeEvidence::exactContent);
        }

        @Override
        public synchronized Optional<ExactNodeEvidence> findExactEvidence(
                String blueId) {
            String selected = Objects.requireNonNull(blueId, "blueId");
            if (lookupScope != null && lookupScope.containsKey(selected)) {
                return lookupScope.get(selected);
            }
            Optional<ExactNodeEvidence> result = Objects.requireNonNull(
                    delegate.findExactEvidence(selected),
                    "provider evidence result");
            if (lookupScope != null) {
                lookupScope.put(selected, result);
            }
            return result;
        }
    }

    private static final class SdkDocumentHandle implements DocumentHandle {
        private final SdkCoordinationRuntime runtime;
        private final DocumentId id;

        private SdkDocumentHandle(
                SdkCoordinationRuntime runtime,
                DocumentId id) {
            this.runtime = Objects.requireNonNull(runtime, "runtime");
            this.id = Objects.requireNonNull(id, "id");
        }

        @Override
        public DocumentId id() { return id; }

        @Override
        public DocumentSnapshot snapshot() { return runtime.snapshot(id); }

        @Override
        public List<DocumentRevision> history() {
            return runtime.history(id);
        }

        @Override
        public ExactBlueValue exact() { return runtime.current(id); }

        @Override
        public boolean equals(Object other) {
            return this == other || other instanceof SdkDocumentHandle handle
                    && runtime == handle.runtime && id.equals(handle.id);
        }

        @Override
        public int hashCode() {
            return 31 * System.identityHashCode(runtime) + id.hashCode();
        }

        @Override
        public String toString() { return id.toString(); }
    }
}
