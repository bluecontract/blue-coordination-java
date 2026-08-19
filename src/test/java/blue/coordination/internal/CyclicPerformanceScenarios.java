package blue.coordination.internal;

import blue.coordination.api.Contracts10Configuration;
import blue.coordination.api.ContractsClosureAdmissionReceipt;
import blue.coordination.api.CoordinationEngine;
import blue.coordination.api.DocumentId;
import blue.coordination.api.Operation;
import blue.coordination.api.Timeline;
import blue.language.processor.registry.RuntimeBlueIds;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.IntStream;

/** Exact test-only public-engine shapes used by the cyclic campaign. */
final class CyclicPerformanceScenarios {
    static final String LANGUAGE_SPEC =
            "sha256:01b038b64e3f0a9a11f3f70d544a63ff78a01d5169f1a03f8b8629cf73645a7d";
    static final String CONTRACTS_SPEC =
            "sha256:dfb444962a5a17b3a6519e8d148c2bf4a975a921b1fcb1277710052caaecd930";
    static final String CONTRACTS_RELEASE =
            "sha256:7e6c3717bc28d21ebadec9f81725913e944bb3b9b70094531f19f10510a10e50";
    static final int UNRELATED_DOCUMENTS = 1_000;
    private static final int UNRELATED_BATCH_SIZE = 25;
    private static final long ENTRY_TIME = 2_600_000_000_000_001L;

    private CyclicPerformanceScenarios() {
    }

    enum Shape {
        TWO_MEMBER(
                "two-member-finite-cycle",
                "A contains B; B contains A; causal work A -> B -> A",
                1_000_000_000L,
                250_000_000L),
        THREE_MEMBER(
                "three-member-ring",
                "B contains A; C contains B; A contains C; causal work A -> B -> C -> A",
                1_500_000_000L,
                500_000_000L),
        FIVE_MEMBER(
                "five-member-shared-anchor",
                "A -> {B1,B2}; B1 -> C1 -> A; B2 -> C2 -> A; one five-member SCC",
                2_500_000_000L,
                1_000_000_000L),
        TWO_DISJOINT(
                "two-disjoint-two-member-cycles",
                "A1 <-> B1 and A2 <-> B2; two disconnected cohorts",
                null,
                null),
        FIVE_MEMBER_PLUS_1000(
                "five-member-plus-1000-unrelated",
                "The five-member SCC plus 1,000 unrelated singleton documents",
                null,
                null),
        DETACH_AND_DISSOLVE(
                "cycle-detachment-and-dissolution",
                "Remove C1/root -> A, then C2/root -> A",
                null,
                null);

        private final String id;
        private final String graph;
        private final Long releaseTargetNanos;
        private final Long aspirationalTargetNanos;

        Shape(
                String id,
                String graph,
                Long releaseTargetNanos,
                Long aspirationalTargetNanos) {
            this.id = id;
            this.graph = graph;
            this.releaseTargetNanos = releaseTargetNanos;
            this.aspirationalTargetNanos = aspirationalTargetNanos;
        }

        String id() {
            return id;
        }

        String graph() {
            return graph;
        }

        Long releaseTargetNanos() {
            return releaseTargetNanos;
        }

        Long aspirationalTargetNanos() {
            return aspirationalTargetNanos;
        }
    }

    static Prepared prepare(Shape shape) {
        return switch (Objects.requireNonNull(shape, "shape")) {
            case TWO_MEMBER -> prepareTwoMember();
            case THREE_MEMBER -> prepareThreeMember();
            case FIVE_MEMBER -> prepareFiveMember(false);
            case TWO_DISJOINT -> prepareDisjoint();
            case FIVE_MEMBER_PLUS_1000 -> prepareFiveMember(true);
            case DETACH_AND_DISSOLVE -> prepareDetachment();
        };
    }

