package io.linkmc.integration;

import io.linkmc.api.Link;
import org.bukkit.Bukkit;
import org.bukkit.Material;

/** Exercises public plugin API dependencies without blocking the server thread. */
final class WorldMatrix {
    static void run(){
        var link=Link.api();String suffix=java.util.UUID.randomUUID().toString().substring(0,8);
        String editor="matrix-editor-"+suffix,template="matrix-template-"+suffix,clone="matrix-clone-"+suffix;
        link.worlds().createEditor(editor)
            .thenCompose(v->link.engine().transact(()->Bukkit.getWorld(editor).getBlockAt(1,65,1).setType(Material.DIAMOND_BLOCK)))
            .thenCompose(v->link.worlds().publish(editor,template,1))
            .thenCompose(v->link.worlds().create(clone,template,1,true))
            .thenCompose(v->link.engine().transact(()->verify(clone)))
            .thenCompose(v->link.worlds().save(clone))
            .thenCompose(v->link.worlds().unload(clone))
            .thenCompose(v->link.worlds().load(clone))
            .thenCompose(v->link.engine().transact(()->verify(clone)))
            .thenCompose(v->link.worlds().unload(clone))
            .thenCompose(v->link.worlds().delete(clone))
            .thenCompose(v->link.worlds().unload(editor))
            .thenCompose(v->link.worlds().delete(editor))
            .whenComplete((v,e)->{
                if(e==null)Bukkit.getLogger().info("MATRIX_PASS public-world-editor-publish-clone-save-unload-reload-delete");
                else Bukkit.getLogger().log(java.util.logging.Level.SEVERE,"MATRIX_FAIL public-world-lifecycle",e);
            });
    }
    private static void verify(String name){
        if(Bukkit.getWorld(name)==null||Bukkit.getWorld(name).getBlockAt(1,65,1).getType()!=Material.DIAMOND_BLOCK)throw new AssertionError("Template block missing from "+name);
    }
}
