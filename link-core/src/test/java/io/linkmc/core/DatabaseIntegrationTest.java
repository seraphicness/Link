package io.linkmc.core;

import io.linkmc.api.LinkApi.*;
import org.junit.jupiter.api.*;
import java.sql.DriverManager;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static io.linkmc.core.Database.*;

/** Run against a disposable PostgreSQL database, never a production database. */
class DatabaseIntegrationTest {
    Database db;
    EventStore events;
    SessionStore sessions;
    NetworkStore network;
    @BeforeEach void setup() throws Exception {
        String url=System.getenv("LINK_TEST_JDBC_URL");
        Assumptions.assumeTrue(url!=null,"Set LINK_TEST_JDBC_URL for PostgreSQL integration tests");
        db=new Database(() -> DriverManager.getConnection(url,System.getenv("LINK_TEST_DB_USER"),System.getenv("LINK_TEST_DB_PASSWORD")));
        db.migrate();
        db.transaction(c -> {update(c,"TRUNCATE link_reservations,link_channels,link_nodes,link_transfers,link_sessions,link_outbox,link_events,link_state,link_operations,link_templates,link_warps CASCADE");return null;});
        events=new EventStore(db);sessions=new SessionStore(db,30);network=new NetworkStore(db);
    }
    private Transaction change(Session s,UUID op,long expected,String value) {
        return new Transaction(op,List.of(s),List.of(new Change("player:"+s.player(),expected,1,"test:set",value.getBytes(java.nio.charset.StandardCharsets.UTF_8))));
    }
    @Test void duplicateDoesNotCreateSecondEvent() throws Exception {
        Session s=sessions.acquire(UUID.randomUUID(),"a");
        Transaction tx=change(s,UUID.randomUUID(),0,"diamond");
        assertFalse(events.commit(tx).duplicate());assertTrue(events.commit(tx).duplicate());
        assertEquals(1,events.history("player:"+s.player(),0,10).size());
        assertThrows(ConflictException.class,() -> events.commit(change(s,tx.operation(),0,"gold")));
    }
    @Test void allParticipantsRollbackOnConflict() throws Exception {
        Session s=sessions.acquire(UUID.randomUUID(),"a");
        events.commit(change(s,UUID.randomUUID(),0,"initial"));
        Transaction tx=new Transaction(UUID.randomUUID(),List.of(s),List.of(
                new Change("container:1",0,1,"take",new byte[]{0}),
                new Change("player:"+s.player(),0,1,"give",new byte[]{1})));
        assertThrows(ConflictException.class,() -> events.commit(tx));
        assertTrue(events.read("container:1").isEmpty());
    }
    @Test void transferFencesSourceAndIsIdempotent() throws Exception {
        UUID player=UUID.randomUUID();Session source=sessions.acquire(player,"a");
        events.commit(change(source,UUID.randomUUID(),0,"item"));
        UUID transferId=UUID.randomUUID();Destination destination=new Destination("b","spawn-2",0,64,0,0,0);
        Transfer transfer=sessions.prepare(source,transferId,destination);
        assertEquals(1,transfer.finalVersion());
        assertEquals(transfer,sessions.prepare(source,transferId,destination));
        assertThrows(ConflictException.class,() -> events.commit(change(source,UUID.randomUUID(),1,"stale")));
        assertThrows(ConflictException.class,() -> sessions.accept(player,transferId,"wrong"));
        Session target=sessions.accept(player,transferId,"b");assertEquals(source.epoch()+1,target.epoch());
        assertEquals(target,sessions.accept(player,transferId,"b"));
        assertThrows(ConflictException.class,() -> sessions.release(source));
        events.commit(change(target,UUID.randomUUID(),1,"new"));
    }
    @Test void reconnectNeverSharesAnActiveOwner() throws Exception {
        UUID player=UUID.randomUUID();Session owner=sessions.acquire(player,"a");
        assertThrows(ConflictException.class,() -> sessions.acquire(player,"a"));
        assertThrows(ConflictException.class,() -> sessions.acquire(player,"b"));
        sessions.release(owner);Session next=sessions.acquire(player,"b");
        assertTrue(next.epoch()>owner.epoch());
        assertThrows(ConflictException.class,() -> sessions.renew(owner));
    }
    @Test void expiredLeaseCannotWrite() throws Exception {
        Session s=sessions.acquire(UUID.randomUUID(),"a");
        db.transaction(c -> {update(c,"UPDATE link_sessions SET expires_at=clock_timestamp()-interval '1 second'");return null;});
        assertThrows(ConflictException.class,() -> events.commit(change(s,UUID.randomUUID(),0,"bad")));
        Session next=sessions.acquire(s.player(),"b");assertEquals(s.epoch()+1,next.epoch());
    }
    @Test void replayRestoresExactPayloadWithoutCallbacks() throws Exception {
        Session s=sessions.acquire(UUID.randomUUID(),"a");
        events.commit(change(s,UUID.randomUUID(),0,"one"));events.commit(change(s,UUID.randomUUID(),1,"two"));
        assertArrayEquals("one".getBytes(),events.replay("player:"+s.player(),1).orElseThrow().payload());
        assertEquals(2,events.read("player:"+s.player()).orElseThrow().version());
    }
    @Test void concurrentWritersOnlyOneWinsExpectedVersion() throws Exception {
        Session s=sessions.acquire(UUID.randomUUID(),"a");
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Boolean>> attempts=new ArrayList<>();
            for(int i=0;i<20;i++) attempts.add(pool.submit(() -> {try {events.commit(change(s,UUID.randomUUID(),0,"x"));return true;}catch(ConflictException e){return false;}}));
            int count=0;for(var f:attempts) if(f.get())count++;
            assertEquals(1,count);
        }
    }
    @Test void concurrentAdmissionsRespectWorldAndNodeLimits() throws Exception {
        network.heartbeat("a",60);
        network.register("one",new Destination("a","spawn-1",0,64,0,0,0),50);
        network.register("two",new Destination("a","spawn-2",0,64,0,0,0),50);
        try(var pool=Executors.newFixedThreadPool(12)) {
            List<Future<Boolean>> attempts=new ArrayList<>();
            for(int i=0;i<80;i++) attempts.add(pool.submit(() -> {try {network.reserve(UUID.randomUUID(),UUID.randomUUID());return true;}catch(ConflictException e){return false;}}));
            int count=0;for(var f:attempts) if(f.get()) count++;
            assertEquals(60,count);
        }
        var channels=network.channels();assertTrue(channels.stream().allMatch(c -> c.reserved()<=50));
        assertEquals(60,channels.stream().mapToInt(Channel::reserved).sum());
    }
    @Test void reservationRetryDoesNotConsumeAnotherSlot() throws Exception {
        network.heartbeat("a",1);network.register("one",new Destination("a","spawn",0,64,0,0,0),1);
        UUID player=UUID.randomUUID(),op=UUID.randomUUID();
        assertEquals(network.reserve(player,op),network.reserve(player,op));
        Session owner=sessions.acquire(player,"a");network.admit(op,owner);
        assertThrows(ConflictException.class,() -> network.reserve(UUID.randomUUID(),UUID.randomUUID()));
        network.depart(owner);sessions.release(owner);
        assertNotNull(network.reserve(UUID.randomUUID(),UUID.randomUUID()));
    }
    @Test void templatesCannotBeOverwrittenAndVerifyDigest() throws Exception {
        TemplateStore store=new TemplateStore(db);byte[] original={1,2,3};
        store.publish("lobby",1,original);store.publish("lobby",1,original);
        assertThrows(ConflictException.class,() -> store.publish("lobby",1,new byte[]{4}));
        assertArrayEquals(original,store.read("lobby",1));
        db.transaction(c -> {update(c,"UPDATE link_templates SET payload=?",new byte[]{9});return null;});
        assertThrows(ConflictException.class,() -> store.read("lobby",1));
    }
    @Test void transferKeepsSourceAdmissionUntilTargetOwnsPlayer() throws Exception {
        network.heartbeat("a",1);network.heartbeat("b",1);
        network.register("a-spawn",new Destination("a","spawn",0,64,0,0,0),1);
        network.register("b-spawn",new Destination("b","spawn",0,64,0,0,0),1);
        UUID player=UUID.randomUUID();Session source=sessions.acquire(player,"a");
        var first=network.reserve(player,UUID.randomUUID());network.admit(first.id(),source);
        var second=network.reserve(player,UUID.randomUUID());assertEquals("b",second.destination().node());
        assertEquals(1,network.channels().stream().filter(c -> c.node().equals("a")).findFirst().orElseThrow().occupied());
        UUID transfer=UUID.randomUUID();sessions.prepare(source,transfer,second.destination());
        Session target=sessions.accept(player,transfer,"b");network.admit(second.id(),target);
        assertEquals(0,network.channels().stream().filter(c -> c.node().equals("a")).findFirst().orElseThrow().occupied());
        assertEquals(1,network.channels().stream().filter(c -> c.node().equals("b")).findFirst().orElseThrow().occupied());
    }
    @Test void payloadsAreDefensivelyCopied() {
        byte[] bytes={1};Change change=new Change("test:a",0,1,"set",bytes);
        bytes[0]=9;change.after()[0]=8;assertEquals(1,change.after()[0]);
    }
    @Test void redisRedeliveryDoesNotDuplicateCommittedState() throws Exception {
        String uri=System.getenv("LINK_TEST_REDIS_URI");
        Assumptions.assumeTrue(uri!=null,"Set LINK_TEST_REDIS_URI for Redis integration test");
        Session owner=sessions.acquire(UUID.randomUUID(),"a");
        UUID op=UUID.randomUUID();events.commit(change(owner,op,0,"durable"));
        try(RedisOutbox relay=new RedisOutbox(db,uri)) {
            assertEquals(1,relay.flush(10));assertEquals(0,relay.flush(10));
            // Simulate a crash after Redis accepted the message but before SQL acknowledged it.
            db.transaction(c -> {update(c,"UPDATE link_outbox SET delivered=false WHERE id=?",op);return null;});
            assertEquals(1,relay.flush(10));
        }
        assertEquals(1,events.history("player:"+owner.player(),0,10).size());
        assertArrayEquals("durable".getBytes(),events.read("player:"+owner.player()).orElseThrow().payload());
    }
    @Test void sqlFailureRollsBackEventAndSnapshotTogether() throws Exception {
        Session owner=sessions.acquire(UUID.randomUUID(),"a");
        db.transaction(c -> {update(c,"ALTER TABLE link_outbox ADD CONSTRAINT test_reject CHECK (false)");return null;});
        try {
            assertThrows(java.sql.SQLException.class,() -> events.commit(change(owner,UUID.randomUUID(),0,"must-not-commit")));
            assertTrue(events.read("player:"+owner.player()).isEmpty());
            assertTrue(events.history("player:"+owner.player(),0,10).isEmpty());
        }finally{db.transaction(c -> {update(c,"ALTER TABLE link_outbox DROP CONSTRAINT test_reject");return null;});}
    }
    @Test void expiredReservationCannotBeReused() throws Exception {
        network.heartbeat("a",1);network.register("one",new Destination("a","spawn",0,64,0,0,0),1);
        UUID player=UUID.randomUUID(),request=UUID.randomUUID();network.reserve(player,request);
        db.transaction(c -> {update(c,"UPDATE link_reservations SET expires_at=clock_timestamp()-interval '1 second'");return null;});
        assertThrows(ConflictException.class,() -> network.reserve(player,request));
        assertNotNull(network.reserve(player,UUID.randomUUID()));
    }
}
