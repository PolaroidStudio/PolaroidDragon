package studio.polaroid.polaroiddragon.listener;

import studio.polaroid.polaroiddragon.manager.DragonManager;
import org.bukkit.entity.EnderDragon;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;

public class DragonDamageListener implements Listener {

    private final DragonManager dragonManager;

    public DragonDamageListener(DragonManager dragonManager) {
        this.dragonManager = dragonManager;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDragonDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof EnderDragon dragon)) return;
        if (!dragonManager.isEventDragon(dragon)) return;

        Player damager = extractPlayer(event.getDamager());
        if (damager == null) return;

        dragonManager.registerDamage(damager, event.getFinalDamage());
    }

    private Player extractPlayer(org.bukkit.entity.Entity entity) {
        if (entity instanceof Player player) return player;
        if (entity instanceof Projectile proj && proj.getShooter() instanceof Player player) return player;
        return null;
    }
}
