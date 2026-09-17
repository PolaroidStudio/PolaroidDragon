package studio.polaroid.polaroiddragon.util;

public class TimeUtil {

    public static String format(long totalSeconds) {
        if (totalSeconds <= 0) return "0s";

        long days    = totalSeconds / 86400;
        long hours   = (totalSeconds % 86400) / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;

        StringBuilder sb = new StringBuilder();
        if (days    > 0) sb.append(days).append("d ");
        if (hours   > 0) sb.append(hours).append("h ");
        if (minutes > 0) sb.append(minutes).append("m ");
        if (seconds > 0 || sb.isEmpty()) sb.append(seconds).append("s");

        return sb.toString().trim();
    }

    /** Same as format() but without showing the seconds. Useful for holograms and menus. */
    public static String formatShort(long totalSeconds) {
        if (totalSeconds <= 0) return "0m";

        long days    = totalSeconds / 86400;
        long hours   = (totalSeconds % 86400) / 3600;
        long minutes = (totalSeconds % 3600) / 60;

        StringBuilder sb = new StringBuilder();
        if (days    > 0) sb.append(days).append("d ");
        if (hours   > 0) sb.append(hours).append("h ");
        if (minutes > 0 || sb.isEmpty()) sb.append(minutes).append("m");

        return sb.toString().trim();
    }
}
