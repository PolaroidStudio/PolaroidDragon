package studio.polaroid.polaroiddragon.manager;

import studio.polaroid.polaroiddragon.PolaroidDragon;
import studio.polaroid.polaroiddragon.util.ColorUtil;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class MessageManager {

    private final PolaroidDragon plugin;
    private final File messagesFile;
    private FileConfiguration messages;

    public MessageManager(PolaroidDragon plugin) {
        this.plugin = plugin;
        this.messagesFile = new File(plugin.getDataFolder(), "messages.yml");
        load();
    }

    public void reload() {
        load();
    }

    private void load() {
        if (!messagesFile.exists()) {
            plugin.saveResource("messages.yml", false);
        }
        messages = YamlConfiguration.loadConfiguration(messagesFile);
    }

    // ─────────────────────────────────────────────
    //  MENSAJES
    // ─────────────────────────────────────────────

    public String get(String path) {
        return get(path, Map.of());
    }

    public String get(String path, Map<String, String> placeholders) {
        return ColorUtil.parse(apply(messages.getString(path, path), placeholders));
    }

    public List<String> getList(String path) {
        return getList(path, Map.of());
    }

    public List<String> getList(String path, Map<String, String> placeholders) {
        return messages.getStringList(path).stream()
                .map(line -> ColorUtil.parse(apply(line, placeholders)))
                .collect(Collectors.toList());
    }

    /** Medalla para una posición del ranking (1 = 1er, 2 = 2do, ...). */
    public String getMedal(int position) {
        List<String> medals = messages.getStringList("medals.positions");
        if (position >= 1 && position <= medals.size()) return ColorUtil.parse(medals.get(position - 1));
        return ColorUtil.parse(messages.getString("medals.fallback", "&8{position}to")
                .replace("{position}", String.valueOf(position)));
    }

    private String apply(String text, Map<String, String> placeholders) {
        if (text == null) return "";
        String result = text.replace("{prefix}", messages.getString("prefix", ""));
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            result = result.replace(entry.getKey(), entry.getValue());
        }
        return result;
    }
}
