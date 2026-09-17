package studio.polaroid.polaroiddragon.manager;

import studio.polaroid.polaroiddragon.PolaroidDragon;
import studio.polaroid.polaroiddragon.util.ColorUtil;
import studio.polaroid.polaroiddragon.util.NotificationSender;
import studio.polaroid.polaroiddragon.util.TimeUtil;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.EnderDragon;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

public class DragonManager {

    /**
     * Persistent tag written on every dragon this plugin spawns. Only a dragon
     * carrying it may be adopted on startup, so the vanilla End dragon (or any
     * dragon spawned by another plugin) can never hijack the event scheduler.
     */
    private static final String EVENT_DRAGON_TAG = "event_dragon";

    /** Upper bound for {@code event.timeout-minutes}: one day. */
    private static final int MAX_TIMEOUT_MINUTES = 24 * 60;

    private final PolaroidDragon plugin;
    private final NamespacedKey eventDragonKey;
    private final DamageTracker damageTracker;
    private final ScheduleManager scheduleManager;
    private final StatsManager statsManager;
    private final PendingRewardManager pendingRewardManager;
    private final EconomyManager economyManager;
    private final DiscordWebhookManager discordWebhookManager;

    // Event state is written on the main thread but read from PlaceholderAPI,
    // which scoreboard and tab plugins routinely evaluate off the main thread.
    // Without volatile there is no happens-before edge and an async reader can
    // observe a stale value indefinitely. DamageTracker is concurrent for the
    // same reason.
    private volatile UUID activeDragonUUID   = null;
    private volatile boolean eventActive     = false;
    private volatile boolean countdownActive = false;

    /**
     * Guards {@link #handleEventEnd}. Reward payout must run exactly once per
     * event: the timeout task, the death listener and an admin stop can all
     * reach it, and paying twice duplicates money and reward commands.
     */
    private final AtomicBoolean endingEvent = new AtomicBoolean(false);

    private BossBar bossBar          = null;
    private BukkitTask countdownTask = null;
    private BukkitTask scheduleTask  = null;
    private BukkitTask bossBarTask   = null;
    private BukkitTask timeoutTask   = null;

    private volatile int countdownSeconds = 0;

    // Dragon health snapshot, refreshed by the boss bar task. Placeholders read
    // this instead of touching the world entity list from an async thread.
    private volatile double cachedHealth    = 0.0;
    private volatile double cachedMaxHealth = 0.0;

