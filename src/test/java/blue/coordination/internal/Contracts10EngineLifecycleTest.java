package blue.coordination.internal;

import blue.coordination.api.Contracts10Configuration;
import blue.coordination.api.CoordinationException;
import blue.coordination.api.DocumentId;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class Contracts10EngineLifecycleTest {
    private static final String LANGUAGE_ID =
            "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String CONTRACTS_ID =
            "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";

    @Test
    void optInFactoryOwnsFeederAndRetainsDurableProgressAcrossRestart() {
        Contracts10Configuration configuration =
                new Contracts10Configuration(
                        LANGUAGE_ID,
                        CONTRACTS_ID,
                        Set.of(DocumentId.of("public-root")));

        try (DefaultCoordinationEngine engine =
                DefaultCoordinationEngine.createContracts10(configuration)) {
            ContractsRootFeederCoordinator before =
                    engine.contractsFeederCoordinator();
            ContractsRootFeederWindow.DurableState durable =
                    before.durableState();
            ContractsJournalDrainCoordinator journalBefore =
                    engine.contractsJournalCoordinator();
            ContractsJournalDrainCoordinator.DurableState journalDurable =
                    journalBefore.durableState();

            engine.restartFromStores();

            ContractsRootFeederCoordinator after =
                    engine.contractsFeederCoordinator();
            assertNotSame(before, after);
            assertSame(durable, after.durableState());
            ContractsJournalDrainCoordinator journalAfter =
                    engine.contractsJournalCoordinator();
            assertNotSame(journalBefore, journalAfter);
            assertSame(journalDurable, journalAfter.durableState());
        }
    }

    @Test
    void legacyFactoryDoesNotSilentlyEnableContracts() {
        try (DefaultCoordinationEngine engine =
                DefaultCoordinationEngine.create()) {
            assertThrows(IllegalStateException.class,
                    engine::contractsFeederCoordinator);
        }
    }

    @Test
    void contractsFactoryRejectsLegacyDocumentAdmission() {
        Contracts10Configuration configuration =
                new Contracts10Configuration(
                        LANGUAGE_ID,
                        CONTRACTS_ID,
                        Set.of(DocumentId.of("public-root")));
        try (DefaultCoordinationEngine engine =
                DefaultCoordinationEngine.createContracts10(configuration)) {
            assertThrows(CoordinationException.class,
                    () -> engine.startDocument(
                            DocumentId.of("public-root"),
                            "name: must-use-admit-closure"));
        }
    }
}
