package io.linkmc.core;

import io.linkmc.api.LinkApi;
import java.util.*;
import java.util.concurrent.*;

/** Bounded async facade. The caller owns DB pool and server-thread dispatch. */
public final class LinkService implements LinkApi, AutoCloseable {
    private final EventStore events;
    private final SessionStore sessions;
    private final NetworkStore network;
    private final RouteStore routes;
    private final ExecutorService executor;
    public LinkService(Database database) {
        events=new EventStore(database); sessions=new SessionStore(database,30); network=new NetworkStore(database);
        routes=new RouteStore(database);
        // FIFO submission order within one runtime. Across runtimes, versions and
        // fencing reject stale work; callers must explicitly reconcile conflicts.
        executor=new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(1024),
                Thread.ofPlatform().name("Link-Database-",0).factory(),new ThreadPoolExecutor.AbortPolicy());
    }
    @FunctionalInterface private interface Task<T> { T run() throws Exception; }
    private <T> CompletionStage<T> async(Task<T> task) {
        try {
            return CompletableFuture.supplyAsync(() -> {
                try {return task.run();} catch(Exception e) {throw new CompletionException(e);}
            },executor);
        } catch(RejectedExecutionException e) { return CompletableFuture.failedFuture(e); }
    }
    @Override public Data data() { return new Data() {
        public CompletionStage<Optional<State>> read(String id) { return async(() -> events.read(id)); }
        public CompletionStage<Commit> commit(Transaction tx) { return async(() -> {
            if(tx.changes().stream().anyMatch(c->c.id().startsWith("native:")||c.id().startsWith("player:")))
                throw new IllegalArgumentException("Native state is writable only through engine().transact");
            return events.commit(tx);
        }); }
        public CompletionStage<List<Event>> history(String id,long after,int limit) { return async(() -> events.history(id,after,limit)); }
    }; }
    @Override public Sessions sessions() { return new Sessions() {
        public CompletionStage<Session> acquire(UUID player,String node) {return async(() -> sessions.acquire(player,node));}
        public CompletionStage<Session> renew(Session session) {return async(() -> sessions.renew(session));}
        public CompletionStage<Void> release(Session session) {return async(() -> {sessions.release(session); return null;});}
        public CompletionStage<Transfer> prepare(Session s,UUID op,Destination d) {return async(() -> sessions.prepare(s,op,d));}
        public CompletionStage<Session> accept(UUID p,UUID t,String node) {return async(() -> sessions.accept(p,t,node));}
    }; }
    @Override public Network network() { return new Network() {
        public CompletionStage<List<Channel>> channels() {return async(network::channels);}
        public CompletionStage<Void> registerChannel(String id,Destination spawn,int capacity){return async(()->{network.register(id,spawn,capacity);return null;});}
        public CompletionStage<Void> setChannelReady(String id,boolean ready){return async(()->{network.ready(id,ready);return null;});}
        public CompletionStage<Reservation> reserve(UUID player,UUID request) {return async(() -> network.reserve(player,request));}
        public CompletionStage<Reservation> reserve(UUID player,UUID request,Destination destination) {return async(() -> network.reserve(player,request,destination));}
        public CompletionStage<Void> cancel(UUID r,UUID p) {return async(() -> {network.cancel(r,p);return null;});}
        public CompletionStage<Optional<Destination>> warp(String name) {return async(() -> network.warp(name));}
        public CompletionStage<Void> setWarp(String name,Destination d) {return async(() -> {network.setWarp(name,d); return null;});}
        public CompletionStage<Reservation> requestTravel(UUID player,UUID operation,Destination destination){return async(()->{
            Reservation reservation=network.reserve(player,operation,destination);
            try{routes.request(operation,reservation);return reservation;}
            catch(Exception error){try{network.cancel(operation,player);}catch(Exception cleanup){error.addSuppressed(cleanup);}throw error;}
        });}
        public CompletionStage<String> travelStatus(UUID operation){return async(()->routes.status(operation));}
    }; }
    @Override public void close() {
        executor.shutdown();
        try { if(!executor.awaitTermination(15,TimeUnit.SECONDS)) executor.shutdownNow(); }
        catch(InterruptedException e) {executor.shutdownNow();Thread.currentThread().interrupt();}
    }
}
