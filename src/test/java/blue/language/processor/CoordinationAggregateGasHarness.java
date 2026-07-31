package blue.language.processor;

import blue.coordination.processor.CoordinationRuntimeGas;
import blue.language.model.Node;
import blue.language.snapshot.FrozenNode;
import blue.repo.coordination.TimelineChannel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Test-only Language-package bridge for large aggregate gas scenarios.
 */
public final class CoordinationAggregateGasHarness {
    private CoordinationAggregateGasHarness() {
    }

    /**
     * Creates a context whose Timeline members reject after one charged header
     * read. Empty submitted fixture ledgers model other hosted runtime
     * components without adding trace entries.
     */
    public static ExternalChannelFunctionContext rejectingTimelineMembers(
            GasMeter parent,
            int memberCount,
            int preopenedNamespaces) {
        return timelineMembers(
                parent,
                memberCount,
                preopenedNamespaces,
                true,
                false,
                -1);
    }

    /**
     * Creates the exact worst-case aggregate scan: every member performs its
     * Timeline header read and both binding comparisons before rejecting.
     */
    public static ExternalChannelFunctionContext
    fullyEvaluatedRejectingTimelineMembers(
            GasMeter parent,
            int memberCount) {
        return timelineMembers(
                parent,
                memberCount,
                0,
                true,
                true,
                -1);
    }

    /**
     * Creates a context whose selected member accepts without adding fixture
     * gas, so aggregate visit accounting can be tested in isolation.
     */
    public static ExternalChannelFunctionContext
    acceptingTimelineMembers(
            GasMeter parent,
            int memberCount,
            int acceptingIndex) {
        if (acceptingIndex < 0
                || acceptingIndex >= memberCount) {
            throw new IllegalArgumentException(
                    "acceptingIndex must identify one member");
        }
        return timelineMembers(
                parent,
                memberCount,
                0,
                false,
                false,
                acceptingIndex);
    }

    /**
     * Creates an accepting shallow member catalog instrumented at each
     * boundary that can resolve a selected member.
     *
     * @param parent live parent gas meter
     * @param memberCount number of shallow Timeline member identities
     * @param acceptingIndex member whose evaluator accepts
     * @param probe resolution counters owned by the caller
     * @return event-evaluation function context
     */
    public static ExternalChannelFunctionContext
    observedAcceptingTimelineMembers(
            GasMeter parent,
            int memberCount,
            int acceptingIndex,
            MemberResolutionProbe probe) {
        if (acceptingIndex < 0
                || acceptingIndex >= memberCount) {
            throw new IllegalArgumentException(
                    "acceptingIndex must identify one member");
        }
        if (probe == null) {
            throw new NullPointerException("probe");
        }
        return timelineMembers(
                parent,
                memberCount,
                0,
                false,
                false,
                acceptingIndex,
                probe);
    }

    private static ExternalChannelFunctionContext timelineMembers(
            GasMeter parent,
            int memberCount,
            int preopenedNamespaces,
            boolean chargeHeaderRead,
            boolean chargeBindingComparison,
            int acceptingIndex) {
        return timelineMembers(
                parent,
                memberCount,
                preopenedNamespaces,
                chargeHeaderRead,
                chargeBindingComparison,
                acceptingIndex,
                null);
    }

    private static ExternalChannelFunctionContext timelineMembers(
            GasMeter parent,
            int memberCount,
            int preopenedNamespaces,
            boolean chargeHeaderRead,
            boolean chargeBindingComparison,
            int acceptingIndex,
            MemberResolutionProbe probe) {
        RuntimeWorkSession session =
                new RuntimeWorkSession(
                        parent,
                        RuntimeWorkSession.Mode.PROCESSING);
        preopenNamespaces(
                session,
                preopenedNamespaces);

        List<ExternalChannelMemberSnapshot> members =
                new ArrayList<ExternalChannelMemberSnapshot>(
                        memberCount);
        Map<String, ExternalChannelMemberSnapshot> byKey =
                new LinkedHashMap<String, ExternalChannelMemberSnapshot>();
        for (int index = 0; index < memberCount; index++) {
            String key = String.format(
                    java.util.Locale.ROOT,
                    "timeline-%04d",
                    Integer.valueOf(index));
            ExternalChannelMemberSnapshot member =
                    timelineMember(
                            session,
                            key,
                            index,
                            chargeHeaderRead,
                            chargeBindingComparison,
                            index == acceptingIndex,
                            probe);
            members.add(member);
            byKey.put(key, member);
        }
        List<ExternalChannelMemberSnapshot> exactMembers =
                Collections.unmodifiableList(members);
        Map<String, ExternalChannelMemberSnapshot> exactByKey =
                Collections.unmodifiableMap(byKey);
        return new ExternalChannelFunctionContext(
                "/",
                "aggregate",
                access(
                        exactMembers,
                        exactByKey,
                        probe),
                session);
    }

    /** Completes the context's work session after assertions are prepared. */
    public static void complete(
            ExternalChannelFunctionContext context) {
        context.runtimeWorkSession().complete();
    }

    /** Retains the admitted prefix for a deterministic fixture failure. */
    public static void failDeterministically(
            ExternalChannelFunctionContext context) {
        context.runtimeWorkSession()
                .failDeterministically();
    }

