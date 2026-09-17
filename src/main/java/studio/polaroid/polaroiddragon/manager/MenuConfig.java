package studio.polaroid.polaroiddragon.manager;

import studio.polaroid.polaroiddragon.PolaroidDragon;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.List;
import java.util.logging.Level;

public class MenuConfig {

    private final PolaroidDragon plugin;
    private final File menuFile;
    private FileConfiguration config;

    public MenuConfig(PolaroidDragon plugin) {
        this.plugin = plugin;
        this.menuFile = new File(plugin.getDataFolder(), "menus.yml");
        load();
    }

    public void reload() {
        load();
    }

    private void load() {
        if (!menuFile.exists()) {
            plugin.saveResource("menus.yml", false);
        }
        config = YamlConfiguration.loadConfiguration(menuFile);
    }

    // ─────────────────────────────────────────────
    //  INFO — title, size, filler
    // ─────────────────────────────────────────────

    public String getInfoTitle() {
        return config.getString("info.title", "<dark_gray><b>ᴇsᴛᴀᴅᴏ ᴅᴇʟ ᴅʀᴀɢᴏɴ");
    }

    public int getInfoSize() {
        return validSize(config.getInt("info.size", 27));
    }

    public boolean isInfoFillerEnabled() {
        return config.getBoolean("info.filler.enabled", true);
    }

    public Material getInfoFillerMaterial() {
        return parseMaterial("info.filler.material", Material.BLACK_STAINED_GLASS_PANE);
    }

    public String getInfoFillerName() {
        return config.getString("info.filler.name", " ");
    }

    /** Hides the filler tooltip so an empty box does not trail the cursor. */
    public boolean isInfoFillerTooltipHidden() {
        return config.getBoolean("info.filler.hide-tooltip", true);
    }

    // ─────────────────────────────────────────────
    //  INFO — items
    // ─────────────────────────────────────────────

    public int getInfoStatusSlot() {
        return config.getInt("info.items.status.slot", 13);
    }

    public Material getInfoActiveMaterial() {
        return parseMaterial("info.items.status.material-active", Material.DRAGON_HEAD);
    }

    public Material getInfoInactiveMaterial() {
        return parseMaterial("info.items.status.material-inactive", Material.BARRIER);
    }

    public int getInfoNavToTopSlot() {
        return config.getInt("info.items.nav-to-top.slot", 22);
    }

    public Material getInfoNavToTopMaterial() {
        return parseMaterial("info.items.nav-to-top.material", Material.NETHER_STAR);
    }

    // ─────────────────────────────────────────────
    //  TOP EVENT — title, size, filler
    // ─────────────────────────────────────────────

    public String getTopEventTitle() {
        return config.getString("top-event.title", "<dark_gray><b>ᴛᴏᴘ ᴅᴇʟ ᴇᴠᴇɴᴛᴏ");
    }

    public int getTopEventSize() {
        return validSize(config.getInt("top-event.size", 27));
    }

    public boolean isTopEventFillerEnabled() {
        return config.getBoolean("top-event.filler.enabled", true);
    }

    public Material getTopEventFillerMaterial() {
        return parseMaterial("top-event.filler.material", Material.BLACK_STAINED_GLASS_PANE);
    }

    public String getTopEventFillerName() {
        return config.getString("top-event.filler.name", " ");
    }

    /** Hides the filler tooltip so an empty box does not trail the cursor. */
    public boolean isTopEventFillerTooltipHidden() {
        return config.getBoolean("top-event.filler.hide-tooltip", true);
    }

    // ─────────────────────────────────────────────
    //  TOP EVENT — items
    // ─────────────────────────────────────────────

    public List<Integer> getTopEventRankingSlots() {
        List<Integer> slots = config.getIntegerList("top-event.items.ranking.slots");
        return slots.isEmpty() ? List.of(10, 11, 12, 13, 14) : slots;
    }

    public int getTopEventMyPositionSlot() {
        return config.getInt("top-event.items.my-position.slot", 4);
    }

