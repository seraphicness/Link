package io.linkmc.core;

import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import java.util.Map;
import java.util.UUID;
import static io.linkmc.core.Database.*;

/**
 * At-least-once invalidation hints. SQL remains authoritative. Consumers reread
 * aggregate versions from SQL and must never blindly replay Redis notifications.
 * A crash after XADD and before marking delivered intentionally causes a duplicate.
 */
public final class RedisOutbox implements AutoCloseable {
    private final Database db;
    private final RedisClient client;
    private final StatefulRedisConnection<String,String> connection;
    public RedisOutbox(Database db,String uri) {
        this.db=db;client=RedisClient.create(uri);
        client.setDefaultTimeout(java.time.Duration.ofSeconds(3));
        try { connection=client.connect(); }
        catch(RuntimeException e) {client.shutdown();throw e;}
    }
    public int flush(int limit) throws Exception {
        if(limit<1 || limit>1000) throw new IllegalArgumentException("Invalid batch size");
        return db.transaction(c -> {
            int count=0;
            try(var s=sql(c,"SELECT id FROM link_outbox WHERE NOT delivered ORDER BY id LIMIT ? FOR UPDATE SKIP LOCKED",limit);var r=s.executeQuery()) {
                while(r.next()) {
                    UUID id=r.getObject(1,UUID.class);
                    connection.sync().xadd("link:commits",Map.of("operation",id.toString()));
                    update(c,"UPDATE link_outbox SET delivered=true WHERE id=?",id);count++;
                }
            }
            return count;
        });
    }
    @Override public void close() {connection.close();client.shutdown();}
}
