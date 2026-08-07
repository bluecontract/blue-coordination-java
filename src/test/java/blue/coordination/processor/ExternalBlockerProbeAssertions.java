package blue.coordination.processor;

import blue.language.model.Node;
import blue.language.model.Schema;
import blue.language.processor.DocumentProcessingResult;
import blue.language.processor.ExecutionEvidenceUnavailableException;
import blue.language.processor.ProcessingDebugResult;
import blue.language.processor.ProcessingTraceConstants;
import blue.language.processor.ProcessingTraceRecord;
import blue.language.processor.ProcessorErrorCategory;
import blue.language.processor.ProcessorStatus;
import blue.language.identity.DirectBlueIdCalculator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * Fail-closed assertions for temporarily catalogued lower-layer probes.
 *
 * <p>A probe is allowed to do exactly one of two things: pass its repaired
 * business assertion, or reproduce the complete lower-layer defect tuple.
 * Any third outcome is evidence that the probe no longer diagnoses the
 * catalogued blocker and must not be accepted under that blocker's
 * fingerprint.</p>
 */
public final class ExternalBlockerProbeAssertions {
    private ExternalBlockerProbeAssertions() {
    }

    public static void classify(
            String family,
            String fingerprintPrefix,
            boolean exactDefect,
            boolean repairedPath,
            String observedTuple) {
        Objects.requireNonNull(family, "family");
        Objects.requireNonNull(
                fingerprintPrefix, "fingerprintPrefix");
        String tuple = String.valueOf(observedTuple);
        if (exactDefect == repairedPath) {
            fail("Invalid external blocker probe ["
                    + family + "]: exactDefect="
                    + exactDefect + ", repairedPath="
                    + repairedPath + ", " + tuple);
        }
        if (exactDefect) {
            fail(fingerprintPrefix + " " + tuple);
        }
    }

    public static void knownDefect(
            String fingerprintPrefix,
            String observedTuple) {
        fail(Objects.requireNonNull(
                fingerprintPrefix, "fingerprintPrefix")
                + " " + String.valueOf(observedTuple));
    }

    public static void invalidProbe(
            String family,
            String observedTuple) {
        fail("Invalid external blocker probe ["
                + Objects.requireNonNull(family, "family")
                + "]: " + String.valueOf(observedTuple));
    }

    public static boolean exactDiagnostic(
            DocumentProcessingResult result,
            ProcessorStatus status,
            ProcessorErrorCategory category,
            String message) {
        return result != null
                && result.status() == status
                && result.diagnostic() != null
                && result.diagnostic().category() == category
                && Objects.equals(
                        message,
                        result.diagnostic().message());
    }

    public static String resultTuple(
            DocumentProcessingResult result) {
        if (result == null) {
            return "result=null";
        }
        return "status=" + result.status()
                + ", category="
                + (result.diagnostic() != null
                ? result.diagnostic().category()
                : null)
                + ", diagnostic="
                + (result.diagnostic() != null
                ? result.diagnostic().message()
                : null)
                + ", events=" + result.events().size()
                + ", gas=" + result.totalGas();
    }

    public static void classifyImplicitInitializationFailure(
            RuntimeException failure,
            List<String> expectedExactBlueIds,
            String context) {
        Objects.requireNonNull(
                expectedExactBlueIds,
                "expectedExactBlueIds");
        List<String> expected =
                Collections.unmodifiableList(
                        new ArrayList<String>(
                                expectedExactBlueIds));
        boolean canonicalExpectedIds =
                !expected.isEmpty()
                        && expected.equals(
                        new ArrayList<String>(
                                new TreeSet<String>(
                                        expected)));
        boolean exactDefect =
                failure
                        instanceof
                        ExecutionEvidenceUnavailableException
                        && "Complete retained external subscription "
                        .concat(
                                "and activation evidence is unavailable")
                        .equals(failure.getMessage());
        List<String> actual =
                Collections.emptyList();
        if (failure
                instanceof
                ExecutionEvidenceUnavailableException) {
            ExecutionEvidenceUnavailableException unavailable =
                    (ExecutionEvidenceUnavailableException)
                            failure;
            actual = unavailable.requiredExactBlueIds();
            exactDefect &= canonicalExpectedIds
                    && actual.equals(expected);
        }
        String tuple =
                context + ": exception="
                        + failure.getClass().getName()
                        + ", diagnostic="
                        + failure.getMessage()
                        + ", expectedExactBlueIds="
                        + expected
                        + ", canonicalExpectedIds="
                        + canonicalExpectedIds
                        + ", requiredExactBlueIds="
                        + actual;
        if (exactDefect) {
            knownDefect(
                    "Language implicit-initialization "
                            + "evidence revalidation defect:",
                    tuple);
        }
        invalidProbe(
                "implicit-initialization-evidence-revalidation",
                tuple);
    }