    private static Prepared prepareTwoMember() {
        DocumentId a = DocumentId.of("perf-two-a");
        DocumentId b = DocumentId.of("perf-two-b");
        List<DocumentId> members = List.of(a, b);
        long engineStarted = System.nanoTime();
        DefaultCoordinationEngine engine = engine(Set.of(a));
        long engineNanos = System.nanoTime() - engineStarted;
        try {
            long admissionStarted = System.nanoTime();
            ContractsClosureAdmissionReceipt admission = admit(
                    new Contracts10ScenarioBuilder(engine)
                            .document(a, twoMemberA(a))
                            .document(b, twoMemberB(b))
                            .processEmbeddedPath(a, "/b", b)
                            .processEmbeddedPath(b, "/a", a)
                            .publicRoot(a)
                            .expectedComponent(a, b)
                            .admissionLabel("cyclic-performance-two-member"),
                    engine);
            long admissionNanos = System.nanoTime() - admissionStarted;
            Timeline timeline = engine.registerTimeline(
                    "performance/two", "alice");
            return new Prepared(
                    Shape.TWO_MEMBER,
                    engine,
                    engineNanos,
                    admissionNanos,
                    List.of(admission),
                    members,
                    0,
                    List.of(new OperationSpec(
                            "finite-cycle",
                            timeline,
                            Operation.yaml("start", "sourceChannel", "{}"),
                            ENTRY_TIME,
                            List.of(members),
                            2L,
                            1L,
                            3L)));
        } catch (RuntimeException failure) {
            engine.close();
            throw failure;
        }
    }

    private static Prepared prepareThreeMember() {
        DocumentId a = DocumentId.of("perf-three-a");
        DocumentId b = DocumentId.of("perf-three-b");
        DocumentId c = DocumentId.of("perf-three-c");
        List<DocumentId> members = List.of(a, b, c);
        long engineStarted = System.nanoTime();
        DefaultCoordinationEngine engine = engine(Set.of(a));
        long engineNanos = System.nanoTime() - engineStarted;
        try {
            long admissionStarted = System.nanoTime();
            ContractsClosureAdmissionReceipt admission = admit(
                    new Contracts10ScenarioBuilder(engine)
                            .document(a, threeMemberA(a))
                            .document(b, threeMemberB(b))
                            .document(c, threeMemberC(c))
                            .processEmbeddedPath(b, "/a", a)
                            .processEmbeddedPath(c, "/b", b)
                            .processEmbeddedPath(a, "/c", c)
                            .publicRoot(a)
                            .expectedComponent(a, b, c)
                            .admissionLabel("cyclic-performance-three-member"),
                    engine);
            long admissionNanos = System.nanoTime() - admissionStarted;
            Timeline timeline = engine.registerTimeline(
                    "performance/three", "alice");
            return new Prepared(
                    Shape.THREE_MEMBER,
                    engine,
                    engineNanos,
                    admissionNanos,
                    List.of(admission),
                    members,
                    0,
                    List.of(new OperationSpec(
                            "finite-ring",
                            timeline,
                            Operation.yaml("start", "sourceChannel", "{}"),
                            ENTRY_TIME + 1L,
                            List.of(members),
                            3L,
                            1L,
                            4L)));
        } catch (RuntimeException failure) {
            engine.close();
            throw failure;
        }
    }

    private static Prepared prepareFiveMember(boolean includeUnrelated) {
        BranchingIds ids = branchingIds("perf-five");
        List<DocumentId> members = ids.members();
        LinkedHashSet<DocumentId> roots = new LinkedHashSet<>();
        roots.add(ids.a());
        if (includeUnrelated) {
            List<DocumentId> unrelated = unrelatedIds();
            for (int index = 0; index < unrelated.size();
                    index += UNRELATED_BATCH_SIZE) {
                roots.add(unrelated.get(index));
            }
        }
        long engineStarted = System.nanoTime();
        DefaultCoordinationEngine engine = engine(roots);
        long engineNanos = System.nanoTime() - engineStarted;
        try {
            long admissionStarted = System.nanoTime();
            ArrayList<ContractsClosureAdmissionReceipt> admissions =
                    new ArrayList<>();
            admissions.add(admit(
                    branchingBuilder(
                            engine,
                            ids,
                            false,
                            "performance/five"),
                    engine));
            if (includeUnrelated) {
                admitUnrelated(engine, admissions);
            }
            long admissionNanos = System.nanoTime() - admissionStarted;
            Timeline timeline = engine.registerTimeline(
                    "performance/five",
                    "alice");
            Shape shape = includeUnrelated
                    ? Shape.FIVE_MEMBER_PLUS_1000
                    : Shape.FIVE_MEMBER;
            return new Prepared(
                    shape,
                    engine,
                    engineNanos,
                    admissionNanos,
                    admissions,
                    members,
                    includeUnrelated ? UNRELATED_DOCUMENTS : 0,
                    List.of(new OperationSpec(
                            "branching-reaction",
                            timeline,
                            Operation.yaml("start", "sourceChannel", "{}"),
                            ENTRY_TIME + 2L,
                            List.of(members),
                            5L,
                            1L,
                            7L)));
        } catch (RuntimeException failure) {
            engine.close();
            throw failure;
        }
    }

