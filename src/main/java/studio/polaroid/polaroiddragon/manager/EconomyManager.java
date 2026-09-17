package studio.polaroid.polaroiddragon.manager;

import studio.polaroid.polaroiddragon.PolaroidDragon;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.RegisteredServiceProvider;

public class EconomyManager {

    private final PolaroidDragon plugin;
    private Economy economy;

    public EconomyManager(PolaroidDragon plugin) {
        this.plugin = plugin;
        hook();
    }

    private void hook() {
        if (economy != null) return;
        if (plugin.getServer().getPluginManager().getPlugin("Vault") == null) return;

        RegisteredServiceProvider<Economy> provider = Bukkit.getServicesManager().getRegistration(Economy.class);
        if (provider != null) {
            economy = provider.getProvider();
            plugin.getLogger().info("Vault detectado — recompensas en dinero habilitadas (" + economy.getName() + ").");
        }
    }

    public boolean isEnabled() {
        hook();
        return economy != null;
    }

    public void deposit(OfflinePlayer player, double amount) {
        if (!isEnabled() || amount <= 0) return;
        economy.depositPlayer(player, amount);
    }

    public String format(double amount) {
        return isEnabled() ? economy.format(amount) : String.format("%,.2f", amount);
    }
}
