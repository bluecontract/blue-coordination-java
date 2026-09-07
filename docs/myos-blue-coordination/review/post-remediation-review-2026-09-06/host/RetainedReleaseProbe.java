package blue.myos.mini.durable;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import static blue.myos.mini.durable.HostModel.*;

/** Standalone storage-protocol witness, not a Coordination semantic fixture. */
public final class RetainedReleaseProbe {
    public static void main(String[] args) {
        String url = System.getenv().getOrDefault("MYOS_DURABLE_JDBC_URL", "jdbc:postgresql://127.0.0.1:15432/myos_phase2");
        String user = System.getenv().getOrDefault("MYOS_DURABLE_DB_USER", "myos_phase2");
        String password = System.getenv().getOrDefault("MYOS_DURABLE_DB_PASSWORD", "phase2-local");
        var admin = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
        String schema = "dh_review_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE SCHEMA " + schema);
        try (var pool = PostgresTestPool.open(url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema, user, password)) {
            new ResourceDatabasePopulator(new ClassPathResource("db/durable/V1__durable_authority.sql"),
                    new ClassPathResource("db/durable/V2__publication_recovery_and_timeline_fairness.sql"),
                    new ClassPathResource("db/durable/V3__prefix_maintenance_holds.sql")).execute(pool);
            Scope scope = new Scope("review", "review", "release");
            var db = new HostDatabase(pool, scope, "operator", 0);
            db.jdbc.update("INSERT INTO dh_authorization(scope,principal) VALUES (?,?)", scope.key(), "operator");
            var work = new PostgresWorkStore(db);
            work.account("account", 100);
            var adapter = new DurableHost.CoordinationAdapter() {
                public DurableHost.Evaluation evaluate(Work ignored, HostDatabase evidence) { throw new AssertionError("No evaluation required"); }
                public void verify(Plan plan) {
                    if (!Boolean.TRUE.equals(db.readJson(plan.receiptKey(), Map.class).get("storageFixture")))
                        throw new Conflict("Only controlled storage fixture allowed");
                }
            };
            var commits = new PostgresCommitStore(db, adapter);
            String receipt = db.putJson(Map.of("storageFixture", true));
            work.enqueue("work", "account", "lineage", receipt, "owner");
            Work first = work.claim("first", Duration.ofMinutes(1)).orElseThrow();
            Plan original = plan(first, receipt, db.authorize());
            commits.retain(original, List.of());
            work.release(new WorkFence(first.key(), first.generation()));
            Work second = work.claim("second", Duration.ofMinutes(1)).orElseThrow();
            Plan retry = plan(second, receipt, db.authorize());
            System.out.println("generations original=" + first.generation() + " retry=" + second.generation());
            try {
                commits.retain(retry, List.of());
                throw new AssertionError("Expected orphan retained-attempt conflict");
            } catch (Conflict expected) {
                System.out.println("sameKeyRetry=" + expected.getMessage());
            }
            db.jdbc.update("UPDATE dh_work SET lease_until=clock_timestamp()-interval '1 second' WHERE scope=? AND key='work'", db.scope);
            // New process-equivalent instances: no retained Java state is used by recovery.
            var coldDb = new HostDatabase(pool, scope, "operator", 0);
            int recovered = new DurableHost(coldDb, adapter, 1).recover(100);
            boolean invalidated = db.jdbc.queryForObject("SELECT invalidated FROM dh_plan WHERE scope=? AND commit_key='operation'", Boolean.class, db.scope);
            System.out.println("coldRecover=" + recovered + " activePlanStillPresent=" + !invalidated + " workState=" + work.read("work").state());
            if (invalidated) throw new AssertionError("Retained attempt unexpectedly invalidated");
            Work third = work.claim("third", Duration.ofMinutes(1)).orElseThrow();
            try {
                commits.retain(plan(third, receipt, db.authorize()), List.of());
                throw new AssertionError("Expected same conflict after recovery");
            } catch (Conflict expected) {
                System.out.println("postRecoverySameKeyRetry=" + expected.getMessage());
            }
            System.out.println("manualReconcile=" + commits.reconcile("operation").status());
            commits.retain(plan(third, receipt, db.authorize()), List.of());
            commits.commit(plan(third, receipt, db.authorize()));
            System.out.println("positiveControlAfterManualReconcile=" + work.read("work").state());
        } finally {
            if (schema.matches("dh_review_[0-9a-f]{32}")) admin.execute("DROP SCHEMA " + schema + " CASCADE");
        }
    }

    private static Plan plan(Work owned, String receipt, long authorization) {
        return new Plan("operation", receipt, "owner", authorization,
                List.of(new WorkFence(owned.key(), owned.generation())), List.of(), List.of(), List.of(),
                List.of(new Outcome(owned.key(), "lane", receipt, "SUCCESS", "input")),
                List.of(), List.of(), List.of(), List.of(), Map.of("account", 1L));
    }
}