    private static Prepared prepareDisjoint() {
        DocumentId a1 = DocumentId.of("perf-disjoint-a1");
        DocumentId b1 = DocumentId.of("perf-disjoint-b1");
        DocumentId a2 = DocumentId.of("perf-disjoint-a2");
        DocumentId b2 = DocumentId.of("perf-disjoint-b2");
        List<DocumentId> members = List.of(a1, b1, a2, b2);
        long engineStarted = System.nanoTime();
        DefaultCoordinationEngine engine = engine(Set.of(a1, a2));
        long engineNanos = System.nanoTime() - engineStarted;
        try {
            long admissionStarted = System.nanoTime();
            ContractsClosureAdmissionReceipt admission = admit(
                    new Contracts10ScenarioBuilder(engine)
                            .document(a1, disjointA(a1, "one"))
                            .document(b1, disjointB(b1, "one"))
                            .document(a2, disjointA(a2, "two"))
                            .document(b2, disjointB(b2, "two"))
                            .processEmbeddedPath(a1, "/b", b1)
                            .processEmbeddedPath(b1, "/a", a1)
                            .processEmbeddedPath(a2, "/b", b2)
                            .processEmbeddedPath(b2, "/a", a2)
                            .publicRoot(a1)
                            .publicRoot(a2)
                            .expectedComponent(a1, b1)
                            .expectedComponent(a2, b2)
                            .admissionLabel("cyclic-performance-disjoint"),
                    engine);
            long admissionNanos = System.nanoTime() - admissionStarted;
            Timeline timeline = engine.registerTimeline(
                    "performance/disjoint", "alice");
            return new Prepared(
                    Shape.TWO_DISJOINT,
                    engine,
                    engineNanos,
                    admissionNanos,
                    List.of(admission),
                    members,
                    0,
                    List.of(new OperationSpec(
                            "both-cycles",
                            timeline,
                            Operation.yaml("start", "sourceChannel", "{}"),
                            ENTRY_TIME + 4L,
                            List.of(List.of(a1, b1), List.of(a2, b2)),
                            4L,
                            2L,
                            6L)));
        } catch (RuntimeException failure) {
            engine.close();
            throw failure;
        }
    }

    private static Prepared prepareDetachment() {
        BranchingIds ids = branchingIds("perf-detach");
        List<DocumentId> members = ids.members();
        long engineStarted = System.nanoTime();
        DefaultCoordinationEngine engine = engine(Set.of(
                ids.a(), ids.c1(), ids.c2()));
        long engineNanos = System.nanoTime() - engineStarted;
        try {
            long admissionStarted = System.nanoTime();
            ContractsClosureAdmissionReceipt admission = admit(
                    branchingBuilder(
                            engine,
                            ids,
                            true,
                            "performance/detach"),
                    engine);
            long admissionNanos = System.nanoTime() - admissionStarted;
            Timeline timeline = engine.registerTimeline(
                    "performance/detach", "alice");
            return new Prepared(
                    Shape.DETACH_AND_DISSOLVE,
                    engine,
                    engineNanos,
                    admissionNanos,
                    List.of(admission),
                    members,
                    0,
                    List.of(
                            new OperationSpec(
                                    "partial-detach",
                                    timeline,
                                    Operation.yaml(
                                            "detachOne",
                                            "controlChannel",
                                            "{}"),
                                    ENTRY_TIME + 5L,
                                    List.of(
                                            List.of(ids.c1()),
                                            List.of(ids.b1()),
                                            List.of(ids.a(), ids.b2(), ids.c2())),
                                    5L,
                                    1L,
                                    1L),
                            new OperationSpec(
                                    "full-dissolution",
                                    timeline,
                                    Operation.yaml(
                                            "detachTwo",
                                            "controlChannel",
                                            "{}"),
                                    ENTRY_TIME + 6L,
                                    List.of(
                                            List.of(ids.c1()),
                                            List.of(ids.b1()),
                                            List.of(ids.c2()),
                                            List.of(ids.b2()),
                                            List.of(ids.a())),
                                    3L,
                                    1L,
                                    1L)));
        } catch (RuntimeException failure) {
            engine.close();
            throw failure;
        }
    }

