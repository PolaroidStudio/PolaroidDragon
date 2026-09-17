package studio.polaroid.polaroiddragon.manager;

import studio.polaroid.polaroiddragon.PolaroidDragon;
import studio.polaroid.polaroiddragon.util.ColorUtil;
import studio.polaroid.polaroiddragon.util.NotificationSender;
import studio.polaroid.polaroiddragon.util.TimeUtil;
import org.bukkit.*;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.EnderDragon;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;

public class DragonManager {

    private final PolaroidDragon plugin;
    private final DamageTracker damageTracker;
    private final ScheduleManager scheduleManager;
    private final StatsManager statsManager;
    private final PendingRewardManager pendingRewardManager;
    private final EconomyManager economyManager;
    private final DiscordWebhookManager discordWebhookManager;

    private UUID activeDragonUUID   = null;
    private boolean eventActive     = false;
    private boolean countdownActive = false;

    private BossBar bossBar          = null;
    private BukkitTask countdownTask = null;
    private BukkitTask scheduleTask  = null;
    private BukkitTask bossBarTask   = null;
    private BukkitTask timeoutTask   = null;

    private int countdownSeconds = 0;

    public DragonManager(PolaroidDragon plugin, StatsManager statsManager, PendingRewardManager pendingRewardManager, EconomyManager economyManager, DiscordWebhookManager discordWebhookManager) {
        this.plugin = plugin;
        this.statsManager = statsManager;
        this.pendingRewardManager = pendingRewardManager;
        this.economyManager = economyManager;
        this.discordWebhookManager = discordWebhookManager;
        this.damageTracker = new DamageTracker();
        this.scheduleManager = new ScheduleManager(plugin);
    }

    // ─────────────────────────────────────────────
    //  SCAN AL INICIAR
    // ─────────────────────────────────────────────

    public void scanExistingDragon() {
        World world = getConfiguredWorld();
        if (world == null) return;

        for (Entity entity : world.getEntitiesByClass(EnderDragon.class)) {
            EnderDragon dragon = (EnderDragon) entity;
            activeDragonUUID = dragon.getUniqueId();
            eventActive = true;
            setupBossBar(dragon);
            scheduleTimeout();
            plugin.getLogger().info("Dragón existente detectado tras reinicio (UUID: " + activeDragonUUID + "). Evento reanudado.");
            return;
        }
    }

    // ─────────────────────────────────────────────
    //  SCHEDULER POR ZONA HORARIA
    // ─────────────────────────────────────────────

    public void scheduleNextEvent() {
        if (!plugin.getConfig().getBoolean("event.auto-spawn")) return;

        cancelScheduleTask();
        scheduleManager.computeNextEvent();

        if (!scheduleManager.hasNextEvent()) {
            plugin.getLogger().warning("No hay horarios válidos en event.schedule.");
            return;
        }

        plugin.getLogger().info("Próximo evento: " + scheduleManager.getNextFullString()
                + " (en " + TimeUtil.format(scheduleManager.getSecondsUntilNext()) + ")");

        // Comprueba cada 20 segundos si ya toca el evento.
        // Más fiable que runTaskLater con millones de ticks.
        scheduleTask = new BukkitRunnable() {
            @Override
            public void run() {
                if (isAnyEventRunning()) return; // ya hay evento, esperar

                long seconds = scheduleManager.getSecondsUntilNext();

                if (seconds > 0) return; // aún no toca

                // Ya llegó la hora — cancelar este task y arrancar
                cancel();
                scheduleTask = null;

                int minPlayers = plugin.getConfig().getInt("event.min-players", 1);
                if (Bukkit.getOnlinePlayers().size() < minPlayers) {
                    plugin.getLogger().info("Jugadores insuficientes ("
                            + Bukkit.getOnlinePlayers().size() + "/" + minPlayers
                            + "). Evento pospuesto al siguiente horario.");
                    scheduleNextEvent();
                    return;
                }

                startCountdown();
            }
        }.runTaskTimer(plugin, 0L, 400L); // cada 20 segundos
    }

    /**
     * Reaplica la programación tras un /reload: si auto-spawn está activo,
     * (re)inicia el scheduleTask con la config actualizada; si se desactivó,
     * lo cancela.
     */
    public void reschedule() {
        if (plugin.getConfig().getBoolean("event.auto-spawn")) {
            scheduleNextEvent();
        } else {
            cancelScheduleTask();
            scheduleManager.computeNextEvent();
        }
    }

    /** Recarga webhook.yml. Llamado desde /polaroiddragon reload. */
    public void reloadWebhook() {
        discordWebhookManager.reload();
    }

