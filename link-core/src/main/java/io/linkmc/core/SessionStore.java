package io.linkmc.core;

import io.linkmc.api.LinkApi;
import io.linkmc.api.LinkApi.*;
import java.sql.*;
import java.util.*;
import static io.linkmc.core.Database.*;

/** DB-clock leases and fencing. Redis locks are never ownership authority. */
public final class SessionStore {
    private final Database db;
    private final int leaseSeconds;
    public SessionStore(Database db, int leaseSeconds) {
        if (leaseSeconds < 5 || leaseSeconds > 300) throw new IllegalArgumentException("Lease must be 5..300 seconds");
        this.db = db; this.leaseSeconds = leaseSeconds;
    }
    public Session acquire(UUID player, String node) throws Exception {
        LinkApi.requireName(node);
        return db.transaction(c -> acquire(c,player,node));
    }
    Session acquire(java.sql.Connection c, UUID player, String node) throws Exception {
            lock(c,"session:" + player);
            try (var s = sql(c,"SELECT state,expires_at>clock_timestamp() FROM link_sessions WHERE player=? FOR UPDATE",player); var r=s.executeQuery()) {
                if(r.next() && r.getBoolean(2) && (r.getString(1).equals("MOVING") || r.getString(1).equals("ACTIVE")))
                    throw new ConflictException("Player already owned or transferring");
            }
            // An expired owner can never publish another frame. Invalidate its unclaimed
            // transfer before issuing a new epoch, so delayed destination connections fail.
            update(c,"UPDATE link_transfers SET state='CANCELLED' WHERE player=? AND state='PREPARED'",player);
            update(c,"UPDATE link_routes SET state='FAILED' WHERE player=? AND state='REQUESTED'",player);
            update(c,"INSERT INTO link_sessions(player,node,epoch,state,expires_at) VALUES (?,?,1,'ACTIVE',clock_timestamp()+?*interval '1 second') ON CONFLICT(player) DO UPDATE SET node=excluded.node,epoch=link_sessions.epoch+1,state='ACTIVE',expires_at=excluded.expires_at",player,node,leaseSeconds);
            return get(c,player);

    }
    public Session renew(Session session) throws Exception {
        return db.transaction(c -> {
            requireActive(c,session);
            update(c,"UPDATE link_sessions SET expires_at=clock_timestamp()+?*interval '1 second' WHERE player=?",leaseSeconds,session.player());
            return get(c,session.player());
        });
    }
    public void release(Session session) throws Exception {
        db.transaction(c -> {
            requireActive(c,session);
            update(c,"UPDATE link_sessions SET state='RELEASED',expires_at=clock_timestamp() WHERE player=?",session.player());
            return null;
        });
    }
    static void requireActive(Connection c, Session session) throws SQLException {
        lock(c,"session:" + session.player());
        try(var s=sql(c,"SELECT node,epoch,state,expires_at>clock_timestamp() FROM link_sessions WHERE player=? FOR UPDATE",session.player()); var r=s.executeQuery()) {
            if(!r.next() || !r.getString(1).equals(session.node()) || r.getLong(2)!=session.epoch()
                    || !r.getString(3).equals("ACTIVE") || !r.getBoolean(4))
                throw new ConflictException("Expired, transferred or fenced session");
        }
    }
    static Session get(Connection c, UUID player) throws SQLException {
        try(var s=sql(c,"SELECT node,epoch,expires_at FROM link_sessions WHERE player=?",player); var r=s.executeQuery()) {
            if(!r.next()) throw new ConflictException("Missing session");
            return new Session(player,r.getString(1),r.getLong(2),r.getTimestamp(3).toInstant());
        }
    }
    public Transfer prepare(Session source, UUID operation, Destination dest) throws Exception {
        return db.transaction(c -> {
            lock(c,"session:" + source.player());
            try(var s=sql(c,"SELECT * FROM link_transfers WHERE id=?",operation); var r=s.executeQuery()) {
                if(r.next()) {
                    if(!r.getObject("player",UUID.class).equals(source.player()) || r.getLong("source_epoch")!=source.epoch()
                            || !r.getString("source_node").equals(source.node()) || !destination(r).equals(dest))
                        throw new ConflictException("Transfer ID reused");
                    return new Transfer(operation,source.player(),r.getLong("final_version"),dest);
                }
            }
            requireActive(c,source);
            // Player writers take the same session lock before the aggregate lock.
            long version=EventStore.read(c,"player:"+source.player()).map(State::version).orElse(0L);
            update(c,"INSERT INTO link_transfers(id,player,source_node,source_epoch,destination_node,world,x,y,z,yaw,pitch,final_version,state) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,'PREPARED')",
                    operation,source.player(),source.node(),source.epoch(),dest.node(),dest.world(),dest.x(),dest.y(),dest.z(),dest.yaw(),dest.pitch(),version);
            update(c,"UPDATE link_sessions SET state='MOVING' WHERE player=?",source.player());
            return new Transfer(operation,source.player(),version,dest);
        });
    }
    public Session accept(UUID player, UUID transfer, String node) throws Exception {
        return db.transaction(c -> accept(c,player,transfer,node));
    }
    Session accept(java.sql.Connection c, UUID player, UUID transfer, String node) throws Exception {
            lock(c,"session:" + player);
            try(var s=sql(c,"SELECT * FROM link_transfers WHERE id=? AND player=? FOR UPDATE",transfer,player); var r=s.executeQuery()) {
                if(!r.next() || !r.getString("destination_node").equals(node)) throw new ConflictException("Wrong transfer destination");
                if(r.getString("state").equals("ACCEPTED")) {
                    Session current=get(c,player);
                    if(current.epoch()!=r.getLong("accepted_epoch") || !current.node().equals(node)) throw new ConflictException("Transfer already superseded");
                    requireActive(c,current);
                    return current;
                }
                if(!r.getString("state").equals("PREPARED"))throw new ConflictException("Transfer cancelled");
                int changed=update(c,"UPDATE link_sessions SET node=?,epoch=epoch+1,state='ACTIVE',expires_at=clock_timestamp()+?*interval '1 second' WHERE player=? AND state='MOVING' AND epoch=? AND node=? AND expires_at>clock_timestamp()",
                        node,leaseSeconds,player,r.getLong("source_epoch"),r.getString("source_node"));
                if(changed!=1) throw new ConflictException("Transfer ownership mismatch");
                Session current=get(c,player);
                update(c,"UPDATE link_transfers SET state='ACCEPTED',accepted_epoch=? WHERE id=?",current.epoch(),transfer);
                return current;
            }

    }
    static Destination destination(ResultSet r) throws SQLException {
        return new Destination(r.getString("destination_node"),r.getString("world"),r.getDouble("x"),r.getDouble("y"),r.getDouble("z"),r.getFloat("yaw"),r.getFloat("pitch"));
    }
}
