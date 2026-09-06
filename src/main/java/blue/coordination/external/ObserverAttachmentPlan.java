package blue.coordination.external;

import blue.language.processor.ExternalOrderKey;
import blue.language.processor.InvalidExecutionEvidenceException;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Observer-owned selection over one canonical source history. A plan selects an
 * installed exact view and a separate historical lane; it never runs old source
 * epochs inside the creator's epoch and never declares a prefix authoritative.
 * The activation occurrence/causal placement remains part of the owning creation
 * operation and must be bound when its later lane is admitted.
 */
public final class ObserverAttachmentPlan {
    private ObserverAttachmentPlan() { }
    public enum Mode { FULL_HISTORY, FROM_NOW, FROM_FRONTIER }

    public record Selection(Mode mode, ExternalOrderKey activationCut, Optional<ExternalOrderKey> frontier) {
        public Selection {
            Objects.requireNonNull(mode); CanonicalSourceHistory.requireOrder(activationCut);
            frontier = Objects.requireNonNull(frontier);
            if ((mode == Mode.FROM_FRONTIER) != frontier.isPresent()) throw invalid("Only FROM_FRONTIER names a frontier");
            frontier.ifPresent(value -> {
                CanonicalSourceHistory.requireOrder(value);
                if (value.compareTo(activationCut) > 0) throw invalid("Observer frontier is after activation");
            });
        }
        public static Selection fullHistory(ExternalOrderKey cut) { return new Selection(Mode.FULL_HISTORY, cut, Optional.empty()); }
        public static Selection fromNow(ExternalOrderKey cut) { return new Selection(Mode.FROM_NOW, cut, Optional.empty()); }
        public static Selection fromFrontier(ExternalOrderKey cut, ExternalOrderKey frontier) {
            return new Selection(Mode.FROM_FRONTIER, cut, Optional.of(frontier));
        }
    }

    public sealed interface Result permits Ready, Await { }
    public record Await(List<String> keys) implements Result { public Await { keys = List.copyOf(keys); } }

    /** Empty lower bound means after canonical initialization, not after physical materialization. */
    public record HistoricalLane(Optional<ExternalOrderKey> exclusiveLower, ExternalOrderKey inclusiveUpper) {
        public HistoricalLane { exclusiveLower = Objects.requireNonNull(exclusiveLower); CanonicalSourceHistory.requireOrder(inclusiveUpper); }
        /** Range check only; exact membership additionally requires the authenticated source predecessor chain. */
        public boolean includes(ManagedImportLane.Header source) {
            return source.productionOrder().isPresent() && includes(source.productionOrder().orElseThrow());
        }
        /** The argument is a producing operation position, never an old event's retained provenance. */
        public boolean includes(ExternalOrderKey productionPosition) {
            CanonicalSourceHistory.requireOrder(productionPosition);
            return (exclusiveLower.isEmpty() || productionPosition.compareTo(exclusiveLower.orElseThrow()) > 0)
                    && productionPosition.compareTo(inclusiveUpper) <= 0;
        }
    }

    public record Ready(String canonicalSourceBasis, Selection selection, CanonicalSourceHistory.View installedView,
                        Optional<HistoricalLane> historicalLane, ExternalOrderKey liveAfter) implements Result { }

    /**
     * FULL_HISTORY needs only canonical initialization to allow the creator's
     * synchronous initial read. Its separate history lane may wait for later
     * source completeness. FROM_NOW/frontier require the exact selected boundary
     * view; a physically newer source head is not a substitute.
     */
    public static Result select(Selection selection, CanonicalSourceHistory.Cursor canonicalPrefix,
                                Optional<CanonicalSourceHistory.Boundary> selectedBoundary) {
        Objects.requireNonNull(selection); Objects.requireNonNull(canonicalPrefix); Objects.requireNonNull(selectedBoundary);
        if (canonicalPrefix.initialView().isEmpty()) return new Await(List.of("canonical-initialization:" + canonicalPrefix.source().value()));
        if (selection.mode() == Mode.FULL_HISTORY) {
            if (selectedBoundary.isPresent()) verifyBasis(canonicalPrefix, selectedBoundary.orElseThrow());
            return new Ready(canonicalPrefix.basisIdentity(), selection, canonicalPrefix.initialView().orElseThrow(),
                    Optional.of(new HistoricalLane(Optional.empty(), selection.activationCut())), selection.activationCut());
        }
        ExternalOrderKey requestedView = selection.mode() == Mode.FROM_NOW ? selection.activationCut() : selection.frontier().orElseThrow();
        if (selectedBoundary.isEmpty()) return new Await(List.of("canonical-source-view:" + canonicalPrefix.source().value() + ":" + requestedView));
        CanonicalSourceHistory.Boundary boundary = selectedBoundary.orElseThrow();
        verifyBasis(canonicalPrefix, boundary);
        if (!boundary.cut().equals(requestedView)) throw invalid("Source boundary does not match the authorized observer selector");
        Optional<HistoricalLane> lane = selection.mode() == Mode.FROM_NOW || requestedView.equals(selection.activationCut())
                ? Optional.empty() : Optional.of(new HistoricalLane(Optional.of(requestedView), selection.activationCut()));
        return new Ready(canonicalPrefix.basisIdentity(), selection, boundary.cursor().successfulView().orElseThrow(),
                lane, selection.activationCut());
    }

    private static void verifyBasis(CanonicalSourceHistory.Cursor prefix, CanonicalSourceHistory.Boundary boundary) {
        if (!prefix.source().equals(boundary.cursor().source()) || !prefix.basisIdentity().equals(boundary.cursor().basisIdentity())
                || !prefix.initialView().equals(boundary.cursor().initialView()))
            throw invalid("Observer boundary belongs to another canonical source history");
    }
    private static InvalidExecutionEvidenceException invalid(String message) { return new InvalidExecutionEvidenceException(message); }
}
