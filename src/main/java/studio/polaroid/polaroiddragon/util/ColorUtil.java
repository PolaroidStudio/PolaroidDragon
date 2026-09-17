package studio.polaroid.polaroiddragon.util;

import net.kyori.adventure.text.Component;

/**
 * Thin facade over {@link ColorFormats} for raw config strings.
 *
 * <p>Use this for values read straight from a config file — a boss bar title, an
 * entity display name, a menu window title. Localized messages do not come
 * through here: they are built as Components by
 * {@link studio.polaroid.polaroiddragon.manager.MessageManager}.
 *
 * <p>There is deliberately no legacy {@code §}-string variant. Rendering a
 * Component down to legacy and reparsing it drops hover and click events and
 * flattens gradients, which is exactly what {@code LegacyRoundTripTest} pins.
 * Everything user-facing stays a Component end to end.
 */
public final class ColorUtil {

    private ColorUtil() {}

    /** Any supported color format rendered to an Adventure Component. */
    public static Component component(String text) {
        return ColorFormats.parse(text);
    }
}
