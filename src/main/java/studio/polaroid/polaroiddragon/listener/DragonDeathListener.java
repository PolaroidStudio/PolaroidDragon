package studio.polaroid.polaroiddragon.listener;

import studio.polaroid.polaroiddragon.manager.DragonManager;
import org.bukkit.entity.EnderDragon;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;

public class DragonDeathListener implements Listener {

    private final DragonManager dragonManager;

    public DragonDeathListener(DragonManager dragonManager) {
        this.dragonManager = dragonManager;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDragonDeath(EntityDeathEvent event) {
        if (!(event.getEntity() instanceof EnderDragon dragon)) return;
        if (!dragonManager.isEventDragon(dragon)) return;

        dragonManager.handleDragonDeath(dragon, dragon.getKiller());
    }
}
