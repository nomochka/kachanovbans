package dendzyhype.kachanovBans.managers;

import dendzyhype.kachanovBans.KachanovBans;
import dendzyhype.kachanovBans.data.IDatabase;
import dendzyhype.kachanovBans.data.Models;
import dendzyhype.kachanovBans.utils.Utils;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.configuration.ConfigurationSection;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.Comparator;

public class PunishmentManager {

    private final KachanovBans plugin;
    private final IDatabase db;
    private final boolean debug;

    public PunishmentManager(KachanovBans plugin) {
        this.plugin = plugin;
        this.db = plugin.getDatabase();
        this.debug = plugin.getPluginConfig().isDebug();
    }

    public boolean kickPlayer(String playerName, String moderatorName, String reason, boolean silent) {
        if (rejectProtected(playerName, moderatorName)) return false;
        if (Bukkit.getPlayerExact(playerName) == null) return false;
        boolean publishExternally = shouldPublishPunishment(moderatorName, silent);
        return applyKick(playerName, moderatorName, reason, silent, publishExternally);
    }

    private boolean applyKick(String playerName, String moderatorName, String reason, boolean silent, boolean publishExternally) {
        if (rejectProtected(playerName, moderatorName)) return false;
        Player player = Bukkit.getPlayerExact(playerName);
        if (player == null) return false;
        long now = System.currentTimeMillis();
        boolean stored = db.transaction(() -> {
            db.upsertPlayer(playerName, null, now);
            db.addHistory(playerName, "KICK", moderatorName, reason, now, 0, null);
        });
        if (!stored) {
            notifyStorageFailure(moderatorName, "кик", playerName);
            return false;
        }

        Component kickMessage = plugin.getPluginConfig().getMessage("kick.kick-message", reason, moderatorName);
        player.kick(kickMessage);
        if (!silent) {
            Bukkit.broadcast(plugin.getPluginConfig().getMessage("kick.moderator-broadcast", moderatorName, playerName, reason));
        }
        if (publishExternally) {
            plugin.getBotManager().sendPunishment("kick", playerName, moderatorName, reason, now, 0, null);
        }
        return true;
    }

    public Set<String> warningCategories() {
        ConfigurationSection categories = plugin.getConfig().getConfigurationSection("warnings.categories");
        return categories == null ? Set.of() : new TreeSet<>(categories.getKeys(false));
    }

    public boolean warnPlayer(String playerName, String moderatorName, String category, String comment,
                              boolean silent, boolean skipEvidence) {
        if (!plugin.getConfig().getBoolean("warnings.enabled", true) || rejectProtected(playerName, moderatorName)) return false;
        String key = category.toLowerCase(Locale.ROOT);
        if (key.length() > 15 || !warningCategories().contains(key)) return false;
        ConfigurationSection rule = plugin.getConfig().getConfigurationSection("warnings.categories." + key);
        if (rule == null) return false;

        ConfigurationSection step = resolveWarningStep(rule, 1);
        if (step == null) return false;
        String action = step.getString("action", "none").toLowerCase(Locale.ROOT);
        if (!Set.of("tempban", "ban", "tempmute", "mute", "kick", "none").contains(action)) {
            plugin.getLogger().warning("Неизвестное действие варна '" + action + "' для пункта " + key);
            return false;
        }
        long duration = 0;
        if (action.equals("tempban") || action.equals("tempmute")) {
            try { duration = Utils.parseDuration(step.getString("duration", "")); }
            catch (IllegalArgumentException ignored) { return false; }
        }
        String displayName = rule.getString("display-name", key);
        String requestDetails = displayName
                + (comment == null || comment.isBlank() || comment.equals("Не указана") ? "" : " • " + comment);
        boolean publishExternally = shouldPublishPunishment(moderatorName, silent);
        Runnable approved = () -> applyWarning(playerName, moderatorName, key, comment, silent, publishExternally);
        if (!skipEvidence && plugin.getBotManager().requestEvidenceApproval("WARN", playerName, moderatorName, requestDetails, approved)) {
            return false;
        }
        return applyWarning(playerName, moderatorName, key, comment, silent, publishExternally);
    }

