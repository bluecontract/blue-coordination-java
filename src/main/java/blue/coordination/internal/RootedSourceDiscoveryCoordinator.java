package blue.coordination.internal;

import blue.coordination.api.DocumentId;
import blue.coordination.api.DocumentInstanceRef;
import blue.coordination.api.ExactValue;
import blue.coordination.api.SourceHistoryPrerequisite;
import blue.coordination.api.SourceHistoryRequest;
import blue.coordination.api.SourceHistoryPrerequisiteObservation;
import blue.coordination.api.SourceHistoryPrerequisiteResult;
import blue.coordination.api.Timeline;
import blue.coordination.api.TimelineEntry;
import blue.coordination.sdk.ExactNodeProvider;
import blue.language.processor.ExternalOrderKey;
import blue.language.processor.closure.ClosureAttemptResult;
import blue.language.processor.closure.ManagedOccurrenceEvidenceDemand;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Retains real stopped-demand correlation; selection and execution remain separate calls. */
final class RootedSourceDiscoveryCoordinator {
    private final DefaultCoordinationEngine engine;
    private final InMemoryDocumentStore documents;
    private final ContractsClosureAdapter adapter;
    private final InMemoryTimelineJournal journal;
    private final EmbeddedOnlyLayoutBuilder layouts;
    private final OperationRouteIndex routes;
    private final Map<String, Timeline> timelines;
    private final ExactNodeProvider provider;
    private final Map<String, Pending> pending;
    private final Map<String, SourceHistoryPrerequisiteResult> completed;
    private final Map<String, Prepared> submitted;
    private final LogicalSourceRequests requests;

    /** Owned maps from one complete runtime scope; not independently publishable evidence. */
    record StoredMaps(Map<String, Pending> pending, Map<String, SourceHistoryPrerequisiteResult> completed,
            Map<String, Prepared> submitted, LogicalSourceRequests requests) {
        StoredMaps(Map<String, Pending> pending, Map<String, SourceHistoryPrerequisiteResult> completed, Map<String, Prepared> submitted) { this(pending, completed, submitted, null); }
        StoredMaps { Objects.requireNonNull(pending); Objects.requireNonNull(completed); Objects.requireNonNull(submitted); }
        static StoredMaps empty() { return new StoredMaps(new LinkedHashMap<>(), new LinkedHashMap<>(), new LinkedHashMap<>()); }
    }

    StoredMaps storedMaps() { return new StoredMaps(pending, completed, submitted, requests); }

    Pending pendingForStorage(String key) { return pending.get(key); }
    Prepared submittedForStorage(String selectionIdentity) { return submitted.get(selectionIdentity); }

    void requireStorageSupported() {
        if (!pending.isEmpty() || !completed.isEmpty() || !submitted.isEmpty())
            throw new blue.coordination.api.storage.CoordinationObjectStorageException(
                    "Retained source discovery requires complete invocation/attempt/selection storage");
    }

    RootedSourceDiscoveryCoordinator(DefaultCoordinationEngine engine, InMemoryDocumentStore documents,
            ContractsClosureAdapter adapter, InMemoryTimelineJournal journal, EmbeddedOnlyLayoutBuilder layouts,
            OperationRouteIndex routes, Map<String, Timeline> timelines, ExactNodeProvider provider) {
        this(engine, documents, adapter, journal, layouts, routes, timelines, provider, StoredMaps.empty());
    }

    RootedSourceDiscoveryCoordinator(DefaultCoordinationEngine engine, InMemoryDocumentStore documents,
            ContractsClosureAdapter adapter, InMemoryTimelineJournal journal, EmbeddedOnlyLayoutBuilder layouts,
            OperationRouteIndex routes, Map<String, Timeline> timelines, ExactNodeProvider provider, StoredMaps maps) {
        this.engine = engine; this.documents = documents; this.adapter = adapter; this.journal = journal;
        this.layouts = layouts; this.routes = routes; this.timelines = timelines; this.provider = provider;
        pending = maps.pending(); completed = maps.completed(); submitted = maps.submitted(); requests = maps.requests();
    }

