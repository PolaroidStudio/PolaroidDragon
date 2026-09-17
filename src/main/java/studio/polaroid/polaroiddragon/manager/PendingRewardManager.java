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
 * Cola de recompensas para jugadores que estaban offline cuando terminó el evento.
 * Se entregan automáticamente la próxima vez que el jugador se conecta.
 */
public class PendingRewardManager {

    private final PolaroidDragon plugin;
    private final File file;
    private FileConfiguration data;

    public PendingRewardManager(PolaroidDragon plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "pending-rewards.yml");
        load();
    }

    private void load() {
        if (!file.exists()) {
            try { file.createNewFile(); } catch (IOException e) {
                plugin.getLogger().severe("No se pudo crear pending-rewards.yml: " + e.getMessage());
            }
        }
        data = YamlConfiguration.loadConfiguration(file);
    }

    private void save() {
        try {
            data.save(file);
        } catch (IOException e) {
            plugin.getLogger().severe("No se pudo guardar pending-rewards.yml: " + e.getMessage());
        }
    }

    /** Encola comandos de recompensa (con {player} ya resuelto) para un jugador offline. */
    public void queue(UUID uuid, String playerName, List<String> commands) {
        if (commands.isEmpty()) return;

        String path = "players." + uuid;
        List<String> pending = new ArrayList<>(data.getStringList(path + ".commands"));
        for (String cmd : commands) {
            pending.add(cmd.replace("{player}", playerName));
        }
        data.set(path + ".commands", pending);
        save();
    }

    /** Entrega y limpia las recompensas pendientes de un jugador al conectarse. */
    public void deliver(Player player) {
        String path = "players." + player.getUniqueId();
        List<String> commands = data.getStringList(path + ".commands");
        if (commands.isEmpty()) return;

        for (String cmd : commands) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
        }
        data.set(path, null);
        save();

        player.sendMessage(plugin.getMessageManager().get("general.pending-rewards-delivered"));
    }
}