    public synchronized boolean unwarnPlayer(String playerName, String moderatorName, String category,
                                             String reason, boolean silent) {
        if (!plugin.getConfig().getBoolean("warnings.enabled", true)) return false;
        String key = category.toLowerCase(Locale.ROOT);
        ConfigurationSection rule = plugin.getConfig().getConfigurationSection("warnings.categories." + key);
        if (rule == null || activeWarningCount(playerName, key, rule) <= 0) return false;

        long now = System.currentTimeMillis();
        String displayName = rule.getString("display-name", key);
        String removalReason = reason == null || reason.isBlank() ? "Не указана" : reason;
        boolean stored = db.transaction(() -> db.addHistory(playerName, "UNWARN:" + key, moderatorName,
                displayName + " • причина снятия: " + removalReason, now, 0, null));
        if (!stored) {
            notifyStorageFailure(moderatorName, "снятие варна", playerName);
            return false;
        }

        if (!silent) {
            Bukkit.broadcast(plugin.getPluginConfig().getMessage(
                    "unwarn.moderator-broadcast", moderatorName, playerName, displayName, removalReason));
        }
        if (shouldPublishPunishment(moderatorName, silent)) {
            plugin.getBotManager().sendPunishment("unwarn", playerName, moderatorName,
                    displayName + " • " + removalReason, now, 0, null);
        }
        return true;
    }

    public int activeWarningCount(String playerName, String category) {
        String key = category.toLowerCase(Locale.ROOT);
        ConfigurationSection rule = plugin.getConfig().getConfigurationSection("warnings.categories." + key);
        return rule == null ? 0 : activeWarningCount(playerName, key, rule);
    }

    private int activeWarningCount(String playerName, String key, ConfigurationSection rule) {
        long now = System.currentTimeMillis();
        int expireDays = Math.max(1, rule.getInt("expire-days", 30));
        long cutoff = now - expireDays * 86_400_000L;
        int active = 0;
        List<Models.HistoryEntry> entries = db.getHistory(playerName).stream()
                .filter(entry -> entry.issuedAt() >= cutoff)
                .filter(entry -> entry.type().equalsIgnoreCase("WARN:" + key)
                        || entry.type().equalsIgnoreCase("UNWARN:" + key))
                .sorted(Comparator.comparingLong(Models.HistoryEntry::issuedAt))
                .toList();
        for (Models.HistoryEntry entry : entries) {
            if (entry.type().equalsIgnoreCase("WARN:" + key)) active++;
            else if (active > 0) active--;
        }
        return active;
    }

    private ConfigurationSection resolveWarningStep(ConfigurationSection rule, int warningNumber) {
        ConfigurationSection steps = rule.getConfigurationSection("steps");
        if (steps == null) return null;
        int selected = -1;
        for (String raw : steps.getKeys(false)) {
            try {
                int number = Integer.parseInt(raw);
                if (number <= warningNumber && number > selected) selected = number;
            } catch (NumberFormatException ignored) {}
        }
        return selected < 0 ? null : steps.getConfigurationSection(String.valueOf(selected));
    }

