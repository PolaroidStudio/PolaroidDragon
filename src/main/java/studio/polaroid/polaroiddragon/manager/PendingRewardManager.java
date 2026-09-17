package studio.polaroid.polaroiddragon.manager;

import studio.polaroid.polaroiddragon.PolaroidDragon;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Reward queue for players who were offline when an event ended. Entries are
 * delivered automatically the next time the player joins.
 *
 * <p>Two invariants keep a queued reward from being paid twice:
 *
 * <ul>
 *   <li>{@link #deliver(Player)} clears and persists the entry <em>before</em>
 *       dispatching anything. A command that throws must never leave the entry
 *       on disk, because the player would then be paid again on every single
 *       join, without bound.</li>
 *   <li>Every dispatch is individually guarded, so one broken third-party
 *       command cannot abort the rest of the batch.</li>
 * </ul>
 *
 * <p>Writes are coalesced: {@link #queue} only marks the file dirty, and the
 * reward loop flushes once via {@link #flush()}. Saving per queued player meant
 * one full YAML serialization per winner, on the main thread, at the exact tick
 * a dragon died.
 */
public class PendingRewardManager {

    private final PolaroidDragon plugin;
    private final File file;
    private FileConfiguration data;
    private boolean dirty = false;

    public PendingRewardManager(PolaroidDragon plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "pending-rewards.yml");
        load();
    }

    private void load() {
        if (!file.exists()) {
            File parent = file.getParentFile();
            if (parent != null) parent.mkdirs();
            try {
                file.createNewFile();
            } catch (IOException e) {
                plugin.getLogger().severe("Could not create pending-rewards.yml: " + e.getMessage());
            }
        }
        data = YamlConfiguration.loadConfiguration(file);
    }

    /** Persists the queue if anything changed since the last write. */
    public void flush() {
        if (!dirty) return;
        try {
            data.save(file);
            dirty = false;
        } catch (IOException e) {
            plugin.getLogger().severe("Could not save pending-rewards.yml: " + e.getMessage());
        }
    }

    /**
     * Queues reward commands for an offline player. The caller is responsible
     * for calling {@link #flush()} once the whole payout loop is done.
     */
    public void queue(UUID uuid, String playerName, List<String> commands) {
        if (commands.isEmpty()) return;

        String path = "players." + uuid;
        String safeName = DragonManager.sanitizeName(playerName);
        List<String> pending = new ArrayList<>(data.getStringList(path + ".commands"));
        for (String cmd : commands) {
            pending.add(cmd.replace("{player}", safeName));
        }
        data.set(path + ".commands", pending);
        dirty = true;
    }

    /**
     * Delivers and clears a player's pending rewards on join.
     *
     * <p>The entry is removed and persisted first, so a failing command costs
     * that one reward instead of granting it again on every future join.
     */
    public void deliver(Player player) {
        String path = "players." + player.getUniqueId();
        List<String> commands = data.getStringList(path + ".commands");
        if (commands.isEmpty()) return;

        // Clear and persist BEFORE dispatching. See the class javadoc.
        data.set(path, null);
        dirty = true;
        flush();

        for (String cmd : commands) {
            try {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
            } catch (Exception e) {
                plugin.getLogger().warning("A pending reward command failed for "
                        + player.getName() + " ('" + cmd + "'): " + e.getMessage());
            }
        }

        player.sendMessage(plugin.getMessageManager().component("general.pending-rewards-delivered"));
    }
}
