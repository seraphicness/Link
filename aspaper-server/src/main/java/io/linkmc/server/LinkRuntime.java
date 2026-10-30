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
    private volatile Session nodeLease;
    private final ScheduledExecutorService heartbeat=Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("Link-Heartbeat").factory());
    private LinkRuntime(Database db,String node)throws Exception {
        leases=new SessionStore(db,30);
        nodeLease=leases.acquire(UUID.nameUUIDFromBytes(("link-node:"+node).getBytes(StandardCharsets.UTF_8)),node);
        service=new LinkService(db);
        driver=new LinkWorldDriver(new EventStore(db),nodeLease,node);
        worlds=new WorldService(driver,new TemplateStore(db));
    }
    /** Called from PaperWorldLoader before any default dimension is constructed. */
    public static void beforeWorlds(){
        String mode=System.getProperty("link.mode","disabled");
        if(mode.equals("disabled"))return;
        if(!mode.equals("tools"))throw new IllegalStateException("Link strict gameplay mode is not implemented: native inventory/world transaction hooks are incomplete. Refusing unsafe startup.");
        if(instance!=null)throw new IllegalStateException("Link initialized twice");
        try {
            String url=required("LINK_JDBC_URL"),user=required("LINK_DB_USER"),password=required("LINK_DB_PASSWORD");
            String node=required("LINK_NODE_ID");LinkApi.requireName(node);
            Database db=new Database(() -> DriverManager.getConnection(url,user,password));db.migrate();
            LinkRuntime runtime=new LinkRuntime(db,node);
            try {
                String template=System.getProperty("link.spawn.template","lobby");
                int version=Integer.getInteger("link.spawn.version",1);
                byte[] bytes=new TemplateStore(db).read(template,version);
                SlimeNMSBridge.instance().setDefaultWorlds(runtime.driver.bootstrap("link-spawn",bytes),null,null);
                instance=runtime;RuntimeProvider.install(runtime);
                runtime.heartbeat.scheduleWithFixedDelay(() -> {
                    try {runtime.nodeLease=runtime.leases.renew(runtime.nodeLease);}
                    catch(Exception e){
                        net.minecraft.server.MinecraftServer.LOGGER.error("Link lost its DB lease; stopping to fence this backend",e);
                        org.bukkit.Bukkit.shutdown();
                    }
                },5,5,TimeUnit.SECONDS);
            } catch(Exception e){runtime.close();throw e;}
        }catch(Exception e){throw new IllegalStateException("Link bootstrap failed; no fallback world will be created",e);}
    }
    public static boolean managesWorlds(){return instance!=null;}
    public static void afterWorlds(){if(instance!=null)LinkCommand.register(instance);}
    private static String required(String name){String value=System.getenv(name);if(value==null||value.isBlank())throw new IllegalStateException("Missing "+name);return value;}
    public static void stop(){LinkRuntime runtime=instance;if(runtime!=null){instance=null;RuntimeProvider.uninstall(runtime);runtime.close();}}
    private void close(){
        worlds.close();
        try {driver.saveOnShutdown();}
        catch(Exception e){net.minecraft.server.MinecraftServer.LOGGER.error("Link final checkpoint failed; ownership will not be explicitly released",e);}
        finally {heartbeat.shutdownNow();service.close();}
    }
    public Data data(){return service.data();}
    public Sessions sessions(){return service.sessions();}
    public Network network(){return service.network();}
    public Worlds worlds(){return worlds;}
}
