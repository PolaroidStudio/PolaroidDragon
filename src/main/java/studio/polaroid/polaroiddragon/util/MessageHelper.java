package studio.polaroid.polaroiddragon.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

/**
 * Builds and sends localized chat components from a lang/messages_&lt;lang&gt;.yml file.
 * Each key is either a plain MiniMessage string or a {text, hover, click} section.
 *
 * <p>The banner lives in its own top-level {@code prefix:} key and is composed here in code —
 * a message value must never contain a literal {@code <prefix>} string, because MiniMessage has
 * no resolver for that tag and would print it verbatim.
 */
public final class MessageHelper {

    private MessageHelper() {}

    /**
     * Builds a Component from a messages key in {text, hover, click} form (or a plain string).
     * replacements are pairwise: "%player%", "Juan", "%nick%", "Rojo", ...
     */
    public static Component build(FileConfiguration messages, String key, String fallback,
                                  String... replacements) {
        ConfigurationSection sec = messages.getConfigurationSection(key);

        String text;
        String hover = null;
        String click = null;

        if (sec != null && sec.isString("text")) {
            text  = sec.getString("text",  fallback);
            hover = sec.getString("hover", null);
            click = sec.getString("click", null);
        } else {
            text = messages.getString(key, fallback);
        }

        text  = applyReplacements(text,  replacements);
        hover = applyReplacements(hover, replacements);
        click = applyReplacements(click, replacements);

        Component component = ColorFormats.parse(text);

        if (hover != null && !hover.isBlank()) {
            component = component.hoverEvent(HoverEvent.showText(ColorFormats.parse(hover)));
        }
        if (click != null && !click.isBlank()) {
            component = ClickActionParser.apply(component, click, "");
        }
        return component;
    }

    /** Sends to any sender: players get the full component (hover/click), console gets plain text. */
    public static void send(CommandSender sender, FileConfiguration messages, String key,
                            String fallback, String... replacements) {
        Component body = buildPrefix(messages).append(build(messages, key, fallback, replacements));
        dispatch(sender, body);
    }

    /** Same as send but without the prefix — use it for list rows, which never repeat the banner. */
    public static void sendRaw(CommandSender sender, FileConfiguration messages, String key,
                               String fallback, String... replacements) {
        dispatch(sender, build(messages, key, fallback, replacements));
    }

    /**
     * True when the key is declared as a standalone message that carries the banner
     * ({@code prefix: true} inside its section). List rows and hover text leave it out.
     */
    public static boolean wantsPrefix(FileConfiguration messages, String key) {
        ConfigurationSection sec = messages.getConfigurationSection(key);
        return sec != null && sec.getBoolean("prefix", false);
    }

    /** The configured banner, already rendered. Empty when no prefix is configured. */
    public static Component buildPrefix(FileConfiguration messages) {
        String prefixRaw = messages.getString("prefix", "");
        if (prefixRaw == null || prefixRaw.isBlank()) return Component.empty();
        return ColorFormats.parse(prefixRaw);
    }

    private static void dispatch(CommandSender sender, Component body) {
        if (sender instanceof Player player) {
            player.sendMessage(body);
        } else {
            sender.sendMessage(PlainTextComponentSerializer.plainText().serialize(body));
        }
    }

    private static String applyReplacements(String text, String[] replacements) {
        if (text == null || replacements == null || replacements.length == 0) return text;
        for (int i = 0; i + 1 < replacements.length; i += 2) {
            text = text.replace(replacements[i], replacements[i + 1]);
        }
        return text;
    }
}
