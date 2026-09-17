package studio.polaroid.polaroiddragon;

import studio.polaroid.polaroiddragon.command.DragonCommand;
import studio.polaroid.polaroiddragon.gui.DragonMenu;
import studio.polaroid.polaroiddragon.gui.DragonMenuListener;
import studio.polaroid.polaroiddragon.listener.DragonDamageListener;
import studio.polaroid.polaroiddragon.listener.DragonDeathListener;
import studio.polaroid.polaroiddragon.listener.PlayerJoinListener;
import studio.polaroid.polaroiddragon.manager.DiscordWebhookManager;
import studio.polaroid.polaroiddragon.manager.DragonManager;
import studio.polaroid.polaroiddragon.manager.EconomyManager;
import studio.polaroid.polaroiddragon.manager.MenuConfig;
import studio.polaroid.polaroiddragon.manager.MessageManager;
import studio.polaroid.polaroiddragon.manager.PendingRewardManager;
import studio.polaroid.polaroiddragon.manager.StatsManager;
import studio.polaroid.polaroiddragon.placeholder.DragonPlaceholder;
import org.bukkit.plugin.java.JavaPlugin;

public class PolaroidDragon extends JavaPlugin {

    private static PolaroidDragon instance;
    private DragonManager dragonManager;
    private StatsManager statsManager;
    private MessageManager messageManager;
    private MenuConfig menuConfig;

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();
        messageManager = new MessageManager(this);
        menuConfig = new MenuConfig(this);

        // Stats debe inicializarse antes que DragonManager
        statsManager = new StatsManager(this);
        PendingRewardManager pendingRewardManager = new PendingRewardManager(this);
        EconomyManager economyManager = new EconomyManager(this);
        DiscordWebhookManager discordWebhookManager = new DiscordWebhookManager(this);
        dragonManager = new DragonManager(this, statsManager, pendingRewardManager, economyManager, discordWebhookManager);

        // Listeners
        getServer().getPluginManager().registerEvents(new DragonDamageListener(dragonManager), this);
        getServer().getPluginManager().registerEvents(new DragonDeathListener(dragonManager), this);
        getServer().getPluginManager().registerEvents(new PlayerJoinListener(dragonManager, pendingRewardManager), this);

        // Menú GUI
        DragonMenu dragonMenu = new DragonMenu(dragonManager, statsManager, messageManager, menuConfig);
        getServer().getPluginManager().registerEvents(new DragonMenuListener(dragonMenu), this);

        // Comando
        getCommand("polaroiddragon").setExecutor(new DragonCommand(this, dragonManager, statsManager, dragonMenu));

        // PlaceholderAPI
        if (getServer().getPluginManager().getPlugin("PlaceholderAPI") != null) {
            new DragonPlaceholder(dragonManager, statsManager).register();
            getLogger().info("PlaceholderAPI detectado — placeholders registrados.");
        }

        // Escanear dragón existente tras reinicio
        dragonManager.scanExistingDragon();

        // Programar próximo evento solo si no hay dragón activo
        if (!dragonManager.isEventActive()) {
            dragonManager.scheduleNextEvent();
        }

        getLogger().info("PolaroidDragon habilitado correctamente.");
    }

    @Override
    public void onDisable() {
        dragonManager.cancelAll();
        if (statsManager != null) {
            statsManager.close();
        }
        getLogger().info("PolaroidDragon deshabilitado.");
    }

    public static PolaroidDragon getInstance() { return instance; }
    public DragonManager getDragonManager() { return dragonManager; }
    public StatsManager getStatsManager()   { return statsManager; }
    public MessageManager getMessageManager() { return messageManager; }
    public MenuConfig getMenuConfig() { return menuConfig; }
}
