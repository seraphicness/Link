package io.linkmc.velocity;

import java.io.IOException;
import java.nio.file.*;
import java.util.Map;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/** Credentials are neither logged nor copied back into the user's configuration. */
final class VelocityConfiguration {
    private final String jdbcUrl, username, password;
    private VelocityConfiguration(String jdbcUrl,String username,String password){
        this.jdbcUrl=jdbcUrl;this.username=username;this.password=password;
    }
    String jdbcUrl(){return jdbcUrl;}
    String username(){return username;}
    String password(){return password;}
    static VelocityConfiguration load(Path directory,Map<String,String> environment){
        Map<?,?> root;
        try{
            Files.createDirectories(directory);
            Path file=directory.resolve("config.yml");
            try{Files.writeString(file,DEFAULTS,StandardOpenOption.CREATE_NEW);}catch(FileAlreadyExistsException ignored){}
            var options=new LoaderOptions();options.setAllowDuplicateKeys(false);options.setCodePointLimit(1024*1024);
            try(var reader=Files.newBufferedReader(file)){
                Object value=new Yaml(new SafeConstructor(options)).load(reader);
                if(!(value instanceof Map<?,?> mapping))throw new IllegalArgumentException();
                root=mapping;
            }
        }catch(IOException|RuntimeException error){
            // YAML parser errors may quote lines containing credentials.
            throw new IllegalStateException("Cannot read plugins/link/config.yml; check YAML syntax and file permissions");
        }
        Object section=root.get("database");
        if(!(section instanceof Map<?,?> database))throw new IllegalArgumentException("Configure database in plugins/link/config.yml");
        String url=value(database,environment,"jdbc-url","LINK_JDBC_URL");
        String user=value(database,environment,"username","LINK_DB_USER");
        String password=value(database,environment,"password","LINK_DB_PASSWORD");
        if((!url.startsWith("jdbc:postgresql:")&&!url.startsWith("jdbc:mariadb:"))||user.isBlank()||password.isBlank())
            throw new IllegalArgumentException("Configure database.jdbc-url, username and password in plugins/link/config.yml, then restart Velocity");
        return new VelocityConfiguration(url,user,password);
    }
    private static String value(Map<?,?> database,Map<String,String> environment,String key,String variable){
        if(environment.containsKey(variable))return environment.get(variable);
        Object value=database.get(key);
        if(!(value instanceof String text))throw new IllegalArgumentException("database."+key+" must be a quoted string in plugins/link/config.yml");
        return text;
    }
    private static final String DEFAULTS="""
        # Link Velocity configuration. Edit this file and restart Velocity.
        # Use the SAME database as all Link backends.
        # LINK_JDBC_URL, LINK_DB_USER and LINK_DB_PASSWORD override these values.
        # Backend lists and modern forwarding remain in velocity.toml.
        database:
          # PostgreSQL is also supported: jdbc:postgresql://127.0.0.1:5432/link
          jdbc-url: 'jdbc:mariadb://127.0.0.1:3306/link'
          username: 'link'
          password: ''
        """;
}