    ManagedOccurrenceResolver.Resolution requirePrerequisites(ContractsClosureAdapter.CohortInvocation current,
            ClosureAttemptResult attempt, ManagedOccurrenceResolver.Resolution resolution) {
        if (current.rootedEvidence() == null) return resolution;
        var accepted = new ArrayList<ManagedOccurrenceResolver.ResolvedOccurrence>();
        var missing = new ArrayList<>(resolution.unresolvedDemands());
        for (var occurrence : resolution.resolvedOccurrences()) {
            Pending candidate = candidate(current, attempt, occurrence);
            if (candidate == null) { accepted.add(occurrence); continue; }
            Prepared next = prepare(candidate);
            if (next == null) { accepted.add(occurrence); continue; }
            if (pending instanceof PendingSelections indexed)
                indexed.putForSource(candidate, documents.pendingSourceInstance(candidate.source(), RootedAttachmentCapture.sourceOwners(current)));
            else pending.put(candidate.key(), candidate);
            missing.add(new ManagedOccurrenceResolver.UnresolvedDemand(occurrence.demand(),
                    ManagedOccurrenceResolver.ResolutionStatus.UNPROVEN_MANAGED_HISTORY,
                    "Separate source-owned prerequisite required: " + next.descriptor().kind()
                            + " source=" + candidate.source() + " cutoff=" + candidate.cutoff()
                            + (next.descriptor().diagnostic() == null ? "" : " " + next.descriptor().diagnostic())));
        }
        return new ManagedOccurrenceResolver.Resolution(resolution.demands(), accepted,
                resolution.resolvedExactNodes(), missing, resolution.resolvedSelectorPaths());
    }

    private Pending candidate(ContractsClosureAdapter.CohortInvocation current, ClosureAttemptResult attempt,
            ManagedOccurrenceResolver.ResolvedOccurrence occurrence) {
        var demand = occurrence.demand(); var plan = current.managedDraftPlan();
        boolean declared = plan != null && demand.sourceDocumentId().value().equals(plan.targetDocumentId().value())
                && plan.expectedOccurrences().stream().anyMatch(row -> row.path().equals(demand.sourcePath()));
        if (declared || current.rootedEvidence().context().entryOwners()
                .contains(ContractsClosureAdapter.closureId(occurrence.targetDocumentId()))) return null;
        ExactValue authored;
        var source = documents.sourceAdmission(occurrence.targetDocumentId(), RootedAttachmentCapture.sourceOwners(current)).orElse(null);
        if (source == null) {
            if (occurrence.targetKind() != ManagedOccurrenceResolver.TargetKind.NEW_AUTHORED
                    || occurrence.newDraft() == null || !occurrence.newDraft().contentDerivedIdentity()) return null;
            authored = occurrence.newDraft().initial();
        } else {
            var history = source.requireRootedHistory();
            if (!"FULL_HISTORY".equals(((Map<?, ?>) history.descriptor().get("admission")).get("mode"))) return null;
            authored = source.revision(0).before().orElseThrow();
            if (!authored.blueId().equals(history.descriptor().get("initialDocumentBlueId")))
                throw new IllegalArgumentException("Source admission does not retain its exact authored history");
        }
        var input = current.input();
        if (attempt.isComplete() || attempt.resourceDemands().stream().noneMatch(actual -> actual == demand)
                || !input.cause().causeIdentity().equals(demand.logicalCauseIdentity())
                || !input.snapshot().closureIdentity().equals(demand.inputClosureIdentity())
                || input.snapshot().graphGeneration() != demand.inputGraphGeneration()
                || !occurrence.targetDocumentId().value().equals(authored.blueId())
                || !occurrence.expectedTargetBlueId().equals(demand.suppliedValueBlueId())) {
            throw new IllegalArgumentException("Source acquisition lacks its actual processor-issued demand");
        }
        ExternalOrderKey cutoff = current.rootedEvidence().historicalOrigin() == null
                ? RootedAttachmentCapture.logicalBoundary(input, documents.catchUpPlansSnapshot())
                : current.rootedEvidence().historicalOrigin().logicalBoundary();
        return new Pending(current, attempt, demand, occurrence.targetDocumentId(), authored, cutoff);
    }

    interface PendingSelections {
        List<Pending> forRoot(DocumentId root);
        List<Pending> forSource(DocumentInstanceRef source);
        void putForSource(Pending pending, DocumentInstanceRef source);
        SourceHistoryRequest request(Pending pending, SourceHistoryPrerequisite descriptor);
        Pending forRequest(SourceHistoryRequest request);
        void removeCurrent(Pending pending);
        boolean currentOwners(Pending pending);

    }

