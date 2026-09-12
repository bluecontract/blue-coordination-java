package blue.coordination.internal;

import blue.coordination.api.ContractsExecutionPolicy;
import blue.coordination.api.CoordinationEngine;
import blue.coordination.api.DocumentId;
import blue.coordination.api.ManagedEpochApplicationReceipt;
import blue.coordination.api.ManagedEpochApplicationWork;
import blue.language.processor.closure.ClosureInvocationInput;
import blue.language.processor.closure.ClosureProcessResult;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Test-only controls over real selected captures and the existing atomic application publisher. */
public final class RootedJoinPublicationSafetyProbe {
    private final DefaultCoordinationEngine engine;

    /** Attaches without replacing the processor, store, or execution policy. */
    public RootedJoinPublicationSafetyProbe(CoordinationEngine engine) {
        this.engine = (DefaultCoordinationEngine) Objects.requireNonNull(engine);
    }

    /** Reads registered due work even when root scheduling correctly waits for another owner. */
    public ManagedEpochApplicationWork registered(DocumentId consumer) {
        return engine.documents().nextCatchUpWorkExcluding(excluded(consumer)).orElseThrow();
    }

    /** Uses the ordinary registered capturer with the actual configured environment and budget. */
    public ClosureInvocationInput registeredInput(DocumentId consumer) {
        var admission = engine.contractsClosureAdmissionAdapter();
        var environment = admission.environment();
        var policy = admission.executionPolicy();
        var profile = ContractsClosureProfile.release10(environment.blueLanguageSpecificationIdentity(),
                environment.contractsSpecificationIdentity(),
                ContractsExecutionPolicy.exactSharedGas(policy.sharedLimit(), policy.label()), List.of(consumer));
        if (!profile.rootedCheckpoint() || !profile.executionPolicy().identity().equals(policy.identity()))
            throw new IllegalArgumentException("Expected the actual rooted release-profile SDK fixture");
        return new ManagedEpochInvocationCapturer(engine.contractsClosureAdapter(), engine.runtime(), engine.objects(),
                engine.documents(), profile, environment).capture(registered(consumer), excluded(consumer))
                .invocation().input();
    }

    /** Holds an actual local capture; callers cannot construct or replace its input or result. */
    public Selection capture(DocumentId root) {
        var local = Objects.requireNonNull(engine.contractsClosureAdapter()
                .nextRootLocalHistory(root, engine.auditTimelineEntries()).step(), "No actual local work");
        var excluded = excluded(local.work().consumerDocumentId());
        var work = engine.documents().nextCatchUpWorkExcluding(excluded).orElseThrow();
        return new Selection(work, excluded, local);
    }

    /** Executes the unchanged sealed local input through the canonical registered publisher. */
    public Publication publish(Selection selected) {
        Objects.requireNonNull(selected);
        var outcome = engine.contractsClosureAdapter().executeRootedJoinApplication(
                selected.work, selected.excluded, selected.local);
        return new Publication(outcome.attempt().processResult(), outcome.receipt().orElseThrow(),
                outcome.published(), outcome.replayed());
    }

    /** Reads the committed receipt after a lost acknowledgement, without invoking PROCESS or route repair. */
    public Optional<Publication> retained(Selection selected) {
        return engine.documents().catchUpApplicationByWork(selected.work.workIdentity()).map(application -> {
            var receipt = engine.documents().closureReceiptForApplication(application).orElseThrow();
            return new Publication(receipt.attempt().processResult(), application, true, false);
        });
    }

    /** Immutable store views include all owners, topology, outbox, checkpoints, receipts and catch-up state. */
    public Object publicationState() {
        return List.of(engine.documents().publicationSnapshot(), engine.documents().catchUpPlansSnapshot());
    }

    /** Actual managed PROCESS counter, distinct from caller attempts and retained-result lookup. */
    public long processCalls() {
        return engine.runtime().metrics().counter(ManagedEpochApplicationExecutor.PROCESS_CALLS);
    }

    private Set<DocumentId> excluded(DocumentId consumer) {
        return engine.documents().sessions().stream().map(DocumentSession::documentId)
                .filter(id -> !id.equals(consumer)).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    /** Opaque originally selected work and capture; no caller-supplied evidence constructors. */
    public static final class Selection {
        private final ManagedEpochApplicationWork work;
        private final Set<DocumentId> excluded;
        private final RootedLocalHistory.Step local;

        private Selection(ManagedEpochApplicationWork work, Set<DocumentId> excluded, RootedLocalHistory.Step local) {
            this.work = work;
            this.excluded = excluded;
            this.local = local;
        }

        /** Exact independently registered work paired with this original local capture. */
        public ManagedEpochApplicationWork work() { return work; }

        /** Immutable input of the original local capture, including its rooted evidence. */
        public ClosureInvocationInput input() { return local.invocation().input(); }
    }

    /** The actual result and response-loss-safe application receipt, never a test-generated result. */
    public record Publication(ClosureProcessResult result, ManagedEpochApplicationReceipt application,
            boolean published, boolean replayed) { }
}