    // ─────────────────────────────────────────────
    //  COUNTDOWN
    // ─────────────────────────────────────────────

    public void startCountdown() {
        if (isAnyEventRunning()) {
            plugin.getLogger().warning("startCountdown() ignorado — ya hay un evento o countdown activo.");
            return;
        }

        cancelCountdown();
        countdownActive = true;
        countdownSeconds = plugin.getConfig().getInt("event.countdown-seconds", 300);
        List<Integer> notifyAt = plugin.getConfig().getIntegerList("phases.countdown.notification-at");

        countdownTask = new BukkitRunnable() {
            @Override
            public void run() {
                if (countdownSeconds <= 0) {
                    cancel();
                    countdownActive = false;
                    spawnDragon();
                    return;
                }
                if (notifyAt.contains(countdownSeconds)) {
                    Map<String, String> ph = new HashMap<>();
                    ph.put("%remaining_time%", TimeUtil.format(countdownSeconds));
                    NotificationSender.send(plugin.getConfig(), "phases.countdown.notification", ph);
                }
                countdownSeconds--;
            }
        }.runTaskTimer(plugin, 0L, 20L);
    }

    // ─────────────────────────────────────────────
    //  SPAWN
    // ─────────────────────────────────────────────

    public void spawnDragon() {
        if (isAnyEventRunning()) {
            plugin.getLogger().warning("spawnDragon() ignorado — ya hay un evento o countdown activo.");
            return;
        }

        World world = getConfiguredWorld();
        if (world == null) {
            plugin.getLogger().severe("El mundo '" + plugin.getConfig().getString("dragon.world") + "' no existe.");
            scheduleNextEvent();
            return;
        }

        double x = plugin.getConfig().getDouble("dragon.spawn-x", 0);
        double y = plugin.getConfig().getDouble("dragon.spawn-y", 100);
        double z = plugin.getConfig().getDouble("dragon.spawn-z", 0);

        EnderDragon dragon = world.spawn(new Location(world, x, y, z), EnderDragon.class, d -> {
            double health = plugin.getConfig().getDouble("dragon.health", 400.0);
            d.setMaxHealth(health);
            d.setHealth(health);
            String rawName = plugin.getConfig().getString("dragon.name", "&5&lDragón Ancestral");
            d.setCustomName(ColorUtil.parse(rawName));
            d.setCustomNameVisible(true);
        });

        activeDragonUUID = dragon.getUniqueId();
        eventActive = true;
        damageTracker.reset();

        setupBossBar(dragon);
        scheduleTimeout();
        NotificationSender.send(plugin.getConfig(), "phases.spawn.notification", new HashMap<>());
        discordWebhookManager.sendSpawn();
    }

    // ─────────────────────────────────────────────
    //  TIMEOUT
    // ─────────────────────────────────────────────

    private void scheduleTimeout() {
        cancelTimeout();
        int timeoutMinutes = plugin.getConfig().getInt("event.timeout-minutes", 0);
        if (timeoutMinutes <= 0) return;

        timeoutTask = new BukkitRunnable() {
            @Override
            public void run() {
                if (!eventActive) return;
                plugin.getLogger().info("Timeout del dragón alcanzado (" + timeoutMinutes + " min). Procesando recompensas.");
                EnderDragon dragon = getActiveDragon();
                handleEventEnd(dragon, null, true);
                if (dragon != null) dragon.remove();
            }
        }.runTaskLater(plugin, (long) timeoutMinutes * 60 * 20);
    }

    // ─────────────────────────────────────────────
    //  BOSSBAR
    // ─────────────────────────────────────────────

    private void setupBossBar(EnderDragon dragon) {
        if (!plugin.getConfig().getBoolean("bossbar.enabled")) return;
        removeBossBar();

        BarColor color = safeBarColor(plugin.getConfig().getString("bossbar.color", "PURPLE"));
        BarStyle style = safeBarStyle(plugin.getConfig().getString("bossbar.style", "SEGMENTED_10"));
        String title   = plugin.getConfig().getString("bossbar.title", "Dragón Ancestral");

        bossBar = Bukkit.createBossBar(ColorUtil.parse(title), color, style);
        bossBar.setVisible(true);
        Bukkit.getOnlinePlayers().forEach(bossBar::addPlayer);

        bossBarTask = new BukkitRunnable() {
            @Override
            public void run() {
                EnderDragon d = getActiveDragon();
                if (d == null || !eventActive) { cancel(); return; }

                bossBar.setProgress(Math.max(0.0, Math.min(1.0, d.getHealth() / d.getMaxHealth())));
                String raw = plugin.getConfig().getString("bossbar.title", "Dragón Ancestral")
                        .replace("{current}", String.format("%.0f", d.getHealth()))
                        .replace("{max}",     String.format("%.0f", d.getMaxHealth()));
                bossBar.setTitle(ColorUtil.parse(raw));
            }
        }.runTaskTimer(plugin, 0L, 20L);
    }

