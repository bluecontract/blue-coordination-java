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
                "sha256:8fa141d5babb21a0b5df064a1b715e3d57f868a9a087fc1fd20b686761375242",
                manifest.contractsSpecification());
        assertEquals(
                "sha256:32a5c3f8dfe99a421ca0d6862bc1f59bddcfb10e4762dcf3d8200b4726defad3",
                manifest.contractsRelease());
        assertEquals(
                "sha256:0d70b0399a61364774fe0509b18b89db27c4ce8bce27db2e5c238a8c6cd59b79",
                manifest.fixturePackage());
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
