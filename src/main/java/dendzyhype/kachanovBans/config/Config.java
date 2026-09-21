package dendzyhype.kachanovBans.config;

import dendzyhype.kachanovBans.KachanovBans;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Config {

    private final KachanovBans plugin;
    private FileConfiguration mainConfig;
    private FileConfiguration messagesConfig;
    private FileConfiguration discordConfig;

    private final Map<String, String> messages = new HashMap<>();

    private String timezone;
    private long checkInterval;
    private int itemsPerPage;
    private String buttonNext;
    private String buttonPrev;
    private String buttonClose;
    private boolean silentEnabled;
    private boolean debug;

    private String storageType;
    private String mysqlHost;
    private int mysqlPort;
    private String mysqlDatabase;
    private String mysqlUser;
    private String mysqlPassword;

    public Config(KachanovBans plugin) {
        this.plugin = plugin;
    }

    public void loadAll() {
        loadMainConfig();
        loadMessagesConfig();
        loadDiscordConfig();
        loadMessageMap();
        if (debug) {
            plugin.getLogger().info("Загружено " + messages.size() + " сообщений из messages.yml");
        }
    }

    private void loadMainConfig() {
        File configFile = new File(plugin.getDataFolder(), "config.yml");
        if (!configFile.exists()) {
            plugin.saveResource("config.yml", false);
        }
        mergeMissingDefaults(configFile, "config.yml");
        plugin.reloadConfig();
        mainConfig = YamlConfiguration.loadConfiguration(configFile);

        timezone = mainConfig.getString("timezone", "Europe/Moscow");
        checkInterval = mainConfig.getLong("check-interval", 1200L);
        itemsPerPage = mainConfig.getInt("pagination.items-per-page", 10);
        buttonNext = mainConfig.getString("pagination.button-next", "<green>[→]</green>");
        buttonPrev = mainConfig.getString("pagination.button-prev", "<green>[←]</green>");
        buttonClose = mainConfig.getString("pagination.button-close", "<red>[✕]</red>");
        silentEnabled = mainConfig.getBoolean("silent-enabled", true);
        debug = mainConfig.getBoolean("debug", false);

        storageType = mainConfig.getString("storage-type", "json").toLowerCase();
        mysqlHost = mainConfig.getString("mysql.host", "localhost");
        mysqlPort = mainConfig.getInt("mysql.port", 3306);
        mysqlDatabase = mainConfig.getString("mysql.database", "kachanovbans");
        mysqlUser = mainConfig.getString("mysql.user", "root");
        mysqlPassword = mainConfig.getString("mysql.password", "");
    }

    private void loadMessagesConfig() {
        File messagesFile = new File(plugin.getDataFolder(), "messages.yml");
        if (!messagesFile.exists()) {
            plugin.saveResource("messages.yml", false);
        }
        mergeMissingDefaults(messagesFile, "messages.yml");
        messagesConfig = YamlConfiguration.loadConfiguration(messagesFile);
    }

    private void loadDiscordConfig() {
        File discordFile = new File(plugin.getDataFolder(), "discord.yml");
        if (!discordFile.exists()) {
            plugin.saveResource("discord.yml", false);
        }
        mergeMissingDefaults(discordFile, "discord.yml");
        discordConfig = YamlConfiguration.loadConfiguration(discordFile);
    }

    private void mergeMissingDefaults(File targetFile, String resourceName) {
        try (InputStream stream = plugin.getResource(resourceName)) {
            if (stream == null) return;
            YamlConfiguration current = new YamlConfiguration();
            current.load(targetFile);
            YamlConfiguration defaults = new YamlConfiguration();
            defaults.load(new InputStreamReader(stream, StandardCharsets.UTF_8));

            int added = 0;
            for (String key : defaults.getKeys(true)) {
                if (defaults.isConfigurationSection(key) || current.contains(key, true)) continue;
                current.set(key, defaults.get(key));
                added++;
            }
            if (added > 0) {
                current.save(targetFile);
                plugin.getLogger().info("Обновлён " + resourceName + ": добавлено отсутствующих параметров — " + added + ".");
            }
        } catch (Exception e) {
            plugin.getLogger().severe("Не удалось безопасно дополнить " + resourceName + ": " + e.getMessage());
        }
    }

    private void loadMessageMap() {
        messages.clear();
        for (String key : messagesConfig.getKeys(true)) {
            if (!messagesConfig.isConfigurationSection(key)) {
                String value = messagesConfig.getString(key);
                if (value != null) {
                    messages.put(key, value);
                }
            }
        }
    }

    public Component getMessage(String key, Object... placeholders) {
        String raw = messages.get(key);
        if (raw == null) {
            if (debug) plugin.getLogger().warning("Сообщение не найдено: " + key);
            return MiniMessage.miniMessage().deserialize("<!i>" + key + " не найден");
        }
        String rendered = replacePlaceholders(raw, placeholders);
        if (shouldPrefix(key)) {
            String prefix = messages.getOrDefault("prefix", "<gradient:#8B5CF6:#22D3EE>KachBans</gradient> <dark_gray>›</dark_gray>");
            rendered = prefix + " " + rendered;
        }
        return MiniMessage.miniMessage().deserialize(rendered);
    }

    private boolean shouldPrefix(String key) {
        return !key.equals("prefix")
                && !key.endsWith("kick-message")
                && !key.endsWith(".header")
                && !key.endsWith(".entry")
                && !key.endsWith(".entry-permanent")
                && !key.equals("mute.target-muted")
                && !key.equals("mute.target-tempmuted")
                && !key.equals("mute.muted-chat")
                && !key.equals("mute.muted-chat-permanent")
                && !key.equals("mute.expired")
                && !key.equals("ban.expired")
                && !key.equals("checkban.banned")
                && !key.equals("checkmute.muted")
                && !key.equals("checkvmute.muted")
                && !key.equals("blacknick.actionbar")
                && !key.equals("blacknick.target-applied")
                && !key.equals("blacknick.progress");
    }

    private String replacePlaceholders(String raw, Object... values) {
        String result = raw;
        for (int i = 0; i < values.length; i++) {
            String placeholder = "{" + i + "}";
            String replacement = values[i] == null ? "null" : values[i].toString();
            result = result.replace(placeholder, MiniMessage.miniMessage().escapeTags(replacement));
        }
        return result;
    }

    public String getTimezone() { return timezone; }
    public long getCheckInterval() { return checkInterval; }
    public int getItemsPerPage() { return itemsPerPage; }
    public String getButtonNext() { return buttonNext; }
    public String getButtonPrev() { return buttonPrev; }
    public String getButtonClose() { return buttonClose; }
    public boolean isSilentEnabled() { return silentEnabled; }
    public boolean isDebug() { return debug; }

    public String getStorageType() { return storageType; }
    public String getMysqlHost() { return mysqlHost; }
    public int getMysqlPort() { return mysqlPort; }
    public String getMysqlDatabase() { return mysqlDatabase; }
    public String getMysqlUser() { return mysqlUser; }
    public String getMysqlPassword() { return mysqlPassword; }

    public boolean isDiscordEnabled() {
        return discordConfig.getBoolean("webhook.enabled", false);
    }
    public String getDiscordWebhookUrl() {
        return discordConfig.getString("webhook.url", "");
    }
    public String getDiscordUsername() {
        return discordConfig.getString("webhook.username", "KachanovBans");
    }
    public String getDiscordAvatarUrl() {
        return discordConfig.getString("webhook.avatar_url", "");
    }
    public int getDiscordDefaultColor() {
        return discordConfig.getInt("webhook.default_color", 0xFF5500);
    }

    public DiscordEmbedSettings getEmbedSettings(String type) {
        String base = "webhook.embeds." + type;
        if (!discordConfig.isConfigurationSection(base)) {
            return new DiscordEmbedSettings(
                    "Наказание",
                    getDiscordDefaultColor(),
                    List.of(),
                    "Время {time}"
            );
        }
        String title = discordConfig.getString(base + ".title", "Наказание");
        int color = discordConfig.getInt(base + ".color", getDiscordDefaultColor());
        List<Map<?, ?>> fieldsRaw = discordConfig.getMapList(base + ".fields");
        String footer = discordConfig.getString(base + ".footer", "");
        return new DiscordEmbedSettings(title, color, fieldsRaw, footer);
    }

    public static class DiscordEmbedSettings {
        public final String title;
        public final int color;
        public final List<Map<?, ?>> fields;
        public final String footer;
        public DiscordEmbedSettings(String title, int color, List<Map<?, ?>> fields, String footer) {
            this.title = title;
            this.color = color;
            this.fields = fields;
            this.footer = footer;
        }
    }

    public void reload() {
        plugin.reloadConfig();
        loadAll();
        plugin.getLogger().info("Конфиги перезагружены.");
    }
}