    public DragonManager(PolaroidDragon plugin, StatsManager statsManager, PendingRewardManager pendingRewardManager, EconomyManager economyManager, DiscordWebhookManager discordWebhookManager) {
        this.plugin = plugin;
        this.eventDragonKey = new NamespacedKey(plugin, EVENT_DRAGON_TAG);
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

    /**
     * Adopts a previously spawned event dragon after a restart.
     *
     * <p>Only a dragon carrying this plugin's persistent tag is adopted. Adopting
     * any {@link EnderDragon} would claim the vanilla End dragon and leave
     * {@code eventActive} stuck at true, which permanently suppresses the
     * scheduler.
     */
    public void scanExistingDragon() {
        World world = getConfiguredWorld();
        if (world == null) return;

        for (EnderDragon dragon : world.getEntitiesByClass(EnderDragon.class)) {
            if (!isTaggedEventDragon(dragon)) continue;
            activeDragonUUID = dragon.getUniqueId();
            eventActive = true;
            setupBossBar(dragon);
            scheduleTimeout();
            plugin.getLogger().info("Tagged event dragon found after restart (UUID: "
                    + activeDragonUUID + "). Event resumed.");
            return;
        }
    }

    /** True when the entity carries the persistent tag written by {@link #spawnDragon()}. */
    private boolean isTaggedEventDragon(Entity entity) {
        return entity.getPersistentDataContainer().has(eventDragonKey, PersistentDataType.BYTE);
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
            plugin.getLogger().severe("The world '" + plugin.getConfig().getString("dragon.world")
                    + "' does not exist; the event cannot start.");
            scheduleNextEvent();
            return;
        }

        double x = plugin.getConfig().getDouble("dragon.spawn-x", 0);
        double y = plugin.getConfig().getDouble("dragon.spawn-y", 100);
        double z = plugin.getConfig().getDouble("dragon.spawn-z", 0);

        // A y outside the world bounds makes world.spawn throw, which would
        // abort the event with a stack trace instead of a readable warning.
        double minY = world.getMinHeight();
        double maxY = world.getMaxHeight() - 1;
        if (y < minY || y > maxY) {
            double clamped = Math.min(Math.max(y, minY), maxY);
            plugin.getLogger().warning("dragon.spawn-y (" + y + ") is outside the world bounds ["
                    + minY + ", " + maxY + "]; using " + clamped + ".");
            y = clamped;
        }

        // Bukkit throws for a non-positive or absurd max health, which would
        // leave a half-configured dragon in the world mid-spawn.
        double configuredHealth = plugin.getConfig().getDouble("dragon.health", 400.0);
        if (configuredHealth <= 0 || !Double.isFinite(configuredHealth)) {
            plugin.getLogger().warning("dragon.health (" + configuredHealth
                    + ") must be a positive number; using 400.");
            configuredHealth = 400.0;
        }
        final double health = configuredHealth;

        EnderDragon dragon;
        try {
            dragon = world.spawn(new Location(world, x, y, z), EnderDragon.class, d -> {
                AttributeInstance maxHealth = d.getAttribute(Attribute.GENERIC_MAX_HEALTH);
                if (maxHealth != null) maxHealth.setBaseValue(health);
                d.setHealth(Math.min(health, d.getMaxHealth()));

                String rawName = plugin.getConfig().getString("dragon.name", "<dark_purple><bold>Ancestral Dragon");
                d.customName(ColorUtil.component(rawName));
                d.setCustomNameVisible(true);
                // Written before the entity is added to the world so a restart can
                // tell this dragon apart from the vanilla End dragon.
                d.getPersistentDataContainer().set(eventDragonKey, PersistentDataType.BYTE, (byte) 1);
            });
        } catch (Exception e) {
            plugin.getLogger().severe("Could not spawn the event dragon: " + e.getMessage());
            scheduleNextEvent();
            return;
        }

        activeDragonUUID = dragon.getUniqueId();
        eventActive = true;
        // Re-arm the payout guard for this new event.
        endingEvent.set(false);
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
        int configured = plugin.getConfig().getInt("event.timeout-minutes", 0);
        if (configured <= 0) return;

        // A full day is already far beyond any sane event; anything larger just
        // pins a task for the server's lifetime.
        final int timeoutMinutes;
        if (configured > MAX_TIMEOUT_MINUTES) {
            plugin.getLogger().warning("event.timeout-minutes (" + configured
                    + ") is unreasonably large; capping at " + MAX_TIMEOUT_MINUTES + ".");
            timeoutMinutes = MAX_TIMEOUT_MINUTES;
        } else {
            timeoutMinutes = configured;
        }

        timeoutTask = new BukkitRunnable() {
            @Override
            public void run() {
                if (!eventActive) return;
                plugin.getLogger().info("Dragon timeout reached (" + timeoutMinutes
                        + " min). Processing rewards.");
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

        // Read once. This task runs every second for the whole fight, and
        // re-reading plus re-parsing the title each tick was pure overhead.
        final String rawTitle = plugin.getConfig().getString("bossbar.title", "<dark_purple>Ancestral Dragon");

        bossBar = Bukkit.createBossBar(ColorUtil.parse(rawTitle), color, style);
        bossBar.setVisible(true);
        Bukkit.getOnlinePlayers().forEach(bossBar::addPlayer);

        cachedHealth    = dragon.getHealth();
        cachedMaxHealth = dragon.getMaxHealth();

        bossBarTask = new BukkitRunnable() {
            private String lastRendered = null;

            @Override
            public void run() {
                EnderDragon d = getActiveDragon();
                if (d == null || !eventActive) { cancel(); return; }

                double health = d.getHealth();
                double maxHealth = d.getMaxHealth();

                // Publish for the placeholders, which run off the main thread and
                // must not walk the world entity list themselves.
                cachedHealth    = health;
                cachedMaxHealth = maxHealth;

                bossBar.setProgress(maxHealth > 0
                        ? Math.max(0.0, Math.min(1.0, health / maxHealth))
                        : 0.0);

                String raw = rawTitle
                        .replace("{current}", String.format("%.0f", health))
                        .replace("{max}",     String.format("%.0f", maxHealth));
                // Only re-parse when the rendered text actually changed.
                if (!raw.equals(lastRendered)) {
                    bossBar.setTitle(ColorUtil.parse(raw));
                    lastRendered = raw;
                }
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
        // Exactly once per event. The timeout task, the death listener and an
        // admin stop can all reach this; a second pass would pay every reward
        // and every command a second time.
        if (!endingEvent.compareAndSet(false, true)) return;

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

        // One disk write for the whole payout, instead of one per queued winner.
        pendingRewardManager.flush();

        // Record every participant in the Hall of Fame.
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

    /**
     * Runs reward commands as console.
     *
     * <p>Each dispatch is guarded individually: one broken third-party command
     * used to abort the whole payout, costing every remaining player their
     * reward and their Hall of Fame entry.
     */
    private void executeCommands(List<String> commands, String playerName) {
        String safeName = sanitizeName(playerName);
        for (String cmd : commands) {
            try {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd.replace("{player}", safeName));
            } catch (Exception e) {
                plugin.getLogger().warning("Reward command failed for " + safeName
                        + " ('" + cmd + "'): " + e.getMessage());
            }
        }
    }

    /**
     * Strips anything that could split or extend a console command.
     *
     * <p>Vanilla names are {@code [A-Za-z0-9_]}, but Bedrock/Geyser names carry
     * a prefix and offline-mode servers accept more, so a name is never pasted
     * into a command line unfiltered.
     */
    static String sanitizeName(String playerName) {
        if (playerName == null) return "";
        return playerName.replaceAll("[^A-Za-z0-9_.]", "");
    }

    private void giveMoney(Player player, double amount) {
        giveMoneyToUuid(player.getUniqueId(), player, amount);
    }

    private void giveMoneyToUuid(UUID uuid, Player onlinePlayer, double amount) {
        if (amount <= 0 || !economyManager.isEnabled()) return;

        // Every UUID here damaged the dragon this event, so its profile is
        // cached and getOfflinePlayer does not hit the network.
        boolean paid = economyManager.deposit(Bukkit.getOfflinePlayer(uuid), amount);
        // Telling a player they were paid when the deposit failed is worse than
        // saying nothing; the failure is already logged by EconomyManager.
        if (paid && onlinePlayer != null) {
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

    /**
     * Stops the running event and removes the dragon entity.
     *
     * <p>Leaving the entity alive would make it untracked yet still tagged, so
     * the next startup scan would re-adopt it and resume a dead event.
     */
    public void stopEvent() {
        // Resolve the entity before the UUID is cleared, otherwise it is unreachable.
        EnderDragon dragon = getActiveDragon();

        // Clear the state BEFORE removing the entity. Removing a dragon can
        // surface a death event, and a listener that still saw eventActive ==
        // true would pay out full rewards for an event an admin just cancelled.
        eventActive      = false;
        countdownActive  = false;
        countdownSeconds = 0;
        activeDragonUUID = null;
        // A stopped event pays nothing, so the next event starts from a clean guard.
        endingEvent.set(false);

        if (dragon != null) {
            dragon.remove();
        }
        damageTracker.reset();
        removeBossBar();
        cancelCountdown();
        cancelTimeout();
    }

    /**
     * Shutdown path. Cancels every scheduled task and clears the boss bar, but
     * deliberately leaves the dragon entity and the event flags alone: the
     * tagged dragon is what {@link #scanExistingDragon()} adopts on the next
     * startup to resume the event.
     */
    public void cancelAll() {
        removeBossBar();
        cancelCountdown();
        cancelTimeout();
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

    /**
     * Resolves the active dragon by UUID. Does not change event state.
     *
     * <p>Main thread only: it touches live entity state. Async callers such as
     * PlaceholderAPI must read {@link #getCachedHealth()} instead.
     */
    public EnderDragon getActiveDragon() {
        UUID uuid = activeDragonUUID;
        if (uuid == null) return null;
        // O(1) on Paper. Scanning every EnderDragon in the world ran once per
        // second from the boss bar task plus once per menu build.
        Entity entity = Bukkit.getEntity(uuid);
        return entity instanceof EnderDragon dragon ? dragon : null;
    }

    /** Dragon health as of the last boss bar tick. Safe to read from any thread. */
    public double getCachedHealth() { return cachedHealth; }

    /** Dragon max health as of the last boss bar tick. Safe to read from any thread. */
    public double getCachedMaxHealth() { return cachedMaxHealth; }

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
