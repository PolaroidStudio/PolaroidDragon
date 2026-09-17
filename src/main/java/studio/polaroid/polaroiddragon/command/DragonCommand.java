package studio.polaroid.polaroiddragon.command;

import studio.polaroid.polaroiddragon.PolaroidDragon;
import studio.polaroid.polaroiddragon.gui.DragonMenu;
import studio.polaroid.polaroiddragon.gui.DragonMenuHolder;
import studio.polaroid.polaroiddragon.manager.DamageTracker;
import studio.polaroid.polaroiddragon.manager.DragonManager;
import studio.polaroid.polaroiddragon.manager.MessageManager;
import studio.polaroid.polaroiddragon.manager.StatsManager;
import studio.polaroid.polaroiddragon.util.TimeUtil;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.EnderDragon;
import org.bukkit.entity.Player;
import org.bukkit.util.StringUtil;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public class DragonCommand implements CommandExecutor, TabCompleter {

    /** Base node for the informational subcommands. Declared default: true. */
    private static final String USE_PERMISSION = "polaroiddragon.use";

    /**
     * Subcommand argument selecting the current event ranking instead of the
     * Hall of Fame. Both spellings are accepted: the plugin ships an English
     * locale, so an English operator must not have to type a Spanish word.
     */
    private static final List<String> EVENT_SCOPE_ALIASES = List.of("event", "evento");

    private final PolaroidDragon plugin;
    private final DragonManager dragonManager;
    private final StatsManager statsManager;
    private final MessageManager messages;
    private final DragonMenu dragonMenu;

    public DragonCommand(PolaroidDragon plugin, DragonManager dragonManager, StatsManager statsManager, DragonMenu dragonMenu) {
        this.plugin = plugin;
        this.dragonManager = dragonManager;
        this.statsManager = statsManager;
        this.messages = plugin.getMessageManager();
        this.dragonMenu = dragonMenu;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {

        if (args.length == 0) { sendHelp(sender); return true; }

        // Locale.ROOT: under a Turkish locale "INFO".toLowerCase() yields "ınfo"
        // and every subcommand silently stops matching.
        switch (args[0].toLowerCase(Locale.ROOT)) {

            case "start" -> {
                if (!sender.hasPermission("polaroiddragon.admin.start")) { noPerms(sender); return true; }
                if (dragonManager.isAnyEventRunning()) {
                    sender.sendMessage(messages.get(dragonManager.isEventActive()
                            ? "event.already-active"
                            : "event.countdown-in-progress"));
                    return true;
                }
                dragonManager.startCountdown();
                sender.sendMessage(messages.get("event.countdown-started"));
            }

            case "spawn" -> {
                if (!sender.hasPermission("polaroiddragon.admin.spawn")) { noPerms(sender); return true; }
                if (dragonManager.isAnyEventRunning()) {
                    sender.sendMessage(messages.get(dragonManager.isEventActive()
                            ? "event.already-active"
                            : "event.countdown-in-progress"));
                    return true;
                }
                dragonManager.spawnDragon();
                sender.sendMessage(messages.get("event.dragon-spawned"));
            }

            case "stop" -> {
                if (!sender.hasPermission("polaroiddragon.admin.stop")) { noPerms(sender); return true; }
                if (!dragonManager.isAnyEventRunning()) {
                    sender.sendMessage(messages.get("event.no-event-or-countdown")); return true;
                }
                dragonManager.stopEvent();
                sender.sendMessage(messages.get("event.event-stopped"));
            }

            case "top" -> {
                if (!sender.hasPermission(USE_PERMISSION)) { noPerms(sender); return true; }
                boolean eventScope = args.length >= 2 && isEventScope(args[1]);
                if (sender instanceof Player player) {
                    DragonMenuHolder.View view = eventScope
                            ? DragonMenuHolder.View.TOP_EVENT
                            : DragonMenuHolder.View.HALL_OF_FAME;
                    player.openInventory(dragonMenu.build(view, player));
                } else if (eventScope) {
                    sendCurrentEventTop(sender);
                } else {
                    sendHallOfFame(sender);
                }
            }

            case "info" -> {
                if (!sender.hasPermission(USE_PERMISSION)) { noPerms(sender); return true; }
                if (sender instanceof Player player) {
                    player.openInventory(dragonMenu.build(DragonMenuHolder.View.INFO, player));
                } else {
                    sendInfoChat(sender);
                }
            }

            case "placeholders" -> {
                if (!sender.hasPermission(USE_PERMISSION)) { noPerms(sender); return true; }
                sendPlaceholders(sender);
            }

            case "reload" -> {
                if (!sender.hasPermission("polaroiddragon.admin.reload")) { noPerms(sender); return true; }
                plugin.reloadConfig();
                messages.reload();
                plugin.getMenuConfig().reload();
                dragonManager.reschedule();
                dragonManager.reloadWebhook();
                sender.sendMessage(messages.get("general.config-reloaded"));
            }

            default -> sendHelp(sender);
        }

        return true;
    }

    // ─────────────────────────────────────────────
    //  INFO (consola)
    // ─────────────────────────────────────────────

    private void sendInfoChat(CommandSender sender) {
        sender.sendMessage(messages.get("info.title"));
        if (dragonManager.isEventActive()) {
            sender.sendMessage(messages.get("info.status-active"));
            EnderDragon d = dragonManager.getActiveDragon();
            if (d != null) {
                sender.sendMessage(messages.get("info.hp", Map.of(
                        "{current}", String.format("%.0f", d.getHealth()),
                        "{max}", String.format("%.0f", d.getMaxHealth())
                )));
            }
            sender.sendMessage(messages.get("info.participants", Map.of(
                    "{count}", String.valueOf(dragonManager.getDamageTracker().getParticipantCount())
            )));
        } else if (dragonManager.isCountdownActive()) {
            sender.sendMessage(messages.get("info.status-countdown"));
            sender.sendMessage(messages.get("info.time-remaining", Map.of(
                    "{time}", TimeUtil.format(dragonManager.getCountdownSeconds())
            )));
        } else {
            sender.sendMessage(messages.get("info.status-inactive"));
            sender.sendMessage(messages.get("info.next-event", Map.of(
                    "{next}", dragonManager.getScheduleManager().getNextFullString(),
                    "{time}", TimeUtil.format(dragonManager.getScheduleManager().getSecondsUntilNext())
            )));
        }
    }

    // ─────────────────────────────────────────────
    //  TOP EVENTO ACTUAL
    // ─────────────────────────────────────────────

    private void sendCurrentEventTop(CommandSender sender) {
        sender.sendMessage(messages.get("top.title"));
        if (!dragonManager.isEventActive()) {
            sender.sendMessage(messages.get("top.no-event-active"));
            return;
        }
        DamageTracker tracker = dragonManager.getDamageTracker();
        List<Map.Entry<UUID, Double>> ranking = tracker.getRanking();

        if (ranking.isEmpty()) {
            sender.sendMessage(messages.get("top.no-damage-yet"));
            return;
        }

        int limit = Math.min(ranking.size(), 5);
        for (int i = 0; i < limit; i++) {
            Map.Entry<UUID, Double> entry = ranking.get(i);
            sender.sendMessage(messages.get("top.entry", Map.of(
                    "{medal}", messages.getMedal(i + 1),
                    "{player}", tracker.getPlayerName(entry.getKey()),
                    "{damage}", String.format("%,.0f", entry.getValue())
            )));
        }

        if (sender instanceof Player player) {
            double myDamage = tracker.getDamage(player.getUniqueId());
            if (myDamage > 0) {
                int myRank = -1;
                for (int i = 0; i < ranking.size(); i++) {
                    if (ranking.get(i).getKey().equals(player.getUniqueId())) { myRank = i + 1; break; }
                }
                sender.sendMessage(messages.get("top.my-position", Map.of(
                        "{rank}", String.valueOf(myRank),
                        "{damage}", String.format("%,.0f", myDamage)
                )));
            }
        }
    }

    // ─────────────────────────────────────────────
    //  HALL OF FAME
    // ─────────────────────────────────────────────

    private void sendHallOfFame(CommandSender sender) {
        sender.sendMessage(messages.get("hall-of-fame.title"));
        List<StatsManager.PlayerStats> top = statsManager.getTopByDamage(5);

        if (top.isEmpty()) {
            sender.sendMessage(messages.get("hall-of-fame.no-stats"));
            return;
        }

        for (int i = 0; i < top.size(); i++) {
            StatsManager.PlayerStats ps = top.get(i);
            sender.sendMessage(messages.get("hall-of-fame.entry", Map.of(
                    "{medal}", messages.getMedal(i + 1),
                    "{player}", ps.getName(),
                    "{damage}", String.format("%,.0f", ps.getTotalDamage()),
                    "{events}", String.valueOf(ps.getEventsParticipated())
            )));
        }

        if (sender instanceof Player player) {
            StatsManager.PlayerStats myStats = statsManager.getPlayerStats(player.getUniqueId());
            if (myStats != null) {
                sender.sendMessage(messages.get("hall-of-fame.my-stats", Map.of(
                        "{damage}", String.format("%,.0f", myStats.getTotalDamage()),
                        "{events}", String.valueOf(myStats.getEventsParticipated())
                )));
            }
        }
    }

    // ─────────────────────────────────────────────
    //  PLACEHOLDERS
    // ─────────────────────────────────────────────

    private void sendPlaceholders(CommandSender sender) {
        sender.sendMessage(messages.get("placeholders-help.title"));
        messages.getList("placeholders-help.lines").forEach(sender::sendMessage);
    }

    // ─────────────────────────────────────────────
    //  AYUDA
    // ─────────────────────────────────────────────

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(messages.get("help.title"));
        messages.getList("help.lines").forEach(sender::sendMessage);
    }

    /** True when the argument selects the current event ranking. */
    private static boolean isEventScope(String arg) {
        return EVENT_SCOPE_ALIASES.contains(arg.toLowerCase(Locale.ROOT));
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String label, @NotNull String[] args) {
        if (args.length == 1) {
            List<String> options = new ArrayList<>();
            if (sender.hasPermission(USE_PERMISSION)) {
                options.addAll(List.of("top", "info", "placeholders"));
            }
            if (sender.hasPermission("polaroiddragon.admin.start")) options.add("start");
            if (sender.hasPermission("polaroiddragon.admin.spawn")) options.add("spawn");
            if (sender.hasPermission("polaroiddragon.admin.stop")) options.add("stop");
            if (sender.hasPermission("polaroiddragon.admin.reload")) options.add("reload");
            // Bukkit does not prefix-filter for an overridden completer, so
            // "/pd s<TAB>" would otherwise offer the whole list.
            return filterPrefix(options, args[0]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("top") && sender.hasPermission(USE_PERMISSION)) {
            return filterPrefix(EVENT_SCOPE_ALIASES, args[1]);
        }
        return List.of();
    }

    private static List<String> filterPrefix(List<String> options, String prefix) {
        return StringUtil.copyPartialMatches(prefix, options, new ArrayList<>());
    }

    private void noPerms(CommandSender sender) { sender.sendMessage(messages.get("general.no-permission")); }
}
