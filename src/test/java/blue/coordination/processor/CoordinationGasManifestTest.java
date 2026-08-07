package blue.coordination.processor;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoordinationGasManifestTest {
    private static final String RESOURCE =
            "blue/coordination/processor/coordination-gas-1.0.yaml";
    private static final String HOST_RESOURCE =
            "blue/coordination/processor/coordination-host-quotas-1.0.yaml";
    private static final String RAW_SHA_256 =
            "9fcdc22563152cdd8cb37f9ea739477ced5f7a9e3088aecaf246812c3a3c6bab";
    private static final String HOST_RAW_SHA_256 =
            "48ebee7646e0bdcf75743944e5d5c11aa9055f39e39a5444d5a03db0b6044f74";
    private static final String PACKAGE_IDENTITY =
            "sha256:45ab8de5985255ba947c5abb6e44cdbd61ca56b5c9fe8ea2617d60e729f26293";

    @Test
    void shouldBundleOnlyPortableProcessCountersInTheGasManifest()
            throws Exception {
        // given
        String manifest = readManifest(RESOURCE);
        List<String> counters = portableCounters();

        // when
        LinkedHashSet<String> runtimeCounters =
                new LinkedHashSet<String>(
                        CoordinationRuntimeGas
                                .counterWeights()
                                .keySet());

        // then
        assertTrue(manifest.contains(
                "packageIdentity: " + PACKAGE_IDENTITY));
        assertEquals(14, counters.size());
        for (String counter : counters) {
            assertTrue(
                    manifest.contains("- name: " + counter + "\n"),
                    "missing frozen counter " + counter);
        }
        assertEquals(
                14,
                occurrences(manifest, "- name: "));
        assertEquals(
                new LinkedHashSet<String>(counters),
                runtimeCounters);
    }

    @Test
    void shouldKeepHostCountersOutOfThePortableGasManifest()
            throws Exception {
        // given
        String manifest = readManifest(RESOURCE);
        String hostManifest = readManifest(HOST_RESOURCE);
        List<String> hostCounters = hostCounters();

        // when
        boolean hostManifestIsNonPortable =
                hostManifest.contains(
                        "portableProcessGas: false");

        // then
        assertTrue(hostManifestIsNonPortable);
        for (String hostCounter : hostCounters) {
            assertFalse(
                    manifest.contains(
                            "- name: " + hostCounter + "\n"));
            assertTrue(
                    hostManifest.contains(
                            "- name: " + hostCounter + "\n"));
        }
        assertEquals(
                9,
                occurrences(hostManifest, "- name: "));
    }

    @Test
    void shouldFreezePortableAndHostGasManifestBytes()
            throws Exception {
        // given
        byte[] portableBytes = readResource(RESOURCE);
        byte[] hostBytes = readResource(HOST_RESOURCE);

        // when
        String portableHash = sha256(portableBytes);
        String hostHash = sha256(hostBytes);

        // then
        assertEquals(RAW_SHA_256, portableHash);
        assertEquals(HOST_RAW_SHA_256, hostHash);
    }

    @Test
    void shouldBindManifestLimitsToTheirOwningRuntimeConstants()
            throws Exception {
        // given
        String manifest = readManifest(RESOURCE);
        String hostManifest = readManifest(HOST_RESOURCE);

        // when
        long runtimeGasLimit =
                CoordinationRuntimeLimits
                        .MAX_COORDINATION_RUNTIME_GAS_PER_PROCESS;

        // then
        assertTrue(hostManifest.contains(
                "portableProcessGas: false"));
        assertTrue(manifest.contains("maxCompositeMembers: 1024"));
        assertTrue(manifest.contains("maxAllTimelinesMembers: 4096"));
        assertTrue(manifest.contains("maxWorkflowSteps: 4096"));
        assertTrue(manifest.contains(
                "maxOperationCandidatesPerChannel: 4096"));
        assertFalse(manifest.contains("maxSplitterCuts:"));
        assertFalse(manifest.contains(
                "maxMandateCandidatesPerDecision:"));
        assertTrue(hostManifest.contains("maxSplitterCuts: 16384"));
        assertTrue(hostManifest.contains(
                "maxMandateCandidatesPerDecision: 4096"));
        assertTrue(hostManifest.contains(
                "maxSubscriptionOccurrencesPerProjection: 65536"));
        assertTrue(hostManifest.contains(
                "maxIndexedCandidatesPerPlan: 65536"));
        assertTrue(hostManifest.contains(
                "maxPrefetchIdentitiesPerPlan: 65536"));
        assertTrue(manifest.contains(
                "maxCoordinationRuntimeGasPerProcess: 100000"));
        assertEquals(
                1024,
                CoordinationRuntimeLimits.MAX_COMPOSITE_MEMBERS);
        assertEquals(
                4096,
                CoordinationRuntimeLimits.MAX_ALL_TIMELINES_MEMBERS);
        assertEquals(
                4096,
                CoordinationRuntimeLimits.MAX_WORKFLOW_STEPS);
        assertEquals(
                4096,
                CoordinationRuntimeLimits
                        .MAX_OPERATION_CANDIDATES_PER_CHANNEL);
        assertEquals(
                16384,
                CoordinationHostQuotas.MAX_SPLITTER_CUTS);
        assertEquals(
                4096,
                CoordinationHostQuotas
                        .MAX_MANDATE_CANDIDATES_PER_DECISION);
        assertEquals(
                65536,
                CoordinationHostQuotas
                        .MAX_SUBSCRIPTION_OCCURRENCES_PER_PROJECTION);
        assertEquals(
                65536,
                CoordinationHostQuotas
                        .MAX_INDEXED_CANDIDATES_PER_PLAN);
        assertEquals(
                65536,
                CoordinationHostQuotas
                        .MAX_PREFETCH_IDENTITIES_PER_PLAN);
        assertEquals(
                100_000L,
                runtimeGasLimit);
    }

    private static List<String> portableCounters() {
        return Arrays.asList(
                "timelineHeaderRead",
                "timelineBindingCompared",
                "compositeMemberVisited",
                "allTimelinesMemberVisited",
                "operationRequestFieldRead",
                "operationTargetLookup",
                "operationCandidateTested",
                "workflowStepVisited",
                "workflowStepExecuted",
                "updateDocumentStep",
                "triggerEventStep",
                "terminateProcessingStep",
                "computeStepEntered",
                "computeDefinitionResolved");
    }

    private static List<String> hostCounters() {
        return Arrays.asList(
                "splitterCatalogEntryVisited",
                "splitterFragmentAdmitted",
                "splitterCutValidated",
                "mandatePredicateEvaluated",
                "responderMandateCandidateTested",
                "subscriptionOccurrenceProjected",
                "indexedCandidateValidated",
                "prefetchIdentityConstructed",
                "fragmentEdgeMetadataProduced");
    }

    private String readManifest(String resource) throws Exception {
        return new String(
                readResource(resource),
                StandardCharsets.UTF_8);
    }

    private static String sha256(byte[] bytes) throws Exception {
        return hex(
                MessageDigest.getInstance("SHA-256")
                        .digest(bytes));
    }

    private byte[] readResource(String resource) throws Exception {
        try (InputStream input = getClass().getClassLoader()
                .getResourceAsStream(resource)) {
            assertNotNull(
                    input,
                    "missing frozen Coordination resource "
                            + resource);
            ByteArrayOutputStream output =
                    new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    private static int occurrences(String value, String needle) {
        int count = 0;
        int offset = 0;
        while ((offset = value.indexOf(needle, offset)) >= 0) {
            count++;
            offset += needle.length();
        }
        return count;
    }

    private static String hex(byte[] bytes) {
        StringBuilder result =
                new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            result.append(String.format(
                    java.util.Locale.ROOT,
                    "%02x",
                    value & 0xff));
        }
        return result.toString();
    }
}
