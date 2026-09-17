package studio.polaroid.polaroiddragon.hook;

import studio.polaroid.polaroiddragon.PolaroidDragon;
import de.oliver.fancynpcs.api.FancyNpcsPlugin;
import de.oliver.fancynpcs.api.Npc;
import de.oliver.fancynpcs.api.skins.SkinData;
import de.oliver.fancynpcs.api.skins.SkinGeneratedEvent;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Optional FancyNpcs integration: points an existing NPC at a remembered hunter.
 *
 * <p>This hook deliberately never creates, moves or deletes an NPC. The server
 * owner builds and places the NPC with FancyNpcs' own commands and writes its
 * name into the config; all this class does is swap that NPC's skin and display
 * name. Owning the NPC's lifecycle would mean fighting FancyNpcs over its own
 * save file and silently resurrecting NPCs the owner deleted on purpose.
 *
 * <p>The whole class is isolated behind {@link #isAvailable()} so that no
 * FancyNpcs class is ever loaded on a server without the plugin — this is also
 * why every public method takes plain Java types, letting callers stay free of
 * FancyNpcs imports.
 *
 * <p><b>Skins are asynchronous.</b> {@code SkinManager.getByUsername} returns
 * null on a cache miss and fetches in the background, so a request that misses
 * is parked here and finished when {@link SkinGeneratedEvent} fires. That event
 * is constructed off the main thread, hence the hop back before touching an NPC.
 *
 * <p>Nothing here throws into the caller: an NPC cosmetic is never worth failing
 * a reward payout over, so every failure is swallowed and logged.
 */
public class FancyNpcsHook implements Listener {

    /** The token replaced with the hunter's name in a configured display name. */
    private static final String PLAYER_TOKEN = "%player%";

    private final PolaroidDragon plugin;

    /**
     * Skin fetches still in flight, keyed by lower-cased username. A cache miss
     * parks the target NPC name here; {@link SkinGeneratedEvent} drains it.
     * Concurrent because the event fires from FancyNpcs' fetch thread.
     */
    private final Map<String, String> pendingSkins = new ConcurrentHashMap<>();

    public FancyNpcsHook(PolaroidDragon plugin) {
        this.plugin = plugin;
    }

    /**
     * True when FancyNpcs is installed and enabled.
     *
     * <p>Every entry point checks this first: {@link FancyNpcsPlugin#get()}
     * throws a NullPointerException rather than returning null when the plugin
     * is absent.
     */
    public static boolean isAvailable() {
        return Bukkit.getPluginManager().isPluginEnabled("FancyNpcs");
    }

    /**
     * Applies a hunter to an existing NPC.
     *
     * @param npcName      the FancyNpcs NPC name; blank skips this NPC entirely
     * @param playerName   the hunter whose skin and name to show; blank is a no-op
     * @param displayName  the MiniMessage display name, where {@code %player%}
     *                     is replaced with {@code playerName}
     */
    public void apply(String npcName, String playerName, String displayName) {
        if (!isAvailable()) return;
        if (npcName == null || npcName.isBlank()) return;
        if (playerName == null || playerName.isBlank()) return;

        try {
            Npc npc = FancyNpcsPlugin.get().getNpcManager().getNpc(npcName);
            if (npc == null) {
                plugin.getLogger().warning("FancyNpcs NPC '" + npcName
                        + "' does not exist; create it in-game or clear its name in config.yml.");
                return;
            }

            if (displayName != null && !displayName.isBlank()) {
                npc.getData().setDisplayName(displayName.replace(PLAYER_TOKEN, playerName));
            }

            // Never NpcData.setSkin(String): it dereferences the fetch result
            // without a null check and throws on every cache miss. Fetch here,
            // null-check, and let the event finish a miss.
            SkinData skin = FancyNpcsPlugin.get().getSkinManager()
                    .getByUsername(playerName, SkinData.SkinVariant.AUTO);

            if (skin != null) {
                npc.getData().setSkinData(skin);
            } else {
                pendingSkins.put(playerName.toLowerCase(Locale.ROOT), npcName);
            }

            refresh(npc);
        } catch (Exception e) {
            plugin.getLogger().warning("Could not update FancyNpcs NPC '" + npcName + "': " + e.getMessage());
        }
    }

    /** Completes a skin request that missed the cache and was fetched in the background. */
    @EventHandler
    public void onSkinGenerated(SkinGeneratedEvent event) {
        String npcName = pendingSkins.remove(event.getId().toLowerCase(Locale.ROOT));
        if (npcName == null) return;

        SkinData skin = event.getSkin();
        if (skin == null) {
            plugin.getLogger().warning("FancyNpcs could not generate the skin for '"
                    + event.getId() + "'; NPC '" + npcName + "' keeps its previous skin.");
            return;
        }

        // The event is constructed asynchronously, so NPC state is touched only
        // after hopping back onto the main thread.
        Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                Npc npc = FancyNpcsPlugin.get().getNpcManager().getNpc(npcName);
                if (npc == null) return;
                npc.getData().setSkinData(skin);
                refresh(npc);
            } catch (Exception e) {
                plugin.getLogger().warning("Could not apply the fetched skin to NPC '"
                        + npcName + "': " + e.getMessage());
            }
        });
    }

    /**
     * Pushes the changed data to every viewer.
     *
     * <p>{@code updateForAll()} is enough for a display name, but whether it
     * makes a client re-read a skin depends on the server build: a skin lives in
     * the player-info entry, which some clients only re-read when the entity is
     * re-added. {@code npc.respawn-on-skin-change} exists so an owner who sees a
     * stale skin can force the remove/spawn cycle without a plugin change.
     */
    private void refresh(Npc npc) {
        if (plugin.getConfig().getBoolean("npc.respawn-on-skin-change", false)) {
            npc.removeForAll();
            npc.spawnForAll();
        } else {
            npc.updateForAll();
        }
    }
}
