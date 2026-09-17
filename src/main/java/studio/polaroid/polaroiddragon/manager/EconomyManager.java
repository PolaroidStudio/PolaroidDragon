package studio.polaroid.polaroiddragon.manager;

import studio.polaroid.polaroiddragon.PolaroidDragon;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.RegisteredServiceProvider;

/**
 * Vault economy hook.
 *
 * <p>The provider is resolved lazily but only probed once: Vault can register
 * its economy after this plugin enables, yet re-running the service lookup on
 * every {@code isEnabled()} call meant two plugin-manager lookups per rewarded
 * player on servers without Vault.
 */
public class EconomyManager {

    private final PolaroidDragon plugin;

    /** Published to the reward path; written once on a successful hook. */
    private volatile Economy economy;

    /** True once a hook attempt found no provider, so it is not retried per call. */
    private volatile boolean probed = false;

    public EconomyManager(PolaroidDragon plugin) {
        this.plugin = plugin;
        hook();
    }

    /** Re-runs the service lookup. Called on /reload so a late Vault is picked up. */
    public void hook() {
        if (economy != null) return;
        if (plugin.getServer().getPluginManager().getPlugin("Vault") == null) {
            probed = true;
            return;
        }

        RegisteredServiceProvider<Economy> provider = Bukkit.getServicesManager().getRegistration(Economy.class);
        if (provider != null) {
            economy = provider.getProvider();
            plugin.getLogger().info("Vault detected — money rewards enabled (" + economy.getName() + ").");
        } else {
            plugin.getLogger().warning("Vault is installed but no economy provider is registered; "
                    + "money rewards are disabled.");
        }
        probed = true;
    }

    public boolean isEnabled() {
        if (economy == null && !probed) hook();
        return economy != null;
    }

    /**
     * Deposits money and reports whether it actually landed.
     *
     * <p>Vault returns a failure response for an unknown account or an offline
     * economy backend. Discarding it meant a player was told they had been paid
     * when nothing was transferred.
     *
     * @return true when the transaction succeeded
     */
    public boolean deposit(OfflinePlayer player, double amount) {
        if (!isEnabled() || amount <= 0) return false;

        try {
            EconomyResponse response = economy.depositPlayer(player, amount);
            if (response == null || response.transactionSuccess()) return true;

            plugin.getLogger().warning("Economy deposit of " + amount + " to "
                    + player.getName() + " failed: " + response.errorMessage);
            return false;
        } catch (Exception e) {
            plugin.getLogger().warning("Economy deposit of " + amount + " to "
                    + player.getName() + " threw: " + e.getMessage());
            return false;
        }
    }

    public String format(double amount) {
        return isEnabled() ? economy.format(amount) : String.format("%,.2f", amount);
    }
}