    private static Contracts10ScenarioBuilder branchingBuilder(
            DefaultCoordinationEngine engine,
            BranchingIds ids,
            boolean detachment,
            String timelineId) {
        Contracts10ScenarioBuilder builder = new Contracts10ScenarioBuilder(
                engine)
                .document(ids.a(), detachment
                        ? detachmentA(ids.a())
                        : branchingA(ids.a(), timelineId))
                .document(ids.b1(), branchingB(
                        ids.b1(), "branch-one-input", "branch-one-result"))
                .document(ids.b2(), branchingB(
                        ids.b2(), "branch-two-input", "branch-two-result"))
                .document(ids.c1(), detachment
                        ? detachmentC(ids.c1(), "detachOne")
                        : branchingC(ids.c1(),
                                "branch-one-start", "branch-one-input"))
                .document(ids.c2(), detachment
                        ? detachmentC(ids.c2(), "detachTwo")
                        : branchingC(ids.c2(),
                                "branch-two-start", "branch-two-input"))
                .processEmbeddedCollectionMember(
                        ids.a(), "/branches", "b1", ids.b1())
                .processEmbeddedPath(ids.b1(), "/child", ids.c1())
                .processEmbeddedPath(ids.c1(), "/root", ids.a())
                .processEmbeddedCollectionMember(
                        ids.a(), "/branches", "b2", ids.b2())
                .processEmbeddedPath(ids.b2(), "/child", ids.c2())
                .processEmbeddedPath(ids.c2(), "/root", ids.a())
                .publicRoot(ids.a())
                .expectedComponent(
                        ids.a(), ids.b1(), ids.b2(), ids.c1(), ids.c2())
                .admissionLabel(detachment
                        ? "cyclic-performance-detachment"
                        : "cyclic-performance-five-member");
        if (detachment) {
            builder.publicRoot(ids.c1()).publicRoot(ids.c2());
        }
        return builder;
    }

    private static void admitUnrelated(
            DefaultCoordinationEngine engine,
            List<ContractsClosureAdmissionReceipt> admissions) {
        List<DocumentId> unrelated = unrelatedIds();
        for (int start = 0; start < unrelated.size();
                start += UNRELATED_BATCH_SIZE) {
            int end = Math.min(start + UNRELATED_BATCH_SIZE,
                    unrelated.size());
            Contracts10ScenarioBuilder batch =
                    new Contracts10ScenarioBuilder(engine);
            for (DocumentId documentId : unrelated.subList(start, end)) {
                batch.document(documentId, unrelatedDocument(documentId))
                        .expectedComponent(documentId);
            }
            batch.publicRoot(unrelated.get(start))
                    .admissionLabel("cyclic-performance-unrelated-" + start);
            admissions.add(admit(batch, engine));
        }
    }

    private static ContractsClosureAdmissionReceipt admit(
            Contracts10ScenarioBuilder builder,
            DefaultCoordinationEngine engine) {
        ContractsClosureAdmissionReceipt receipt = builder
                .admitTo(engine).admissionReceipt();
        if (receipt.publicationOutcome()
                != ContractsClosureAdmissionReceipt.PublicationOutcome
                        .PUBLISHED
                || !receipt.attempt().isComplete()
                || !receipt.attempt().processResult().commits()) {
            throw new IllegalStateException(
                    "Performance scenario admission failed: "
                            + receipt.publicationOutcome());
        }
        return receipt;
    }

    private static DefaultCoordinationEngine engine(Set<DocumentId> roots) {
        return (DefaultCoordinationEngine)
                CoordinationEngine.inMemoryContracts10(
                        new Contracts10Configuration(
                                LANGUAGE_SPEC,
                                CONTRACTS_SPEC,
                                roots));
    }

