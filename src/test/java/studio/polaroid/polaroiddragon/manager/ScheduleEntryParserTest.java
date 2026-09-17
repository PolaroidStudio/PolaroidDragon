package studio.polaroid.polaroiddragon.manager;

import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The schedule parser decides when every event fires, and a rejected entry
 * fails silently — it simply never spawns a dragon. These cases pin both the
 * accepted format and each rejection reason.
 */
class ScheduleEntryParserTest {

    private static final ZoneId MADRID = ZoneId.of("Europe/Madrid");

    /** Wednesday 2025-06-11, 12:00 — an ordinary mid-week, mid-day instant. */
    private static final ZonedDateTime BASE =
            ZonedDateTime.of(2025, 6, 11, 12, 0, 0, 0, MADRID);

    @Test
    void parsesADayAndTimeInTheSameWeek() {
        ScheduleEntryParser.Result result = ScheduleEntryParser.parse(BASE, "FRIDAY 20:00");

        assertTrue(result.isValid(), result.error());
        ZonedDateTime value = result.value();
        assertEquals(DayOfWeek.FRIDAY, value.getDayOfWeek());
        assertEquals(20, value.getHour());
        assertEquals(0, value.getMinute());
        assertEquals(MADRID, value.getZone());
    }

    @Test
    void resolvesADayEarlierInTheWeekInThePast() {
        // Monday is before the Wednesday base; the caller advances it by a week.
        ScheduleEntryParser.Result result = ScheduleEntryParser.parse(BASE, "MONDAY 09:00");

        assertTrue(result.isValid(), result.error());
        assertEquals(DayOfWeek.MONDAY, result.value().getDayOfWeek());
        assertTrue(result.value().isBefore(BASE));
    }

    @Test
    void secondsAndNanosAreZeroed() {
        ZonedDateTime noisyBase = BASE.withSecond(37).withNano(123_456_789);

        ScheduleEntryParser.Result result = ScheduleEntryParser.parse(noisyBase, "FRIDAY 20:00");

        assertEquals(0, result.value().getSecond());
        assertEquals(0, result.value().getNano());
    }

    @Test
    void isCaseInsensitiveAndToleratesExtraWhitespace() {
        ScheduleEntryParser.Result result = ScheduleEntryParser.parse(BASE, "  friday   20:00  ");

        assertTrue(result.isValid(), result.error());
        assertEquals(DayOfWeek.FRIDAY, result.value().getDayOfWeek());
        assertEquals(20, result.value().getHour());
    }

    @Test
    void acceptsEveryDayOfTheWeek() {
        for (DayOfWeek day : DayOfWeek.values()) {
            ScheduleEntryParser.Result result =
                    ScheduleEntryParser.parse(BASE, day.name() + " 18:30");

            assertTrue(result.isValid(), day + " should parse: " + result.error());
            assertEquals(day, result.value().getDayOfWeek());
        }
    }

    /**
     * Spain springs forward at 02:00 on 2025-03-30, so 02:30 does not exist.
     * java.time shifts a gap time forward by the gap length, giving 03:30 —
     * a defined resolution, and the same one either construction produces.
     * This pins the date and that the instant is real.
     */
    @Test
    void resolvesATimeInsideADstSpringForwardGap() {
        ZonedDateTime beforeTransition =
                ZonedDateTime.of(2025, 3, 24, 12, 0, 0, 0, MADRID);

        ScheduleEntryParser.Result result =
                ScheduleEntryParser.parse(beforeTransition, "SUNDAY 02:30");

        assertTrue(result.isValid(), result.error());
        ZonedDateTime value = result.value();

        assertEquals(DayOfWeek.SUNDAY, value.getDayOfWeek());
        assertEquals(java.time.LocalDate.of(2025, 3, 30), value.toLocalDate());
        assertEquals(30, value.getMinute());
        assertEquals(value.toInstant(),
                value.toLocalDateTime().atZone(MADRID).toInstant(),
                "the produced instant must round-trip through its own zone");
    }

