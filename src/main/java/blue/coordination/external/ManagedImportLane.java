package blue.coordination.external;

import blue.language.processor.ExternalOrderKey;
import blue.language.processor.ProcessorStatus;
import blue.language.processor.closure.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.util.*;

/**
 * One committed occurrence/activation's historical lane. Its terminal input cursor is
 * independent of the consumer's external Timeline cursor and successful embedded view.
 * Restored descriptors, headers and cursors must come from authenticated committed roots;
 * their hashes establish exact data identity, not execution or publication authority.
 */
public final class ManagedImportLane {
    private ManagedImportLane() { }
    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

    /** Source-operation order is its producing position, not retained event provenance. */
    public record Header(DocumentId sourceLineage, String canonicalSourceBasis, String operationIdentity,
                         Optional<String> predecessorOperationIdentity, CoordinationCore.OperationKind kind,
                         ProcessorStatus status, String beforeBlueId, long beforeEpoch, String afterBlueId, long afterEpoch,
                         Optional<ExternalOrderKey> productionOrder, String reactionPositionIdentity) {
        public Header {
            Objects.requireNonNull(sourceLineage); text(canonicalSourceBasis); sha(operationIdentity);
            predecessorOperationIdentity = Objects.requireNonNull(predecessorOperationIdentity); predecessorOperationIdentity.ifPresent(ManagedImportLane::sha);
            Objects.requireNonNull(kind); Objects.requireNonNull(status); text(beforeBlueId); text(afterBlueId);
            epoch(beforeEpoch); epoch(afterEpoch); sha(reactionPositionIdentity);
            productionOrder = Objects.requireNonNull(productionOrder); productionOrder.ifPresent(CanonicalSourceHistory::requireOrder);
            if ((kind == CoordinationCore.OperationKind.INITIALIZATION) == productionOrder.isPresent()) throw invalid("Source operation production position is missing or spurious");
            if (status != ProcessorStatus.SUCCESS && status != ProcessorStatus.GAS_LIMIT_EXCEEDED && status != ProcessorStatus.RUNTIME_FATAL)
                throw invalid("Source header is not a supported terminal operation");
            if (status != ProcessorStatus.SUCCESS && (!beforeBlueId.equals(afterBlueId) || beforeEpoch != afterEpoch))
                throw invalid("Failed source header changed its successful view");
            if (kind == CoordinationCore.OperationKind.INITIALIZATION && (status != ProcessorStatus.SUCCESS || afterEpoch != 0 || predecessorOperationIdentity.isPresent()))
                throw invalid("A source initialization header must establish canonical init0");
        }

        /** Derives a header from the complete owning result before encoding its committed receipt. */
        public static Header fromOperation(CoordinationCore.PreparedOperation operation, DocumentId source, String basis) {
            if (operation.disposition() != CoordinationCore.Disposition.CONSUMED) throw invalid("A blocked operation is not a source history header");
            var state = operation.projections().stream().filter(value -> value.lineage().equals(source)).findFirst()
                    .orElseThrow(() -> invalid("Operation does not own the source lineage"));
            Optional<ExternalOrderKey> order;
            String position;
            if (operation.kind() == CoordinationCore.OperationKind.MANAGED_RECEIPT_IMPORT) {
                ManagedReactionContext reaction = operation.managedReaction().orElseThrow(() -> invalid("Managed source header lacks its producing reaction position"));
                order = Optional.of(reaction.activationCut()); position = reaction.reactionOriginIdentity();
            } else {
                order = operation.input().map(CoordinationCore.TimelineInput::order); position = operation.invocation().cause().causeIdentity();
            }
            return new Header(source, basis, operation.operationId(), Optional.ofNullable(state.precedingOperation()), operation.kind(),
                    operation.result().status(), state.beforeBlueId(), state.beforeEpoch(), state.afterBlueId(), state.afterEpoch(), order, position);
        }

        /** Header of one independently atomic external group, never a projection of an enclosing global result. */
        public static Header fromOperation(CoordinationCore.PreparedGroupOperation operation, DocumentId source, String basis) {
            Objects.requireNonNull(operation, "operation");
            var state = operation.projections().stream().filter(value -> value.lineage().equals(source)).findFirst()
                    .orElseThrow(() -> invalid("Group operation does not own the source lineage"));
            return new Header(source, basis, operation.operationId(), Optional.ofNullable(state.precedingOperation()), operation.kind(),
                    operation.result().status(), state.beforeBlueId(), state.beforeEpoch(), state.afterBlueId(), state.afterEpoch(),
                    operation.input().map(CoordinationCore.TimelineInput::order), operation.invocation().cause().causeIdentity());
        }

        void verify(SourceObservationProgram program) {
            if (status != ProcessorStatus.SUCCESS || !operationIdentity.equals(program.invocationIdentity()) || !program.ownedDocumentIds().contains(sourceLineage))
                throw invalid("Selected source program does not belong to its exact header");
            var before = program.sourcePredecessors().stream().filter(value -> value.documentId().equals(sourceLineage)).findFirst().orElseThrow(() -> invalid("Missing source predecessor"));
            var after = program.sourceResults().stream().filter(value -> value.documentId().equals(sourceLineage)).findFirst().orElseThrow(() -> invalid("Missing source result"));
            if (!before.blueId().equals(beforeBlueId) || before.epoch() != beforeEpoch || !after.blueId().equals(afterBlueId) || after.epoch() != afterEpoch)
                throw invalid("Selected source header disagrees with its exact program states");
            verifyPosition(program.managedReaction(), program.externalCause());
        }
        void verify(SourceOperationFailure failure) {
            if (status != failure.status() || !operationIdentity.equals(failure.invocationIdentity()) || !failure.ownedDocumentIds().contains(sourceLineage))
                throw invalid("Selected source failure does not belong to its exact header");
            var before = failure.sourcePredecessors().stream().filter(value -> value.documentId().equals(sourceLineage)).findFirst().orElseThrow(() -> invalid("Missing failed source predecessor"));
            if (!before.blueId().equals(beforeBlueId) || before.epoch() != beforeEpoch) throw invalid("Failed source header disagrees with its exact rollback view");
            verifyPosition(failure.managedReaction(), failure.externalCause());
        }
        private void verifyPosition(Optional<ManagedReactionContext> reaction, ExternalEventCause external) {
            if (kind == CoordinationCore.OperationKind.INITIALIZATION) return;
            if (kind == CoordinationCore.OperationKind.MANAGED_RECEIPT_IMPORT) {
                ManagedReactionContext selected = reaction.orElseThrow(() -> invalid("Managed source header lacks its exact producing context"));
                if (!productionOrder.equals(Optional.of(selected.activationCut())) || !reactionPositionIdentity.equals(selected.reactionOriginIdentity()))
                    throw invalid("Managed source header substituted old provenance for its producing position");
            } else if (reaction.isPresent() || external == null || !productionOrder.equals(Optional.of(external.sourceOrder()))
                    || !reactionPositionIdentity.equals(external.causeIdentity()))
                throw invalid("External source header disagrees with its producing position");
        }
    }