    public static void requireImplicitInitializationSuccess(
            ProcessingDebugResult debug,
            String sourceKey,
            String expectedEventBlueId,
            String context) {
        Objects.requireNonNull(sourceKey, "sourceKey");
        Objects.requireNonNull(
                expectedEventBlueId,
                "expectedEventBlueId");
        DocumentProcessingResult result =
                debug != null
                        ? debug.processResult()
                        : null;
        List<ProcessingTraceRecord> deliveries =
                matchingRecords(
                        debug,
                        ProcessingTraceRecord.Kind
                                .EXTERNAL_DELIVERY,
                        sourceKey);
        List<ProcessingTraceRecord> checkpointWrites =
                matchingRecords(
                        debug,
                        ProcessingTraceRecord.Kind
                                .CHECKPOINT_WRITE,
                        sourceKey);
        ProcessingTraceRecord delivery =
                deliveries.size() == 1
                        ? deliveries.get(0)
                        : null;
        ProcessingTraceRecord checkpointWrite =
                checkpointWrites.size() == 1
                        ? checkpointWrites.get(0)
                        : null;
        String deliveryDomain =
                delivery != null
                        ? delivery.detail(
                        ProcessingTraceConstants
                                .FIELD_CHECKPOINT_DOMAIN_BLUE_ID)
                        : null;
        String checkpointDomain =
                checkpointWrite != null
                        ? checkpointWrite.detail(
                        ProcessingTraceConstants
                                .FIELD_DOMAIN)
                        : null;
        Node persistedDomain =
                nodeAt(
                        result != null
                                ? result.document()
                                : null,
                        "/contracts/checkpoint/entries/"
                                + sourceKey + "/domain");
        Node persistedSubject =
                nodeAt(
                        result != null
                                ? result.document()
                                : null,
                        "/contracts/checkpoint/entries/"
                                + sourceKey + "/subject");
        String persistedSubjectBlueId =
                persistedSubject != null
                        ? DirectBlueIdCalculator.calculateBlueId(
                        persistedSubject)
                        : null;
        boolean repairedPath =
                result != null
                        && result.status()
                        == ProcessorStatus.SUCCESS
                        && deliveries.size() == 1
                        && checkpointWrites.size() == 1
                        && "/".equals(
                        delivery.scopePath())
                        && "/".equals(
                        checkpointWrite.scopePath())
                        && expectedEventBlueId.equals(
                        delivery.detail(
                                ProcessingTraceConstants
                                        .FIELD_CHECKPOINT_SUBJECT_BLUE_ID))
                        && expectedEventBlueId.equals(
                        checkpointWrite.detail(
                                ProcessingTraceConstants
                                        .FIELD_SUBJECT))
                        && expectedEventBlueId.equals(
                        persistedSubjectBlueId)
                        && deliveryDomain != null
                        && deliveryDomain.equals(
                        checkpointDomain)
                        && persistedDomain != null
                        && deliveryDomain.equals(
                        persistedDomain.getBlueId());
        classify(
                "implicit-initialization-evidence-revalidation",
                "Language implicit-initialization evidence revalidation defect:",
                false,
                repairedPath,
                context + ": " + resultTuple(result)
                        + ", sourceKey=" + sourceKey
                        + ", expectedEventBlueId="
                        + expectedEventBlueId
                        + ", deliveries="
                        + deliveries.size()
                        + ", checkpointWrites="
                        + checkpointWrites.size()
                        + ", deliverySubjectBlueId="
                        + (delivery != null
                        ? delivery.detail(
                                ProcessingTraceConstants
                                        .FIELD_CHECKPOINT_SUBJECT_BLUE_ID)
                        : null)
                        + ", checkpointSubjectBlueId="
                        + (checkpointWrite != null
                        ? checkpointWrite.detail(
                                ProcessingTraceConstants
                                        .FIELD_SUBJECT)
                        : null)
                        + ", persistedSubjectBlueId="
                        + persistedSubjectBlueId
                        + ", deliveryDomain="
                        + deliveryDomain
                        + ", checkpointDomain="
                        + checkpointDomain
                        + ", persistedDomain="
                        + (persistedDomain != null
                        ? persistedDomain.getBlueId()
                        : null));
    }

    /**
     * Projects the fixture-owned Root and Event into the exact reference
     * demand expected from Language without consulting an exception payload.
     *
     * @param roots fixture values submitted at the processing boundary
     * @return immutable, deduplicated, deterministically sorted exact BlueIds
     */
    public static List<String> expectedExactBlueIds(
            Node... roots) {
        TreeSet<String> result =
                new TreeSet<String>();
        IdentityHashMap<Node, Boolean> visited =
                new IdentityHashMap<Node, Boolean>();
        if (roots != null) {
            for (Node root : roots) {
                collectReferencedBlueIds(
                        root, result, visited);
            }
        }
        return Collections.unmodifiableList(
                new ArrayList<String>(result));
    }

