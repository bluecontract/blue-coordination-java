package blue.coordination.sdk;

import blue.coordination.api.DocumentId;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;

class SdkStaticSharedAdmissionTest {
    @Test
    void staticAdmissionCapturesOtherParentsOfAnExistingChild() {
        // given
        try (BlueCoordination blue = BlueCoordination.builder().contentDerivedDocumentIds().build()) {
            DocumentHandle child = blue.documents().admitStaticProcessEmbedded("counter: 0").document("root");
            String childId = child.id().value();
            String childHead = child.snapshot().blueId();
            DocumentHandle first = parent(blue, "shared-static-first", child.snapshot().exact());
            String firstHead = first.snapshot().blueId();
            List<ManagedEpochReceipt> childHistory = blue.advanced()
                    .auditManagedEpochs(DocumentId.of(childId));
            // when
            DocumentHandle second = parent(blue, "shared-static-second", child.snapshot().exact());
            DocumentHandle outer = parent(blue, "shared-static-outer", second.snapshot().exact());
            // then
            assertEquals(childHead, child.snapshot().blueId());
            assertEquals(firstHead, first.snapshot().blueId());
            assertEquals(childHistory.stream().map(ManagedEpochReceipt::receiptIdentity).toList(),
                    blue.advanced().auditManagedEpochs(DocumentId.of(childId))
                            .stream().map(ManagedEpochReceipt::receiptIdentity).toList());
            assertEquals(childHead, second.snapshot().valueAt("/child").blueId());
            assertEquals(second.snapshot().blueId(), outer.snapshot().valueAt("/child").blueId());
        }
    }

    private static DocumentHandle parent(BlueCoordination blue, String id, ExactBlueValue child) {
        String source = """
                name: %s
                child: %s
                contracts:
                  embedded:
                    type: Process Embedded
                    paths: [/child]
                """.formatted(id, child.json());
        return blue.documents().admitStaticProcessEmbedded(source).publicRoots().get(0);
    }
}