    /** Immutable history-selection basis; completion proof can arrive later without changing this identity. */
    public static final class Descriptor {
        private final DocumentId consumerLineage, sourceLineage;
        private final String occurrenceIdentity, creatingOperationIdentity, creatorExecutionSeedIdentity, creationSiteIdentity;
        private final ObserverAttachmentPlan.Selection selection;
        private final String canonicalSourceBasis, installedSourceOperationIdentity, installedBlueId, identity;
        private final long installedEpoch;

        public Descriptor(DocumentId consumerLineage, String occurrenceIdentity, DocumentId sourceLineage,
                String creatingOperationIdentity, String creatorExecutionSeedIdentity, String creationSiteIdentity,
                ObserverAttachmentPlan.Selection selection, String canonicalSourceBasis, String installedSourceOperationIdentity,
                String installedBlueId, long installedEpoch) {
            this.consumerLineage = Objects.requireNonNull(consumerLineage); this.occurrenceIdentity = sha(occurrenceIdentity);
            this.sourceLineage = Objects.requireNonNull(sourceLineage); this.creatingOperationIdentity = sha(creatingOperationIdentity);
            this.creatorExecutionSeedIdentity = sha(creatorExecutionSeedIdentity); this.creationSiteIdentity = sha(creationSiteIdentity);
            this.selection = Objects.requireNonNull(selection); this.canonicalSourceBasis = text(canonicalSourceBasis);
            this.installedSourceOperationIdentity = sha(installedSourceOperationIdentity); this.installedBlueId = text(installedBlueId);
            this.installedEpoch = epoch(installedEpoch);
            identity = digest("blue-managed-import-lane-poc/1", List.of(consumerLineage.value(), occurrenceIdentity, sourceLineage.value(),
                    creatingOperationIdentity, creatorExecutionSeedIdentity, creationSiteIdentity, selection.mode().name(), selection.activationCut().components(),
                    selection.frontier().map(ExternalOrderKey::components).orElse(List.of()), canonicalSourceBasis, installedSourceOperationIdentity, installedBlueId, installedEpoch));
        }