    List<Pending> pendingForRetirement(DocumentInstanceRef instance) {
        var selected = new LinkedHashMap<String, Pending>();
        if (pending instanceof PendingSelections indexed) {
            indexed.forRoot(instance.documentId()).forEach(row -> selected.put(row.key(), row));
            indexed.forSource(instance).forEach(row -> selected.put(row.key(), row));
        } else pending.values().stream().filter(row -> row.owns(instance.documentId()) || row.source().equals(instance.documentId()))
                .forEach(row -> selected.put(row.key(), row));
        return selected.values().stream().filter(this::stillCurrent).toList();
    }

    List<SourceHistoryPrerequisite> selections(DocumentId requestingRoot) {
        var values = new ArrayList<SourceHistoryPrerequisite>();
        for (var candidate : pending instanceof PendingSelections selected
                ? selected.forRoot(requestingRoot) : List.copyOf(pending.values())) {
            if (!candidate.owns(requestingRoot)) continue;
            if (!stillCurrent(candidate)) { removePending(candidate); continue; }
            Prepared next = prepare(candidate);
            if (next != null) {
                if (requests != null) requests.bind(((PendingSelections) pending).request(candidate, next.descriptor()));
                values.add(next.descriptor());
            }
        }
        values.sort(java.util.Comparator.comparing(SourceHistoryPrerequisite::demandIdentity)
                .thenComparing(value -> value.sourceDocumentId().value()));
        return List.copyOf(values);
    }

    List<SourceHistoryRequest> requests(DocumentId root) {
        if (requests == null) throw new IllegalStateException("Instance-bound source requests require native instance storage");
        var result = new ArrayList<SourceHistoryRequest>();
        for (var descriptor : selections(root)) {
            for (var candidate : ((PendingSelections) pending).forRoot(root)) {
                if (candidate.key().equals(key(descriptor.requestingInvocationIdentity(), descriptor.demandIdentity()))) {
                    var request = ((PendingSelections) pending).request(candidate, descriptor);
                    requests.bind(request); result.add(request); break;
                }
            }
        }
        return List.copyOf(result);
    }
    void retainStage(SourceHistoryRequest request, blue.coordination.api.SourceHistoryStageContext stage) { requests.retainStage(request, stage); }
    blue.coordination.api.SourceHistoryStageContext retainedStage(SourceHistoryRequest request) { return requests.stage(request); }
    void requireRequest(SourceHistoryRequest request) { requests.require(request); }
    Optional<SourceHistoryRequest> findOriginalRequest(SourceHistoryPrerequisite descriptor) { return requests.findOriginal(descriptor); }
    SourceHistoryRequest originalRequest(SourceHistoryPrerequisite descriptor) {
        if (requests == null) throw new IllegalStateException("Source context requires native instance storage");
        return requests.original(descriptor);
    }
    private String resultKey(SourceHistoryPrerequisite expected, SourceHistoryRequest request) {
        if (requests == null) return expected.selectionIdentity();
        requests.require(Objects.requireNonNull(request));
        if (!request.prerequisite().equals(expected)) throw new IllegalArgumentException("Source context descriptor differs");
        return LogicalSourceRequests.storageKey(request);
    }
    private SourceHistoryRequest originalIfNative(SourceHistoryPrerequisite expected) {
        return requests == null ? null : requests.original(expected);
    }
    private Pending pending(SourceHistoryPrerequisite expected, SourceHistoryRequest request) {
        return requests == null ? pending.get(key(expected.requestingInvocationIdentity(), expected.demandIdentity()))
                : ((PendingSelections) pending).forRequest(request);
    }
    private void removePending(Pending row) {
        if (pending instanceof PendingSelections indexed) indexed.removeCurrent(row); else pending.remove(row.key());
    }
    SourceHistoryPrerequisiteObservation observe(SourceHistoryPrerequisite expected) {
        return observe(expected, originalIfNative(expected));
    }
    SourceHistoryPrerequisiteObservation observe(SourceHistoryPrerequisite expected, SourceHistoryRequest request) {
        resultKey(expected, request);
        if (requests != null && !requests.currentRequesters(request)) return new SourceHistoryPrerequisiteObservation(
                SourceHistoryPrerequisiteObservation.Status.STALE, Optional.empty());
        Pending candidate = pending(expected, request);
        if (candidate == null) return new SourceHistoryPrerequisiteObservation(
                SourceHistoryPrerequisiteObservation.Status.STALE, Optional.empty());
        if (!expected.requestingRoot().value().equals(candidate.invocation().rootedEvidence()
                        .context().canonicalRootDocumentId().value())
                || !expected.sourceDocumentId().equals(candidate.source())
                || !expected.authoredBlueId().equals(candidate.authored().blueId())
                || !expected.cutoffExclusive().equals(candidate.cutoff()))
            throw new IllegalArgumentException("Changed source-prerequisite logical authority");
        if (!stillCurrent(candidate)) {
            removePending(candidate);
            return new SourceHistoryPrerequisiteObservation(
                    SourceHistoryPrerequisiteObservation.Status.STALE, Optional.empty());
        }
        Prepared next = prepare(candidate);
        if (requests != null && next != null) requests.bind(((PendingSelections) pending).request(candidate, next.descriptor()));
        return next == null ? new SourceHistoryPrerequisiteObservation(
                SourceHistoryPrerequisiteObservation.Status.SATISFIED, Optional.empty())
                : new SourceHistoryPrerequisiteObservation(
                        SourceHistoryPrerequisiteObservation.Status.PENDING, Optional.of(next.descriptor()));
    }

