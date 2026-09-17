package studio.polaroid.polaroiddragon.placeholder;

import studio.polaroid.polaroiddragon.manager.DamageTracker;
import studio.polaroid.polaroiddragon.manager.DragonManager;
import studio.polaroid.polaroiddragon.manager.StatsManager;
import studio.polaroid.polaroiddragon.util.TimeUtil;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.entity.EnderDragon;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public class DragonPlaceholder extends PlaceholderExpansion {

    private final DragonManager dragonManager;
    private final StatsManager statsManager;

    public DragonPlaceholder(DragonManager dragonManager, StatsManager statsManager) {
        this.dragonManager = dragonManager;
        this.statsManager  = statsManager;
    }

    @Override public @NotNull String getIdentifier() { return "polaroiddragon"; }
    @Override public @NotNull String getAuthor()     { return "PolaroidStudio"; }
    @Override public @NotNull String getVersion()    { return "1.0.0"; }
    @Override public boolean persist()               { return true; }

    @Override
    public String onPlaceholderRequest(Player player, @NotNull String identifier) {

        DamageTracker tracker = dragonManager.getDamageTracker();

        switch (identifier) {

            // ── CURRENT EVENT ──────────────────────────
            case "active":
                return dragonManager.isEventActive() ? "true" : "false";

            case "countdown":
                return TimeUtil.format(dragonManager.getCountdownSeconds());

            // PlaceholderAPI is routinely evaluated off the main thread by
            // scoreboard and tab plugins, so these read the cached snapshot the
            // boss bar task publishes. Touching the live entity here meant
            // iterating the world entity list from an async thread.
            case "hp":
                return dragonManager.isEventActive()
                        ? String.format("%.0f", dragonManager.getCachedHealth()) : "0";

            case "hp_max":
                return dragonManager.isEventActive()
                        ? String.format("%.0f", dragonManager.getCachedMaxHealth()) : "0";
            case "participants":
                return String.valueOf(tracker.getParticipantCount());

            // ── NEXT EVENT ─────────────────────────
            case "next":
                return TimeUtil.format(dragonManager.getScheduleManager().getSecondsUntilNext());
            case "next_short":
                return TimeUtil.formatShort(dragonManager.getScheduleManager().getSecondsUntilNext());
            case "next_time":
                return dragonManager.getScheduleManager().getNextTimeString();
            case "next_day":
                return dragonManager.getScheduleManager().getNextDayString();
            case "next_full":
                return dragonManager.getScheduleManager().getNextFullString();

            // ── PLAYER — CURRENT EVENT ────────────────
            case "my_damage":
                if (player == null) return "0";
                return String.format("%,.0f", tracker.getDamage(player.getUniqueId()));

            case "my_rank": {
                if (player == null) return "-";
                List<Map.Entry<UUID, Double>> ranking = tracker.getRanking();
                for (int i = 0; i < ranking.size(); i++) {
                    if (ranking.get(i).getKey().equals(player.getUniqueId()))
                        return String.valueOf(i + 1);
                }
                return "-";
            }

            // ── PLAYER — ALL-TIME ────────────────────
            case "my_total_damage": {
                if (player == null) return "0";
                StatsManager.PlayerStats ps = statsManager.getPlayerStats(player.getUniqueId());
                return ps != null ? String.format("%,.0f", ps.getTotalDamage()) : "0";
            }
            case "my_events": {
                if (player == null) return "0";
                StatsManager.PlayerStats ps = statsManager.getPlayerStats(player.getUniqueId());
                return ps != null ? String.valueOf(ps.getEventsParticipated()) : "0";
            }

            default: {
                // ── CURRENT EVENT TOP: top1_name, top1_damage … top5 ──
                for (int i = 1; i <= 5; i++) {
                    if (identifier.equals("top" + i + "_name"))
                        return getTopName(tracker, i);
                    if (identifier.equals("top" + i + "_damage"))
                        return getTopDamage(tracker, i);
                }
                // ── HALL OF FAME: hall_1_name, hall_1_damage, hall_1_events … hall_5 ──
                for (int i = 1; i <= 5; i++) {
                    if (identifier.equals("hall_" + i + "_name"))   return getHallName(i);
                    if (identifier.equals("hall_" + i + "_damage"))  return getHallDamage(i);
                    if (identifier.equals("hall_" + i + "_events"))  return getHallEvents(i);
                }
                return null;
            }
        }
    }

    private String getTopName(DamageTracker t, int pos) {
        List<Map.Entry<UUID, Double>> r = t.getRanking();
        return pos <= r.size() ? t.getPlayerName(r.get(pos - 1).getKey()) : "-";
    }
    private String getTopDamage(DamageTracker t, int pos) {
        List<Map.Entry<UUID, Double>> r = t.getRanking();
        return pos <= r.size() ? String.format("%,.0f", r.get(pos - 1).getValue()) : "0";
    }
    private String getHallName(int pos) {
        List<StatsManager.PlayerStats> top = statsManager.getTopByDamage(pos);
        return top.size() >= pos ? top.get(pos - 1).getName() : "-";
    }
    private String getHallDamage(int pos) {
        List<StatsManager.PlayerStats> top = statsManager.getTopByDamage(pos);
        return top.size() >= pos ? String.format("%,.0f", top.get(pos - 1).getTotalDamage()) : "0";
    }
    private String getHallEvents(int pos) {
        List<StatsManager.PlayerStats> top = statsManager.getTopByDamage(pos);
        return top.size() >= pos ? String.valueOf(top.get(pos - 1).getEventsParticipated()) : "0";
    }
}
