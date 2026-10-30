package io.linkmc.integration;

import io.linkmc.api.Link;
import org.bukkit.*;
import org.bukkit.block.Chest;
import org.bukkit.entity.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

/** Install only in disposable runtime tests. Never included in the Link server artifact. */
public final class LinkIntegrationProbe extends JavaPlugin {
    @Override public void onEnable(){
        if(!System.getenv().getOrDefault("LINK_NODE_ID","").startsWith("test-"))throw new IllegalStateException("Integration probe requires test-* node");
        getCommand("linkprobe").setExecutor((sender,command,label,args)->{
            if(sender instanceof Player||args.length!=1)return false;
            if(args[0].equals("matrix-worlds")){WorldMatrix.run();return true;}
            boolean prepare=args[0].equals("prepare");
            Link.api().engine().transact(()->{
                if(args[0].startsWith("matrix-")){GameplayMatrix.run(args[0]);return;}
                if(args[0].equals("platform")){
                    World world=java.util.Objects.requireNonNull(Bukkit.getWorld("link-spawn"));
                    world.setDifficulty(Difficulty.NORMAL);
                    for(int x=-8;x<=8;x++)for(int z=-8;z<=8;z++)world.getBlockAt(x,64,z).setType(Material.BEDROCK);
                    return;
                }
                if(args[0].startsWith("wire-")){
                    var player=java.util.Objects.requireNonNull(Bukkit.getPlayerExact("LinkWire"),"Wire client not on this backend");
                    switch(args[0]){
                        case "wire-prepare" -> {
                            player.getInventory().clear();player.getInventory().setItem(0,new ItemStack(Material.DIAMOND,7));
                            player.getEnderChest().setItem(0,new ItemStack(Material.EMERALD,3));player.setHealth(13);player.setLevel(9);player.setFoodLevel(6);player.setSaturation(0);
                            player.setInvulnerable(true);player.setAllowFlight(true);player.setFlying(true);
                        }
                        case "wire-transfer" -> Link.api().network().requestTravel(player.getUniqueId(),java.util.UUID.randomUUID(),new io.linkmc.api.LinkApi.Destination("test-b","link-spawn",8,66,8,90,0)).whenComplete((r,e)->{if(e!=null)getLogger().log(java.util.logging.Level.SEVERE,"WIRE_TRANSFER_FAILED",e);else getLogger().info("WIRE_TRANSFER_REQUESTED "+r.id());});
                        case "wire-verify" -> {
                            var item=player.getInventory().getItem(0);var ender=player.getEnderChest().getItem(0);
                            if(item==null||item.getType()!=Material.DIAMOND||item.getAmount()!=7||ender==null||ender.getAmount()!=3||player.getLevel()!=9||player.getHealth()!=13)throw new AssertionError("Wire state: item="+item+", ender="+ender+", level="+player.getLevel()+", health="+player.getHealth());
                            if(player.getLocation().getX()!=8||player.getLocation().getZ()!=8)throw new AssertionError("Wire arrival location mismatch");
                        }
                        default -> throw new IllegalArgumentException("Unknown wire probe");
                    }return;
                }
                if(args[0].startsWith("player-")){NativePlayerProbe.run(args[0]);return;}
                World world=java.util.Objects.requireNonNull(Bukkit.getWorld("link-spawn"));
                world.getChunkAt(0,0).load();
                if(prepare){
                    world.getBlockAt(1,64,1).setType(Material.BEDROCK);
                    world.getBlockAt(1,65,1).setType(Material.CHEST);
                    ((Chest)world.getBlockAt(1,65,1).getState()).getBlockInventory().setItem(0,new ItemStack(Material.DIAMOND,7));
                    var stand=world.spawn(new Location(world,3,65,3),ArmorStand.class);
                    stand.setGravity(false);stand.setPersistent(true);stand.addScoreboardTag("link-probe");stand.getEquipment().setHelmet(new ItemStack(Material.GOLDEN_HELMET));
                    world.getPersistentDataContainer().set(new NamespacedKey(this,"marker"),org.bukkit.persistence.PersistentDataType.INTEGER,42);
                    world.setTime(6000);
                    var board=Bukkit.getScoreboardManager().getMainScoreboard();
                    var objective=board.getObjective("linkprobe");if(objective==null)objective=board.registerNewObjective("linkprobe",org.bukkit.scoreboard.Criteria.DUMMY,net.kyori.adventure.text.Component.text("probe"));
                    objective.getScore("marker").setScore(17);
                }else{
                    if(!(world.getBlockAt(1,65,1).getState() instanceof Chest chest)||chest.getBlockInventory().getItem(0)==null||chest.getBlockInventory().getItem(0).getType()!=Material.DIAMOND||chest.getBlockInventory().getItem(0).getAmount()!=7)throw new AssertionError("Chest inventory did not recover");
                    long count=world.getEntitiesByClass(ArmorStand.class).stream().filter(e->e.getScoreboardTags().contains("link-probe")&&e.getEquipment().getHelmet().getType()==Material.GOLDEN_HELMET).count();
                    if(count!=1)throw new AssertionError("Entity recovery count="+count);
                    if(!Integer.valueOf(42).equals(world.getPersistentDataContainer().get(new NamespacedKey(this,"marker"),org.bukkit.persistence.PersistentDataType.INTEGER)))throw new AssertionError("PDC did not recover");
                    var objective=Bukkit.getScoreboardManager().getMainScoreboard().getObjective("linkprobe");
                    if(objective==null||objective.getScore("marker").getScore()!=17)throw new AssertionError("Scoreboard did not recover");
                }
            }).whenComplete((v,error)->{if(error==null)getLogger().info((args[0].startsWith("matrix-")?"LINK_PROBE_COMMITTED ":"LINK_PROBE_OK ")+args[0]);else getLogger().log(java.util.logging.Level.SEVERE,"LINK_PROBE_FAILED",error);});
            return true;
        });
    }
    @Override public void onDisable(){NativePlayerProbe.disconnect();}
}
