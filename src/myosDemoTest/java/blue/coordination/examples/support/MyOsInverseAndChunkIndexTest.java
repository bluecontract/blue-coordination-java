package blue.coordination.examples.support;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class MyOsInverseAndChunkIndexTest {

    @Test
    void shouldMaintainExactBidirectionalTimelineMembership() {
        // given
        MyOsTimelineDocumentIndex index = new MyOsTimelineDocumentIndex();
        MyOsDocumentIdentity root = identity("root");
        MyOsDocumentIdentity emb = identity("emb");
        MyOsTimelineBinding alice = new MyOsTimelineBinding(
                "timeline-a", "actor-a");
        MyOsTimelineBinding bob = new MyOsTimelineBinding(
                "timeline-b", "actor-b");

        // when
        index.replaceDocumentBindings(root, Set.of(alice, bob));
        index.replaceDocumentBindings(emb, Set.of(alice));
        index.verifySymmetry();

        assertEquals(Set.of(root, emb), index.documents(alice));
        assertEquals(Set.of(alice, bob), index.timelines(root));
        index.replaceDocumentBindings(root, Set.of(bob));
        index.verifySymmetry();

        // then
        assertEquals(Set.of(emb), index.documents(alice));
        assertEquals(Set.of(bob), index.timelines(root));
    }

    @Test
    void shouldCopyIndexWithoutSharingMutableMembership() {
        // given
        MyOsTimelineDocumentIndex source = new MyOsTimelineDocumentIndex();
        MyOsDocumentIdentity root = identity("root");
        MyOsTimelineBinding alice = new MyOsTimelineBinding(
                "timeline-a", "actor-a");
        MyOsTimelineBinding bob = new MyOsTimelineBinding(
                "timeline-b", "actor-b");
        source.replaceDocumentBindings(root, Set.of(alice));
        MyOsTimelineDocumentIndex branch = source.copy();

        // when
        branch.replaceDocumentBindings(root, Set.of(bob));

        // then
        assertEquals(Set.of(alice), source.timelines(root));
        assertEquals(Set.of(bob), branch.timelines(root));
        source.verifySymmetry();
        branch.verifySymmetry();
    }

    private static MyOsDocumentIdentity identity(String key) {
        return new MyOsDocumentIdentity("myos-demo/" + key, "initial-" + key);
    }
}
