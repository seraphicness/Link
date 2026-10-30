package io.linkmc.server;

import com.infernalsuite.asp.api.SlimeNMSBridge;
import io.linkmc.api.LinkApi;
import io.linkmc.core.*;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.util.UUID;
import java.util.concurrent.*;

/** Native bootstrap; tooling mode is explicitly NOT a durable gameplay implementation. */
public final class LinkRuntime implements LinkApi {
    private static volatile LinkRuntime instance;
    private final LinkService service;
    private final WorldService worlds;
    private final LinkWorldDriver driver;
    private final SessionStore leases;
    private final Database database;
    private final String node;
    private final NetworkStore networkStore;
    private RedisOutbox redis;
    private volatile boolean closing;
    private final ScheduledExecutorService notifications=Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("Link-Notifications").factory());
    private SpawnProvisioner provisioner;
    private volatile Session nodeLease;
    private final ScheduledExecutorService heartbeat=Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("Link-Heartbeat").factory());
    private LinkRuntime(Database db,String node)throws Exception {
        this.database=db;this.node=node;networkStore=new NetworkStore(db);
        leases=new SessionStore(db,30);
        nodeLease=NativeJournal.enabled()?NativeJournal.startupOwner():leases.acquire(UUID.nameUUIDFromBytes(("link-node:"+node).getBytes(StandardCharsets.UTF_8)),node);
        service=new LinkService(db);
        driver=new LinkWorldDriver(new EventStore(db),nodeLease,node);
        worlds=new WorldService(driver,new TemplateStore(db));
    }
    /** Called from PaperWorldLoader before any default dimension is constructed. */
    public static void beforeWorlds(){
        var config=LinkConfiguration.get();
        String mode=config.mode();
        if(mode.equals("disabled"))return;
        if(!mode.equals("tools")&&!mode.equals("journal"))throw new IllegalStateException("Link strict gameplay mode is not yet validated. Use journal only in an isolated integration environment.");
        if(instance!=null)throw new IllegalStateException("Link initialized twice");
        if(mode.equals("journal")){
            var forwarding=io.papermc.paper.configuration.GlobalConfiguration.get().proxies.velocity;
            if(!forwarding.enabled||!forwarding.onlineMode||forwarding.secret.isBlank())throw new IllegalStateException("Link journal requires authenticated Velocity modern forwarding");
        }
        try {
            String url=config.jdbcUrl(),user=config.username(),password=config.password();
            String node=config.node();
            Database db=NativeJournal.enabled()?NativeJournal.database():Database.pooled(url,user,password);db.migrate();
            LinkRuntime runtime=new LinkRuntime(db,node);
            try {
                String template=config.template();
                int version=config.templateVersion();
                byte[] bytes=new TemplateStore(db).read(template,version);
                SlimeNMSBridge.instance().setDefaultWorlds(runtime.driver.bootstrap("link-spawn",NativeJournal.worldOrTemplate("link-spawn",bytes)),null,null);
                instance=runtime;RuntimeProvider.install(runtime);
                runtime.heartbeat.scheduleWithFixedDelay(() -> {
                    try {
                        runtime.nodeLease=runtime.leases.renew(runtime.nodeLease);NativeJournal.renew();
                        if(NativeJournal.enabled()){
                            runtime.networkStore.heartbeat(node,config.nodeCapacity());
                            if(runtime.provisioner!=null)runtime.provisioner.ensureSpare(0,65,0,0,0).whenComplete((created,error)->{if(error!=null)net.minecraft.server.MinecraftServer.LOGGER.error("Link spawn provisioning failed",error);});
                        }
                    }
                    catch(Exception e){
                        net.minecraft.server.MinecraftServer.LOGGER.error("Link lost its DB lease; stopping to fence this backend",e);
                        org.bukkit.Bukkit.shutdown();
                    }
                },5,5,TimeUnit.SECONDS);
                NativeJournal.finishStartup();
            } catch(Exception e){runtime.close();throw e;}
        }catch(Exception e){throw new IllegalStateException("Link bootstrap failed; no fallback world will be created",e);}
    }
    public static boolean managesWorlds(){return instance!=null;}
    public static void afterWorlds(){
        if(instance==null)return;
        LinkRuntime runtime=instance;
        var config=LinkConfiguration.get();
        try{
            if(NativeJournal.enabled()){
                for(String name:NativeJournal.recoveredWorlds())if(!name.equals("link-spawn"))runtime.driver.restoreNative(name,NativeJournal.recoveredWorld(name));
                net.minecraft.server.MinecraftServer.getServer().linkRestoreSavedData();
                NativeJournal.activate();
                runtime.networkStore.heartbeat(runtime.node,config.nodeCapacity());
                runtime.networkStore.reconcileNode(runtime.nodeLease);
                for(var world:org.bukkit.Bukkit.getWorlds())if(world.getName().equals("link-spawn")||world.getName().startsWith("spawn-")){
                    var pos=world.getSpawnLocation();
                    runtime.networkStore.register(runtime.node+":"+world.getName(),new Destination(runtime.node,world.getName(),pos.getX(),pos.getY(),pos.getZ(),pos.getYaw(),pos.getPitch()),config.worldCapacity());
                }
                runtime.provisioner=new SpawnProvisioner(runtime.worlds,runtime.networkStore,runtime.node,config.template(),config.templateVersion(),config.worldCapacity(),config.maxWorlds());
            }
            String redisUri=config.redisUri();
            if(redisUri!=null&&!redisUri.isBlank())runtime.notifications.scheduleWithFixedDelay(()->runtime.deliverNotifications(redisUri),0,5,TimeUnit.SECONDS);
            LinkCommand.register(runtime);
        }catch(Exception e){throw new IllegalStateException("Link world activation failed",e);}
    }
    public static void stop(){LinkRuntime runtime=instance;if(runtime!=null){instance=null;RuntimeProvider.uninstall(runtime);runtime.close();}}
    static void fence(){LinkRuntime runtime=instance;if(runtime!=null)runtime.heartbeat.shutdownNow();}
    private synchronized void deliverNotifications(String uri){
        if(closing)return;
        try{
            if(redis==null)redis=new RedisOutbox(database,uri);
            redis.flush(64);
        }catch(Exception e){net.minecraft.server.MinecraftServer.LOGGER.warn("Redis delivery deferred; SQL outbox retained",e);}
    }
    private synchronized void closeNotifications(){if(redis!=null){redis.close();redis=null;}}
    private void close(){
        closing=true;notifications.shutdownNow();
        worlds.close();
        try {if(NativeJournal.enabled())NativeJournal.shutdown();else driver.saveOnShutdown();}
        catch(Exception e){net.minecraft.server.MinecraftServer.LOGGER.error("Link final checkpoint failed; ownership will not be explicitly released",e);}
        finally {heartbeat.shutdownNow();closeNotifications();service.close();database.close();}
    }
    public Data data(){return service.data();}
    public Sessions sessions(){return service.sessions();}
    public Network network(){return service.network();}
    public Worlds worlds(){return worlds;}
    public Engine engine(){return new Engine(){
        public boolean journalEnabled(){return NativeJournal.enabled();}
        public CompletionStage<Void> transact(Runnable mutation){
            java.util.Objects.requireNonNull(mutation);
            CompletableFuture<Void> result=new CompletableFuture<>();
            if(!NativeJournal.enabled())return CompletableFuture.failedFuture(new IllegalStateException("Native journal disabled"));
            if(!NativeJournal.watch(result))return result;
            net.minecraft.server.MinecraftServer.getServer().execute(()->{
                try{NativeJournal.run(()->{mutation.run();NativeJournal.complete(result,null);});}
                catch(Throwable error){result.completeExceptionally(error);throw error;}
            });
            return result;
        }
    };}
}
