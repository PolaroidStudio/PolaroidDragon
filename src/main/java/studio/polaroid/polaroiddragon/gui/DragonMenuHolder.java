package studio.polaroid.polaroiddragon.gui;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;

public class DragonMenuHolder implements InventoryHolder {

    public enum View { INFO, TOP_EVENT, HALL_OF_FAME }

    private final View view;
    private Inventory inventory;

    public DragonMenuHolder(View view) {
        this.view = view;
    }

    public View getView() {
        return view;
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }

    public void setInventory(Inventory inventory) {
        this.inventory = inventory;
    }
}
