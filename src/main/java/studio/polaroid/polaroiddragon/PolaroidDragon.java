package studio.polaroid.polaroiddragon;

import studio.polaroid.polaroiddragon.command.DragonCommand;
import studio.polaroid.polaroiddragon.gui.DragonMenu;
import studio.polaroid.polaroiddragon.gui.DragonMenuListener;
import studio.polaroid.polaroiddragon.gui.MenuItemCleanupListener;
import studio.polaroid.polaroiddragon.gui.MenuItemMarker;
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
import org.bukkit.command.PluginCommand;
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

        // Stats must be constructed before DragonManager. The database work
        // itself runs asynchronously so JDBC never blocks the server thread.
        statsManager = new StatsManager(this);
        statsManager.initAsync();
        PendingRewardManager pendingRewardManager = new PendingRewardManager(this);
        EconomyManager economyManager = new EconomyManager(this);
        DiscordWebhookManager discordWebhookManager = new DiscordWebhookManager(this);
        dragonManager = new DragonManager(this, statsManager, pendingRewardManager, economyManager, discordWebhookManager);

        // Listeners
        getServer().getPluginManager().registerEvents(new DragonDamageListener(dragonManager), this);
        getServer().getPluginManager().registerEvents(new DragonDeathListener(dragonManager), this);
        getServer().getPluginManager().registerEvents(new PlayerJoinListener(dragonManager, pendingRewardManager), this);

        // GUI menus
        MenuItemMarker menuItemMarker = new MenuItemMarker(this);
        DragonMenu dragonMenu = new DragonMenu(dragonManager, statsManager, messageManager, menuConfig, menuItemMarker);
        getServer().getPluginManager().registerEvents(
                new DragonMenuListener(this, dragonMenu, menuItemMarker), this);
        getServer().getPluginManager().registerEvents(
                new MenuItemCleanupListener(this, menuItemMarker), this);

        // Command
        PluginCommand command = getCommand("polaroiddragon");
        if (command == null) {
            getLogger().severe("The command 'polaroiddragon' is missing from plugin.yml; "
                    + "commands and tab completion are unavailable.");
        } else {
            DragonCommand dragonCommand = new DragonCommand(this, dragonManager, statsManager, dragonMenu);
            command.setExecutor(dragonCommand);
            command.setTabCompleter(dragonCommand);
        }

        // PlaceholderAPI
        if (getServer().getPluginManager().getPlugin("PlaceholderAPI") != null) {
            new DragonPlaceholder(dragonManager, statsManager).register();
            getLogger().info("PlaceholderAPI detected; placeholders registered.");
        }

        // Scan for an existing dragon after a restart
        dragonManager.scanExistingDragon();

        // Schedule the next event only if no dragon is active
        if (!dragonManager.isEventActive()) {
            dragonManager.scheduleNextEvent();
        }

        getLogger().info("PolaroidDragon enabled.");
    }

    @Override
    public void onDisable() {
        if (dragonManager != null) {
            dragonManager.cancelAll();
        }
        if (statsManager != null) {
            statsManager.close();
        }
        getLogger().info("PolaroidDragon disabled.");
    }

    public static PolaroidDragon getInstance() { return instance; }
    public DragonManager getDragonManager() { return dragonManager; }
    public StatsManager getStatsManager()   { return statsManager; }
    public MessageManager getMessageManager() { return messageManager; }
    public MenuConfig getMenuConfig() { return menuConfig; }
}
