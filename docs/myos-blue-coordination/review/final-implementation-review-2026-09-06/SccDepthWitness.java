package blue.language.processor.closure;
import java.util.*;
public class SccDepthWitness {
 public static void main(String[] args) {
  for(int count:new int[]{1000,2000,4000,8000,10000}) {
   var docs=new ArrayList<DocumentId>(); var rows=new ArrayList<ManagedOccurrenceBinding>();
   for(int i=0;i<count;i++) docs.add(new DocumentId(String.format("member-%05d",i)));
   for(int i=1;i<count;i++) rows.add(ManagedDocumentGraphTest.binding(i,docs.get(i-1),docs.get(i),true));
   var graph=ManagedDocumentGraph.fromBindings(docs,rows);
   try { System.out.println(count+" chain members: "+new SccPartitioner().partition(graph).size()+" components"); }
   catch(StackOverflowError failure) { System.out.println(count+" chain members: StackOverflowError at "+failure.getStackTrace()[0]); }
  }
 }
}
