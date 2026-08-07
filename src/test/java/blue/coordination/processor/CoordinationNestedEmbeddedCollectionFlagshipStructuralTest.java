package blue.coordination.processor;

import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import blue.language.model.NodeWireForm;
import blue.language.processor.EffectiveFragmentationCatalog;
import blue.language.processor.EmbeddedScopePlanView;
import blue.language.processor.registry.RuntimeBlueIds;
import blue.language.provider.ExactNodeGraphFragments;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Structural flagship for the current public Process Embedded collection
 * catalog. Runtime PROCESS equivalence is intentionally outside this test:
 * these assertions cover only the public scope plan, Coordination's physical
 * split, occurrence provenance, and exact reconstruction.
 */
final class CoordinationNestedEmbeddedCollectionFlagshipStructuralTest {

    private static final String AGREEMENT_A =
            "/agreements/agreement-a";
    private static final String AGREEMENT_B =
            "/agreements/agreement-b";
    private static final String LESSON_A =
            AGREEMENT_A + "/lessons/lesson-a";
    private static final String LESSON_B =
            AGREEMENT_A + "/lessons/lesson-b";
    private static final String LESSON_C =
            AGREEMENT_B + "/lessons/lesson-c";
    private static final String PAYMENT_A =
            AGREEMENT_A + "/paymentProcesses/payment-a";
    private static final String PAYMENT_ESCAPED =
            AGREEMENT_A
                    + "/paymentProcesses/payment~1b~0retry";
    private static final String PAYMENT_C =
            AGREEMENT_B + "/paymentProcesses/payment-c";
    private static final String CANCEL_A =
            LESSON_A + "/cancellations/cancel-a";
    private static final String CANCEL_B =
            LESSON_A + "/cancellations/cancel-b";

    @Test
    void shouldExposeExactAgreementPortfolioCollectionScopePlans() {
        // given
        AgreementPortfolioFixture fixture =
                AgreementPortfolioFixture.create();

        // when
        CoordinationDocumentSplitterTestSupport.CollectionInspection
                inspection = CoordinationDocumentSplitterTestSupport
                .inspectCollectionDocument(fixture.root);
        EffectiveFragmentationCatalog catalog = inspection.catalog();

        // then
        assertEquals(
                DirectBlueIdCalculator.calculateBlueId(fixture.root),
                catalog.rootBlueId());
        assertEquals(
                new TreeSet<>(fixture.scopesByPath.keySet()),
                new TreeSet<>(catalog.scopePlansByScope().keySet()));
        assertCollectionPlan(
                catalog,
                "/",
                Collections.singletonList("/agreements"),
                collectionMembers(
                        "/agreements",
                        "agreement-a",
                        "agreement-b"),
                Arrays.asList(AGREEMENT_A, AGREEMENT_B));
        assertCollectionPlan(
                catalog,
                AGREEMENT_A,
                Arrays.asList("/lessons", "/paymentProcesses"),
                collectionMembers(
                        "/lessons",
                        Arrays.asList("lesson-a", "lesson-b"),
                        "/paymentProcesses",
                        Arrays.asList("payment-a", "payment/b~retry")),
                Arrays.asList(
                        LESSON_A,
                        LESSON_B,
                        PAYMENT_A,
                        PAYMENT_ESCAPED));
        assertCollectionPlan(
                catalog,
                AGREEMENT_B,
                Arrays.asList("/lessons", "/paymentProcesses"),
                collectionMembers(
                        "/lessons",
                        Collections.singletonList("lesson-c"),
                        "/paymentProcesses",
                        Collections.singletonList("payment-c")),
                Arrays.asList(LESSON_C, PAYMENT_C));
        assertCollectionPlan(
                catalog,
                LESSON_A,
                Collections.singletonList("/cancellations"),
                collectionMembers(
                        "/cancellations",
                        "cancel-a",
                        "cancel-b"),
                Arrays.asList(CANCEL_A, CANCEL_B));
        assertCollectionPlan(
                catalog,
                LESSON_B,
                Collections.singletonList("/cancellations"),
                collectionMembers("/cancellations"),
                Collections.<String>emptyList());
        assertCollectionPlan(
                catalog,
                LESSON_C,
                Collections.singletonList("/cancellations"),
                collectionMembers("/cancellations"),
                Collections.<String>emptyList());
        assertEmptyCollectionPlan(catalog, PAYMENT_A);
        assertEmptyCollectionPlan(catalog, PAYMENT_ESCAPED);
        assertEmptyCollectionPlan(catalog, PAYMENT_C);
        assertEmptyCollectionPlan(catalog, CANCEL_A);
        assertEmptyCollectionPlan(catalog, CANCEL_B);
    }

