package blue.coordination.internal;

import blue.coordination.api.CoordinationEngine;
import blue.coordination.api.DocumentId;
import blue.coordination.api.SourceHistoryPrerequisite;
import blue.language.processor.closure.ClosureExecutionEvidenceStorageCodec;
import blue.language.processor.closure.ManagedOccurrenceEvidenceDemand;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.function.Function;
import static blue.coordination.internal.SessionRecordCodec.*;
import static blue.coordination.internal.SessionStorageWire.*;

/** Exact stopped demand and submitted source step; decoding never selects or executes work. */
final class SourceDiscoveryStorageCodec {
    private static final String PENDING = "blue-coordination/source-pending/1";
    private static final String PREPARED = "blue-coordination/source-prepared/2";
    private final int maximumBytes;
    private final SessionRecordCodec rows;
    private final CohortInvocationStorageCodec cohorts;
    private final ManagedWorkStorageCodec works;
    private final ClosureExecutionEvidenceStorageCodec execution;
    private final blue.language.snapshot.ExactNodeStorageCodec nodes;
    private final RootedLocalStepStorageCodec localSteps;

    SourceDiscoveryStorageCodec(int maximumBytes, int maximumDepth) {
        this(maximumBytes, maximumDepth, null);
    }

    SourceDiscoveryStorageCodec(int maximumBytes, int maximumDepth, RootedStorageCache cache) {
        this.maximumBytes = maximumBytes;
        rows = new SessionRecordCodec(maximumBytes, maximumDepth);
        cohorts = new CohortInvocationStorageCodec(maximumBytes, maximumDepth, cache);
        works = new ManagedWorkStorageCodec(maximumBytes, maximumDepth, cache);
        execution = new ClosureExecutionEvidenceStorageCodec(maximumBytes, maximumDepth,
                new StoredClosureResultCodec(maximumBytes, maximumDepth, cache).configured());
        nodes = new blue.language.snapshot.ExactNodeStorageCodec(maximumBytes, maximumDepth);
        localSteps = new RootedLocalStepStorageCodec(maximumBytes, maximumDepth, cache);
    }

    byte[] encodePending(RootedSourceDiscoveryCoordinator.Pending value, Function<RootedDocumentView, String> views) {
        requirePending(value);
        return SessionStorageWire.encode(maximumBytes, w -> {
            w.text(PENDING); w.text(value.key());
            w.bytes(cohorts.encodeAttempt(value.invocation(), value.attempt(), value.demand(), views));
            w.text(value.source().value()); rows.exact(w, value.authored()); order(w, value.cutoff());
        });
    }

    RootedSourceDiscoveryCoordinator.Pending decodePending(String expectedKey, byte[] bytes, DocumentSessionStorage.OpenScope scope) {
        return physical(() -> {
            var value = SessionStorageWire.decode(bytes, maximumBytes, r -> {
                require(PENDING.equals(text(r)) && expectedKey.equals(text(r)), "Wrong pending source row/key");
                var attempt = cohorts.decodeAttempt(r.bytes(maximumBytes), scope);
                require(attempt.selectedDemand() instanceof ManagedOccurrenceEvidenceDemand, "Pending source lost its actual managed demand");
                return new RootedSourceDiscoveryCoordinator.Pending(attempt.invocation(), attempt.attempt(),
                        (ManagedOccurrenceEvidenceDemand) attempt.selectedDemand(), DocumentId.of(text(r)), rows.exact(r), order(r));
            });
            requirePending(value); require(expectedKey.equals(value.key()), "Pending source key changed its original input/demand");
            require(Arrays.equals(bytes, encodePending(value, scope::addressOf)), "Noncanonical pending source evidence");
            return value;
        });
    }

    private static void requirePending(RootedSourceDiscoveryCoordinator.Pending value) {
        Objects.requireNonNull(value); Objects.requireNonNull(value.cutoff());
        var input = value.invocation().input(); var demand = value.demand();
        require(value.invocation().rootedEvidence() != null && !value.attempt().isComplete()
                && value.attempt().resourceDemands().stream().anyMatch(actual -> actual == demand),
                "Pending source requires the original suspended rooted attempt and selected demand member");
        require(value.source().value().equals(value.authored().blueId())
                && input.cause().causeIdentity().equals(demand.logicalCauseIdentity())
                && input.snapshot().closureIdentity().equals(demand.inputClosureIdentity())
                && input.snapshot().graphGeneration() == demand.inputGraphGeneration(), "Pending source changed its captured authority");
    }