    /**
     * Spain falls back at 03:00 on 2025-10-26, so 02:30 happens twice: once at
     * +02:00 and again at +01:00.
     *
     * <p>This is the case that actually discriminates the two constructions.
     * Building the time with withHour() on a ZonedDateTime carried the offset
     * in effect at the base instant and resolved to the SECOND occurrence
     * (+01:00), firing the event an hour later than the earlier reading of the
     * configured wall clock. Resolving a LocalDateTime through the zone picks
     * the first occurrence, which is java.time's documented rule for an
     * overlap.
     */
    @Test
    void picksTheFirstOccurrenceOfAnAmbiguousDstFallBackTime() {
        ZonedDateTime beforeTransition =
                ZonedDateTime.of(2025, 10, 20, 12, 0, 0, 0, MADRID);

        ScheduleEntryParser.Result result =
                ScheduleEntryParser.parse(beforeTransition, "SUNDAY 02:30");

        assertTrue(result.isValid(), result.error());
        ZonedDateTime value = result.value();

        assertEquals(java.time.LocalDate.of(2025, 10, 26), value.toLocalDate());
        assertEquals(2, value.getHour());
        assertEquals(30, value.getMinute());
        assertEquals(java.time.ZoneOffset.ofHours(2), value.getOffset(),
                "an ambiguous local time must resolve to its first occurrence");
    }

    @Test
    void honoursTheZoneOfTheBaseInstant() {
        ZoneId tokyo = ZoneId.of("Asia/Tokyo");
        ZonedDateTime tokyoBase = ZonedDateTime.of(2025, 6, 11, 12, 0, 0, 0, tokyo);

        ScheduleEntryParser.Result result = ScheduleEntryParser.parse(tokyoBase, "FRIDAY 20:00");

        assertEquals(tokyo, result.value().getZone());
        assertEquals(20, result.value().getHour());
    }

    @Test
    void rejectsAnUnknownDay() {
        ScheduleEntryParser.Result result = ScheduleEntryParser.parse(BASE, "FUNDAY 20:00");

        assertFalse(result.isValid());
        assertTrue(result.error().contains("FUNDAY"), result.error());
    }

    @Test
    void rejectsAMissingTimeComponent() {
        // "FRIDAY 20" used to throw ArrayIndexOutOfBounds into a generic catch,
        // so the operator was told the format was wrong but not which part.
        ScheduleEntryParser.Result result = ScheduleEntryParser.parse(BASE, "FRIDAY 20");

        assertFalse(result.isValid());
        assertTrue(result.error().contains("HH:mm"), result.error());
    }

    @Test
    void rejectsAnHourOutOfRange() {
        ScheduleEntryParser.Result result = ScheduleEntryParser.parse(BASE, "FRIDAY 25:00");

        assertFalse(result.isValid());
        assertTrue(result.error().contains("0-23"), result.error());
    }

    @Test
    void rejectsAMinuteOutOfRange() {
        ScheduleEntryParser.Result result = ScheduleEntryParser.parse(BASE, "FRIDAY 20:75");

        assertFalse(result.isValid());
        assertTrue(result.error().contains("0-59"), result.error());
    }

    @Test
    void rejectsNonNumericTime() {
        ScheduleEntryParser.Result result = ScheduleEntryParser.parse(BASE, "FRIDAY ab:cd");

        assertFalse(result.isValid());
        assertTrue(result.error().contains("non-numeric"), result.error());
    }

    @Test
    void rejectsTooManyParts() {
        ScheduleEntryParser.Result result = ScheduleEntryParser.parse(BASE, "FRIDAY 20:00 UTC");

        assertFalse(result.isValid());
        assertTrue(result.error().contains("two parts"), result.error());
    }

    @Test
    void rejectsEmptyAndNullEntries() {
        assertFalse(ScheduleEntryParser.parse(BASE, "").isValid());
        assertFalse(ScheduleEntryParser.parse(BASE, "   ").isValid());
        assertFalse(ScheduleEntryParser.parse(BASE, null).isValid());
    }
}
