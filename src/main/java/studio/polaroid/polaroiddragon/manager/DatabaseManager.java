package studio.polaroid.polaroiddragon.manager;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import studio.polaroid.polaroiddragon.PolaroidDragon;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Hall of Fame persistence.
 *
 * <p>Every JDBC call borrows its own connection from a HikariCP pool and
 * returns it, so an async flush and a shutdown close can never touch the same
 * connection. A single shared {@link Connection} is not thread-safe in either
 * SQLite or MySQL Connector/J.
 *
 * <p>All timeouts are bounded, so a dead MySQL host fails fast instead of
 * parking the caller forever.
 *
 * <p>JDBC is blocking: every public method here must be called off the main
 * thread. {@link StatsManager} owns that contract.
 */
public class DatabaseManager {

    private static final String TABLE = "polaroiddragon_stats";

    /** SQLite has a single writer; more than one connection just starves the lock. */
    private static final int SQLITE_POOL_SIZE = 1;

    private static final long CONNECTION_TIMEOUT_MILLIS = 5_000L;
    private static final long VALIDATION_TIMEOUT_MILLIS = 3_000L;
    private static final int  QUERY_TIMEOUT_SECONDS     = 15;

    private final PolaroidDragon plugin;
    private final boolean mysql;
    private final String jdbcUrl;
    private final String username;
    private final String password;
    private final int poolSize;

    private volatile HikariDataSource dataSource;

    public DatabaseManager(PolaroidDragon plugin) {
        this.plugin = plugin;

        File configFile = new File(plugin.getDataFolder(), "data.yml");
        if (!configFile.exists()) {
            plugin.saveResource("data.yml", false);
        }
        FileConfiguration config = YamlConfiguration.loadConfiguration(configFile);

        this.mysql = "mysql".equalsIgnoreCase(config.getString("storage", "sqlite"));

        if (mysql) {
            String host = config.getString("mysql.host", "localhost");
            int port = config.getInt("mysql.port", 3306);
            String database = config.getString("mysql.database", "minecraft");
            String params = config.getString("mysql.params", "");
            this.jdbcUrl = "jdbc:mysql://" + host + ":" + port + "/" + database
                    + (params == null || params.isBlank() ? "" : "?" + params);
            this.username = config.getString("mysql.username", "root");
            this.password = config.getString("mysql.password", "");
            this.poolSize = Math.max(1, config.getInt("mysql.pool-size", 8));
        } else {
            String file = config.getString("sqlite.file", "data.db");
            this.jdbcUrl = "jdbc:sqlite:" + resolveSqliteFile(file).getAbsolutePath();
            this.username = null;
            this.password = null;
            this.poolSize = SQLITE_POOL_SIZE;
        }
    }

    /**
     * The database file always lives in {@code plugins/<plugin>/data/}, never
     * next to the YAML configs, so a config reload cannot try to parse it.
     */
    private File resolveSqliteFile(String fileName) {
        File dataDir = new File(plugin.getDataFolder(), "data");
        if (!dataDir.exists() && !dataDir.mkdirs()) {
            plugin.getLogger().warning("Could not create the data directory: " + dataDir.getAbsolutePath());
        }
        File target = new File(dataDir, fileName);

        // Older versions kept the database next to the YAML configs. Move it
        // once, otherwise the server would silently start with empty statistics.
        File legacy = new File(plugin.getDataFolder(), fileName);
        if (!target.exists() && legacy.isFile()) {
            if (legacy.renameTo(target)) {
                plugin.getLogger().info("Moved the existing database to " + target.getAbsolutePath() + ".");
            } else {
                plugin.getLogger().warning("Could not move the existing database from "
                        + legacy.getAbsolutePath() + " to " + target.getAbsolutePath()
                        + "; move it manually or the previous statistics will not be visible.");
            }
        }
        return target;
    }

    public boolean isMysql() {
        return mysql;
    }

