package blue.coordination.internal;

import blue.coordination.api.DocumentId;
import blue.language.processor.closure.AffectedClosureSnapshot;
import blue.language.processor.closure.AffectedClosureSnapshotStorageCodec;
import blue.language.processor.closure.ClosureExecutionEvidenceStorageCodec;
import java.util.Arrays;
import java.util.Objects;
import java.util.function.Function;
import static blue.coordination.internal.SessionRecordCodec.*;
import static blue.coordination.internal.SessionStorageWire.*;

/** Retained submitted-step verification evidence, including the pre-binding capture and selected peer fences. */
final class RootedLocalStepStorageCodec {
    private static final String FORMAT = "blue-coordination/rooted-local-step/1";
    private final int maximumBytes;
    private final int maximumDepth;
    private final SessionRecordCodec rows;
    private final CohortInvocationStorageCodec cohorts;
    private final ClosureExecutionEvidenceStorageCodec execution;
    private final AffectedClosureSnapshotStorageCodec snapshots;

    RootedLocalStepStorageCodec(int maximumBytes, int maximumDepth) {
        this(maximumBytes, maximumDepth, null);
    }

    RootedLocalStepStorageCodec(int maximumBytes, int maximumDepth, RootedStorageCache cache) {
        this.maximumBytes = maximumBytes;
        this.maximumDepth = maximumDepth;
        rows = new SessionRecordCodec(maximumBytes, maximumDepth);
        cohorts = new CohortInvocationStorageCodec(maximumBytes, maximumDepth, cache);
        execution = new ClosureExecutionEvidenceStorageCodec(maximumBytes, maximumDepth,
                new StoredClosureResultCodec(maximumBytes, maximumDepth, cache).configured());
        snapshots = new AffectedClosureSnapshotStorageCodec(maximumBytes, maximumDepth);
    }

    byte[] encode(RootedLocalHistory.Step value, Function<RootedDocumentView, String> views) {
        return SessionStorageWire.encode(maximumBytes, out -> {
            out.text(FORMAT);
            step(out, Objects.requireNonNull(value), views, 0);
        });
    }

    RootedLocalHistory.Step decode(byte[] bytes, DocumentSessionStorage.OpenScope scope) {
        return physical(() -> {
            var value = SessionStorageWire.decode(bytes, maximumBytes, in -> {
                require(FORMAT.equals(text(in)), "Wrong selected local-step format");
                return step(in, scope, 0);
            });
            require(Arrays.equals(bytes, encode(value, scope::addressOf)), "Noncanonical selected local-step proof");
            return value;
        });
    }

    private void step(Writer out, RootedLocalHistory.Step value,
            Function<RootedDocumentView, String> views, int depth) {
        require(depth <= maximumDepth, "Selected local-step proof exceeds physical depth");
        require(value.originalInputForStorage().snapshot() == value.capturedState().snapshot(),
                "Selected local step lost its original pre-binding snapshot");
        out.text(value.root().value());
        StoreIndexCodecs.occurrence(out, value.target());
        order(out, value.sourceOrder());
        rows.entry(out, value.anchor());
        out.bytes(cohorts.encode(value.invocation(), views));
        out.bytes(execution.encodeInvocation(value.originalInputForStorage()));
        capture(out, value.capturedState(), views, depth + 1);
    }

    private RootedLocalHistory.Step step(Reader in, DocumentSessionStorage.OpenScope scope, int depth) {
        require(depth <= maximumDepth, "Selected local-step proof exceeds physical depth");
        var root = DocumentId.of(text(in));
        var target = StoreIndexCodecs.occurrence(in);
        var sourceOrder = order(in);
        var anchor = rows.entry(in);
        var bound = cohorts.decode(in.bytes(maximumBytes), scope);
        var original = execution.decodeInvocation(in.bytes(maximumBytes));
        var captured = capture(in, scope, original.snapshot(), depth + 1);
        var restored = RootedLocalHistory.Step.restoreStored(root, target, sourceOrder, anchor, bound, original, captured);
        require(Arrays.equals(cohorts.encode(bound, scope::addressOf),
                cohorts.encode(restored.invocation(), scope::addressOf)),
                "Stored local step changed the Language-bound original execution input");
        return restored;
    }

    private void capture(Writer out, ContractsClosureAdapter.RootedCapturedState value,
            Function<RootedDocumentView, String> views, int depth) {
        require(depth <= maximumDepth, "Selected capture proof exceeds physical depth");
        out.bytes(snapshots.encode(value.snapshot()));
        documents(out, value.documents(), cohorts::captured);
        out.text(views.apply(value.view()));
        out.text(value.anchor().value());
        optional(out, value.originalCaptureForStorage(), (w, original) -> capture(w, original, views, depth + 1));
        documents(out, value.peerPrefixesForStorage(), (w, peer) -> step(w, peer, views, depth + 1));
    }

    private ContractsClosureAdapter.RootedCapturedState capture(Reader in, DocumentSessionStorage.OpenScope scope,
            AffectedClosureSnapshot originalSnapshot, int depth) {
        require(depth <= maximumDepth, "Selected capture proof exceeds physical depth");
        byte[] bytes = in.bytes(maximumBytes);
        var snapshot = snapshots.decode(bytes);
        if (originalSnapshot != null) {
            require(Arrays.equals(bytes, snapshots.encode(originalSnapshot)), "Capture differs from its original pre-binding input");
            snapshot = originalSnapshot;
        }
        var documents = documents(in, cohorts::captured);
        var view = scope.view(text(in));
        var anchor = DocumentId.of(text(in));
        var original = optional(in, r -> capture(r, scope, null, depth + 1));
        var peers = documents(in, r -> step(r, scope, depth + 1));
        return ContractsClosureAdapter.RootedCapturedState.restoreStored(snapshot, documents,
                view, anchor, original, peers);
    }
}
