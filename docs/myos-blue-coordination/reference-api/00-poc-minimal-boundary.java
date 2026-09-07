/*
 * REVISION 15.12 — IMPLEMENTED LOCAL POC API USAGE REFERENCE (2026-09-06).
 *
 * This file is documentation, not a shipped class, server, wire schema or complete host adapter.
 * Example helper names below belong only to this file. Imported classes and invoked public
 * signatures are from the implemented coordination-external-state worktrees.
 * No build or conformance result is implied by these examples.
 *
 * Semantics: ../22-processing-kernel.md.
 * Concrete contract: ../05-poc-api.md; rationale: ../05-api-contract.md.
 * Implemented scope/evidence: ../24-phase-1-library-summary.md and ../implementation/.
 * Remaining application integration: ../25-phase-3-integration-plan.md.
 *
 * The previous version declared proposed SemanticConfiguration, CommitReceipt, fan-out ports,
 * progress unions and ObservationFrame DTOs. Those were a design sketch, not implemented APIs.
 * Host requirements are retained explicitly as non-executable notes at the end of this file.
 */
package documentation.examples;

import blue.coordination.api.ExactValue;
import blue.coordination.external.CanonicalSourceAwaitCodec;
import blue.coordination.external.CanonicalSourceHistory;
import blue.coordination.external.CoordinationCore;
import blue.coordination.external.ManagedImportSelection;
import blue.coordination.external.OperationReceiptCodec;
import blue.coordination.external.SourceFrontierSelection;
import blue.coordination.external.SourceInputAdmission;
import blue.language.processor.DocumentProcessor;
import blue.language.processor.ExternalOrderKey;
import blue.language.processor.closure.AffectedClosureSnapshot;
import blue.language.processor.closure.ClosureEnvironment;
import blue.language.processor.closure.DocumentId;
import blue.language.processor.closure.ExecutionPolicy;
import blue.language.processor.closure.FrozenNodeEvidenceCodec;
import blue.language.processor.closure.ManagedReadPin;
import blue.language.processor.closure.RootChannelMetadata;
import blue.language.processor.closure.SameOriginAttachmentPolicy;
import blue.language.processor.closure.SourceInitialization;
import blue.language.processor.closure.SourceObservationGap;
import blue.language.processor.closure.SourceObservationProgram;
import blue.language.processor.closure.SourceOperationFailure;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/* These package-private helpers demonstrate calls; they do not extend the library API. */
final class PocMinimalBoundaryReference {
    private PocMinimalBoundaryReference() { }

    static CoordinationCore core(
            DocumentProcessor processor,
            ClosureEnvironment exactEnvironment,
            ExecutionPolicy fixedExecutionPolicy) {
        // Exactly three constructor arguments. Scope/public roots belong to snapshot evidence.
        // Core has no public SemanticConfiguration or environment()/executionPolicy() getters.
        return new CoordinationCore(processor, exactEnvironment, fixedExecutionPolicy);
    }

    static CoordinationCore.TimelineInput timelineInput(
            String exactTimelineLocator,
            long timestampMicros,
            ExactValue completeTypedTimelineEntry) {
        /*
         * Core verifies the typed Coordination/Timeline Entry and its typed MyOS/Timeline,
         * exact /timeline/timelineId locator, timestamp, actor, message and source.
         * The locator is NOT the Timeline object's BlueId. The host Timeline store's typed
         * Timeline BlueId must be translated through authenticated exact Timeline evidence.
         *
         * entry and event identify the SAME complete Entry, not just its message.
         * 0 < timestampMicros < 2^53-1.
         * order() = [timestampMicros, completeTypedTimelineEntry.blueId()].
         * Core derives actual deliveries from the checked current channel/read cut.
         */
        return new CoordinationCore.TimelineInput(
                exactTimelineLocator, timestampMicros,
                completeTypedTimelineEntry, completeTypedTimelineEntry, List.of());
    }

