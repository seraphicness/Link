package io.linkmc.velocity;

import java.nio.file.*;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class VelocityConfigurationTest {
    @TempDir Path directory;
    private Path file(){return directory.resolve("config.yml");}
    @Test void generatesFileBeforeRejectingUnconfiguredDatabase()throws Exception{
        assertThrows(IllegalArgumentException.class,()->VelocityConfiguration.load(directory,Map.of()));
        assertTrue(Files.readString(file()).contains("jdbc:mariadb:"));
    }
    @Test void readsYamlAndPreservesExistingComments()throws Exception{
        String yaml="# keep\ndatabase:\n  jdbc-url: 'jdbc:mariadb://localhost/link'\n  username: 'link'\n  password: 'spaces # : secret'\n";
        Files.writeString(file(),yaml);
        var config=VelocityConfiguration.load(directory,Map.of());
        assertEquals("jdbc:mariadb://localhost/link",config.jdbcUrl());assertEquals("link",config.username());
        assertEquals("spaces # : secret",config.password());assertEquals(yaml,Files.readString(file()));
    }
    @Test void environmentOverridesAreNotPersisted()throws Exception{
        var config=VelocityConfiguration.load(directory,Map.of("LINK_JDBC_URL","jdbc:postgresql://localhost/link","LINK_DB_USER","env-user","LINK_DB_PASSWORD","env-secret"));
        assertEquals("env-secret",config.password());assertEquals("env-user",config.username());
        assertEquals("jdbc:postgresql://localhost/link",config.jdbcUrl());assertFalse(Files.readString(file()).contains("env-secret"));
    }
    @Test void invalidYamlDoesNotExposeSecrets()throws Exception{
        Files.writeString(file(),"database: [VERY_SECRET\n");
        var error=assertThrows(IllegalStateException.class,()->VelocityConfiguration.load(directory,Map.of()));
        assertFalse(error.toString().contains("VERY_SECRET"));assertNull(error.getCause());
    }
    @Test void rejectsDuplicateKeysAndInvalidDatabase()throws Exception{
        Files.writeString(file(),"database:\n  password: 'a'\n  password: 'b'\n");
        assertThrows(IllegalStateException.class,()->VelocityConfiguration.load(directory,Map.of()));
        Files.writeString(file(),"database:\n  jdbc-url: 'jdbc:other://localhost'\n  username: 'link'\n  password: 'secret'\n");
        assertThrows(IllegalArgumentException.class,()->VelocityConfiguration.load(directory,Map.of()));
    }
}
