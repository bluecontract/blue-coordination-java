package blue.coordination.sdk;

import blue.coordination.api.ContractsClosureAdmissionReceipt;
import blue.coordination.api.CoordinationEngine;
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
import blue.coordination.internal.ContractsManagedDraftPlan;
import blue.coordination.internal.DefaultCoordinationEngine;
import blue.language.model.Node;
import blue.language.model.NodePathEditor;
import blue.language.processor.ExternalOrderKey;

import java.math.BigInteger;
import java.util.ArrayList;
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
    private final SdkDrainResultMapper mapper;
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
            String contractsIdentity) {
        this.owner = Objects.requireNonNull(owner, "owner");
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
                language, contracts);
        compiler = new Contracts10AuthoredClosureCompiler(engine);
        mapper = new SdkDrainResultMapper(this, engine);
    }

    static SdkCoordinationRuntime create(
            Object owner,
            String languageIdentity,
            String contractsIdentity) {
        return new SdkCoordinationRuntime(
                owner, languageIdentity, contractsIdentity);
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
        ensureOpen();
        Timeline registered = engine.registerTimeline(
                requireText(timelineId, "timelineId"),
                requireText(accountId, "accountId"));
        TimelineHandle handle = new TimelineHandle(
                owner, registered.timelineId(), registered.actorId());
        TimelineHandle prior = timelines.putIfAbsent(
                registered.timelineId(), handle);
        return prior == null ? handle : prior;
    }

    synchronized ExactBlueValue exactValue(String sourceYaml) {
        ensureOpen();
        return ExactBlueValue.wrap(engine.exactValue(
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
        admitCompiled(compiler.compile(request), Set.of(selected.id()));
        return requireDocument(selected.id());
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
                compiler.compile(request);
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
                null);
    }

    synchronized TargetSelection selectTarget(DocumentId documentId) {
        ensureOpen();
        DocumentId id = Objects.requireNonNull(documentId, "documentId");
        try {
            blue.coordination.api.DocumentSnapshot snapshot =
                    engine.auditDocument(id);
            return new TargetSelection(
                    id,
                    ExactBlueValue.wrap(snapshot.current()),
                    snapshot.epoch(),
                    true,
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
                    "document is not managed");
        }
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

    synchronized EntryIntent intent(String entryBlueId) {
        return intents.getOrDefault(
                Objects.requireNonNull(entryBlueId, "entryBlueId"),
                EntryIntent.broadcast());
    }

    synchronized TimelineEntry retainedCoreEntry(String entryBlueId) {
        CoreEntryRef ref = coreEntries.get(Objects.requireNonNull(
                entryBlueId, "entryBlueId"));
        return ref == null ? null : ref.entry();
    }

    synchronized EntryHandle handle(TimelineEntry entry) {
        TimelineHandle timeline = timelines.computeIfAbsent(
                entry.timeline().timelineId(),
                ignored -> new TimelineHandle(
                        owner,
                        entry.timeline().timelineId(),
                        entry.timeline().actorId()));
        return new EntryHandle(
                owner,
                timeline,
                entry.blueId(),
                entry.globalSequence(),
                entry.timelineSequence());
    }

    synchronized EntryHandle lightweightHandle(String blueId) {
        return new EntryHandle(owner, blueId);
    }

    synchronized List<DocumentRevision> history(DocumentId id) {
        ensureOpen();
        return engine.history(id).stream()
                .map(this::publicRevision)
                .toList();
    }

    synchronized DocumentSnapshot snapshot(DocumentId id) {
        ensureOpen();
        blue.coordination.api.DocumentSnapshot snapshot =
                engine.document(id);
        if (snapshot.status() != SessionStatus.READY) {
            throw new IllegalStateException(
                    "DOCUMENT_NOT_READY: " + id);
        }
        return new DocumentSnapshot(
                id,
                snapshot.epoch(),
                true,
                ExactBlueValue.wrap(snapshot.current()),
                latestPublicEvents(id));
    }

    synchronized ExactBlueValue current(DocumentId id) {
        ensureOpen();
        return ExactBlueValue.wrap(engine.document(id).current());
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
        if (!receipt.published()) {
            if (!receipt.attempt().isComplete()) {
                throw new IllegalStateException(
                        "ADMISSION_NEEDS_RESOURCES: "
                                + receipt.attempt().requiredExactBlueIds());
            }
            String status = receipt.attempt().processResult()
                    .status().wireValue();
            throw new IllegalStateException(
                    "ADMISSION_REJECTED_"
                            + status.toUpperCase().replace('-', '_'));
        }
        return receipt;
    }

    private EntryHandle appendOperation(OperationCall call) {
        requireOwned(call.timeline());
        ManagedDraftEvidence managed = managedDraftEvidence(call);
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
                .targeting(target.exact().unwrap(), true);
        Timeline timeline = new Timeline(
                call.timeline().id(), call.timeline().accountId());
        TimelineEntry appended;
        if (managed == null || !target.presentAtSelection()) {
            // Preserve the ordinary precise zero-attempt target diagnostic.
            // A missing target cannot own an affected closure or new lineage.
            appended = engine.append(timeline, operation);
        } else {
            ContractsManagedDraftPlan plan = new ContractsManagedDraftPlan(
                    target.id(),
                    target.epochAtSelection(),
                    target.exact().blueId(),
                    managed.drafts(),
                    managed.requestFields(),
                    managed.expectedOccurrences());
            appended = engine.append(timeline, operation, plan);
        }
        intents.put(appended.blueId(), EntryIntent.targeted(
                target,
                call.operation(),
                call.channel(),
                call.timeline()));
        return retainCoreEntry(appended);
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

    private static ContractsManagedDraftPlan.ManagedDraft managedDraft(
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
                draft.id(), draft.initial().unwrap(), null);
    }

    private static boolean sameManagedDraft(
            ContractsManagedDraftPlan.ManagedDraft left,
            ContractsManagedDraftPlan.ManagedDraft right) {
        return left.documentId().equals(right.documentId())
                && left.initial().sameExactValue(right.initial())
                && Objects.equals(left.knownEpoch(), right.knownEpoch());
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
                revision.processingGas());
    }

    private List<PublicEvent> latestPublicEvents(DocumentId id) {
        List<blue.coordination.api.DocumentRevision> revisions =
                engine.history(id);
        if (revisions.isEmpty()) {
            return List.of();
        }
        return revisions.get(revisions.size() - 1).emittedEvents().stream()
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