    static CoordinationCore.EvaluationEvidence evidence(
            AffectedClosureSnapshot directedReadCut,
            Set<String> completeRelevantTimelineLocators,
            List<CoordinationCore.TimelinePrefix> completePrefixes,
            Optional<ExternalOrderKey> handledThrough,
            List<CoordinationCore.ReadFence> readFences,
            Map<DocumentId, String> precedingOperations,
            List<SourceObservationProgram> retainedSourcePrograms,
            Map<DocumentId, List<SourceObservationGap>> gapsBySource,
            List<SourceOperationFailure> retainedSourceFailures,
            List<SourceInitialization> offeredInitializations,
            Map<DocumentId, List<CoordinationCore.ReadFence>> fencesByOwner,
            List<SourceFrontierSelection> offeredFrontiers,
            Map<DocumentId, String> authenticatedProducerBases,
            List<SourceInputAdmission> originalSourceAdmissions,
            Map<DocumentId, String> authenticatedOriginalProducerRoots) {
        /*
         * This is the actual full record constructor. Shorter constructors also exist.
         * withSourceInitializations, withOperationFences, withSourceFrontiers,
         * withExpectedSourceBases, withSourceInputAdmissions and withOriginalSourceInputRoots
         * return new evidence values;
         * they are not separate evaluator entry points.
         *
         * Relevant membership is complete directed live membership, not arbitrary host choice.
         * Every relevant prefix must be complete strictly after the selected timestamp.
         * Sparse bodies do not permit missing topology, quiet Timelines, markers or selected pins.
         * Retained source facts are offered evidence, not ambient activation or publication.
         * Expected producer bases come from independently trusted source context, not from
         * the offered result or the importing consumer's possibly different gas budget.
         * This includes used initialization/borrowed-init capabilities and selected frontier views.
         * A history Request authenticates the requested source's original root by Entry ID.
         * Original producer roots here authenticate independent fresh producers by lineage for
         * this selected Entry. Offered records alone never establish either authority.
         * Missing authority yields a noncommitting need at actual fresh seed admission; the
         * observer cannot supply its own choices or budget as an independent source's input.
         *
         * An owned group's mutable CAS fences are its owners' exact union. A consumed immutable
         * source operation is a dependency, not a stale mutable-source-head fence.
         * Metadata progress uses only its target's attributed entry, even when explicitly empty.
         * A partial owner map lacking the target yields a named need; only a wholly empty map
         * retains the legacy metadata fallback. Never union independent source-head fences.
         */
        return new CoordinationCore.EvaluationEvidence(
                directedReadCut, completeRelevantTimelineLocators, completePrefixes,
                handledThrough, readFences, precedingOperations, retainedSourcePrograms,
                gapsBySource, retainedSourceFailures, offeredInitializations,
                fencesByOwner, offeredFrontiers, authenticatedProducerBases, originalSourceAdmissions,
                authenticatedOriginalProducerRoots);
    }

    static CoordinationCore.EvaluationResult initialize(
            CoordinationCore core, DocumentId source, CoordinationCore.EvaluationEvidence evidence) {
        return core.evaluate(new CoordinationCore.WorkIntent(
                source, CoordinationCore.OperationKind.INITIALIZATION), evidence);
    }

    static CoordinationCore.EvaluationResult nextExternalInput(
            CoordinationCore core, DocumentId target, CoordinationCore.EvaluationEvidence evidence) {
        return core.evaluate(new CoordinationCore.WorkIntent(
                target, CoordinationCore.OperationKind.EXTERNAL_INPUT), evidence);
    }

    static CoordinationCore.EvaluationResult nextExternalInputWithAttachments(
            CoordinationCore core, DocumentId target, CoordinationCore.EvaluationEvidence evidence,
            SameOriginAttachmentPolicy exactAttachmentChoices) {
        // Nonempty choices are external-only; FULL_HISTORY/FROM_NOW/FROM_FRONTIER choose
        // observer history/placement, never an alternative canonical source birth.
        return core.evaluate(new CoordinationCore.WorkIntent(
                target, CoordinationCore.OperationKind.EXTERNAL_INPUT), evidence, exactAttachmentChoices);
    }