    public void removeBossBar() {
        if (bossBarTask != null) { bossBarTask.cancel(); bossBarTask = null; }
        if (bossBar != null)     { bossBar.removeAll(); bossBar.setVisible(false); bossBar = null; }
    }

    public void addPlayerToBossBar(Player player) {
        if (bossBar != null) bossBar.addPlayer(player);
    }

    // ─────────────────────────────────────────────
    //  MUERTE / FIN DEL EVENTO
    // ─────────────────────────────────────────────

    /** Llamado desde DragonDeathListener cuando muere el dragón del evento. */
    public void handleDragonDeath(EnderDragon dragon, Player killer) {
        if (!dragon.getUniqueId().equals(activeDragonUUID)) return;
        handleEventEnd(dragon, killer, false);
    }

    /**
     * Lógica central de fin de evento. Usada tanto por muerte natural como por timeout.
     *
     * @param dragon   el dragón (puede ser null si ya fue removido)
     * @param killer   jugador que dio el golpe final (null si timeout o sin killer)
     * @param timeout  true si el evento terminó por tiempo agotado
     */
    private void handleEventEnd(EnderDragon dragon, Player killer, boolean timeout) {
        eventActive      = false;
        activeDragonUUID = null;
        cancelTimeout();
        removeBossBar();

        List<Map.Entry<UUID, Double>> ranking = damageTracker.getRanking();
        int topSize = plugin.getConfig().getInt("event.top-size", 5);
        double minDamage = plugin.getConfig().getDouble("rewards.min-damage", 10.0);

        // Notificación
        String phase = timeout ? "phases.timeout.notification" : "phases.death.notification";
        Map<String, String> ph = buildEndPlaceholders(ranking, topSize, killer);
        NotificationSender.send(plugin.getConfig(), phase, ph);
        if (timeout) {
            discordWebhookManager.sendTimeout(ph);
        } else {
            discordWebhookManager.sendDeath(ph);
        }

        // Recompensas por posición (sin restricción de min-damage)
        Set<UUID> rewarded = givePositionRewards(ranking, topSize);

        // Kill bonus
        if (!timeout && killer != null && plugin.getConfig().getBoolean("rewards.kill-bonus.enabled")) {
            executeCommands(plugin.getConfig().getStringList("rewards.kill-bonus.commands"), killer.getName());
            giveMoney(killer, plugin.getConfig().getDouble("rewards.kill-bonus.money", 0));
        }

        // Participación — fuera del top y que superen min-damage
        if (plugin.getConfig().getBoolean("rewards.participation.enabled")) {
            List<String> commands = plugin.getConfig().getStringList("rewards.participation.commands");
            double money = plugin.getConfig().getDouble("rewards.participation.money", 0);
            for (Map.Entry<UUID, Double> entry : ranking) {
                if (rewarded.contains(entry.getKey())) continue;
                if (entry.getValue() < minDamage) continue;
                UUID uuid = entry.getKey();
                Player p = Bukkit.getPlayer(uuid);
                if (p != null) {
                    executeCommands(commands, p.getName());
                } else {
                    pendingRewardManager.queue(uuid, damageTracker.getPlayerName(uuid), commands);
                }
                giveMoneyToUuid(uuid, p, money);
            }
        }

        // Guardar en el Hall of Fame a todos los participantes
        for (Map.Entry<UUID, Double> entry : ranking) {
            statsManager.recordParticipation(
                    entry.getKey(),
                    damageTracker.getPlayerName(entry.getKey()),
                    entry.getValue()
            );
        }
        if (!ranking.isEmpty()) statsManager.flush();

        damageTracker.reset();
        scheduleNextEvent();
    }

    // ─────────────────────────────────────────────
    //  RECOMPENSAS
    // ─────────────────────────────────────────────

    private Set<UUID> givePositionRewards(List<Map.Entry<UUID, Double>> ranking, int topSize) {
        Set<UUID> rewarded = new HashSet<>();
        for (int i = 1; i <= Math.min(topSize, ranking.size()); i++) {
            UUID uuid = ranking.get(i - 1).getKey();
            List<String> commands = plugin.getConfig().getStringList("rewards.positions." + i + ".commands");
            double money = plugin.getConfig().getDouble("rewards.positions." + i + ".money", 0);
            Player p = Bukkit.getPlayer(uuid);
            if (p != null) {
                executeCommands(commands, p.getName());
            } else {
                pendingRewardManager.queue(uuid, damageTracker.getPlayerName(uuid), commands);
            }
            giveMoneyToUuid(uuid, p, money);
            rewarded.add(uuid);
        }
        return rewarded;
    }

