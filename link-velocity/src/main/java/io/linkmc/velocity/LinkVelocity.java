package io.linkmc.velocity;

import com.google.inject.Inject;
import com.velocitypowered.api.event.*;
import com.velocitypowered.api.event.player.*;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.proxy.*;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.proxy.*;
import com.velocitypowered.api.command.SimpleCommand;
import io.linkmc.core.*;
import io.linkmc.api.LinkApi.*;
import net.kyori.adventure.text.Component;
import org.slf4j.Logger;
import java.sql.DriverManager;
import java.util.*;
import java.util.concurrent.*;

/** Backend names in velocity.toml must equal LINK_NODE_ID; modern forwarding is required. */
@Plugin(id="link",name="Link",version="0.1.0-SNAPSHOT",description="Link transactional backend routing")
public final class LinkVelocity {
    private record Pending(UUID ticket,Reservation reservation){}
    private final ProxyServer proxy;
    private final Logger logger;
    private final ExecutorService worker=new ThreadPoolExecutor(4,4,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(256),Thread.ofPlatform().name("Link-Proxy-",0).factory(),new ThreadPoolExecutor.AbortPolicy());
    private final Map<UUID,Pending> pending=new ConcurrentHashMap<>();
    private final Set<UUID> travelling=ConcurrentHashMap.newKeySet();
    private NetworkStore network;
    private ArrivalStore arrivals;
    private RouteStore routes;
    private Database database;
    private LinkService service;
    public io.linkmc.api.LinkApi api(){return Objects.requireNonNull(service,"Link proxy not initialized");}
    private final java.util.concurrent.atomic.AtomicBoolean polling=new java.util.concurrent.atomic.AtomicBoolean();
    @Inject public LinkVelocity(ProxyServer proxy,Logger logger){this.proxy=proxy;this.logger=logger;}
    @Subscribe public void initialize(ProxyInitializeEvent event)throws Exception{
        String url=required("LINK_JDBC_URL"),user=required("LINK_DB_USER"),password=required("LINK_DB_PASSWORD");
        Database db=Database.pooled(url,user,password);database=db;db.migrate();
        service=new LinkService(db);RuntimeProvider.install(service);
        network=new NetworkStore(db);arrivals=new ArrivalStore(db);routes=new RouteStore(db);
        proxy.getScheduler().buildTask(this,()->{
            if(!polling.compareAndSet(false,true))return;
            try{worker.execute(()->{try{dispatchReady();}catch(Exception e){logger.error("Link route dispatch failed",e);}finally{polling.set(false);}});}
            catch(RejectedExecutionException e){polling.set(false);}
        }).repeat(java.time.Duration.ofMillis(500)).schedule();
        proxy.getCommandManager().register(proxy.getCommandManager().metaBuilder("spawn").plugin(this).build(),new SimpleCommand(){
            public void execute(Invocation invocation){if(invocation.source() instanceof Player player)travel(player,null);}
        });
        proxy.getCommandManager().register(proxy.getCommandManager().metaBuilder("warp").plugin(this).build(),new SimpleCommand(){
            public void execute(Invocation invocation){if(invocation.source() instanceof Player player&&invocation.arguments().length==1)travel(player,invocation.arguments()[0]);}
            public boolean hasPermission(Invocation invocation){return invocation.source().hasPermission("link.warp");}
        });
    }
    @Subscribe public EventTask initial(PlayerChooseInitialServerEvent event){
        return EventTask.withContinuation(continuation->{
            try{worker.execute(()->{
                Player player=event.getPlayer();
                try{
                    UUID ticket=UUID.randomUUID();Reservation reservation=network.reserve(player.getUniqueId(),ticket);
                    Pending request=new Pending(ticket,reservation);pending.put(player.getUniqueId(),request);
                    var target=proxy.getServer(reservation.destination().node()).orElseThrow(()->new IllegalStateException("Unregistered Link backend"));
                    arrivals.issue(ticket,reservation,null);event.setInitialServer(target);
                }catch(Exception e){logger.error("Link initial allocation failed",e);player.disconnect(Component.text("스폰을 준비하지 못했습니다. 잠시 후 다시 접속해 주세요."));cleanup(player.getUniqueId());}
                finally{continuation.resume();}
            });}catch(RejectedExecutionException e){event.getPlayer().disconnect(Component.text("접속 요청이 많습니다. 잠시 후 다시 시도해 주세요."));continuation.resume();}
        });
    }
    @Subscribe(order=PostOrder.LAST) public void preConnect(ServerPreConnectEvent event){
        Pending request=pending.get(event.getPlayer().getUniqueId());
        if(request==null||!request.reservation().destination().node().equals(event.getResult().getServer().map(s->s.getServerInfo().getName()).orElse(""))){
            event.setResult(ServerPreConnectEvent.ServerResult.denied());
        }
    }
    @Subscribe public void connected(ServerPostConnectEvent event){pending.remove(event.getPlayer().getUniqueId());travelling.remove(event.getPlayer().getUniqueId());}
    @Subscribe public void disconnected(DisconnectEvent event){
        travelling.remove(event.getPlayer().getUniqueId());
        try{worker.execute(()->cleanup(event.getPlayer().getUniqueId()));}catch(RejectedExecutionException ignored){}
    }
    private void travel(Player player,String warp){
        UUID id=player.getUniqueId();if(!travelling.add(id))return;
        try{worker.execute(()->{
            try{
                UUID ticket=UUID.randomUUID();Destination destination=warp==null?null:network.warp(warp).orElseThrow(()->new IllegalArgumentException("Unknown warp"));
                Reservation reservation=network.reserve(id,ticket,destination);
                pending.put(id,new Pending(ticket,reservation));
                if(proxy.getServer(reservation.destination().node()).isEmpty())throw new IllegalStateException("Unregistered Link backend");
                routes.request(ticket,reservation);
            }catch(Exception e){logger.error("Link transfer failed",e);cleanup(id);travelling.remove(id);player.disconnect(Component.text("서버 이동을 완료하지 못했습니다. 잠시 후 다시 접속해 주세요."));}
        });}catch(RejectedExecutionException e){travelling.remove(id);player.sendMessage(Component.text("이동 요청이 많습니다. 잠시 후 다시 시도해 주세요."));}
    }
    private void dispatchReady()throws Exception{
        var players=proxy.getAllPlayers().stream().map(Player::getUniqueId).toList();
        for(var route:routes.readyFor(players)){
            Player player=proxy.getPlayer(route.reservation().player()).orElse(null);
            if(player==null||!player.isActive()||!routes.dispatch(route.id()))continue;
            UUID id=player.getUniqueId();pending.put(id,new Pending(route.id(),route.reservation()));travelling.add(id);
            try{
                var target=proxy.getServer(route.reservation().destination().node()).orElseThrow(()->new IllegalStateException("Unregistered Link backend"));
                if(player.getCurrentServer().map(s->s.getServerInfo().getName().equals(route.reservation().destination().node())).orElse(false)){
                    routes.finish(route.id(),true);pending.remove(id);travelling.remove(id);continue;
                }
                player.createConnectionRequest(target).connect().whenComplete((result,error)->{
                    boolean success=error==null&&result.isSuccessful();
                    try{worker.execute(()->{try{routes.finish(route.id(),success);}catch(Exception e){logger.error("Route completion recording failed",e);}});}catch(RejectedExecutionException ignored){}
                    if(!success)player.disconnect(Component.text("서버 이동에 실패했습니다. 다시 접속해 주세요."));
                });
            }catch(Exception e){routes.finish(route.id(),false);cleanup(id);travelling.remove(id);player.disconnect(Component.text("서버 이동에 실패했습니다. 다시 접속해 주세요."));logger.error("Link route failed",e);}
        }
        for(var entry:List.copyOf(pending.entrySet()))if(entry.getValue().reservation().expiresAt().isBefore(java.time.Instant.now())){
            proxy.getPlayer(entry.getKey()).ifPresent(p->p.disconnect(Component.text("이동 예약이 만료되었습니다. 다시 접속해 주세요.")));cleanup(entry.getKey());travelling.remove(entry.getKey());
        }
    }
    private void cleanup(UUID id){
        Pending request=pending.remove(id);if(request==null)return;
        try{routes.finish(request.ticket(),false);arrivals.cancel(request.ticket(),id);network.cancel(request.reservation().id(),id);}catch(Exception e){logger.warn("Arrival cleanup deferred until expiry",e);}
    }
    @Subscribe public void shutdown(ProxyShutdownEvent event){worker.shutdownNow();if(service!=null){RuntimeProvider.uninstall(service);service.close();}if(database!=null)database.close();}
    private static String required(String name){String value=System.getenv(name);if(value==null||value.isBlank())throw new IllegalStateException("Missing "+name);return value;}
}
