package studio.polaroid.polaroiddragon.manager;

import studio.polaroid.polaroiddragon.PolaroidDragon;

import java.time.DayOfWeek;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class ScheduleManager {

    private final PolaroidDragon plugin;
    // Read from PlaceholderAPI, which runs off the main thread.
    private volatile ZonedDateTime nextEventTime;

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");

    /**
     * Fallback used only when a language file has no {@code days} section.
     * Day names are localized like every other user-facing string; hardcoding
     * Spanish here meant an English server showed "Sábado" in its countdown.
     */
    private static final Map<DayOfWeek, String> DAY_NAMES_FALLBACK = Map.of(
            DayOfWeek.MONDAY,    "Monday",
            DayOfWeek.TUESDAY,   "Tuesday",
            DayOfWeek.WEDNESDAY, "Wednesday",
            DayOfWeek.THURSDAY,  "Thursday",
            DayOfWeek.FRIDAY,    "Friday",
            DayOfWeek.SATURDAY,  "Saturday",
            DayOfWeek.SUNDAY,    "Sunday"
    );

    public ScheduleManager(PolaroidDragon plugin) {
        this.plugin = plugin;
        computeNextEvent();
    }

    /**
     * Computes the next event from the weekly schedule list.
     * Format of each entry: "FRIDAY 20:00" (DayOfWeek HH:mm)
     */
    public void computeNextEvent() {
        ZoneId zone = safeZone();
        ZonedDateTime now = ZonedDateTime.now(zone);
        List<String> schedule = plugin.getConfig().getStringList("event.schedule");

        List<ZonedDateTime> candidates = new ArrayList<>();

        for (String entry : schedule) {
            ZonedDateTime candidate = parseEntry(now, entry);
            if (candidate == null) continue;
            // Already past this week, so add 7 days
            if (!candidate.isAfter(now)) candidate = candidate.plusWeeks(1);
            candidates.add(candidate);
        }

        if (candidates.isEmpty()) {
            nextEventTime = null;
            plugin.getLogger().warning("No valid entries in event.schedule. Expected format: FRIDAY 20:00");
            return;
        }

        nextEventTime = candidates.stream().min(ZonedDateTime::compareTo).orElse(null);
    }

    /**
     * Parses a "FRIDAY 20:00" entry and returns the ZonedDateTime of that occurrence
     * in the current week (it may be in the past; the caller adds 7 days when needed).
     */
    private ZonedDateTime parseEntry(ZonedDateTime base, String entry) {
        ScheduleEntryParser.Result result = ScheduleEntryParser.parse(base, entry);
        if (result.isValid()) return result.value();

        // Name the actual problem. A single generic "invalid format" line left
        // the operator guessing between a bad day, a bad time and a bad range.
        plugin.getLogger().warning("Invalid schedule entry '" + entry + "': " + result.error()
                + ". Expected format: FRIDAY 20:00");
        return null;
    }

    // ─────────────────────────────────────────────
    //  GETTERS
    // ─────────────────────────────────────────────

    /** Seconds until the next event. -1 when no schedule is configured. */
    public long getSecondsUntilNext() {
        if (nextEventTime == null) return -1;
        long seconds = java.time.Duration.between(ZonedDateTime.now(safeZone()), nextEventTime).getSeconds();
        return Math.max(0, seconds);
    }

    /** Ticks until the next event. */
    public long getTicksUntilNext() {
        return getSecondsUntilNext() * 20L;
    }

    /** Time of the next event. E.g. "18:00" */
    public String getNextTimeString() {
        return nextEventTime != null ? nextEventTime.format(TIME_FMT) : "-";
    }

    /** Localized day of the next event. E.g. "Saturday". */
    public String getNextDayString() {
        if (nextEventTime == null) return "-";
        DayOfWeek day = nextEventTime.getDayOfWeek();

        String localized = plugin.getMessageManager().getDayName(day.name());
        if (localized != null && !localized.isBlank()) return localized;

        return DAY_NAMES_FALLBACK.getOrDefault(day, day.name());
    }

    /** Day and time of the next event. E.g. "Saturday 18:00" */
    public String getNextFullString() {
        if (nextEventTime == null) return "-";
        return getNextDayString() + " " + getNextTimeString();
    }

    public boolean hasNextEvent() {
        return nextEventTime != null;
    }

    private ZoneId safeZone() {
        try {
            return ZoneId.of(plugin.getConfig().getString("event.timezone", "Europe/Madrid"));
        } catch (Exception e) {
            plugin.getLogger().warning("Invalid event.timezone; falling back to Europe/Madrid.");
            return ZoneId.of("Europe/Madrid");
        }
    }
}
