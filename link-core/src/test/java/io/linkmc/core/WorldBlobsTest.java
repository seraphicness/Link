package io.linkmc.core;

import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class WorldBlobsTest {
    static final class Memory implements WorldBlobs.Store {
        final Map<WorldBlobs.Revision,byte[]> values=new HashMap<>();
        boolean unavailable;
        public WorldBlobs.Revision put(byte[] bytes)throws IOException{
            if(unavailable)throw new IOException("unavailable");
            var revision=new WorldBlobs.Revision("worlds","object.slime",UUID.randomUUID().toString());
            values.put(revision,bytes.clone());return revision;
        }
        public byte[] get(WorldBlobs.Revision revision)throws IOException{
            if(unavailable)throw new IOException("unavailable");
            return values.get(revision);
        }
    }
    @Test void exactRevisionSurvivesLaterWritesAndNewReader()throws Exception{
        var store=new Memory();var blobs=new WorldBlobs(store);
        byte[] first=blobs.encode("world",new byte[]{1});
        assertArrayEquals(first,blobs.encode("world",new byte[]{1}));assertEquals(1,store.values.size());
        byte[] second=blobs.encode("world",new byte[]{2});
        var restarted=new WorldBlobs(store);
        assertArrayEquals(new byte[]{1},restarted.decode(first));assertArrayEquals(new byte[]{2},restarted.decode(second));
    }
    @Test void uploadFailureDoesNotCacheUncommittedReference()throws Exception{
        var store=new Memory();var blobs=new WorldBlobs(store);store.unavailable=true;
        assertThrows(IOException.class,()->blobs.encode("world",new byte[]{1}));assertTrue(store.values.isEmpty());
        store.unavailable=false;assertArrayEquals(new byte[]{1},blobs.decode(blobs.encode("world",new byte[]{1})));
    }
    @Test void corruptionAndMissingStoreFailClosed()throws Exception{
        var store=new Memory();var blobs=new WorldBlobs(store);byte[] reference=blobs.encode("world",new byte[]{1});
        store.values.replaceAll((key,value)->new byte[]{9});
        assertThrows(IOException.class,()->blobs.decode(reference));
        assertThrows(IllegalStateException.class,()->new WorldBlobs(null).decode(reference));
    }
    @Test void existingSqlPayloadsRemainReadable()throws Exception{
        byte[] payload={1,2,3};assertArrayEquals(payload,new WorldBlobs(new Memory()).decode(payload));
        assertArrayEquals(payload,new WorldBlobs(null).encode("world",payload));
    }
}