    static CoordinationCore.EvaluationResult importManagedReaction(
            CoordinationCore core, ManagedImportSelection exactDueLanes,
            CoordinationCore.EvaluationEvidence evidence) {
        // No consumer external Timeline advancement. A bare MANAGED_RECEIPT_IMPORT WorkIntent
        // instead returns a named need for the actual managed-reaction selection.
        return core.evaluate(exactDueLanes, evidence);
    }

    static SourceInitialization initializationCapability(SourceObservationProgram successfulInitialization) {
        // Closed validation requires usable canonical initialization, including epoch/markers.
        // A capability or encoded candidate is not an authoritative source publication.
        return SourceInitialization.fromProgram(successfulInitialization);
    }

    static CanonicalSourceHistory.Cursor beginCanonicalSourceHistory(
            CoordinationCore core, DocumentId source) {
        return new CanonicalSourceHistory(core).start(source);
    }

    static CanonicalSourceHistory.Result prepareCanonicalSourcePrefixStep(
            CoordinationCore core,
            DocumentId source,
            ExternalOrderKey inclusiveCut,
            CanonicalSourceHistory.Cursor retainedCursor,
            CoordinationCore.EvaluationEvidence exactNextCut,
            Map<String, String> authenticatedOriginalAdmissionRoots,
            List<SourceInputAdmission> originalAdmissions,
            FrozenNodeEvidenceCodec.Writer fragments,
            FrozenNodeEvidenceCodec.Limits physicalCodecLimits) {
        // Actual composition, not a Core.prepareSourcePrefixStep method.
        // Await / Step / Complete / Blocked are CanonicalSourceHistory results.
        // Step.publications() retains EVERY independently atomic prerequisite/group.
        // Preparation retains immutable evidence; it does not advance authoritative host heads.
        // Sparse per-step roots come from original logical input authority, not this worker.
        // Even an empty selection is explicit. Missing admission returns Await before execution.
        return new CanonicalSourceHistory(core).prepareNext(
                new CanonicalSourceHistory.Request(source, inclusiveCut, authenticatedOriginalAdmissionRoots),
                retainedCursor, exactNextCut.withSourceInputAdmissions(originalAdmissions),
                fragments, physicalCodecLimits);
    }

    static SourceInputAdmission restoreOriginalSourceInput(
            String authenticatedOriginalAdmissionRoot,
            FrozenNodeEvidenceCodec.Reader fragments,
            FrozenNodeEvidenceCodec.Limits physicalCodecLimits) {
        // encodeCandidate produces bytes only; it does not authenticate an original choice.
        return SourceInputAdmission.restore(authenticatedOriginalAdmissionRoot, fragments, physicalCodecLimits);
    }

    static CanonicalSourceHistory.Await restoreHistoryNeed(
            String retainedNeedRoot, FrozenNodeEvidenceCodec.Reader fragments,
            FrozenNodeEvidenceCodec.Limits physicalCodecLimits) {
        // Includes typed requests, not only hashes; this does not admit the demanded resources.
        return CanonicalSourceAwaitCodec.decode(retainedNeedRoot, fragments, physicalCodecLimits);
    }

    static CanonicalSourceHistory.Cursor resumeCanonicalSourceHistory(
            CoordinationCore core, DocumentId source, String authenticatedRecordIdentity,
            FrozenNodeEvidenceCodec.Reader fragments,
            FrozenNodeEvidenceCodec.Limits physicalCodecLimits) {
        return new CanonicalSourceHistory(core).resume(
                source, authenticatedRecordIdentity, fragments, physicalCodecLimits);
    }

    static SourceFrontierSelection frontierSelection(
            SameOriginAttachmentPolicy.Selection selection,
            CanonicalSourceHistory.Boundary completeBoundary,
            String authenticatedTerminalReceipt,
            FrozenNodeEvidenceCodec.Reader fragments,
            FrozenNodeEvidenceCodec.Limits physicalCodecLimits) {
        // An exact SourceFrontierView by itself is NOT proof of prefix completeness.
        return SourceFrontierSelection.fromBoundary(
                selection, completeBoundary, authenticatedTerminalReceipt, fragments, physicalCodecLimits);
    }

