package studio.polaroid.polaroiddragon.manager;

import studio.polaroid.polaroiddragon.PolaroidDragon;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Remembers who the last dragon hunter was, across restarts.
 *
 * <p>Two records are kept, and they are deliberately independent: the player who
 * landed the final blow and the player who topped the damage ranking. They are
 * frequently different people, and a server that shows "last killer" on an NPC
 * does not want that NPC to change because someone else out-damaged them.
 *
 * <p><b>Sticky by design.</b> Each record keeps the last <em>valid</em> value
 * forever until a new valid one replaces it. An event that times out, or whose
 * dragon dies to lava, has no killer — recording that would blank a name the
 * server is displaying, so {@link #recordKiller} is a no-op on null and the
 * previous value survives. The same holds for an empty ranking. Because the two
 * update independently, an event with a killer but no ranking refreshes only
 * the killer record.
 *
 * <p>This intentionally does not reuse {@code event-state.yml}: that file is
 * deleted when an event ends, which is precisely when these values are written.
 */
public class HunterStore {

    private static final String FILE_NAME = "last-hunters.yml";

    private static final String KILLER_PATH = "last-killer";
    private static final String TOP_DAMAGER_PATH = "top-damager";

    private final Logger logger;
    private final File file;

    // Written on the main thread at event end, read by PlaceholderAPI, which
    // scoreboard and tab plugins routinely evaluate off the main thread. The
    // Hunter record is immutable, so publishing the reference through a
    // volatile field is enough to make its fields safely visible.
    private volatile Hunter lastKiller;
    private volatile Hunter topDamager;

    public HunterStore(PolaroidDragon plugin) {
        this(plugin.getDataFolder(), plugin.getLogger());
    }

    /**
     * Folder-and-logger constructor. The store holds no other plugin state, so
     * taking these directly keeps it testable without a running server.
     */
    public HunterStore(File dataFolder, Logger logger) {
        this.logger = logger;
        this.file = new File(dataFolder, FILE_NAME);
        load();
    }

    /**
     * Records the player who landed the final blow.
     *
     * <p>A null uuid means the event had no killer; the previous record is kept.
     */
    public void recordKiller(UUID uuid, String name) {
        if (uuid == null) return;
        lastKiller = new Hunter(uuid, name, System.currentTimeMillis());
        save();
    }

    /**
     * Records the player who topped the damage ranking.
     *
     * <p>A null uuid means the ranking was empty; the previous record is kept.
     */
    public void recordTopDamager(UUID uuid, String name) {
        if (uuid == null) return;
        topDamager = new Hunter(uuid, name, System.currentTimeMillis());
        save();
    }

    /** The last player who landed the final blow, or null when never recorded. */
    public Hunter getLastKiller() {
        return lastKiller;
    }

    /** The last player who topped the damage ranking, or null when never recorded. */
    public Hunter getTopDamager() {
        return topDamager;
    }

    private void load() {
        if (!file.exists()) return;

        FileConfiguration data = YamlConfiguration.loadConfiguration(file);
        lastKiller = read(data, KILLER_PATH);
        topDamager = read(data, TOP_DAMAGER_PATH);
    }

    private Hunter read(FileConfiguration data, String path) {
        String rawUuid = data.getString(path + ".uuid");
        if (rawUuid == null) return null;

        try {
            return new Hunter(
                    UUID.fromString(rawUuid),
                    data.getString(path + ".name"),
                    data.getLong(path + ".timestamp", 0L)
            );
        } catch (IllegalArgumentException e) {
            // A malformed entry is dropped rather than failing the whole load:
            // the other role's record is still perfectly usable.
            logger.warning(FILE_NAME + " holds an invalid UUID under '"
                    + path + "'; ignoring that entry.");
            return null;
        }
    }

    private void save() {
        FileConfiguration data = new YamlConfiguration();
        write(data, KILLER_PATH, lastKiller);
        write(data, TOP_DAMAGER_PATH, topDamager);

        try {
            File parent = file.getParentFile();
            if (parent != null) parent.mkdirs();
            data.save(file);
        } catch (IOException e) {
            logger.warning("Could not save " + FILE_NAME + ": " + e.getMessage());
        }
    }

    private void write(FileConfiguration data, String path, Hunter hunter) {
        if (hunter == null) return;
        data.set(path + ".uuid", hunter.uuid().toString());
        data.set(path + ".name", hunter.name());
        data.set(path + ".timestamp", hunter.timestamp());
    }

    /**
     * One remembered hunter. Immutable so it can be published across threads by
     * a single volatile write.
     *
     * @param uuid      the player
     * @param name      their name as it was resolved at event end, may be null
     * @param timestamp when the record was taken, epoch millis
     */
    public record Hunter(UUID uuid, String name, long timestamp) {
    }
}
