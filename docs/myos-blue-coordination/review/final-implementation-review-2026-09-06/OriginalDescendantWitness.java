package blue.coordination.external;
import blue.language.processor.closure.*;
import java.util.*;

public class OriginalDescendantWitness {
  static final FrozenNodeEvidenceCodec.Limits L = FrozenNodeEvidenceCodec.Limits.defaults();
  static CoordinationCore.EvaluationEvidence evidence(List<ManagedDocumentSnapshot> docs, List<ManagedOccurrenceBinding> rows,
      List<CoordinationCore.TimelineInput> entries, Map<DocumentId,String> predecessors) {
    Map<DocumentId,ManagedDocumentSnapshot> byId=new TreeMap<>();docs.forEach(d->byId.put(d.documentId(),d));
    var components = new SccPartitioner().partition(ManagedDocumentGraph.fromBindings(byId.keySet(),rows)).stream()
        .map(ids->ClosureEvidenceFactory.acyclicComponent(byId.get(ids.get(0)))).toList();
    return new CoordinationCore.EvaluationEvidence(ClosureEvidenceFactory.affectedClosure(0, docs, rows, components,
        docs.stream().map(ManagedDocumentSnapshot::documentId).toList()), entries.isEmpty()?Set.of():Set.of("timeline"),
        entries.isEmpty()?List.of():List.of(new CoordinationCore.TimelinePrefix("timeline",100,entries)), Optional.empty(),List.of(),predecessors);
  }
  public static void main(String[] args) {
    try (var f = new CanonicalSourceHistoryTest.Fixture()) {
      var y = f.source(); var e10=CanonicalSourceHistoryTest.input("advance Y",10,"account");
      var f5=CanonicalSourceHistoryTest.input("frontier before advance",5,"other");
      var e30=CanonicalSourceHistoryTest.input("X creates Y",30,"creator");
      var yh=new CanonicalSourceHistory(f.core);
      var yfull=f.prepare(yh,y,e30.order(),List.of(e10,e30),100);
      var yfront=f.prepare(yh,y,f5.order(),List.of(e10),100);
      var yhead=f.restore(yfull.boundary().cursor().successfulView().orElseThrow());
      var xa=f.authored("""
          name: independent X attaches at its own original frontier
          counter: -1
          contracts:
            ingress:
              type: Coordination/Timeline Channel
              timeline: {type: MyOS/MyOS Timeline, timelineId: timeline}
              actor: {type: MyOS/Principal Actor, accountId: creator}
            create:
              type: Coordination/Sequential Workflow
              channel: ingress
              order: 0
              steps:
                - type: Coordination/Update Document
                  changeset: [{op: add, path: /contracts/embedded, val: {type: Process Embedded, paths: [/child]}}]
            read:
              type: Coordination/Sequential Workflow
              channel: ingress
              order: 1
              steps:
                - type: Coordination/Compute
                  do:
                    - $appendChange: {op: replace, path: /counter, val: {$document: /child/counter}}
                    - $return: true
                - type: Coordination/Trigger Event
                  event: agreement-updated
          """,Map.of("child",yhead.blueId()));
      var hist=new CanonicalSourceHistory(f.core);
      var xr=new CanonicalSourceHistory.Request(xa.documentId(),e30.order());
      var xb=(CanonicalSourceHistory.Step)hist.prepareNext(xr,hist.start(xa.documentId()),evidence(List.of(xa),List.of(),List.of(),Map.of()),f.blobs::put,L);
      var x=f.restore(xb.after().successfulView().orElseThrow());
      var xmetaEvidence=evidence(List.of(x),List.of(),List.of(e10,e30),Map.of(x.documentId(),xb.after().semanticPredecessor().orElseThrow()));
      var xmetaAdmission=OriginalSourceInputTestSupport.admit(f.core,xb.after(),xr,xmetaEvidence,SameOriginAttachmentPolicy.empty(),f.blobs);
      var xm=(CanonicalSourceHistory.Step)hist.prepareNext(xmetaAdmission.request(),xb.after(),xmetaAdmission.evidence(),f.blobs::put,L);
      if (!(xm.evaluation() instanceof CoordinationCore.MetadataProgress)) throw new AssertionError("E10 is metadata for X");
      var xy=ManagedOccurrenceBinding.derived(f.core.environment().managedBindingPolicyIdentity(),x.documentId(),ScopeAddress.embedded("/child",1),y.documentId(),yhead.blueId(),false,null);
      var choice=new SameOriginAttachmentPolicy.Selection(SameOriginAttachmentPolicy.Mode.FROM_FRONTIER,x.documentId(),xy.occurrenceIdentity(),y.documentId(),yhead.blueId(),f5.order());
      var policy=new SameOriginAttachmentPolicy(List.of(choice));
      var frontier=SourceFrontierSelection.fromBoundary(choice,yfront.boundary(),yfront.steps().get(0).receiptIdentity(),f.blobs::get,L);
      var xe=evidence(List.of(x,yhead),List.of(xy),List.of(e10,e30),Map.of(x.documentId(),xm.after().semanticPredecessor().orElseThrow()));
      var admittedX=OriginalSourceInputTestSupport.admit(f.core,xm.after(),xr,xe.withSourceFrontiers(List.of(frontier)),policy,f.blobs);
      var authentic=(CanonicalSourceHistory.Step)hist.prepareNext(admittedX.request(),xm.after(),admittedX.evidence(),f.blobs::put,L);
      var authenticX=((CoordinationCore.PreparedOperations)authentic.evaluation()).operations().get(0);
      if (!java.math.BigInteger.ZERO.equals(authenticX.projections().get(0).result().document().get("/counter"))) throw new AssertionError("Original X frontier selects zero");
      System.out.println("AUTHENTIC X status="+authenticX.result().status()+" counter="+authenticX.projections().get(0).result().document().get("/counter")+" gas="+authenticX.result().totalGas()+" operation="+authenticX.operationId());
      var aa=f.initializingObserver("A reconstructs X",x.blueId(),"agreement-updated");
      var ax=f.binding(aa,x);
      var ar=new CanonicalSourceHistory.Request(aa.documentId(),e30.order());
      var ab=(CanonicalSourceHistory.Step)hist.prepareNext(ar,hist.start(aa.documentId()),evidence(List.of(aa,x),List.of(ax),List.of(),Map.of(x.documentId(),xb.after().semanticPredecessor().orElseThrow())),f.blobs::put,L);
      var a=f.restore(ab.after().successfulView().orElseThrow());
      var abop=(CoordinationCore.PreparedOperation)ab.evaluation();
      var actualRows=new ArrayList<>(abop.ownedOccurrenceBindings());actualRows.add(xy);
      var ae=evidence(List.of(a,x,yhead),actualRows,List.of(e10,e30),Map.of(a.documentId(),ab.after().semanticPredecessor().orElseThrow(),x.documentId(),xb.after().semanticPredecessor().orElseThrow()));
      var ametaAdmission=OriginalSourceInputTestSupport.admit(f.core,ab.after(),ar,ae,SameOriginAttachmentPolicy.empty(),f.blobs);
      var am=(CanonicalSourceHistory.Step)hist.prepareNext(ametaAdmission.request(),ab.after(),ametaAdmission.evidence(),f.blobs::put,L);
      if (!(am.evaluation() instanceof CoordinationCore.MetadataProgress)) throw new AssertionError("E10 is metadata for A");
      var admittedA=OriginalSourceInputTestSupport.admit(f.core,am.after(),ar,ae,SameOriginAttachmentPolicy.empty(),f.blobs);
      System.out.println("A admission owners="+admittedA.admission().sourceBases().keySet()+"; X has separate original admission="+admittedX.admission().identity());
      var result=hist.prepareNext(admittedA.request(),am.after(),admittedA.evidence(),f.blobs::put,L);
      System.out.println("A HISTORICAL RESULT="+result.getClass());
      var groups=(CoordinationCore.PreparedOperations)((CanonicalSourceHistory.Step)result).evaluation();
      var substitutedX=groups.operations().stream().filter(g->g.ownedLineages().contains(x.documentId())).findFirst().orElseThrow();
      Object counter=substitutedX.projections().get(0).result().document().get("/counter");
      System.out.println("FRESH X status="+substitutedX.result().status()+" counter="+counter+" gas="+substitutedX.result().totalGas()+" operation="+substitutedX.operationId());
      if (!java.math.BigInteger.valueOf(5).equals(counter) || substitutedX.operationId().equals(authenticX.operationId())) throw new AssertionError("Witness behavior changed");
      var coldX=OperationReceiptCodec.restoreSourceProgram(authentic.receiptIdentity(),f.blobs::get,L);
      var aBase=admittedA.evidence();
      var warmEvidence=new CoordinationCore.EvaluationEvidence(aBase.snapshot(),aBase.relevantTimelines(),aBase.prefixes(),aBase.handledThrough(),
          aBase.fences(),aBase.precedingOperations(),List.of(coldX)).withSourceInputAdmissions(aBase.sourceInputAdmissions())
          .withExpectedSourceBases(Map.of(x.documentId(),xb.after().basisIdentity()));
      var warm=hist.prepareNext(admittedA.request(),am.after(),warmEvidence,f.blobs::put,L);
      System.out.println("A WITH AUTHENTIC X CACHE="+warm.getClass());
      if(warm instanceof CanonicalSourceHistory.Step step) {
        var warmGroups=(CoordinationCore.PreparedOperations)step.evaluation();
        System.out.println("warm owners="+warmGroups.operations().stream().map(CoordinationCore.PreparedGroupOperation::ownedLineages).toList()+" consumedX="+warmGroups.operations().get(0).consumedSourceOperations());
      }
    }
  }
}
