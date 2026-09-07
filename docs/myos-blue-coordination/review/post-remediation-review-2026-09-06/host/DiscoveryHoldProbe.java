package blue.myos.mini.durable;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import static blue.myos.mini.durable.HostModel.*;
import static blue.myos.mini.durable.PostgresPrefixStore.Kind;

/** Reuses existing isolated-schema fixture construction; executes real recovery/discovery APIs. */
public final class DiscoveryHoldProbe {
    public static void main(String[] args) throws Exception {
        var fixture = new PostgresMaintenanceTest();
        fixture.setup();
        try {
            HostDatabase db = (HostDatabase) field(fixture, "db");
            var pool = (com.zaxxer.hikari.HikariDataSource) field(fixture, "source");
            Method create = PostgresMaintenanceTest.class.getDeclaredMethod("create", String.class, Kind.class);
            create.setAccessible(true);
            Object blocked = create.invoke(fixture, "P", Kind.OPERATION);
            Object healthy = create.invoke(fixture, "Q", null);
            Method proofBytes = PostgresMaintenanceTest.class.getDeclaredMethod("proofBytes", healthy.getClass(), Kind.class);
            proofBytes.setAccessible(true);
            int healthyBytes = (int) proofBytes.invoke(fixture, healthy, Kind.OPERATION);
            int capacity = healthyBytes + 32;
            int blockedBytes = (int) proofBytes.invoke(fixture, blocked, Kind.OPERATION);
            if (blockedBytes <= capacity) throw new AssertionError("P must require more proof capacity than Q");
            var discovery = new PostgresDiscoveryStore(db);
            String history = db.putJson(Map.of("fixtureOnly", true));
            var registration = new Registration("Q", "q-recipient", "recipient", "account", 0, 0, null, history, 0, 0, -1);
            discovery.register(registration, db.control("source", "Q"), db.control("lineage", "recipient"));
            int materialized = PostgresPrefixStore.reader(db).materializeFanout(2, 1);
            if (materialized != 2) throw new AssertionError("Both fanouts must already be durable");
            var originalRoots = db.jdbc.queryForList("SELECT source,root_key FROM dh_prefix_activation WHERE scope=? ORDER BY source", db.scope);
            var originalCursors = db.jdbc.queryForList("SELECT source,scan_key,scan_generation,initial_done,changes_through FROM dh_fanout WHERE scope=? ORDER BY source", db.scope);
            var cold = new HostDatabase(pool, new Scope("tenant-a", "environment-a", "domain-a"), "operator", 0, capacity);
            var host = new DurableHost(cold, new DurableHost.CoordinationAdapter() {
                public DurableHost.Evaluation evaluate(Work ignored, HostDatabase evidence) { throw new AssertionError("No reevaluation"); }
                public void verify(Plan ignored) { throw new AssertionError("No recommit"); }
            }, 1);
            System.out.println("capacity=" + capacity + " blockedProofBytes=" + blockedBytes + " healthyProofBytes=" + healthyBytes + " initialFanouts=" + materialized);
            for (int attempt = 1; attempt <= 3; attempt++) {
                long beforeReads = cold.contentMetrics().get("physicalReads");
                try { host.recover(100); throw new AssertionError("Expected P capacity Hold"); }
                catch (Hold expected) {
                    System.out.println("recover" + attempt + "=" + expected.getMessage()
                            + " healthyDeliveries=" + count(db, "dh_delivery", "consumer='recipient'")
                            + " physicalReads=" + (cold.contentMetrics().get("physicalReads") - beforeReads));
                }
            }
            if (count(db, "dh_delivery", "consumer='recipient'") != 0) throw new AssertionError("Q unexpectedly progressed");
            boolean rootsUnchanged = originalRoots.equals(db.jdbc.queryForList("SELECT source,root_key FROM dh_prefix_activation WHERE scope=? ORDER BY source", db.scope));
            boolean cursorsUnchanged = originalCursors.equals(db.jdbc.queryForList("SELECT source,scan_key,scan_generation,initial_done,changes_through FROM dh_fanout WHERE scope=? ORDER BY source", db.scope));
            long used = db.jdbc.queryForObject("SELECT used FROM dh_account WHERE scope=? AND account='account'", Long.class, db.scope);
            System.out.println("rootsUnchanged=" + rootsUnchanged + " fanoutCursorsUnchanged=" + cursorsUnchanged + " commits=" + count(db, "dh_commit", "true") + " settledAccountUnits=" + used);
            if (!rootsUnchanged || !cursorsUnchanged || used != 0 || count(db, "dh_commit", "true") != 2) throw new AssertionError("Semantic authority unexpectedly changed");
            System.out.println("heldFanoutStillDue=" + db.jdbc.queryForObject("SELECT next_attempt<=clock_timestamp() FROM dh_fanout WHERE scope=? AND source='P'", Boolean.class, db.scope));
            // Same capacity, same authoritative roots, no repair to P: Q itself is immediately serviceable.
            int direct = new PostgresDiscoveryStore(cold).fanoutPage("Q", 1, 1);
            System.out.println("positiveControlDirectHealthyFanout=" + direct + " healthyDeliveries=" + count(db, "dh_delivery", "consumer='recipient'"));
            if (count(db, "dh_delivery", "consumer='recipient'") != 1) throw new AssertionError("Healthy positive control failed");
        } finally { fixture.cleanup(); }
    }
    private static Object field(Object value, String name) throws Exception {
        Field field = value.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(value);
    }
    private static long count(HostDatabase db, String table, String predicate) {
        return db.jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE scope=? AND " + predicate, Long.class, db.scope);
    }
}