    private synchronized boolean applyWarning(String playerName, String moderatorName, String key, String comment,
                                              boolean silent, boolean publishExternally) {
        if (rejectProtected(playerName, moderatorName)) return false;
        ConfigurationSection rule = plugin.getConfig().getConfigurationSection("warnings.categories." + key);
        if (rule == null) return false;
        long now = System.currentTimeMillis();
        int warningNumber = 1 + activeWarningCount(playerName, key, rule);
        ConfigurationSection step = resolveWarningStep(rule, warningNumber);
        if (step == null) return false;
        String action = step.getString("action", "none").toLowerCase(Locale.ROOT);
        if (!Set.of("tempban", "ban", "tempmute", "mute", "kick", "none").contains(action)) return false;
        long duration = 0;
        if (action.equals("tempban") || action.equals("tempmute")) {
            try { duration = Utils.parseDuration(step.getString("duration", "")); }
            catch (IllegalArgumentException ignored) { return false; }
        }
        String displayName = rule.getString("display-name", key);
        String details = displayName + " • варн " + warningNumber + " • мера: " + warningActionText(action, duration)
                + (comment == null || comment.isBlank() || comment.equals("Не указана") ? "" : " • " + comment);
        long finalDuration = duration;
        boolean stored = db.transaction(() -> {
            db.upsertPlayer(playerName, null, now);
            db.addHistory(playerName, "WARN:" + key, moderatorName, details, now, finalDuration, null);
        });
        if (!stored) {
            notifyStorageFailure(moderatorName, "варн", playerName);
            return false;
        }

        String punishmentReason = "Варн " + warningNumber + ": " + details;
        boolean actionApplied = switch (action) {
            case "tempban" -> banPlayer(playerName, moderatorName, punishmentReason, duration, true, true);
            case "ban" -> banPlayer(playerName, moderatorName, punishmentReason, 0, true, true);
            case "tempmute" -> mutePlayer(playerName, moderatorName, punishmentReason, duration, true, true);
            case "mute" -> mutePlayer(playerName, moderatorName, punishmentReason, 0, true, true);
            case "kick" -> kickPlayer(playerName, moderatorName, punishmentReason, true);
            case "none" -> true;
            default -> false;
        };
        if (!actionApplied) return false;
        if (!silent) {
            Bukkit.broadcast(plugin.getPluginConfig().getMessage(
                    "warn.moderator-broadcast", moderatorName, playerName, details));
        }
        if (publishExternally) {
            plugin.getBotManager().sendPunishment("warn", playerName, moderatorName, details, now, duration, null);
        }
        return true;
    }

    private String warningActionText(String action, long duration) {
        return switch (action) {
            case "tempban" -> "бан " + Utils.parseTime(duration);
            case "ban" -> "перманентный бан";
            case "tempmute" -> "мут " + Utils.parseTime(duration);
            case "mute" -> "перманентный мут";
            case "kick" -> "кик";
            case "none" -> "без дополнительной меры";
            default -> action;
        };
    }

    public void recordVoicePunishment(String playerName, String moderatorName, String reason,
                                      long duration, boolean removal) {
        long now = System.currentTimeMillis();
        String normalizedReason = reason == null || reason.isBlank() ? "Не указана" : reason;
        String historyType = removal ? "VOICE_UNMUTE" : duration > 0 ? "TEMP_VOICE_MUTE" : "VOICE_MUTE";
        boolean stored = db.transaction(() -> {
            db.upsertPlayer(playerName, null, now);
            db.addHistory(playerName, historyType, moderatorName, normalizedReason, now, duration, null);
        });
        if (!stored) {
            notifyStorageFailure(moderatorName, removal ? "снятие voice-мута" : "voice-мут", playerName);
            return;
        }
        if (shouldPublishPunishment(moderatorName, false)) {
            plugin.getBotManager().sendPunishment(
                    removal ? "voiceunmute" : duration > 0 ? "tempvoicemute" : "voicemute",
                    playerName, moderatorName, normalizedReason, now, duration, null);
        }
    }

    public boolean banPlayer(String playerName, String moderatorName, String reason, long duration, boolean silent) {
        return banPlayer(playerName, moderatorName, reason, duration, silent, false);
    }

    public boolean banPlayer(String playerName, String moderatorName, String reason, long duration, boolean silent, boolean skipEvidence) {
        if (rejectProtected(playerName, moderatorName)) return false;
        boolean publishExternally = shouldPublishPunishment(moderatorName, silent);
        if (!skipEvidence && plugin.getBotManager().requestEvidenceApproval(duration == 0 ? "BAN" : "TEMPBAN", playerName, moderatorName, reason,
                () -> applyBan(playerName, moderatorName, reason, duration, silent, publishExternally))) return false;
        return applyBan(playerName, moderatorName, reason, duration, silent, publishExternally);
    }

