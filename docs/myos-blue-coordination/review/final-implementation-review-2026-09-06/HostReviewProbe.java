package blue.myos.mini.durable;

import java.lang.reflect.*;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.ClassPathResource;
import static blue.myos.mini.durable.HostModel.*;
import static blue.myos.mini.durable.PostgresPrefixStore.*;

/** Controlled storage-only review diagnostics. No repository classes are edited. */
public final class HostReviewProbe {
  static final long GATE = 79449381L;
  static final CountDownLatch retainedWorkLocked = new CountDownLatch(1), insertPlan = new CountDownLatch(1);
  static final AtomicBoolean holdRetain = new AtomicBoolean(true);
  public static void main(String[] args) throws Exception {
    String url="jdbc:postgresql://127.0.0.1:15432/myos_phase2", user="myos_phase2", password="phase2-local";
    String schema="dh_review_"+UUID.randomUUID().toString().replace("-", "");
    JdbcTemplate admin=new JdbcTemplate(new DriverManagerDataSource(url,user,password));
    admin.execute("CREATE SCHEMA "+schema);
    HikariDataSource pool=new HikariDataSource(); pool.setJdbcUrl(url+"?currentSchema="+schema); pool.setUsername(user); pool.setPassword(password);
    pool.setMaximumPoolSize(8); pool.setMinimumIdle(1);
    System.out.println("ISOLATED_SCHEMA="+schema);
    try {
      new ResourceDatabasePopulator(new ClassPathResource("db/durable/V1__durable_authority.sql"),new ClassPathResource("db/durable/V2__publication_recovery_and_timeline_fairness.sql")).execute(pool);
      Scope scope=new Scope("review",schema,"authority");
      HostDatabase db=new HostDatabase(instrument(pool),scope,"reviewer",0);
      db.jdbc.update("INSERT INTO dh_authorization(scope,principal) VALUES (?,?)",scope.key(),"reviewer");
      new PostgresWorkStore(db).account("account",1_000_000);
      race(db,pool);
      starvation(db,pool,scope);
    } finally {
      pool.close();
      if (!schema.matches("dh_review_[0-9a-f]{32}")) throw new AssertionError("Unsafe cleanup");
      admin.execute("DROP SCHEMA "+schema+" CASCADE");
      System.out.println("CLEANED_ONLY="+schema);
    }
  }
  static DataSource instrument(DataSource delegate) {
    return (DataSource)Proxy.newProxyInstance(HostReviewProbe.class.getClassLoader(),new Class<?>[]{DataSource.class},(p,m,a)-> {
      Object result=call(m,delegate,a);
      if (!m.getName().equals("getConnection")) return result;
      Connection raw=(Connection)result;
      return Proxy.newProxyInstance(HostReviewProbe.class.getClassLoader(),new Class<?>[]{Connection.class},(q,n,b)-> {
        if (!n.getName().equals("prepareStatement") || !(b[0] instanceof String sql)) return call(n,raw,b);
        boolean retention=sql.startsWith("INSERT INTO dh_plan(");
        boolean recovery=sql.contains("lease_until<=clock_timestamp()") && sql.contains("FOR UPDATE SKIP LOCKED");
        if (recovery) {
          b=b.clone();
          // Scheduling-only TRUE InitPlan: preserves original predicates and SKIP LOCKED.
          b[0]=sql.replace("FOR UPDATE SKIP LOCKED","AND (SELECT true FROM (SELECT pg_advisory_xact_lock("+GATE+")) review_barrier) FOR UPDATE SKIP LOCKED");
        }
        PreparedStatement ps=(PreparedStatement)call(n,raw,b);
        if (!retention) return ps;
        return Proxy.newProxyInstance(HostReviewProbe.class.getClassLoader(),new Class<?>[]{PreparedStatement.class},(r,o,c)-> {
          if (o.getName().equals("executeUpdate") && holdRetain.compareAndSet(true,false)) {
            retainedWorkLocked.countDown();
            if (!insertPlan.await(10,TimeUnit.SECONDS)) throw new AssertionError("Retain latch timeout");
          }
          return call(o,ps,c);
        });
      });
    });
  }
  static Object call(Method m,Object target,Object[] args) throws Throwable { try{return m.invoke(target,args);}catch(InvocationTargetException x){throw x.getCause();} }
  static Plan plan(HostDatabase db,String key,Work w) {
    String receipt=db.putJson(Map.of("status","SUCCESS","fixtureOnly",true));
    return new Plan(key,receipt,"original-owner",db.authorize(),List.of(new WorkFence(w.key(),w.generation())),List.of(),List.of(),List.of(),List.of(),List.of(),List.of(),List.of(),List.of(),Map.of());
  }
  static void race(HostDatabase db,HikariDataSource pool) throws Exception {
    PostgresWorkStore work=new PostgresWorkStore(db);
    work.enqueue("race-work","account","race-source",db.putJson("race-input"),"original-owner");
    Work original=work.claim("retaining-worker",Duration.ofMillis(500)).orElseThrow();
    Plan originalPlan=plan(db,"race-commit",original);
    PostgresCommitStore commits=new PostgresCommitStore(db,p-> { if(!Boolean.TRUE.equals(db.readJson(p.receiptKey(),Map.class).get("fixtureOnly")))throw new AssertionError(); });
    try(Connection gate=pool.getConnection(); ExecutorService executor=Executors.newFixedThreadPool(2)) {
      gate.createStatement().execute("SELECT pg_advisory_lock("+GATE+")");
      Future<String> retaining=executor.submit(()->commits.retain(originalPlan,List.of()));
      if(!retainedWorkLocked.await(5,TimeUnit.SECONDS))throw new AssertionError("retainer not locked");
      Thread.sleep(700); // Lease expires while the real retain transaction holds the work row.
      Future<Integer> recovering=executor.submit(()->work.recoverLeases(1));
      long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5); boolean blocked=false;
      while(System.nanoTime()<until) {
        blocked=Boolean.TRUE.equals(db.jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE wait_event='advisory' AND query LIKE '%review_barrier%')",Boolean.class));
        if(blocked)break; Thread.sleep(10);
      }
      if(!blocked)throw new AssertionError("recovery snapshot barrier not reached");
      insertPlan.countDown(); retaining.get(5,TimeUnit.SECONDS);
      System.out.println("RACE retained plan committed while recovery statement's TRUE barrier held; work="+work.read("race-work"));
      gate.createStatement().execute("SELECT pg_advisory_unlock("+GATE+")");
      System.out.println("RACE recovered="+recovering.get(5,TimeUnit.SECONDS)+" work="+work.read("race-work"));
      System.out.println("RACE plan_work="+db.jdbc.queryForList("SELECT p.invalidated,pw.generation FROM dh_plan p JOIN dh_plan_work pw ON pw.scope=p.scope AND pw.commit_key=p.commit_key WHERE p.scope=?",db.scope));
      Work retried=work.claim("retry-worker",Duration.ofSeconds(30)).orElseThrow();
      try { commits.retain(plan(db,"race-commit",retried),List.of()); System.out.println("RACE unexpected retry accepted"); }
      catch(Conflict e) { System.out.println("RACE retry_rejected="+e.getMessage()); }
      Long discoverable=db.jdbc.queryForObject("SELECT count(*) FROM dh_work w JOIN dh_plan_work pw ON pw.scope=w.scope AND pw.work_key=w.key AND pw.generation=w.generation JOIN dh_plan p ON p.scope=pw.scope AND p.commit_key=pw.commit_key WHERE w.scope=? AND NOT p.invalidated",Long.class,db.scope);
      System.out.println("RACE automatic_reconciliation_candidates="+discoverable);
      // Diagnostic leaves no active lease to interfere with the independent next scenario.
      commits.reconcile("race-commit");
      db.jdbc.update("UPDATE dh_work SET state='PAUSED' WHERE scope=? AND key='race-work'",db.scope);
      db.jdbc.update("UPDATE dh_account SET pending=0 WHERE scope=? AND account='account'",db.scope);
    }
  }
  record Made(String source,String root,Plan plan,Page ready) { }
  static Made create(HostDatabase db,String source,String readyKey) {
    String evidence=db.putJson(Map.of("fixtureOnly",true,"source",source));
    String state=db.putJson(Map.of("source",source,"counter",0));
    String payload=db.putJson(new Operation(new History(source,0,state,0,source+"-init"),new PostgresDiscoveryStore.SourceReceipt(source,1,source+"-init",evidence,evidence,0,"owner",null,null,SourceEvidenceKind.SUCCESS,null)));
    Page operations=new Page(Kind.OPERATION,0,List.of(new Row(source+"-op",0,payload,evidence,evidence,evidence)));
    Page ready=new Page(Kind.READY,0,List.of(new Row(readyKey,0,db.putJson(new Ready("account",source,evidence)),evidence,evidence,evidence)));
    EnumMap<Kind,Section> sections=new EnumMap<>(Kind.class);
    for(Kind kind:Kind.values())sections.put(kind,new Section(null,0,0));
    sections.put(Kind.OPERATION,new Section(leafKey(db,0,db.putJson(operations)),1,1));
    sections.put(Kind.READY,new Section(leafKey(db,0,db.putJson(ready)),1,1));
    Manifest manifest=new Manifest(source,evidence,evidence,db.putJson(new Lineage(source,0,state,0,source+"-init",true)),1,sections);
    PostgresPrefixStore prefixes=new PostgresPrefixStore(db,new Verifier(){public void manifest(Manifest m){if(!source.equals(m.source()))throw new AssertionError();}public void page(Manifest m,Page p){if(p.rows().size()!=1)throw new AssertionError();}});
    String root=prefixes.prepare(manifest); prefixes.stage(root,operations,new Membership(List.of())); prefixes.stage(root,ready,new Membership(List.of())); prefixes.seal(root);
    PostgresWorkStore works=new PostgresWorkStore(db); String key="activate-"+source;
    works.enqueue(key,"account",source,evidence,"owner"); Work w=works.claim("activate",Duration.ofSeconds(30)).orElseThrow();
    List<ControlFence> controls=new ArrayList<>();for(String family:List.of("prefix-source","source","lineage","stream"))controls.add(new ControlFence(family,source,db.control(family,source)));
    Plan plan=new Plan(key,evidence,"owner",db.authorize(),List.of(new WorkFence(w.key(),w.generation())),controls,List.of(),List.of(),List.of(),List.of(),List.of(),List.of(),List.of(),Map.of(),List.of(),List.of(new PrefixActivation(source,root,evidence)));
    PostgresCommitStore commits=new PostgresCommitStore(db,p->{if(!Boolean.TRUE.equals(db.readJson(p.receiptKey(),Map.class).get("fixtureOnly")))throw new AssertionError();});
    commits.retain(plan,List.of());commits.commit(plan); return new Made(source,root,plan,ready);
  }
  static void starvation(HostDatabase db,HikariDataSource pool,Scope scope) {
    Made bad=create(db,"P","p".repeat(1000)), good=create(db,"Q","good-ready");
    int goodBytes=db.bytes(good.plan()).length+db.bytes(good.ready()).length+db.bytes(new Membership(List.of())).length+db.bytes(List.of()).length;
    int capacity=goodBytes+16;
    System.out.println("STARVATION good_proof_bytes="+goodBytes+" configured="+capacity+" bad_proof_bytes="+(db.bytes(bad.plan()).length+db.bytes(bad.ready()).length+db.bytes(new Membership(List.of())).length+2));
    for(int attempt=1;attempt<=3;attempt++) {
      HostDatabase cold=new HostDatabase(pool,scope,"reviewer",0,capacity);
      PostgresPrefixStore reader=PostgresPrefixStore.reader(cold);
      if(reader.read("Q",Kind.READY,-1,1).size()!=1)throw new AssertionError("good independent root unavailable");
      try{reader.materializeReady(1,1);System.out.println("STARVATION unexpected advance");}
      catch(Hold e){System.out.println("STARVATION cold_attempt="+attempt+" hold="+e.getMessage()+" queue="+db.jdbc.queryForList("SELECT source,next_ordinal,turn FROM dh_prefix_ready_queue WHERE scope=? ORDER BY turn",db.scope));}
    }
    System.out.println("STARVATION healthy_work_materialized="+db.jdbc.queryForObject("SELECT count(*) FROM dh_work WHERE scope=? AND key='good-ready'",Long.class,db.scope));
  }
}
