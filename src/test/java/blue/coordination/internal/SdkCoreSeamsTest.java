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
    void bundledReleaseCreatesExactContractsConfiguration() throws Exception {
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
                owningJarResourceIdentity(blue.language.runtime.BlueLanguage.class,
                        "specifications/blue-language-specification-1.0.md"),
                manifest.blueLanguageSpecification());
        assertEquals(
                owningJarResourceIdentity(blue.language.processor.DocumentProcessor.class,
                        "specifications/blue-contracts-and-processor-specification-1.0.md"),
                manifest.contractsSpecification());
        assertEquals(
                "sha256:fcde2e3af19a583743fb485d113531d993156a183cb414587fb75d873b905b51",
                manifest.contractsRelease());
        assertEquals(
                "sha256:e61d6b75b5f6d84065cdbd2e439fa99a6b4710fde163ace2faaa39fdbaa8229f",
                manifest.fixturePackage());
        assertEquals(
                blue.language.processor.GasSchedule.contracts10().packageIdentity(),
                manifest.gasManifest());
        assertEquals(
                blue.language.processor.ClosureRuntimeDescriptor.CYCLIC_FINALIZER_IDENTITY,
                manifest.cyclicFinalizer());
        assertEquals(
                blue.language.processor.ClosureRuntimeDescriptor.CYCLIC_PROOF_VERIFIER_IDENTITY,
                manifest.cyclicProofVerifier());
    }

    private static String owningJarResourceIdentity(Class<?> owner, String resource) throws Exception {
        java.io.File file = java.nio.file.Path.of(owner.getProtectionDomain()
                .getCodeSource().getLocation().toURI()).toFile();
        try (java.util.jar.JarFile jar = new java.util.jar.JarFile(file)) {
            java.util.jar.JarEntry entry = jar.getJarEntry(resource);
            if (entry == null) throw new AssertionError("Missing owning-JAR resource: " + resource);
            try (java.io.InputStream input = jar.getInputStream(entry)) {
                return "sha256:" + java.util.HexFormat.of().formatHex(
                        java.security.MessageDigest.getInstance("SHA-256").digest(input.readAllBytes()));
            }
        }
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
