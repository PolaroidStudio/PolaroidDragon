package studio.polaroid.polaroiddragon.gui;

import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

/**
 * Persistent marker written on every item rendered into a menu.
 *
 * <p>All menu slots are chrome, so a marked item is never something a player
 * legitimately owns. Cancelling clicks alone is not enough: an item can still
 * escape a menu through a desync or a plugin interaction, so marked items are
 * deleted wherever they surface.
 */
public final class MenuItemMarker {

    private final NamespacedKey key;

    public MenuItemMarker(Plugin plugin) {
        this.key = new NamespacedKey(plugin, "menu_item");
    }

    /** Marks the item in place and returns it. */
    public ItemStack mark(ItemStack item) {
        if (item == null) {
            return null;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }
        meta.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    public boolean isMarked(ItemStack item) {
        if (item == null) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        return meta != null && meta.getPersistentDataContainer().has(key, PersistentDataType.BYTE);
    }

    /** Removes every marked item from the player's inventory and cursor. */
    public void cleanInventory(Player player) {
        ItemStack[] contents = player.getInventory().getContents();
        boolean changed = false;
        for (int slot = 0; slot < contents.length; slot++) {
            if (isMarked(contents[slot])) {
                player.getInventory().setItem(slot, null);
                changed = true;
            }
        }
        if (isMarked(player.getItemOnCursor())) {
            player.setItemOnCursor(null);
            changed = true;
        }
        if (changed) {
            player.updateInventory();
        }
    }
}