    private static String twoMemberA(DocumentId id) {
        return """
                documentId: %s
                phase: initial
                contracts:
                  sourceChannel:
                    type: Coordination/Timeline Channel
                    timeline: {type: MyOS/MyOS Timeline, timelineId: performance/two}
                    actor: {type: MyOS/Principal Actor, accountId: alice}
                  start:
                    type: Coordination/Sequential Workflow Operation
                    channel: sourceChannel
                    request: {}
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendChange: {op: replace, path: /phase, val: started}
                          - $appendEvent: {type: Coordination/Event, kind: two-x}
                          - $return: true
                  fromB:
                    type: {blueId: %s}
                    sourcePath: /b
                    event: {type: Coordination/Event, kind: two-y}
                  finish:
                    type: Coordination/Sequential Workflow
                    channel: fromB
                    event: {type: Coordination/Event, kind: two-y}
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendChange: {op: replace, path: /phase, val: done}
                          - $return: true
                """.formatted(id.value(), RuntimeBlueIds.EMBEDDED_NODE_CHANNEL);
    }

    private static String twoMemberB(DocumentId id) {
        return """
                documentId: %s
                phase: initial
                contracts:
                  fromA:
                    type: {blueId: %s}
                    sourcePath: /a
                    event: {type: Coordination/Event, kind: two-x}
                  relay:
                    type: Coordination/Sequential Workflow
                    channel: fromA
                    event: {type: Coordination/Event, kind: two-x}
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendChange: {op: replace, path: /phase, val: relayed}
                          - $appendEvent: {type: Coordination/Event, kind: two-y}
                          - $return: true
                """.formatted(id.value(), RuntimeBlueIds.EMBEDDED_NODE_CHANNEL);
    }

    private static String threeMemberA(DocumentId id) {
        return """
                documentId: %s
                phase: initial
                contracts:
                  sourceChannel:
                    type: Coordination/Timeline Channel
                    timeline: {type: MyOS/MyOS Timeline, timelineId: performance/three}
                    actor: {type: MyOS/Principal Actor, accountId: alice}
                  start:
                    type: Coordination/Sequential Workflow Operation
                    channel: sourceChannel
                    request: {}
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendChange: {op: replace, path: /phase, val: started}
                          - $appendEvent: {type: Coordination/Event, kind: three-x}
                          - $return: true
                  fromC:
                    type: {blueId: %s}
                    sourcePath: /c
                    event: {type: Coordination/Event, kind: three-z}
                  finish:
                    type: Coordination/Sequential Workflow
                    channel: fromC
                    event: {type: Coordination/Event, kind: three-z}
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendChange: {op: replace, path: /phase, val: done}
                          - $return: true
                """.formatted(id.value(), RuntimeBlueIds.EMBEDDED_NODE_CHANNEL);
    }

    private static String threeMemberB(DocumentId id) {
        return relayDocument(id, "/a", "three-x", "three-y");
    }

    private static String threeMemberC(DocumentId id) {
        return relayDocument(id, "/b", "three-y", "three-z");
    }

    private static String relayDocument(
            DocumentId id,
            String sourcePath,
            String inputKind,
            String outputKind) {
        return """
                documentId: %s
                phase: initial
                contracts:
                  input:
                    type: {blueId: %s}
                    sourcePath: %s
                    event: {type: Coordination/Event, kind: %s}
                  relay:
                    type: Coordination/Sequential Workflow
                    channel: input
                    event: {type: Coordination/Event, kind: %s}
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendChange: {op: replace, path: /phase, val: relayed}
                          - $appendEvent: {type: Coordination/Event, kind: %s}
                          - $return: true
                """.formatted(
                id.value(),
                RuntimeBlueIds.EMBEDDED_NODE_CHANNEL,
                sourcePath,
                inputKind,
                inputKind,
                outputKind);
    }

