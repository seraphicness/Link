package io.linkmc.core;

import io.linkmc.api.LinkApi;
import io.linkmc.api.LinkApi.*;
import java.sql.*;
import java.util.*;
import static io.linkmc.core.Database.*;

/** DB reservations, not Redis counters, enforce both node and world capacities. */
public final class NetworkStore {
    private final Database db;
    public NetworkStore(Database db) { this.db=db; }
    public void heartbeat(String node, int capacity) throws Exception {
        LinkApi.requireName(node);
        if(capacity<1) throw new IllegalArgumentException("Invalid capacity");
        db.transaction(c -> {
            lock(c,"allocation");
            update(c,"INSERT INTO link_nodes(id,capacity) VALUES (?,?) ON CONFLICT(id) DO UPDATE SET capacity=excluded.capacity,heartbeat=clock_timestamp()",node,capacity);
            return null;
        });
    }
    public void register(String id, Destination spawn, int capacity) throws Exception {
        LinkApi.requireName(id);
        if(capacity<1) throw new IllegalArgumentException("Invalid capacity");
        db.transaction(c -> {
            lock(c,"allocation");
            update(c,"INSERT INTO link_channels(id,node,world,capacity,ready,x,y,z,yaw,pitch) VALUES (?,?,?,?,true,?,?,?,?,?)",
                    id,spawn.node(),spawn.world(),capacity,spawn.x(),spawn.y(),spawn.z(),spawn.yaw(),spawn.pitch());
            return null;
        });
    }
    public void ready(String id, boolean ready) throws Exception {
        db.transaction(c -> { lock(c,"allocation"); update(c,"UPDATE link_channels SET ready=? WHERE id=?",ready,id); return null; });
    }
    public List<Channel> channels() throws Exception {
        return db.transaction(c -> {
            List<Channel> result=new ArrayList<>();
            try(var s=sql(c,"SELECT c.*,count(r.id) FILTER(WHERE r.state='ADMITTED') AS occupied,count(r.id) FILTER(WHERE r.state='RESERVED') AS reserved FROM link_channels c JOIN link_nodes n ON n.id=c.node LEFT JOIN link_reservations r ON r.channel=c.id AND r.expires_at>clock_timestamp() WHERE c.ready AND n.heartbeat>clock_timestamp()-interval '15 seconds' GROUP BY c.id ORDER BY c.id"); var r=s.executeQuery()) {
                while(r.next()) result.add(new Channel(r.getString("id"),r.getString("node"),r.getString("world"),r.getInt("capacity"),r.getInt("occupied"),r.getInt("reserved")));
            }
            return List.copyOf(result);
        });
    }
    public Reservation reserve(UUID player, UUID request) throws Exception {
        return db.transaction(c -> {
            lock(c,"allocation");
            // Idempotent retries return the same destination; no extra slot is consumed.
            try(var s=sql(c,"SELECT r.*,c.node,c.world,c.x,c.y,c.z,c.yaw,c.pitch,r.expires_at>clock_timestamp() AS valid FROM link_reservations r JOIN link_channels c ON c.id=r.channel WHERE r.id=?",request); var r=s.executeQuery()) {
                if(r.next()) {
                    if(!player.equals(r.getObject("player",UUID.class))) throw new ConflictException("Reservation ID reused");
                    if(!r.getString("state").equals("RESERVED")) throw new ConflictException("Already admitted");
                    if(!r.getBoolean("valid")) throw new ConflictException("Reservation expired");
                    return reservation(r);
                }
            }
            // Expired admitted sessions are NOT silently freed: the backend must
            // stop simulation before releasing its admission. Dead nodes are excluded.
            update(c,"DELETE FROM link_reservations WHERE state='RESERVED' AND expires_at<=clock_timestamp()");
            try(var s=sql(c,"SELECT 1 FROM link_reservations WHERE player=? AND state='RESERVED'",player); var r=s.executeQuery()) {
                if(r.next()) throw new ConflictException("Player already has an admission");
            }
            String query="SELECT c.* FROM link_channels c JOIN link_nodes n ON n.id=c.node WHERE c.ready AND n.heartbeat>clock_timestamp()-interval '15 seconds' AND (SELECT count(*) FROM link_reservations r WHERE r.channel=c.id)<c.capacity AND (SELECT count(*) FROM link_reservations r JOIN link_channels nc ON nc.id=r.channel WHERE nc.node=c.node)<n.capacity ORDER BY (SELECT count(*) FROM link_reservations r WHERE r.channel=c.id) DESC,c.id LIMIT 1";
            try(var s=sql(c,query); var r=s.executeQuery()) {
                if(!r.next()) throw new ConflictException("NO_CAPACITY: provision a ready spawn before retrying");
                String channel=r.getString("id");
                Destination destination=new Destination(r.getString("node"),r.getString("world"),r.getDouble("x"),r.getDouble("y"),r.getDouble("z"),r.getFloat("yaw"),r.getFloat("pitch"));
                try(var insert=sql(c,"INSERT INTO link_reservations(id,player,channel,state,expires_at) VALUES (?,?,?,'RESERVED',clock_timestamp()+interval '30 seconds') RETURNING expires_at",request,player,channel); var expiry=insert.executeQuery()) {
                    expiry.next(); return new Reservation(request,player,channel,destination,expiry.getTimestamp(1).toInstant());
                }
            }
        });
    }
    public Destination admit(UUID reservation, Session owner) throws Exception {
        return db.transaction(c -> {
            // Match session -> allocation lock order everywhere.
            SessionStore.requireActive(c,owner); lock(c,"allocation");
            try(var s=sql(c,"SELECT r.*,c.node,c.world,c.x,c.y,c.z,c.yaw,c.pitch,c.ready,n.heartbeat>clock_timestamp()-interval '15 seconds' AS alive,r.expires_at>clock_timestamp() AS valid FROM link_reservations r JOIN link_channels c ON c.id=r.channel JOIN link_nodes n ON n.id=c.node WHERE r.id=? AND r.player=? FOR UPDATE OF r",reservation,owner.player()); var r=s.executeQuery()) {
                if(!r.next() || !r.getString("node").equals(owner.node()) || !r.getBoolean("ready") || !r.getBoolean("alive") || !r.getBoolean("valid"))
                    throw new ConflictException("Invalid destination reservation");
                // The target already owns the fenced session. Only now release
                // its previous admission, in the same transaction as target entry.
                update(c,"DELETE FROM link_reservations WHERE player=? AND state='ADMITTED' AND id<>?",owner.player(),reservation);
                update(c,"UPDATE link_reservations SET state='ADMITTED',expires_at='infinity' WHERE id=?",reservation);
                return reservation(r).destination();
            }
        });
    }
    public void cancel(UUID id, UUID player) throws Exception {
        db.transaction(c -> { lock(c,"allocation"); update(c,"DELETE FROM link_reservations WHERE id=? AND player=? AND state='RESERVED'",id,player); return null; });
    }
    public void depart(Session owner) throws Exception {
        db.transaction(c -> {
            SessionStore.requireActive(c,owner); lock(c,"allocation");
            update(c,"DELETE FROM link_reservations r USING link_channels c WHERE r.channel=c.id AND r.player=? AND c.node=?",owner.player(),owner.node()); return null;
        });
    }
    public int channelCount(String node) throws Exception {
        return db.transaction(c -> {try(var s=sql(c,"SELECT count(*) FROM link_channels WHERE node=?",node);var r=s.executeQuery()){r.next();return r.getInt(1);}});
    }
    public Optional<Destination> warp(String name) throws Exception {
        LinkApi.requireName(name);
        return db.transaction(c -> {
            try(var s=sql(c,"SELECT * FROM link_warps WHERE name=?",name); var r=s.executeQuery()) {
                return r.next()?Optional.of(new Destination(r.getString("node"),r.getString("world"),r.getDouble("x"),r.getDouble("y"),r.getDouble("z"),r.getFloat("yaw"),r.getFloat("pitch"))):Optional.empty();
            }
        });
    }
    public void setWarp(String name, Destination d) throws Exception {
        LinkApi.requireName(name);
        db.transaction(c -> { update(c,"INSERT INTO link_warps(name,node,world,x,y,z,yaw,pitch) VALUES (?,?,?,?,?,?,?,?) ON CONFLICT(name) DO UPDATE SET node=excluded.node,world=excluded.world,x=excluded.x,y=excluded.y,z=excluded.z,yaw=excluded.yaw,pitch=excluded.pitch",name,d.node(),d.world(),d.x(),d.y(),d.z(),d.yaw(),d.pitch()); return null; });
    }
    private static Reservation reservation(ResultSet r) throws SQLException {
        return new Reservation(r.getObject("id",UUID.class),r.getObject("player",UUID.class),r.getString("channel"),new Destination(r.getString("node"),r.getString("world"),r.getDouble("x"),r.getDouble("y"),r.getDouble("z"),r.getFloat("yaw"),r.getFloat("pitch")),r.getTimestamp("expires_at").toInstant());
    }
}
