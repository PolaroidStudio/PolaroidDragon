package studio.polaroid.polaroiddragon.gui;

import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.Plugin;

/**
 * Deletes menu chrome that escaped a menu, on every path it can reach the world:
 * pickup, drop and a delayed sweep on join for items that survived a restart.
 */
public class MenuItemCleanupListener implements Listener {

    private static final long JOIN_SWEEP_DELAY_TICKS = 10L;

    private final Plugin plugin;
    private final MenuItemMarker marker;

    public MenuItemCleanupListener(Plugin plugin, MenuItemMarker marker) {
        this.plugin = plugin;
        this.marker = marker;
    }

    @EventHandler
    public void onPickup(EntityPickupItemEvent event) {
        if (marker.isMarked(event.getItem().getItemStack())) {
            event.setCancelled(true);
            event.getItem().remove();
        }
    }

    @EventHandler
    public void onDrop(PlayerDropItemEvent event) {
        if (marker.isMarked(event.getItemDrop().getItemStack())) {
            event.getItemDrop().remove();
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Bukkit.getScheduler().runTaskLater(plugin,
                () -> marker.cleanInventory(event.getPlayer()), JOIN_SWEEP_DELAY_TICKS);
    }
}
