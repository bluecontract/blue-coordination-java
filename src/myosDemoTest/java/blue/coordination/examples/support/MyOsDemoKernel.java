package blue.coordination.examples.support;

import blue.coordination.processor.CoordinationTestRuntime;
import blue.language.model.Node;
import blue.repo.BlueRepository;

import java.util.Map;
import java.util.Objects;

/**
 * One immutable Language/Contracts/BEX/Repository kernel per example-test JVM.
 *
 * <p>Business tests receive isolated fragment and session stores, but do not
 * rebuild the expensive type registry, mapper, provider chain, BEX runtime,
 * and processor generation for every test method. The dedicated Gradle task
 * runs with one fork and without JUnit parallelism, so this immutable kernel
 * is shared safely and deterministically.</p>
 */
final class MyOsDemoKernel {

    private static final MyOsExactNodeProvider EXACT_NODES =
            new MyOsExactNodeProvider();
    private static final CoordinationTestRuntime RUNTIME = createRuntime();

    private MyOsDemoKernel() {
    }

    static CoordinationTestRuntime runtime() {
        return RUNTIME;
    }

    static void registerExactDocument(
            String claimedBlueId,
            Node exactDocument) {
        String checkedBlueId = Objects.requireNonNull(
                claimedBlueId, "claimedBlueId");
        Node checkedDocument = Objects.requireNonNull(
                exactDocument, "exactDocument").clone();
        String calculatedBlueId = RUNTIME.calculateBlueId(checkedDocument);
        if (!calculatedBlueId.equals(checkedBlueId)) {
            throw new IllegalArgumentException(
                    "Exact-node identity does not match its canonical "
                            + "content");
        }
        EXACT_NODES.register(checkedBlueId, checkedDocument);
    }

    static void replaceCurrentExactNodes(
            Object owner,
            Map<String, Map<String, Node>> scopes) {
        EXACT_NODES.replaceCurrent(owner, scopes);
    }

    static void releaseCurrentExactNodes(Object owner) {
        EXACT_NODES.release(owner);
    }

    private static CoordinationTestRuntime createRuntime() {
        CoordinationTestRuntime runtime =
                CoordinationTestRuntime.create(BlueRepository.current());
        runtime.addNodeProvider(EXACT_NODES);
        return runtime;
    }
}
