package io.linkmc.core;

import io.linkmc.api.LinkApi.*;
import java.util.*;
import static io.linkmc.core.Database.*;

/** Backend commands or plugins request travel; only the source backend may freeze/checkpoint its player. */
public final class RouteStore {
    public record Request(UUID id,Reservation reservation){}
    private final Database db;
    public RouteStore(Database db){this.db=db;}
    public void request(UUID id,Reservation reservation)throws Exception{
        db.transaction(c->{
            lock(c,"session:"+reservation.player());
            try(var s=sql(c,"SELECT 1 FROM link_sessions WHERE player=? AND state='ACTIVE' AND expires_at>clock_timestamp()",reservation.player());var r=s.executeQuery()){
                if(!r.next())throw new ConflictException("No active source session");
            }
            try(var s=sql(c,"SELECT player,reservation FROM link_routes WHERE id=?",id);var r=s.executeQuery()){
                if(r.next()){
                    if(!r.getObject(1,UUID.class).equals(reservation.player())||!r.getObject(2,UUID.class).equals(reservation.id()))throw new ConflictException("Route ID reused");
                    return null;
                }
            }
            update(c,"INSERT INTO link_routes(id,player,reservation,state) VALUES (?,?,?,'REQUESTED')",id,reservation.player(),reservation.id());return null;
        });
    }
    public List<Request> pending(String source)throws Exception{
        return db.transaction(c->{
            List<Request> requests=new ArrayList<>();
            try(var s=sql(c,"SELECT q.id,r.id AS reservation,r.player,r.channel,r.expires_at,c.node,c.world,r.x,r.y,r.z,r.yaw,r.pitch FROM link_routes q JOIN link_sessions s ON s.player=q.player JOIN link_reservations r ON r.id=q.reservation JOIN link_channels c ON c.id=r.channel WHERE q.state='REQUESTED' AND s.node=? AND s.state='ACTIVE' AND r.expires_at>clock_timestamp() ORDER BY q.created_at LIMIT 64",source);var r=s.executeQuery()){
                while(r.next())requests.add(new Request(r.getObject("id",UUID.class),new Reservation(r.getObject("reservation",UUID.class),r.getObject("player",UUID.class),r.getString("channel"),new Destination(r.getString("node"),r.getString("world"),r.getDouble("x"),r.getDouble("y"),r.getDouble("z"),r.getFloat("yaw"),r.getFloat("pitch")),instant(r,"expires_at"))));
            }return List.copyOf(requests);
        });
    }
    public void ready(UUID id)throws Exception{db.transaction(c->{update(c,"UPDATE link_routes SET state='READY' WHERE id=? AND state='REQUESTED'",id);return null;});}
    public List<Request> readyFor(Collection<UUID> players)throws Exception{
        if(players.isEmpty())return List.of();
        return db.transaction(c->{
            List<Request> result=new ArrayList<>();
            String placeholders=String.join(",",Collections.nCopies(players.size(),"?"));
            try(var s=sql(c,"SELECT q.id,r.id AS reservation,r.player,r.channel,r.expires_at,c.node,c.world,r.x,r.y,r.z,r.yaw,r.pitch FROM link_routes q JOIN link_reservations r ON r.id=q.reservation JOIN link_channels c ON c.id=r.channel WHERE q.state='READY' AND q.player IN ("+placeholders+") ORDER BY q.created_at LIMIT 64",players.toArray());var r=s.executeQuery()){
                while(r.next())result.add(new Request(r.getObject("id",UUID.class),new Reservation(r.getObject("reservation",UUID.class),r.getObject("player",UUID.class),r.getString("channel"),new Destination(r.getString("node"),r.getString("world"),r.getDouble("x"),r.getDouble("y"),r.getDouble("z"),r.getFloat("yaw"),r.getFloat("pitch")),instant(r,"expires_at"))));
            }return List.copyOf(result);
        });
    }
    public boolean dispatch(UUID id)throws Exception{return db.transaction(c->update(c,"UPDATE link_routes SET state='CONNECTING' WHERE id=? AND state='READY'",id)==1);}
    public void finish(UUID id,boolean success)throws Exception{db.transaction(c->{update(c,"UPDATE link_routes SET state=? WHERE id=? AND state IN ('REQUESTED','READY','CONNECTING')",success?"COMPLETE":"FAILED",id);return null;});}
    public String status(UUID id)throws Exception{return db.transaction(c->{try(var s=sql(c,"SELECT state FROM link_routes WHERE id=?",id);var r=s.executeQuery()){return r.next()?r.getString(1):"MISSING";}});}
}