    private boolean applyBan(String playerName, String moderatorName, String reason, long duration, boolean silent, boolean publishExternally) {
        if (rejectProtected(playerName, moderatorName)) return false;
        long now = System.currentTimeMillis();
        long expiry = duration == 0 ? 0 : now + duration;

        boolean stored = db.transaction(() -> {
            db.upsertPlayer(playerName, null, now);
            db.deactivateBan(playerName);
            db.addBan(playerName, moderatorName, reason, now, expiry);
            db.addHistory(playerName, duration == 0 ? "BAN" : "TEMP_BAN", moderatorName, reason, now, duration, null);
        });
        if (!stored) {
            notifyStorageFailure(moderatorName, "бан", playerName);
            return false;
        }

        Player p = Bukkit.getPlayer(playerName);
        if (p != null) {
            Component kickMsg = plugin.getPluginConfig().getMessage(
                    "ban.kick-message",
                    playerName,
                    expiry == 0 ? "навсегда" : Utils.formatDate(expiry),
                    reason
            );
            p.kick(kickMsg);
        }

        if (!silent) {
            Component broadcastMsg;
            if (duration == 0) {
                broadcastMsg = plugin.getPluginConfig().getMessage(
                        "ban.moderator-broadcast",
                        moderatorName,
                        playerName,
                        reason
                );
            } else {
                broadcastMsg = plugin.getPluginConfig().getMessage(
                        "ban.moderator-tempbroadcast",
                        moderatorName,
                        playerName,
                        Utils.formatDate(expiry),
                        reason
                );
            }
            Bukkit.broadcast(broadcastMsg);
        }

        if (publishExternally) plugin.getBotManager().sendPunishment(
                duration == 0 ? "ban" : "tempban",
                playerName,
                moderatorName,
                reason,
                now,
                duration,
                null
        );
        return true;
    }

    public boolean unbanPlayer(String playerName, String moderatorName, String reason, boolean silent) {
        boolean publishExternally = shouldPublishPunishment(moderatorName, silent);
        long now = System.currentTimeMillis();
        boolean stored = db.transaction(() -> {
            db.deactivateBan(playerName);
            db.addHistory(playerName, "UNBAN", moderatorName, reason, now, 0, null);
        });
        if (!stored) {
            notifyStorageFailure(moderatorName, "разбан", playerName);
            return false;
        }

        if (!silent) {
            Component msg = plugin.getPluginConfig().getMessage(
                    "unban.moderator-broadcast",
                    moderatorName,
                    playerName,
                    reason
            );
            Bukkit.broadcast(msg);
        }

        if (publishExternally) plugin.getBotManager().sendPunishment(
                "unban",
                playerName,
                moderatorName,
                reason,
                System.currentTimeMillis(),
                0,
                null
        );
        return true;
    }

    public boolean mutePlayer(String playerName, String moderatorName, String reason, long duration, boolean silent) {
        return mutePlayer(playerName, moderatorName, reason, duration, silent, false);
    }

    public boolean mutePlayer(String playerName, String moderatorName, String reason, long duration, boolean silent, boolean skipEvidence) {
        if (rejectProtected(playerName, moderatorName)) return false;
        boolean publishExternally = shouldPublishPunishment(moderatorName, silent);
        if (!skipEvidence && plugin.getBotManager().requestEvidenceApproval(duration == 0 ? "MUTE" : "TEMPMUTE", playerName, moderatorName, reason,
                () -> applyMute(playerName, moderatorName, reason, duration, silent, publishExternally))) return false;
        return applyMute(playerName, moderatorName, reason, duration, silent, publishExternally);
    }

