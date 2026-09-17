package studio.polaroid.polaroiddragon.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;

import java.util.regex.Pattern;

/**
 * Normalizes every supported Minecraft color/style format to MiniMessage, then renders it.
 * Supported: &f/§f legacy codes and styles, &#RRGGBB, §x§R§G§B§R§G§B legacy hex, #RRGGBB,
 * &lt;#RRGGBB&gt; and native MiniMessage tags (passed through unchanged).
 */
public final class ColorFormats {

    // Single shared MiniMessage instance. Swap it at startup via init(...) if the plugin ever
    // registers custom tag resolvers (Nexo, PlaceholderAPI tags, ...).
    private static MiniMessage MM = MiniMessage.miniMessage();

    public static void init(MiniMessage instance) { MM = instance; }

    public static MiniMessage mm() { return MM; }

    // §x§R§G§B§R§G§B — legacy section hex
    private static final Pattern SECTION_HEX = Pattern.compile("§x(§[0-9a-fA-F]){6}");
    // §f — simple section code
    private static final Pattern SECTION_CODE = Pattern.compile("§([0-9a-fk-orA-FK-OR])");
    // &#RRGGBB — ampersand hex
    private static final Pattern AMP_HEX = Pattern.compile("&#([0-9a-fA-F]{6})");
    // &f — ampersand code
    private static final Pattern AMP_CODE = Pattern.compile("&([0-9a-fk-orA-FK-OR])");
    // #RRGGBB without delimiters — excludes those preceded by &, §, <, # or :
    private static final Pattern BARE_HEX = Pattern.compile("(?<![&§<#:])#([0-9a-fA-F]{6})(?![>0-9a-fA-F])");
    private ColorFormats() {}

    /** Any color format to its MiniMessage equivalent. Already-valid MiniMessage is returned unchanged. */
    public static String normalize(String input) {
        if (input == null || input.isBlank()) return input;
        return toLegacyToMM(input);
    }

    /** Parses text with colors in any supported format into a Component. */
    public static Component parse(String text) {
        if (text == null || text.isBlank()) return Component.empty();
        return MM.deserialize(normalize(text));
    }

    /** Converts §-codes / &amp;-codes / bare hex to MiniMessage tags; leaves existing tags intact. */
    public static String toLegacyToMM(String text) {
        if (text == null) return "";
        // Fast-path: no legacy/hex markers, nothing to convert. Critical for per-message cost.
        if (!text.contains("§") && !text.contains("&") && !text.contains("#")) return text;

        text = SECTION_HEX.matcher(text).replaceAll(m -> {
            String digits = m.group().replace("§x", "").replace("§", "");
            return "<#" + digits + ">";
        });
        text = SECTION_CODE.matcher(text).replaceAll(m -> legacyCodeToTag(m.group(1).charAt(0)));
        text = AMP_HEX.matcher(text).replaceAll("<#$1>");
        text = AMP_CODE.matcher(text).replaceAll(m -> legacyCodeToTag(m.group(1).charAt(0)));
        text = BARE_HEX.matcher(text).replaceAll("<#$1>");
        return text;
    }

    private static String legacyCodeToTag(char code) {
        return switch (Character.toLowerCase(code)) {
            case '0' -> "<black>";
            case '1' -> "<dark_blue>";
            case '2' -> "<dark_green>";
            case '3' -> "<dark_aqua>";
            case '4' -> "<dark_red>";
            case '5' -> "<dark_purple>";
            case '6' -> "<gold>";
            case '7' -> "<gray>";
            case '8' -> "<dark_gray>";
            case '9' -> "<blue>";
            case 'a' -> "<green>";
            case 'b' -> "<aqua>";
            case 'c' -> "<red>";
            case 'd' -> "<light_purple>";
            case 'e' -> "<yellow>";
            case 'f' -> "<white>";
            case 'l' -> "<bold>";
            case 'o' -> "<italic>";
            case 'n' -> "<underlined>";
            case 'm' -> "<strikethrough>";
            case 'k' -> "<obfuscated>";
            case 'r' -> "<reset>";
            default  -> "&" + code;
        };
    }
}
