package studio.polaroid.polaroiddragon.util;

import net.kyori.adventure.text.Component;

/**
 * Thin compatibility facade over {@link ColorFormats}.
 *
 * <p>Historically this class only understood {@code &} codes and {@code &#RRGGBB}. Every format is
 * now normalized to MiniMessage by {@link ColorFormats}, so MiniMessage tags written in the config
 * files (the Polaroid style) render as well as legacy codes still sitting in older configs.
 *
 * <p>{@link #parse(String)} keeps returning a legacy {@code §} string because existing call sites
 * feed it to {@code sendMessage(String)}, boss bar titles and entity custom names.
 * New code should prefer {@link #component(String)}.
 */
public final class ColorUtil {

    private ColorUtil() {}

    /** Any supported color format rendered down to a legacy {@code §} string. */
    public static String parse(String text) {
        if (text == null) return "";
        if (text.isEmpty()) return "";
        return ColorFormats.toLegacy(ColorFormats.parse(text));
    }

    /** Any supported color format rendered to an Adventure Component. Preferred for new code. */
    public static Component component(String text) {
        return ColorFormats.parse(text);
    }

    /** Any supported color format normalized to MiniMessage, without rendering. */
    public static String normalize(String text) {
        return ColorFormats.normalize(text);
    }
}
