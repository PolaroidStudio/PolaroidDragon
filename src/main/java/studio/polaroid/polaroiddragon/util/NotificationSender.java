package studio.polaroid.polaroiddragon.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.List;
import java.util.Map;

public class NotificationSender {

    /**
     * Lee un bloque de notificación del config y lo envía a todos los jugadores online.
     *
     * @param config       FileConfiguration del plugin
     * @param path         ruta al bloque (ej. "phases.spawn.notification")
     * @param placeholders mapa de reemplazos %clave% → valor
     */
    public static void send(FileConfiguration config, String path, Map<String, String> placeholders) {

        // CHAT
        if (config.getBoolean(path + ".chat.enabled")) {
            List<String> lines = config.getStringList(path + ".chat.content");
            for (Player player : Bukkit.getOnlinePlayers()) {
                for (String line : lines) {
                    player.sendMessage(ColorUtil.parse(apply(line, placeholders)));
                }
            }
        }

        // ACTIONBAR
        if (config.getBoolean(path + ".actionbar.enabled")) {
            Component component = Component.text(
                    ColorUtil.parse(apply(config.getString(path + ".actionbar.content", ""), placeholders))
            );
            for (Player player : Bukkit.getOnlinePlayers()) {
                player.sendActionBar(component);
            }
        }

        // TITLE
        if (config.getBoolean(path + ".title.enabled")) {
            String titleText    = ColorUtil.parse(apply(config.getString(path + ".title.title",    ""), placeholders));
            String subtitleText = ColorUtil.parse(apply(config.getString(path + ".title.subtitle", ""), placeholders));
            Title title = Title.title(
                    Component.text(titleText),
                    Component.text(subtitleText),
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
            } catch (IllegalArgumentException ignored) { }
        }
    }

    private static String apply(String text, Map<String, String> placeholders) {
        if (text == null) return "";
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            text = text.replace(entry.getKey(), entry.getValue());
        }
        return text;
    }
}
