package io.linkmc.core;

import java.nio.file.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

class SlimeVaultIntegrationTest {
    @Test void realAspWorldUsesLeaseAndExactRevision()throws Exception{
        String endpoint=System.getenv("LINK_TEST_S3_ENDPOINT");
        Assumptions.assumeTrue(endpoint!=null,"Set SlimeVault integration environment");
        var store=new SlimeVaultStore(endpoint,"slimevault","worlds","link-test",System.getenv("LINK_TEST_S3_ACCESS_KEY"),System.getenv("LINK_TEST_S3_SECRET_KEY"));
        byte[] world=Files.readAllBytes(Path.of(System.getenv("LINK_TEST_S3_WORLD")));
        var revision=store.put(world);
        assertTrue(revision.version().matches("[0-9a-f]{64}"));assertArrayEquals(world,store.get(revision));
        var next=store.put(world);assertNotEquals(revision,next);assertArrayEquals(world,store.get(revision));
        assertThrows(Exception.class,()->store.put(new byte[]{1,2,3}));
    }
}
