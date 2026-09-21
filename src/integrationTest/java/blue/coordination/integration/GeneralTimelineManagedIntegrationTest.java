package blue.coordination.integration;

import blue.coordination.processor.*;
import blue.coordination.sdk.*;
import blue.coordination.internal.RootedCalculationFixture;
import blue.language.processor.closure.BlueClosureContracts;
import blue.language.runtime.BlueLanguage;
import blue.language.codec.BlueFormat;
import blue.language.model.Node;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.processor.*;
import blue.language.processor.registry.*;
import blue.language.registry.BlueCoreTypeRegistry;
import blue.language.provider.SequentialNodeProvider;
import blue.repo.BlueRepository;
import java.util.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.junit.jupiter.api.Assertions.*;

/** The lower Contracts path is an independent test oracle, never a host execution path. */
final class GeneralTimelineManagedIntegrationTest {
    @ParameterizedTest @CsvSource({"false,SINGLE", "true,SINGLE", "false,MULTIPLE", "false,COMPOSITE"})
    void managedStageMatchesRegisteredContractsReference(boolean suppliedInput, String shape) {
        // given
        var repository = BlueRepository.current();
        var aliases = new LinkedHashMap<String, String>(repository.preprocessingAliases());
        aliases.putAll(RuntimeTypeAliases.AGGREGATE_NAME_TO_BLUE_ID);
        var provider = new SequentialNodeProvider(BlueCoreTypeRegistry.INSTANCE.verifiedProvider(),
                BlueRuntimeTypeRegistry.getDefault().asProcessorSnapshotProvider(), repository.nodeProvider());
        try (var language = BlueLanguage.builder().nodeProvider(provider).preprocessingAliases(aliases).environmentImports(aliases).build();
             var blue = BlueCoordination.builder().contentDerivedDocumentIds().build()) {
            var registry = CoordinationProcessors.registerTimelineSubtype(
                    CoordinationProcessors.configure(ContractProcessorRegistryBuilder.create().registerDefaults(),
                            CoordinationProcessorOptions.builder().language(language).build()),
                    blue.repo.myos.MyOSTimelineChannel.class).build();
            try (var contracts = BlueContracts.builder(language.processing()).runtimeRegistry(registry).build();
                 var processor = DocumentProcessor.builder().runtimeAccess(contracts.runtimeAccess()).runtimeRegistry(registry)
                         .runtimeRegistryIdentity(registry.generationIdentity()).build()) {
                var timeline = blue.timelines().register("general/reference", "alice");
                var root = blue.documents().admitStaticProcessEmbedded(document(shape), ActivationPolicy.importFullHistory()).document("root");
                // Freeze the exact managed initialization, so only execution is compared.
                Node initial = language.codec().parseBlueIdInput(root.snapshot().exact().json(), BlueFormat.JSON);
                var exact = blue.values().yaml("""
                        type: Coordination/Timeline Entry
                        timeline: {type: MyOS/MyOS Timeline, timelineId: general/reference}
                        actor: {type: MyOS/Principal Actor, accountId: alice}
                        timestamp: 1700000000000000
                        message: {kind: CreditRecorded, amount: 7}
                        """);
                Node event = language.codec().parseBlueIdInput(exact.json(), BlueFormat.JSON);
                var coverage = contracts.subscriptionSurfaceProjection().projectInitial(initial, 0L, ExternalOrderKey.of(List.of(0L)));
                var order = ExternalOrderKey.of(List.of(1700000000000000L,
                        DirectBlueIdCalculator.calculateBlueId(event.getProperties().get("timeline")), exact.blueId()));
                var plan = contracts.currentRootDeliveryPlanDeriver(0L, order, coverage.added()).derive(initial, event);
                // when
                var reference = contracts.processForPlatformCommit(initial, event,
                        PlatformProcessInvocation.builder().deliveryPlan(plan).nodeProvider(provider).build()).processResult();
                var input = blue.events().from(timeline).exact(exact).submit();
                var frozenInput = new RootedCalculationFixture(blue.advanced().rawEngine()).capture(root.id(), input.blueId(), null);
                var managedReference = new BlueClosureContracts(processor).processClosure(frozenInput).processResult();
                var stage = suppliedInput ? blue.processing().processStage(root, input) : blue.processing().processNextStage(root);
                // then
                assertEquals(shape.equals("SINGLE") ? 1 : 2, plan.deliveries().size());
                assertEquals(ProcessingStageResult.Disposition.COMPLETED, stage.disposition());
                assertEquals(EntryDisposition.APPLIED, stage.entry(input).disposition());
                assertEquals(shape.equals("MULTIPLE") ? 9 : 7, root.snapshot().longAt("/counter"));
                assertEquals(DirectBlueIdCalculator.calculateBlueId(reference.document()), root.snapshot().blueId());
                var terminal = blue.advanced().closureExecution(stage.entry(input).closures().get(0).closureId()).orElseThrow();
                // Standalone document execution excludes closure scheduling/finalization gas.
                // Compare the entire managed scope against a separate processor using frozen input.
                assertEquals(managedReference.totalGas(), stage.entry(input).stats().gas());
                assertEquals(managedReference.gasTraceIdentity(), terminal.gasTraceIdentity());
                assertEquals(managedReference.checkpointWritesIdentity(), terminal.checkpointWritesIdentity());
                assertEquals(managedReference.publicEventsIdentity(), terminal.publicEventsIdentity());
                assertEquals(managedReference.outputClosureIdentity(), terminal.outputClosureIdentity());
                System.out.println("GENERAL_REFERENCE documentGas=" + reference.totalGas() + " managedGas=" + terminal.totalGas()
                        + " gasTrace=" + terminal.gasTraceIdentity() + " head=" + root.snapshot().blueId());
                assertTrue(stage.entry(input).publicEvents().isEmpty());
                assertEquals(List.of(root.id()), stage.resultOwners());
                assertEquals(exact.blueId(), blue.advanced().auditTimelineEntry(input.blueId()).orElseThrow().exact().blueId());
                assertEquals(ProcessingStageResult.Disposition.NO_WORK, blue.processing().processNextStage(root).disposition());
            }
        }
    }
    private static String document(String shape) {
        String single = """
                counter: 0
                contracts:
                  owner:
                    type: Coordination/Timeline Channel
                    timeline: {type: MyOS/MyOS Timeline, timelineId: general/reference}
                    actor: {type: MyOS/Principal Actor, accountId: alice}
                  onCredit:
                    type: Coordination/Sequential Workflow
                    channel: owner
                    event: {message: {kind: CreditRecorded}}
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendChange:
                              op: replace
                              path: /counter
                              val: {$event: /message/amount}
                          - $return: true
                """;
        if (shape.equals("SINGLE")) return single;
        if (shape.equals("MULTIPLE")) return single + """
                  second:
                    type: Coordination/Timeline Channel
                    timeline: {type: MyOS/MyOS Timeline, timelineId: general/reference}
                    actor: {type: MyOS/Principal Actor, accountId: alice}
                  onSecond:
                    type: Coordination/Sequential Workflow
                    channel: second
                    event: {message: {kind: CreditRecorded}}
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendChange: {op: replace, path: /counter, val: 9}
                          - $return: true
                """;
        return single.replace("channel: owner", "channel: combined") + """
                  combined:
                    type: Coordination/Composite Timeline Channel
                    channels: [owner]
                """;
    }
}