    byte[] encodePrepared(RootedSourceDiscoveryCoordinator.Prepared value, Function<RootedDocumentView, String> views) {
        requirePrepared(value);
        return SessionStorageWire.encode(maximumBytes, w -> {
            w.text(PREPARED); descriptor(w, value.descriptor()); optional(w, value.admission(), this::admission);
            optional(w, value.step(), (out, step) -> step(out, step, views));
            optional(w, value.completeness(), (out, proof) -> {
                out.longValue(proof.journalRevision()); out.longValue(proof.routeIndexGeneration()); out.longValue(proof.graphGeneration());
                order(out, proof.cutoffExclusive()); out.text(proof.sourceSurfaceIdentity());
            });
        });
    }

    RootedSourceDiscoveryCoordinator.Prepared decodePrepared(String expectedSelection, byte[] bytes, DocumentSessionStorage.OpenScope scope) {
        return physical(() -> {
            var value = SessionStorageWire.decode(bytes, maximumBytes, r -> {
                require(PREPARED.equals(text(r)), "Wrong prepared source format");
                return new RootedSourceDiscoveryCoordinator.Prepared(descriptor(r), optional(r, this::admission),
                        optional(r, in -> step(in, scope)), optional(r, in -> new CompletenessEvidence(
                                in.longValue(), in.longValue(), in.longValue(), order(in), text(in))));
            });
            requirePrepared(value); require(expectedSelection.equals(value.descriptor().selectionIdentity()), "Prepared source belongs to another selection");
            require(Arrays.equals(bytes, encodePrepared(value, scope::addressOf)), "Noncanonical prepared source evidence");
            return value;
        });
    }

    private void requirePrepared(RootedSourceDiscoveryCoordinator.Prepared value) {
        Objects.requireNonNull(value); var d = value.descriptor(); var step = value.step();
        int modes = value.admission() == null ? 0 : 1;
        if (step != null) {
            require(!step.blocked(), "A submitted source step cannot be blocked");
            modes += step.live() == null ? 0 : 1; modes += step.historical() == null ? 0 : 1;
            // A registered terminal with its exact local calculation is one step, not two executions.
            modes += step.localHistorical() != null && step.historical() == null ? 1 : 0;
        }
        require(modes == (d.kind() == SourceHistoryPrerequisite.Kind.WAIT ? 0 : 1), "Prepared source has inconsistent phase count");
        switch (d.kind()) {
            case WAIT -> require(value.admission() == null && step == null, "Wait must not contain executable work");
            case ADMISSION -> {
                var a = Objects.requireNonNull(value.admission());
                require(step == null && a.rootDocumentId().equals(d.sourceDocumentId())
                        && a.invocation().invocationIdentity().equals(d.workIdentity())
                        && a.activationInputs().policy() == CoordinationEngine.AdmissionPolicy.FULL_HISTORY,
                        "Prepared admission changed source, input or full-history policy");
                var root = a.invocation().snapshot().managedDocument(ContractsClosureAdapter.closureId(d.sourceDocumentId()));
                require(root != null && !root.initialized() && root.blueId().equals(d.authoredBlueId()),
                        "Prepared admission lost its authored root identity or lifecycle");
                require(Arrays.equals(nodes.encode(root.document()), nodes.encode(a.authoredRoot())),
                        "Prepared admission authored body differs from its original snapshot");
            }
            case LIVE -> {
                var live = Objects.requireNonNull(Objects.requireNonNull(step).live());
                require(!live.invocations().isEmpty() && live.entry().blueId().equals(d.entryBlueId())
                        && live.invocations().get(0).executionInvocationIdentity().equals(d.workIdentity())
                        && live.entry().sourceOrderKey().compareTo(d.cutoffExclusive()) < 0,
                        "Prepared LIVE step changed its exact entry, work or logical boundary");
            }
            case MANAGED_HISTORY -> {
                var work = Objects.requireNonNull(Objects.requireNonNull(step).historical());
                var local = step.localHistorical();
                boolean ownsSource = local == null ? work.consumerDocumentId().equals(d.sourceDocumentId())
                        : local.invocation().rootedEvidence().context().entryOwners()
                                .contains(ContractsClosureAdapter.closureId(d.sourceDocumentId()));
                require(work.workIdentity().equals(d.workIdentity()) && ownsSource,
                        "Prepared history work belongs to another source");
            }
            case ROOTED_RETAINED -> {
                var local = Objects.requireNonNull(Objects.requireNonNull(step).localHistorical());
                require(local.root().equals(d.sourceDocumentId()) && local.work().workIdentity().equals(d.workIdentity())
                        && local.anchor().sourceOrderKey().compareTo(d.cutoffExclusive()) < 0, "Prepared rooted history changed its source or boundary");
            }
        }
        var proof = value.completeness();
        require(d.kind() == SourceHistoryPrerequisite.Kind.WAIT || proof != null, "Executable source work lost completeness evidence");
        if (proof != null) require(proof.journalRevision() == d.journalRevision() && proof.routeIndexGeneration() == d.routeGeneration()
                && proof.cutoffExclusive().equals(d.cutoffExclusive()) && proof.sourceSurfaceIdentity().equals(d.sourceSurfaceIdentity()),
                "Prepared source completeness differs from selected window");
    }

