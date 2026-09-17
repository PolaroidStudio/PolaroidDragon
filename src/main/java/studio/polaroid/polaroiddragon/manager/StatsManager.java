package studio.polaroid.polaroiddragon.manager;

import studio.polaroid.polaroiddragon.PolaroidDragon;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class StatsManager {

    private final PolaroidDragon plugin;
    private final DatabaseManager db;

    /** True once the async startup load has finished. Reads before that see an empty ranking. */
    private volatile boolean loaded = false;

    /** Upper bound on how long server shutdown waits for the final flush. */
    private static final long SHUTDOWN_TIMEOUT_MILLIS = 10_000L;

    // Totales acumulados por jugador — lectura/escritura desde hilo principal Y async (PlaceholderAPI)
    private final Map<UUID, PlayerStats> totals = new ConcurrentHashMap<>();

    // Snapshot del ranking, reemplazado en cada flush(). Lectura desde cualquier hilo.
    private volatile List<PlayerStats> cachedRanking = new ArrayList<>();

    // Deltas acumulados desde el último flush(). Solo se accede desde el hilo principal.
    private final Map<UUID, PendingDelta> pending = new HashMap<>();

    public StatsManager(PolaroidDragon plugin) {
        this.plugin = plugin;
        this.db = new DatabaseManager(plugin);
    }

    /**
     * Opens the pool and loads the Hall of Fame off the main thread.
     *
     * <p>JDBC is blocking and neither the SQLite file nor a remote MySQL host
     * answers within a tick budget, so none of this may run on the server
     * thread. Until the load finishes, queries simply report empty stats.
     */
    public void initAsync() {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                db.connect();
            } catch (SQLException e) {
                plugin.getLogger().severe("Could not connect to the statistics database: " + e.getMessage());
                return;
            }

            List<PlayerStats> rows = db.loadAll();
            if (rows.isEmpty()) {
                rows = maybeMigrateFromYaml();
            }

            for (PlayerStats ps : rows) {
                totals.put(ps.getUuid(), ps);
            }
            rebuildCache();
            loaded = true;

            plugin.getLogger().info("Statistics loaded from the database (" + totals.size() + " players).");
        });
    }

    /** True once the startup load finished. */
    public boolean isLoaded() {
        return loaded;
    }

    // ─────────────────────────────────────────────
    //  MIGRACIÓN DESDE stats.yml (una sola vez)
    // ─────────────────────────────────────────────

    private List<PlayerStats> maybeMigrateFromYaml() {
        File statsFile = new File(plugin.getDataFolder(), "stats.yml");
        if (!statsFile.exists()) return List.of();

        FileConfiguration old = YamlConfiguration.loadConfiguration(statsFile);
        if (!old.contains("players")) return List.of();

        List<PlayerStats> migrated = new ArrayList<>();
        for (String uuidStr : old.getConfigurationSection("players").getKeys(false)) {
            String path = "players." + uuidStr;
            try {
                migrated.add(new PlayerStats(
                        UUID.fromString(uuidStr),
                        old.getString(path + ".name", uuidStr),
                        old.getDouble(path + ".total-damage", 0.0),
                        old.getInt(path + ".events-participated", 0)
                ));
            } catch (IllegalArgumentException ignored) { }
        }

        if (migrated.isEmpty()) return migrated;

        db.upsertBatch(migrated);

        File backup = new File(plugin.getDataFolder(), "stats.yml.bak");
        if (statsFile.renameTo(backup)) {
            plugin.getLogger().info("Migración completada: " + migrated.size()
                    + " jugadores importados desde stats.yml a la base de datos. El archivo antiguo se renombró a stats.yml.bak.");
        } else {
            plugin.getLogger().warning("Migración completada (" + migrated.size()
                    + " jugadores), pero no se pudo renombrar stats.yml a stats.yml.bak.");
        }

        return migrated;
    }

    // ─────────────────────────────────────────────
    //  REGISTRO DE EVENTOS
    // ─────────────────────────────────────────────

    /**
     * Registra la participación de un jugador al finalizar un evento.
     * Llamado una vez por jugador por evento. No persiste a disco — llamar a
     * flush() una sola vez después de procesar a todos los participantes.
     */
    public void recordParticipation(UUID uuid, String name, double damage) {
        totals.compute(uuid, (key, existing) -> existing == null
                ? new PlayerStats(uuid, name, damage, 1)
                : new PlayerStats(uuid, name, existing.getTotalDamage() + damage, existing.getEventsParticipated() + 1));

        pending.compute(uuid, (key, existing) -> {
            if (existing == null) return new PendingDelta(name, damage, 1);
            existing.name = name;
            existing.damage += damage;
            existing.events += 1;
            return existing;
        });
    }

    /** Reconstruye la caché de ranking en memoria y persiste los deltas pendientes de forma asíncrona. */
    public void flush() {
        rebuildCache();

        List<PlayerStats> deltas = drainPending();
        if (deltas.isEmpty()) return;

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> db.upsertBatch(deltas));
    }

    private List<PlayerStats> drainPending() {
        if (pending.isEmpty()) return List.of();

        List<PlayerStats> deltas = new ArrayList<>(pending.size());
        for (Map.Entry<UUID, PendingDelta> entry : pending.entrySet()) {
            PendingDelta delta = entry.getValue();
            deltas.add(new PlayerStats(entry.getKey(), delta.name, delta.damage, delta.events));
        }
        pending.clear();
        return deltas;
    }

    // ─────────────────────────────────────────────
    //  CONSULTAS
    // ─────────────────────────────────────────────

    /** Top N por daño total histórico. */
    public List<PlayerStats> getTopByDamage(int limit) {
        return cachedRanking.stream().limit(limit).collect(Collectors.toList());
    }

    public PlayerStats getPlayerStats(UUID uuid) {
        return totals.get(uuid);
    }

    // ─────────────────────────────────────────────
    //  CACHE
    // ─────────────────────────────────────────────

    private void rebuildCache() {
        List<PlayerStats> list = new ArrayList<>(totals.values());
        list.sort(Comparator.comparingDouble(PlayerStats::getTotalDamage).reversed());
        cachedRanking = list;
    }

    // ─────────────────────────────────────────────
    //  CIERRE
    // ─────────────────────────────────────────────

    /**
     * Final flush plus pool shutdown, on a worker thread bounded by a timeout.
     *
     * <p>Bukkit kills its async scheduler before {@code onDisable} returns, so
     * the last flush cannot be a scheduler task. It runs on a plain thread that
     * the main thread joins for at most {@link #SHUTDOWN_TIMEOUT_MILLIS}, which
     * keeps a hung database from stalling server shutdown indefinitely.
     */
    public void close() {
        List<PlayerStats> deltas = drainPending();

        Thread worker = new Thread(() -> {
            if (!deltas.isEmpty()) {
                db.upsertBatch(deltas);
            }
            db.close();
        }, "PolaroidDragon-stats-shutdown");
        worker.setDaemon(true);
        worker.start();

        try {
            worker.join(SHUTDOWN_TIMEOUT_MILLIS);
            if (worker.isAlive()) {
                plugin.getLogger().warning("The final statistics flush did not finish within "
                        + (SHUTDOWN_TIMEOUT_MILLIS / 1000) + "s; shutting down anyway.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ─────────────────────────────────────────────
    //  RECORD
    // ─────────────────────────────────────────────

    public static class PlayerStats {
        private final UUID uuid;
        private final String name;
        private final double totalDamage;
        private final int eventsParticipated;

        public PlayerStats(UUID uuid, String name, double totalDamage, int eventsParticipated) {
            this.uuid = uuid;
            this.name = name;
            this.totalDamage = totalDamage;
            this.eventsParticipated = eventsParticipated;
        }

        public UUID getUuid()              { return uuid; }
        public String getName()            { return name; }
        public double getTotalDamage()     { return totalDamage; }
        public int getEventsParticipated() { return eventsParticipated; }
    }

    private static class PendingDelta {
        String name;
        double damage;
        int events;

        PendingDelta(String name, double damage, int events) {
            this.name = name;
            this.damage = damage;
            this.events = events;
        }
    }
}
