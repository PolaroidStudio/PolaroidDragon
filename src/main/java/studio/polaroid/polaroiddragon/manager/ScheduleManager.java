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
    private ZonedDateTime nextEventTime;

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");

    // Traducción de DayOfWeek a español
    private static final Map<DayOfWeek, String> DAY_NAMES_ES = Map.of(
            DayOfWeek.MONDAY,    "Lunes",
            DayOfWeek.TUESDAY,   "Martes",
            DayOfWeek.WEDNESDAY, "Miércoles",
            DayOfWeek.THURSDAY,  "Jueves",
            DayOfWeek.FRIDAY,    "Viernes",
            DayOfWeek.SATURDAY,  "Sábado",
            DayOfWeek.SUNDAY,    "Domingo"
    );

    public ScheduleManager(PolaroidDragon plugin) {
        this.plugin = plugin;
        computeNextEvent();
    }

    /**
     * Calcula el próximo evento según la lista de horarios semanales.
     * Formato de cada entrada: "FRIDAY 20:00" (DayOfWeek HH:mm)
     */
    public void computeNextEvent() {
        ZoneId zone = safeZone();
        ZonedDateTime now = ZonedDateTime.now(zone);
        List<String> schedule = plugin.getConfig().getStringList("event.schedule");

        List<ZonedDateTime> candidates = new ArrayList<>();

        for (String entry : schedule) {
            ZonedDateTime candidate = parseEntry(now, entry);
            if (candidate == null) continue;
            // Si ya pasó esta semana, sumar 7 días
            if (!candidate.isAfter(now)) candidate = candidate.plusWeeks(1);
            candidates.add(candidate);
        }

        if (candidates.isEmpty()) {
            nextEventTime = null;
            plugin.getLogger().warning("No hay horarios válidos en event.schedule. Formato: FRIDAY 20:00");
            return;
        }

        nextEventTime = candidates.stream().min(ZonedDateTime::compareTo).orElse(null);
    }

    /**
     * Parsea una entrada "FRIDAY 20:00" y devuelve el ZonedDateTime de esa ocurrencia
     * en la semana actual (puede estar en el pasado; el llamador suma 7 días si hace falta).
     */
    private ZonedDateTime parseEntry(ZonedDateTime base, String entry) {
        try {
            String[] parts = entry.trim().split("\\s+");
            if (parts.length != 2) throw new IllegalArgumentException("Formato inválido");

            DayOfWeek day = DayOfWeek.valueOf(parts[0].toUpperCase());
            String[] timeParts = parts[1].split(":");
            int hour   = Integer.parseInt(timeParts[0]);
            int minute = Integer.parseInt(timeParts[1]);

            // Ajustar al día de la semana correcto dentro de la semana actual
            ZonedDateTime result = base.with(java.time.temporal.TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                    .with(day)
                    .withHour(hour).withMinute(minute).withSecond(0).withNano(0);

            return result;
        } catch (Exception e) {
            plugin.getLogger().warning("Horario inválido: '" + entry + "'. Formato correcto: FRIDAY 20:00");
            return null;
        }
    }

    // ─────────────────────────────────────────────
    //  GETTERS
    // ─────────────────────────────────────────────

    /** Segundos hasta el próximo evento. -1 si no hay horarios. */
    public long getSecondsUntilNext() {
        if (nextEventTime == null) return -1;
        long seconds = java.time.Duration.between(ZonedDateTime.now(safeZone()), nextEventTime).getSeconds();
        return Math.max(0, seconds);
    }

    /** Ticks hasta el próximo evento. */
    public long getTicksUntilNext() {
        return getSecondsUntilNext() * 20L;
    }

    /** Hora del próximo evento. Ej: "18:00" */
    public String getNextTimeString() {
        return nextEventTime != null ? nextEventTime.format(TIME_FMT) : "-";
    }

    /** Día del próximo evento en español. Ej: "Sábado" */
    public String getNextDayString() {
        if (nextEventTime == null) return "-";
        return DAY_NAMES_ES.getOrDefault(nextEventTime.getDayOfWeek(), nextEventTime.getDayOfWeek().name());
    }

    /** Día y hora del próximo evento. Ej: "Sábado 18:00" */
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
            plugin.getLogger().warning("Zona horaria inválida, usando Europe/Madrid.");
            return ZoneId.of("Europe/Madrid");
        }
    }
}
