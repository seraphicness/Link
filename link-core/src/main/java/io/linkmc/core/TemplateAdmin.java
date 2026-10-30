package io.linkmc.core;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;

/** Offline SRF publication; no server/world files are modified. */
public final class TemplateAdmin {
    private TemplateAdmin() {}
    public static void main(String[] args) throws Exception {
        if(args.length!=3)throw new IllegalArgumentException("Usage: <template> <version> <file.slime>");
        String url=required("LINK_JDBC_URL"),user=required("LINK_DB_USER"),password=required("LINK_DB_PASSWORD");
        Database db=new Database(() -> DriverManager.getConnection(url,user,password));db.migrate();
        new TemplateStore(db).publish(args[0],Integer.parseInt(args[1]),Files.readAllBytes(Path.of(args[2])));
        System.out.println("Published immutable template "+args[0]+":"+args[1]);
    }
    private static String required(String name){String value=System.getenv(name);if(value==null||value.isBlank())throw new IllegalArgumentException("Missing "+name);return value;}
}
