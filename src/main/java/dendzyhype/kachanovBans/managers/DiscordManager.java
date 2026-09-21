package dendzyhype.kachanovBans.managers;

import dendzyhype.kachanovBans.KachanovBans;
import dendzyhype.kachanovBans.config.Config;
import dendzyhype.kachanovBans.utils.Utils;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.bukkit.Bukkit;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

public class DiscordManager {

    private final KachanovBans plugin;
    private final boolean debug;

    public DiscordManager(KachanovBans plugin) {
        this.plugin = plugin;
        this.debug = plugin.getPluginConfig().isDebug();
    }

    public void sendPunishment(String type, String targetName, String moderatorName, String reason,
                               long issuedAt, long duration, String ip, Map<String, String> extraPlaceholders) {
        if (!plugin.getPluginConfig().isDiscordEnabled()) {
            if (debug) plugin.getLogger().info("[Debug] Discord отключён в конфиге.");
            return;
        }
        String webhookUrl = plugin.getPluginConfig().getDiscordWebhookUrl();
        if (webhookUrl.isEmpty()) {
            if (debug) plugin.getLogger().warning("[Debug] Discord URL пуст.");
            return;
        }

        Config.DiscordEmbedSettings settings = plugin.getPluginConfig().getEmbedSettings(type.toLowerCase());

        JsonObject embed = new JsonObject();
        embed.addProperty("title", settings.title);
        embed.addProperty("color", settings.color);

        JsonArray fields = new JsonArray();
        for (Map<?, ?> fieldMap : settings.fields) {
            JsonObject field = new JsonObject();
            String name = (String) fieldMap.get("name");
            String value = (String) fieldMap.get("value");
            Boolean inline = (Boolean) fieldMap.get("inline");
            if (name != null && value != null) {
                field.addProperty("name", replacePlaceholders(name, targetName, moderatorName, reason, issuedAt, duration, ip, extraPlaceholders));
                field.addProperty("value", replacePlaceholders(value, targetName, moderatorName, reason, issuedAt, duration, ip, extraPlaceholders));
                if (inline != null) field.addProperty("inline", inline);
                fields.add(field);
            }
        }
        embed.add("fields", fields);

        String footerText = settings.footer;
        if (footerText != null && !footerText.isEmpty()) {
            JsonObject footer = new JsonObject();
            footer.addProperty("text", replacePlaceholders(footerText, targetName, moderatorName, reason, issuedAt, duration, ip, extraPlaceholders));
            embed.add("footer", footer);
        }

        JsonArray embeds = new JsonArray();
        embeds.add(embed);

        JsonObject payload = new JsonObject();
        payload.addProperty("username", plugin.getPluginConfig().getDiscordUsername());
        String avatar = plugin.getPluginConfig().getDiscordAvatarUrl();
        if (avatar != null && !avatar.isEmpty()) payload.addProperty("avatar_url", avatar);
        payload.add("embeds", embeds);

        String json = payload.toString();
        if (debug) plugin.getLogger().info("[Debug] Отправка в Discord: " + json);
        sendWebhook(webhookUrl, json);
    }

    public void sendPunishment(String type, String targetName, String moderatorName, String reason,
                               long issuedAt, long duration, String ip) {
        sendPunishment(type, targetName, moderatorName, reason, issuedAt, duration, ip, null);
    }

    private String replacePlaceholders(String text, String targetName, String moderatorName, String reason,
                                       long issuedAt, long duration, String ip, Map<String, String> extra) {
        String result = text;
        result = result.replace("{player}", targetName != null ? targetName : "?");
        result = result.replace("{moderator}", moderatorName != null ? moderatorName : "Console");
        result = result.replace("{reason}", reason != null ? reason : "Не указана");
        result = result.replace("{time}", Utils.formatDate(issuedAt));
        if (duration > 0) {
            result = result.replace("{expiry}", Utils.formatDate(issuedAt + duration));
            result = result.replace("{duration}", Utils.parseTime(duration));
        } else {
            result = result.replace("{expiry}", "навсегда");
            result = result.replace("{duration}", "0");
        }
        result = result.replace("{ip}", ip != null ? ip : "?");

        if (extra != null) {
            for (Map.Entry<String, String> entry : extra.entrySet()) {
                result = result.replace("{" + entry.getKey() + "}", entry.getValue());
            }
        }
        return result;
    }

    private void sendWebhook(String url, String json) {
        Bukkit.getAsyncScheduler().runNow(
                plugin,
                scheduledTask -> {
                    if (debug) plugin.getLogger().info("[Debug] Отправка POST-запроса на Discord");
                    try {
                        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                        conn.setRequestMethod("POST");
                        conn.setRequestProperty("Content-Type", "application/json");
                        conn.setDoOutput(true);
                        conn.setConnectTimeout(15000);
                        conn.setReadTimeout(15000);

                        try (OutputStream os = conn.getOutputStream()) {
                            os.write(json.getBytes(StandardCharsets.UTF_8));
                        }

                        int code = conn.getResponseCode();
                        if (code >= 200 && code < 300) {
                            if (debug) plugin.getLogger().info("[Debug] Discord ответил с кодом " + code + " (успешно)");
                        } else {
                            try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getErrorStream()))) {
                                StringBuilder sb = new StringBuilder();
                                String line;
                                while ((line = br.readLine()) != null) sb.append(line);
                                plugin.getLogger().warning("Discord ошибка: " + code + " " + sb.toString());
                            }
                        }
                        conn.disconnect();
                    } catch (Exception e) {
                        plugin.getLogger().warning("Ошибка отправки в Discord: " + e.getMessage());
                        if (debug) e.printStackTrace();
                    }
                }
        );
    }
}