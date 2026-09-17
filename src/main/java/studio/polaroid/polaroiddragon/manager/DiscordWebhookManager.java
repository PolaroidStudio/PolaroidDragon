package studio.polaroid.polaroiddragon.manager;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import studio.polaroid.polaroiddragon.PolaroidDragon;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

public class DiscordWebhookManager {

    private final PolaroidDragon plugin;
    private final File configFile;
    private final HttpClient httpClient;
    private FileConfiguration config;

    public DiscordWebhookManager(PolaroidDragon plugin) {
        this.plugin = plugin;
        this.configFile = new File(plugin.getDataFolder(), "webhook.yml");
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        load();
    }

    public void reload() {
        load();
    }

    private void load() {
        if (!configFile.exists()) {
            plugin.saveResource("webhook.yml", false);
        }
        config = YamlConfiguration.loadConfiguration(configFile);
    }

    public void sendSpawn() {
        send("spawn", Map.of());
    }

    public void sendDeath(Map<String, String> placeholders) {
        send("death", placeholders);
    }

    public void sendTimeout(Map<String, String> placeholders) {
        send("timeout", placeholders);
    }

    private void send(String section, Map<String, String> placeholders) {
        if (!config.getBoolean("enabled", false)) return;
        if (!config.getBoolean(section + ".enabled", false)) return;

        String url = config.getString("url", "");
        if (url == null || url.isBlank()) return;

        String title = apply(config.getString(section + ".embed.title", ""), placeholders);
        String description = apply(config.getString(section + ".embed.description", ""), placeholders);
        int color = parseColor(config.getString(section + ".embed.color", "#AA33FF"));

        JsonObject embed = new JsonObject();
        embed.addProperty("title", title);
        embed.addProperty("description", description);
        embed.addProperty("color", color);

        JsonArray embeds = new JsonArray();
        embeds.add(embed);

        JsonObject payload = new JsonObject();
        String username = config.getString("username", "");
        if (username != null && !username.isBlank()) payload.addProperty("username", username);
        String avatarUrl = config.getString("avatar-url", "");
        if (avatarUrl != null && !avatarUrl.isBlank()) payload.addProperty("avatar_url", avatarUrl);
        payload.add("embeds", embeds);

        // Building the request is synchronous, so a malformed URL would throw on
        // the caller's thread and abort whatever it was doing (reward payout, for
        // one) part-way through. A bad webhook URL must never cost a player a
        // reward, so it is contained here and only disables the notification.
        HttpRequest request;
        try {
            request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
                    .build();
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning("Invalid Discord webhook URL in webhook.yml ("
                    + e.getMessage() + "); the notification was skipped.");
            return;
        }

        httpClient.sendAsync(request, HttpResponse.BodyHandlers.discarding())
                .exceptionally(ex -> {
                    plugin.getLogger().warning("Could not send the Discord webhook: " + ex.getMessage());
                    return null;
                });
    }

    private String apply(String text, Map<String, String> placeholders) {
        if (text == null) return "";
        String result = text;
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            result = result.replace(entry.getKey(), entry.getValue());
        }
        return result;
    }

    private int parseColor(String hex) {
        try {
            return Integer.parseInt(hex.replace("#", ""), 16);
        } catch (Exception e) {
            return 0xAA33FF;
        }
    }
}
