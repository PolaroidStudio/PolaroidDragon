package studio.polaroid.polaroiddragon.gui;

import studio.polaroid.polaroiddragon.manager.DamageTracker;
import studio.polaroid.polaroiddragon.manager.DragonManager;
import studio.polaroid.polaroiddragon.manager.MenuConfig;
import studio.polaroid.polaroiddragon.manager.MessageManager;
import studio.polaroid.polaroiddragon.manager.StatsManager;
import studio.polaroid.polaroiddragon.util.ColorUtil;
import studio.polaroid.polaroiddragon.util.TimeUtil;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.EnderDragon;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class DragonMenu {

    private final DragonManager dragonManager;
    private final StatsManager statsManager;
    private final MessageManager messages;
    private final MenuConfig menuConfig;
    private final MenuItemMarker marker;

    /** Slots already reported as out of range, so a reopened menu does not flood the console. */
    private final Set<Integer> warnedSlots = new HashSet<>();

    public DragonMenu(DragonManager dragonManager, StatsManager statsManager,
                      MessageManager messages, MenuConfig menuConfig, MenuItemMarker marker) {
        this.dragonManager = dragonManager;
        this.statsManager = statsManager;
        this.messages = messages;
        this.menuConfig = menuConfig;
        this.marker = marker;
    }

    // ─────────────────────────────────────────────
    //  NAVEGACIÓN
    // ─────────────────────────────────────────────

    public DragonMenuHolder.View navTarget(DragonMenuHolder.View current, int slot) {
        switch (current) {
            case INFO -> {
                if (slot == menuConfig.getInfoNavToTopSlot()) return DragonMenuHolder.View.TOP_EVENT;
            }
            case TOP_EVENT -> {
                if (slot == menuConfig.getTopEventNavToHallSlot()) return DragonMenuHolder.View.HALL_OF_FAME;
                if (slot == menuConfig.getTopEventNavToInfoSlot()) return DragonMenuHolder.View.INFO;
            }
            case HALL_OF_FAME -> {
                if (slot == menuConfig.getHallOfFameNavToTopSlot()) return DragonMenuHolder.View.TOP_EVENT;
                if (slot == menuConfig.getHallOfFameNavToInfoSlot()) return DragonMenuHolder.View.INFO;
            }
        }
        return null;
    }

    // ─────────────────────────────────────────────
    //  CONSTRUCCIÓN DEL INVENTARIO
    // ─────────────────────────────────────────────

    public Inventory build(DragonMenuHolder.View view, Player viewer) {
        DragonMenuHolder holder = new DragonMenuHolder(view);

        String rawTitle = switch (view) {
            case INFO -> menuConfig.getInfoTitle();
            case TOP_EVENT -> menuConfig.getTopEventTitle();
            case HALL_OF_FAME -> menuConfig.getHallOfFameTitle();
        };
        int size = switch (view) {
            case INFO -> menuConfig.getInfoSize();
            case TOP_EVENT -> menuConfig.getTopEventSize();
            case HALL_OF_FAME -> menuConfig.getHallOfFameSize();
        };

        Inventory inv = Bukkit.createInventory(holder, size, colorize(rawTitle));
        holder.setInventory(inv);

        switch (view) {
            case INFO -> buildInfo(inv);
            case TOP_EVENT -> buildTopEvent(inv, viewer);
            case HALL_OF_FAME -> buildHallOfFame(inv, viewer);
        }

        addNavigation(inv, view);
        fillEmpty(inv, view);
        return inv;
    }

    // ─────────────────────────────────────────────
    //  INFO
    // ─────────────────────────────────────────────

    private void buildInfo(Inventory inv) {
        String name;
        List<String> lore;
        boolean anyActive = dragonManager.isEventActive() || dragonManager.isCountdownActive();

        if (dragonManager.isEventActive()) {
            name = messages.get("info.status-active");
            lore = new ArrayList<>();

            EnderDragon dragon = dragonManager.getActiveDragon();
            if (dragon != null) {
                double hp = dragon.getHealth();
                // The attribute instance is nullable per the API contract, and a health bar
                // is not worth an NPE that would take the whole menu down with it.
                AttributeInstance maxHealth = dragon.getAttribute(Attribute.GENERIC_MAX_HEALTH);
                double maxHp = maxHealth != null ? maxHealth.getValue() : dragon.getMaxHealth();
                lore.add(buildHealthBar(hp, maxHp));
                lore.add(messages.get("info.hp", Map.of(
                        "{current}", "%.0f".formatted(hp),
                        "{max}", "%.0f".formatted(maxHp)
                )));
            }

            lore.add(messages.get("info.participants", Map.of(
                    "{count}", String.valueOf(dragonManager.getDamageTracker().getParticipantCount())
            )));
        } else if (dragonManager.isCountdownActive()) {
            name = messages.get("info.status-countdown");
            lore = List.of(messages.get("info.time-remaining", Map.of(
                    "{time}", TimeUtil.format(dragonManager.getCountdownSeconds())
            )));
        } else {
            name = messages.get("info.status-inactive");
            lore = List.of(messages.get("info.next-event", Map.of(
                    "{next}", dragonManager.getScheduleManager().getNextFullString(),
                    "{time}", TimeUtil.format(dragonManager.getScheduleManager().getSecondsUntilNext())
            )));
        }

        Material mat = anyActive ? menuConfig.getInfoActiveMaterial() : menuConfig.getInfoInactiveMaterial();
        safeSet(inv, menuConfig.getInfoStatusSlot(), simpleItem(mat, name, lore.toArray(new String[0])));
    }

    /** Barra de 20 caracteres: &a (vida actual) + &7 (vida faltante). */
    private static String buildHealthBar(double current, double max) {
        if (max <= 0) max = 1;
        int total = 20;
        int filled = Math.max(0, Math.min(total, (int) Math.round((current / max) * total)));

        return "&a" + "█".repeat(filled) + "&7" + "█".repeat(total - filled);
    }

    // ─────────────────────────────────────────────
    //  TOP EVENTO ACTUAL
    // ─────────────────────────────────────────────

    private void buildTopEvent(Inventory inv, Player viewer) {
        if (!dragonManager.isEventActive()) {
            safeSet(inv, menuConfig.getTopEventEmptySlot(),
                    simpleItem(menuConfig.getTopEventEmptyMaterial(), messages.get("top.no-event-active")));
            return;
        }

        DamageTracker tracker = dragonManager.getDamageTracker();
        List<Map.Entry<UUID, Double>> ranking = tracker.getRanking();

        if (ranking.isEmpty()) {
            safeSet(inv, menuConfig.getTopEventEmptySlot(),
                    simpleItem(menuConfig.getTopEventEmptyMaterial(), messages.get("top.no-damage-yet")));
            return;
        }

        List<Integer> rankingSlots = menuConfig.getTopEventRankingSlots();
        int limit = Math.min(ranking.size(), rankingSlots.size());
        for (int i = 0; i < limit; i++) {
            Map.Entry<UUID, Double> entry = ranking.get(i);
            String medal = messages.getMedal(i + 1);
            String playerName = tracker.getPlayerName(entry.getKey());

            safeSet(inv, rankingSlots.get(i), playerHead(entry.getKey(), medal + " " + playerName,
                    messages.get("top.entry", Map.of(
                            "{medal}", medal,
                            "{player}", playerName,
                            "{damage}", "%,.0f".formatted(entry.getValue())
                    ))));
        }

        double myDamage = tracker.getDamage(viewer.getUniqueId());
        if (myDamage > 0) {
            int myRank = -1;
            for (int i = 0; i < ranking.size(); i++) {
                if (ranking.get(i).getKey().equals(viewer.getUniqueId())) { myRank = i + 1; break; }
            }
            safeSet(inv, menuConfig.getTopEventMyPositionSlot(),
                    playerHead(viewer.getUniqueId(), viewer.getName(),
                            messages.get("top.my-position", Map.of(
                                    "{rank}", String.valueOf(myRank),
                                    "{damage}", "%,.0f".formatted(myDamage)
                            ))));
        }
    }

    // ─────────────────────────────────────────────
    //  HALL OF FAME
    // ─────────────────────────────────────────────

    private void buildHallOfFame(Inventory inv, Player viewer) {
        List<StatsManager.PlayerStats> top = statsManager.getTopByDamage(5);

        if (top.isEmpty()) {
            safeSet(inv, menuConfig.getHallOfFameEmptySlot(),
                    simpleItem(menuConfig.getHallOfFameEmptyMaterial(), messages.get("hall-of-fame.no-stats")));
            return;
        }

        List<Integer> rankingSlots = menuConfig.getHallOfFameRankingSlots();
        int limit = Math.min(top.size(), rankingSlots.size());
        for (int i = 0; i < limit; i++) {
            StatsManager.PlayerStats ps = top.get(i);
            String medal = messages.getMedal(i + 1);

            safeSet(inv, rankingSlots.get(i), playerHead(ps.getUuid(), medal + " " + ps.getName(),
                    messages.get("hall-of-fame.entry", Map.of(
                            "{medal}", medal,
                            "{player}", ps.getName(),
                            "{damage}", "%,.0f".formatted(ps.getTotalDamage()),
                            "{events}", String.valueOf(ps.getEventsParticipated())
                    ))));
        }

        StatsManager.PlayerStats myStats = statsManager.getPlayerStats(viewer.getUniqueId());
        if (myStats != null) {
            safeSet(inv, menuConfig.getHallOfFameMyPositionSlot(),
                    playerHead(viewer.getUniqueId(), viewer.getName(),
                            messages.get("hall-of-fame.my-stats", Map.of(
                                    "{damage}", "%,.0f".formatted(myStats.getTotalDamage()),
                                    "{events}", String.valueOf(myStats.getEventsParticipated())
                            ))));
        }
    }

    // ─────────────────────────────────────────────
    //  NAVEGACIÓN (ITEMS)
    // ─────────────────────────────────────────────

    private void addNavigation(Inventory inv, DragonMenuHolder.View view) {
        switch (view) {
            case INFO -> safeSet(inv,
                    menuConfig.getInfoNavToTopSlot(),
                    navItem(menuConfig.getInfoNavToTopMaterial(), "gui.nav-top"));
            case TOP_EVENT -> {
                safeSet(inv, menuConfig.getTopEventNavToHallSlot(),
                        navItem(menuConfig.getTopEventNavToHallMaterial(), "gui.nav-hall-of-fame"));
                safeSet(inv, menuConfig.getTopEventNavToInfoSlot(),
                        navItem(menuConfig.getTopEventNavToInfoMaterial(), "gui.nav-info"));
            }
            case HALL_OF_FAME -> {
                safeSet(inv, menuConfig.getHallOfFameNavToTopSlot(),
                        navItem(menuConfig.getHallOfFameNavToTopMaterial(), "gui.nav-top"));
                safeSet(inv, menuConfig.getHallOfFameNavToInfoSlot(),
                        navItem(menuConfig.getHallOfFameNavToInfoMaterial(), "gui.nav-info"));
            }
        }
    }

    /**
     * A navigation button: its name from {@code <key>} and its lore from
     * {@code <key>-lore}, so every actionable item ends in a click prompt.
     */
    private ItemStack navItem(Material material, String key) {
        List<String> lore = messages.getList(key + "-lore");
        return simpleItem(material, messages.get(key), lore.toArray(new String[0]));
    }

    // ─────────────────────────────────────────────
    //  RELLENO DE SLOTS VACÍOS
    // ─────────────────────────────────────────────

    private void fillEmpty(Inventory inv, DragonMenuHolder.View view) {
        boolean enabled = switch (view) {
            case INFO -> menuConfig.isInfoFillerEnabled();
            case TOP_EVENT -> menuConfig.isTopEventFillerEnabled();
            case HALL_OF_FAME -> menuConfig.isHallOfFameFillerEnabled();
        };
        if (!enabled) return;

        Material mat = switch (view) {
            case INFO -> menuConfig.getInfoFillerMaterial();
            case TOP_EVENT -> menuConfig.getTopEventFillerMaterial();
            case HALL_OF_FAME -> menuConfig.getHallOfFameFillerMaterial();
        };
        String name = switch (view) {
            case INFO -> menuConfig.getInfoFillerName();
            case TOP_EVENT -> menuConfig.getTopEventFillerName();
            case HALL_OF_FAME -> menuConfig.getHallOfFameFillerName();
        };

        boolean hideTooltip = switch (view) {
            case INFO -> menuConfig.isInfoFillerTooltipHidden();
            case TOP_EVENT -> menuConfig.isTopEventFillerTooltipHidden();
            case HALL_OF_FAME -> menuConfig.isHallOfFameFillerTooltipHidden();
        };

        ItemStack filler = simpleItem(mat, name);
        if (hideTooltip) {
            // An empty tooltip box trailing the cursor over every filler pane
            // looks broken; Paper 1.20.5+ can suppress it outright.
            ItemMeta fillerMeta = filler.getItemMeta();
            // AIR is an accepted filler material in menus.yml and carries no meta at all,
            // so the tooltip request is simply dropped instead of throwing mid-build.
            if (fillerMeta != null) {
                fillerMeta.setHideTooltip(true);
                filler.setItemMeta(fillerMeta);
            }
        }
        for (int i = 0; i < inv.getSize(); i++) {
            if (inv.getItem(i) == null) {
                // A clone per slot: sharing one instance lets a later mutation
                // on any slot silently rewrite every other one.
                inv.setItem(i, filler.clone());
            }
        }
    }

    // ─────────────────────────────────────────────
    //  HELPERS
    // ─────────────────────────────────────────────

    /**
     * menus.yml slots are operator-controlled and were never validated, so a slot beyond the
     * view size threw ArrayIndexOutOfBoundsException out of build(), which propagates into the
     * command handler and the click listener. A misconfigured slot now costs one item, not the menu.
     */
    private void safeSet(Inventory inv, int slot, ItemStack item) {
        if (slot < 0 || slot >= inv.getSize()) {
            if (warnedSlots.add(slot)) {
                Bukkit.getLogger().warning("[PolaroidDragon] menus.yml slot " + slot
                        + " is outside the inventory size " + inv.getSize() + "; the item was skipped.");
            }
            return;
        }
        safeSet(inv, slot, item);
    }

    private ItemStack simpleItem(Material material, String name, String... loreLines) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        // AIR is an accepted material in menus.yml and has no meta; the item still has to
        // come back placeable so the caller's slot logic stays unchanged.
        if (meta != null) {
            meta.displayName(plain(colorize(name)));
            if (loreLines.length > 0) {
                List<Component> lore = new ArrayList<>();
                for (String line : loreLines) lore.add(plain(colorize(line)));
                meta.lore(lore);
            }
            item.setItemMeta(meta);
        }
        // Single render choke point: every chrome item leaves here marked.
        return marker.mark(item);
    }

    private ItemStack playerHead(UUID uuid, String name, String... loreLines) {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        // An unconditional cast would turn any future meta change into a ClassCastException
        // thrown out of build(); the head without its skin is the acceptable degradation.
        if (item.getItemMeta() instanceof SkullMeta meta) {
            meta.setOwningPlayer(Bukkit.getOfflinePlayer(uuid));
            meta.displayName(plain(colorize(name)));
            if (loreLines.length > 0) {
                List<Component> lore = new ArrayList<>();
                for (String line : loreLines) lore.add(plain(colorize(line)));
                meta.lore(lore);
            }
            item.setItemMeta(meta);
        }
        // Single render choke point: every chrome item leaves here marked.
        return marker.mark(item);
    }

    /**
     * Item names and lore inherit vanilla's purple italic unless a decoration is set
     * explicitly, which overrides whatever menus.yml configured.
     */
    private static Component plain(Component component) {
        return component.decoration(TextDecoration.ITALIC, false);
    }

    private Component colorize(String text) {
        // Rendered straight to a Component. Round-tripping through the legacy
        // serializer would flatten gradients to per-character colours and drop
        // hover/click, which the Polaroid menu styling relies on.
        return ColorUtil.component(text);
    }
}
