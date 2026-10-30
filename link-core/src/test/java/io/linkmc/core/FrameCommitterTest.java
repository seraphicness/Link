package io.linkmc.core;

import io.linkmc.api.LinkApi.*;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FrameCommitterTest {
    @Test void playerAndBlockAndEntityPublishOnlyAfterOneCommit()throws Exception{
        List<String> sequence=new ArrayList<>();
        FrameCommitter journal=new FrameCommitter(tx->{assertEquals(3,tx.changes().size());sequence.add("db");return new Commit(tx.operation(),false);});
        journal.commit(List.of(),Map.of("player:test",new byte[]{1},"world:block",new byte[]{2},"world:entity",new byte[]{3}),()->sequence.add("packets"));
        assertEquals(List.of("db","packets"),sequence);
    }
    @Test void uncertainCommitReusesExactTransaction()throws Exception{
        AtomicReference<Transaction> first=new AtomicReference<>();AtomicInteger calls=new AtomicInteger();
        FrameCommitter journal=new FrameCommitter(tx->{if(calls.getAndIncrement()==0){first.set(tx);throw new SQLException("lost response");}assertSame(first.get(),tx);return new Commit(tx.operation(),true);});
        AtomicBoolean sent=new AtomicBoolean();journal.commit(List.of(),Map.of("test:a",new byte[]{1}),()->sent.set(true));
        assertTrue(sent.get());assertEquals(1,journal.state("test:a").orElseThrow().version());
    }
    @Test void failureDiscardsPublicationAndPermanentlyFencesEngine(){
        FrameCommitter journal=new FrameCommitter(tx->{throw new SQLException("offline");});
        AtomicBoolean sent=new AtomicBoolean();
        assertThrows(SQLException.class,()->journal.commit(List.of(),Map.of("test:a",new byte[]{1}),()->sent.set(true)));
        assertFalse(sent.get());assertTrue(journal.failed());assertTrue(journal.state("test:a").isEmpty());
        assertThrows(IllegalStateException.class,()->journal.commit(List.of(),Map.of(),()->sent.set(true)));
    }
    @Test void unchangedSnapshotsDoNotCreateNewEvents()throws Exception{
        AtomicInteger writes=new AtomicInteger();FrameCommitter journal=new FrameCommitter(tx->{writes.incrementAndGet();return new Commit(tx.operation(),false);});
        journal.commit(List.of(),Map.of("test:a",new byte[]{1}),()->{});
        journal.commit(List.of(),Map.of("test:a",new byte[]{1}),()->{});
        assertEquals(1,writes.get());
    }
}