        public static Descriptor fromPlan(ManagedOccurrenceBinding occurrence, String creatingOperationIdentity,
                String creatorExecutionSeedIdentity, String creationSiteIdentity, ObserverAttachmentPlan.Ready plan, Header installed) {
            if (!occurrence.targetDocumentId().equals(installed.sourceLineage()) || !plan.canonicalSourceBasis().equals(installed.canonicalSourceBasis())
                    || !plan.installedView().source().equals(installed.sourceLineage()) || !plan.installedView().blueId().equals(installed.afterBlueId())
                    || plan.installedView().epoch() != installed.afterEpoch()
                    || !occurrence.expectedTargetBlueId().equals(installed.afterBlueId())) throw invalid("Attachment plan does not establish the occurrence's exact installed source view");
            if (plan.selection().mode() == ObserverAttachmentPlan.Mode.FULL_HISTORY && (installed.kind() != CoordinationCore.OperationKind.INITIALIZATION
                    || installed.status() != ProcessorStatus.SUCCESS))
                throw invalid("FULL_HISTORY starts at canonical init0, not the physical source head");
            return new Descriptor(occurrence.sourceDocumentId(), occurrence.occurrenceIdentity(), occurrence.targetDocumentId(),
                    creatingOperationIdentity, creatorExecutionSeedIdentity, creationSiteIdentity, plan.selection(), plan.canonicalSourceBasis(),
                    installed.operationIdentity(), installed.afterBlueId(), installed.afterEpoch());
        }

        /** Mints a lane only from an actual successful creator's retained synchronous init0 installation. */
        public static Optional<Descriptor> fromAcceptedInitialization(CoordinationCore.PreparedOperation creator,
                AcceptedInitializationInstallation installation, SourceInitialization initialization,
                CanonicalSourceHistory.Cursor sourcePrefix) {
            Objects.requireNonNull(creator, "creator");
            if (!creator.result().commits() || creator.disposition() != CoordinationCore.Disposition.CONSUMED)
                throw invalid("An unsuccessful creator cannot activate an import lane");
            return fromAcceptedInitialization(creator.operationId(), creator.invocation(), creator.ownedLineages(),
                    creator.ownedOccurrenceBindings(), creator.sourceProgram().orElseThrow(() -> invalid("Creator lacks its retained installation program")),
                    installation, initialization, sourcePrefix);
        }

        /** Independently atomic same-origin group variant; final ownership is not inferred from a global closure result. */
        public static Optional<Descriptor> fromAcceptedInitialization(SameOriginOperationResult creator,
                AcceptedInitializationInstallation installation, SourceInitialization initialization,
                CanonicalSourceHistory.Cursor sourcePrefix) {
            Objects.requireNonNull(creator, "creator"); Objects.requireNonNull(installation, "installation");
            if (creator.status() != ProcessorStatus.SUCCESS || !installation.creatorSeedIdentity().equals(
                    creator.originalSeedByMember().get(installation.selection().creatorLineage())))
                throw invalid("Installation does not belong to the successful creator's original execution seed");
            return fromAcceptedInitialization(creator.operationIdentity(), creator.origin(), creator.ownedDocumentIds(),
                    creator.occurrenceBindings(), creator.sourceProgram().orElseThrow(() -> invalid("Creator lacks its retained installation program")),
                    installation, initialization, sourcePrefix);
        }

