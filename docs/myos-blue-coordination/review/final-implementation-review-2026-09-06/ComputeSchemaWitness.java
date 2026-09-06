package blue.coordination.external;

import blue.language.processor.closure.FrozenNodeEvidenceCodec;
import java.util.*;

public class ComputeSchemaWitness {
 public static void main(String[] args) {
  new ComputeAuthoredOutputFailureTest().dynamicallyInvalidEventSettlesSemanticFailureAndRollsBackEarlierBufferedPatch();
  System.out.println("Existing invalid-BlueId control: PASS (RuntimeFatal, rollback, runtime gas, no event)");
  for (String key : List.of("required", "uniqueItems", "minItems", "maxLength", "minimum")) {
   try (var f = new CanonicalSourceHistoryTest.Fixture()) {
    var authored = f.authored("""
      name: invalid computed schema
      counter: 0
      contracts:
        input:
          type: Coordination/Timeline Channel
          timeline: {type: MyOS/MyOS Timeline, timelineId: timeline}
          actor: {type: MyOS/Principal Actor, accountId: account}
        run:
          type: Coordination/Sequential Workflow
          channel: input
          steps:
            - type: Coordination/Compute
              do:
                - $appendChange: {op: replace, path: /counter, val: 99}
                - $appendEvent:
                    $objectSet:
                      object: {$emptyObject: true}
                      key: schema
                      val:
                        $objectSet:
                          object: {$emptyObject: true}
                          key: %s
                          val: "bogus"
      """.formatted(key), Map.of());
    var birth = (CoordinationCore.PreparedOperation) f.core.evaluate(
      new CoordinationCore.WorkIntent(authored.documentId(), CoordinationCore.OperationKind.INITIALIZATION), f.evidence(authored,List.of(),20));
    var receipt=OperationReceiptCodec.encode(birth,f.blobs::put,FrozenNodeEvidenceCodec.Limits.defaults());
    var before=OperationReceiptCodec.restoreState(receipt.receiptIdentity(),authored.documentId(),true,0,f.blobs::get,FrozenNodeEvidenceCodec.Limits.defaults());
    var input=CanonicalSourceHistoryTest.input("execute invalid output",15,"account");
    try {
     var attempt=f.core.evaluate(new CoordinationCore.WorkIntent(before.documentId(),CoordinationCore.OperationKind.EXTERNAL_INPUT),f.evidence(before,List.of(input),20));
     System.out.println(key+": RESULT "+attempt.getClass());
     if(attempt instanceof CoordinationCore.PreparedOperations a) for(var failed:a.operations()) System.out.println(" status="+failed.result().status()+" gas="+failed.result().totalGas()+" unchanged="+before.blueId().equals(failed.projections().get(0).afterBlueId())+" events="+failed.result().events().size());
    } catch(Throwable t) {
     System.out.println(key+": ESCAPED "+t.getClass()+" "+t.getMessage());
     Throwable c=t; while(c.getCause()!=null) c=c.getCause();
     System.out.println(" rootCause="+c+" at "+c.getStackTrace()[0]);
     for(var frame:t.getStackTrace()) if(frame.getClassName().contains("SemanticOutputBoundary") || frame.getClassName().contains("ComputeStepExecutor")) System.out.println(" boundary="+frame);
     try {
      f.core.evaluate(new CoordinationCore.WorkIntent(before.documentId(),CoordinationCore.OperationKind.EXTERNAL_INPUT),f.evidence(before,List.of(input),20));
      throw new AssertionError("Identical retry unexpectedly succeeded");
     } catch(blue.language.processor.UnclassifiedProcessingException repeated) { System.out.println(" identical retry: same operational failure; no prepared terminal result"); }
    }
   }
  }
 }
}
