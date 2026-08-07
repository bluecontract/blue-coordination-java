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
        // given
        DocumentProcessor empty =
                DocumentProcessor.builder().build();
        DocumentProcessor configured =
                CoordinationProcessors.configure(
                        DocumentProcessor.builder())
                        .build();

        try {
            // when
            String emptyIdentity =
                    CoordinationProcessors
                            .runtimeRegistrationIdentity(empty);
            String configuredIdentity =
                    CoordinationProcessors
                            .runtimeRegistrationIdentity(configured);

            // then
            assertNotEquals(
                    emptyIdentity,
                    configuredIdentity);
        } finally {
            empty.close();
            configured.close();
        }
    }

    @Test
    void shouldExposeStableIdentityForTheSuppliedProcessorGeneration() {
        // given
        DocumentProcessor configured =
                CoordinationProcessors.configure(
                                DocumentProcessor.builder())
                        .build();

        try {
            // when
            String first = CoordinationProcessors
                    .runtimeRegistrationIdentity(configured);
            String second = CoordinationProcessors
                    .runtimeRegistrationIdentity(configured);

            // then
            assertEquals(first, second);
            assertEquals(
                    CoordinationRuntimeRegistrations.identity(configured),
                    first);
        } finally {
            configured.close();
        }
    }

    @Test
    void shouldBindIdentityToExplicitTimelineSubtypeRegistration() {
        // given
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
            // when
            String baseIdentity =
                    CoordinationRuntimeRegistrations
                            .identity(base);
            String extendedIdentity =
                    CoordinationRuntimeRegistrations
                            .identity(extended);

            // then
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