    private Map<String, String> buildEndPlaceholders(List<Map.Entry<UUID, Double>> ranking,
                                                      int topSize, Player killer) {
        Map<String, String> ph = new HashMap<>();
        ph.put("%killer%", killer != null ? killer.getName() : plugin.getMessageManager().get("general.no-killer"));
        for (int i = 1; i <= topSize; i++) {
            if (i <= ranking.size()) {
                Map.Entry<UUID, Double> e = ranking.get(i - 1);
                ph.put("%top" + i + "_name%",   damageTracker.getPlayerName(e.getKey()));
                ph.put("%top" + i + "_damage%", String.format("%,.0f", e.getValue()));
            } else {
                ph.put("%top" + i + "_name%",   "-");
                ph.put("%top" + i + "_damage%", "0");
            }
        }
        return ph;
    }

    private void executeCommands(List<String> commands, String playerName) {
        for (String cmd : commands) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd.replace("{player}", playerName));
        }
    }

    private void giveMoney(Player player, double amount) {
        giveMoneyToUuid(player.getUniqueId(), player, amount);
    }

    private void giveMoneyToUuid(UUID uuid, Player onlinePlayer, double amount) {
        if (amount <= 0 || !economyManager.isEnabled()) return;
        economyManager.deposit(Bukkit.getOfflinePlayer(uuid), amount);
        if (onlinePlayer != null) {
            onlinePlayer.sendMessage(plugin.getMessageManager().get("rewards.money-received", Map.of(
                    "{amount}", economyManager.format(amount)
            )));
        }
    }

    // ─────────────────────────────────────────────
    //  DAÑO
    // ─────────────────────────────────────────────

    public void registerDamage(Player player, double damage) {
        damageTracker.addDamage(player.getUniqueId(), player.getName(), damage);
    }

    /** Verifica que el dragón sea el del evento por UUID. */
    public boolean isEventDragon(EnderDragon dragon) {
        return eventActive && activeDragonUUID != null
                && dragon.getUniqueId().equals(activeDragonUUID);
    }

    // ─────────────────────────────────────────────
    //  STOP
    // ─────────────────────────────────────────────

    public void stopEvent() {
        eventActive      = false;
        countdownActive  = false;
        countdownSeconds = 0;
        activeDragonUUID = null;
        damageTracker.reset();
        removeBossBar();
        cancelCountdown();
        cancelTimeout();
    }

    public void cancelAll() {
        stopEvent();
        cancelScheduleTask();
    }

    private void cancelCountdown()    { if (countdownTask != null) { countdownTask.cancel(); countdownTask = null; } }
    private void cancelScheduleTask() { if (scheduleTask  != null) { scheduleTask.cancel();  scheduleTask  = null; } }
    private void cancelTimeout()      { if (timeoutTask   != null) { timeoutTask.cancel();   timeoutTask   = null; } }

    // ─────────────────────────────────────────────
    //  GETTERS
    // ─────────────────────────────────────────────

    public boolean isEventActive()          { return eventActive; }
    public boolean isCountdownActive()      { return countdownActive; }
    public boolean isAnyEventRunning()      { return eventActive || countdownActive; }
    public DamageTracker getDamageTracker() { return damageTracker; }
    public ScheduleManager getScheduleManager() { return scheduleManager; }
    public int getCountdownSeconds()        { return countdownSeconds; }

    /** Busca el dragón activo por UUID. No modifica el estado del evento. */
    public EnderDragon getActiveDragon() {
        if (activeDragonUUID == null) return null;
        World world = getConfiguredWorld();
        if (world == null) return null;
        for (Entity e : world.getEntitiesByClass(EnderDragon.class)) {
            if (e.getUniqueId().equals(activeDragonUUID)) return (EnderDragon) e;
        }
        return null;
    }

    private World getConfiguredWorld() {
        return Bukkit.getWorld(plugin.getConfig().getString("dragon.world", "world_the_end"));
    }

    private BarColor safeBarColor(String v) {
        try { return BarColor.valueOf(v); } catch (Exception e) { return BarColor.PURPLE; }
    }

    private BarStyle safeBarStyle(String v) {
        try { return BarStyle.valueOf(v); } catch (Exception e) { return BarStyle.SEGMENTED_10; }
    }
}