    static OperationReceiptCodec.Encoded retainOperation(
            CoordinationCore.PreparedOperation operation,
            FrozenNodeEvidenceCodec.Writer fragments,
            FrozenNodeEvidenceCodec.Limits physicalCodecLimits) {
        return OperationReceiptCodec.encode(operation, fragments, physicalCodecLimits);
    }

    static List<OperationReceiptCodec.Encoded> retainEveryPreparedGroup(
            CoordinationCore.PreparedOperations prepared,
            FrozenNodeEvidenceCodec.Writer fragments,
            FrozenNodeEvidenceCodec.Limits physicalCodecLimits) {
        /*
         * Retain in dependency order, without synthesizing a whole-closure result or dropping
         * failed groups. This example ONLY encodes evidence. It performs no authority transaction.
         * Each encoded row corresponds to prepared.operations().get(the same index).
         * Optional prepared.targetProgress() is separate metadata bookkeeping, not another group.
         */
        List<OperationReceiptCodec.Encoded> retained = new ArrayList<>();
        for (CoordinationCore.PreparedGroupOperation group : prepared.operations()) {
            retained.add(OperationReceiptCodec.encode(group, fragments, physicalCodecLimits));
        }
        return List.copyOf(retained);
    }

    static SourceObservationProgram restoreSourceProgram(
            String authenticatedCommittedReceipt,
            FrozenNodeEvidenceCodec.Reader fragments,
            FrozenNodeEvidenceCodec.Limits physicalCodecLimits) {
        // Expected receipt identity comes from committed, validated operation authority.
        // A digest supplied by an untrusted host object authenticates only its bytes.
        return OperationReceiptCodec.restoreSourceProgram(
                authenticatedCommittedReceipt, fragments, physicalCodecLimits);
    }

    static SourceObservationGap restoreConsumerGap(
            String authenticatedFailureReceipt,
            DocumentId consumer,
            DocumentId source,
            String authenticatedOfferedSourceReceipt,
            FrozenNodeEvidenceCodec.Reader fragments,
            FrozenNodeEvidenceCodec.Limits physicalCodecLimits) {
        // The observed successful view is per consumer AND source, not one global source cursor.
        return OperationReceiptCodec.restoreSourceGap(
                authenticatedFailureReceipt, consumer, source,
                authenticatedOfferedSourceReceipt, fragments, physicalCodecLimits);
    }

    static AffectedClosureSnapshot evictBodies(
            AffectedClosureSnapshot verifiedCompleteCut,
            Set<DocumentId> keepResident,
            Collection<RootChannelMetadata> exactRootChannels) {
        // Retains verified component/member/root-channel authority, not placeholder Nodes.
        return verifiedCompleteCut.retainResidentBodies(keepResident, exactRootChannels);
    }

    static AffectedClosureSnapshot hydrate(
            AffectedClosureSnapshot verifiedSparseCut, ManagedReadPin exactMissingBody) {
        // Only the exact selected body/proof can satisfy this need; never substitute latest.
        return verifiedSparseCut.withResidentBody(exactMissingBody);
    }
}

