package studio.polaroid.polaroiddragon.util;

import io.papermc.paper.ServerBuildInfo;
import org.bukkit.Bukkit;

/**
 * Startup gate for the platform the plugin is running on.
 *
 * <p>The plugin targets Java 21 and Minecraft 1.21. On an older platform it does
 * not merely misbehave, it dies deep inside an API call with a
 * {@code NoSuchMethodError} or an {@code UnsupportedClassVersionError} — stack
 * traces that name a Paper internal and tell the server owner nothing about what
 * to fix. Checking first turns that into one actionable line.
 *
 * <p><b>Fail open is the governing rule.</b> The plugin is only ever disabled
 * when a version was understood <em>and</em> is genuinely too old. An unreadable
 * string, a missing API, or a versioning scheme nobody has invented yet all
 * allow startup with a warning. The alternative is far worse: a parser written
 * today would brick a working server on a future Minecraft release simply
 * because it did not recognise the format, and a plugin that refuses to load is
 * a harder outage to diagnose than one that logs a warning and works.
 *
 * <p>Two Minecraft version schemes exist. The classic {@code 1.21.4} line, and
 * the {@code year.drop.patch} scheme Mojang introduced in 2026 ({@code 26.3}).
 * They are told apart by their first component: {@code 1} means classic, a
 * two-digit year of 26 or later means the new scheme, which is by construction
 * newer than any {@code 1.x} release and therefore always accepted.
 *
 * <p>All version logic lives here as pure static methods over strings so it can
 * be tested without a running server; only {@link #detectMinecraftVersion()}
 * touches Bukkit.
 */
public final class CompatibilityCheck {

    /** Minimum supported Java feature release. */
    public static final int MINIMUM_JAVA = 21;

    /** Minimum supported Minecraft version, in the classic scheme. */
    public static final int[] MINIMUM_MINECRAFT = {1, 21};

    /**
     * First year of the {@code year.drop.patch} scheme. A leading component this
     * large cannot be a classic major version, so it disambiguates the two.
     */
    private static final int FIRST_NEW_SCHEME_YEAR = 26;

    private CompatibilityCheck() {}

    /** Either a satisfied requirement or the reason it was not met. */
    public record Result(boolean supported, String reason) {

        static Result ok() { return new Result(true, null); }

        static Result unsupported(String reason) { return new Result(false, reason); }
    }

    /**
     * Checks the running JVM against {@link #MINIMUM_JAVA}.
     *
     * <p>Reads {@code Runtime.version().feature()} rather than splitting
     * {@code java.version}: that property has changed shape several times
     * (1.8.0_351, 11.0.2, 21-ea) and string parsing it is how plugins end up
     * rejecting valid JVMs.
     */
    public static Result checkJava() {
        return checkJava(Runtime.version().feature());
    }

    /** Pure form of {@link #checkJava()}, taking the feature release directly. */
    public static Result checkJava(int feature) {
        if (feature >= MINIMUM_JAVA) {
            return Result.ok();
        }
        return Result.unsupported("Java " + feature + " is running, but Java "
                + MINIMUM_JAVA + " or newer is required");
    }

    /**
     * Checks a Minecraft version string against {@link #MINIMUM_MINECRAFT}.
     *
     * <p>Returns supported for anything it cannot parse, per the fail-open rule
     * in the class javadoc.
     */
    public static Result checkMinecraft(String version) {
        int[] parsed = parseVersion(version);
        if (parsed == null) {
            // Unknown shape. Say so and get out of the way.
            return Result.ok();
        }

        // New scheme: the year alone already exceeds every 1.x release.
        if (parsed[0] >= FIRST_NEW_SCHEME_YEAR) {
            return Result.ok();
        }

        if (compare(parsed, MINIMUM_MINECRAFT) >= 0) {
            return Result.ok();
        }

        return Result.unsupported("Minecraft " + version.trim() + " is running, but "
                + format(MINIMUM_MINECRAFT) + " or newer is required");
    }

    /**
     * Splits a version string into its numeric components, or returns
     * {@code null} when it is not a version this code understands.
     *
     * <p>A trailing qualifier such as {@code -pre1}, {@code -rc1} or a snapshot
     * suffix is dropped: a pre-release of 1.21 is close enough to 1.21 for a
     * compatibility gate, and treating it as unparseable would only reach the
     * same fail-open answer by a noisier route. Components that are not numeric
     * end the parse rather than failing it, so {@code 1.21.1+build.7} still
     * reads as {@code 1.21.1}.
     */
    static int[] parseVersion(String version) {
        if (version == null || version.isBlank()) {
            return null;
        }

        String trimmed = version.trim();

        // Cut everything from the first qualifier separator onwards.
        int cut = trimmed.length();
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (c == '-' || c == '+' || c == '_' || c == ' ') {
                cut = i;
                break;
            }
        }
        trimmed = trimmed.substring(0, cut);
        if (trimmed.isEmpty()) {
            return null;
        }

        String[] parts = trimmed.split("\\.");
        int[] components = new int[parts.length];
        int count = 0;
        for (String part : parts) {
            if (part.isEmpty()) {
                break;
            }
            int value;
            try {
                value = Integer.parseInt(part);
            } catch (NumberFormatException e) {
                break;
            }
            if (value < 0) {
                break;
            }
            components[count++] = value;
        }

        if (count == 0) {
            return null;
        }

        int[] trimmedComponents = new int[count];
        System.arraycopy(components, 0, trimmedComponents, 0, count);
        return trimmedComponents;
    }

    /**
     * Orders two component arrays numerically, shortest padded with zeros.
     *
     * <p>Component-wise integer comparison is the whole point: {@code 1.21.11}
     * is newer than {@code 1.21.4}, which neither string ordering nor reading
     * the version as a decimal gets right.
     */
    static int compare(int[] left, int[] right) {
        int length = Math.max(left.length, right.length);
        for (int i = 0; i < length; i++) {
            int a = i < left.length ? left[i] : 0;
            int b = i < right.length ? right[i] : 0;
            if (a != b) {
                return Integer.compare(a, b);
            }
        }
        return 0;
    }

    private static String format(int[] components) {
        StringBuilder sb = new StringBuilder();
        for (int component : components) {
            if (!sb.isEmpty()) sb.append('.');
            sb.append(component);
        }
        return sb.toString();
    }

    /**
     * Reads the running Minecraft version, or {@code null} when no source could
     * supply one.
     *
     * <p>{@code ServerBuildInfo} is the accurate source but it is Paper-specific
     * and has moved between Paper releases, so a fork or an unexpected build can
     * throw {@link NoClassDefFoundError} or {@link NoSuchMethodError} rather
     * than a plain exception — hence catching {@link Throwable}. Two fallbacks
     * follow, and a null return simply feeds the fail-open path.
     */
    public static String detectMinecraftVersion() {
        try {
            return ServerBuildInfo.buildInfo().minecraftVersionId();
        } catch (Throwable ignored) {
            // Not Paper, or a build whose ServerBuildInfo does not match ours.
        }

        try {
            return Bukkit.getMinecraftVersion();
        } catch (Throwable ignored) {
            // Older API surface without this accessor.
        }

        try {
            // "1.21.1-R0.1-SNAPSHOT" — the qualifier is stripped while parsing.
            return Bukkit.getBukkitVersion();
        } catch (Throwable ignored) {
            return null;
        }
    }
}
