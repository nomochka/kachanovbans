package dendzyhype.kachanovBans.listeners;

import dendzyhype.kachanovBans.KachanovBans;
import dendzyhype.kachanovBans.data.Models;
import dendzyhype.kachanovBans.integrations.PlasmoVoiceMuteService.VoiceMute;
import dendzyhype.kachanovBans.utils.Utils;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.server.ServerCommandEvent;
import org.bukkit.metadata.FixedMetadataValue;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class Listeners implements Listener {

    private static final Set<String> MUTED_CHAT_COMMANDS = Set.of(
            "m", "msg", "tell", "whisper", "w", "pm", "message",
            "r", "reply", "emsg", "etell", "ewhisper", "er", "ereply",
            "me", "eme", "teammsg", "tm"
    );

    private static final Set<String> PUNISHMENT_COMMANDS = Set.of(
            "warn", "unwarn", "staffhistory", "kick", "ban", "tempban", "unban", "pardon", "mute", "tempmute", "unmute",
            "ipban", "banip", "ban-ip", "tempipban", "ipunban", "unbanip", "pardon-ip", "banlist", "mutelist",
            "history", "iphistory", "dupeip", "checkmute", "checkban", "vmutelist", "checkvmute",
            "blacknick", "unblacknick"
    );

    private final KachanovBans plugin;
    private final boolean debug;
    private final Map<String, ScheduledTask> muteTasks = new HashMap<>();

    public Listeners(KachanovBans plugin) {
        this.plugin = plugin;
        this.debug = plugin.getPluginConfig().isDebug();
    }

    @EventHandler
    public void onPlayerLogin(PlayerLoginEvent event) {
        Player player = event.getPlayer();
        String name = player.getName();
        String ip = event.getAddress().getHostAddress();

        Models.Ban ban = plugin.getPunishmentManager().getActiveBan(name);
        if (ban != null && ban.expiry() != 0 && ban.expiry() <= System.currentTimeMillis()) {
            plugin.getDatabase().deactivateBan(name);
            ban = null;
        }
        if (ban != null) {
            String reason = ban.reason();
            String expiryStr = ban.expiry() == 0 ? "навсегда" : Utils.formatDate(ban.expiry());
            Component kickMsg = plugin.getPluginConfig().getMessage(
                    "ban.kick-message",
                    name,
                    expiryStr,
                    reason
            );
            event.disallow(PlayerLoginEvent.Result.KICK_BANNED, Utils.fromComponent(kickMsg));
            return;
        }

        Models.IpBan ipBan = plugin.getDatabase().getActiveIpBan(ip);
        if (ipBan != null && ipBan.expiry() != 0 && ipBan.expiry() <= System.currentTimeMillis()) {
            plugin.getDatabase().deactivateIpBan(ip);
            ipBan = null;
        }
        if (ipBan != null) {
            String reason = ipBan.reason();
            String expiryStr = ipBan.expiry() == 0 ? "навсегда" : Utils.formatDate(ipBan.expiry());
            Component kickMsg = plugin.getPluginConfig().getMessage(
                    "ipban.kick-message",
                    ip,
                    expiryStr,
                    reason
            );
            event.disallow(PlayerLoginEvent.Result.KICK_BANNED, Utils.fromComponent(kickMsg));
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        String name = player.getName();
        String ip = player.getAddress() != null ? player.getAddress().getAddress().getHostAddress() : "unknown";
        long now = System.currentTimeMillis();

        player.setMetadata("kachanovbans_join_time", new FixedMetadataValue(plugin, now));
        plugin.getDatabase().upsertPlayer(name, ip, now);
        plugin.getDatabase().addIpHistory(name, ip, now);

        if (plugin.getAccessManager().requiresAuthentication(player) && !plugin.getAccessManager().isAuthorized(player)) {
            plugin.getAccessManager().requestSecondFactor(player);
            String stage = plugin.getAccessManager().loginStage(player);
            if (!stage.equals("authorized")) {
                String text = stage.equals("discord-link")
                        ? plugin.getAccessManager().usesExternalDiscordLinks()
                        ? "<gray>Сначала получите проходку и привяжите Discord через бота проекта, затем перезайдите.</gray>"
                        : "<gray>Напишите Discord-боту</gray> <aqua>/link</aqua><gray>, затем используйте</gray> <aqua>/ds add &lt;код&gt;</aqua>"
                        : stage.equals("discord-2fa")
                        ? "<gray>Подтвердите запрос в личных сообщениях Discord.</gray>"
                        : "<gray>Введите:</gray> <aqua>/access [ключ]</aqua>";
                Utils.sendMessage(player, "<gradient:#8B5CF6:#22D3EE>KachBans</gradient> <dark_gray>›</dark_gray> " + text);
                startAccessDeadline(player);
            }
        }

        Models.Mute mute = plugin.getPunishmentManager().getActiveMute(name);
        if (debug) plugin.getLogger().info("[DebugMute] Игрок " + name + " вошёл, активный мут: " + (mute != null));
        if (mute != null) {
            if (debug) plugin.getLogger().info("[DebugMute] Осталось времени: " + mute.expiry() + " мс (" + Utils.parseTime(mute.expiry()) + ")");
            startMuteTask(player, mute);
        } else {
            player.removeMetadata("kachanovbans_muted", plugin);
            if (debug) plugin.getLogger().info("[DebugMute] У игрока нет активного мута.");
        }
    }

    public void startMuteTask(Player player, Models.Mute mute) {
        String name = player.getName();
        ScheduledTask oldTask = muteTasks.remove(name);
        if (oldTask != null) {
            oldTask.cancel();
            if (debug) plugin.getLogger().info("[DebugMute] Старая задача мута для " + name + " остановлена.");
        }

        long remaining = mute.expiry();
        if (debug) plugin.getLogger().info("[DebugMute] startMuteTask для " + name + ", осталось " + remaining + " мс (" + Utils.parseTime(remaining) + ")");

        if (remaining == 0) {
            player.setMetadata("kachanovbans_muted", new FixedMetadataValue(plugin, mute));
            Component msg = plugin.getPluginConfig().getMessage(
                    "mute.target-muted",
                    name,
                    "навсегда",
                    mute.reason()
            );
            Utils.sendMessage(player, msg);
            if (debug) plugin.getLogger().info("[DebugMute] Мут вечный, задача не запущена.");
            return;
        }

        if (remaining <= 0) {
            if (debug) plugin.getLogger().info("[DebugMute] Осталось <= 0, деактивируем мут.");
            plugin.getDatabase().deactivateMute(name);
            player.removeMetadata("kachanovbans_muted", plugin);
            return;
        }

        long[] remainingRef = {remaining};
        if (debug) plugin.getLogger().info("[DebugMute] Запуск задачи мута для " + name + ", период 1 сек.");

        ScheduledTask task = player.getScheduler().runAtFixedRate(
                plugin,
                scheduledTask -> {
                    if (!player.isOnline()) {
                        scheduledTask.cancel();
                        muteTasks.remove(name);
                        if (debug) plugin.getLogger().info("[DebugMute] Задача мута остановлена, игрок оффлайн: " + name);
                        return;
                    }

                    long currentRemaining = remainingRef[0] - 1000;
                    if (debug) plugin.getLogger().info("[DebugMute] Тик: " + name + ", old=" + remainingRef[0] + ", new=" + currentRemaining);

                    if (currentRemaining <= 0) {
                        plugin.getDatabase().deactivateMute(name);
                        player.removeMetadata("kachanovbans_muted", plugin);
                        Component expiredMsg = plugin.getPluginConfig().getMessage("mute.expired", name);
                        Utils.sendMessage(player, expiredMsg);
                        scheduledTask.cancel();
                        muteTasks.remove(name);
                        if (debug) plugin.getLogger().info("[DebugMute] Мут игрока " + name + " истёк во время игры.");
                    } else {
                        remainingRef[0] = currentRemaining;
                        plugin.getDatabase().updateMuteExpiry(name, currentRemaining);
                        Models.Mute updatedMute = new Models.Mute(
                                mute.playerName(),
                                mute.moderatorName(),
                                mute.reason(),
                                mute.issuedAt(),
                                currentRemaining
                        );
                        player.setMetadata("kachanovbans_muted", new FixedMetadataValue(plugin, updatedMute));
                        if (debug && currentRemaining % 5000 == 0) {
                            plugin.getLogger().info("[DebugMute] Обновлён мут для " + name + ", осталось " + currentRemaining + " мс (" + Utils.parseTime(currentRemaining) + ")");
                        }
                    }
                },
                null,
                20L,
                20L
        );

        muteTasks.put(name, task);
        player.setMetadata("kachanovbans_muted", new FixedMetadataValue(plugin, mute));

        Component msg = plugin.getPluginConfig().getMessage(
                "mute.target-tempmuted",
                name,
                Utils.parseTime(remaining),
                mute.reason()
        );
        Utils.sendMessage(player, msg);
        if (debug) plugin.getLogger().info("[DebugMute] Запущена задача мута для " + name + ", осталось " + remaining + " мс");
    }

    public void refreshMute(Player player) {
        String name = player.getName();
        if (debug) plugin.getLogger().info("[DebugMute] refreshMute для " + name);
        ScheduledTask oldTask = muteTasks.remove(name);
        if (oldTask != null) {
            oldTask.cancel();
            if (debug) plugin.getLogger().info("[DebugMute] Старая задача остановлена.");
        }
        player.removeMetadata("kachanovbans_muted", plugin);

        Models.Mute freshMute = plugin.getPunishmentManager().getActiveMute(name);
        if (debug) plugin.getLogger().info("[DebugMute] Свежий мут из БД: " + (freshMute != null ? freshMute.expiry() : "null"));
        if (freshMute != null) {
            startMuteTask(player, freshMute);
        } else {
            player.removeMetadata("kachanovbans_muted", plugin);
            if (debug) plugin.getLogger().info("[DebugMute] Мута больше нет, удаляем метаданные.");
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        String name = player.getName();
        plugin.getAccessManager().logout(player.getUniqueId());
        if (debug) plugin.getLogger().info("[DebugMute] Игрок " + name + " выходит.");

        ScheduledTask task = muteTasks.remove(name);
        if (task != null) {
            task.cancel();
            if (debug) plugin.getLogger().info("[DebugMute] Остановлена задача мута для " + name);
        }

        Models.Mute mute = plugin.getPunishmentManager().getActiveMute(name);
        if (mute != null && mute.expiry() > 0) {
            plugin.getDatabase().updateMuteExpiry(name, mute.expiry());
            if (debug) plugin.getLogger().info("[DebugMute] Сохранено оставшееся время мута для " + name + ": " + mute.expiry());
        } else if (mute != null && mute.expiry() == 0) {
            if (debug) plugin.getLogger().info("[DebugMute] Мут вечный, не сохраняем.");
        } else {
            if (debug) plugin.getLogger().info("[DebugMute] Активного мута нет.");
        }

        player.removeMetadata("kachanovbans_muted", plugin);
        player.removeMetadata("kachanovbans_join_time", plugin);
    }

    private boolean locked(Player player) {
        if (!plugin.getAccessManager().legacyAuthenticationEnabled()) return false;
        return plugin.getAccessManager().requiresAuthentication(player) && !plugin.getAccessManager().isAuthorized(player);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void routePlayerPunishmentCommand(PlayerCommandPreprocessEvent event) {
        String message = event.getMessage();
        String withoutSlash = message.startsWith("/") ? message.substring(1) : message;
        int space = withoutSlash.indexOf(' ');
        String root = (space < 0 ? withoutSlash : withoutSlash.substring(0, space)).toLowerCase();
        if (root.contains(":") || !PUNISHMENT_COMMANDS.contains(root)) return;
        String arguments = space < 0 ? "" : withoutSlash.substring(space);
        event.setMessage("/kachanovbans:" + normalizePunishmentCommand(root) + arguments);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void routeConsolePunishmentCommand(ServerCommandEvent event) {
        String command = event.getCommand();
        int space = command.indexOf(' ');
        String root = (space < 0 ? command : command.substring(0, space)).toLowerCase();
        if (root.contains(":") || !PUNISHMENT_COMMANDS.contains(root)) return;
        String arguments = space < 0 ? "" : command.substring(space);
        event.setCommand("kachanovbans:" + normalizePunishmentCommand(root) + arguments);
    }

    private String normalizePunishmentCommand(String command) {
        return switch (command) {
            case "pardon" -> "unban";
            case "banip", "ban-ip" -> "ipban";
            case "unbanip", "pardon-ip" -> "ipunban";
            default -> command;
        };
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void auditPlayerCommand(PlayerCommandPreprocessEvent event) {
        String raw = event.getMessage();
        String root = commandRoot(raw);
        if (root.isBlank() || root.equals("access")) return;

        boolean voiceMute = configuredCommand("voice-mute-audit.mute-commands", root);
        boolean voiceUnmute = configuredCommand("voice-mute-audit.unmute-commands", root);
        if (plugin.getConfig().getBoolean("command-audit.enabled", true)
                && (configuredCommand("command-audit.commands", root) || voiceMute || voiceUnmute)) {
            Player player = event.getPlayer();
            String location = player.getWorld().getName() + " "
                    + String.format(Locale.ROOT, "%.1f %.1f %.1f", player.getX(), player.getY(), player.getZ());
            plugin.getBotManager().auditLog("Minecraft-команда • " + player.getName()
                    + " • " + location + " • `" + singleLine(raw) + "`");
        }
        if (plugin.getConfig().getBoolean("voice-mute-audit.enabled", true) && (voiceMute || voiceUnmute)) {
            verifyVoiceCommand(singleLine(raw), event.getPlayer().getName(), voiceUnmute);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void enforceVoiceCommandDiscordLink(PlayerCommandPreprocessEvent event) {
        String root = commandRoot(event.getMessage());
        boolean voiceCommand = configuredCommand("voice-mute-audit.mute-commands", root)
                || configuredCommand("voice-mute-audit.unmute-commands", root);
        if (!voiceCommand || plugin.getAccessManager().canUseStaffCommands(event.getPlayer())) return;
        event.setCancelled(true);
        plugin.getBotManager().auditLog("ОТКЛОНЕНО • Discord не привязан • " + event.getPlayer().getName()
                + " • `" + singleLine(event.getMessage()) + "`");
        Utils.sendMessage(event.getPlayer(), "<gradient:#8B5CF6:#22D3EE>KachBans</gradient> <dark_gray>›</dark_gray> "
                + "<red>Discord не привязан.</red> "
                + "<gray>Получите проходку через основного бота проекта.</gray>");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void auditConsoleCommand(ServerCommandEvent event) {
        String raw = event.getCommand();
        String root = commandRoot(raw);
        if (root.isBlank() || root.equals("access")) return;
        boolean voiceMute = configuredCommand("voice-mute-audit.mute-commands", root);
        boolean voiceUnmute = configuredCommand("voice-mute-audit.unmute-commands", root);
        if (plugin.getConfig().getBoolean("command-audit.enabled", true)
                && (configuredCommand("command-audit.commands", root) || voiceMute || voiceUnmute)) {
            plugin.getBotManager().auditLog("Серверная команда • " + event.getSender().getName()
                    + " • `" + singleLine(raw) + "`");
        }
        if (plugin.getConfig().getBoolean("voice-mute-audit.enabled", true) && (voiceMute || voiceUnmute)) {
            String moderator = event.getSender().getName().equalsIgnoreCase("Rcon") ? "RCON" : "Console";
            verifyVoiceCommand(singleLine(raw), moderator, voiceUnmute);
        }
    }

    private void verifyVoiceCommand(String raw, String moderator, boolean removal) {
        String withoutSlash = raw.startsWith("/") ? raw.substring(1) : raw;
        String[] parts = withoutSlash.trim().split("\\s+");
        if (parts.length < 2) return;
        String target = parts[1];
        Player targetPlayer = Bukkit.getPlayerExact(target);
        if (targetPlayer == null) return;
        VoiceMute before = plugin.getPlasmoVoiceMuteService().findActiveMute(targetPlayer.getName()).orElse(null);
        Bukkit.getGlobalRegionScheduler().runDelayed(plugin, task -> {
            VoiceMute after = plugin.getPlasmoVoiceMuteService().findActiveMute(targetPlayer.getName()).orElse(null);
            if (removal) {
                if (before == null || after != null) return;
                plugin.getPunishmentManager().recordVoicePunishment(
                        before.playerName(), moderator, "Снятие voice-мута", 0, true);
                return;
            }
            if (after == null || (before != null && after.issuedAt() == before.issuedAt()
                    && after.expiry() == before.expiry() && after.reason().equals(before.reason()))) return;
            long duration = after.expiry() == 0 ? 0 : Math.max(0, after.expiry() - after.issuedAt());
            plugin.getPunishmentManager().recordVoicePunishment(
                    after.playerName(), moderator, after.reason(), duration, false);
        }, 1L);
    }

    private boolean configuredCommand(String path, String root) {
        List<String> commands = plugin.getConfig().getStringList(path);
        return commands.stream().map(this::commandRoot).anyMatch(root::equals);
    }

    private String commandRoot(String command) {
        if (command == null) return "";
        String value = command.trim();
        if (value.startsWith("/")) value = value.substring(1);
        int space = value.indexOf(' ');
        String root = (space < 0 ? value : value.substring(0, space)).toLowerCase(Locale.ROOT);
        int namespace = root.indexOf(':');
        return namespace < 0 ? root : root.substring(namespace + 1);
    }

    private String singleLine(String value) {
        return value.replace('\r', ' ').replace('\n', ' ').replace('`', '\'').trim();
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onSecureAccessCommand(PlayerCommandPreprocessEvent event) {
        String message = event.getMessage();
        String[] parts = message.trim().split("\\s+", 2);
        if (!parts[0].equalsIgnoreCase("/access")) return;
        event.setMessage("/access [СКРЫТО]");
        event.setCancelled(true);
        if (!plugin.getAccessManager().legacyAuthenticationEnabled()) {
            Utils.sendMessage(event.getPlayer(), "<gradient:#8B5CF6:#22D3EE>KachBans</gradient> <dark_gray>›</dark_gray> "
                    + "<gray>Встроенная авторизация отключена. Discord проверяется через основного бота проходок.</gray>");
            return;
        }
        if (parts.length != 2 || parts[1].isBlank()) {
            Utils.sendMessage(event.getPlayer(), "<gradient:#8B5CF6:#22D3EE>KachBans</gradient> <dark_gray>›</dark_gray> <gray>Использование:</gray> <white>/access [ключ]</white>");
            return;
        }
        String result = plugin.getAccessManager().submitKey(event.getPlayer(), parts[1]);
        Utils.sendMessage(event.getPlayer(), "<gradient:#8B5CF6:#22D3EE>KachBans</gradient> <dark_gray>›</dark_gray> <gray>" + result + "</gray>");
    }

    public void startAccessDeadline(Player player) {
        if (!plugin.getAccessManager().legacyAuthenticationEnabled()) return;
        String[] stage = {plugin.getAccessManager().loginStage(player)};
        long[] changedAt = {System.currentTimeMillis()};
        long timeout = Math.max(10, plugin.getConfig().getLong("security.stage-timeout-seconds", 60)) * 1000L;
        player.getScheduler().runAtFixedRate(plugin, task -> {
            if (!player.isOnline() || plugin.getAccessManager().isAuthorized(player)) { task.cancel(); return; }
            String current = plugin.getAccessManager().loginStage(player);
            if (!current.equals(stage[0])) {
                stage[0] = current; changedAt[0] = System.currentTimeMillis();
                if (current.equals("authorized")) { task.cancel(); return; }
                String message = current.equals("discord-link")
                        ? "<gradient:#8B5CF6:#22D3EE>KachBans</gradient> <dark_gray>›</dark_gray> <gray>Привяжите Discord через</gray> <aqua>/ds add [код]</aqua><gray>.</gray>"
                        : current.equals("discord-2fa")
                        ? "<gradient:#8B5CF6:#22D3EE>KachBans</gradient> <dark_gray>›</dark_gray> <gray>Подтвердите вход в личных сообщениях Discord.</gray>"
                        : "<gradient:#8B5CF6:#22D3EE>KachBans</gradient> <dark_gray>›</dark_gray> <gray>Введите:</gray> <aqua>/access [ключ]</aqua>";
                Utils.sendMessage(player, message);
            }
            if (System.currentTimeMillis() - changedAt[0] >= timeout) {
                player.kick(Utils.getComponent("<red>Время авторизации истекло</red>\n\n<gray>Этап:</gray> <white>" + current + "</white>\n<gray>Подключитесь снова и повторите вход.</gray>"));
                task.cancel();
            }
        }, null, 20L, 20L);
    }

    @EventHandler public void onLockedMove(PlayerMoveEvent event) {
        if (locked(event.getPlayer()) && event.hasChangedPosition()) event.setTo(event.getFrom());
    }
    @EventHandler public void onLockedCommand(PlayerCommandPreprocessEvent event) {
        if (!locked(event.getPlayer())) return;
        String root = event.getMessage().split("\\s+", 2)[0].toLowerCase();
        if (!root.equals("/access") && !root.equals("/ds") && !root.equals("/tg")) {
            event.setCancelled(true);
            Utils.sendMessage(event.getPlayer(), "<gradient:#8B5CF6:#22D3EE>KachBans</gradient> <dark_gray>›</dark_gray> <red>Завершите безопасный вход.</red>");
        }
    }
    @EventHandler public void onLockedInteract(PlayerInteractEvent event) { if (locked(event.getPlayer())) event.setCancelled(true); }
    @EventHandler public void onLockedDrop(PlayerDropItemEvent event) { if (locked(event.getPlayer())) event.setCancelled(true); }
    @EventHandler public void onLockedInventory(InventoryOpenEvent event) { if (event.getPlayer() instanceof Player p && locked(p)) event.setCancelled(true); }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMutedPlayerCommand(PlayerCommandPreprocessEvent event) {
        String message = event.getMessage().trim();
        if (message.length() < 2 || message.charAt(0) != '/') return;

        int space = message.indexOf(' ');
        String command = (space < 0 ? message.substring(1) : message.substring(1, space))
                .toLowerCase(Locale.ROOT);
        int namespaceSeparator = command.indexOf(':');
        if (namespaceSeparator >= 0) command = command.substring(namespaceSeparator + 1);
        if (!MUTED_CHAT_COMMANDS.contains(command)) return;

        Player player = event.getPlayer();
        Models.Mute mute = plugin.getPunishmentManager().getActiveMute(player.getName());
        if (mute == null) {
            player.removeMetadata("kachanovbans_muted", plugin);
            return;
        }

        long remaining = mute.expiry();
        if (remaining < 0) {
            plugin.getDatabase().deactivateMute(player.getName());
            player.removeMetadata("kachanovbans_muted", plugin);
            return;
        }

        event.setCancelled(true);
        Utils.sendMessage(player, plugin.getPluginConfig().getMessage(
                remaining == 0 ? "mute.muted-chat-permanent" : "mute.muted-chat",
                remaining == 0 ? "навсегда" : Utils.parseTime(remaining),
                mute.reason()
        ));
        if (debug) plugin.getLogger().info("[DebugMute] Заблокирована команда /" + command + " от " + player.getName());
    }

    @EventHandler
    public void onAsyncPlayerChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        String name = player.getName();

        Models.Mute mute = plugin.getPunishmentManager().getActiveMute(name);
        if (mute != null) {
            long remaining = mute.expiry();
            if (remaining == 0 || remaining > 0) {
                Component msg = plugin.getPluginConfig().getMessage(
                        remaining == 0 ? "mute.muted-chat-permanent" : "mute.muted-chat",
                        remaining == 0 ? "навсегда" : Utils.parseTime(remaining),
                        mute.reason()
                );
                Utils.sendMessage(player, msg);
                event.setCancelled(true);
                if (debug) plugin.getLogger().info("[DebugMute] Заблокировано сообщение от замученного " + name);
                return;
            } else {
                plugin.getDatabase().deactivateMute(name);
                player.removeMetadata("kachanovbans_muted", plugin);
                if (debug) plugin.getLogger().info("[DebugMute] Мут игрока " + name + " истёк, деактивирован при попытке чата.");
            }
        }
        if (player.hasMetadata("kachanovbans_muted")) {
            player.removeMetadata("kachanovbans_muted", plugin);
        }
    }
}