/*
 * HOST PROTOCOL NOTES — deliberately NOT Java interfaces or an implemented transaction adapter.
 *
 * Result dispatch (the complete public CoordinationCore.EvaluationResult union):
 *
 *   NeedEvidence:
 *     retain named keys and actual typed resourceDemands where supplied; arrange durable wakeup
 *     or exact acquisition, then re-enter evaluation. No portable tentative state/queue/gas result.
 *   Idle:
 *     no selected local work at this cut; not proof that global fan-out is complete.
 *   MetadataProgress:
 *     handled-input metadata only; no target processor invocation, business epoch or output event.
 *     Respect supplied fences and consumedSourceOperations.
 *   ManagedProgress:
 *     failed-source lane accounting only; no observer business invocation or external cursor advance.
 *   PreparedOperation:
 *     inspect its actual kind, disposition, result, projections, sourcePins, dependencies and
 *     managed lane deltas. Commit/record only the complete owning outcome.
 *   PreparedOperations:
 *     retain every group and optional targetProgress. Publish prerequisite groups before dependents.
 *     Each group owns one atomic transaction across ALL its owned lineages, gas, effects and outbox;
 *     the container is NOT one global atomic transaction and is NOT a partial interpreter result.
 *     SameOriginGroupEvidence separately binds interpreted initialization/frontier authority.
 *     Those identity inputs are not external consumed-source operations or publication demands.
 *
 * Core's fixed operation laws:
 *   - INITIALIZATION needs a committing usable result; failure cannot publish initialized authority.
 *   - Completed same-origin EXTERNAL_INPUT groups consume their exact selected input, including
 *     certified gas/runtime failures. State rollback and terminal input progress remain distinct.
 *   - MANAGED_RECEIPT_IMPORT consumes committing / GAS_LIMIT_EXCEEDED / certified RUNTIME_FATAL
 *     outcomes; other statuses are blocked. Source-only failures can produce ManagedProgress.
 *   - Failed managed work preserves the actual successful state; exact terminal lane/gap authority
 *     can allow a later import to align to its authenticated source-before, without replaying the
 *     failed event or claiming successful cursor progress.
 *   - Missing evidence, cancellation, I/O, resource holds, unknown commits and unclassified faults
 *     do not become semantic runtime failures. There is no configurable disposition-table API.
 *
 * Source/attachment requirements:
 *   - Canonical source initialization is independent of the first observer and its FROM_* choice.
 *   - SourceInitialization, accepted creator-site evidence and a surviving occurrence authorize
 *     the appropriate lane factory. Merely offering an initialization does not activate it.
 *   - ObserverAttachmentPlan and closed SourceFrontierSelection bind history choices. A historical
 *     successful view can differ from terminal source progress; preserve both.
 *   - SourceObservationProgram retains real Steps/Patch/resultingBindings/Enqueue, reference
 *     projections, borrowed programs, accepted creation views and TerminationRequest actions.
 *     Already-accepted terminal work cutoffs are authenticated separate skipped-work records,
 *     not fake executed Steps. Retained replay does not rerun producer business handlers.
 *
 * Host transaction/recovery requirements (not Core methods):
 *   - Verify exact predecessor/fence authority for the owning projections. An already committed
 *     immutable source dependency need not remain the current mutable source head.
 *   - Atomically record each group's owned results, terminal/successful progress as applicable,
 *     one gas settlement, receipt linkage and matching outbox/index deltas. Never split a group.
 *   - Source publication must not wait for all independent observers or enumerate all recipients.
 *     Use durable indexed discovery/registration coverage; no-gap wakeup authority is positive
 *     evidence, not a currently empty SQL page. Physical pages do not choose semantic order.
 *   - Reconcile an unknown transaction outcome by its exact durable identity before retrying its
 *     effects. Cancellation or a worker restart does not create a second logical settlement.
 *   - Create-then-retire retains observed preparation facts/gas but creates no orphan source
 *     publication or surviving import lane. Existing authoritative sources are not rolled back
 *     by a failed later creator.
 *
 * Phase2 supplies PostgreSQL storage/indexes, ready/reverse-wait primitives and cold recovery
 * witnesses. General graph mapping/application pumps and scale measurement remain Phase3 work.
 * CommitReceipt / CommitUnknown / DurableFanoutBasis / HostWorkSelectionPort / RecoveryPort in
 * surrounding prose are host concepts, not the old sketch's shipped public ports.
 *
 * Current lazy boundary:
 *   complete authenticated directed metadata/read cut + optionally absent application bodies;
 *   named demands at actual reads, owning execution or changed/newly owned SCC proof requirements;
 *   no reverse-only observer inventory and no ambient later-source substitution;
 *   source programs/borrowed DAG currently materialized within physical codec limits, not streamed;
 *   capture-time memory must still be measured; physical budgets never change semantics or gas;
 *   complete M1 may publish while an execution-only M2 body is missing, but M1's own required
 *   selection/execution fact still holds M1. No private continuation is made portable.
 *
 * These are POC integration requirements, not evidence that every acceptance-catalog scenario,
 * production-scale workload or deployment procedure has already passed.
 */