    private static String branchingA(DocumentId id, String timelineId) {
        return """
                documentId: %s
                phase: initial
                branch1: pending
                branch2: pending
                contracts:
                  sourceChannel:
                    type: Coordination/Timeline Channel
                    timeline: {type: MyOS/MyOS Timeline, timelineId: %s}
                    actor: {type: MyOS/Principal Actor, accountId: alice}
                  start:
                    type: Coordination/Sequential Workflow Operation
                    channel: sourceChannel
                    request: {}
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendChange: {op: replace, path: /phase, val: started}
                          - $appendEvent: {type: Coordination/Event, kind: branch-one-start}
                          - $return: true
                  fromB1:
                    type: {blueId: %s}
                    sourcePath: /branches/b1
                    event: {type: Coordination/Event, kind: branch-one-result}
                  acceptB1:
                    type: Coordination/Sequential Workflow
                    channel: fromB1
                    event: {type: Coordination/Event, kind: branch-one-result}
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendChange: {op: replace, path: /branch1, val: done}
                          - $appendEvent: {type: Coordination/Event, kind: branch-two-start}
                          - $return: true
                  fromB2:
                    type: {blueId: %s}
                    sourcePath: /branches/b2
                    event: {type: Coordination/Event, kind: branch-two-result}
                  acceptB2:
                    type: Coordination/Sequential Workflow
                    channel: fromB2
                    event: {type: Coordination/Event, kind: branch-two-result}
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendChange: {op: replace, path: /branch2, val: done}
                          - $appendChange: {op: replace, path: /phase, val: done}
                          - $return: true
                """.formatted(
                id.value(),
                timelineId,
                RuntimeBlueIds.EMBEDDED_NODE_CHANNEL,
                RuntimeBlueIds.EMBEDDED_NODE_CHANNEL);
    }

    private static String branchingB(
            DocumentId id,
            String inputKind,
            String outputKind) {
        return """
                documentId: %s
                phase: initial
                contracts:
                  fromChild:
                    type: {blueId: %s}
                    sourcePath: /child
                    event: {type: Coordination/Event, kind: %s}
                  contribute:
                    type: Coordination/Sequential Workflow
                    channel: fromChild
                    event: {type: Coordination/Event, kind: %s}
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendChange: {op: replace, path: /phase, val: contributed}
                          - $appendEvent: {type: Coordination/Event, kind: %s}
                          - $return: true
                """.formatted(
                id.value(),
                RuntimeBlueIds.EMBEDDED_NODE_CHANNEL,
                inputKind,
                inputKind,
                outputKind);
    }

    private static String branchingC(
            DocumentId id,
            String inputKind,
            String outputKind) {
        return """
                documentId: %s
                phase: initial
                contracts:
                  fromRoot:
                    type: {blueId: %s}
                    sourcePath: /root
                    event: {type: Coordination/Event, kind: %s}
                  observe:
                    type: Coordination/Sequential Workflow
                    channel: fromRoot
                    event: {type: Coordination/Event, kind: %s}
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendChange: {op: replace, path: /phase, val: observed}
                          - $appendEvent: {type: Coordination/Event, kind: %s}
                          - $return: true
                """.formatted(
                id.value(),
                RuntimeBlueIds.EMBEDDED_NODE_CHANNEL,
                inputKind,
                inputKind,
                outputKind);
    }

    private static String disjointA(DocumentId id, String suffix) {
        return """
                documentId: %s
                phase: initial
                contracts:
                  sourceChannel:
                    type: Coordination/Timeline Channel
                    timeline: {type: MyOS/MyOS Timeline, timelineId: performance/disjoint}
                    actor: {type: MyOS/Principal Actor, accountId: alice}
                  start:
                    type: Coordination/Sequential Workflow Operation
                    channel: sourceChannel
                    request: {}
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendChange: {op: replace, path: /phase, val: started}
                          - $appendEvent: {type: Coordination/Event, kind: disjoint-%s-x}
                          - $return: true
                  fromB:
                    type: {blueId: %s}
                    sourcePath: /b
                    event: {type: Coordination/Event, kind: disjoint-%s-y}
                  finish:
                    type: Coordination/Sequential Workflow
                    channel: fromB
                    event: {type: Coordination/Event, kind: disjoint-%s-y}
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendChange: {op: replace, path: /phase, val: done}
                          - $return: true
                """.formatted(
                id.value(),
                suffix,
                RuntimeBlueIds.EMBEDDED_NODE_CHANNEL,
                suffix,
                suffix);
    }