    Optional<SourceHistoryPrerequisiteResult> completed(SourceHistoryPrerequisite expected) {
        return completed(expected, originalIfNative(expected));
    }
    Optional<SourceHistoryPrerequisiteResult> completed(SourceHistoryPrerequisite expected, SourceHistoryRequest request) {
        var prior = completed.get(resultKey(expected, request));
        if (prior == null) return Optional.empty();
        if (!prior.selection().equals(expected)) throw new IllegalArgumentException("Changed source result selection");
        return Optional.of(new SourceHistoryPrerequisiteResult(expected, prior.admission(), prior.processing(), true));
    }

    Optional<Prepared> committedSelection(SourceHistoryPrerequisite expected) {
        return committedSelection(expected, originalIfNative(expected));
    }
    Optional<Prepared> committedSelection(SourceHistoryPrerequisite expected, SourceHistoryRequest request) {
        Prepared prior = submitted.get(resultKey(expected, request));
        if (prior == null) return Optional.empty();
        if (requests != null) requests.requireExecutable(request);
        if (!prior.descriptor().equals(expected)) throw new IllegalArgumentException("Changed submitted source selection");
        // An exact retained publication is the only route around stale-head checks after response loss.
        return engine.sourceHistoryPrerequisiteCommitted(prior) ? Optional.of(prior) : Optional.empty();
    }

    Prepared requireSelection(SourceHistoryPrerequisite expected) {
        return requireSelection(expected, originalIfNative(expected));
    }
    Prepared requireSelection(SourceHistoryPrerequisite expected, SourceHistoryRequest request) {
        resultKey(expected, request);
        if (requests != null) requests.requireExecutable(request);
        Pending candidate = pending(expected, request);
        if (candidate == null || !stillCurrent(candidate)) throw new IllegalArgumentException("Source prerequisite is stale");
        Prepared selected = prepare(candidate);
        if (selected == null || !selected.descriptor().equals(expected))
            throw new IllegalArgumentException("Source prerequisite changed before execution");
        if (expected.kind() == SourceHistoryPrerequisite.Kind.WAIT)
            throw new blue.coordination.api.CoordinationException(blue.coordination.api.CoordinationErrorCode.NEEDS_RESOURCES,
                    expected.diagnostic());
        submitted.put(resultKey(expected, request), selected);
        return selected;
    }

    void retain(SourceHistoryPrerequisiteResult result) { retain(result, originalIfNative(result.selection())); }
    void retain(SourceHistoryPrerequisiteResult result, SourceHistoryRequest request) {
        boolean commits = result.admission().map(value -> value.published()).orElse(false)
                || result.processing().map(value -> value.committedProcessTransitions() > 0L).orElse(false);
        if (commits) completed.put(resultKey(result.selection(), request), result);
    }

