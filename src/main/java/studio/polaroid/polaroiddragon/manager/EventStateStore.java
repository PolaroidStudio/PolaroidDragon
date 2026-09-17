package studio.polaroid.polaroiddragon.manager;

import studio.polaroid.polaroiddragon.PolaroidDragon;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;

/**
 * Crash-safe snapshot of the in-flight event.
 *
 * <p>The damage ledger lives in {@link DamageTracker}, in memory only. A restart
 * mid-fight therefore used to lose every participant's damage: the dragon was
 * re-adopted by its persistent tag, the fight continued, and the payout then ran
 * against an empty ranking. The event deadline had the same problem in reverse —
 * a fresh full timeout was armed on each startup, so restart-cycling extended an
 * event indefinitely.
 *
 * <p>This file is written when the event starts and on plugin shutdown, not per
 * hit: it exists to survive a restart, not to be a live mirror.
 */
public class EventStateStore {

    private static final String FILE_NAME = "event-state.yml";

    private final PolaroidDragon plugin;
    private final File file;

    public EventStateStore(PolaroidDragon plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), FILE_NAME);
    }

    /**
     * Persists the current ledger and the absolute deadline.
     *
     * @param dragonUuid  the active event dragon
     * @param deadlineEpochMillis when the event times out, or 0 when no timeout is configured
     */
    public void save(UUID dragonUuid, Map<UUID, Double> damage, Map<UUID, String> names,
                     long deadlineEpochMillis) {
        FileConfiguration data = new YamlConfiguration();
        data.set("dragon-uuid", dragonUuid != null ? dragonUuid.toString() : null);
        data.set("deadline", deadlineEpochMillis);

        for (Map.Entry<UUID, Double> entry : damage.entrySet()) {
            String path = "participants." + entry.getKey();
            data.set(path + ".damage", entry.getValue());
            String name = names.get(entry.getKey());
            if (name != null) data.set(path + ".name", name);
        }

        try {
            File parent = file.getParentFile();
            if (parent != null) parent.mkdirs();
            data.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("Could not save " + FILE_NAME + ": " + e.getMessage());
        }
    }

    /** Loads a previously saved snapshot, or null when there is none. */
    public Snapshot load() {
        if (!file.exists()) return null;

        FileConfiguration data = YamlConfiguration.loadConfiguration(file);
        String rawUuid = data.getString("dragon-uuid");
        if (rawUuid == null) return null;

        UUID dragonUuid;
        try {
            dragonUuid = UUID.fromString(rawUuid);
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning(FILE_NAME + " holds an invalid dragon UUID; ignoring it.");
            return null;
        }

        Snapshot snapshot = new Snapshot(dragonUuid, data.getLong("deadline", 0L));

        ConfigurationSection participants = data.getConfigurationSection("participants");
        if (participants != null) {
            for (String key : participants.getKeys(false)) {
                try {
                    snapshot.damage.put(UUID.fromString(key),
                            participants.getDouble(key + ".damage", 0.0));
                    String name = participants.getString(key + ".name");
                    if (name != null) snapshot.names.put(UUID.fromString(key), name);
                } catch (IllegalArgumentException ignored) {
                    // A malformed key is skipped rather than failing the whole restore.
                }
            }
        }
        return snapshot;
    }

    /** Drops the snapshot once the event is over. */
    public void clear() {
        if (file.exists() && !file.delete()) {
            plugin.getLogger().warning("Could not delete " + FILE_NAME + ".");
        }
    }

    /** A restored event snapshot. */
    public static class Snapshot {
        public final UUID dragonUuid;
        public final long deadlineEpochMillis;
        public final Map<UUID, Double> damage = new java.util.HashMap<>();
        public final Map<UUID, String> names = new java.util.HashMap<>();

        Snapshot(UUID dragonUuid, long deadlineEpochMillis) {
            this.dragonUuid = dragonUuid;
            this.deadlineEpochMillis = deadlineEpochMillis;
        }
    }
}
