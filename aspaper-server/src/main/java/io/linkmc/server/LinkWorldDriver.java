package io.linkmc.server;

import com.infernalsuite.asp.api.AdvancedSlimePaperAPI;
import com.infernalsuite.asp.api.SlimeNMSBridge;
import com.infernalsuite.asp.api.world.SlimeWorld;
import com.infernalsuite.asp.api.world.SlimeWorldInstance;
import com.infernalsuite.asp.api.world.properties.SlimeProperties;
import com.infernalsuite.asp.api.world.properties.SlimePropertyMap;
import io.linkmc.api.LinkApi.*;
import io.linkmc.core.*;
import net.minecraft.server.MinecraftServer;
import org.bukkit.Bukkit;
import org.bukkit.World;
import java.util.*;
import java.util.concurrent.*;

/** Native server adapter. Snapshot operations always capture on the server thread. */
public final class LinkWorldDriver implements WorldService.Driver {
    private final AdvancedSlimePaperAPI asp=AdvancedSlimePaperAPI.instance();
    private final EventStore store;
    private final Session guard;
    private final String node;
    private final Map<String,Boolean> managed=new ConcurrentHashMap<>();
    private final Map<String,Long> versions=new ConcurrentHashMap<>();
    public LinkWorldDriver(EventStore store,Session guard,String node){this.store=store;this.guard=guard;this.node=node;}
    private String key(String name){io.linkmc.api.LinkApi.requireName(name);return "world:"+node+"/"+name;}
    static <T> T main(Callable<T> task) throws Exception {
        if(Bukkit.isPrimaryThread())return task.call();
        if(MinecraftServer.getServer().isStopped())throw new IllegalStateException("Server stopped before world operation could run");
        var future=new CompletableFuture<T>();
        if(!NativeJournal.watch(future))return future.get();
        MinecraftServer.getServer().execute(() -> {if(future.isCancelled())return;try{NativeJournal.complete(future,task.call());}catch(Throwable e){future.completeExceptionally(e);if(NativeJournal.enabled())throw new IllegalStateException("World operation failed",e);}});
        // No timeout: otherwise a timed-out operation might still create a world later.
        try{return future.get();}catch(InterruptedException e){future.cancel(false);Thread.currentThread().interrupt();throw e;}
    }
    private SlimeWorldInstance live(String name){
        if(!managed.containsKey(name))throw new ConflictException("Not a Link-managed world: "+name);
        World world=Bukkit.getWorld(name);
        if(world==null)throw new ConflictException("World is not loaded");
        return Objects.requireNonNull(SlimeNMSBridge.instance().getInstance(world));
    }
    private static SlimePropertyMap properties(){
        SlimePropertyMap props=new SlimePropertyMap();
        props.setValue(SlimeProperties.SAVE_BLOCK_TICKS,true);
        props.setValue(SlimeProperties.SAVE_FLUID_TICKS,true);
        props.setValue(SlimeProperties.SAVE_POI,true);
        props.setValue(SlimeProperties.SPAWN_Y,65);
        return props;
    }
    public SlimeWorld bootstrap(String name,byte[] template)throws Exception {
        SlimeWorld world=asp.getSerializer().deserializeWorld(name,template,null,properties(),true);
        managed.put(name,false);return world;
    }
    public void restoreNative(String name,byte[] bytes)throws Exception{
        var world=asp.getSerializer().deserializeWorld(name,bytes,null,properties(),true);
        asp.loadWorld(world,true);managed.put(name,NativeJournal.persistentWorld(name));
    }
    public WorldInfo createEditor(String name)throws Exception {
        key(name);
        if(store.read(key(name)).isPresent())throw new ConflictException("Stored world already exists");
        return main(() -> {
            if(NativeJournal.knownWorld(name))throw new ConflictException("World identity already exists");
            SlimeWorld world=asp.createEmptyWorld(name,true,properties(),null);
            SlimeWorldInstance loaded=asp.loadWorld(world,true);
            managed.put(name,true);versions.put(name,0L);
            NativeJournal.createdWorld(name,true);
            loaded.getBukkitWorld().setAutoSave(false);
            loaded.getBukkitWorld().getWorldBorder().setSize(256);
            loaded.getBukkitWorld().getBlockAt(0,64,0).setType(org.bukkit.Material.BEDROCK);
            loaded.getBukkitWorld().setSpawnLocation(0,65,0);
            return new WorldInfo(name,true,0);
        });
    }
    public byte[] snapshot(String name)throws Exception {
        SlimeWorld copy=main(() -> {
            var result=live(name).getSerializableCopy();
            if(NativeJournal.enabled()){
                var saved=new net.minecraft.nbt.CompoundTag();
                ((org.bukkit.craftbukkit.CraftWorld)Bukkit.getWorld(name)).getHandle().getDataStorage().linkSnapshot().forEach((id,tag)->{
                    if(!id.equals("paper:metadata"))saved.put(id,tag);
                });
                result.getExtraData().put("LinkSavedData",com.infernalsuite.asp.Converter.convertTag(saved));
            }
            return result;
        });
        return asp.getSerializer().serializeWorld(copy);
    }
    public WorldInfo instantiate(String name,byte[] srf,boolean persistent)throws Exception {
        key(name);
        if(store.read(key(name)).isPresent())throw new ConflictException("Stored world already exists");
        SlimeWorld world=asp.getSerializer().deserializeWorld(name,srf,null,properties(),true);
        WorldInfo result=main(() -> {
            if(NativeJournal.knownWorld(name))throw new ConflictException("World identity already exists");
            var instance=asp.loadWorld(world,true);managed.put(name,persistent);versions.put(name,0L);
            NativeJournal.createdWorld(name,persistent);
            instance.getBukkitWorld().setAutoSave(false);
            return new WorldInfo(name,persistent,instance.getBukkitWorld().getPlayerCount());
        });
        if(persistent)save(name);
        return result;
    }
    public WorldInfo load(String name)throws Exception {
        if(NativeJournal.enabled())return main(()->{
            if(managed.containsKey(name))throw new ConflictException("World already loaded");
            var world=asp.getSerializer().deserializeWorld(name,NativeJournal.storedWorld(name),null,properties(),true);
            asp.loadWorld(world,true);managed.put(name,NativeJournal.persistentWorld(name));
            Bukkit.getWorld(name).setAutoSave(false);return new WorldInfo(name,NativeJournal.persistentWorld(name),0);
        });
        State state=store.read(key(name)).orElseThrow(() -> new ConflictException("Unknown stored world"));
        if(state.payload().length==0)throw new ConflictException("World was deleted");
        SlimeWorld world=asp.getSerializer().deserializeWorld(name,state.payload(),null,properties(),true);
        return main(() -> {
            asp.loadWorld(world,true);managed.put(name,true);versions.put(name,state.version());
            Bukkit.getWorld(name).setAutoSave(false);return new WorldInfo(name,true,0);
        });
    }
    public void save(String name)throws Exception {
        if(NativeJournal.enabled()){main(()->{NativeJournal.run(()->{});return null;});return;}
        if(!Boolean.TRUE.equals(managed.get(name)))throw new ConflictException("Temporary worlds are never saved");
        byte[] bytes=snapshot(name);
        long version=versions.get(name);
        store.commit(new Transaction(UUID.randomUUID(),List.of(guard),List.of(new Change(key(name),version,1,"world:checkpoint",bytes))));
        versions.put(name,version+1);
    }
    public void unload(String name)throws Exception {
        main(() -> {
            World world=live(name).getBukkitWorld();
            if(world.equals(Bukkit.getWorlds().getFirst()))throw new ConflictException("Cannot unload the bootstrap world");
            if(!world.getPlayers().isEmpty())throw new ConflictException("World still has players");
            return null;
        });
        if(Boolean.TRUE.equals(managed.get(name)))save(name);
        main(() -> {
            if(!Bukkit.unloadWorld(name,false))throw new ConflictException("World unload rejected");
            managed.remove(name);versions.remove(name);return null;
        });
    }
    public void delete(String name)throws Exception {
        if(managed.containsKey(name))throw new ConflictException("Unload the world before deleting it");
        if(NativeJournal.enabled()){main(()->{NativeJournal.deleteWorld(name);return null;});return;}
        State state=store.read(key(name)).orElseThrow(() -> new ConflictException("Unknown world"));
        store.commit(new Transaction(UUID.randomUUID(),List.of(guard),List.of(new Change(key(name),state.version(),1,"world:deleted",new byte[0]))));
    }
    public List<WorldInfo> loaded()throws Exception {
        return main(() -> managed.entrySet().stream().map(e -> new WorldInfo(e.getKey(),e.getValue(),live(e.getKey()).getBukkitWorld().getPlayerCount())).toList());
    }
    public void saveOnShutdown() throws Exception {
        for(String name:List.copyOf(managed.keySet()))if(Boolean.TRUE.equals(managed.get(name)))save(name);
    }
}