    @Test
    void shouldRetainEveryCollectionDeclarationAndEscapedMemberKey() {
        // given
        AgreementPortfolioFixture fixture =
                AgreementPortfolioFixture.create();
        Map<String, String> expectedProvenance =
                expectedProvenance();

        // when
        CoordinationDocumentSplitter.SplitGraph split =
                CoordinationDocumentSplitterTestSupport
                        .inspectCollectionDocument(fixture.root)
                        .split();
        Map<String, String> actualProvenance =
                embeddedProvenance(split);
        CoordinationDocumentSplitter.EdgeOccurrence escaped =
                occurrenceAt(split, PAYMENT_ESCAPED);

        // then
        assertEquals(
                new ArrayList<>(expectedProvenance.keySet()),
                embeddedPointers(split));
        assertEquals(expectedProvenance, actualProvenance);
        assertEquals(AGREEMENT_A, escaped.declaringScopePath());
        assertEquals("/paymentProcesses",
                escaped.collectionDeclarationPath());
        assertEquals("payment/b~retry",
                escaped.collectionMemberKey());
        assertEquals(PAYMENT_ESCAPED, escaped.absolutePointer());
        assertNull(escaped.explicitDeclarationPath());
    }

    @Test
    void shouldKeepSharedCancellationBlueIdAsTwoIndependentOccurrences() {
        // given
        AgreementPortfolioFixture fixture =
                AgreementPortfolioFixture.create();
        String sharedCancellationBlueId =
                DirectBlueIdCalculator.calculateBlueId(
                        fixture.scopesByPath.get(CANCEL_A));

        // when
        CoordinationDocumentSplitter.SplitGraph split =
                CoordinationDocumentSplitterTestSupport
                        .inspectCollectionDocument(fixture.root)
                        .split();
        CoordinationDocumentSplitter.EdgeOccurrence cancelA =
                occurrenceAt(split, CANCEL_A);
        CoordinationDocumentSplitter.EdgeOccurrence cancelB =
                occurrenceAt(split, CANCEL_B);
        List<String> retainedOccurrencePaths =
                fragmentRootPaths(split, sharedCancellationBlueId);

        // then
        assertEquals(sharedCancellationBlueId, cancelA.childBlueId());
        assertEquals(sharedCancellationBlueId, cancelB.childBlueId());
        assertFalse(cancelA.absolutePointer().equals(
                cancelB.absolutePointer()));
        assertFalse(cancelA.ownerRelativePointer().equals(
                cancelB.ownerRelativePointer()));
        assertEquals(
                Arrays.asList(CANCEL_A, CANCEL_B),
                retainedOccurrencePaths);
        assertEquals(1,
                split.fragments().containsKey(sharedCancellationBlueId)
                        ? 1 : 0);
    }

    @Test
    void shouldProduceExactCanonicalInventoryAndReconstructAgreementPortfolio() {
        // given
        AgreementPortfolioFixture fixture =
                AgreementPortfolioFixture.create();
        ExactNodeGraphFragments expectedFragments =
                new ExactNodeGraphFragments(
                        fixture.scopesByPath.values());

        // when
        CoordinationDocumentSplitter.SplitGraph split =
                CoordinationDocumentSplitterTestSupport
                        .inspectCollectionDocument(fixture.root)
                        .split();
        Node reconstructed = split.reconstruct();

        // then
        assertExactCanonicalFragments(
                expectedFragments.fragments(),
                split.fragments());
        assertEquals(
                expectedFragmentRootRows(fixture),
                actualFragmentRootRows(split));
        assertEquals(
                expectedMetadataRows(fixture),
                actualMetadataRows(split));
        assertEquals(
                NodeWireForm.get(fixture.root),
                NodeWireForm.get(reconstructed));
        assertEquals(
                DirectBlueIdCalculator.calculateBlueId(fixture.root),
                split.rootBlueId());
        assertEquals(
                split.rootBlueId(),
                DirectBlueIdCalculator.calculateBlueId(reconstructed));
        assertTrue(split.inventoryIdentity().startsWith("sha256:"));
    }