    private boolean applyMute(String playerName, String moderatorName, String reason, long duration, boolean silent, boolean publishExternally) {
        if (rejectProtected(playerName, moderatorName)) return false;
        long now = System.currentTimeMillis();
        long remaining = duration;

        if (debug) plugin.getLogger().info("[DebugMute] mutePlayer: " + playerName + ", duration=" + duration + ", remaining=" + remaining);

        boolean stored = db.transaction(() -> {
            db.upsertPlayer(playerName, null, now);
            db.deactivateMute(playerName);
            db.addMute(playerName, moderatorName, reason, now, remaining);
            db.addHistory(playerName, duration == 0 ? "MUTE" : "TEMP_MUTE", moderatorName, reason, now, duration, null);
        });
        if (!stored) {
            notifyStorageFailure(moderatorName, "мут", playerName);
            return false;
        }

        Player p = Bukkit.getPlayer(playerName);
        if (p != null) {
            plugin.getListeners().refreshMute(p);
        }

        if (!silent) {
            Component broadcastMsg;
            if (duration == 0) {
                broadcastMsg = plugin.getPluginConfig().getMessage(
                        "mute.moderator-broadcast",
                        moderatorName,
                        playerName,
                        reason
                );
            } else {
                broadcastMsg = plugin.getPluginConfig().getMessage(
                        "mute.moderator-tempbroadcast",
                        moderatorName,
                        playerName,
                        Utils.parseTime(duration),
                        reason
                );
            }
            Bukkit.broadcast(broadcastMsg);
        }

        if (publishExternally) plugin.getBotManager().sendPunishment(
                duration == 0 ? "mute" : "tempmute",
                playerName,
                moderatorName,
                reason,
                now,
                duration,
                null
        );
        return true;
    }

    public boolean unmutePlayer(String playerName, String moderatorName, String reason, boolean silent) {
        boolean publishExternally = shouldPublishPunishment(moderatorName, silent);
        if (debug) plugin.getLogger().info("[DebugMute] unmutePlayer: " + playerName);

        long now = System.currentTimeMillis();
        boolean stored = db.transaction(() -> {
            db.deactivateMute(playerName);
            db.addHistory(playerName, "UNMUTE", moderatorName, reason, now, 0, null);
        });
        if (!stored) {
            notifyStorageFailure(moderatorName, "размут", playerName);
            return false;
        }

        if (!silent) {
            Component msg = plugin.getPluginConfig().getMessage(
                    "unmute.moderator-broadcast",
                    moderatorName,
                    playerName,
                    reason
            );
            Bukkit.broadcast(msg);
        }

        Player p = Bukkit.getPlayer(playerName);
        if (p != null) {
            plugin.getListeners().refreshMute(p);
        }

        if (publishExternally) plugin.getBotManager().sendPunishment(
                "unmute",
                playerName,
                moderatorName,
                reason,
                System.currentTimeMillis(),
                0,
                null
        );
        return true;
    }

    public boolean ipBanPlayer(String ip, String moderatorName, String reason, long duration, boolean silent) {
        return ipBanPlayer(ip, moderatorName, reason, duration, silent, false);
    }

    public boolean ipBanPlayer(String ip, String moderatorName, String reason, long duration, boolean silent, boolean skipEvidence) {
        if (rejectProtectedIp(ip, moderatorName)) return false;
        boolean publishExternally = shouldPublishPunishment(moderatorName, silent);
        if (!skipEvidence && plugin.getBotManager().requestEvidenceApproval(duration == 0 ? "IPBAN" : "TEMPIPBAN", "IP [скрыт]", moderatorName, reason,
                () -> applyIpBan(ip, moderatorName, reason, duration, silent, publishExternally))) return false;
        return applyIpBan(ip, moderatorName, reason, duration, silent, publishExternally);
    }

