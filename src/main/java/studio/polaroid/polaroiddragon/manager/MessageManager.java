package studio.polaroid.polaroiddragon.manager;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import studio.polaroid.polaroiddragon.PolaroidDragon;
import studio.polaroid.polaroiddragon.util.ColorFormats;
import studio.polaroid.polaroiddragon.util.MessageHelper;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Loads lang/messages_&lt;language&gt;.yml and resolves message keys.
 *
 * <p>English is the base language: lang/messages_en.yml is installed as the configuration
 * defaults, so a key missing from a translation automatically falls back to English instead of
 * printing the raw key.
 *
 * <p>The banner is never inlined in a message value. It lives in the top-level {@code prefix:}
 * key and is composed here — {@code get(...)} returns the message alone, {@code prefixed(...)}
 * returns prefix + message.
 */
public class MessageManager {

    private static final String BASE_LANGUAGE = "en";

    private final PolaroidDragon plugin;
    private FileConfiguration messages;

    public MessageManager(PolaroidDragon plugin) {
        this.plugin = plugin;
        load();
    }

    public void reload() {
        load();
    }

    private void load() {
        migrateLegacyMessagesFile();

        saveResourceIfMissing("lang/messages_" + BASE_LANGUAGE + ".yml");

        String language = plugin.getConfig().getString("language", BASE_LANGUAGE);
        saveResourceIfMissing("lang/messages_" + language + ".yml");

        File file = new File(plugin.getDataFolder(), "lang/messages_" + language + ".yml");
        if (!file.exists()) {
            plugin.getLogger().warning("lang/messages_" + language
                    + ".yml not found — falling back to " + BASE_LANGUAGE + ".");
            file = new File(plugin.getDataFolder(), "lang/messages_" + BASE_LANGUAGE + ".yml");
        }

        FileConfiguration loaded = YamlConfiguration.loadConfiguration(file);

        // English defaults: any key missing from the active translation resolves to English.
        try (InputStream in = plugin.getResource("lang/messages_" + BASE_LANGUAGE + ".yml")) {
            if (in != null) {
                loaded.setDefaults(YamlConfiguration.loadConfiguration(
                        new InputStreamReader(in, StandardCharsets.UTF_8)));
            }
        } catch (IOException ignored) {
            // A missing base file only costs the fallback, never the load.
        }

        messages = loaded;
    }

    private void saveResourceIfMissing(String path) {
        if (plugin.getResource(path) != null && !new File(plugin.getDataFolder(), path).exists()) {
            plugin.saveResource(path, false);
        }
    }

    /**
     * Renames a pre-i18n messages.yml so the server owner keeps their old copy but the plugin
     * stops reading it. The strings inside it use keys that no longer exist.
     */
    private void migrateLegacyMessagesFile() {
        File legacy = new File(plugin.getDataFolder(), "messages.yml");
        if (!legacy.exists()) return;
        File backup = new File(plugin.getDataFolder(), "messages.yml.pre-i18n.bak");
        if (backup.exists() || legacy.renameTo(backup)) {
            plugin.getLogger().info("messages.yml is superseded by lang/messages_<language>.yml; "
                    + "the old file was kept as messages.yml.pre-i18n.bak.");
        }
    }

    // ─────────────────────────────────────────────
    //  MESSAGES
    // ─────────────────────────────────────────────

    /** The raw FileConfiguration, for callers that go through MessageHelper directly. */
    public FileConfiguration raw() {
        return messages;
    }

    /** The configured banner as a Component. */
    public Component prefixComponent() {
        return MessageHelper.buildPrefix(messages);
    }

    /**
     * A message stripped to plain text, with no formatting at all.
     *
     * <p>Only for destinations that cannot render a Component: a Discord embed
     * or a value interpolated into another template. Anything sent to a player
     * must use {@link #component(String)} instead, because rendering to a
     * legacy §-string drops hover and click events and flattens gradients.
     */
    public String plainText(String path) {
        return PlainTextComponentSerializer.plainText().serialize(component(path));
    }

    public Component component(String path) {
        return component(path, Map.of());
    }

    /**
     * A message as a Component (hover/click preserved).
     *
     * <p>The banner is prepended only when the key declares {@code prefix: true} in the language
     * file — that is how a standalone result keeps its banner while a list row does not. The
     * banner itself is never written inside a message value.
     */
    public Component component(String path, Map<String, String> placeholders) {
        Component body = MessageHelper.build(messages, path, path, flatten(placeholders));
        if (MessageHelper.wantsPrefix(messages, path)) {
            return prefixComponent().append(body);
        }
        return body;
    }

    public Component prefixedComponent(String path) {
        return prefixedComponent(path, Map.of());
    }

    /** Banner + message as a Component. Idempotent: a key already flagged {@code prefix: true}
     *  is not banner-stamped twice. */
    public Component prefixedComponent(String path, Map<String, String> placeholders) {
        if (MessageHelper.wantsPrefix(messages, path)) {
            return component(path, placeholders);
        }
        return prefixComponent().append(component(path, placeholders));
    }

    public List<Component> componentList(String path) {
        return componentList(path, Map.of());
    }

    /** A list of message lines as Components. */
    public List<Component> componentList(String path, Map<String, String> placeholders) {
        List<Component> out = new ArrayList<>();
        for (String line : messages.getStringList(path)) {
            out.add(ColorFormats.parse(apply(line, placeholders)));
        }
        return out;
    }

    /**
     * A message whose placeholders are replaced with Components rather than raw text.
     *
     * <p>Two reasons this exists instead of string interpolation:
     *
     * <ul>
     *   <li>A replacement that is itself styled — a medal, for one — keeps its own
     *       color, hover and click, because it is spliced into the parsed tree
     *       instead of being flattened into the source string first.</li>
     *   <li>Substituting before parsing means a value containing MiniMessage tags
     *       is interpreted as markup. Player names reaching this from the database
     *       or from a non-vanilla client could then open a tag. Replacing after the
     *       parse makes that structurally impossible.</li>
     * </ul>
     *
     * @param replacements placeholder token (e.g. {@code "{medal}"}) to the Component it becomes
     */
    public Component componentWith(String path, Map<String, Component> replacements) {
        Component result = component(path);
        for (Map.Entry<String, Component> entry : replacements.entrySet()) {
            result = result.replaceText(builder -> builder
                    .matchLiteral(entry.getKey())
                    .replacement(entry.getValue()));
        }
        return result;
    }

    /**
     * Localized weekday name, or null when the language file has no entry.
     *
     * <p>Returns null rather than the raw key so the caller can fall back to a
     * readable English name instead of printing {@code days.SATURDAY}.
     */
    public String getDayName(String dayOfWeekName) {
        return messages.getString("days." + dayOfWeekName);
    }

    public Component medalComponent(int position) {
        List<String> medals = messages.getStringList("medals.positions");
        if (position >= 1 && position <= medals.size()) {
            return ColorFormats.parse(medals.get(position - 1));
        }
        String fallback = messages.getString("medals.fallback", "<#315a7a>{position}");
        return ColorFormats.parse(fallback.replace("{position}", String.valueOf(position)));
    }

    private String apply(String text, Map<String, String> placeholders) {
        if (text == null) return "";
        String result = text;
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            result = result.replace(entry.getKey(), entry.getValue());
        }
        return result;
    }

    private String[] flatten(Map<String, String> placeholders) {
        String[] pairs = new String[placeholders.size() * 2];
        int i = 0;
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            pairs[i++] = entry.getKey();
            pairs[i++] = entry.getValue();
        }
        return pairs;
    }
}