        private static Optional<Descriptor> fromAcceptedInitialization(String operation, ClosureInvocationInput creator,
                Set<DocumentId> owners, List<ManagedOccurrenceBinding> rows, SourceObservationProgram program,
                AcceptedInitializationInstallation installation, SourceInitialization initialization,
                CanonicalSourceHistory.Cursor prefix) {
            Objects.requireNonNull(installation, "installation"); Objects.requireNonNull(prefix, "sourcePrefix");
            var selection = installation.selection();
            if (!owners.contains(selection.creatorLineage()) || !program.invocationIdentity().equals(operation)
                    || program.acceptedInitializations().stream().noneMatch(retained -> retained.identity().equals(installation.identity())))
                throw invalid("Installation is not retained by this exact successful owning creator");
            installation.verifySourceInitialization(initialization);
            var initial = prefix.initialView().orElseThrow(() -> invalid("Source prefix has no canonical initialized origin"));
            if (!prefix.source().equals(selection.targetLineage()) || !initial.blueId().equals(installation.selectedView().blueId()) || initial.epoch() != 0)
                throw invalid("Canonical source prefix does not establish the installed init0 view");
            if (!(creator.cause() instanceof ExternalEventCause))
                throw invalid("Historical creator lanes require an actual external producing position");
            ExternalOrderKey cut = creator.managedReaction().map(ManagedReactionContext::activationCut)
                    .orElseGet(() -> ((ExternalEventCause) creator.cause()).sourceOrder());
            ManagedOccurrenceBinding occurrence = rows.stream().filter(row -> row.occurrenceIdentity().equals(selection.occurrenceIdentity())).findFirst().orElse(null);
            // The creating workflow may retire this exact placement later in its same operation.
            if (occurrence == null || !occurrence.active() && occurrence.pendingHistoricalEpoch() == null) return Optional.empty();
            if (!occurrence.sourceDocumentId().equals(selection.creatorLineage()) || !occurrence.targetDocumentId().equals(selection.targetLineage())
                    || occurrence.active() || !Long.valueOf(0).equals(occurrence.pendingHistoricalEpoch())
                    || !occurrence.expectedTargetBlueId().equals(installation.selectedView().blueId()))
                throw invalid("FULL_HISTORY creation must retain its exact inactive init0 placement before historical consumption");
            return Optional.of(new Descriptor(selection.creatorLineage(), selection.occurrenceIdentity(), selection.targetLineage(), operation,
                    installation.creatorSeedIdentity(), installation.creatorPatchSite(), ObserverAttachmentPlan.Selection.fullHistory(cut),
                    prefix.basisIdentity(), installation.sourceInitializationOperationIdentity(), installation.selectedView().blueId(), 0L));
        }

        /** Actual successful creator-site frontier placement; no host-authored operation or installation claims. */
        public static Optional<Descriptor> fromAcceptedFrontier(CoordinationCore.PreparedGroupOperation creator,
                AcceptedAttachmentView installation, SourceFrontierSelection frontier) {
            Objects.requireNonNull(creator); Objects.requireNonNull(installation); Objects.requireNonNull(frontier);
            var group = creator.result(); var selection = installation.selection();
            if (group.status() != ProcessorStatus.SUCCESS || creator.disposition() != CoordinationCore.Disposition.CONSUMED
                    || !group.ownedDocumentIds().contains(selection.creatorLineage())
                    || !installation.creatorSeedIdentity().equals(group.originalSeedByMember().get(selection.creatorLineage())))
                throw invalid("Frontier placement does not belong to this successful creator's original execution seed");
            var program = group.sourceProgram().orElseThrow(() -> invalid("Creator lacks its retained placement program"));
            if (!program.invocationIdentity().equals(creator.operationId())
                    || program.acceptedViews().stream().noneMatch(value -> value.identity().equals(installation.identity())))
                throw invalid("Frontier placement is not retained by this exact owning creator");
            var selected = installation.frontierView().orElseThrow(() -> invalid("Placement is not FROM_FRONTIER"));
            frontier.verifySelection(selection);
            if (!frontier.selectedView().identity().equals(selected.identity()))
                throw invalid("Installed frontier differs from its complete canonical boundary");
            selected.verifyInvocation(creator.invocation());
            ExternalOrderKey cut = creator.invocation().managedReaction().map(ManagedReactionContext::activationCut)
                    .orElseGet(() -> ((ExternalEventCause) creator.invocation().cause()).sourceOrder());
            ManagedOccurrenceBinding occurrence = group.occurrenceBindings().stream()
                    .filter(row -> row.occurrenceIdentity().equals(selection.occurrenceIdentity())).findFirst().orElse(null);
            if (occurrence == null || !occurrence.active() && occurrence.pendingHistoricalEpoch() == null) return Optional.empty();
            if (!occurrence.sourceDocumentId().equals(selection.creatorLineage()) || !occurrence.targetDocumentId().equals(selection.targetLineage())
                    || !occurrence.expectedTargetBlueId().equals(selected.selectedView().blueId())
                    || !selection.frontier().orElseThrow().equals(cut) && (occurrence.active()
                    || !Long.valueOf(selected.successfulEpoch()).equals(occurrence.pendingHistoricalEpoch())))
                throw invalid("Frontier placement did not retain its exact selected historical source view");
            return Optional.of(new Descriptor(selection.creatorLineage(), selection.occurrenceIdentity(), selection.targetLineage(), creator.operationId(),
                    installation.creatorSeedIdentity(), installation.creatorPatchSite(), ObserverAttachmentPlan.Selection.fromFrontier(cut, selection.frontier().orElseThrow()),
                    frontier.boundary().cursor().basisIdentity(), selected.terminalOperationIdentity(), selected.selectedView().blueId(), selected.successfulEpoch()));
        }
        public DocumentId consumerLineage() { return consumerLineage; }
        public String occurrenceIdentity() { return occurrenceIdentity; }
        public DocumentId sourceLineage() { return sourceLineage; }
        public String creatingOperationIdentity() { return creatingOperationIdentity; }
        public String creatorExecutionSeedIdentity() { return creatorExecutionSeedIdentity; }
        public String creationSiteIdentity() { return creationSiteIdentity; }
        public ObserverAttachmentPlan.Selection selection() { return selection; }
        public String canonicalSourceBasis() { return canonicalSourceBasis; }
        /** Terminal source-chain operation at installation; a failed terminal retains the earlier successful exact body/epoch. */
        public String installedSourceOperationIdentity() { return installedSourceOperationIdentity; }
        public String installedBlueId() { return installedBlueId; }
        public long installedEpoch() { return installedEpoch; }
        public String identity() { return identity; }
    }

