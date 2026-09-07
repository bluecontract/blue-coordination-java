package blue.myos.mini.durable;

import java.lang.reflect.Field;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static blue.myos.mini.durable.HostModel.*;

/** Actual row-lock interleaving; only the existing isolated storage fixture creates the schema. */
public final class ReservationAuthorizationProbe {
    public static void main(String[] args) throws Exception {
        var fixture = new PostgresMaintenanceTest(); fixture.setup();
        try {
            HostDatabase db = (HostDatabase) field(fixture, "db");
            var pool = (com.zaxxer.hikari.HikariDataSource) field(fixture, "source");
            db.control("source", "S");
            String application = "review_authorization_" + java.util.UUID.randomUUID().toString().replace("-", "");
            try (var workerPool = PostgresTestPool.open(pool.getJdbcUrl() + "&ApplicationName=" + application, pool.getUsername(), pool.getPassword());
                 var locked = pool.getConnection(); var executor = Executors.newSingleThreadExecutor()) {
                var workerDb = new HostDatabase(workerPool, new Scope("tenant-a", "environment-a", "domain-a"), "operator", 0);
                locked.setAutoCommit(false);
                try (var statement = locked.prepareStatement("SELECT revision FROM dh_control WHERE scope=? AND family='source' AND key='S' FOR UPDATE")) {
                    statement.setString(1, db.scope); statement.executeQuery().close();
                }
                var future = executor.submit(() -> new PostgresDiscoveryStore(workerDb).reserve("S", "pending-owner", 10));
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                boolean blocked = false;
                while (System.nanoTime() < deadline) {
                    blocked = Boolean.TRUE.equals(db.jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE application_name=? AND wait_event_type='Lock' AND query LIKE 'SELECT revision FROM dh_control%FOR UPDATE')", Boolean.class, application));
                    if (blocked) break;
                    Thread.sleep(10);
                }
                if (!blocked) { locked.rollback(); throw new AssertionError("Worker never reached actual source lock"); }
                // This update completes while reserve is blocked, proving reserve holds no authorization fence.
                db.jdbc.update("UPDATE dh_authorization SET enabled=false,revision=revision+1 WHERE scope=? AND principal='operator'", db.scope);
                System.out.println("revocationCommittedBeforeSourceUnlock=true");
                locked.commit(); future.get(5, TimeUnit.SECONDS);
                long reservations = db.jdbc.queryForObject("SELECT count(*) FROM dh_reservation WHERE scope=? AND source='S' AND owner='pending-owner'", Long.class, db.scope);
                System.out.println("postRevocationReservationCount=" + reservations);
                if (reservations != 1) throw new AssertionError("Expected current unfenced reservation publication");
                try { new PostgresDiscoveryStore(workerDb).reserve("S", "later-owner", 11); throw new AssertionError("Revoked call accepted at entry"); }
                catch (SecurityException expected) { System.out.println("positiveControlCallStartedAfterRevocation=" + expected.getClass().getSimpleName()); }
            }
        } finally { fixture.cleanup(); }
    }
    private static Object field(Object value, String name) throws Exception {
        Field field = value.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(value);
    }
}
