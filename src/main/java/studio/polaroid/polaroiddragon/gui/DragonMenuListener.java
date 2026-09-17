package studio.polaroid.polaroiddragon.gui;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.plugin.Plugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Hardened listener for the dragon menus.
 *
 * <p>Every slot in these menus is chrome: the player owns nothing in them, so
 * every click and every drag is cancelled unconditionally before any lookup
 * runs, and navigation is only dispatched afterwards.
 */
public class DragonMenuListener implements Listener {

    /** General click debounce. */
    private static final long CLICK_COOLDOWN_MILLIS = 75L;
    /** Longer window for shift-click, because the client emits duplicate packets. */
    private static final long SHIFT_CLICK_COOLDOWN_MILLIS = 200L;

    private final Plugin plugin;
    private final DragonMenu menu;
    private final MenuItemMarker marker;
    private final Map<UUID, Long> lastDispatch = new ConcurrentHashMap<>();

    public DragonMenuListener(Plugin plugin, DragonMenu menu, MenuItemMarker marker) {
        this.plugin = plugin;
        this.menu = menu;
        this.marker = marker;
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onClick(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder() instanceof DragonMenuHolder holder)) return;

        // Cancel first, unconditionally. This also covers clicks in the player's
        // own inventory, where shift-click, number keys and offhand swap would
        // otherwise push a real item into a menu slot.
        event.setCancelled(true);

        if (!(event.getWhoClicked() instanceof Player player)) return;

        ClickType click = event.getClick();
        // DOUBLE_CLICK is a collect-to-cursor; it never carries a navigation intent.
        if (click == ClickType.DOUBLE_CLICK) return;
        // Neither of these goes through the normal dispatch chain; drop them.
        if (click == ClickType.UNKNOWN || click == ClickType.CREATIVE) return;

        int rawSlot = event.getRawSlot();
        if (rawSlot < 0 || rawSlot >= top.getSize()) return;

        long cooldown = click == ClickType.SHIFT_LEFT
                ? SHIFT_CLICK_COOLDOWN_MILLIS
                : CLICK_COOLDOWN_MILLIS;
        if (isOnCooldown(player, cooldown)) return;

        DragonMenuHolder.View next = menu.navTarget(holder.getView(), rawSlot);
        if (next == null) return;

        markDispatched(player);
        // Opening an inventory from inside InventoryClickEvent desyncs the
        // client and leaves a ghost cursor item. Always defer a tick.
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) {
                player.openInventory(menu.build(next, player));
            }
        });
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onDrag(InventoryDragEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder() instanceof DragonMenuHolder)) return;

        // Every slot of this window is chrome, so any drag touching it is cancelled.
        for (int rawSlot : event.getRawSlots()) {
            if (rawSlot >= 0 && rawSlot < top.getSize()) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof DragonMenuHolder)) return;
        lastDispatch.remove(event.getPlayer().getUniqueId());
        if (event.getPlayer() instanceof Player player) {
            // Sweep a few ticks later so chrome that landed in the player
            // inventory is cleared and the client resyncs.
            Bukkit.getScheduler().runTaskLater(plugin, () -> marker.cleanInventory(player), 2L);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        lastDispatch.remove(event.getPlayer().getUniqueId());
    }

    private boolean isOnCooldown(Player player, long window) {
        Long last = lastDispatch.get(player.getUniqueId());
        return last != null && System.currentTimeMillis() - last < window;
    }

    private void markDispatched(Player player) {
        lastDispatch.put(player.getUniqueId(), System.currentTimeMillis());
    }
}
