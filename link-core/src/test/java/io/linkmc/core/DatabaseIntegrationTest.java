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
        db.transaction(c -> {for(String table:"link_routes,link_arrivals,link_reservations,link_channels,link_nodes,link_transfers,link_sessions,link_outbox,link_events,link_state,link_operations,link_templates,link_warps".split(","))update(c,"DELETE FROM "+table);return null;});
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
        assertTrue(store.find("lobby",1).isEmpty());
        assertThrows(ConflictException.class,() -> store.read("lobby",1));
        store.publish("lobby",1,original);store.publish("lobby",1,original);
        assertThrows(ConflictException.class,() -> store.publish("lobby",1,new byte[]{4}));
        assertArrayEquals(original,store.read("lobby",1));
        db.transaction(c -> {update(c,"UPDATE link_templates SET payload=?",new byte[]{9});return null;});
        assertThrows(ConflictException.class,() -> store.read("lobby",1));
        assertThrows(ConflictException.class,() -> store.find("lobby",1));
    }
    @Test void failedObjectUploadNeverPublishesTemplate()throws Exception{
        var storage=new WorldBlobsTest.Memory();storage.unavailable=true;db.worldStorage(storage);
        var templates=new TemplateStore(db);
        assertThrows(java.io.IOException.class,()->templates.publish("failed",1,new byte[]{1}));
        assertTrue(templates.find("failed",1).isEmpty());
        storage.unavailable=false;templates.publish("ok",1,new byte[]{1,2});
        assertArrayEquals(new byte[]{1,2},templates.read("ok",1));
    }
    @Test void leaseInstantIsIndependentOfJvmTimeZone()throws Exception{
        var before=TimeZone.getDefault();
        try{
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Seoul"));
            var owner=sessions.acquire(UUID.randomUUID(),"timezone-test");
            long remaining=java.time.Duration.between(java.time.Instant.now(),owner.expiresAt()).toSeconds();
            assertTrue(remaining>=20&&remaining<=31,"Lease must be about 30 seconds, not shifted by hours");
        }finally{TimeZone.setDefault(before);}
    }
    @Test void transferKeepsSourceAdmissionUntilTargetOwnsPlayer() throws Exception {
        network.heartbeat("a",1);network.heartbeat("b",1);
        network.register("a-spawn",new Destination("a","spawn",0,64,0,0,0),1);
        network.register("b-spawn",new Destination("b","spawn",0,64,0,0,0),1);
        UUID player=UUID.randomUUID();Session source=sessions.acquire(player,"a");
        var first=network.reserve(player,UUID.randomUUID());network.admit(first.id(),source);
        var second=network.reserve(player,UUID.randomUUID(),new Destination("b","spawn",0,64,0,0,0));assertEquals("b",second.destination().node());
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
    @Test void arrivalClaimIsAtomicSingleUseAndBoundToBackend()throws Exception{
        network.heartbeat("a",2);network.register("one",new Destination("a","spawn",2,65,3,0,0),2);
        UUID player=UUID.randomUUID(),ticket=UUID.randomUUID();var reservation=network.reserve(player,ticket);
        ArrivalStore arrivals=new ArrivalStore(db);arrivals.issue(ticket,reservation,null);
        assertThrows(ConflictException.class,()->arrivals.claim(player,"b"));
        var claimed=arrivals.claim(player,"a");assertEquals(reservation.destination(),claimed.destination());
        assertThrows(ConflictException.class,()->arrivals.claim(player,"a"));
        assertEquals(1,network.channels().getFirst().occupied());
    }
    @Test void invalidAdmissionRollsBackOwnershipAcquisition()throws Exception{
        network.heartbeat("a",1);network.register("one",new Destination("a","spawn",0,65,0,0,0),1);
        UUID player=UUID.randomUUID(),ticket=UUID.randomUUID();var reservation=network.reserve(player,ticket);
        ArrivalStore arrivals=new ArrivalStore(db);arrivals.issue(ticket,reservation,null);network.ready("one",false);
        assertThrows(ConflictException.class,()->arrivals.claim(player,"a"));
        // The failed claim must not leave an ACTIVE owner or consume the ticket.
        network.ready("one",true);assertNotNull(arrivals.claim(player,"a"));
    }
    @Test void warpPositionIsImmutableAfterReservation()throws Exception{
        network.heartbeat("a",2);network.register("one",new Destination("a","spawn",0,65,0,0,0),2);
        UUID player=UUID.randomUUID(),ticket=UUID.randomUUID();Destination warp=new Destination("a","spawn",8,70,9,90,0);
        var reservation=network.reserve(player,ticket,warp);
        network.register("one",new Destination("a","spawn",100,65,100,0,0),2);
        ArrivalStore arrivals=new ArrivalStore(db);arrivals.issue(ticket,reservation,null);
        assertEquals(warp,arrivals.claim(player,"a").destination());
    }
    @Test void expiredTransferCanReconnectButOldDestinationCannotClaim()throws Exception{
        UUID player=UUID.randomUUID(),transfer=UUID.randomUUID();
        Session source=sessions.acquire(player,"a");
        sessions.prepare(source,transfer,new Destination("b","spawn",0,65,0,0,0));
        assertThrows(ConflictException.class,()->sessions.acquire(player,"c"));
        db.transaction(c->{update(c,"UPDATE link_sessions SET expires_at=clock_timestamp()-interval '1 second' WHERE player=?",player);return null;});
        assertThrows(ConflictException.class,()->sessions.accept(player,transfer,"b"));
        Session recovered=sessions.acquire(player,"c");assertEquals(source.epoch()+1,recovered.epoch());
        assertThrows(ConflictException.class,()->sessions.accept(player,transfer,"b"));
        assertThrows(ConflictException.class,()->events.commit(change(source,UUID.randomUUID(),0,"stale")));
        assertNotNull(sessions.prepare(recovered,UUID.randomUUID(),new Destination("d","spawn",0,65,0,0,0)));
    }
    @Test void channelIdentityAndReservationDestinationCannotBeReused()throws Exception{
        network.heartbeat("a",2);network.heartbeat("b",2);
        Destination spawn=new Destination("a","spawn",0,65,0,0,0);network.register("one",spawn,2);
        assertThrows(ConflictException.class,()->network.register("one",new Destination("b","spawn",0,65,0,0,0),2));
        UUID player=UUID.randomUUID(),request=UUID.randomUUID();network.reserve(player,request,spawn);
        assertThrows(ConflictException.class,()->network.reserve(player,request,new Destination("a","spawn",99,65,0,0,0)));
    }
    @Test void routeDispatchHasOneWinnerAndCompletionIsTerminal()throws Exception{
        network.heartbeat("a",2);network.register("one",new Destination("a","spawn",0,65,0,0,0),2);
        UUID player=UUID.randomUUID(),operation=UUID.randomUUID();sessions.acquire(player,"a");
        var reservation=network.reserve(player,operation);RouteStore routes=new RouteStore(db);
        routes.request(operation,reservation);routes.request(operation,reservation);
        assertEquals(1,routes.pending("a").size());assertTrue(routes.readyFor(List.of(player)).isEmpty());
        routes.ready(operation);assertEquals(1,routes.readyFor(List.of(player)).size());
        assertTrue(routes.readyFor(List.of(UUID.randomUUID())).isEmpty());
        try(var executor=Executors.newFixedThreadPool(4)){
            var attempts=new ArrayList<Future<Boolean>>();for(int i=0;i<8;i++)attempts.add(executor.submit(()->routes.dispatch(operation)));
            int winners=0;for(var attempt:attempts)if(attempt.get())winners++;assertEquals(1,winners);
        }
        routes.finish(operation,true);routes.finish(operation,false);assertEquals("COMPLETE",routes.status(operation));
    }
    @Test void pooledConnectionRollsBackBeforeReuse()throws Exception{
        try(Database pooled=Database.pooled(System.getenv("LINK_TEST_JDBC_URL"),System.getenv("LINK_TEST_DB_USER"),System.getenv("LINK_TEST_DB_PASSWORD"))){
            assertThrows(java.sql.SQLException.class,()->pooled.transaction(c->{
                update(c,"INSERT INTO link_state VALUES ('pool-test',1,1,?)",new byte[]{1});
                throw new java.sql.SQLException("injected failure");
            }));
            assertTrue(new EventStore(pooled).read("pool-test").isEmpty());
            int value=pooled.transaction(c->{try(var s=sql(c,"SELECT 42");var r=s.executeQuery()){r.next();return r.getInt(1);}});
            assertEquals(42,value);
        }
    }
    @Test void fullChannelAllowsItsOwnPlayerToWarpWithoutOpeningAnExtraSlot()throws Exception{
        Destination spawn=new Destination("a","spawn",0,65,0,0,0);
        network.heartbeat("a",1);network.register("one",spawn,1);
        UUID player=UUID.randomUUID();Session owner=sessions.acquire(player,"a");
        network.admit(network.reserve(player,UUID.randomUUID()).id(),owner);
        network.reserve(player,UUID.randomUUID(),new Destination("a","spawn",5,65,5,0,0));
        var channel=network.channels().getFirst();assertEquals(1,channel.occupied());assertEquals(0,channel.reserved());
        assertThrows(ConflictException.class,()->network.reserve(UUID.randomUUID(),UUID.randomUUID()));
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints={0,1,2,3,4,5})
    void recoveryAtEveryTransferBoundaryFencesOldOwners(int boundary)throws Exception{
        network.heartbeat("a",50);network.heartbeat("b",50);
        Destination destination=new Destination("b","spawn",8,66,8,90,0);network.register("b:spawn",destination,50);
        UUID player=UUID.randomUUID(),operation=UUID.randomUUID();Session source=sessions.acquire(player,"a");
        events.commit(change(source,UUID.randomUUID(),0,"seven-diamonds"));
        var reservation=network.reserve(player,operation,destination);var routes=new RouteStore(db);var arrivals=new ArrivalStore(db);routes.request(operation,reservation);
        Session owner=source;
        if(boundary>=1)sessions.prepare(source,operation,destination);
        if(boundary>=2)arrivals.issue(operation,reservation,operation);
        if(boundary>=3)routes.ready(operation);
        if(boundary>=4)assertTrue(routes.dispatch(operation));
        if(boundary>=5)owner=arrivals.claim(player,"b").session();
        Session crashed=owner;
        db.transaction(c->{update(c,"UPDATE link_sessions SET expires_at=clock_timestamp()-interval '1 second' WHERE player=?",player);return null;});
        Session recovered=sessions.acquire(player,"c");assertTrue(recovered.epoch()>crashed.epoch());
        assertArrayEquals("seven-diamonds".getBytes(java.nio.charset.StandardCharsets.UTF_8),events.read("player:"+player).orElseThrow().payload());
        assertThrows(ConflictException.class,()->events.commit(change(source,UUID.randomUUID(),1,"stale-source")));
        assertThrows(ConflictException.class,()->events.commit(change(crashed,UUID.randomUUID(),1,"stale-destination")));
        if(boundary>=1)assertThrows(ConflictException.class,()->sessions.accept(player,operation,"b"));
        events.commit(change(recovered,UUID.randomUUID(),1,"seven-diamonds-recovered"));
        assertEquals(2,events.history("player:"+player,0,10).size());
    }
}