    /** Authenticated complete source-prefix endpoint at this lane's fixed logical cut. */
    public record PrefixAuthority(String laneIdentity, String terminalSourceOperationIdentity) {
        public PrefixAuthority { sha(laneIdentity); sha(terminalSourceOperationIdentity); }
        public static PrefixAuthority fromBoundary(Descriptor lane, CanonicalSourceHistory.Boundary boundary) {
            if (!lane.sourceLineage().equals(boundary.cursor().source()) || !lane.canonicalSourceBasis().equals(boundary.cursor().basisIdentity())
                    || !lane.selection().activationCut().equals(boundary.cut())) throw invalid("Source-prefix authority does not match the attachment's fixed cut");
            return new PrefixAuthority(lane.identity(), boundary.cursor().semanticPredecessor().orElseThrow(() -> invalid("Source prefix has no initialized operation")));
        }
    }

    /** A persisted cursor is accepted only through its authenticated prior consumer result. */
    public record Cursor(Descriptor descriptor, String positionIdentity, String lastTerminalSourceOperationIdentity,
                         String successfulBlueId, long successfulEpoch, boolean hadFailures, boolean complete) {
        public Cursor { Objects.requireNonNull(descriptor); sha(positionIdentity); sha(lastTerminalSourceOperationIdentity); text(successfulBlueId); epoch(successfulEpoch); }
        public static Cursor start(Descriptor lane) {
            return new Cursor(lane, digest("blue-managed-import-lane-start-poc/1", List.of(lane.identity())),
                    lane.installedSourceOperationIdentity(), lane.installedBlueId(), lane.installedEpoch(), false,
                    lane.selection().mode() == ObserverAttachmentPlan.Mode.FROM_NOW
                            || lane.selection().frontier().map(lane.selection().activationCut()::equals).orElse(false));
        }
        public Cursor certifyComplete(PrefixAuthority authority) {
            if (!descriptor.identity().equals(authority.laneIdentity()) || !lastTerminalSourceOperationIdentity.equals(authority.terminalSourceOperationIdentity()))
                throw invalid("Unattempted fixed-cut source operations remain");
            return new Cursor(descriptor, positionIdentity, lastTerminalSourceOperationIdentity, successfulBlueId, successfulEpoch, hadFailures, true);
        }
    }

    public enum Outcome { APPLIED, RETIRED, GAS_LIMIT_EXCEEDED, RUNTIME_FATAL, SOURCE_FAILURE }
    public record Delta(String laneIdentity, String expectedPositionIdentity, Cursor nextCursor, Outcome outcome) {
        public Delta {
            sha(laneIdentity); sha(expectedPositionIdentity); Objects.requireNonNull(nextCursor); Objects.requireNonNull(outcome);
            if (!laneIdentity.equals(nextCursor.descriptor().identity())) throw invalid("Lane delta changes its immutable selection basis");
        }
        /** Rebinds a restored delta to its authenticated owning operation (or metadata reaction identity). */
        public void verifyConsumerOperation(String operationIdentity) {
            sha(operationIdentity);
            String expected = terminalPosition(laneIdentity, expectedPositionIdentity,
                    nextCursor.lastTerminalSourceOperationIdentity(), operationIdentity, outcome,
                    nextCursor.successfulBlueId(), nextCursor.successfulEpoch());
            if (!expected.equals(nextCursor.positionIdentity())) throw invalid("Lane delta belongs to another consumer operation");
            if (outcome == Outcome.RETIRED && !nextCursor.complete()) throw invalid("A retired occurrence cannot retain a runnable lane");
        }
    }