    private boolean stillCurrent(Pending candidate) {
        if (pending instanceof PendingSelections indexed && !indexed.currentOwners(candidate)) return false;
        // Terminal rejections can consume the exact requester without changing its document head.
        if (documents.hasExecutionPublication(candidate.invocation().rootedEvidence().terminalKey(),
                DocumentId.of(candidate.invocation().rootedEvidence().context().canonicalRootDocumentId().value()))) return false;
        for (var owner : candidate.invocation().rootedEvidence().context().entryOwners()) {
            DocumentId id = ContractsClosureAdapter.coordinationId(owner);
            var prior = candidate.invocation().rootedEvidence().publicationFence(id);
            var current = documents.find(id).orElse(null);
            if (current == null || current.epoch() != prior.head().epoch()
                    || !current.currentRepresentation().blueId().equals(prior.head().blueId())
                    || documents.graphGeneration(id) != prior.graphGeneration()) return false;
        }
        return true;
    }

    private Prepared prepare(Pending candidate) {
        var admitted = documents.sourceAdmission(candidate.source(), RootedAttachmentCapture.sourceOwners(candidate.invocation()));
        var source = documents.sourceBefore(candidate.source(), candidate.cutoff(), RootedAttachmentCapture.sourceOwners(candidate.invocation())).orElse(null);
        if (admitted.isPresent() && source == null)
            return selected(candidate, SourceHistoryPrerequisite.Kind.WAIT, null, null,
                    "missing-retained-source", "Admitted source lacks authenticated pre-boundary history", null);
        Window window;
        try { window = window(candidate, source); }
        catch (blue.language.provider.ProviderUnavailableException unavailable) {
            return selected(candidate, SourceHistoryPrerequisite.Kind.WAIT, null, null,
                    "provider-unavailable", unavailable.getMessage(), null);
        }
        catch (blue.language.processor.ExecutionEvidenceUnavailableException unavailable) {
            return selected(candidate, SourceHistoryPrerequisite.Kind.WAIT, null, null,
                    "execution-evidence-unavailable", unavailable.getMessage(), null);
        }
        if (window.diagnostic() != null)
            return selected(candidate, SourceHistoryPrerequisite.Kind.WAIT, null, null,
                    window.identity(), window.diagnostic(), null);
        if (source == null) {
            var compiled = new Contracts10StaticEmbeddedAdmissionCompiler(engine).compileExactAuthored(candidate.authored(),
                    Contracts10AuthoredClosureCompiler.ActivationInputs.fullHistory());
            if (!compiled.rootDocumentId().equals(candidate.source()))
                throw new IllegalArgumentException("Source compiler changed the exact authored identity");
            return selected(candidate, SourceHistoryPrerequisite.Kind.ADMISSION, compiled, null,
                    window.identity(), null, window.evidence());
        }
        var assessment = RootedSourceHistoryAssessment.assess(candidate.source(), source, candidate.cutoff(),
                documents, adapter, journal, RootedAttachmentCapture.sourceOwners(candidate.invocation()));
        if (assessment.satisfied()) return null;
        var retired = documents.sourceWorkBlock(candidate.source(), RootedAttachmentCapture.sourceOwners(candidate.invocation()));
        if (retired.isPresent()) return selected(candidate, SourceHistoryPrerequisite.Kind.WAIT, null, null,
                window.identity(), retired.orElseThrow() + " prerequisite=" + assessment.requiredEvidence(), window.evidence());
        // Actual source-owned selection retains all ordinary live head, owner and control fences.
        source = documents.require(candidate.source());
        boolean logical = documents.storedState().sessionIndex().isLogical();
        var driver = new RootedCheckpointDriver(documents, adapter, logical, candidate.cutoff());
        java.util.function.Function<DocumentId, List<TimelineEntry>> inputs = root -> logical
                ? adapter.rootedJournalEntriesBefore(root, journal, candidate.cutoff()) : journal.entries();
        var next = driver.select(candidate.source(), inputs);
        if (next.blocked()) {
            if (driver.completeBefore(candidate.source(), inputs.apply(candidate.source()), candidate.cutoff())) return null;
            String reason = RootedJoinPrerequisites.pendingBefore(candidate.source(),
                    source.rootedViewBefore(candidate.cutoff()), candidate.cutoff(), documents);
            return selected(candidate, SourceHistoryPrerequisite.Kind.WAIT, null, null,
                    window.identity(), reason == null ? "Source has unproven earlier history" : reason, window.evidence());
        }
        ExternalOrderKey origin;
        SourceHistoryPrerequisite.Kind kind;
        if (next.live() != null) { origin = next.live().entry().sourceOrderKey(); kind = SourceHistoryPrerequisite.Kind.LIVE; }
        else if (next.localHistorical() != null && next.historical() == null) {
            origin = next.localHistorical().anchor().sourceOrderKey(); kind = SourceHistoryPrerequisite.Kind.ROOTED_RETAINED;
        } else if (next.historical() != null) {
            origin = documents.catchUpBarrier(next.historical().barrierIdentity()).orElseThrow().causeOrder();
            kind = SourceHistoryPrerequisite.Kind.MANAGED_HISTORY;
        } else return null;
        if (origin.compareTo(candidate.cutoff()) >= 0) return null;
        return selected(candidate, kind, null, next, window.identity(), null, window.evidence());
    }

