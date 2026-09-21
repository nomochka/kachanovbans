package dendzyhype.kachanovBans.security;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dendzyhype.kachanovBans.KachanovBans;
import org.bukkit.entity.Player;
import org.bukkit.Bukkit;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.model.user.User;
import dendzyhype.kachanovBans.utils.Utils;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class AccessManager {
    public enum Role { HELPER, MODERATOR, ADMIN, SUPEROWNER }
    public record LinkRequest(UUID playerId, String playerName, String platform, String externalId, String code, long expiresAt) {}
    private static final long LINK_TTL = 10 * 60_000L;
    private final KachanovBans plugin;
    private final File file;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final ExternalDiscordLinkStore externalDiscordLinks;
    private Data data;
    private final Map<UUID, TrustedSession> sessions = new ConcurrentHashMap<>();
    private final Set<UUID> admittedConnections = ConcurrentHashMap.newKeySet();
    private final Set<UUID> discordPassed = ConcurrentHashMap.newKeySet();
    private final Map<UUID, LoginApproval> pendingApprovals = new ConcurrentHashMap<>();
    private final Map<String, LinkRequest> linkRequests = new ConcurrentHashMap<>();
    private final Map<String, BotLinkCode> discordLinkCodes = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();

    public AccessManager(KachanovBans plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "security.json");
        this.externalDiscordLinks = new ExternalDiscordLinkStore(plugin);
        load();
    }

    public boolean enabled() {
        return plugin.getConfig().getBoolean("legacy-auth.enabled", false)
                && plugin.getConfig().getBoolean("security.enabled", true);
    }

    public boolean legacyAuthenticationEnabled() {
        return plugin.getConfig().getBoolean("legacy-auth.enabled", false);
    }

    public boolean canUseStaffCommands(Player player) {
        if (!plugin.getConfig().getBoolean("security.require-discord-link", true)) return true;
        return hasDiscordLink(player.getUniqueId());
    }
    public boolean isStaff(Player p) {
        String permission = plugin.getConfig().getString("security.staff-permission", "kachanovbans.staff");
        return permission != null && !permission.isBlank() && p.hasPermission(permission);
    }
    public boolean optionalTwoFactorEnabled(UUID uuid) {
        Link link = data.players.get(uuid.toString());
        return link != null && link.twoFactorEnabled;
    }
    public boolean requiresDiscordLink(Player player) {
        return isStaff(player) && plugin.getConfig().getBoolean("security.require-discord-link", true);
    }
    public boolean requiresDiscordTwoFactor(Player player) {
        return plugin.getConfig().getBoolean("security.require-discord-2fa", true)
                && (isStaff(player) || optionalTwoFactorEnabled(player.getUniqueId()));
    }
    public boolean requiresAccessKey(Player player) {
        return isStaff(player) && plugin.getConfig().getBoolean("security.require-access-key", true);
    }
    public boolean requiresAuthentication(Player player) {
        return enabled() && (requiresDiscordLink(player) || requiresDiscordTwoFactor(player) || requiresAccessKey(player));
    }
    public String setOptionalTwoFactor(Player player, boolean value) {
        UUID uuid = player.getUniqueId();
        if (!plugin.getConfig().getBoolean("security.require-discord-2fa", true)) {
            return "2FA полностью отключена администратором сервера.";
        }
        if (value && isStaff(player) && plugin.getConfig().getBoolean("security.require-discord-2fa", true)) {
            return "2FA уже включена и обязательна для вашей staff-группы. Текущая сессия не изменена.";
        }
        if (value && optionalTwoFactorEnabled(uuid)) {
            return "2FA уже включена. Текущая сессия не изменена.";
        }
        if (!value && !optionalTwoFactorEnabled(uuid) && !isStaff(player)) {
            return "Добровольная 2FA уже выключена.";
        }
        if (value && !plugin.getBotManager().isDiscordReady()) {
            return "Discord-бот сейчас недоступен. 2FA не включена, чтобы не заблокировать ваш вход.";
        }
        if (value && !hasDiscordLink(uuid)) {
            return "Сначала привяжите Discord: напишите боту /link и введите в игре /ds add [код].";
        }
        if (!value && isStaff(player) && plugin.getConfig().getBoolean("security.require-discord-2fa", true)) {
            return "Для вашей staff-группы 2FA обязательна и не может быть отключена.";
        }
        if (!value && optionalTwoFactorEnabled(uuid) && !isAuthorized(player)) {
            return "Сначала подтвердите текущий вход в Discord. Без подтверждения отключить 2FA нельзя.";
        }
        Link link = data.players.computeIfAbsent(uuid.toString(), key -> new Link(player.getName()));
        link.playerName = player.getName();
        link.twoFactorEnabled = value;
        sessions.remove(uuid);
        admittedConnections.remove(uuid);
        discordPassed.remove(uuid);
        pendingApprovals.remove(uuid);
        save();
        if (value) {
            requestSecondFactor(player);
            return "2FA включена. Подтвердите текущий вход в личных сообщениях Discord-бота.";
        }
        return "Добровольная 2FA отключена.";
    }
    public String loginStage(Player p) {
        if (isAuthorized(p)) return "authorized";
        if (requiresDiscordLink(p) && !hasDiscordLink(p.getUniqueId())) return "discord-link";
        if (requiresDiscordTwoFactor(p) && !hasDiscordLink(p.getUniqueId())) return "discord-link";
        if (requiresDiscordTwoFactor(p) && !discordPassed.contains(p.getUniqueId())) return "discord-2fa";
        if (requiresAccessKey(p)) return "access-key";
        authorize(p);
        return "authorized";
    }
    public boolean isAuthorized(Player p) {
        if (admittedConnections.contains(p.getUniqueId())) return true;
        if (!requiresAuthentication(p)) {
            admittedConnections.add(p.getUniqueId());
            return true;
        }

        if (requiresDiscordLink(p) && !hasDiscordLink(p.getUniqueId())) return false;
        if (!requiresDiscordTwoFactor(p) && !requiresAccessKey(p)) {
            admittedConnections.add(p.getUniqueId());
            return true;
        }

        TrustedSession session = sessions.get(p.getUniqueId());
        String currentIp = playerIp(p);
        if (session == null || currentIp == null
                || !MessageDigest.isEqual(session.ip.getBytes(StandardCharsets.UTF_8), currentIp.getBytes(StandardCharsets.UTF_8))) {
            sessions.remove(p.getUniqueId());
            return false;
        }
        if (session.active) {
            admittedConnections.add(p.getUniqueId());
            return true;
        }
        if (session.expiresAt <= System.currentTimeMillis()) {
            sessions.remove(p.getUniqueId());
            return false;
        }
        sessions.put(p.getUniqueId(), new TrustedSession(currentIp, Long.MAX_VALUE, true));
        admittedConnections.add(p.getUniqueId());
        return true;
    }
    public boolean usesExternalDiscordLinks() { return externalDiscordLinks.enabled(); }
    public boolean hasDiscordLink(UUID uuid) { return notBlank(discordId(uuid)); }
    public String discordId(UUID uuid) {
        if (usesExternalDiscordLinks()) return externalDiscordLinks.discordIdByPlayer(playerName(uuid));
        Link l = data.players.get(uuid.toString());
        return l == null ? null : l.discordId;
    }
    public String telegramId(UUID uuid) { Link l = data.players.get(uuid.toString()); return l == null ? null : l.telegramId; }
    public String discordIdByPlayerName(String playerName) {
        if (playerName == null) return null;
        if (usesExternalDiscordLinks()) return externalDiscordLinks.discordIdByPlayer(playerName);
        return data.players.values().stream()
                .filter(link -> playerName.equalsIgnoreCase(link.playerName) && notBlank(link.discordId))
                .map(link -> link.discordId)
                .findFirst().orElse(null);
    }
    public UUID playerByDiscord(String id) {
        if (usesExternalDiscordLinks()) {
            String playerName = externalDiscordLinks.playerByDiscordId(id);
            if (playerName == null) return null;
            Player online = Bukkit.getPlayerExact(playerName);
            return online != null ? online.getUniqueId() : Bukkit.getOfflinePlayer(playerName).getUniqueId();
        }
        return findPlayer("discord", id);
    }
    public UUID playerByTelegram(String id) { return findPlayer("telegram", id); }

    public String submitKey(Player player, String supplied) {
        if (!requiresAccessKey(player)) {
            loginStage(player);
            return "Ввод ключа отключён в конфигурации.";
        }
        String expected = plugin.getConfig().getString("security.access-keys." + player.getName());
        if (expected == null || expected.length() < 8) expected = groupKey(player);
        if (expected == null || expected.length() < 8) return "Для вашего ника не настроен безопасный ключ доступа.";
        if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8))) return "Неверный ключ доступа.";
        if (requiresDiscordLink(player) && !hasDiscordLink(player.getUniqueId())) return "Сначала привяжите Discord: /ds add <код>.";
        if (requiresDiscordTwoFactor(player) && !discordPassed.remove(player.getUniqueId()))
            return "Сначала подтвердите запрос 2FA в личке Discord-бота, затем снова введите ключ.";
        authorize(player);
        return "Доступ подтверждён.";
    }

    public void requestSecondFactor(Player player) {
        discordPassed.remove(player.getUniqueId());
        pendingApprovals.remove(player.getUniqueId());
        if (requiresDiscordTwoFactor(player) && hasDiscordLink(player.getUniqueId())) {
            long ttl = Math.max(10, plugin.getConfig().getLong("security.stage-timeout-seconds", 60)) * 1000L;
            String nonce = randomToken(18);
            pendingApprovals.put(player.getUniqueId(), new LoginApproval(nonce, System.currentTimeMillis() + ttl));
            plugin.getBotManager().requestLoginApproval(player, nonce);
        }
    }

    public boolean approveDiscordLogin(String discordId, String nonce) {
        UUID uuid = playerByDiscord(discordId);
        if (uuid == null) return false;
        LoginApproval approval = pendingApprovals.remove(uuid);
        Player player = Bukkit.getPlayer(uuid);
        if (approval == null || approval.expiresAt < System.currentTimeMillis()
                || !MessageDigest.isEqual(approval.nonce.getBytes(StandardCharsets.UTF_8), nonce.getBytes(StandardCharsets.UTF_8))
                || player == null || !player.isOnline() || !requiresAuthentication(player)) return false;
        if (requiresAccessKey(player)) discordPassed.add(uuid);
        else authorize(player);
        return true;
    }

    public boolean rejectDiscordLogin(String discordId, String nonce) {
        UUID uuid = playerByDiscord(discordId);
        if (uuid == null) return false;
        LoginApproval approval = pendingApprovals.get(uuid);
        Player player = Bukkit.getPlayer(uuid);
        if (approval == null || approval.expiresAt < System.currentTimeMillis()
                || !MessageDigest.isEqual(approval.nonce.getBytes(StandardCharsets.UTF_8), nonce.getBytes(StandardCharsets.UTF_8))
                || player == null || !player.isOnline() || !requiresAuthentication(player)
                || !pendingApprovals.remove(uuid, approval)) return false;
        discordPassed.remove(uuid);
        sessions.remove(uuid);
        admittedConnections.remove(uuid);
        player.getScheduler().execute(plugin,
                () -> player.kick(Utils.getComponent("<red>Вход отклонён</red>\n\n<gray>Запрос 2FA отклонён через Discord.</gray>")),
                null,
                1L);
        return true;
    }

    public LinkRequest beginLink(Player player, String platform, String externalId) {
        String code = String.format("%06d", random.nextInt(1_000_000));
        LinkRequest request = new LinkRequest(player.getUniqueId(), player.getName(), platform, externalId, code, System.currentTimeMillis() + LINK_TTL);
        linkRequests.put(platform + ":" + externalId, request);
        return request;
    }

    public String createDiscordLinkCode(String discordId) {
        if (usesExternalDiscordLinks()) return null;
        if (playerByDiscord(discordId) != null) return null;
        discordLinkCodes.entrySet().removeIf(e -> e.getValue().expiresAt < System.currentTimeMillis()
                || e.getValue().discordId.equals(discordId));
        String code;
        do { code = String.format("%06d", random.nextInt(1_000_000)); }
        while (discordLinkCodes.containsKey(code));
        discordLinkCodes.put(code, new BotLinkCode(discordId, System.currentTimeMillis() + LINK_TTL));
        return code;
    }

    public boolean redeemDiscordLinkCode(Player player, String code) {
        if (usesExternalDiscordLinks()) return false;
        BotLinkCode pending = discordLinkCodes.remove(code);
        if (pending == null || pending.expiresAt < System.currentTimeMillis() || playerByDiscord(pending.discordId) != null) return false;
        Link link = data.players.computeIfAbsent(player.getUniqueId().toString(), k -> new Link(player.getName()));
        link.playerName = player.getName();
        link.discordId = pending.discordId;
        save();
        requestSecondFactor(player);
        return true;
    }

    public boolean confirmLink(String platform, String externalId, String code) {
        LinkRequest req = linkRequests.remove(platform + ":" + externalId);
        if (req == null || req.expiresAt < System.currentTimeMillis() || !req.code.equals(code)) return false;
        if (findPlayer(platform, externalId) != null) return false;
        Link link = data.players.computeIfAbsent(req.playerId.toString(), k -> new Link(req.playerName));
        link.playerName = req.playerName;
        if (platform.equals("discord")) link.discordId = externalId; else link.telegramId = externalId;
        save();
        if (platform.equals("discord")) Bukkit.getGlobalRegionScheduler().execute(plugin, () -> {
            Player online = Bukkit.getPlayer(req.playerId);
            if (online != null) requestSecondFactor(online);
        });
        return true;
    }

    public Role role(String platform, String externalId) {
        String owner = platform.equals("telegram")
                ? plugin.getConfig().getString("telegramSuperOwnerId", plugin.getConfig().getString("bots.superowner-telegram-id", ""))
                : plugin.getConfig().getString("bots.superowner-discord-id", "");
        if (externalId.equals(owner)) return Role.SUPEROWNER;
        if (platform.equals("discord") && plugin.getConfig().getStringList("bots.allowed-discord-ids").contains(externalId))
            return Role.ADMIN;
        try { return Role.valueOf(data.remoteRoles.getOrDefault(platform + ":" + externalId, "")); }
        catch (IllegalArgumentException ignored) { return null; }
    }
    public synchronized void setRole(String platform, String externalId, Role role) {
        if (role == null) data.remoteRoles.remove(platform + ":" + externalId);
        else data.remoteRoles.put(platform + ":" + externalId, role.name());
        save();
    }
    public boolean canPunish(Role role, String command) {
        if (role == null) return false;
        return switch (role) {
            case SUPEROWNER, ADMIN -> true;
            case MODERATOR -> Set.of("warn", "kick", "ban", "unban", "mute", "tempban", "tempmute", "unmute").contains(command);
            case HELPER -> Set.of("kick", "mute", "tempmute", "unmute").contains(command);
        };
    }
    public void logout(UUID uuid) {
        admittedConnections.remove(uuid);
        TrustedSession session = sessions.get(uuid);
        if (session != null) {
            long minutes = Math.max(1, plugin.getConfig().getLong("security.same-ip-rejoin-minutes", 60));
            sessions.put(uuid, new TrustedSession(session.ip, System.currentTimeMillis() + minutes * 60_000L, false));
        }
        discordPassed.remove(uuid);
        pendingApprovals.remove(uuid);
    }

    private void authorize(Player player) {
        String ip = playerIp(player);
        if (ip != null) {
            sessions.put(player.getUniqueId(), new TrustedSession(ip, Long.MAX_VALUE, true));
            admittedConnections.add(player.getUniqueId());
        }
    }
    private String playerIp(Player player) {
        return player.getAddress() == null ? null : player.getAddress().getAddress().getHostAddress();
    }
    private String groupKey(Player player) {
        LuckPerms luckPerms = Bukkit.getServicesManager().load(LuckPerms.class);
        User user = luckPerms == null ? null : luckPerms.getUserManager().getUser(player.getUniqueId());
        if (user == null) return null;
        return plugin.getConfig().getString("security.group-access-keys." + user.getPrimaryGroup().toLowerCase(Locale.ROOT));
    }
    private String randomToken(int bytes) {
        byte[] value = new byte[bytes];
        random.nextBytes(value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }
    private UUID findPlayer(String platform, String externalId) {
        for (Map.Entry<String, Link> e : data.players.entrySet()) {
            String value = platform.equals("discord") ? e.getValue().discordId : e.getValue().telegramId;
            if (externalId.equals(value)) try { return UUID.fromString(e.getKey()); } catch (IllegalArgumentException ignored) {}
        }
        return null;
    }
    private String playerName(UUID uuid) {
        Player online = Bukkit.getPlayer(uuid);
        if (online != null) return online.getName();
        Link local = data.players.get(uuid.toString());
        if (local != null && notBlank(local.playerName)) return local.playerName;
        return Bukkit.getOfflinePlayer(uuid).getName();
    }
    private static boolean notBlank(String s) { return s != null && !s.isBlank(); }
    private void load() {
        if (!file.exists()) { data = new Data(); save(); return; }
        try (Reader r = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)) { data = gson.fromJson(r, Data.class); }
        catch (Exception e) { plugin.getLogger().severe("Не удалось прочитать security.json: " + e.getMessage()); data = new Data(); }
        if (data == null) data = new Data();
    }
    private synchronized void save() {
        try { if (!plugin.getDataFolder().exists()) plugin.getDataFolder().mkdirs();
            try (Writer w = new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8)) { gson.toJson(data, w); }
        } catch (IOException e) { plugin.getLogger().severe("Не удалось сохранить security.json: " + e.getMessage()); }
    }
    private static final class Data { Map<String, Link> players = new HashMap<>(); Map<String, String> remoteRoles = new HashMap<>(); }
    private static final class Link { String playerName; String discordId; String telegramId; boolean twoFactorEnabled; Link(String playerName) { this.playerName = playerName; } }
    private record LoginApproval(String nonce, long expiresAt) {}
    private record BotLinkCode(String discordId, long expiresAt) {}
    private record TrustedSession(String ip, long expiresAt, boolean active) {}
}
