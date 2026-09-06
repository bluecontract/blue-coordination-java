package blue.coordination.external;
import blue.language.processor.closure.*;
import java.util.*;

public class PolicyWitness {
  public static void main(String[] args) {
    var limits = FrozenNodeEvidenceCodec.Limits.defaults();
    try (var f = new CanonicalSourceHistoryTest.Fixture()) {
      var source = f.authored("""
          name: source with an expensive external computation
          counter: 0
          contracts:
            ingress:
              type: Coordination/Timeline Channel
              timeline: {type: MyOS/MyOS Timeline, timelineId: timeline}
              actor: {type: MyOS/Principal Actor, accountId: account}
            update:
              type: Coordination/Sequential Workflow
              channel: ingress
              steps:
                - type: Coordination/Compute
                  do:
                    - $appendChange: {op: replace, path: /counter, val: {$size: {$split: {text: {$event: /message}, separator: ','}}}}
                    - $return: true
                - type: Coordination/Trigger Event
                  event: agreement-updated
          """, Map.of());
      var entry = CanonicalSourceHistoryTest.input("item,".repeat(3000), 15, "account");
      var producerCore = new CoordinationCore(f.processor, f.core.environment(),
          ClosureEvidenceFactory.executionPolicy(5000, Map.of(), "independent-producer-5000"));
      var history = new CanonicalSourceHistory(producerCore);
      var request = new CanonicalSourceHistory.Request(source.documentId(), entry.order());
      var birth = (CanonicalSourceHistory.Step) history.prepareNext(request, history.start(source.documentId()),
          f.evidence(source, List.of(entry), 20), f.blobs::put, limits);
      var before = f.restore(birth.after().successfulView().orElseThrow());
      var initialized = (CoordinationCore.PreparedOperation) birth.evaluation();
      if (initialized.result().status() != blue.language.processor.ProcessorStatus.SUCCESS) throw new AssertionError("Producer must have a valid own-policy initialization");
      System.out.println("SOURCE=" + source.documentId() + " init=" + initialized.result().status() + " gas=" + initialized.result().totalGas()
          + " policy=" + initialized.invocation().executionPolicy().identity() + " operation=" + initialized.operationId());
      var observer = f.observer("policy witness observer", birth.after().successfulView().orElseThrow());
      var base = f.observerEvidence(observer, before, List.of(entry), List.of(), Optional.empty());
      var predecessor = Map.of(source.documentId(), birth.after().semanticPredecessor().orElseThrow());
      var originalBasis = SourceExecutionBasis.identity(source.documentId(), producerCore.environment(), producerCore.executionPolicy());
      System.out.println("EXPECTED SOURCE BASIS=" + originalBasis + " sharedLimit=" + producerCore.executionPolicy().sharedLimit());
      var uncached = new CoordinationCore.EvaluationEvidence(base.snapshot(), base.relevantTimelines(), base.prefixes(),
          base.handledThrough(), List.of(), predecessor).withExpectedSourceBases(Map.of(source.documentId(), originalBasis));
      var fresh = f.core.evaluate(new CoordinationCore.WorkIntent(observer.before().documentId(), CoordinationCore.OperationKind.EXTERNAL_INPUT), uncached);
      System.out.println("NO CACHE: " + fresh.getClass());
      if (fresh instanceof CoordinationCore.PreparedOperations groups) for (var group : groups.operations())
        System.out.println("owners=" + group.ownedLineages() + " status=" + group.result().status() + " gas=" + group.result().totalGas() + " operation=" + group.operationId() + " policy=" + group.invocation().executionPolicy().identity() + " expectedBasisMatches=" + originalBasis.equals(SourceExecutionBasis.identity(source.documentId(), group.invocation().environment(), group.invocation().executionPolicy())));
      var sourceEvidence = new CoordinationCore.EvaluationEvidence(f.evidence(before, List.of(entry), 20).snapshot(), base.relevantTimelines(), base.prefixes(),
          Optional.empty(), List.of(), predecessor);
      var produced = (CoordinationCore.PreparedOperations) producerCore.evaluate(new CoordinationCore.WorkIntent(source.documentId(), CoordinationCore.OperationKind.EXTERNAL_INPUT), sourceEvidence);
      var sourceResult = produced.operations().get(0);
      if (sourceResult.result().status() != blue.language.processor.ProcessorStatus.GAS_LIMIT_EXCEEDED) throw new AssertionError("Expected authentic producer budget failure");
      System.out.println("AUTHENTIC PRODUCER: " + sourceResult.result().status() + " gas=" + sourceResult.result().totalGas() + " operation=" + sourceResult.operationId() + " policy=" + sourceResult.invocation().executionPolicy().identity());
      var encoded = OperationReceiptCodec.encode(sourceResult, f.blobs::put, limits);
      var failure = OperationReceiptCodec.restoreSourceFailure(encoded.receiptIdentity(), f.blobs::get, limits);
      var cached = new CoordinationCore.EvaluationEvidence(base.snapshot(), base.relevantTimelines(), base.prefixes(), base.handledThrough(), List.of(),
          predecessor, List.of(), Map.of(), List.of(failure)).withExpectedSourceBases(Map.of(source.documentId(), originalBasis));
      var warm = f.core.evaluate(new CoordinationCore.WorkIntent(observer.before().documentId(), CoordinationCore.OperationKind.EXTERNAL_INPUT), cached);
      System.out.println("WITH AUTHENTIC CACHE: " + warm.getClass());
      if (!(fresh instanceof CoordinationCore.PreparedOperations) || !(warm instanceof CoordinationCore.MetadataProgress)) throw new AssertionError("Witness behavior changed");
      if (warm instanceof CoordinationCore.PreparedOperations groups) for (var group : groups.operations()) System.out.println("warmOwners=" + group.ownedLineages() + " status=" + group.result().status());
    }
  }
}
