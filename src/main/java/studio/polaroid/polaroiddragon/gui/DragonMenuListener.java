package studio.polaroid.polaroiddragon.gui;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;

public class DragonMenuListener implements Listener {

    private final DragonMenu menu;

    public DragonMenuListener(DragonMenu menu) {
        this.menu = menu;
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof DragonMenuHolder holder)) return;
        event.setCancelled(true);

        if (event.getClickedInventory() != event.getView().getTopInventory()) return;
        if (!(event.getWhoClicked() instanceof Player player)) return;

        DragonMenuHolder.View next = menu.navTarget(holder.getView(), event.getSlot());
        if (next != null) {
            player.openInventory(menu.build(next, player));
        }
    }
}