    private static List<ProcessingTraceRecord> matchingRecords(
            ProcessingDebugResult debug,
            ProcessingTraceRecord.Kind kind,
            String sourceKey) {
        List<ProcessingTraceRecord> matches =
                new ArrayList<ProcessingTraceRecord>();
        if (debug == null) {
            return matches;
        }
        for (ProcessingTraceRecord record :
                debug.trace().records(kind)) {
            if (sourceKey.equals(
                    record.contractKey())) {
                matches.add(record);
            }
        }
        return matches;
    }

    private static Node nodeAt(
            Node root,
            String path) {
        try {
            return root != null
                    ? root.getAsNode(path)
                    : null;
        } catch (IllegalArgumentException absent) {
            return null;
        }
    }

    private static void collectReferencedBlueIds(
            Node node,
            TreeSet<String> result,
            IdentityHashMap<Node, Boolean> visited) {
        if (node == null
                || visited.put(
                node, Boolean.TRUE) != null) {
            return;
        }
        if (node.isReferenceOnly()) {
            if (node.getBlueId() != null
                    && !node.getBlueId().isEmpty()) {
                result.add(node.getBlueId());
            }
            return;
        }
        collectReferencedBlueIds(
                node.getType(), result, visited);
        collectReferencedBlueIds(
                node.getSchema(), result, visited);
        collectReferencedBlueIds(
                node.getContracts(), result, visited);
        if (node.getProperties() != null) {
            for (Node child :
                    node.getProperties().values()) {
                collectReferencedBlueIds(
                        child, result, visited);
            }
        }
        if (node.getItems() != null) {
            for (Node child : node.getItems()) {
                collectReferencedBlueIds(
                        child, result, visited);
            }
        }
    }

    private static void collectReferencedBlueIds(
            Schema schema,
            TreeSet<String> result,
            IdentityHashMap<Node, Boolean> visited) {
        if (schema == null) {
            return;
        }
        if (schema.isReferenceOnly()) {
            if (schema.getBlueId() != null
                    && !schema.getBlueId().isEmpty()) {
                result.add(schema.getBlueId());
            }
            return;
        }
        collectReferencedBlueIds(
                schema.getRequired(), result, visited);
        collectReferencedBlueIds(
                schema.getMinLength(), result, visited);
        collectReferencedBlueIds(
                schema.getMaxLength(), result, visited);
        collectReferencedBlueIds(
                schema.getMinimum(), result, visited);
        collectReferencedBlueIds(
                schema.getMaximum(), result, visited);
        collectReferencedBlueIds(
                schema.getExclusiveMinimum(),
                result,
                visited);
        collectReferencedBlueIds(
                schema.getExclusiveMaximum(),
                result,
                visited);
        collectReferencedBlueIds(
                schema.getMultipleOf(), result, visited);
        collectReferencedBlueIds(
                schema.getMinItems(), result, visited);
        collectReferencedBlueIds(
                schema.getMaxItems(), result, visited);
        collectReferencedBlueIds(
                schema.getUniqueItems(), result, visited);
        collectReferencedBlueIds(
                schema.getMinFields(), result, visited);
        collectReferencedBlueIds(
                schema.getMaxFields(), result, visited);
        if (schema.getEnum() != null) {
            for (Node value : schema.getEnum()) {
                collectReferencedBlueIds(
                        value, result, visited);
            }
        }
    }

    public static void classifyMandateContractRefresh(
            DocumentProcessingResult result,
            boolean effectiveTypePresentBeforeRun,
            String context) {
        boolean exactDefect =
                result != null
                        && (result.status()
                        == ProcessorStatus.RUNTIME_FATAL
                        || result.status()
                        == ProcessorStatus.CAPABILITY_FAILURE)
                        && result.diagnostic() != null
                        && result.diagnostic().category()
                        == ProcessorErrorCategory
                        .UnsupportedRuntimeType
                        && "Contract 'mandateGuarantorChannel' "
                        .concat("must declare a type")
                        .equals(
                                result.diagnostic()
                                        .message())
                        && result.events().isEmpty()
                        && effectiveTypePresentBeforeRun;
        classify(
                "mandate-effective-contract-type-refresh",
                "Language mandate effective-contract refresh defect:",
                exactDefect,
                result != null
                        && result.status()
                        == ProcessorStatus.SUCCESS,
                context + ": " + resultTuple(result)
                        + ", effectiveTypePresentBeforeRun="
                        + effectiveTypePresentBeforeRun);
    }
}
