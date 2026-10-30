package io.linkmc.server;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class LinkConfigurationTestSuite {
    @TempDir Path directory;
    private Path file(){return directory.resolve("link.yml");}
    private LinkConfiguration load(){return LinkConfiguration.load(file(),Map.of(),new Properties());}
    @Test void generatesDefaultsWithoutDatabaseAndDoesNotOverwrite()throws Exception{
        var config=load();assertEquals("disabled",config.mode());assertTrue(Files.readString(file()).contains("database:"));
        Files.writeString(file(),"# keep my comment\nmode: disabled\nnode:\n  id: custom\n");
        assertEquals("custom",load().node());assertTrue(Files.readString(file()).startsWith("# keep my comment"));
    }
    @Test void journalReadsYamlWithoutEnvironmentVariables()throws Exception{
        Files.writeString(file(),"mode: journal\nnode:\n  id: node-b\n  capacity: 75\ndatabase:\n  jdbc-url: 'jdbc:postgresql://localhost/link'\n  username: 'tester'\n  password: 'spaces # : secret'\nredis:\n  enabled: true\n  uri: 'redis://localhost:6379'\nspawn:\n  template: hub\n  version: 2\n  capacity: 25\n  max-worlds: 4\n");
        var config=load();assertEquals("journal",config.mode());assertEquals("node-b",config.node());assertEquals("tester",config.username());assertEquals("spaces # : secret",config.password());
        assertEquals("redis://localhost:6379",config.redisUri());assertEquals("hub",config.template());assertEquals(2,config.templateVersion());assertEquals(25,config.worldCapacity());assertEquals(75,config.nodeCapacity());assertEquals(4,config.maxWorlds());
    }
    @Test void legacyOverridesWinWithoutWritingSecretsToFile()throws Exception{
        var properties=new Properties();properties.setProperty("link.mode","journal");properties.setProperty("link.spawn.capacity","12");
        var config=LinkConfiguration.load(file(),Map.of("LINK_DB_PASSWORD","env-secret","LINK_NODE_ID","env-node","LINK_REDIS_URI","redis://localhost:6380"),properties);
        assertEquals("env-secret",config.password());assertEquals("env-node",config.node());assertEquals(12,config.worldCapacity());assertEquals("redis://localhost:6380",config.redisUri());
        assertFalse(Files.readString(file()).contains("env-secret"));assertFalse(properties.containsKey("LINK_DB_PASSWORD"));
    }
    @Test void rejectsMissingCredentialsWhenEnabled()throws Exception{
        Files.writeString(file(),"mode: journal\n");assertThrows(IllegalArgumentException.class,this::load);
    }
    @Test void rejectsInvalidYamlWithoutLeakingParserInput()throws Exception{
        Files.writeString(file(),"database: [VERY_SECRET\n");
        var error=assertThrows(IllegalStateException.class,this::load);assertFalse(error.toString().contains("VERY_SECRET"));assertNull(error.getCause());
    }
    @Test void rejectsInvalidModesAndLimits()throws Exception{
        Files.writeString(file(),"mode: typo\n");assertThrows(IllegalArgumentException.class,this::load);
        Files.writeString(file(),"spawn:\n  capacity: 0\n");assertThrows(IllegalArgumentException.class,this::load);
        Files.writeString(file(),"spawn:\n  capacity: nope\n");assertThrows(IllegalArgumentException.class,this::load);
    }
    @Test void redisIsDisabledUnlessEnabledOrExplicitlyOverridden()throws Exception{
        Files.writeString(file(),"redis:\n  enabled: false\n  uri: 'redis://localhost:6379'\n");assertEquals("",load().redisUri());
    }
}
