package blue.coordination.internal;

import blue.coordination.api.DocumentId;
import blue.coordination.api.ExactValue;
import blue.coordination.api.SourceHistoryPrerequisite;
import blue.coordination.api.SourceHistoryPrerequisiteResult;
import blue.coordination.api.Timeline;
import blue.coordination.sdk.ExactNodeProvider;
import blue.language.codec.jackson.UncheckedObjectMapper;
import blue.language.model.NodeWireForm;
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
    private final Map<String, Pending> pending = new LinkedHashMap<>();
    private final Map<String, SourceHistoryPrerequisiteResult> completed = new LinkedHashMap<>();
    private final Map<String, Prepared> submitted = new LinkedHashMap<>();

    RootedSourceDiscoveryCoordinator(DefaultCoordinationEngine engine, InMemoryDocumentStore documents,
            ContractsClosureAdapter adapter, InMemoryTimelineJournal journal, EmbeddedOnlyLayoutBuilder layouts,
            OperationRouteIndex routes, Map<String, Timeline> timelines, ExactNodeProvider provider) {
        this.engine = engine; this.documents = documents; this.adapter = adapter; this.journal = journal;
        this.layouts = layouts; this.routes = routes; this.timelines = timelines; this.provider = provider;
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
            if (next == null) { pending.remove(candidate.key()); accepted.add(occurrence); continue; }
            pending.put(candidate.key(), candidate);
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
        var source = documents.find(occurrence.targetDocumentId()).orElse(null);
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

    List<SourceHistoryPrerequisite> selections(DocumentId requestingRoot) {
        var values = new ArrayList<SourceHistoryPrerequisite>();
        for (var candidate : List.copyOf(pending.values())) {
            if (!candidate.owns(requestingRoot)) continue;
            if (!stillCurrent(candidate)) { pending.remove(candidate.key()); continue; }
            Prepared next = prepare(candidate);
            if (next == null) pending.remove(candidate.key()); else values.add(next.descriptor());
        }
        values.sort(java.util.Comparator.comparing(SourceHistoryPrerequisite::demandIdentity)
                .thenComparing(value -> value.sourceDocumentId().value()));
        return List.copyOf(values);
    }

    Optional<SourceHistoryPrerequisiteResult> completed(SourceHistoryPrerequisite expected) {
        var prior = completed.get(expected.selectionIdentity());
        if (prior == null) return Optional.empty();
        if (!prior.selection().equals(expected)) throw new IllegalArgumentException("Changed source result selection");
        return Optional.of(new SourceHistoryPrerequisiteResult(expected, prior.admission(), prior.processing(), true));
    }

    Optional<Prepared> committedSelection(SourceHistoryPrerequisite expected) {
        Prepared prior = submitted.get(expected.selectionIdentity());
        if (prior == null) return Optional.empty();
        if (!prior.descriptor().equals(expected)) throw new IllegalArgumentException("Changed submitted source selection");
        // An exact retained publication is the only route around stale-head checks after response loss.
        return engine.sourceHistoryPrerequisiteCommitted(prior) ? Optional.of(prior) : Optional.empty();
    }

    Prepared requireSelection(SourceHistoryPrerequisite expected) {
        Pending candidate = pending.get(key(expected.requestingInvocationIdentity(), expected.demandIdentity()));
        if (candidate == null || !stillCurrent(candidate)) throw new IllegalArgumentException("Source prerequisite is stale");
        Prepared selected = prepare(candidate);
        if (selected == null || !selected.descriptor().equals(expected))
            throw new IllegalArgumentException("Source prerequisite changed before execution");
        if (expected.kind() == SourceHistoryPrerequisite.Kind.WAIT)
            throw new blue.coordination.api.CoordinationException(blue.coordination.api.CoordinationErrorCode.NEEDS_RESOURCES,
                    expected.diagnostic());
        submitted.put(expected.selectionIdentity(), selected);
        return selected;
    }

    void retain(SourceHistoryPrerequisiteResult result) {
        boolean commits = result.admission().map(value -> value.published()).orElse(false)
                || result.processing().map(value -> value.committedProcessTransitions() > 0L).orElse(false);
        if (commits) completed.put(result.selection().selectionIdentity(), result);
    }

    private boolean stillCurrent(Pending candidate) {
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
        var source = documents.find(candidate.source()).orElse(null);
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
            String json = UncheckedObjectMapper.JSON_MAPPER.writeValueAsString(NodeWireForm.get(candidate.authored().copyNode()));
            var compiled = new Contracts10StaticEmbeddedAdmissionCompiler(engine).compile(json, provider,
                    Contracts10AuthoredClosureCompiler.ActivationInputs.fullHistory());
            if (!compiled.rootDocumentId().equals(candidate.source()))
                throw new IllegalArgumentException("Source compiler changed the exact authored identity");
            return selected(candidate, SourceHistoryPrerequisite.Kind.ADMISSION, compiled, null,
                    window.identity(), null, window.evidence());
        }
        var next = new RootedCheckpointDriver(documents, adapter).select(candidate.source(), journal.entries());
        if (next.blocked()) {
            String reason = RootedJoinPrerequisites.pendingBefore(candidate.source(),
                    source.rootedViewBefore(candidate.cutoff()), candidate.cutoff(), documents);
            return selected(candidate, SourceHistoryPrerequisite.Kind.WAIT, null, null,
                    window.identity(), reason == null ? "Source has unproven earlier history" : reason, window.evidence());
        }
        ExternalOrderKey origin;
        SourceHistoryPrerequisite.Kind kind;
        if (next.live() != null) { origin = next.live().entry().sourceOrderKey(); kind = SourceHistoryPrerequisite.Kind.LIVE; }
        else if (next.localHistorical() != null) {
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
        var step = journal.nextHistoricalStep(null, candidate.cutoff(), null, ignored -> false,
                routes.generation(), candidate.invocation().input().snapshot().graphGeneration(), () -> identity);
        if (step instanceof HistoricalStep.Unavailable wait) return new Window(identity, null, wait.diagnostic());
        if (step instanceof HistoricalStep.InvalidEvidence invalid) throw new IllegalArgumentException(invalid.diagnostic());
        CompletenessEvidence evidence = step instanceof HistoricalStep.Complete full ? full.evidence()
                : step instanceof HistoricalStep.CompleteEmpty empty ? empty.evidence() : null;
        if (evidence == null || !evidence.isCurrentFor(journal.revision(), routes.generation(),
                candidate.invocation().input().snapshot().graphGeneration(), candidate.cutoff(), identity))
            throw new IllegalArgumentException("Source completeness does not bind the exact pending window");
        return new Window(identity, evidence, null);
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
                : step.localHistorical() != null ? step.localHistorical().work().workIdentity() : step.historical().workIdentity();
        String entry = step != null && step.live() != null ? step.live().entry().blueId() : null;
        String root = candidate.invocation().rootedEvidence().context().canonicalRootDocumentId().value();
        var fields = new ArrayList<String>(List.of(root, candidate.invocation().input().invocationIdentity(),
                candidate.demand().demandIdentity(), candidate.source().value(), candidate.authored().blueId(),
                kind.name(), Long.toString(epoch), head, work, Objects.toString(entry, ""),
                Long.toString(journal.revision()), Long.toString(routes.generation()), surface, Objects.toString(diagnostic, "")));
        candidate.cutoff().components().forEach(value -> fields.add(value.toString()));
        var descriptor = new SourceHistoryPrerequisite(hash("blue.coordination/source-history-prerequisite/1", fields),
                DocumentId.of(root), candidate.invocation().input().invocationIdentity(), candidate.demand().demandIdentity(),
                candidate.source(), candidate.authored().blueId(), candidate.cutoff(), kind, epoch, head, work, entry,
                journal.revision(), routes.generation(), surface, diagnostic);
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
    private record Pending(ContractsClosureAdapter.CohortInvocation invocation, ClosureAttemptResult attempt,
            ManagedOccurrenceEvidenceDemand demand, DocumentId source, ExactValue authored, ExternalOrderKey cutoff) {
        String key() { return RootedSourceDiscoveryCoordinator.key(invocation.input().invocationIdentity(), demand.demandIdentity()); }
        boolean owns(DocumentId root) { return invocation.rootedEvidence().context().entryOwners()
                .contains(ContractsClosureAdapter.closureId(root)); }
    }
}