    /** Opens the pool and creates the table if it does not exist. Call off the main thread. */
    public void connect() throws SQLException {
        String driver = mysql ? "com.mysql.cj.jdbc.Driver" : "org.sqlite.JDBC";
        try {
            Class.forName(driver);
        } catch (ClassNotFoundException e) {
            throw new SQLException("JDBC driver not found: " + e.getMessage(), e);
        }

        HikariConfig hikari = new HikariConfig();
        hikari.setPoolName("PolaroidDragon-" + (mysql ? "MySQL" : "SQLite"));
        hikari.setJdbcUrl(jdbcUrl);
        hikari.setDriverClassName(driver);
        if (mysql) {
            hikari.setUsername(username);
            hikari.setPassword(password);
            // Bounded socket/connect timeouts: a dead host must fail, not hang.
            hikari.addDataSourceProperty("connectTimeout", "5000");
            hikari.addDataSourceProperty("socketTimeout", "30000");
        }
        hikari.setMaximumPoolSize(poolSize);
        hikari.setConnectionTimeout(CONNECTION_TIMEOUT_MILLIS);
        hikari.setValidationTimeout(VALIDATION_TIMEOUT_MILLIS);
        hikari.setConnectionTestQuery("SELECT 1");

        try {
            this.dataSource = new HikariDataSource(hikari);
        } catch (RuntimeException e) {
            throw new SQLException("Could not open the database pool: " + e.getMessage(), e);
        }

        createTable();
    }

    private void createTable() throws SQLException {
        String ddl = "CREATE TABLE IF NOT EXISTS " + TABLE + " (" +
                "uuid VARCHAR(36) PRIMARY KEY, " +
                "name VARCHAR(16) NOT NULL, " +
                "total_damage DOUBLE NOT NULL DEFAULT 0, " +
                "events_participated INT NOT NULL DEFAULT 0" +
                ")";
        try (Connection conn = borrow();
             Statement statement = conn.createStatement()) {
            statement.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
            statement.execute(ddl);
        }
    }

    private Connection borrow() throws SQLException {
        HikariDataSource source = this.dataSource;
        if (source == null || source.isClosed()) {
            throw new SQLException("The database pool is not open.");
        }
        return source.getConnection();
    }

    /** Loads every row. Used only on startup. Call off the main thread. */
    public List<StatsManager.PlayerStats> loadAll() {
        List<StatsManager.PlayerStats> result = new ArrayList<>();
        String sql = "SELECT uuid, name, total_damage, events_participated FROM " + TABLE;
        try (Connection conn = borrow();
             Statement statement = conn.createStatement()) {
            statement.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
            try (ResultSet rs = statement.executeQuery(sql)) {
                while (rs.next()) {
                    try {
                        result.add(new StatsManager.PlayerStats(
                                UUID.fromString(rs.getString("uuid")),
                                rs.getString("name"),
                                rs.getDouble("total_damage"),
                                rs.getInt("events_participated")
                        ));
                    } catch (IllegalArgumentException ignored) { }
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Could not load statistics from the database: " + e.getMessage());
        }
        return result;
    }

    /**
     * Inserts or adds damage/event deltas for each player, in one transaction.
     * Call off the main thread.
     */
    public void upsertBatch(Collection<StatsManager.PlayerStats> deltas) {
        if (deltas.isEmpty()) return;

        String sql = mysql
                ? "INSERT INTO " + TABLE + " (uuid, name, total_damage, events_participated) VALUES (?, ?, ?, ?) " +
                  "ON DUPLICATE KEY UPDATE name = VALUES(name), " +
                  "total_damage = total_damage + VALUES(total_damage), " +
                  "events_participated = events_participated + VALUES(events_participated)"
                : "INSERT INTO " + TABLE + " (uuid, name, total_damage, events_participated) VALUES (?, ?, ?, ?) " +
                  "ON CONFLICT(uuid) DO UPDATE SET name = excluded.name, " +
                  "total_damage = total_damage + excluded.total_damage, " +
                  "events_participated = events_participated + excluded.events_participated";

        try (Connection conn = borrow()) {
            conn.setAutoCommit(false);
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
                for (StatsManager.PlayerStats delta : deltas) {
                    ps.setString(1, delta.getUuid().toString());
                    ps.setString(2, delta.getName());
                    ps.setDouble(3, delta.getTotalDamage());
                    ps.setInt(4, delta.getEventsParticipated());
                    ps.addBatch();
                }
                ps.executeBatch();
                conn.commit();
            } catch (SQLException e) {
                try { conn.rollback(); } catch (SQLException ignored) { }
                throw e;
            } finally {
                // The connection returns to the pool; restore its default mode.
                try { conn.setAutoCommit(true); } catch (SQLException ignored) { }
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Could not save statistics to the database: " + e.getMessage());
        }
    }

    /** Closes the pool once. Safe to call twice. */
    public void close() {
        HikariDataSource source = this.dataSource;
        this.dataSource = null;
        if (source != null && !source.isClosed()) {
            try {
                source.close();
            } catch (RuntimeException e) {
                plugin.getLogger().warning("Error while closing the database pool: " + e.getMessage());
            }
        }
    }
}