    private static void assertCollectionPlan(
            EffectiveFragmentationCatalog catalog,
            String scopePath,
            List<String> collectionDeclarations,
            Map<String, List<String>> memberKeys,
            List<String> concretePaths) {
        EmbeddedScopePlanView view =
                catalog.scopePlansByScope().get(scopePath);
        assertNotNull(view, "missing scope plan at " + scopePath);
        assertEquals(scopePath, view.scopePath());
        assertEquals(Collections.emptyList(),
                view.explicitDeclarationPaths());
        assertEquals(collectionDeclarations,
                view.collectionDeclarationPaths());
        assertEquals(memberKeys,
                view.collectionMemberKeysByDeclaration());
        assertEquals(concretePaths, view.concreteChildPaths());
        Map<String, EmbeddedScopePlanView.Origin> expectedOrigins =
                new LinkedHashMap<>();
        for (String concretePath : concretePaths) {
            expectedOrigins.put(
                    concretePath,
                    EmbeddedScopePlanView.Origin.COLLECTION_MEMBER);
        }
        assertEquals(expectedOrigins, view.originsByConcretePath());
    }

    private static void assertEmptyCollectionPlan(
            EffectiveFragmentationCatalog catalog,
            String scopePath) {
        assertCollectionPlan(
                catalog,
                scopePath,
                Collections.<String>emptyList(),
                Collections.<String, List<String>>emptyMap(),
                Collections.<String>emptyList());
    }

