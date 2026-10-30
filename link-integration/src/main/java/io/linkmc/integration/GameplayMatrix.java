package io.linkmc.integration;

import java.util.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.*;
import net.minecraft.world.item.*;
import net.minecraft.world.item.trading.*;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.craftbukkit.entity.CraftPlayer;

/** Disposable real-player fixtures; menu cases call native gameplay code on the server thread. */
final class GameplayMatrix {
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
    private static int count(ServerPlayer p,Item item){int n=0;for(int i=0;i<p.getInventory().getContainerSize();i++){var s=p.getInventory().getItem(i);if(s.is(item))n+=s.getCount();}return n;}
    private static void reset(ServerPlayer p){p.closeContainer();p.getInventory().clearContent();p.inventoryMenu.setCarried(ItemStack.EMPTY);}
    private static void check(String name,Runnable action,int[] totals){
        try{action.run();totals[0]++;Bukkit.getLogger().info("MATRIX_PASS "+name);}
        catch(Throwable e){totals[1]++;Bukkit.getLogger().log(java.util.logging.Level.SEVERE,"MATRIX_FAIL "+name,e);}
    }
    static void run(String mode){
        ServerPlayer p=((CraftPlayer)Objects.requireNonNull(Bukkit.getPlayerExact("LinkWire"),"Connect LinkWire first")).getHandle();
        int[] totals={0,0};
        if(mode.equals("matrix-menus")){
            check("native-left-right-click",()->{
                reset(p);var box=new net.minecraft.world.SimpleContainer(27);box.setItem(0,new ItemStack(Items.DIAMOND,7));
                var menu=ChestMenu.threeRows(2,p.getInventory(),box);p.containerMenu=menu;
                menu.clicked(0,1,ContainerInput.PICKUP,p);
                require(menu.getCarried().getCount()==4&&box.getItem(0).getCount()==3,"Right click must split 7 into 4+3");
                menu.clicked(1,0,ContainerInput.PICKUP,p);
                require(menu.getCarried().isEmpty()&&box.getItem(1).getCount()==4,"Left click must place exactly four");
            },totals);
            check("native-shift-click",()->{
                reset(p);var box=new net.minecraft.world.SimpleContainer(27);box.setItem(0,new ItemStack(Items.DIAMOND,64));
                var menu=ChestMenu.threeRows(3,p.getInventory(),box);p.containerMenu=menu;menu.clicked(0,0,ContainerInput.QUICK_MOVE,p);
                require(box.getItem(0).isEmpty()&&count(p,Items.DIAMOND)==64,"Shift-click conservation");
            },totals);
            check("native-hotbar-swap",()->{
                reset(p);p.getInventory().setItem(0,new ItemStack(Items.EMERALD,3));var box=new net.minecraft.world.SimpleContainer(27);box.setItem(0,new ItemStack(Items.DIAMOND,7));
                var menu=ChestMenu.threeRows(4,p.getInventory(),box);p.containerMenu=menu;menu.clicked(0,0,ContainerInput.SWAP,p);
                require(count(p,Items.DIAMOND)==7&&box.getItem(0).is(Items.EMERALD)&&box.getItem(0).getCount()==3,"Hotbar swap conservation");
            },totals);
            check("native-drag",()->{
                reset(p);var box=new net.minecraft.world.SimpleContainer(27);var menu=ChestMenu.threeRows(5,p.getInventory(),box);p.containerMenu=menu;menu.setCarried(new ItemStack(Items.DIAMOND,8));
                menu.clicked(-999,AbstractContainerMenu.getQuickcraftMask(0,0),ContainerInput.QUICK_CRAFT,p);
                menu.clicked(0,AbstractContainerMenu.getQuickcraftMask(1,0),ContainerInput.QUICK_CRAFT,p);
                menu.clicked(1,AbstractContainerMenu.getQuickcraftMask(1,0),ContainerInput.QUICK_CRAFT,p);
                menu.clicked(-999,AbstractContainerMenu.getQuickcraftMask(2,0),ContainerInput.QUICK_CRAFT,p);
                require(menu.getCarried().isEmpty()&&box.getItem(0).getCount()==4&&box.getItem(1).getCount()==4,"Drag distributes eight exactly");
            },totals);
            check("native-crafting-result",()->{
                reset(p);var menu=new CraftingMenu(6,p.getInventory(),ContainerLevelAccess.create(p.level(),p.blockPosition()));p.containerMenu=menu;
                menu.getSlot(1).set(new ItemStack(Items.OAK_LOG,1));menu.slotsChanged(menu.getSlot(1).container);
                require(menu.getSlot(0).getItem().is(Items.OAK_PLANKS),"Oak-log recipe available");
                menu.clicked(0,0,ContainerInput.PICKUP,p);
                require(menu.getCarried().is(Items.OAK_PLANKS)&&menu.getCarried().getCount()==4&&menu.getSlot(1).getItem().isEmpty(),"Craft consumes one log for four planks");
                p.closeContainer();require(count(p,Items.OAK_PLANKS)==4,"Craft result returned once on close");
            },totals);
            check("native-merchant-trade",()->{
                reset(p);var merchant=new net.minecraft.world.entity.npc.ClientSideMerchant(p);var offers=new MerchantOffers();
                offers.add(new MerchantOffer(new ItemCost(Items.EMERALD,2),new ItemStack(Items.DIAMOND,1),10,1,0));merchant.overrideOffers(offers);
                var menu=new MerchantMenu(7,p.getInventory(),merchant);p.containerMenu=menu;menu.getSlot(0).set(new ItemStack(Items.EMERALD,5));menu.updateSellItem();
                require(menu.getSlot(2).getItem().is(Items.DIAMOND),"Trade result available");menu.clicked(2,0,ContainerInput.PICKUP,p);
                require(menu.getCarried().getCount()==1&&menu.getSlot(0).getItem().getCount()==3,"Trade consumes exactly two emeralds");
                p.closeContainer();require(count(p,Items.DIAMOND)==1&&count(p,Items.EMERALD)==3,"Trade result/input recovered on close");
            },totals);
            check("native-item-pickup",()->{
                reset(p);var item=new net.minecraft.world.entity.item.ItemEntity(p.level(),p.getX(),p.getY(),p.getZ(),new ItemStack(Items.DIAMOND,7));item.setNoPickUpDelay();p.level().addFreshEntity(item);
                item.playerTouch(p);require(item.isRemoved()&&count(p,Items.DIAMOND)==7,"Pickup consumes world entity once");
                // The world no longer ticks/collides a removed entity. Calling playerTouch
                // directly on that discarded Java object is not a second gameplay pickup.
            },totals);
            reset(p);
        }else if(mode.equals("matrix-death")){
            reset(p);p.getBukkitEntity().setInvulnerable(false);p.getInventory().setItem(0,new ItemStack(Items.NETHER_STAR,7));
            int oldDrops=p.level().getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,p.getBoundingBox().inflate(8)).stream().filter(e->e.getItem().is(Items.NETHER_STAR)).mapToInt(e->e.getItem().getCount()).sum();
            p.setHealth(0);p.die(p.damageSources().generic());
            check("native-death-drop-conservation",()->{
                int dropped=p.level().getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,p.getBoundingBox().inflate(8)).stream().filter(e->e.getItem().is(Items.NETHER_STAR)).mapToInt(e->e.getItem().getCount()).sum();
                require(count(p,Items.NETHER_STAR)==0&&dropped-oldDrops==7,"Death player/new-world-drop total: "+count(p,Items.NETHER_STAR)+"/"+(dropped-oldDrops));
            },totals);
            p.connection.handleClientCommand(new net.minecraft.network.protocol.game.ServerboundClientCommandPacket(net.minecraft.network.protocol.game.ServerboundClientCommandPacket.Action.PERFORM_RESPAWN));
            check("native-respawn",()->require(p.isAlive()&&p.getHealth()>0&&count(p,Items.NETHER_STAR)==0,"Respawn alive with empty old inventory"),totals);
            p.getBukkitEntity().setInvulnerable(true);
        }else if(mode.equals("matrix-rollback-baseline")){
            reset(p);p.getInventory().setItem(0,new ItemStack(Items.DIAMOND,7));var world=p.getBukkitEntity().getWorld();world.getBlockAt(20,65,0).setType(Material.CHEST);var inventory=((org.bukkit.block.Chest)world.getBlockAt(20,65,0).getState()).getBlockInventory();inventory.clear();inventory.setItem(0,new org.bukkit.inventory.ItemStack(Material.DIAMOND,11));totals[0]++;
        }else if(mode.equals("matrix-abort")){
            p.getInventory().setItem(0,new ItemStack(Items.DIAMOND,99));p.getBukkitEntity().getWorld().getBlockAt(20,65,0).setType(Material.STONE);
            throw new IllegalStateException("Deliberately aborting an uncommitted native frame");
        }else if(mode.equals("matrix-rollback-verify")){
            check("aborted-native-frame-not-persisted",()->{
                var block=p.getBukkitEntity().getWorld().getBlockAt(20,65,0).getState();
                require(count(p,Items.DIAMOND)==7&&block instanceof org.bukkit.block.Chest chest&&chest.getBlockInventory().getItem(0).getAmount()==11,"Aborted inventory/block mutations persisted");
            },totals);
        }else if(mode.equals("matrix-map-prepare")){
            reset(p);var map=MapItem.create(p.level(),0,0,(byte)0,false,false);var data=Objects.requireNonNull(MapItem.getSavedData(map,p.level()));data.locked=true;data.setColor(0,0,(byte)117);p.getInventory().setItem(0,map);totals[0]++;
        }else if(mode.equals("matrix-map-verify")){
            check("cross-server-map-contents",()->{var data=MapItem.getSavedData(p.getInventory().getItem(0),p.level());require(data!=null&&data.colors[0]==117,"Map item present but original map pixels unavailable");},totals);
        }else if(mode.equals("matrix-relations-prepare")){
            reset(p);var world=p.getBukkitEntity().getWorld();
            var boat=world.spawn(new org.bukkit.Location(world,2,65,2),org.bukkit.entity.boat.OakBoat.class);boat.addScoreboardTag("matrix-boat");boat.setGravity(false);boat.addPassenger(p.getBukkitEntity());
            var pearl=world.spawn(new org.bukkit.Location(world,0,80,0),org.bukkit.entity.EnderPearl.class);pearl.setShooter(p.getBukkitEntity());pearl.setGravity(false);pearl.setVelocity(new org.bukkit.util.Vector());pearl.addScoreboardTag("matrix-pearl");
            require(p.getVehicle()!=null,"Vehicle attached before crash");totals[0]++;
        }else if(mode.equals("matrix-relations-recover")){
            var world=p.getBukkitEntity().getWorld();
            check("crash-vehicle-entity",()->require(world.getEntitiesByClass(org.bukkit.entity.boat.OakBoat.class).stream().filter(e->e.getScoreboardTags().contains("matrix-boat")).count()==1,"Exactly one original boat"),totals);
            check("crash-pearl-entity-and-owner",()->{var pearls=world.getEntitiesByClass(org.bukkit.entity.EnderPearl.class).stream().filter(e->e.getScoreboardTags().contains("matrix-pearl")).toList();require(pearls.size()==1&&p.getBukkitEntity().equals(pearls.getFirst().getShooter()),"One pearl with restored owner");},totals);
            check("crash-pearl-ticket-registration",()->require(p.getEnderPearls().stream().anyMatch(e->e.entityTags().contains("matrix-pearl")),"Restored pearl registered with player"),totals);
            check("crash-mounted-player-relationship",()->require(p.getVehicle()!=null&&p.getVehicle().entityTags().contains("matrix-boat"),"Mounted relationship was not restored"),totals);
        }else if(mode.equals("matrix-prepare")){
            reset(p);p.getBukkitEntity().setInvulnerable(true);p.getBukkitEntity().setFoodLevel(6);p.setHealth(13);
            var world=p.getBukkitEntity().getWorld();world.setDifficulty(org.bukkit.Difficulty.NORMAL);
            for(int x=16;x<32;x++)for(int z=0;z<16;z++)world.getBlockAt(x,64,z).setType(Material.BEDROCK);
            world.getBlockAt(20,65,0).setType(Material.CHEST);
            var chest=((org.bukkit.block.Chest)world.getBlockAt(20,65,0).getState()).getBlockInventory();chest.clear();chest.setItem(0,new org.bukkit.inventory.ItemStack(Material.DIAMOND,11));
            world.getBlockAt(22,65,0).setType(Material.CHEST);((org.bukkit.block.Chest)world.getBlockAt(22,65,0).getState()).getBlockInventory().clear();
            world.getBlockAt(22,66,0).setType(Material.HOPPER);var hopper=(org.bukkit.block.Hopper)world.getBlockAt(22,66,0).getState();hopper.getInventory().clear();hopper.getInventory().setItem(0,new org.bukkit.inventory.ItemStack(Material.IRON_INGOT,16));
            // Unconsumed crafting inputs and cursor must survive abrupt termination.
            var menu=new CraftingMenu(8,p.getInventory(),ContainerLevelAccess.create(p.level(),p.blockPosition()));p.containerMenu=menu;
            menu.getSlot(1).set(new ItemStack(Items.DIAMOND,5));menu.setCarried(new ItemStack(Items.EMERALD,3));
            var stand=world.spawn(new org.bukkit.Location(world,24,65,0),org.bukkit.entity.ArmorStand.class);stand.setGravity(false);stand.addScoreboardTag("matrix-equipped");stand.getEquipment().setHelmet(new org.bukkit.inventory.ItemStack(Material.DIAMOND_HELMET));
            totals[0]++;
        }else if(mode.equals("matrix-recover")){
            var world=p.getBukkitEntity().getWorld();
            check("crash-transient-crafting-and-cursor",()->require(count(p,Items.DIAMOND)==5&&count(p,Items.EMERALD)==3,"Expected five diamonds and three emeralds exactly; got "+count(p,Items.DIAMOND)+"/"+count(p,Items.EMERALD)),totals);
            check("crash-chest",()->{var chest=(org.bukkit.block.Chest)world.getBlockAt(20,65,0).getState();require(chest.getBlockInventory().getItem(0).getAmount()==11,"Chest retains eleven");},totals);
            check("crash-hopper-conservation",()->{int total=0;for(var i:((org.bukkit.block.Hopper)world.getBlockAt(22,66,0).getState()).getInventory())if(i!=null&&i.getType()==Material.IRON_INGOT)total+=i.getAmount();for(var i:((org.bukkit.block.Chest)world.getBlockAt(22,65,0).getState()).getBlockInventory())if(i!=null&&i.getType()==Material.IRON_INGOT)total+=i.getAmount();require(total==16,"Hopper/chest total="+total);},totals);
            check("crash-equipped-entity",()->require(world.getEntitiesByClass(org.bukkit.entity.ArmorStand.class).stream().filter(e->e.getScoreboardTags().contains("matrix-equipped")&&e.getEquipment().getHelmet().getType()==Material.DIAMOND_HELMET).count()==1,"Exactly one equipped entity"),totals);
        }else throw new IllegalArgumentException(mode);
        Bukkit.getLogger().info("MATRIX_SUMMARY "+mode+" passed="+totals[0]+" failed="+totals[1]);
    }
}