    private Window window(Pending candidate, DocumentSession source) {
        var required = new LinkedHashMap<String, String>(); var frames = new ArrayList<String>();
        frames.add(candidate.authored().blueId()); frames.add(candidate.invocation().input().environment().runtimeRegistryIdentity());
        if (source == null) {
            // The actual stopped demand already authenticated this complete authored Root.
            // A source-selection read must not reopen its identity via an external provider
            // or traverse its Process Embedded children before the separate ADMIT attempt.
            try (var contracts = new blue.language.processor.closure.BlueClosureContracts(engine.runtime().documentProcessor())) {
                var surface = contracts.projectRootSubscriptionSurface(candidate.authored().copyNode());
                addSurface(frames, required, candidate.source().value(), candidate.authored().blueId(),
                        RoutingSurface.fromManagedRootContracts(surface.effectiveRootContracts()));
            }
        }
        else {
            var view = source.rootedViewBefore(candidate.cutoff());
            frames.add(view.snapshot().closureIdentity());
            for (var surface : adapter.sourceDiscoverySurfaces(view, candidate.source()))
                addSurface(frames, required, surface.documentId().value(), surface.blueId(), surface.routing());
        }
        String identity = hash("blue.coordination/source-discovery-surface/1", frames);
        for (var item : required.entrySet()) {
            Timeline registered = timelines.get(item.getKey());
            if (registered == null) return new Window(identity, null, "Required source Timeline is not registered: " + item.getKey());
            if (!registered.actorId().equals(item.getValue()))
                throw new IllegalArgumentException("Required source Timeline actor differs: " + item.getKey());
        }
        var step = journal.sourceCoverage(required.keySet(), candidate.cutoff(),
                routes.generation(), candidate.invocation().input().snapshot().graphGeneration(), identity);
        if (step instanceof HistoricalStep.Unavailable wait) return new Window(identity, null, wait.diagnostic());
        if (step instanceof HistoricalStep.InvalidEvidence invalid) throw new IllegalArgumentException(invalid.diagnostic());
        CompletenessEvidence evidence = step instanceof HistoricalStep.Complete full ? full.evidence()
                : step instanceof HistoricalStep.CompleteEmpty empty ? empty.evidence() : null;
        if (evidence == null || !evidence.isCurrentFor(journal.scopedCoverage() ? 0 : journal.revision(), routes.generation(),
                candidate.invocation().input().snapshot().graphGeneration(), candidate.cutoff(),
                journal.scopedCoverage() ? evidence.sourceSurfaceIdentity() : identity))
            throw new IllegalArgumentException("Source completeness does not bind the exact pending window");
        return new Window(evidence.sourceSurfaceIdentity(), evidence, null);
    }

