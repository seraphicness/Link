package io.linkmc.integration;

import io.linkmc.core.*;
import com.infernalsuite.asp.skeleton.SkeletonSlimeWorld;
import com.infernalsuite.asp.api.world.properties.SlimePropertyMap;
import com.infernalsuite.asp.serialization.slime.SlimeSerializer;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.sql.DriverManager;
import java.util.concurrent.ConcurrentHashMap;

/** Only seeds an empty integration template into an explicitly named test database. */
public final class SeedTemplate {
    public static void main(String[] args)throws Exception{
        String url=System.getenv("LINK_JDBC_URL");
        if(url==null||!url.endsWith("/link_runtime_test"))throw new IllegalStateException("Requires disposable link_runtime_test database");
        net.minecraft.SharedConstants.tryDetectVersion();
        var world=new SkeletonSlimeWorld("lobby",null,true,new Long2ObjectOpenHashMap<>(),new ConcurrentHashMap<>(),new SlimePropertyMap(),net.minecraft.SharedConstants.getCurrentVersion().dataVersion().version());
        Database db=new Database(()->DriverManager.getConnection(url,System.getenv("LINK_DB_USER"),System.getenv("LINK_DB_PASSWORD")));db.migrate();
        new TemplateStore(db).publish("lobby",1,SlimeSerializer.serialize(world));
        System.out.println("LINK_TEST_TEMPLATE_READY");
    }
}
