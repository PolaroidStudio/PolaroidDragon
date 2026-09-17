package studio.polaroid.polaroiddragon.listener;

import studio.polaroid.polaroiddragon.manager.DragonManager;
import studio.polaroid.polaroiddragon.manager.PendingRewardManager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

public class PlayerJoinListener implements Listener {

    private final DragonManager dragonManager;
    private final PendingRewardManager pendingRewardManager;

    public PlayerJoinListener(DragonManager dragonManager, PendingRewardManager pendingRewardManager) {
        this.dragonManager = dragonManager;
        this.pendingRewardManager = pendingRewardManager;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (dragonManager.isEventActive()) {
            dragonManager.addPlayerToBossBar(event.getPlayer());
        }
        pendingRewardManager.deliver(event.getPlayer());
    }
}
