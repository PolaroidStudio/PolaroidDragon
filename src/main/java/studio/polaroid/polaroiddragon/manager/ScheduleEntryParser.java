package studio.polaroid.polaroiddragon.manager;

import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.Locale;

/**
 * Parses a single {@code "FRIDAY 20:00"} schedule entry.
 *
 * <p>Split out of {@link ScheduleManager} so the date logic can be tested
 * without a running server: this is the densest logic in the plugin and its
 * failures are silent, because an unparseable entry simply never fires.
 */
public final class ScheduleEntryParser {

    private ScheduleEntryParser() {}

    /** Either a parsed occurrence or the reason the entry was rejected. */
    public record Result(ZonedDateTime value, String error) {
        public boolean isValid() { return value != null; }

        static Result ok(ZonedDateTime value) { return new Result(value, null); }
        static Result fail(String error) { return new Result(null, error); }
    }

    /**
     * Resolves the entry to its occurrence in the week containing {@code base}.
     *
     * <p>The result may be in the past; the caller advances it by a week.
     */
    public static Result parse(ZonedDateTime base, String entry) {
        if (entry == null || entry.isBlank()) {
            return Result.fail("the entry is empty");
        }

        String[] parts = entry.trim().split("\\s+");
        if (parts.length != 2) {
            return Result.fail("expected two parts, a day and a time");
        }

        DayOfWeek day;
        try {
            day = DayOfWeek.valueOf(parts[0].toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return Result.fail("'" + parts[0] + "' is not a day of the week (MONDAY ... SUNDAY)");
        }

        String[] timeParts = parts[1].split(":");
        if (timeParts.length != 2) {
            return Result.fail("'" + parts[1] + "' is not a HH:mm time");
        }

        int hour;
        int minute;
        try {
            hour = Integer.parseInt(timeParts[0]);
            minute = Integer.parseInt(timeParts[1]);
        } catch (NumberFormatException e) {
            return Result.fail("'" + parts[1] + "' has a non-numeric hour or minute");
        }

        LocalTime time;
        try {
            time = LocalTime.of(hour, minute);
        } catch (DateTimeException e) {
            return Result.fail("hour must be 0-23 and minute 0-59, got " + hour + ":" + minute);
        }

        // Build the local date first, then resolve the zone once.
        //
        // Calling withHour() on a ZonedDateTime carries the offset in effect at
        // the BASE instant, so a wall-clock time that occurs twice on a DST
        // fall-back day resolved to the second occurrence and the event fired an
        // hour late. Resolving a LocalDateTime through the zone applies
        // java.time's overlap rule instead, which picks the first occurrence.
        // (A spring-forward gap resolves identically either way.)
        LocalDate weekStart = base.toLocalDate()
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate targetDate = weekStart.with(TemporalAdjusters.nextOrSame(day));

        return Result.ok(LocalDateTime.of(targetDate, time).atZone(base.getZone()));
    }
}