    private static Map<String, List<String>> collectionMembers(
            String declaration,
            String... keys) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        result.put(declaration, Arrays.asList(keys));
        return result;
    }

    private static Map<String, List<String>> collectionMembers(
            String firstDeclaration,
            List<String> firstKeys,
            String secondDeclaration,
            List<String> secondKeys) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        result.put(firstDeclaration, firstKeys);
        result.put(secondDeclaration, secondKeys);
        return result;
    }

    private static Map<String, String> expectedProvenance() {
        Map<String, String> result = new TreeMap<>();
        putProvenance(result, AGREEMENT_A, "/", "/agreements",
                "agreement-a");
        putProvenance(result, AGREEMENT_B, "/", "/agreements",
                "agreement-b");
        putProvenance(result, LESSON_A, AGREEMENT_A, "/lessons",
                "lesson-a");
        putProvenance(result, LESSON_B, AGREEMENT_A, "/lessons",
                "lesson-b");
        putProvenance(result, PAYMENT_A, AGREEMENT_A,
                "/paymentProcesses", "payment-a");
        putProvenance(result, PAYMENT_ESCAPED, AGREEMENT_A,
                "/paymentProcesses", "payment/b~retry");
        putProvenance(result, LESSON_C, AGREEMENT_B, "/lessons",
                "lesson-c");
        putProvenance(result, PAYMENT_C, AGREEMENT_B,
                "/paymentProcesses", "payment-c");
        putProvenance(result, CANCEL_A, LESSON_A, "/cancellations",
                "cancel-a");
        putProvenance(result, CANCEL_B, LESSON_A, "/cancellations",
                "cancel-b");
        return result;
    }

    private static void putProvenance(
            Map<String, String> target,
            String path,
            String declaringScope,
            String declaration,
            String memberKey) {
        target.put(
                path,
                declaringScope + "|" + declaration + "|" + memberKey);
    }

    private static Map<String, String> embeddedProvenance(
            CoordinationDocumentSplitter.SplitGraph split) {
        Map<String, String> result = new TreeMap<>();
        for (CoordinationDocumentSplitter.EdgeOccurrence occurrence
                : split.edgeOccurrences()) {
            if (occurrence.edgeKind()
                    != CoordinationDocumentSplitter.EdgeKind.EMBEDDED_ROOT) {
                continue;
            }
            assertEquals(
                    CoordinationDocumentSplitter.EmbeddedEdgeOrigin
                            .COLLECTION_MEMBER,
                    occurrence.embeddedOrigin());
            assertTrue(occurrence.splitterCreated());
            assertFalse(occurrence.originalPureReference());
            assertNull(occurrence.explicitDeclarationPath());
            result.put(
                    occurrence.absolutePointer(),
                    occurrence.declaringScopePath()
                            + "|"
                            + occurrence.collectionDeclarationPath()
                            + "|"
                            + occurrence.collectionMemberKey());
        }
        return result;
    }

    private static List<String> embeddedPointers(
            CoordinationDocumentSplitter.SplitGraph split) {
        List<String> result = new ArrayList<>();
        for (CoordinationDocumentSplitter.EdgeOccurrence occurrence
                : split.edgeOccurrences()) {
            if (occurrence.edgeKind()
                    == CoordinationDocumentSplitter.EdgeKind.EMBEDDED_ROOT) {
                result.add(occurrence.absolutePointer());
            }
        }
        Collections.sort(result);
        return result;
    }

    private static CoordinationDocumentSplitter.EdgeOccurrence occurrenceAt(
            CoordinationDocumentSplitter.SplitGraph split,
            String absolutePointer) {
        for (CoordinationDocumentSplitter.EdgeOccurrence occurrence
                : split.edgeOccurrences()) {
            if (occurrence.edgeKind()
                    == CoordinationDocumentSplitter.EdgeKind.EMBEDDED_ROOT
                    && absolutePointer.equals(
                    occurrence.absolutePointer())) {
                return occurrence;
            }
        }
        throw new AssertionError(
                "No embedded occurrence at " + absolutePointer);
    }

    private static List<String> fragmentRootPaths(
            CoordinationDocumentSplitter.SplitGraph split,
            String blueId) {
        List<String> result = new ArrayList<>();
        for (CoordinationDocumentSplitter.FragmentRoot fragmentRoot
                : split.fragmentRoots()) {
            if (blueId.equals(fragmentRoot.blueId())) {
                result.add(fragmentRoot.absolutePath());
            }
        }
        Collections.sort(result);
        return result;
    }

    private static void assertExactCanonicalFragments(
            Map<String, Node> expected,
            Map<String, Node> actual) {
        assertEquals(expected.keySet(), actual.keySet());
        for (String blueId : expected.keySet()) {
            assertEquals(
                    NodeWireForm.get(expected.get(blueId)),
                    NodeWireForm.get(actual.get(blueId)),
                    "canonical fragment " + blueId);
        }
    }

    private static Set<String> expectedFragmentRootRows(
            AgreementPortfolioFixture fixture) {
        Set<String> result = new TreeSet<>();
        for (Map.Entry<String, Node> scope
                : fixture.scopesByPath.entrySet()) {
            String kind = "/".equals(scope.getKey())
                    ? CoordinationDocumentSplitter.FragmentRootKind
                    .DOCUMENT.name()
                    : CoordinationDocumentSplitter.FragmentRootKind
                    .DOCUMENT_SCOPE.name();
            result.add(
                    kind
                            + "|"
                            + scope.getKey()
                            + "|"
                            + DirectBlueIdCalculator.calculateBlueId(
                            scope.getValue()));
        }
        return result;
    }

    private static Set<String> actualFragmentRootRows(
            CoordinationDocumentSplitter.SplitGraph split) {
        Set<String> result = new TreeSet<>();
        for (CoordinationDocumentSplitter.FragmentRoot root
                : split.fragmentRoots()) {
            result.add(
                    root.kind().name()
                            + "|"
                            + root.absolutePath()
                            + "|"
                            + root.blueId());
        }
        return result;
    }

    private static Set<String> expectedMetadataRows(
            AgreementPortfolioFixture fixture) {
        Set<String> result = new TreeSet<>();
        for (Map.Entry<String, Node> scope
                : fixture.scopesByPath.entrySet()) {
            String kind = "/".equals(scope.getKey())
                    ? CoordinationDocumentSplitter.FragmentKind
                    .DOCUMENT_ROOT.name()
                    : CoordinationDocumentSplitter.FragmentKind
                    .EMBEDDED_ROOT.name();
            result.add(
                    kind
                            + "|"
                            + scope.getKey()
                            + "|"
                            + scope.getKey()
                            + "|"
                            + DirectBlueIdCalculator.calculateBlueId(
                            scope.getValue())
                            + "|null|null");
        }
        return result;
    }

    private static Set<String> actualMetadataRows(
            CoordinationDocumentSplitter.SplitGraph split) {
        Set<String> result = new TreeSet<>();
        for (CoordinationDocumentSplitter.FragmentMetadata metadata
                : split.metadata()) {
            result.add(
                    metadata.kind().name()
                            + "|"
                            + metadata.scopePath()
                            + "|"
                            + metadata.pointer()
                            + "|"
                            + metadata.blueId()
                            + "|"
                            + metadata.handlerTypeBlueId()
                            + "|"
                            + metadata.executableBodyField());
        }
        return result;
    }

    private static Node processEmbeddedCollections(
            String... collectionPaths) {
        List<Node> paths = new ArrayList<>();
        for (String collectionPath : collectionPaths) {
            paths.add(scalar(collectionPath));
        }
        return new Node()
                .type(new Node().blueId(
                        RuntimeBlueIds.PROCESS_EMBEDDED))
                .properties(
                        "collectionPaths",
                        new Node().items(paths));
    }

    private static Node scalar(String value) {
        return new Node().value(value);
    }

    private static final class AgreementPortfolioFixture {

        private final Node root;
        private final Map<String, Node> scopesByPath;

        private AgreementPortfolioFixture(
                Node root,
                Map<String, Node> scopesByPath) {
            this.root = root;
            this.scopesByPath = scopesByPath;
        }

        private static AgreementPortfolioFixture create() {
            Node sharedCancellation = new Node()
                    .name("Cancellation Request")
                    .properties("state", scalar("requested"));
            Node cancelA = sharedCancellation.clone();
            Node cancelB = sharedCancellation.clone();
            Map<String, Node> cancellations = new LinkedHashMap<>();
            cancellations.put("cancel-b", cancelB);
            cancellations.put("cancel-a", cancelA);

            Node lessonA = lesson(
                    "Lesson A",
                    "awaiting-confirmation",
                    new Node().properties(cancellations));
            Node lessonB = lesson(
                    "Lesson B",
                    "draft",
                    null);
            Node lessonC = lesson(
                    "Lesson C",
                    "confirmed",
                    null);

            Node sharedPayment = new Node()
                    .name("Payment Process")
                    .properties("state", scalar("pending"));
            Node paymentA = sharedPayment.clone();
            Node paymentEscaped = sharedPayment.clone();
            Node paymentC = new Node()
                    .name("Payment Process")
                    .properties("state", scalar("confirmed"));

            Map<String, Node> agreementALessons = new LinkedHashMap<>();
            agreementALessons.put("lesson-b", lessonB);
            agreementALessons.put("lesson-a", lessonA);
            Map<String, Node> agreementAPayments = new LinkedHashMap<>();
            agreementAPayments.put("payment/b~retry", paymentEscaped);
            agreementAPayments.put("payment-a", paymentA);
            Node agreementA = agreement(
                    "Agreement A",
                    "active",
                    agreementALessons,
                    agreementAPayments);

            Map<String, Node> agreementBLessons = new LinkedHashMap<>();
            agreementBLessons.put("lesson-c", lessonC);
            Map<String, Node> agreementBPayments = new LinkedHashMap<>();
            agreementBPayments.put("payment-c", paymentC);
            Node agreementB = agreement(
                    "Agreement B",
                    "review",
                    agreementBLessons,
                    agreementBPayments);

            Map<String, Node> agreements = new LinkedHashMap<>();
            agreements.put("agreement-b", agreementB);
            agreements.put("agreement-a", agreementA);
            Node root = new Node()
                    .name("Agreement Portfolio")
                    .properties(
                            "state", scalar("open"),
                            "agreements", new Node().properties(agreements))
                    .contracts(new Node().properties(
                            "embedded",
                            processEmbeddedCollections("/agreements")));

            Map<String, Node> scopes = new LinkedHashMap<>();
            scopes.put("/", root);
            scopes.put(AGREEMENT_A, agreementA);
            scopes.put(AGREEMENT_B, agreementB);
            scopes.put(LESSON_A, lessonA);
            scopes.put(LESSON_B, lessonB);
            scopes.put(LESSON_C, lessonC);
            scopes.put(PAYMENT_A, paymentA);
            scopes.put(PAYMENT_ESCAPED, paymentEscaped);
            scopes.put(PAYMENT_C, paymentC);
            scopes.put(CANCEL_A, cancelA);
            scopes.put(CANCEL_B, cancelB);
            return new AgreementPortfolioFixture(
                    root,
                    Collections.unmodifiableMap(scopes));
        }

        private static Node agreement(
                String name,
                String state,
                Map<String, Node> lessons,
                Map<String, Node> paymentProcesses) {
            return new Node()
                    .name(name)
                    .properties(
                            "state", scalar(state),
                            "lessons", new Node().properties(lessons),
                            "paymentProcesses",
                            new Node().properties(paymentProcesses))
                    .contracts(new Node().properties(
                            "embedded",
                            processEmbeddedCollections(
                                    "/lessons",
                                    "/paymentProcesses")));
        }

        private static Node lesson(
                String name,
                String state,
                Node cancellations) {
            Map<String, Node> properties = new LinkedHashMap<>();
            properties.put("state", scalar(state));
            if (cancellations != null) {
                properties.put("cancellations", cancellations);
            }
            return new Node()
                    .name(name)
                    .properties(properties)
                    .contracts(new Node().properties(
                            "embedded",
                            processEmbeddedCollections(
                                    "/cancellations")));
        }
    }
}
