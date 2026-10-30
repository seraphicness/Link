package io.linkmc.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.Objects;

/** One connection per transaction; connection factory may be backed by a pool. */
public final class Database implements AutoCloseable {
    @FunctionalInterface public interface Connections { Connection open() throws SQLException; }
    @FunctionalInterface public interface Work<T> { T run(Connection connection) throws Exception; }
    private final Connections connections;
    private Runnable close=()->{};
    public Database(Connections connections) { this.connections = Objects.requireNonNull(connections); }
    public static Database pooled(String url,String user,String password){
        var config=new com.zaxxer.hikari.HikariConfig();
        // Velocity uses an isolated plugin class loader; JDBC service discovery
        // on an event thread may otherwise see only the proxy's parent loader.
        config.setDriverClassName("org.postgresql.Driver");
        config.setJdbcUrl(url);config.setUsername(user);config.setPassword(password);
        config.setPoolName("Link-SQL");config.setMaximumPoolSize(8);config.setMinimumIdle(2);
        config.setConnectionTimeout(5000);config.setValidationTimeout(2000);
        config.addDataSourceProperty("tcpKeepAlive","true");
        config.addDataSourceProperty("connectTimeout","5");
        config.addDataSourceProperty("socketTimeout","15");
        var pool=new com.zaxxer.hikari.HikariDataSource(config);
        var db=new Database(pool::getConnection);db.close=pool::close;return db;
    }
    @Override public void close(){close.run();}
    public <T> T transaction(Work<T> work) throws Exception {
        try (Connection c = connections.open()) {
            c.setAutoCommit(false);
            c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            try {
                try (Statement s = c.createStatement()) {
                    s.execute("SET LOCAL statement_timeout = '10s'");
                    s.execute("SET LOCAL lock_timeout = '5s'");
                    s.execute("SET LOCAL synchronous_commit = on");
                }
                T result = work.run(c);
                c.commit();
                return result;
            } catch (Exception e) {
                try { c.rollback(); } catch (SQLException rollback) { e.addSuppressed(rollback); }
                throw e;
            }
        }
    }
    public void migrate() throws Exception {
        try (var input = Database.class.getResourceAsStream("/link/schema.sql")) {
            if (input == null) throw new IOException("Missing Link schema");
            String sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            transaction(c -> {
                lock(c, "schema");
                try (Statement s = c.createStatement()) { s.execute(sql); }
                try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT max(version) FROM link_schema")) {
                    r.next();
                    if (r.getInt(1) != 1) throw new SQLException("Unsupported Link schema");
                }
                return null;
            });
        }
    }
    /** Stable transaction lock; collisions serialize unrelated work but cannot corrupt it. */
    public static void lock(Connection c, String key) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))")) {
            s.setString(1, "link:" + key); s.execute();
        }
    }
    public static PreparedStatement sql(Connection c, String sql, Object... args) throws SQLException {
        PreparedStatement s = c.prepareStatement(sql);
        try {
            for (int i = 0; i < args.length; i++) s.setObject(i + 1, args[i]);
            return s;
        } catch (SQLException e) { s.close(); throw e; }
    }
    public static int update(Connection c, String sql, Object... args) throws SQLException {
        try (PreparedStatement s = sql(c, sql, args)) { return s.executeUpdate(); }
    }
}
