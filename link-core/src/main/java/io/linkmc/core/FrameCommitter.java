package io.linkmc.core;

import io.linkmc.api.LinkApi.*;
import java.sql.SQLException;
import java.util.*;

/** One serialized native execution frame: snapshots become visible before packets do. */
public final class FrameCommitter {
    @FunctionalInterface public interface Writer { Commit commit(Transaction transaction) throws Exception; }
    private final Writer writer;
    private final Map<String,State> committed=new HashMap<>();
    private boolean failed;
    public FrameCommitter(Writer writer){this.writer=writer;}
    public synchronized void restore(State state){
        if(failed)throw new IllegalStateException("Frame journal is failed");
        committed.put(state.id(),state);
    }
    public synchronized Optional<State> state(String id){return Optional.ofNullable(committed.get(id));}
    /** All supplied bytes are copied by Change. Retries reuse the exact operation and payload. */
    public synchronized void commit(List<Session> owners,Map<String,byte[]> snapshots,Runnable publish) throws Exception {
        if(failed)throw new IllegalStateException("Frame journal is failed");
        List<Change> changes=new ArrayList<>();
        for(var entry:new TreeMap<>(snapshots).entrySet()){
            State previous=committed.get(entry.getKey());
            if(previous==null || !Arrays.equals(previous.payload(),entry.getValue()))
                changes.add(new Change(entry.getKey(),previous==null?0:previous.version(),1,"native:frame",entry.getValue()));
        }
        try {
            if(!changes.isEmpty()){
                Transaction transaction=new Transaction(UUID.randomUUID(),owners,changes);
                try{writer.commit(transaction);}catch(SQLException uncertain){
                    // A lost COMMIT response is reconciled by replaying the same idempotent operation.
                    try{writer.commit(transaction);}catch(Exception retry){retry.addSuppressed(uncertain);throw retry;}
                }
                for(Change change:changes)committed.put(change.id(),new State(change.id(),change.expectedVersion()+1,change.schema(),change.after()));
            }
            publish.run();
        }catch(Exception error){failed=true;throw error;}
    }
    public synchronized boolean failed(){return failed;}
}
