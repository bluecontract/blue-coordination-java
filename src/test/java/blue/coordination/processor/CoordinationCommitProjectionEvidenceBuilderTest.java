package blue.coordination.processor;

import blue.coordination.engine.api.CoordinationFragmentInventory;
import blue.coordination.engine.api.FragmentRootRecord;
import blue.coordination.engine.fastpath.ExactNodeHandle;
import blue.coordination.engine.fastpath.HybridResultFrontier;
import blue.coordination.engine.fastpath.IndexedRetainedReferenceResolver;
import blue.coordination.engine.fastpath.PreparedRootExecutionContext;
import blue.coordination.engine.fastpath.RequestDigestMemo;
import blue.coordination.engine.fastpath.RetainedReferenceIndex;
import blue.coordination.engine.fastpath.VerifiedHybridResultFrontier;
import blue.coordination.fastpath.DeltaProjectionApplier;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import blue.language.model.wire.BlueLanguageConstants;
import blue.language.processor.ExternalChannelDependencySnapshot;
import blue.language.processor.ExternalOrderKey;
import blue.language.processor.SubscriptionDelta;
import blue.language.processor.registry.BlueRuntimeTypeRegistry;
import blue.language.processor.registry.RuntimeBlueIds;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class CoordinationCommitProjectionEvidenceBuilderTest {

    @Test
    void scalarDeltaMatchesCompleteSnapshotOracleAndSharesUnchangedScope() {
        Node child = new Node().properties(
                "stable", new Node().value("retained"));
        String childBlueId = blueId(child);
        Node prior = new Node().properties(
                "child", child,
                "counter", new Node().value(1));
        PreparedFixture prepared = prepared(prior);
        Node exactPrior = prepared.exactPriorRoot;

        CoordinationSubscriptionOccurrence rootOccurrence = occurrence(
                "/", prepared.rootBlueId, "root-channel", 0);
        CoordinationSubscriptionOccurrence childOccurrence = occurrence(
                "/child", childBlueId, "child-channel", 1);
        CoordinationSubscriptionSnapshot previous = snapshot(
                prepared.rootBlueId,
                1L,
                order(1L),
                rootOccurrence,
                childOccurrence);

        Node hybrid = new Node().properties(
                "child", new Node().blueId(childBlueId),
                "counter", new Node().value(2));
        String resultingRootBlueId = blueId(hybrid);
        VerifiedHybridResultFrontier frontier = HybridResultFrontier
                .proveRetainedBindings(
                        hybrid, prepared.context, prepared.owner);
        Node exactResult = new IndexedRetainedReferenceResolver(
                prepared.context.retainedReferences(), prepared.owner)
                .resolveRequestOwned(hybrid);

        CoordinationCommitProjectionEvidence evidence =
                new CoordinationCommitProjectionEvidenceBuilder().build(
                        previous,
                        frontier,
                        exactPrior,
                        exactResult,
                        resultingRootBlueId,
                        2L,
                        order(2L),
                        SubscriptionDelta.empty());
        CoordinationSubscriptionUpdate actual =
                new CoordinationDeltaSubscriptionProjector().apply(
                        previous, evidence);

        CoordinationSubscriptionSnapshot completeOracle = snapshot(
                resultingRootBlueId,
                2L,
                order(2L),
                rootOccurrence.withScopeBlueId(resultingRootBlueId),
                childOccurrence);
        assertEquals(completeOracle.toMap(), actual.snapshot().toMap());
        assertEquals(completeOracle.digest(), actual.snapshot().digest());
        assertSame(
                childOccurrence,
                actual.snapshot().occurrence(
                        childOccurrence.occurrenceKey()),
                "an unaffected embedded scope must be structurally shared");
    }

    @Test
    void contractMutationUsesTypedColdFallback() {
        Node prior = new Node()
                .contracts(new Node().properties(
                        "policy", new Node().value("old")))
                .properties("counter", new Node().value(1));
        PreparedFixture prepared = prepared(prior);
        Node changed = new Node()
                .contracts(new Node().properties(
                        "policy", new Node().value("new")))
                .properties("counter", new Node().value(2));
        String changedBlueId = blueId(changed);
        VerifiedHybridResultFrontier frontier = HybridResultFrontier
                .proveRetainedBindings(
                        changed, prepared.context, prepared.owner);

        CoordinationSubscriptionOccurrence occurrence = occurrence(
                "/", prepared.rootBlueId, "root-channel", 0);
        CoordinationSubscriptionSnapshot previous = snapshot(
                prepared.rootBlueId,
                1L,
                order(1L),
                occurrence);

        assertThrows(
                DeltaProjectionApplier.ColdProjectionRequiredException.class,
                () -> new CoordinationCommitProjectionEvidenceBuilder()
                        .build(
                                previous,
                                frontier,
                                prepared.exactPriorRoot,
                                changed,
                                changedBlueId,
                                2L,
                                order(2L),
                                SubscriptionDelta.empty()));
    }

    @Test
    void membershipMutationUsesTypedColdFallback() {
        Node prior = new Node().properties(
                "counter", new Node().value(1));
        PreparedFixture prepared = prepared(prior);
        Node changed = new Node().properties(
                "counter", new Node().value(2));
        VerifiedHybridResultFrontier frontier = HybridResultFrontier
                .proveRetainedBindings(
                        changed, prepared.context, prepared.owner);
        CoordinationSubscriptionOccurrence retained = occurrence(
                "/", prepared.rootBlueId, "root-channel", 0);
        CoordinationSubscriptionOccurrence added = occurrence(
                "/", blueId(changed), "added-channel", 1)
                .withScopeAndInterval(
                        blueId(changed),
                        new SubscriptionDelta.Entry(
                                "/",
                                "added-channel",
                                "type-1",
                                Collections.singletonList("source-1"),
                                1,
                                Collections.singletonList("key-1"),
                                "checkpoint-1",
                                ExternalChannelDependencySnapshot.none(),
                                Long.valueOf(2L),
                                order(2L),
                                null));
        SubscriptionDelta delta = new SubscriptionDelta(
                Collections.singletonList(
                        added.toSubscriptionDeltaEntry()),
                Collections.<SubscriptionDelta.Entry>emptyList());

        assertThrows(
                DeltaProjectionApplier.ColdProjectionRequiredException.class,
                () -> new CoordinationCommitProjectionEvidenceBuilder()
                        .build(
                                snapshot(
                                        prepared.rootBlueId,
                                        1L,
                                        order(1L),
                                        retained),
                                frontier,
                                prepared.exactPriorRoot,
                                changed,
                                blueId(changed),
                                2L,
                                order(2L),
                                delta));
    }

    @Test
    void retainedValueMovedToAnotherPathCannotForgeAFrontierProof() {
        Node left = new Node().value("left");
        Node right = new Node().value("right");
        String leftBlueId = blueId(left);
        String rightBlueId = blueId(right);
        PreparedFixture prepared = prepared(new Node().properties(
                "left", left,
                "right", right));
        Node swapped = new Node().properties(
                "left", new Node().blueId(rightBlueId),
                "right", new Node().blueId(leftBlueId));

        assertThrows(
                DeltaProjectionApplier.ColdProjectionRequiredException.class,
                () -> HybridResultFrontier.proveRetainedBindings(
                        swapped, prepared.context, prepared.owner));
    }

    @Test
    void pureReferenceAtPriorPathUsesVerifiedExpandedRepresentative() {
        Node operationType = new Node().properties(
                "kind", new Node().value("operation"));
        String operationTypeBlueId = blueId(operationType);
        Node prior = new Node().properties(
                "definitions", new Node().properties(
                        "operation", operationType),
                "contract", new Node()
                        .type(new Node().blueId(operationTypeBlueId))
                        .properties("counter", new Node().value(1)));
        PreparedFixture prepared = prepared(prior);
        Node exactPrior = prepared.exactPriorRoot;
        Node exactDefinitions = exactPrior.getProperties().get(
                "definitions");
        String definitionsBlueId = blueId(exactDefinitions);
        Node hybrid = new Node().properties(
                "definitions", new Node().blueId(definitionsBlueId),
                "contract", new Node()
                        .type(new Node().blueId(operationTypeBlueId))
                        .properties("counter", new Node().value(2)));

        VerifiedHybridResultFrontier frontier = HybridResultFrontier
                .proveRetainedBindings(
                        hybrid, prepared.context, prepared.owner);
        Node exactResult = new IndexedRetainedReferenceResolver(
                prepared.context.retainedReferences(), prepared.owner)
                .resolveRequestOwned(hybrid);

        assertTrue(frontier.retainedBindingsRemainExact(exactResult));
        assertSame(
                exactPrior.getProperties().get("definitions")
                        .getProperties().get("operation"),
                exactResult.getProperties().get("contract").getType());
    }

    @Test
    void pureReferencesSwappedBetweenPriorPathsCannotForgeAFrontierProof() {
        Node left = new Node().value("left");
        Node right = new Node().value("right");
        String leftBlueId = blueId(left);
        String rightBlueId = blueId(right);
        Node prior = new Node().properties(
                "definitions", new Node().properties(
                        "left", left,
                        "right", right),
                "aliases", new Node().properties(
                        "left", new Node().blueId(leftBlueId),
                        "right", new Node().blueId(rightBlueId)));
        PreparedFixture prepared = prepared(prior);
        Node exactPrior = prepared.exactPriorRoot;
        String definitionsBlueId = blueId(
                exactPrior.getProperties().get("definitions"));
        Node swapped = new Node().properties(
                "definitions", new Node().blueId(definitionsBlueId),
                "aliases", new Node().properties(
                        "left", new Node().blueId(rightBlueId),
                        "right", new Node().blueId(leftBlueId)));

        assertThrows(
                DeltaProjectionApplier.ColdProjectionRequiredException.class,
                () -> HybridResultFrontier.proveRetainedBindings(
                        swapped, prepared.context, prepared.owner));
    }

    @Test
    void separatelyAllocatedEqualPriorValuesKeepBothExactPathBindings() {
        Node left = new Node().properties(
                "kind", new Node().value("operation"));
        Node right = new Node().properties(
                "kind", new Node().value("operation"));
        String sharedBlueId = blueId(left);
        assertEquals(sharedBlueId, blueId(right));
        PreparedFixture prepared = prepared(new Node().properties(
                "left", left,
                "right", right));
        Node hybrid = new Node().properties(
                "left", new Node().blueId(sharedBlueId),
                "right", new Node().blueId(sharedBlueId));

        VerifiedHybridResultFrontier frontier = HybridResultFrontier
                .proveRetainedBindings(
                        hybrid, prepared.context, prepared.owner);
        Node exactResult = new IndexedRetainedReferenceResolver(
                prepared.context.retainedReferences(), prepared.owner)
                .resolveRequestOwned(hybrid);

        assertTrue(frontier.retainedBindingsRemainExact(exactResult));
        assertSame(
                exactResult.getProperties().get("left"),
                exactResult.getProperties().get("right"));
    }

    @Test
    void unexpandedPureReferenceKeepsExactBlueIdPathBinding() {
        String externalBlueId = blueId(new Node().properties(
                "kind", new Node().value("external-operation")));
        PreparedFixture prepared = prepared(new Node().properties(
                "type", new Node().blueId(externalBlueId)));
        Node hybrid = new Node().properties(
                "type", new Node().blueId(externalBlueId));
        VerifiedHybridResultFrontier frontier = HybridResultFrontier
                .proveRetainedBindings(
                        hybrid, prepared.context, prepared.owner);
        Node exactResult = new IndexedRetainedReferenceResolver(
                prepared.context.retainedReferences(), prepared.owner)
                .resolveRequestOwned(hybrid);

        assertTrue(frontier.retainedBindingsRemainExact(exactResult));
        assertTrue(exactResult.getProperties().get("type").isReferenceOnly());

        exactResult.properties(
                "type", new Node().blueId(blueId(new Node().value("other"))));
        assertFalse(frontier.retainedBindingsRemainExact(exactResult));
    }

    @Test
    void exactExpandedExternalChannelTypeIsOneVerifiedReferenceBoundary() {
        Node runtimeType = new Node().properties(
                "kind", new Node().value("runtime-channel"));
        String runtimeTypeBlueId = blueId(runtimeType);
        Node externalChannel = new Node()
                .type(new Node().blueId(runtimeTypeBlueId))
                .properties("name", new Node().value("hotel-provider"));
        String externalChannelBlueId = blueId(externalChannel);
        Node prior = new Node()
                .contracts(new Node().properties(
                        "attachPayNoteAsCustomer", new Node().properties(
                                "channel", new Node().blueId(
                                        externalChannelBlueId))))
                .properties("counter", new Node().value(1));
        PreparedFixture prepared = prepared(prior);
        Node hybrid = new Node()
                .contracts(new Node().properties(
                        "attachPayNoteAsCustomer", new Node().properties(
                                "channel", externalChannel)))
                .properties("counter", new Node().value(2));
        String resultingRootBlueId = blueId(hybrid);

        VerifiedHybridResultFrontier frontier = HybridResultFrontier
                .proveRetainedBindings(
                        hybrid, prepared.context, prepared.owner);
        Node exactResult = new IndexedRetainedReferenceResolver(
                prepared.context.retainedReferences(), prepared.owner)
                .resolveRequestOwned(hybrid);

        assertEquals(
                externalChannelBlueId,
                frontier.exactValueBoundaryBlueIdByPath().get(
                        "/$contracts/attachPayNoteAsCustomer/channel"));
        assertFalse(frontier.retainedBlueIdByPath().containsKey(
                "/$contracts/attachPayNoteAsCustomer/channel/$type"));
        assertTrue(frontier.retainedBindingsRemainExact(exactResult));

        CoordinationSubscriptionSnapshot previous = snapshot(
                prepared.rootBlueId,
                1L,
                order(1L),
                occurrence(
                        "/",
                        prepared.rootBlueId,
                        "root-channel",
                        0));
        CoordinationCommitProjectionEvidence evidence =
                new CoordinationCommitProjectionEvidenceBuilder().build(
                        previous,
                        frontier,
                        prepared.exactPriorRoot,
                        exactResult,
                        resultingRootBlueId,
                        2L,
                        order(2L),
                        SubscriptionDelta.empty());

        assertEquals(resultingRootBlueId, evidence.resultingRootBlueId());
    }

    @Test
    void expandedExternalReferenceBoundaryIsReverifiedBeforeCommit() {
        Node externalChannel = new Node().properties(
                "name", new Node().value("hotel-provider"));
        String externalChannelBlueId = blueId(externalChannel);
        PreparedFixture prepared = prepared(new Node().properties(
                "channel", new Node().blueId(externalChannelBlueId)));
        Node hybrid = new Node().properties("channel", externalChannel);
        VerifiedHybridResultFrontier frontier = HybridResultFrontier
                .proveRetainedBindings(
                        hybrid, prepared.context, prepared.owner);

        externalChannel.properties(
                "name", new Node().value("forged-provider"));

        assertFalse(frontier.retainedBindingsRemainExact(hybrid));
    }

    @Test
    void canonicalImplicitChannelTypeIsOneExactValueBoundary() {
        String textTypeBlueId = blue.language.model.wire
                .BlueLanguageConstants.TEXT_TYPE_BLUE_ID;
        Node priorChannel = new Node().value("customerChannel");
        Node resultingChannel = new Node()
                .type(new Node().blueId(textTypeBlueId))
                .value("customerChannel");
        assertEquals(blueId(priorChannel), blueId(resultingChannel),
                "explicit primitive type is canonical scalar identity");
        Node prior = new Node()
                .contracts(new Node().properties(
                        "attachPayNoteAsCustomer", new Node().properties(
                                "channel", priorChannel)))
                .properties("counter", new Node().value(1));
        PreparedFixture prepared = prepared(prior);
        Node hybrid = new Node()
                .contracts(new Node().properties(
                        "attachPayNoteAsCustomer", new Node().properties(
                                "channel", resultingChannel)))
                .properties("counter", new Node().value(2));
        String resultingRootBlueId = blueId(hybrid);

        VerifiedHybridResultFrontier frontier = HybridResultFrontier
                .proveRetainedBindings(
                        hybrid, prepared.context, prepared.owner);

        assertEquals(
                blueId(priorChannel),
                frontier.exactValueBoundaryBlueIdByPath().get(
                        "/$contracts/attachPayNoteAsCustomer/channel"));
        assertFalse(frontier.retainedBlueIdByPath().containsKey(
                "/$contracts/attachPayNoteAsCustomer/channel/$type"));
        CoordinationCommitProjectionEvidence evidence =
                new CoordinationCommitProjectionEvidenceBuilder().build(
                        snapshot(
                                prepared.rootBlueId,
                                1L,
                                order(1L),
                                occurrence(
                                        "/",
                                        prepared.rootBlueId,
                                        "root-channel",
                                        0)),
                        frontier,
                        prepared.exactPriorRoot,
                        hybrid,
                        resultingRootBlueId,
                        2L,
                        order(2L),
                        SubscriptionDelta.empty());

        assertEquals(resultingRootBlueId, evidence.resultingRootBlueId());
    }

    @Test
    void expandedImplicitTextTypeIsOneExactValueBoundary() {
        Node priorAccountId = new Node().value("alice");
        Node resultingAccountId = new Node()
                .type(new Node().blueId(
                        BlueLanguageConstants.TEXT_TYPE_BLUE_ID))
                .value("alice");
        assertEquals(
                blueId(priorAccountId),
                blueId(resultingAccountId),
                "expanded primitive type must retain canonical scalar identity");
        Node prior = new Node()
                .contracts(new Node().properties(
                        "customerChannel",
                        new Node().properties(
                                "actor",
                                new Node().properties(
                                        "accountId", priorAccountId))))
                .properties("counter", new Node().value(1));
        PreparedFixture prepared = prepared(prior);
        Node result = new Node()
                .contracts(new Node().properties(
                        "customerChannel",
                        new Node().properties(
                                "actor",
                                new Node().properties(
                                        "accountId", resultingAccountId))))
                .properties("counter", new Node().value(2));
        String resultingRootBlueId = blueId(result);

        VerifiedHybridResultFrontier frontier = HybridResultFrontier
                .proveRetainedBindings(
                        result, prepared.context, prepared.owner);

        assertEquals(
                blueId(priorAccountId),
                frontier.exactValueBoundaryBlueIdByPath().get(
                        "/$contracts/customerChannel/actor/accountId"));
        CoordinationCommitProjectionEvidence evidence =
                new CoordinationCommitProjectionEvidenceBuilder().build(
                        snapshot(
                                prepared.rootBlueId,
                                1L,
                                order(1L),
                                occurrence(
                                        "/",
                                        prepared.rootBlueId,
                                        "root-channel",
                                        0)),
                        frontier,
                        prepared.exactPriorRoot,
                        result,
                        resultingRootBlueId,
                        2L,
                        order(2L),
                        SubscriptionDelta.empty());

        assertEquals(resultingRootBlueId, evidence.resultingRootBlueId());
    }

    @Test
    void changedTextScalarAcceptsOnlyItsCanonicalMaterializedType() {
        Node priorAccountId = new Node().value("alice");
        Node resultingAccountId = new Node()
                .type(new Node().blueId(
                        BlueLanguageConstants.TEXT_TYPE_BLUE_ID))
                .value("bob");
        Node prior = new Node()
                .contracts(new Node().properties(
                        "customerChannel",
                        new Node().properties(
                                "actor",
                                new Node().properties(
                                        "accountId", priorAccountId))))
                .properties("counter", new Node().value(1));
        PreparedFixture prepared = prepared(prior);
        Node result = new Node()
                .contracts(new Node().properties(
                        "customerChannel",
                        new Node().properties(
                                "actor",
                                new Node().properties(
                                        "accountId", resultingAccountId))))
                .properties("counter", new Node().value(2));
        String resultingRootBlueId = blueId(result);
        CoordinationSubscriptionOccurrence retained = occurrence(
                "/", prepared.rootBlueId, "root-channel", 0);
        VerifiedHybridResultFrontier frontier = HybridResultFrontier
                .proveRetainedBindings(
                        result, prepared.context, prepared.owner);

        CoordinationCommitProjectionEvidence evidence =
                new CoordinationCommitProjectionEvidenceBuilder().build(
                        snapshot(
                                prepared.rootBlueId,
                                1L,
                                order(1L),
                                retained),
                        frontier,
                        prepared.exactPriorRoot,
                        result,
                        resultingRootBlueId,
                        2L,
                        order(2L),
                        SubscriptionDelta.empty());

        assertEquals(resultingRootBlueId, evidence.resultingRootBlueId());
        assertTrue(evidence.affectedRetainedOccurrenceKeys().contains(
                retained.occurrenceKey()));
    }

    @Test
    void changedTextScalarRejectsANonCanonicalMaterializedType() {
        String forgedTypeBlueId = blueId(new Node().properties(
                "kind", new Node().value("not-text")));
        Node prior = new Node().properties(
                "accountId", new Node().value("alice"));
        PreparedFixture prepared = prepared(prior);
        Node result = new Node().properties(
                "accountId", new Node()
                        .type(new Node().blueId(forgedTypeBlueId))
                        .value("bob"));

        DeltaProjectionApplier.ColdProjectionRequiredException failure =
                assertThrows(
                        DeltaProjectionApplier
                                .ColdProjectionRequiredException.class,
                        () -> HybridResultFrontier.proveRetainedBindings(
                                result,
                                prepared.context,
                                prepared.owner));

        assertTrue(failure.getMessage().contains("/accountId/$type"));
    }

    @Test
    void changedTextScalarRejectsExplicitToImplicitTypeMetadata() {
        Node prior = new Node().properties(
                "accountId", new Node()
                        .type(new Node().blueId(
                                BlueLanguageConstants.TEXT_TYPE_BLUE_ID))
                        .value("alice"));
        PreparedFixture prepared = prepared(prior);
        Node result = new Node().properties(
                "accountId", new Node().value("bob"));
        String resultingRootBlueId = blueId(result);
        VerifiedHybridResultFrontier frontier = HybridResultFrontier
                .proveRetainedBindings(
                        result, prepared.context, prepared.owner);

        DeltaProjectionApplier.ColdProjectionRequiredException failure =
                assertThrows(
                        DeltaProjectionApplier
                                .ColdProjectionRequiredException.class,
                        () -> new CoordinationCommitProjectionEvidenceBuilder()
                                .build(
                                        snapshot(
                                                prepared.rootBlueId,
                                                1L,
                                                order(1L),
                                                occurrence(
                                                        "/",
                                                        prepared.rootBlueId,
                                                        "root-channel",
                                                        0)),
                                        frontier,
                                        prepared.exactPriorRoot,
                                        result,
                                        resultingRootBlueId,
                                        2L,
                                        order(2L),
                                        SubscriptionDelta.empty()));

        assertTrue(failure.getMessage().contains(
                "semantic metadata at /accountId"));
    }

    @Test
    void expandedProcessEmbeddedTypeProvesOnlyOneCanonicalPathAppend() {
        Node prior = new Node()
                .contracts(new Node().properties(
                        "embedded",
                        processEmbedded(
                                runtimeType(RuntimeBlueIds.PROCESS_EMBEDDED),
                                "/product")))
                .properties(
                        "product", new Node().value("existing"),
                        "payNotes", new Node().properties(
                                Collections.<String, Node>emptyMap()));
        PreparedFixture prepared = prepared(prior);
        Node result = new Node()
                .contracts(new Node().properties(
                        "embedded",
                        processEmbedded(
                                runtimeType(RuntimeBlueIds.PROCESS_EMBEDDED),
                                "/product",
                                "/payNotes/packagePayment")))
                .properties(
                        "product", new Node().value("existing"),
                        "payNotes", new Node().properties(
                                "packagePayment",
                                new Node().value("new")));

        VerifiedHybridResultFrontier frontier = HybridResultFrontier
                .proveRetainedBindings(
                        result, prepared.context, prepared.owner);

        assertEquals(
                blueId(result.getContracts().getProperties().get(
                        "embedded")),
                frontier.processEmbeddedBoundaryBlueIdByPath().get(
                        "/$contracts/embedded"));
        assertTrue(frontier.retainedBindingsRemainExact(result));

        result.getContracts().getProperties().get("embedded")
                .getProperties().get("paths").getItems().get(1)
                .value("/payNotes/forged");
        assertFalse(frontier.retainedBindingsRemainExact(result));
    }

    @Test
    void processEmbeddedAppendAcceptsCanonicalMaterializedPathMetadata() {
        Node prior = new Node()
                .contracts(new Node().properties(
                        "embedded",
                        processEmbedded(
                                runtimeType(RuntimeBlueIds.PROCESS_EMBEDDED),
                                "/product")))
                .properties(
                        "product", new Node().value("existing"),
                        "payNotes", new Node().properties(
                                Collections.<String, Node>emptyMap()));
        PreparedFixture prepared = prepared(prior);
        Node declaration = processEmbedded(
                runtimeType(RuntimeBlueIds.PROCESS_EMBEDDED),
                "/product",
                "/payNotes/packagePayment");
        materializeCanonicalPathMetadata(declaration);
        Node result = new Node()
                .contracts(new Node().properties(
                        "embedded", declaration))
                .properties(
                        "product", new Node().value("existing"),
                        "payNotes", new Node().properties(
                                "packagePayment",
                                new Node().value("new")));

        VerifiedHybridResultFrontier frontier = HybridResultFrontier
                .proveRetainedBindings(
                        result, prepared.context, prepared.owner);

        assertEquals(
                blueId(declaration),
                frontier.processEmbeddedBoundaryBlueIdByPath().get(
                        "/$contracts/embedded"));
        assertFalse(frontier.retainedBlueIdByPath().containsKey(
                "/$contracts/embedded/paths/1/$type"));
        assertTrue(frontier.retainedBindingsRemainExact(result));
    }

    @Test
    void processEmbeddedAppendRejectsANonCanonicalMaterializedListType() {
        Node prior = new Node().contracts(new Node().properties(
                "embedded",
                processEmbedded(
                        new Node().blueId(RuntimeBlueIds.PROCESS_EMBEDDED),
                        "/product")));
        PreparedFixture prepared = prepared(prior);
        Node declaration = processEmbedded(
                new Node().blueId(RuntimeBlueIds.PROCESS_EMBEDDED),
                "/product",
                "/payNote");
        materializeCanonicalPathMetadata(declaration);
        declaration.getProperties().get("paths").type(
                new Node().blueId(
                        BlueLanguageConstants.TEXT_TYPE_BLUE_ID));
        Node result = new Node().contracts(new Node().properties(
                "embedded", declaration));

        DeltaProjectionApplier.ColdProjectionRequiredException failure =
                assertThrows(
                        DeltaProjectionApplier
                                .ColdProjectionRequiredException.class,
                        () -> HybridResultFrontier.proveRetainedBindings(
                                result,
                                prepared.context,
                                prepared.owner));

        assertTrue(failure.getMessage().contains("/paths/$type"));
    }

    @Test
    void processEmbeddedAppendRejectsExplicitToImplicitPathMetadata() {
        Node priorDeclaration = processEmbedded(
                new Node().blueId(RuntimeBlueIds.PROCESS_EMBEDDED),
                "/product");
        materializeCanonicalPathMetadata(priorDeclaration);
        PreparedFixture prepared = prepared(
                new Node().contracts(new Node().properties(
                        "embedded", priorDeclaration)));
        Node result = new Node().contracts(new Node().properties(
                "embedded",
                processEmbedded(
                        new Node().blueId(RuntimeBlueIds.PROCESS_EMBEDDED),
                        "/product",
                        "/payNote")));

        VerifiedHybridResultFrontier frontier = HybridResultFrontier
                .proveRetainedBindings(
                        result, prepared.context, prepared.owner);

        assertTrue(frontier.processEmbeddedBoundaryBlueIdByPath().isEmpty());
    }

    @Test
    void processEmbeddedAppendRejectsExtraPathItemMetadata() {
        Node prior = new Node().contracts(new Node().properties(
                "embedded",
                processEmbedded(
                        new Node().blueId(RuntimeBlueIds.PROCESS_EMBEDDED),
                        "/product")));
        PreparedFixture prepared = prepared(prior);
        Node declaration = processEmbedded(
                new Node().blueId(RuntimeBlueIds.PROCESS_EMBEDDED),
                "/product",
                "/payNote");
        declaration.getProperties().get("paths").getItems().get(1)
                .name("forged-item-metadata");
        Node result = new Node().contracts(new Node().properties(
                "embedded", declaration));

        VerifiedHybridResultFrontier frontier = HybridResultFrontier
                .proveRetainedBindings(
                        result, prepared.context, prepared.owner);

        assertTrue(frontier.processEmbeddedBoundaryBlueIdByPath().isEmpty());
    }

    @Test
    void processEmbeddedAppendRejectsAdditionalContractPayload() {
        Node prior = new Node().contracts(new Node().properties(
                "embedded",
                processEmbedded(
                        new Node().blueId(RuntimeBlueIds.PROCESS_EMBEDDED),
                        "/product")));
        PreparedFixture prepared = prepared(prior);
        Node changedDeclaration = processEmbedded(
                new Node().blueId(RuntimeBlueIds.PROCESS_EMBEDDED),
                "/product",
                "/payNote");
        changedDeclaration.getProperties().put(
                "forged", new Node().value(true));
        Node result = new Node().contracts(new Node().properties(
                "embedded", changedDeclaration));

        VerifiedHybridResultFrontier frontier = HybridResultFrontier
                .proveRetainedBindings(
                        result, prepared.context, prepared.owner);

        assertTrue(frontier.processEmbeddedBoundaryBlueIdByPath().isEmpty());
    }

    @Test
    void arbitraryAddedChannelTypeCannotForgeImplicitTypeMaterialization() {
        Node prior = new Node().contracts(new Node().properties(
                "operation", new Node().properties(
                        "channel", new Node().value("customerChannel"))));
        PreparedFixture prepared = prepared(prior);
        String forgedTypeBlueId = blueId(new Node().properties(
                "kind", new Node().value("forged-channel-type")));
        Node forged = new Node().contracts(new Node().properties(
                "operation", new Node().properties(
                        "channel", new Node()
                                .type(new Node().blueId(forgedTypeBlueId))
                                .value("customerChannel"))));

        assertThrows(
                DeltaProjectionApplier.ColdProjectionRequiredException.class,
                () -> HybridResultFrontier.proveRetainedBindings(
                        forged, prepared.context, prepared.owner));
    }

    @Test
    void newSubtreeAcceptsOnlyAnAdmittedTypeHeader() {
        Node admittedType = new Node().properties(
                "kind", new Node().value("processor-marker"));
        String admittedTypeBlueId = blueId(admittedType);
        PreparedFixture prepared = prepared(
                new Node().properties("stable", new Node().value(1)),
                admittedType);
        Node result = new Node().properties(
                "stable", new Node().value(1),
                "checkpoint", new Node()
                        .type(new Node().blueId(admittedTypeBlueId))
                        .properties(
                                "entries",
                                new Node().properties(
                                        Collections
                                                .<String, Node>emptyMap())));

        VerifiedHybridResultFrontier frontier = HybridResultFrontier
                .proveRetainedBindings(
                        result, prepared.context, prepared.owner);

        assertEquals(
                admittedTypeBlueId,
                frontier.newSubtreeHeaderBlueIdByPath().get(
                        "/checkpoint/$type"));
        assertTrue(frontier.retainedBindingsRemainExact(result));
    }

    @Test
    void newSubtreeRejectsAnUnadmittedTypeHeader() {
        PreparedFixture prepared = prepared(
                new Node().properties("stable", new Node().value(1)));
        String unadmittedTypeBlueId = blueId(new Node().properties(
                "kind", new Node().value("unadmitted-marker")));
        Node result = new Node().properties(
                "stable", new Node().value(1),
                "checkpoint", new Node()
                        .type(new Node().blueId(unadmittedTypeBlueId))
                        .properties(
                                "entries",
                                new Node().properties(
                                        Collections
                                                .<String, Node>emptyMap())));

        DeltaProjectionApplier.ColdProjectionRequiredException failure =
                assertThrows(
                        DeltaProjectionApplier
                                .ColdProjectionRequiredException.class,
                        () -> HybridResultFrontier.proveRetainedBindings(
                                result,
                                prepared.context,
                                prepared.owner));

        assertTrue(failure.getMessage().contains("/checkpoint/$type"));
    }

    @Test
    void newRuntimeCheckpointIsOpaqueToSubscriptionTopology() {
        Node prior = new Node()
                .contracts(new Node().properties(
                        "stable", new Node().value(true)))
                .properties("counter", new Node().value(1));
        PreparedFixture prepared = prepared(prior);
        String domainBlueId = blueId(new Node().properties(
                "channel", new Node().value("customerChannel")));
        Node result = new Node()
                .contracts(new Node().properties(
                        "stable", new Node().value(true),
                        "checkpoint", runtimeCheckpoint(
                                domainBlueId,
                                new Node().properties(
                                        "event", new Node().value("first")))))
                .properties("counter", new Node().value(2));
        String resultingRootBlueId = blueId(result);

        VerifiedHybridResultFrontier frontier = HybridResultFrontier
                .proveRetainedBindings(
                        result, prepared.context, prepared.owner);

        assertEquals(
                blueId(result.getContracts().getProperties().get(
                        "checkpoint")),
                frontier.newRuntimeBoundaryBlueIdByPath().get(
                        "/$contracts/checkpoint"));
        assertTrue(frontier.retainedBindingsRemainExact(result));

        CoordinationCommitProjectionEvidence evidence =
                new CoordinationCommitProjectionEvidenceBuilder().build(
                        snapshot(
                                prepared.rootBlueId,
                                1L,
                                order(1L),
                                occurrence(
                                        "/",
                                        prepared.rootBlueId,
                                        "root-channel",
                                        0)),
                        frontier,
                        prepared.exactPriorRoot,
                        result,
                        resultingRootBlueId,
                        2L,
                        order(2L),
                        SubscriptionDelta.empty());

        assertEquals(resultingRootBlueId, evidence.resultingRootBlueId());
    }

    @Test
    void runtimeCheckpointMutationAfterProofIsRejected() {
        Node prior = new Node().contracts(new Node().properties(
                "stable", new Node().value(true)));
        PreparedFixture prepared = prepared(prior);
        Node subject = new Node().properties(
                "event", new Node().value("first"));
        Node result = new Node().contracts(new Node().properties(
                "stable", new Node().value(true),
                "checkpoint", runtimeCheckpoint(
                        blueId(new Node().value("domain")), subject)));
        VerifiedHybridResultFrontier frontier = HybridResultFrontier
                .proveRetainedBindings(
                        result, prepared.context, prepared.owner);

        subject.properties("event", new Node().value("forged"));

        assertFalse(frontier.retainedBindingsRemainExact(result));
    }

    @Test
    void runtimeCheckpointAtWrongPathIsRejected() {
        PreparedFixture prepared = prepared(
                new Node().properties("stable", new Node().value(true)));
        Node result = new Node().properties(
                "stable", new Node().value(true),
                "checkpoint", runtimeCheckpoint(null, null));

        assertThrows(
                DeltaProjectionApplier.ColdProjectionRequiredException.class,
                () -> HybridResultFrontier.proveRetainedBindings(
                        result, prepared.context, prepared.owner));
    }

    @Test
    void reservedCheckpointPathWithWrongTypeIsRejected() {
        PreparedFixture prepared = prepared(new Node().contracts(
                new Node().properties("stable", new Node().value(true))));
        Node wrongCheckpoint = runtimeCheckpoint(null, null)
                .type(new Node().blueId(RuntimeBlueIds.MARKER));
        Node result = new Node().contracts(new Node().properties(
                "stable", new Node().value(true),
                "checkpoint", wrongCheckpoint));

        assertThrows(
                DeltaProjectionApplier.ColdProjectionRequiredException.class,
                () -> HybridResultFrontier.proveRetainedBindings(
                        result, prepared.context, prepared.owner));
    }

    @Test
    void runtimeCheckpointWithSemanticContractsIsRejected() {
        PreparedFixture prepared = prepared(new Node().contracts(
                new Node().properties("stable", new Node().value(true))));
        Node wrongCheckpoint = runtimeCheckpoint(null, null)
                .contracts(new Node().properties(
                        "operation", new Node().value("forged")));
        Node result = new Node().contracts(new Node().properties(
                "stable", new Node().value(true),
                "checkpoint", wrongCheckpoint));

        assertThrows(
                DeltaProjectionApplier.ColdProjectionRequiredException.class,
                () -> HybridResultFrontier.proveRetainedBindings(
                        result, prepared.context, prepared.owner));
    }

    @Test
    void ordinaryNewTypedSubtreeDoesNotAuthorizeNestedPayloadReference() {
        Node admittedType = new Node().properties(
                "kind", new Node().value("ordinary-new-type"));
        String admittedTypeBlueId = blueId(admittedType);
        Node payload = new Node().properties(
                "value", new Node().value("retained elsewhere"));
        String payloadBlueId = blueId(payload);
        Node prior = new Node().properties(
                "definitions", new Node().properties(
                        "payload", payload),
                "stable", new Node().value(1));
        PreparedFixture prepared = prepared(prior, admittedType);
        String definitionsBlueId = blueId(
                prior.getProperties().get("definitions"));
        Node result = new Node().properties(
                "definitions", new Node().blueId(definitionsBlueId),
                "stable", new Node().value(1),
                "newValue", new Node()
                        .type(new Node().blueId(admittedTypeBlueId))
                        .properties(
                                "payload", new Node().blueId(
                                        payloadBlueId)));

        DeltaProjectionApplier.ColdProjectionRequiredException failure =
                assertThrows(
                        DeltaProjectionApplier
                                .ColdProjectionRequiredException.class,
                        () -> HybridResultFrontier.proveRetainedBindings(
                                result,
                                prepared.context,
                                prepared.owner));

        assertTrue(failure.getMessage().contains("/newValue/payload"));
    }

    private static Node runtimeCheckpoint(
            String domainBlueId, Node subject) {
        Node entries = new Node().properties(
                Collections.<String, Node>emptyMap());
        if (domainBlueId != null || subject != null) {
            entries.properties(
                    "customerChannel",
                    new Node().properties(
                            "domain", new Node().blueId(domainBlueId),
                            "subject", subject));
        }
        return new Node()
                .type(new Node().blueId(
                        RuntimeBlueIds.CHANNEL_EVENT_CHECKPOINT))
                .properties("entries", entries);
    }

    private static Node processEmbedded(
            Node exactType, String... paths) {
        List<Node> values = new ArrayList<Node>();
        for (String path : paths) {
            values.add(new Node().value(path));
        }
        return new Node()
                .description("Exact test Process Embedded declaration")
                .type(exactType)
                .properties("paths", new Node().items(values));
    }

    private static void materializeCanonicalPathMetadata(Node declaration) {
        Node paths = declaration.getProperties().get("paths");
        paths.type(new Node().blueId(
                        BlueLanguageConstants.LIST_TYPE_BLUE_ID))
                .itemType(new Node().blueId(
                        BlueLanguageConstants.TEXT_TYPE_BLUE_ID));
        for (Node item : paths.getItems()) {
            item.type(new Node().blueId(
                    BlueLanguageConstants.TEXT_TYPE_BLUE_ID));
        }
    }

    private static Node runtimeType(String blueId) {
        Node value = BlueRuntimeTypeRegistry.getDefault()
                .asProcessorSnapshotProvider()
                .fetchFirstByBlueId(blueId);
        assertNotNull(value, "published runtime type " + blueId);
        assertEquals(blueId, blueId(value));
        return value;
    }

    private static PreparedFixture prepared(
            Node suppliedRoot, Node... admittedProjectionValues) {
        Object owner = new Object();
        String rootBlueId = blueId(suppliedRoot);
        CoordinationFragmentInventory inventory =
                new CoordinationFragmentInventory(
                        CoordinationFragmentInventory.SCHEMA_VERSION,
                        CoordinationDocumentSplitter
                                .FRAGMENTATION_PROFILE_ID,
                        CoordinationDocumentSplitter
                                .EDGE_METADATA_SCHEMA_ID,
                        rootBlueId,
                        Collections.singletonList(rootBlueId),
                        Collections.singletonList(new FragmentRootRecord(
                                rootBlueId,
                                CoordinationDocumentSplitter
                                        .FragmentRootKind.DOCUMENT,
                                "/")),
                        Collections.emptyList(),
                        Collections.emptyList());
        ExactNodeHandle root = ExactNodeHandle.adoptAndVerify(
                rootBlueId, suppliedRoot, owner);
        RequestDigestMemo digests = new RequestDigestMemo();
        digests.bindVerified(suppliedRoot, rootBlueId);
        RetainedReferenceIndex retained =
                RetainedReferenceIndex.scanOnce(root, owner, digests);
        List<ExactNodeHandle> admitted =
                new ArrayList<ExactNodeHandle>();
        for (Node value : admittedProjectionValues) {
            admitted.add(ExactNodeHandle.adoptAndVerify(
                    blueId(value), value, owner));
        }
        RetainedReferenceIndex projection = admitted.isEmpty()
                ? retained
                : retained.withVerifiedHandles(admitted, owner);
        PreparedRootExecutionContext context =
                new PreparedRootExecutionContext(
                        "session",
                        0L,
                        inventory,
                        root,
                        retained,
                        projection,
                        Collections.emptyMap(),
                        owner);
        return new PreparedFixture(
                owner, rootBlueId, suppliedRoot, context);
    }

    private static CoordinationSubscriptionSnapshot snapshot(
            String rootBlueId,
            long revision,
            ExternalOrderKey frontier,
            CoordinationSubscriptionOccurrence... occurrences) {
        return new CoordinationSubscriptionSnapshot(
                "language-runtime",
                "coordination-runtime",
                rootBlueId,
                revision,
                frontier,
                Arrays.asList(occurrences),
                Collections.emptyMap(),
                Collections.emptySet());
    }

    private static CoordinationSubscriptionOccurrence occurrence(
            String scopePath,
            String scopeBlueId,
            String channelKey,
            int order) {
        return new CoordinationSubscriptionOccurrence(
                scopePath,
                scopeBlueId,
                "/",
                "/".equals(scopePath)
                        ? CoordinationSubscriptionOccurrence.Origin.ROOT
                        : CoordinationSubscriptionOccurrence.Origin.EXPLICIT,
                "/".equals(scopePath) ? null : scopePath,
                null,
                null,
                channelKey,
                Collections.singletonList("source-" + order),
                "type-" + order,
                order,
                "checkpoint-" + order,
                "header-" + order,
                Collections.singletonMap("field", "field-" + order),
                Collections.singletonList("key-" + order),
                Long.valueOf(1L),
                order(1L),
                null,
                ExternalChannelDependencySnapshot.none());
    }

    private static String blueId(Node node) {
        return DirectBlueIdCalculator.calculateBlueId(node);
    }

    private static ExternalOrderKey order(long value) {
        return ExternalOrderKey.of(
                Collections.singletonList(BigInteger.valueOf(value)));
    }

    private static final class PreparedFixture {
        private final Object owner;
        private final String rootBlueId;
        private final Node exactPriorRoot;
        private final PreparedRootExecutionContext context;

        private PreparedFixture(
                Object owner,
                String rootBlueId,
                Node exactPriorRoot,
                PreparedRootExecutionContext context) {
            this.owner = owner;
            this.rootBlueId = rootBlueId;
            this.exactPriorRoot = exactPriorRoot;
            this.context = context;
        }
    }
}
