package blue.coordination.examples.documents;

import java.util.Objects;

/** Documents for the required late Root -> Emb1 -> Emb2 topology proof. */
public final class NestedTopologyDocuments {

    private NestedTopologyDocuments() {
    }

    /** Deep logical document admitted and processed before either parent. */
    public static final String EMB2 = """
        name: Late Attached Emb2
        counter: 0
        contracts:
          ownerChannel:
            description: Alice's nested-document Timeline
            type: Coordination/Timeline Channel
            timeline:
              type: MyOS/MyOS Timeline
              timelineId: examples/nested/alice
            actor:
              type: MyOS/Principal Actor
              accountId: alice
          increment:
            description: Increment this logical document
            type: Coordination/Sequential Workflow Operation
            channel: ownerChannel
            request:
              amount:
                type: Integer
            steps:
              - name: Increment
                type: Coordination/Compute
                do:
                  - $appendChange:
                      op: replace
                      path: /counter
                      val:
                        $add:
                          - $document: /counter
                          - $binding: event/message/request/amount
                  - $return: true
        """;

    /**
     * The reference is explicit lineage evidence. The environment replaces it
     * with the managed child's current exact Root before parent initialization.
     */
    public static String emb1Linking(String emb2InitialBlueId) {
        return """
            name: Late Attached Emb1
            emb2:
              blueId: %s
            contracts:
              embedded:
                description: Process the explicitly linked Emb2 scope
                type: Process Embedded
                paths:
                  - /emb2
            """.formatted(requireBlueId(emb2InitialBlueId));
    }

    /** Transitive parent link; Emb1 already contains its managed Emb2 link. */
    public static String rootLinking(String emb1InitialBlueId) {
        return """
            name: Late Attached Root
            emb1:
              blueId: %s
            contracts:
              embedded:
                description: Process the explicitly linked Emb1 graph
                type: Process Embedded
                paths:
                  - /emb1
            """.formatted(requireBlueId(emb1InitialBlueId));
    }

    private static String requireBlueId(String value) {
        String checked = Objects.requireNonNull(value, "value");
        if (checked.isBlank() || !checked.equals(checked.trim())) {
            throw new IllegalArgumentException(
                    "Expected an exact non-blank BlueId");
        }
        return checked;
    }
}

