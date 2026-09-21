package dendzyhype.kachanovBans.security;

import dendzyhype.kachanovBans.KachanovBans;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

final class ExternalDiscordLinkStore {
    private static final Pattern SQL_IDENTIFIER = Pattern.compile("[A-Za-z0-9_]+");
    private final KachanovBans plugin;
    private final Map<String, CachedValue> playerCache = new ConcurrentHashMap<>();
    private final Map<String, CachedValue> discordCache = new ConcurrentHashMap<>();
    private final AtomicLong nextErrorLogAt = new AtomicLong();

    ExternalDiscordLinkStore(KachanovBans plugin) {
        this.plugin = plugin;
    }

    boolean enabled() {
        return plugin.getConfig().getBoolean("security.discord-link-database.enabled", false);
    }

    String discordIdByPlayer(String playerName) {
        if (!enabled() || playerName == null || playerName.isBlank()) return null;
        String key = playerName.toLowerCase(Locale.ROOT);
        CachedValue cached = playerCache.get(key);
        if (cached != null && cached.valid()) return cached.value;
        String value = query(column("discord-id-column", "discord_id"), column("player-column", "player"), playerName);
        cache(playerCache, key, value);
        if (value != null) cache(discordCache, value, playerName);
        return value;
    }

    String playerByDiscordId(String discordId) {
        if (!enabled() || discordId == null || discordId.isBlank()) return null;
        CachedValue cached = discordCache.get(discordId);
        if (cached != null && cached.valid()) return cached.value;
        String value = query(column("player-column", "player"), column("discord-id-column", "discord_id"), discordId);
        cache(discordCache, discordId, value);
        if (value != null) cache(playerCache, value.toLowerCase(Locale.ROOT), discordId);
        return value;
    }

    private String query(String selectedColumn, String lookupColumn, String value) {
        try {
            String table = column("table", "granted_passes");
            String sql = "SELECT `" + selectedColumn + "` FROM `" + table + "` WHERE LOWER(`" + lookupColumn + "`) = LOWER(?) LIMIT 1";
            try (Connection connection = DriverManager.getConnection(url(), user(), password());
             PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, value);
                try (ResultSet result = statement.executeQuery()) {
                    if (!result.next()) return null;
                    String found = result.getString(1);
                    return found == null || found.isBlank() ? null : found.trim();
                }
            }
        } catch (Exception error) {
            long now = System.currentTimeMillis();
            long next = nextErrorLogAt.get();
            if (now >= next && nextErrorLogAt.compareAndSet(next, now + 60_000L)) {
                plugin.getLogger().severe("Не удалось проверить Discord-привязку в velocity.granted_passes: " + error.getMessage());
            }
            return null;
        }
    }

    private String url() {
        String host = plugin.getConfig().getString("security.discord-link-database.host", "127.0.0.1");
        int port = plugin.getConfig().getInt("security.discord-link-database.port", 3306);
        String database = column("database", "velocity");
        int timeoutMs = Math.max(1, plugin.getConfig().getInt("security.discord-link-database.connect-timeout-seconds", 3)) * 1000;
        return "jdbc:mysql://" + host + ":" + port + "/" + database
                + "?useSSL=false&characterEncoding=UTF-8&connectTimeout=" + timeoutMs + "&socketTimeout=" + timeoutMs;
    }

    private String user() {
        return plugin.getConfig().getString("security.discord-link-database.user", "root");
    }

    private String password() {
        return plugin.getConfig().getString("security.discord-link-database.password", "");
    }

    private String column(String key, String fallback) {
        String value = plugin.getConfig().getString("security.discord-link-database." + key, fallback);
        if (value == null || !SQL_IDENTIFIER.matcher(value).matches()) {
            throw new IllegalArgumentException("Недопустимый SQL-идентификатор: " + key);
        }
        return value;
    }

    private void cache(Map<String, CachedValue> cache, String key, String value) {
        long seconds = Math.max(0, plugin.getConfig().getLong("security.discord-link-database.cache-seconds", 15));
        if (seconds > 0) cache.put(key, new CachedValue(value, System.currentTimeMillis() + seconds * 1000L));
    }

    private record CachedValue(String value, long expiresAt) {
        boolean valid() { return expiresAt > System.currentTimeMillis(); }
    }
}