    /** Exactly the next source-chain operation; prefix endpoint proof is optional until available. */
    public record Due(Cursor cursor, Header source, Optional<PrefixAuthority> completePrefix) {
        public Due {
            Objects.requireNonNull(cursor); Objects.requireNonNull(source); completePrefix = Objects.requireNonNull(completePrefix);
            Descriptor lane = cursor.descriptor();
            if (cursor.complete() || !lane.sourceLineage().equals(source.sourceLineage()) || !lane.canonicalSourceBasis().equals(source.canonicalSourceBasis())
                    || source.kind() == CoordinationCore.OperationKind.INITIALIZATION
                    || !source.predecessorOperationIdentity().equals(Optional.of(cursor.lastTerminalSourceOperationIdentity())))
                throw invalid("Import must consume the next exact source operation in this occurrence lane");
            if (source.productionOrder().orElseThrow().compareTo(lane.selection().activationCut()) > 0)
                throw invalid("Source operation was produced after the fixed attachment cut, regardless of old event provenance");
            if (lane.selection().frontier().isPresent() && source.productionOrder().orElseThrow().compareTo(lane.selection().frontier().orElseThrow()) <= 0)
                throw invalid("Source operation is outside the selected frontier suffix");
            if (completePrefix.isPresent() && !lane.identity().equals(completePrefix.orElseThrow().laneIdentity())) throw invalid("Foreign source-prefix completion authority");
            if (completePrefix.isPresent() && cursor.lastTerminalSourceOperationIdentity().equals(completePrefix.orElseThrow().terminalSourceOperationIdentity()))
                throw invalid("The fixed source prefix is already consumed; completion is metadata, not another imported operation");
        }
        Delta finish(String consumerOperationIdentity, Outcome outcome, String resultingPin, long resultingEpoch) {
            sha(consumerOperationIdentity); Descriptor lane = cursor.descriptor();
            boolean failure = outcome != Outcome.APPLIED && outcome != Outcome.RETIRED;
            String blueId = outcome != Outcome.APPLIED ? cursor.successfulBlueId() : text(resultingPin);
            long pinEpoch = outcome != Outcome.APPLIED ? cursor.successfulEpoch() : epoch(resultingEpoch);
            String position = terminalPosition(lane.identity(), cursor.positionIdentity(), source.operationIdentity(),
                    consumerOperationIdentity, outcome, blueId, pinEpoch);
            boolean complete = outcome == Outcome.RETIRED || completePrefix.isPresent()
                    && completePrefix.orElseThrow().terminalSourceOperationIdentity().equals(source.operationIdentity());
            Cursor after = new Cursor(lane, position, source.operationIdentity(), blueId, pinEpoch, cursor.hadFailures() || failure, complete);
            return new Delta(lane.identity(), cursor.positionIdentity(), after, outcome);
        }
    }

    private static String terminalPosition(String lane, String previous, String sourceOperation, String consumerOperation,
            Outcome outcome, String blueId, long epoch) {
        return digest("blue-managed-import-lane-terminal-poc/1", List.of(lane, previous, sourceOperation,
                consumerOperation, outcome.name(), blueId, epoch));
    }

    static String digest(String domain, Object value) {
        try { return "sha256:" + FrozenNodeEvidenceCodec.digest(JSON.writeValueAsBytes(Map.of("domain", domain, "value", value))); }
        catch (java.io.IOException exception) { throw new IllegalStateException("Cannot encode exact managed lane identity", exception); }
    }
    static String sha(String value) { if (value == null || !value.matches("sha256:[0-9a-f]{64}")) throw invalid("Expected canonical semantic identity"); return value; }
    private static String text(String value) { if (value == null || value.isEmpty()) throw invalid("Expected nonempty exact text"); return value; }
    private static long epoch(long value) { if (value < 0 || value > 9_007_199_254_740_991L) throw invalid("Invalid source epoch"); return value; }
    static IllegalArgumentException invalid(String message) { return new IllegalArgumentException(message); }
}