    private static void addSurface(List<String> frames, Map<String, String> required, String id,
            String blueId, RoutingSurface surface) {
        frames.add(id); frames.add(blueId); frames.add(Integer.toString(surface.definitions().size()));
        for (var row : surface.definitions()) {
            frames.add(row.scopePath()); frames.add(row.operation()); frames.add(row.channelKey());
            frames.add(Integer.toString(row.sources().size()));
            for (var source : row.sources()) {
                frames.add(source.timelineId()); frames.add(source.actorId());
                String old = required.putIfAbsent(source.timelineId(), source.actorId());
                if (old != null && !old.equals(source.actorId())) throw new IllegalArgumentException("Conflicting source actor catalog");
            }
        }
        frames.add(Integer.toString(surface.channels().size()));
        for (var channel : surface.channels()) {
            frames.add(channel.scopePath()); frames.add(channel.channelKey());
            frames.add(Integer.toString(channel.sources().size()));
            for (var source : channel.sources()) {
                frames.add(source.timelineId()); frames.add(source.actorId());
                String old = required.putIfAbsent(source.timelineId(), source.actorId());
                if (old != null && !old.equals(source.actorId())) throw new IllegalArgumentException("Conflicting source actor catalog");
            }
        }
        frames.add(Boolean.toString(surface.deliversEmbeddedRevisionEvents()));
    }

    private Prepared selected(Pending candidate, SourceHistoryPrerequisite.Kind kind,
            Contracts10StaticEmbeddedAdmissionCompiler.CompiledStaticAdmission admission,
            RootedCheckpointDriver.Selection step, String surface, String diagnostic, CompletenessEvidence evidence) {
        var source = documents.find(candidate.source()).orElse(null);
        long epoch = source == null ? -1L : source.epoch();
        String head = source == null ? candidate.authored().blueId() : source.currentRepresentation().blueId();
        String work = admission != null ? admission.invocation().invocationIdentity()
                : step == null ? candidate.demand().demandIdentity()
                : step.live() != null ? step.live().invocations().get(0).executionInvocationIdentity()
                : step.historical() != null ? step.historical().workIdentity() : step.localHistorical().work().workIdentity();
        String entry = step != null && step.live() != null ? step.live().entry().blueId() : null;
        String root = candidate.invocation().rootedEvidence().context().canonicalRootDocumentId().value();
        long journalRevision = journal.scopedCoverage() ? 0 : journal.revision();
        var fields = new ArrayList<String>(List.of(root, candidate.invocation().input().invocationIdentity(),
                candidate.demand().demandIdentity(), candidate.source().value(), candidate.authored().blueId(),
                kind.name(), Long.toString(epoch), head, work, Objects.toString(entry, ""),
                Long.toString(journalRevision), Long.toString(routes.generation()), surface, Objects.toString(diagnostic, "")));
        candidate.cutoff().components().forEach(value -> fields.add(value.toString()));
        var descriptor = new SourceHistoryPrerequisite(hash("blue.coordination/source-history-prerequisite/1", fields),
                DocumentId.of(root), candidate.invocation().input().invocationIdentity(), candidate.demand().demandIdentity(),
                candidate.source(), candidate.authored().blueId(), candidate.cutoff(), kind, epoch, head, work, entry,
                journalRevision, routes.generation(), surface, diagnostic);
        return new Prepared(descriptor, admission, step, evidence);
    }

    private static String hash(String domain, List<String> fields) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            var selected = new ArrayList<String>(); selected.add(domain); selected.addAll(fields);
            for (String field : selected) {
                byte[] bytes = field.getBytes(StandardCharsets.UTF_8);
                digest.update(java.nio.ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array()); digest.update(bytes);
            }
            return "sha256:" + HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static String key(String invocation, String demand) { return invocation + "/" + demand; }
    private record Window(String identity, CompletenessEvidence evidence, String diagnostic) { }
    record Prepared(SourceHistoryPrerequisite descriptor,
            Contracts10StaticEmbeddedAdmissionCompiler.CompiledStaticAdmission admission,
            RootedCheckpointDriver.Selection step, CompletenessEvidence completeness) { }
    record Pending(ContractsClosureAdapter.CohortInvocation invocation, ClosureAttemptResult attempt,
            ManagedOccurrenceEvidenceDemand demand, DocumentId source, ExactValue authored, ExternalOrderKey cutoff) {
        String key() { return RootedSourceDiscoveryCoordinator.key(invocation.input().invocationIdentity(), demand.demandIdentity()); }
        boolean owns(DocumentId root) { return invocation.rootedEvidence().context().entryOwners()
                .contains(ContractsClosureAdapter.closureId(root)); }
    }
}
