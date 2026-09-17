package studio.polaroid.polaroiddragon.manager;

import studio.polaroid.polaroiddragon.PolaroidDragon;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public class DatabaseManager {

    private static final String TABLE = "polaroiddragon_stats";

    private final PolaroidDragon plugin;
    private final boolean mysql;
    private final String jdbcUrl;
    private final String username;
    private final String password;

    private Connection connection;

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
        } else {
            String file = config.getString("sqlite.file", "data.db");
            this.jdbcUrl = "jdbc:sqlite:" + new File(plugin.getDataFolder(), file).getAbsolutePath();
            this.username = null;
            this.password = null;
        }
    }

    public boolean isMysql() {
        return mysql;
    }

    /** Carga el driver, abre la conexión y crea la tabla si no existe. */
    public void connect() throws SQLException {
        try {
            Class.forName(mysql ? "com.mysql.cj.jdbc.Driver" : "org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            throw new SQLException("Driver JDBC no encontrado: " + e.getMessage(), e);
        }
        getConnection();
        createTable();
    }

    private synchronized Connection getConnection() throws SQLException {
        if (connection == null || !connection.isValid(2)) {
            if (connection != null) {
                try { connection.close(); } catch (SQLException ignored) { }
            }
            connection = mysql
                    ? DriverManager.getConnection(jdbcUrl, username, password)
                    : DriverManager.getConnection(jdbcUrl);
        }
        return connection;
    }

    private void createTable() throws SQLException {
        String ddl = "CREATE TABLE IF NOT EXISTS " + TABLE + " (" +
                "uuid VARCHAR(36) PRIMARY KEY, " +
                "name VARCHAR(16) NOT NULL, " +
                "total_damage DOUBLE NOT NULL DEFAULT 0, " +
                "events_participated INT NOT NULL DEFAULT 0" +
                ")";
        try (Statement statement = getConnection().createStatement()) {
            statement.execute(ddl);
        }
    }

    /** Carga todas las filas de la tabla. Usado solo al iniciar. */
    public List<StatsManager.PlayerStats> loadAll() {
        List<StatsManager.PlayerStats> result = new ArrayList<>();
        String sql = "SELECT uuid, name, total_damage, events_participated FROM " + TABLE;
        try (Statement statement = getConnection().createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
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
        } catch (SQLException e) {
            plugin.getLogger().severe("No se pudieron cargar las estadísticas desde la base de datos: " + e.getMessage());
        }
        return result;
    }

    /**
     * Inserta o suma deltas de daño/eventos para cada jugador.
     * Pensado para llamarse fuera del hilo principal.
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

        try {
            Connection conn = getConnection();
            boolean previousAutoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
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
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(previousAutoCommit);
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("No se pudieron guardar las estadísticas en la base de datos: " + e.getMessage());
        }
    }

    public void close() {
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException e) {
                plugin.getLogger().warning("Error al cerrar la conexión a la base de datos: " + e.getMessage());
            }
        }
    }
}
