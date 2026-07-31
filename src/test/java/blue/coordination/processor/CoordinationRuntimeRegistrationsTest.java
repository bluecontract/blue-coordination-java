package blue.coordination.processor;

import blue.language.processor.DocumentProcessor;
import blue.repo.myos.MyOSTimelineChannel;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class CoordinationRuntimeRegistrationsTest {

    @Test
    void shouldBindIdentityToActuallyInstalledCoordinationProcessors() {
        // Given
        DocumentProcessor empty =
                DocumentProcessor.builder().build();
        DocumentProcessor configured =
                CoordinationProcessors.configure(
                        DocumentProcessor.builder())
                        .build();

        try {
            // When
            String emptyIdentity =
                    CoordinationRuntimeRegistrations
                            .identity(empty);
            String configuredIdentity =
                    CoordinationRuntimeRegistrations
                            .identity(configured);

            // Then
            assertNotEquals(
                    emptyIdentity,
                    configuredIdentity);
        } finally {
            empty.close();
            configured.close();
        }
    }

    @Test
    void shouldBindIdentityToExplicitTimelineSubtypeRegistration() {
        // Given
        DocumentProcessor base =
                CoordinationProcessors.configure(
                        DocumentProcessor.builder())
                        .build();
        DocumentProcessor extended =
                CoordinationProcessors
                        .registerTimelineSubtype(
                                CoordinationProcessors
                                        .configure(
                                                DocumentProcessor
                                                        .builder()),
                                MyOSTimelineChannel.class)
                        .build();

        try {
            // When
            String baseIdentity =
                    CoordinationRuntimeRegistrations
                            .identity(base);
            String extendedIdentity =
                    CoordinationRuntimeRegistrations
                            .identity(extended);

            // Then
            assertNotEquals(
                    baseIdentity,
                    extendedIdentity);
            assertEquals(
                    Collections.singletonList(
                            MyOSTimelineChannel.blueId()),
                    CoordinationRuntimeRegistrations
                            .timelineSubtypeBlueIds(
                                    extended));
        } finally {
            base.close();
            extended.close();
        }
    }
}
