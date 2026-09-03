package blue.coordination.internal;

import blue.coordination.api.Contracts10Configuration;
import blue.coordination.api.ContractsClosureDispatchAttempt;
import blue.coordination.api.DocumentId;
import blue.coordination.api.ExactValue;
import blue.coordination.api.Operation;
import blue.coordination.api.ProcessingDrainReceipt;
import blue.coordination.api.Timeline;
import blue.coordination.api.TimelineEntry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Focused regression coverage for additive SDK engine seams. */
final class SdkCoreSeamsTest {
    private static final DocumentId A = DocumentId.of("sdk-core-a");
    private static final DocumentId B = DocumentId.of("sdk-core-b");

    @Test
    void bundledReleaseCreatesExactContractsConfiguration() {
        // given
        BundledContracts10Release.Manifest manifest =
                BundledContracts10Release.manifest();

        // when
        Contracts10Configuration configuration =
                BundledContracts10Release.configuration(Set.of(A));

        // then
        assertEquals(manifest.blueLanguageSpecification(),
                configuration.blueLanguageSpecificationIdentity());
        assertEquals(manifest.contractsSpecification(),
                configuration.contractsSpecificationIdentity());
        assertEquals(Set.of(A), configuration.publicRootDocumentIds());
        assertEquals(
                "sha256:019a436c6266400710bca7f49905c2c53d62434762850236ca0f86d99dff1b37",
                manifest.blueLanguageSpecification());
        assertEquals(
                "sha256:62be2e671a88d231c151944a35030c0c56696cc6e4b073f86681f0c795c54bf9",
                manifest.contractsSpecification());
        assertEquals(
                "sha256:ed634d06aa95153fd34ae991c901131714a2a303980c49dc12ba9ce498364c5c",
                manifest.contractsRelease());
        assertEquals(
                "sha256:5c6c6ca1ae10cff5e3afa4ee3a816b9e9f1a0bca802c71662f06951001473783",
                manifest.fixturePackage());
        assertEquals(
                "sha256:03219c42eb3696ef8727fe8ae226c8a5eb4a6126859ba744f571d892c409626a",
                manifest.gasManifest());
        assertEquals(
                "sha256:d71ec19247a32f7f40107f512e4eb567b4cb41ae8e73c16d2ebe10b0e8517c76",
                manifest.cyclicFinalizer());
        assertEquals(
                "sha256:0768d22420c5bb01109861eb9e090b758aa66b25c2df7b4708dff36a969cefdd",
                manifest.cyclicProofVerifier());
    }

    @Test
    void targetedOperationWritesExactDocumentEvidenceIntoEntry() {
        try (DefaultCoordinationEngine engine =
                DefaultCoordinationEngine.create()) {
            // given
            Timeline timeline = engine.registerTimeline(
                    "sdk-target-timeline", "alice");
            ExactValue target = engine.exactValue(
                    "documentId: sdk-target\ncounter: 1");

            // when
            TimelineEntry entry = engine.append(
                    timeline,
                    Operation.yaml("update", "ownerChannel", "{}")
                            .targeting(target, true));

            // then
            assertEquals(target.blueId(), entry.exactEvent()
                    .canonicalAt("/message/document")
                    .getReferenceBlueId());
            assertEquals(Boolean.TRUE, entry.exactEvent()
                    .canonicalAt("/message/requireExactDocumentVersion")
                    .getValue());
        }
    }

    @Test
    void sdkRuntimeCanBootstrapBeforeAnyPublicRootIsKnown() {
        // given
        BundledContracts10Release.Manifest manifest =
                BundledContracts10Release.manifest();
        try (DefaultCoordinationEngine engine =
                DefaultCoordinationEngine.createContracts10Sdk(
                        manifest.blueLanguageSpecification(),
                        manifest.contractsSpecification())) {
            // when
            engine.authorizeContractsPublicRoots(Set.of(B));
            Contracts10ScenarioBuilder.ScenarioRuntime runtime =
                    new Contracts10ScenarioBuilder(engine)
                            .document(B, "marker: b")
                            .publicRoot(B)
                            .expectedComponent(B)
                            .admitTo(engine);
            ContractsClosureDispatchAttempt attempt =
                    new ContractsClosureDispatchAttempt(
                            "sha256:" + "1".repeat(64),
                            List.of(B),
                            runtime.admissionReceipt().attempt(),
                            true,
                            runtime.admissionReceipt().publicationIdentity(),
                            false);
            ProcessingDrainReceipt drain = new ProcessingDrainReceipt(
                    List.of(), Map.of(), Map.of(attempt.entryBlueId(),
                    List.of(attempt)), null, true, false, 0L, 0L);

            // then
            assertTrue(runtime.admissionReceipt().published());
            assertEquals(List.of(attempt),
                    drain.contractsAttemptsFor(attempt.entryBlueId()));
            assertFalse(drain.blocked());
        }
    }
}