    private static void preopenNamespaces(
            RuntimeWorkSession session,
            int count) {
        Map<String, Long> catalog =
                Collections.singletonMap(
                        "fixture",
                        Long.valueOf(1L));
        for (int index = 0; index < count; index++) {
            GasMeter.ChildGasLedger ledger =
                    session.openLedger(
                            String.format(
                                    java.util.Locale.ROOT,
                                    "fixture.%04d",
                                    Integer.valueOf(index)),
                            catalog);
            session.submit(ledger);
        }
    }

    private static ExternalChannelMemberSnapshot
    timelineMember(
            RuntimeWorkSession session,
            String key,
            int order,
            boolean chargeHeaderRead,
            boolean chargeBindingComparison,
            boolean accepts,
            MemberResolutionProbe probe) {
        String domain = "fixture-domain:" + key;
        return new ExternalChannelMemberSnapshot(
                key,
                order,
                TimelineChannel.blueId(),
                Collections.singletonList(
                        "fixture-source:" + key),
                ExternalChannelDependencySnapshot.none(),
                Collections.singletonList(
                        "fixture-subscription"),
                domain,
                new Node().type(
                        new Node().blueId(
                                TimelineChannel.blueId())),
                exactEvent -> {
                    if (probe != null) {
                        probe.memberEvaluations.incrementAndGet();
                    }
                    if (chargeHeaderRead) {
                        CoordinationRuntimeGas.charge(
                                session,
                                "timelineHeaderRead",
                                1L,
                                GasChargeContext.of(
                                        "/",
                                        key,
                                        null,
                                        "read rejecting member header"));
                    }
                    if (chargeBindingComparison) {
                        CoordinationRuntimeGas.charge(
                                session,
                                "timelineBindingCompared",
                                1L,
                                GasChargeContext.of(
                                        "/",
                                        key,
                                        null,
                                        "compare rejecting member "
                                                + "Timeline binding"));
                        CoordinationRuntimeGas.charge(
                                session,
                                "timelineBindingCompared",
                                1L,
                                GasChargeContext.of(
                                        "/",
                                        key,
                                        null,
                                        "compare rejecting member "
                                                + "Actor binding"));
                    }
                    return new ExternalChannelMemberEvaluation(
                            Collections.singletonList(
                                    "fixture-subscription"),
                            Collections.<String>emptyList(),
                            accepts,
                            accepts,
                            domain,
                            null,
                            null,
                            null,
                            null);
                });
    }

    private static ExternalChannelFunctionContext.Access access(
            List<ExternalChannelMemberSnapshot> members,
            Map<String, ExternalChannelMemberSnapshot> byKey,
            MemberResolutionProbe probe) {
        return new ExternalChannelFunctionContext.Access() {
            @Override
            public ExternalChannelMemberSnapshot member(
                    String key) {
                if (probe != null) {
                    probe.directMemberLookups.incrementAndGet();
                }
                ExternalChannelMemberSnapshot member =
                        byKey.get(key);
                if (member == null) {
                    throw new IllegalArgumentException(
                            "Unknown fixture member " + key);
                }
                return member;
            }

            @Override
            public List<ExternalChannelMemberSnapshot> members() {
                return members;
            }

            @Override
            public List<ExternalChannelMemberSnapshot>
            membersByEffectiveType(
                    String effectiveTypeBlueId) {
                return TimelineChannel.blueId().equals(
                        effectiveTypeBlueId)
                        ? members
                        : Collections
                        .<ExternalChannelMemberSnapshot>emptyList();
            }

            @Override
            public List<ExternalChannelMemberSnapshot>
            membersAssignableToType(
                    String baseTypeBlueId) {
                if (probe != null) {
                    probe.shallowTypeFamilyQueries.incrementAndGet();
                }
                return TimelineChannel.blueId().equals(
                        baseTypeBlueId)
                        ? members
                        : Collections
                        .<ExternalChannelMemberSnapshot>emptyList();
            }

            @Override
            public ChannelMemberSnapshot dependOnSameScopeChannel(
                    String key) {
                throw new UnsupportedOperationException(
                        "Channel lookup is outside this fixture");
            }

            @Override
            public void dependOnSameScopeChannelCatalog() {
                throw new UnsupportedOperationException(
                        "Channel catalog is outside this fixture");
            }

            @Override
            public ChannelLookupResult lookupChannel(
                    String key) {
                throw new UnsupportedOperationException(
                        "Channel lookup is outside this fixture");
            }

            @Override
            public boolean matchesPattern(
                    FrozenNode candidate,
                    FrozenNode pattern) {
                return false;
            }

            @Override
            public FrozenNode materializeExactReference(
                    FrozenNode reference) {
                return reference;
            }
        };
    }

    /**
     * Observable distinction between Language's shallow catalog query and a
     * selected peer lookup or evaluation.
     */
    public static final class MemberResolutionProbe {
        private final AtomicInteger shallowTypeFamilyQueries =
                new AtomicInteger();
        private final AtomicInteger directMemberLookups =
                new AtomicInteger();
        private final AtomicInteger memberEvaluations =
                new AtomicInteger();

        /**
         * Returns generic shallow Timeline-family catalog queries.
         *
         * @return query count
         */
        public int shallowTypeFamilyQueries() {
            return shallowTypeFamilyQueries.get();
        }

        /**
         * Returns eager exact-key member lookups.
         *
         * @return lookup count
         */
        public int directMemberLookups() {
            return directMemberLookups.get();
        }

        /**
         * Returns selected peer evaluator invocations.
         *
         * @return evaluation count
         */
        public int memberEvaluations() {
            return memberEvaluations.get();
        }
    }
}
