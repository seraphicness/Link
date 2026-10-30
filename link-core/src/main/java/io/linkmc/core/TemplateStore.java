package io.linkmc.core;

import io.linkmc.api.LinkApi;
import java.security.MessageDigest;
import java.util.*;
import static io.linkmc.core.Database.*;

/** Immutable published SRF blobs. Editing always happens in a separate world. */
public final class TemplateStore {
    private final Database db;
    public TemplateStore(Database db) { this.db=db; }
    public void publish(String name,int version,byte[] srf) throws Exception {
        LinkApi.requireName(name);
        if(version<1 || srf.length==0) throw new IllegalArgumentException("Invalid template");
        byte[] bytes=srf.clone(), digest=MessageDigest.getInstance("SHA-256").digest(bytes);
        db.transaction(c -> {
            lock(c,"template:"+name+":"+version);
            try(var s=sql(c,"SELECT sha256 FROM link_templates WHERE name=? AND version=?",name,version);var r=s.executeQuery()) {
                if(r.next()) {
                    if(!MessageDigest.isEqual(digest,r.getBytes(1))) throw new ConflictException("Published template versions are immutable");
                    return null;
                }
            }
            update(c,"INSERT INTO link_templates(name,version,payload,sha256) VALUES (?,?,?,?)",name,version,bytes,digest); return null;
        });
    }
    public byte[] read(String name,int version) throws Exception {
        LinkApi.requireName(name);
        return db.transaction(c -> {
            try(var s=sql(c,"SELECT payload,sha256 FROM link_templates WHERE name=? AND version=?",name,version);var r=s.executeQuery()) {
                if(!r.next()) throw new ConflictException("Unknown template version");
                byte[] bytes=r.getBytes(1);
                if(!MessageDigest.isEqual(MessageDigest.getInstance("SHA-256").digest(bytes),r.getBytes(2))) throw new ConflictException("Template checksum mismatch");
                return bytes;
            }
        });
    }
    public List<String> list() throws Exception {
        return db.transaction(c -> {
            var result=new ArrayList<String>();
            try(var s=sql(c,"SELECT name,version FROM link_templates ORDER BY name,version");var r=s.executeQuery()) {
                while(r.next()) result.add(r.getString(1)+":"+r.getInt(2));
            }
            return List.copyOf(result);
        });
    }
}
