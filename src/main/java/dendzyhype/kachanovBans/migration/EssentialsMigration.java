package dendzyhype.kachanovBans.migration;

import dendzyhype.kachanovBans.KachanovBans;
import dendzyhype.kachanovBans.data.IDatabase;
import dendzyhype.kachanovBans.data.Models;
import org.bukkit.BanEntry;
import org.bukkit.BanList;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.Date;

public final class EssentialsMigration {

    public record Result(int bans, int mutes, int skipped, int errors, boolean essentialsDataFound) {}

    private final KachanovBans plugin;
    private final IDatabase database;

    public EssentialsMigration(KachanovBans plugin) {
        this.plugin = plugin;
        this.database = plugin.getDatabase();
    }

    @SuppressWarnings("deprecation")
    public Result migrate() {
        int bans = 0;
        int mutes = 0;
        int skipped = 0;
        int errors = 0;
        long now = System.currentTimeMillis();

        for (BanEntry<?> entry : Bukkit.getBanList(BanList.Type.NAME).getBanEntries()) {
            try {
                String playerName = String.valueOf(entry.getTarget());
                Date expiration = entry.getExpiration();
                long expiry = expiration == null ? 0 : expiration.getTime();
                if (playerName.isBlank() || (expiry != 0 && expiry <= now)) {
                    skipped++;
                    continue;
                }
                Models.Ban current = database.getActiveBan(playerName);
                if (current != null && (current.expiry() == 0 || current.expiry() > now)) {
                    skipped++;
                    continue;
                }
                if (current != null) database.deactivateBan(playerName);
                long issuedAt = entry.getCreated() == null ? now : entry.getCreated().getTime();
                String source = valueOr(entry.getSource(), "Essentials");
                String reason = valueOr(entry.getReason(), "Импортировано из Essentials");
                database.addBan(playerName, source, reason, issuedAt, expiry);
                database.addHistory(playerName, "BAN_IMPORT_ESSENTIALS", source, reason, issuedAt,
                        expiry == 0 ? 0 : Math.max(0, expiry - issuedAt), null);
                bans++;
            } catch (Exception exception) {
                errors++;
                plugin.getLogger().warning("Не удалось импортировать Essentials-бан: " + exception.getMessage());
            }
        }

        File userdata = new File(plugin.getDataFolder().getParentFile(), "Essentials/userdata");
        File[] files = userdata.listFiles((dir, name) -> name.toLowerCase().endsWith(".yml"));
        boolean essentialsDataFound = files != null;
        if (files != null) {
            for (File file : files) {
                try {
                    YamlConfiguration user = YamlConfiguration.loadConfiguration(file);
                    if (!user.getBoolean("muted", false)) continue;
                    String playerName = user.getString("last-account-name", "").trim();
                    if (playerName.isBlank()) {
                        skipped++;
                        continue;
                    }
                    long muteUntil = user.getLong("mute-time", 0L);
                    if (muteUntil > 0 && muteUntil <= now) {
                        skipped++;
                        continue;
                    }
                    Models.Mute current = database.getActiveMute(playerName);
                    if (current != null && (current.expiry() == 0 || current.expiry() > 0)) {
                        skipped++;
                        continue;
                    }
                    if (current != null) database.deactivateMute(playerName);
                    long remaining = muteUntil <= 0 ? 0 : muteUntil - now;
                    String reason = valueOr(user.getString("mute-reason"), "Импортировано из Essentials");
                    database.addMute(playerName, "Essentials", reason, now, remaining);
                    database.addHistory(playerName, "MUTE_IMPORT_ESSENTIALS", "Essentials", reason, now, remaining, null);
                    mutes++;
                } catch (Exception exception) {
                    errors++;
                    plugin.getLogger().warning("Не удалось импортировать " + file.getName() + ": " + exception.getMessage());
                }
            }
        }
        return new Result(bans, mutes, skipped, errors, essentialsDataFound);
    }

    private String valueOr(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