    void descriptor(Writer w, SourceHistoryPrerequisite d) {
        w.text(d.selectionIdentity()); w.text(d.requestingRoot().value()); w.text(d.requestingInvocationIdentity()); w.text(d.demandIdentity());
        w.text(d.sourceDocumentId().value()); w.text(d.authoredBlueId()); order(w, d.cutoffExclusive()); w.text(d.kind().name());
        w.longValue(d.sourceEpoch()); w.text(d.sourceBlueId()); w.text(d.workIdentity()); w.nullableText(d.entryBlueId());
        w.longValue(d.journalRevision()); w.longValue(d.routeGeneration()); w.text(d.sourceSurfaceIdentity()); w.nullableText(d.diagnostic());
    }

    SourceHistoryPrerequisite descriptor(Reader r) {
        return new SourceHistoryPrerequisite(text(r), DocumentId.of(text(r)), text(r), text(r), DocumentId.of(text(r)), text(r),
                order(r), SourceHistoryPrerequisite.Kind.valueOf(text(r)), r.longValue(), text(r), text(r), nullableText(r),
                r.longValue(), r.longValue(), text(r), nullableText(r));
    }

    private void admission(Writer w, Contracts10StaticEmbeddedAdmissionCompiler.CompiledStaticAdmission a) {
        w.bytes(execution.encodeInvocation(a.invocation())); w.text(a.activationInputs().policy().name());
        optional(w, a.activationInputs().verifiedFrontier(), SessionStorageWire::order);
        w.text(a.rootDocumentId().value()); rows.node(w, a.authoredRoot());
    }

    private Contracts10StaticEmbeddedAdmissionCompiler.CompiledStaticAdmission admission(Reader r) {
        return new Contracts10StaticEmbeddedAdmissionCompiler.CompiledStaticAdmission(execution.decodeInvocation(r.bytes(maximumBytes)),
                new Contracts10AuthoredClosureCompiler.ActivationInputs(CoordinationEngine.AdmissionPolicy.valueOf(text(r)),
                        optional(r, SessionStorageWire::order)), DocumentId.of(text(r)), rows.node(r));
    }

    private void step(Writer w, RootedCheckpointDriver.Selection s, Function<RootedDocumentView, String> views) {
        list(w, s.excludedConsumers().stream().sorted(EmbeddingBinding.DOCUMENT_ORDER).toList(), (out, id) -> out.text(id.value()));
        w.bool(s.blocked()); optional(w, s.live(), (out, live) -> {
            rows.entry(out, live.entry()); out.longValue(live.routeGeneration());
            list(out, live.invocations(), (inner, invocation) -> inner.bytes(cohorts.encode(invocation, views)));
        });
        optional(w, s.historical(), works::work);
        optional(w, s.localHistorical(), (out, local) -> out.bytes(localSteps.encode(local, views)));
    }

    private RootedCheckpointDriver.Selection step(Reader r, DocumentSessionStorage.OpenScope scope) {
        var excluded = list(r, in -> DocumentId.of(text(in))); require(new LinkedHashSet<>(excluded).size() == excluded.size(), "Duplicate excluded source consumer");
        boolean blocked = r.bool();
        var live = optional(r, in -> new ContractsClosureAdapter.FrozenBatch(rows.entry(in), in.longValue(),
                list(in, inner -> cohorts.decode(inner.bytes(maximumBytes), scope))));
        var history = optional(r, works::work);
        var local = optional(r, in -> localSteps.decode(in.bytes(maximumBytes), scope));
        return new RootedCheckpointDriver.Selection(live, history, new LinkedHashSet<>(excluded), blocked, local);
    }
}
