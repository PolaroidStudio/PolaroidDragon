package studio.polaroid.polaroiddragon.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Reads a notification block from the config and broadcasts it to every online player.
 *
 * <p>A block has four independent channels — chat, actionbar, title and sound — each with its
 * own {@code enabled} switch. Text is parsed with {@link ColorFormats}, so MiniMessage tags and
 * legacy codes both render.
 */
public final class NotificationSender {

    private NotificationSender() {}

    /**
     * @param config       the plugin FileConfiguration
     * @param path         path to the notification block (e.g. "phases.spawn.notification")
     * @param placeholders replacement map, %key% to value
     */
    public static void send(FileConfiguration config, String path, Map<String, String> placeholders) {

        // CHAT
        if (config.getBoolean(path + ".chat.enabled")) {
            List<Component> lines = new ArrayList<>();
            for (String line : config.getStringList(path + ".chat.content")) {
                lines.add(render(line, placeholders));
            }
            if (!lines.isEmpty()) {
                for (Player player : Bukkit.getOnlinePlayers()) {
                    for (Component line : lines) {
                        player.sendMessage(line);
                    }
                }
            }
        }

        // ACTIONBAR
        if (config.getBoolean(path + ".actionbar.enabled")) {
            Component component = render(config.getString(path + ".actionbar.content", ""), placeholders);
            for (Player player : Bukkit.getOnlinePlayers()) {
                player.sendActionBar(component);
            }
        }

        // TITLE
        if (config.getBoolean(path + ".title.enabled")) {
            Title title = Title.title(
                    render(config.getString(path + ".title.title", ""), placeholders),
                    render(config.getString(path + ".title.subtitle", ""), placeholders),
                    Title.Times.times(
                            Duration.ofMillis(config.getInt(path + ".title.fade-in",  10) * 50L),
                            Duration.ofMillis(config.getInt(path + ".title.stay",     60) * 50L),
                            Duration.ofMillis(config.getInt(path + ".title.fade-out", 10) * 50L)
                    )
            );
            for (Player player : Bukkit.getOnlinePlayers()) {
                player.showTitle(title);
            }
        }

        // SOUND
        if (config.getBoolean(path + ".sound.enabled")) {
            try {
                Sound sound = Sound.valueOf(config.getString(path + ".sound.content", ""));
                for (Player player : Bukkit.getOnlinePlayers()) {
                    player.playSound(player.getLocation(), sound, 1f, 1f);
                }
            } catch (IllegalArgumentException ignored) {
                // An unknown sound name silences that channel; the rest of the block still fires.
            }
        }
    }

    private static Component render(String text, Map<String, String> placeholders) {
        String applied = apply(text, placeholders);
        // A blank separator line must stay a real empty line, not collapse away.
        if (applied.isEmpty()) return Component.empty();
        return ColorFormats.parse(applied);
    }

    private static String apply(String text, Map<String, String> placeholders) {
        if (text == null) return "";
        String result = text;
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            result = result.replace(entry.getKey(), entry.getValue());
        }
        return result;
    }
}
