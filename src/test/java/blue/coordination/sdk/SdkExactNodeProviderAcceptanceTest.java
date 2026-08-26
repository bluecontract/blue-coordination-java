package blue.coordination.sdk;

import blue.coordination.api.CoordinationErrorCode;
import blue.coordination.api.CoordinationException;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Public-SDK acceptance for direct type content and runtime provider use. */
final class SdkExactNodeProviderAcceptanceTest {
    private static final String PREMIUM_DOCUMENT_TYPE = """
            name: Premium Document
            type: Common/Document
            premiumCode:
              type: Text
              schema:
                required: true
            """;

    @Test
    void directProviderContentFeedsOrdinaryRuntimeTypeResolution() {
        // given
        Map<String, String> content = new LinkedHashMap<>();
        List<String> reads = new ArrayList<>();
        ExactNodeProvider provider = blueId -> {
            reads.add(blueId);
            return Optional.ofNullable(content.get(blueId));
        };

        // when
        try (BlueCoordination blue = BlueCoordination.builder()
                .exactNodeProvider(provider)
                .build()) {
            ExactBlueValue type = blue.values().providerContentYaml(
                    PREMIUM_DOCUMENT_TYPE);

            // then
            assertTrue(type.json().contains("\"schema\""));
            assertTrue(type.json().contains("\"required\""));
            assertFalse(type.json().contains("Common/Document"),
                    "runtime aliases must be preprocessed before identity");
            assertTrue(reads.isEmpty(),
                    "direct provider parsing must neither resolve nor retain "
                            + "the declared type");

            content.put(type.blueId(), type.json());
            ExactBlueValue resolved = blue.values().yaml("""
                    type:
                      blueId: %s
                    kind: account
                    premiumCode: gold
                    """.formatted(type.blueId()));

            assertTrue(resolved.json().contains("\"premiumCode\""));
            assertTrue(resolved.json().contains("gold"));
            assertEquals(List.of(type.blueId()), reads,
                    "ordinary resolution must use the verified provider leaf");
        }
    }

    @Test
    void authoredAdmissionVerificationUsesTheSameProviderLeaf() {
        // given
        Map<String, String> content = new LinkedHashMap<>();
        List<String> reads = new ArrayList<>();
        ExactNodeProvider provider = blueId -> {
            reads.add(blueId);
            return Optional.ofNullable(content.get(blueId));
        };

        // when
        try (BlueCoordination blue = BlueCoordination.builder()
                .exactNodeProvider(provider)
                .build()) {
            ExactBlueValue type = blue.values().providerContentYaml(
                    PREMIUM_DOCUMENT_TYPE);
            content.put(type.blueId(), type.json());
            DocumentHandle admitted = blue.documents().admit(
                    ManagedDocument.yaml("premium-root", """
                                    type:
                                      blueId: %s
                                    kind: account
                                    premiumCode: gold
                                    """.formatted(type.blueId()))
                            .publicRoot()
                            .fromNow());

            // then
            assertEquals("gold",
                    admitted.snapshot().textAt("/premiumCode"));
            assertEquals(List.of(type.blueId()), reads,
                    "isolated authored verification must share the verified "
                            + "provider adapter");
        }
    }

    @Test
    void ordinaryResolutionRejectsProviderIdentityMismatch() {
        // given
        Map<String, String> content = new LinkedHashMap<>();
        ExactNodeProvider provider = blueId -> Optional.ofNullable(
                content.get(blueId));

        // when
        try (BlueCoordination blue = BlueCoordination.builder()
                .exactNodeProvider(provider)
                .build()) {
            ExactBlueValue expected = blue.values().providerContentYaml(
                    PREMIUM_DOCUMENT_TYPE);
            ExactBlueValue wrong = blue.values().providerContentYaml("""
                    name: Wrong Document
                    type: Common/Document
                    premiumCode:
                      type: Text
                      schema:
                        required: true
                    """);
            content.put(expected.blueId(), wrong.json());

            CoordinationException failure = assertThrows(
                    CoordinationException.class,
                    () -> blue.values().yaml("""
                            type:
                              blueId: %s
                            kind: account
                            premiumCode: gold
                            """.formatted(expected.blueId())));

            // then
            assertEquals(CoordinationErrorCode.INVALID_DOCUMENT_IDENTITY,
                    failure.code());
            assertEquals(expected.blueId(),
                    failure.details().get("blueId"));
            assertEquals(wrong.blueId(),
                    failure.details().get("actualBlueId"));
        }
    }
}