    private boolean applyIpBan(String ip, String moderatorName, String reason, long duration, boolean silent, boolean publishExternally) {
        if (rejectProtectedIp(ip, moderatorName)) return false;
        long now = System.currentTimeMillis();
        long expiry = duration == 0 ? 0 : now + duration;
        String shownIp = plugin.getConfig().getBoolean("privacy.hide-ip-in-messages", true) ? "[скрыт]" : ip;

        List<Models.IpHistoryEntry> players = db.getPlayersByIp(ip);
        boolean stored = db.transaction(() -> {
            db.addIpBan(ip, reason, now, expiry);
            if (!players.isEmpty()) {
                String first = players.get(0).playerName();
                db.addHistory(first, duration == 0 ? "IP_BAN" : "TEMP_IP_BAN", moderatorName, reason, now, duration, ip);
            }
            for (Models.IpHistoryEntry entry : players) {
                String linkedName = entry.playerName();
                if (linkedName != null && db.getActiveBan(linkedName) == null) {
                    db.addBan(linkedName, moderatorName, reason + " [IP: " + ip + "]", now, expiry);
                    db.addHistory(linkedName, duration == 0 ? "BAN_BY_IP" : "TEMP_BAN_BY_IP", moderatorName, reason, now, duration, ip);
                }
            }
        });
        if (!stored) {
            notifyStorageFailure(moderatorName, "IP-бан", "IP [скрыт]");
            return false;
        }

        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getAddress() != null && p.getAddress().getAddress().getHostAddress().equals(ip)) {
                Component kickMsg = plugin.getPluginConfig().getMessage(
                        "ipban.kick-message",
                        shownIp,
                        expiry == 0 ? "навсегда" : Utils.formatDate(expiry),
                        reason
                );
                p.kick(kickMsg);
            }
        }

        if (!silent) {
            Component broadcastMsg;
            if (duration == 0) {
                broadcastMsg = plugin.getPluginConfig().getMessage(
                        "ipban.moderator-broadcast",
                        moderatorName,
                        shownIp,
                        reason
                );
            } else {
                broadcastMsg = plugin.getPluginConfig().getMessage(
                        "ipban.moderator-tempbroadcast",
                        moderatorName,
                        shownIp,
                        Utils.formatDate(expiry),
                        reason
                );
            }
            Bukkit.broadcast(broadcastMsg);
        }

        if (publishExternally) plugin.getBotManager().sendPunishment(
                duration == 0 ? "ipban" : "tempipban",
                null,
                moderatorName,
                reason,
                now,
                duration,
                shownIp
        );
        return true;
    }

    public boolean ipUnban(String ip, String moderatorName, String reason, boolean silent) {
        boolean publishExternally = shouldPublishPunishment(moderatorName, silent);
        String shownIp = plugin.getConfig().getBoolean("privacy.hide-ip-in-messages", true) ? "[скрыт]" : ip;
        List<Models.IpHistoryEntry> players = db.getPlayersByIp(ip);
        long now = System.currentTimeMillis();
        boolean stored = db.transaction(() -> {
            db.deactivateIpBan(ip);
            for (Models.IpHistoryEntry entry : players) {
                String linkedName = entry.playerName();
                Models.Ban activeBan = linkedName == null ? null : db.getActiveBan(linkedName);
                if (activeBan != null && wasCreatedByIpBan(linkedName, ip, activeBan.issuedAt())) {
                    db.deactivateBan(linkedName);
                    db.addHistory(linkedName, "UNBAN_BY_IP", moderatorName, reason, now, 0, ip);
                }
            }
            if (!players.isEmpty()) {
                String first = players.get(0).playerName();
                db.addHistory(first, "IP_UNBAN", moderatorName, reason, now, 0, ip);
            }
        });
        if (!stored) {
            notifyStorageFailure(moderatorName, "снятие IP-бана", "IP [скрыт]");
            return false;
        }

        if (!silent) {
            Component msg = plugin.getPluginConfig().getMessage(
                    "ipunban.moderator-broadcast",
                    moderatorName,
                    shownIp,
                    reason
            );
            Bukkit.broadcast(msg);
        }

        if (publishExternally) plugin.getBotManager().sendPunishment(
                "ipunban",
                null,
                moderatorName,
                reason,
                System.currentTimeMillis(),
                0,
                shownIp
        );
        return true;
    }

    private boolean wasCreatedByIpBan(String playerName, String ip, long banIssuedAt) {
        return db.getHistory(playerName).stream().anyMatch(entry ->
                entry.issuedAt() == banIssuedAt
                        && (entry.type().equalsIgnoreCase("BAN_BY_IP")
                        || entry.type().equalsIgnoreCase("TEMP_BAN_BY_IP"))
                        && ip.equals(entry.ip()));
    }

    public boolean shouldPublishPunishment(String moderatorName, boolean silent) {
        if (silent) return false;
        Player moderator = Bukkit.getPlayerExact(moderatorName);
        return moderator == null || !Utils.hasExactPermission(moderator, "kachbans.webhook.bypass");
    }

    public boolean isPunishmentProtected(String playerName) {
        return Utils.hasExactPermission(playerName, "kachbans.punishment.protected");
    }

    private boolean rejectProtected(String playerName, String moderatorName) {
        if (!isPunishmentProtected(playerName)) return false;
        notifyProtectedDenial(moderatorName, playerName);
        return true;
    }

    private boolean rejectProtectedIp(String ip, String moderatorName) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getAddress() != null
                    && player.getAddress().getAddress().getHostAddress().equals(ip)
                    && isPunishmentProtected(player.getName())) {
                notifyProtectedDenial(moderatorName, player.getName());
                return true;
            }
        }
        for (Models.IpHistoryEntry entry : db.getPlayersByIp(ip)) {
            if (entry.playerName() != null && isPunishmentProtected(entry.playerName())) {
                notifyProtectedDenial(moderatorName, entry.playerName());
                return true;
            }
        }
        return false;
    }

    private void notifyProtectedDenial(String moderatorName, String targetName) {
        Player moderator = Bukkit.getPlayerExact(moderatorName);
        if (moderator != null) {
            Utils.sendMessage(moderator, "<gradient:#8B5CF6:#22D3EE>KachBans</gradient> <dark_gray>›</dark_gray> <red>Игрок <white>"
                    + targetName + "</white> защищён от наказаний.</red>");
        }
        plugin.getLogger().warning("Наказание отклонено: игрок " + targetName + " защищён правом kachbans.punishment.protected");
    }

    private void notifyStorageFailure(String moderatorName, String action, String targetName) {
        Player moderator = Bukkit.getPlayerExact(moderatorName);
        if (moderator != null) {
            Utils.sendMessage(moderator, "<gradient:#8B5CF6:#22D3EE>KachBans</gradient> <dark_gray>›</dark_gray> "
                    + "<red>Ошибка базы:</red> <gray>действие «"
                    + action + "» для <white>" + targetName + "</white> не применено.</gray>");
        }
        plugin.getLogger().severe("Наказание не применено из-за ошибки базы: " + action + " -> " + targetName);
    }

    public void checkExpirations() {
        long now = System.currentTimeMillis();

        for (Models.Ban ban : db.getActiveBans()) {
            if (ban.expiry() != 0 && ban.expiry() <= now) {
                db.deactivateBan(ban.playerName());
                String name = ban.playerName();
                if (name != null) {
                    Component msg = plugin.getPluginConfig().getMessage("ban.expired", name);
                    Player p = Bukkit.getPlayer(name);
                    if (p != null && p.isOnline()) {
                        Utils.sendMessage(p, msg);
                    } else {
                        plugin.getLogger().info("Бан игрока " + name + " истёк.");
                    }
                }
            }
        }
    }

    public boolean isBanned(String playerName) {
        return db.getActiveBan(playerName) != null;
    }

    public boolean isMuted(String playerName) {
        boolean result = db.getActiveMute(playerName) != null;
        if (debug) plugin.getLogger().info("[DebugMute] isMuted(" + playerName + ") = " + result);
        return result;
    }

    public Models.Ban getActiveBan(String playerName) {
        return db.getActiveBan(playerName);
    }

    public Models.Mute getActiveMute(String playerName) {
        return db.getActiveMute(playerName);
    }

    public List<Models.Ban> getActiveBans() {
        return db.getActiveBans();
    }

    public List<Models.Mute> getActiveMutes() {
        return db.getActiveMutes();
    }
}