    public int getTopEventEmptySlot() {
        return config.getInt("top-event.items.empty.slot", 13);
    }

    public Material getTopEventEmptyMaterial() {
        return parseMaterial("top-event.items.empty.material", Material.BARRIER);
    }

    public int getTopEventNavToHallSlot() {
        return config.getInt("top-event.items.nav-to-hall.slot", 18);
    }

    public Material getTopEventNavToHallMaterial() {
        return parseMaterial("top-event.items.nav-to-hall.material", Material.NETHER_STAR);
    }

    public int getTopEventNavToInfoSlot() {
        return config.getInt("top-event.items.nav-to-info.slot", 22);
    }

    public Material getTopEventNavToInfoMaterial() {
        return parseMaterial("top-event.items.nav-to-info.material", Material.ARROW);
    }

    // ─────────────────────────────────────────────
    //  HALL OF FAME — title, size, filler
    // ─────────────────────────────────────────────

    public String getHallOfFameTitle() {
        return config.getString("hall-of-fame.title", "<dark_gray><b>sᴀʟᴏɴ ᴅᴇ ʟᴀ ғᴀᴍᴀ");
    }

    public int getHallOfFameSize() {
        return validSize(config.getInt("hall-of-fame.size", 27));
    }

    public boolean isHallOfFameFillerEnabled() {
        return config.getBoolean("hall-of-fame.filler.enabled", true);
    }

    public Material getHallOfFameFillerMaterial() {
        return parseMaterial("hall-of-fame.filler.material", Material.BLACK_STAINED_GLASS_PANE);
    }

    public String getHallOfFameFillerName() {
        return config.getString("hall-of-fame.filler.name", " ");
    }

    /** Hides the filler tooltip so an empty box does not trail the cursor. */
    public boolean isHallOfFameFillerTooltipHidden() {
        return config.getBoolean("hall-of-fame.filler.hide-tooltip", true);
    }

    // ─────────────────────────────────────────────
    //  HALL OF FAME — items
    // ─────────────────────────────────────────────

    public List<Integer> getHallOfFameRankingSlots() {
        List<Integer> slots = config.getIntegerList("hall-of-fame.items.ranking.slots");
        return slots.isEmpty() ? List.of(10, 11, 12, 13, 14) : slots;
    }

    public int getHallOfFameMyPositionSlot() {
        return config.getInt("hall-of-fame.items.my-position.slot", 4);
    }

    public int getHallOfFameEmptySlot() {
        return config.getInt("hall-of-fame.items.empty.slot", 13);
    }

    public Material getHallOfFameEmptyMaterial() {
        return parseMaterial("hall-of-fame.items.empty.material", Material.BARRIER);
    }

    public int getHallOfFameNavToTopSlot() {
        return config.getInt("hall-of-fame.items.nav-to-top.slot", 18);
    }

    public Material getHallOfFameNavToTopMaterial() {
        return parseMaterial("hall-of-fame.items.nav-to-top.material", Material.NETHER_STAR);
    }

    public int getHallOfFameNavToInfoSlot() {
        return config.getInt("hall-of-fame.items.nav-to-info.slot", 22);
    }

    public Material getHallOfFameNavToInfoMaterial() {
        return parseMaterial("hall-of-fame.items.nav-to-info.material", Material.ARROW);
    }

    // ─────────────────────────────────────────────
    //  UTIL
    // ─────────────────────────────────────────────

    private Material parseMaterial(String path, Material fallback) {
        String value = config.getString(path);
        if (value == null) return fallback;
        Material mat = Material.matchMaterial(value);
        if (mat == null) {
            plugin.getLogger().log(Level.WARNING,
                    "menus.yml: invalid material at ''{0}'': {1}. Using {2}.",
                    new Object[]{path, value, fallback.name()});
            return fallback;
        }
        return mat;
    }

    private int validSize(int size) {
        if (size % 9 != 0 || size < 9 || size > 54) {
            plugin.getLogger().log(Level.WARNING,
                    "menus.yml: invalid size ({0}), using 27.", size);
            return 27;
        }
        return size;
    }
}