    private static String disjointB(DocumentId id, String suffix) {
        return """
                documentId: %s
                phase: initial
                contracts:
                  fromA:
                    type: {blueId: %s}
                    sourcePath: /a
                    event: {type: Coordination/Event, kind: disjoint-%s-x}
                  relay:
                    type: Coordination/Sequential Workflow
                    channel: fromA
                    event: {type: Coordination/Event, kind: disjoint-%s-x}
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendChange: {op: replace, path: /phase, val: relayed}
                          - $appendEvent: {type: Coordination/Event, kind: disjoint-%s-y}
                          - $return: true
                """.formatted(
                id.value(),
                RuntimeBlueIds.EMBEDDED_NODE_CHANNEL,
                suffix,
                suffix,
                suffix);
    }

    private static String detachmentA(DocumentId id) {
        return """
                documentId: %s
                phase: initial
                branches: {}
                """.formatted(id.value());
    }

    private static String detachmentC(DocumentId id, String operation) {
        return """
                documentId: %s
                phase: initial
                contracts:
                  controlChannel:
                    type: Coordination/Timeline Channel
                    timeline: {type: MyOS/MyOS Timeline, timelineId: performance/detach}
                    actor: {type: MyOS/Principal Actor, accountId: alice}
                  %s:
                    type: Coordination/Sequential Workflow Operation
                    channel: controlChannel
                    request: {}
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendChange: {op: remove, path: /root}
                          - $appendChange: {op: replace, path: /phase, val: detached}
                          - $return: true
                """.formatted(id.value(), operation);
    }

    private static String unrelatedDocument(DocumentId id) {
        return """
                documentId: %s
                phase: unrelated
                """.formatted(id.value());
    }

    private static List<DocumentId> unrelatedIds() {
        return IntStream.range(0, UNRELATED_DOCUMENTS)
                .mapToObj(index -> DocumentId.of(
                        "perf-unrelated-%04d".formatted(index)))
                .toList();
    }

    private static BranchingIds branchingIds(String prefix) {
        return new BranchingIds(
                DocumentId.of(prefix + "-a"),
                DocumentId.of(prefix + "-b1"),
                DocumentId.of(prefix + "-b2"),
                DocumentId.of(prefix + "-c1"),
                DocumentId.of(prefix + "-c2"));
    }

    record OperationSpec(
            String id,
            Timeline timeline,
            Operation operation,
            long timestampMicros,
            List<List<DocumentId>> expectedPartition,
            long expectedChangedDocuments,
            long expectedDirectSeeds,
            long expectedAcceptedWork) {
        OperationSpec {
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException(
                        "Operation id must not be blank");
            }
            Objects.requireNonNull(timeline, "timeline");
            Objects.requireNonNull(operation, "operation");
            expectedPartition = expectedPartition.stream()
                    .map(List::copyOf)
                    .toList();
            if (timestampMicros <= 0L || expectedChangedDocuments < 0L
                    || expectedDirectSeeds <= 0L
                    || expectedAcceptedWork <= 0L) {
                throw new IllegalArgumentException(
                        "Operation measurements must be non-negative");
            }
        }
    }

    record Prepared(
            Shape shape,
            DefaultCoordinationEngine engine,
            long engineConstructionNanos,
            long admissionNanos,
            List<ContractsClosureAdmissionReceipt> admissions,
            List<DocumentId> relevantDocuments,
            int unrelatedDocumentCount,
            List<OperationSpec> operations) implements AutoCloseable {
        Prepared {
            Objects.requireNonNull(shape, "shape");
            Objects.requireNonNull(engine, "engine");
            admissions = List.copyOf(admissions);
            relevantDocuments = List.copyOf(relevantDocuments);
            operations = List.copyOf(operations);
            if (engineConstructionNanos < 0L || admissionNanos < 0L
                    || unrelatedDocumentCount < 0
                    || admissions.isEmpty() || relevantDocuments.isEmpty()
                    || operations.isEmpty()) {
                throw new IllegalArgumentException(
                        "Prepared performance scenario is incomplete");
            }
        }

        @Override
        public void close() {
            engine.close();
        }
    }

    private record BranchingIds(
            DocumentId a,
            DocumentId b1,
            DocumentId b2,
            DocumentId c1,
            DocumentId c2) {
        private List<DocumentId> members() {
            return List.of(a, b1, b2, c1, c2);
        }
    }
}
