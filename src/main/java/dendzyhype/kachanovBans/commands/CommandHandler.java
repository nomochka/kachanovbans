package dendzyhype.kachanovBans.commands;

import dendzyhype.kachanovBans.KachanovBans;
import dendzyhype.kachanovBans.data.Models;
import dendzyhype.kachanovBans.data.PendingConfirmation;
import dendzyhype.kachanovBans.migration.EssentialsMigration;
import dendzyhype.kachanovBans.utils.Utils;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.RemoteConsoleCommandSender;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.net.Inet6Address;
import java.net.InetAddress;

public class CommandHandler implements CommandExecutor {

    private final KachanovBans plugin;
    private final boolean debug;
    private final CommandContext commandContext;

    public CommandHandler(KachanovBans plugin) {
        this.plugin = plugin;
        this.debug = plugin.getPluginConfig().isDebug();
        this.commandContext = new CommandContext(plugin);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        String name = cmd.getName().toLowerCase();
        if (debug) plugin.getLogger().info("[Debug] Выполнена команда: /" + name + " " + String.join(" ", args) + " от " + sender.getName());

        if (name.equals("access")) {
            if (!(sender instanceof Player player)) { Utils.sendMessage(sender, "<gradient:#8B5CF6:#22D3EE>KachBans</gradient> <dark_gray>›</dark_gray> <red>Команда доступна только игроку.</red>"); return true; }
            if (args.length != 1) { Utils.sendMessage(sender, "<gradient:#8B5CF6:#22D3EE>KachBans</gradient> <dark_gray>›</dark_gray> <gray>Использование:</gray> <white>/access [ключ]</white>"); return true; }
            Utils.sendMessage(sender, "<gradient:#8B5CF6:#22D3EE>KachBans</gradient> <dark_gray>›</dark_gray> <gray>" + plugin.getAccessManager().submitKey(player, args[0]) + "</gray>");
            return true;
        }
        if (name.equals("ds") || name.equals("tg")) {
            if (!(sender instanceof Player player)) { Utils.sendMessage(sender, "<gradient:#8B5CF6:#22D3EE>KachBans</gradient> <dark_gray>›</dark_gray> <red>Команда доступна только игроку.</red>"); return true; }
            if (name.equals("ds")) {
                if (plugin.getAccessManager().usesExternalDiscordLinks()) {
                    Utils.sendMessage(sender, "<gradient:#8B5CF6:#22D3EE>KachBans</gradient> <dark_gray>›</dark_gray> <gray>Discord привязывается через бота проходок. После привязки перезайдите.</gray>");
                    return true;
                }
                if (args.length != 2 || !args[0].equalsIgnoreCase("add") || !args[1].matches("\\d{6}")) {
                    Utils.sendMessage(sender, "<gradient:#8B5CF6:#22D3EE>KachBans</gradient> <dark_gray>›</dark_gray> <gray>Напишите боту</gray> <aqua>/link</aqua><gray>, затем:</gray> <aqua>/ds add [код]</aqua>");
                    return true;
                }
                boolean linked = plugin.getAccessManager().redeemDiscordLinkCode(player, args[1]);
                String linkedMessage = "<gradient:#8B5CF6:#22D3EE>KachBans</gradient> <dark_gray>›</dark_gray> <green>Discord привязан.</green>";
                if (linked && plugin.getAccessManager().requiresDiscordTwoFactor(player)) {
                    linkedMessage += " <gray>Подтвердите новый запрос 2FA в личке бота.</gray>";
                } else if (linked) {
                    plugin.getAccessManager().loginStage(player);
                    linkedMessage += " <gray>Доступ разрешён.</gray>";
                }
                Utils.sendMessage(sender, linked
                        ? linkedMessage
                        : "<gradient:#8B5CF6:#22D3EE>KachBans</gradient> <dark_gray>›</dark_gray> <red>Код неверен, истёк или уже использован.</red>");
                return true;
            }
            if (args.length != 2 || !args[0].equalsIgnoreCase("add") || !args[1].matches("\\d{5,25}")) {
                Utils.sendMessage(sender, "<gradient:#8B5CF6:#22D3EE>KachBans</gradient> <dark_gray>›</dark_gray> <gray>Использование:</gray> <white>/tg add [ID]</white>"); return true;
            }
            var request = plugin.getAccessManager().beginLink(player, "telegram", args[1]);
            plugin.getBotManager().sendLinkConfirmation(request);
            Utils.sendMessage(sender, "<gradient:#8B5CF6:#22D3EE>KachBans</gradient> <dark_gray>›</dark_gray> <gray>Запрос отправлен в личные сообщения. Код действует 10 минут.</gray>");
            return true;
        }

        if (name.equals("2fa")) {
            if (!(sender instanceof Player player)) {
                Utils.sendMessage(sender, "<gradient:#8B5CF6:#22D3EE>KachBans</gradient> <dark_gray>›</dark_gray> <red>Команда доступна только игроку.</red>");
                return true;
            }
            if (args.length == 0 || args[0].equalsIgnoreCase("status")) {
                boolean enabled = plugin.getConfig().getBoolean("security.require-discord-2fa", true);
                boolean mandatory = enabled && plugin.getAccessManager().isStaff(player);
                boolean optional = plugin.getAccessManager().optionalTwoFactorEnabled(player.getUniqueId());
                String status = !enabled ? "полностью отключена на сервере"
                        : mandatory ? "включена обязательно для staff-группы"
                        : optional ? "включена добровольно" : "выключена";
                Utils.sendMessage(sender, "<gradient:#8B5CF6:#22D3EE>KachBans</gradient> <dark_gray>›</dark_gray> <gray>2FA:</gray> <white>" + status + "</white><gray>. Команды:</gray> <aqua>/2fa on|off</aqua>");
                return true;
            }
            if (!args[0].equalsIgnoreCase("on") && !args[0].equalsIgnoreCase("off")) {
                Utils.sendMessage(sender, "<gradient:#8B5CF6:#22D3EE>KachBans</gradient> <dark_gray>›</dark_gray> <gray>Использование:</gray> <aqua>/2fa on|off|status</aqua>");
                return true;
            }
            boolean turnOn = args[0].equalsIgnoreCase("on");
            String result = plugin.getAccessManager().setOptionalTwoFactor(player, turnOn);
            Utils.sendMessage(sender, "<gradient:#8B5CF6:#22D3EE>KachBans</gradient> <dark_gray>›</dark_gray> <gray>" + result + "</gray>");
            if (turnOn && plugin.getAccessManager().optionalTwoFactorEnabled(player.getUniqueId())
                    && !plugin.getAccessManager().isAuthorized(player)) {
                plugin.getListeners().startAccessDeadline(player);
            }
            return true;
        }

        if (name.equals("kachanovbans") && args.length == 2
                && args[0].equalsIgnoreCase("migrate") && args[1].equalsIgnoreCase("essentials")) {
            if (sender instanceof Player player && !player.isOp()) {
                Utils.sendMessage(sender, "<gradient:#8B5CF6:#22D3EE>KachBans</gradient> <dark_gray>›</dark_gray> <red>Команда доступна только OP.</red>");
                return true;
            }
            EssentialsMigration.Result result = new EssentialsMigration(plugin).migrate();
            String sourceNote = result.essentialsDataFound()
                    ? ""
                    : " <yellow>Папка Essentials/userdata не найдена: импортированы только баны из banned-players.json.</yellow>";
            Utils.sendMessage(sender, "<gradient:#8B5CF6:#22D3EE>KachBans</gradient> <dark_gray>›</dark_gray> <green>Миграция завершена.</green> "
                    + "<gray>Банов:</gray> <white>" + result.bans() + "</white><gray>, мутов:</gray> <white>" + result.mutes()
                    + "</white><gray>, пропущено:</gray> <white>" + result.skipped() + "</white><gray>, ошибок:</gray> <white>" + result.errors() + "</white>." + sourceNote);
            return true;
        }

        if (sender instanceof Player player && plugin.getAccessManager().requiresAuthentication(player) && !plugin.getAccessManager().isAuthorized(player)) {
            Utils.sendMessage(sender, "<gradient:#8B5CF6:#22D3EE>KachBans</gradient> <dark_gray>›</dark_gray> <red>Завершите безопасный вход.</red>");
            return true;
        }

        if (name.equals("kachanovbans")) {
            if (!sender.hasPermission("kachanovbans.reload")) {
                Utils.sendMessage(sender, plugin.getPluginConfig().getMessage("general.no-permission"));
                return true;
            }
            if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
                plugin.getPluginConfig().reload();
                Utils.sendMessage(sender, plugin.getPluginConfig().getMessage("general.reloaded"));
                if (debug) plugin.getLogger().info("[Debug] Конфиги перезагружены.");
            } else {
                Utils.sendMessage(sender, plugin.getPluginConfig().getMessage("general.invalid-arguments", "/kachanovbans reload | /kachanovbans migrate essentials"));
            }
            return true;
        }

        if (name.equals("punishconfirm")) {
            if (!sender.hasPermission("kachanovbans.confirm")) {
                Utils.sendMessage(sender, plugin.getPluginConfig().getMessage("general.no-permission"));
                return true;
            }
            if (args.length < 3) {
                Utils.sendMessage(sender, plugin.getPluginConfig().getMessage("general.invalid-arguments", "/punishconfirm <ник> <ban/mute> <replace/add>"));
                return true;
            }
            String targetName = args[0];
            String type = args[1];
            String action = args[2];
            String key = pendingKey(targetName, type);
            PendingConfirmation pending = plugin.getPending(key);
            if (pending == null || pending.getExpiresAt() < System.currentTimeMillis()
                    || !pending.getOwnerId().equals(senderIdentity(sender))) {
                if (pending != null && pending.getExpiresAt() < System.currentTimeMillis()) plugin.removePending(key);
                Utils.sendMessage(sender, plugin.getPluginConfig().getMessage("confirm.error"));
                return true;
            }
            if (plugin.getPunishmentManager().isPunishmentProtected(pending.getTargetName())) {
                Utils.sendMessage(sender, "<gradient:#8B5CF6:#22D3EE>KachBans</gradient> <dark_gray>›</dark_gray> <red>Игрок <white>"
                        + pending.getTargetName() + "</white> защищён от наказаний.</red>");
                plugin.removePending(key);
                return true;
            }

            if (action.equalsIgnoreCase("replace")) {
                boolean replaced;
                if (type.equals("ban")) {
                    replaced = plugin.getPunishmentManager().banPlayer(pending.getTargetName(), pending.getModeratorName(),
                            pending.getReason(), pending.getDuration(), pending.isSilent(), pending.skipsEvidence());
                    if (debug) plugin.getLogger().info("[Debug] Бан заменён для " + pending.getTargetName());
                } else if (type.equals("mute")) {
                    if (debug) plugin.getLogger().info("[DebugMute] Замена мута для " + pending.getTargetName() + ", длительность=" + pending.getDuration());
                    replaced = plugin.getPunishmentManager().mutePlayer(pending.getTargetName(), pending.getModeratorName(),
                            pending.getReason(), pending.getDuration(), pending.isSilent(), pending.skipsEvidence());
                    Player target = Bukkit.getPlayer(pending.getTargetName());
                    if (replaced && target != null && target.isOnline()) {
                        if (debug) plugin.getLogger().info("[DebugMute] Перезапуск задачи мута для " + target.getName());
                        plugin.getListeners().refreshMute(target);
                    }
                } else {
                    Utils.sendMessage(sender, plugin.getPluginConfig().getMessage("confirm.error"));
                    plugin.removePending(key);
                    return true;
                }
                plugin.removePending(key);
                if (replaced) Utils.sendMessage(sender, plugin.getPluginConfig().getMessage("confirm.done", "заменили"));
            } else if (action.equalsIgnoreCase("add")) {
                if (type.equals("ban")) {
                    Models.Ban currentBan = plugin.getPunishmentManager().getActiveBan(pending.getTargetName());
                    if (currentBan == null) {
                        Utils.sendMessage(sender, plugin.getPluginConfig().getMessage("confirm.error"));
                        plugin.removePending(key);
                        return true;
                    }
                    if (currentBan.expiry() == 0) {
                        Utils.sendMessage(sender, "<gradient:#8B5CF6:#22D3EE>KachBans</gradient> <dark_gray>›</dark_gray> <red>Нельзя добавить срок к вечному бану.</red>");
                        plugin.removePending(key);
                        return true;
                    }
                    long newExpiry = currentBan.expiry() + pending.getDuration();
                    boolean stored = plugin.getDatabase().transaction(() -> {
                        plugin.getDatabase().deactivateBan(pending.getTargetName());
                        plugin.getDatabase().addBan(pending.getTargetName(), pending.getModeratorName(), currentBan.reason() + " + " + pending.getReason(), currentBan.issuedAt(), newExpiry);
                        plugin.getDatabase().addHistory(pending.getTargetName(), "BAN_EXTEND", pending.getModeratorName(), "Добавлено " + Utils.parseTime(pending.getDuration()), System.currentTimeMillis(), 0, null);
                    });
                    if (!stored) {
                        plugin.removePending(key);
                        Utils.sendMessage(sender, "<gradient:#8B5CF6:#22D3EE>KachBans</gradient> <dark_gray>›</dark_gray> <red>Не удалось сохранить изменение бана.</red>");
                        return true;
                    }
                    Utils.sendMessage(sender, plugin.getPluginConfig().getMessage("confirm.done", "добавили срок"));

                    Map<String, String> extra = new HashMap<>();
                    extra.put("new_expiry", Utils.formatDate(newExpiry));
                    extra.put("added_duration", Utils.parseTime(pending.getDuration()));
                    if (plugin.getPunishmentManager().shouldPublishPunishment(pending.getModeratorName(), pending.isSilent())) plugin.getBotManager().sendPunishment(
                            "ban_extend",
                            pending.getTargetName(),
                            pending.getModeratorName(),
                            pending.getReason(),
                            System.currentTimeMillis(),
                            pending.getDuration(),
                            null,
                            extra
                    );

                    Player target = Bukkit.getPlayer(pending.getTargetName());
                    if (target != null && target.isOnline()) {
                        Component msg = plugin.getPluginConfig().getMessage("ban.tempban-success", pending.getTargetName(), Utils.formatDate(newExpiry));
                        Utils.sendMessage(target, msg);
                    }
                } else if (type.equals("mute")) {
                    Models.Mute currentMute = plugin.getPunishmentManager().getActiveMute(pending.getTargetName());
                    if (currentMute == null) {
                        Utils.sendMessage(sender, plugin.getPluginConfig().getMessage("confirm.error"));
                        plugin.removePending(key);
                        return true;
                    }
                    if (currentMute.expiry() == 0) {
                        Utils.sendMessage(sender, "<gradient:#8B5CF6:#22D3EE>KachBans</gradient> <dark_gray>›</dark_gray> <red>Нельзя добавить срок к вечному муту.</red>");
                        plugin.removePending(key);
                        return true;
                    }
                    long newRemaining = currentMute.expiry() + pending.getDuration();
                    boolean stored = plugin.getDatabase().transaction(() -> {
                        plugin.getDatabase().updateMuteExpiry(pending.getTargetName(), newRemaining);
                        plugin.getDatabase().addHistory(pending.getTargetName(), "MUTE_EXTEND", pending.getModeratorName(), "Добавлено " + Utils.parseTime(pending.getDuration()), System.currentTimeMillis(), 0, null);
                    });
                    if (!stored) {
                        plugin.removePending(key);
                        Utils.sendMessage(sender, "<gradient:#8B5CF6:#22D3EE>KachBans</gradient> <dark_gray>›</dark_gray> <red>Не удалось сохранить изменение мута.</red>");
                        return true;
                    }
                    Utils.sendMessage(sender, plugin.getPluginConfig().getMessage("confirm.done", "добавили срок"));

                    Map<String, String> extra = new HashMap<>();
                    extra.put("added_duration", Utils.parseTime(pending.getDuration()));
                    extra.put("new_remaining", Utils.parseTime(newRemaining));
                    if (plugin.getPunishmentManager().shouldPublishPunishment(pending.getModeratorName(), pending.isSilent())) plugin.getBotManager().sendPunishment(
                            "mute_extend",
                            pending.getTargetName(),
                            pending.getModeratorName(),
                            pending.getReason(),
                            System.currentTimeMillis(),
                            pending.getDuration(),
                            null,
                            extra
                    );

                    Player target = Bukkit.getPlayer(pending.getTargetName());
                    if (target != null && target.isOnline()) {
                        plugin.getListeners().refreshMute(target);
                    }
                } else {
                    Utils.sendMessage(sender, plugin.getPluginConfig().getMessage("confirm.error"));
                    plugin.removePending(key);
                    return true;
                }
                plugin.removePending(key);
            } else {
                plugin.removePending(key);
                Utils.sendMessage(sender, plugin.getPluginConfig().getMessage("confirm.cancelled"));
            }
            return true;
        }

        boolean isBanCmd = name.equals("ban") || name.equals("tempban") || name.equals("unban");
        boolean isWarnCmd = name.equals("warn");
        boolean isUnwarnCmd = name.equals("unwarn");
        boolean isStaffHistory = name.equals("staffhistory");
        boolean isKickCmd = name.equals("kick");
        boolean isMuteCmd = name.equals("mute") || name.equals("tempmute") || name.equals("unmute");
        boolean isIpBanCmd = name.equals("ipban") || name.equals("tempipban") || name.equals("ipunban");
        boolean isList = name.equals("banlist") || name.equals("mutelist");
        boolean isHistory = name.equals("history");
        boolean isIpHistory = name.equals("iphistory");
        boolean isDupeIp = name.equals("dupeip");
        boolean isCheckMute = name.equals("checkmute");
        boolean isCheckBan = name.equals("checkban");

        if (!sender.hasPermission("kachanovbans." + name)) {
            Utils.sendMessage(sender, plugin.getPluginConfig().getMessage("general.no-permission"));
            if (debug) plugin.getLogger().info("[Debug] У " + sender.getName() + " нет прав для " + name);
            return true;
        }

        if (isCheckMute || isCheckBan) {
            if (args.length < 1) {
                Utils.sendMessage(sender, plugin.getPluginConfig().getMessage("general.invalid-arguments", "/" + name + " <ник>"));
                return true;
            }
            String targetName = args[0];
            if (!plugin.getDatabase().isPlayerExists(targetName)) {
                Utils.sendMessage(sender, plugin.getPluginConfig().getMessage("general.invalid-player", targetName));
                return true;
            }
            if (isCheckMute) {
                showCheckMute(sender, targetName);
            } else {
                showCheckBan(sender, targetName);
            }
            return true;
        }

        if (isList) {
            int page = 1;
            if (args.length > 0) {
                try {
                    if (args[0].equalsIgnoreCase("close")) return true;
                    page = Integer.parseInt(args[0]);
                } catch (NumberFormatException ignored) {}
            }
            if (debug) plugin.getLogger().info("[Debug] Показ списка " + name + ", страница " + page);
            if (name.equals("banlist")) {
                showBanList(sender, page);
            } else {
                showMuteList(sender, page);
            }
            return true;
        }

        if (isHistory) {
            if (args.length < 1) {
                Utils.sendMessage(sender, plugin.getPluginConfig().getMessage("general.invalid-arguments", "/history <ник> [страница]"));
                return true;
            }
            String targetName = args[0];
            int page = 1;
            if (args.length > 1) {
                try { page = Integer.parseInt(args[1]); } catch (NumberFormatException ignored) {}
            }
            if (debug) plugin.getLogger().info("[Debug] Запрос истории для " + targetName + ", страница " + page);
            showHistory(sender, targetName, page);
            return true;
        }

        if (isStaffHistory) {
            if (args.length < 1) {
                Utils.sendMessage(sender, plugin.getPluginConfig().getMessage("general.invalid-arguments", "/staffhistory <сотрудник> [страница]"));
                return true;
            }
            int page = 1;
            if (args.length > 1) {
                try { page = Integer.parseInt(args[1]); } catch (NumberFormatException ignored) {}
            }
            showStaffHistory(sender, args[0], page);
            return true;
        }

        if (isIpHistory) {
            if (args.length < 1) {
                Utils.sendMessage(sender, plugin.getPluginConfig().getMessage("general.invalid-arguments", "/iphistory <ник> [страница]"));
                return true;
            }
            String targetName = args[0];
            int page = 1;
            if (args.length > 1) {
                try { page = Integer.parseInt(args[1]); } catch (NumberFormatException ignored) {}
            }
            if (debug) plugin.getLogger().info("[Debug] Запрос IP-истории для " + targetName + ", страница " + page);
            showIpHistory(sender, targetName, page);
            return true;
        }

        if (isDupeIp) {
            if (args.length < 1) {
                Utils.sendMessage(sender, plugin.getPluginConfig().getMessage("general.invalid-arguments", "/dupeip <ник> [страница]"));
                return true;
            }
            String targetName = args[0];
            int page = 1;
            if (args.length > 1) {
                try { page = Integer.parseInt(args[1]); } catch (NumberFormatException ignored) {}
            }
            if (debug) plugin.getLogger().info("[Debug] Запрос дублированных IP для " + targetName + ", страница " + page);
            showDupeIp(sender, targetName, page);
            return true;
        }

        boolean isPermanent = name.equals("ban") || name.equals("mute") || name.equals("ipban");
        boolean isTemp = name.equals("tempban") || name.equals("tempmute") || name.equals("tempipban");
        boolean isUn = name.equals("unban") || name.equals("unmute") || name.equals("ipunban");

        String moderatorName = sender instanceof Player
                ? sender.getName()
                : sender instanceof RemoteConsoleCommandSender ? "RCON" : "Console";

        if (isWarnCmd) {
            if (args.length < 2) {
                Utils.sendMessage(sender, plugin.getPluginConfig().getMessage("general.invalid-arguments",
                        "/warn <ник> <пункт> [комментарий] [-s] [-a]"));
                return true;
            }
            String targetName = args[0];
            String category = args[1].toLowerCase(Locale.ROOT);
            if (!plugin.getPunishmentManager().warningCategories().contains(category)) {
                Utils.sendMessage(sender, plugin.getPluginConfig().getMessage("warn.unknown-category",
                        String.join(", ", plugin.getPunishmentManager().warningCategories())));
                return true;
            }
            boolean silent = Utils.isSilent(args);
            if (!commandContext.canUseSilent(sender, silent)) return true;
            boolean skipEvidence = Utils.skipsEvidence(args);
            if (!commandContext.canSkipEvidence(sender, skipEvidence)) return true;
            String comment = Utils.buildReason(args, 2, silent);
            boolean applied = plugin.getPunishmentManager().warnPlayer(targetName, moderatorName, category, comment, silent, skipEvidence);
            if (applied) {
                Utils.sendMessage(sender, plugin.getPluginConfig().getMessage("warn.success", targetName));
            }
            return true;
        }

        if (isUnwarnCmd) {
            if (args.length < 2) {
                Utils.sendMessage(sender, plugin.getPluginConfig().getMessage("general.invalid-arguments",
                        "/unwarn <ник> <пункт> [причина] [-s]"));
                return true;
            }
            String targetName = args[0];
            String category = args[1].toLowerCase(Locale.ROOT);
            if (!plugin.getPunishmentManager().warningCategories().contains(category)) {
                Utils.sendMessage(sender, plugin.getPluginConfig().getMessage("unwarn.unknown-category",
                        String.join(", ", plugin.getPunishmentManager().warningCategories())));
                return true;
            }
            boolean silent = Utils.isSilent(args);
            if (!commandContext.canUseSilent(sender, silent)) return true;
            String reason = Utils.buildReason(args, 2, silent);
            boolean removed = plugin.getPunishmentManager().unwarnPlayer(
                    targetName, moderatorName, category, reason, silent);
            if (removed) {
                Utils.sendMessage(sender, plugin.getPluginConfig().getMessage("unwarn.success", targetName, category));
            } else {
                Utils.sendMessage(sender, plugin.getPluginConfig().getMessage("unwarn.not-found", targetName, category));
            }
            return true;
        }

        if (isKickCmd) {
            if (args.length < 1) {
                Utils.sendMessage(sender, plugin.getPluginConfig().getMessage("general.invalid-arguments", "/kick <ник> [причина] [-s]"));
                return true;
            }
            String targetName = args[0];
            boolean silent = Utils.isSilent(args);
            if (!commandContext.canUseSilent(sender, silent)) return true;
            String reason = Utils.buildReason(args, 1, silent);
            if (sender instanceof Player && targetName.equalsIgnoreCase(sender.getName())) {
                Utils.sendMessage(sender, plugin.getPluginConfig().getMessage("kick.self-kick"));
                return true;
            }
            if (Bukkit.getPlayerExact(targetName) == null) {
                Utils.sendMessage(sender, plugin.getPluginConfig().getMessage("general.invalid-player", targetName));
                return true;
            }
            boolean applied = plugin.getPunishmentManager().kickPlayer(targetName, moderatorName, reason, silent);
            if (applied) Utils.sendMessage(sender, plugin.getPluginConfig().getMessage("kick.success", targetName));
            return true;
        }

        if (isPermanent) {
            if (args.length < 1) {
                Utils.sendMessage(sender, plugin.getPluginConfig().getMessage("general.invalid-arguments", "/" + name + " <ник> [причина] [-s] [-a]"));
                return true;
            }
            String targetName = args[0];
            boolean silent = Utils.isSilent(args);
            if (!commandContext.canUseSilent(sender, silent)) return true;
            boolean skipEvidence = Utils.skipsEvidence(args);
            if (!commandContext.canSkipEvidence(sender, skipEvidence)) return true;
            String reason = Utils.buildReason(args, 1, silent);
            if (reason.isEmpty()) reason = "Не указана";

            if (isBanCmd) {
                if (sender instanceof Player && targetName.equalsIgnoreCase(sender.getName())) {
                    Utils.sendMessage(sender, plugin.getPluginConfig().getMessage("ban.self-ban"));
                    return true;
                }
                if (plugin.getPunishmentManager().isBanned(targetName)) {
                    PendingConfirmation pending = pendingConfirmation(sender, targetName, moderatorName, reason, 0, silent, skipEvidence, "ban");
                    plugin.addPending(pendingKey(targetName, "ban"), pending);
                    Component header = plugin.getPluginConfig().getMessage("confirm.header", targetName, "забанен");
                    Component replace = plugin.getPluginConfig().getMessage("confirm.replace")
                            .clickEvent(ClickEvent.runCommand("/punishconfirm " + targetName + " ban replace"))
                            .hoverEvent(HoverEvent.showText(Component.text("Заменить бан")));
                    Component add = plugin.getPluginConfig().getMessage("confirm.add")
                            .clickEvent(ClickEvent.runCommand("/punishconfirm " + targetName + " ban add"))
                            .hoverEvent(HoverEvent.showText(Component.text("Добавить срок")));
                    Utils.sendMessage(sender, header);
                    sender.sendMessage(Component.text().append(replace).append(Component.text("  ")).append(add).build());
                    return true;
                }
                boolean applied = plugin.getPunishmentManager().banPlayer(targetName, moderatorName, reason, 0, silent, skipEvidence);
                if (applied) Utils.sendMessage(sender, plugin.getPluginConfig().getMessage("ban.ban-success", targetName));
                if (debug) plugin.getLogger().info("[Debug] Бан навсегда: " + targetName);
            } else if (isMuteCmd) {
                if (sender instanceof Player && targetName.equalsIgnoreCase(sender.getName())) {
                    Utils.sendMessage(sender, plugin.getPluginConfig().getMessage("mute.self-mute"));
                    return true;
                }
                if (plugin.getPunishmentManager().isMuted(targetName)) {
                    if (debug) plugin.getLogger().info("[DebugMute] Игрок " + targetName + " уже замучен, показываем кнопки замены/добавления.");
                    PendingConfirmation pending = pendingConfirmation(sender, targetName, moderatorName, reason, 0, silent, skipEvidence, "mute");
                    plugin.addPending(pendingKey(targetName, "mute"), pending);
                    Component header = plugin.getPluginConfig().getMessage("confirm.header", targetName, "замучен");
                    Component replace = plugin.getPluginConfig().getMessage("confirm.replace")
                            .clickEvent(ClickEvent.runCommand("/punishconfirm " + targetName + " mute replace"))
                            .hoverEvent(HoverEvent.showText(Component.text("Заменить мут")));
                    Component add = plugin.getPluginConfig().getMessage("confirm.add")
                            .clickEvent(ClickEvent.runCommand("/punishconfirm " + targetName + " mute add"))
                            .hoverEvent(HoverEvent.showText(Component.text("Добавить срок")));
                    Utils.sendMessage(sender, header);
                    sender.sendMessage(Component.text().append(replace).append(Component.text("  ")).append(add).build());
                    return true;
                }
                boolean applied = plugin.getPunishmentManager().mutePlayer(targetName, moderatorName, reason, 0, silent, skipEvidence);
                if (applied) Utils.sendMessage(sender, plugin.getPluginConfig().getMessage("mute.success", targetName));
                Player p = Bukkit.getPlayer(targetName);
                if (p != null && p.isOnline()) {
                    plugin.getListeners().refreshMute(p);
                }
                if (debug) plugin.getLogger().info("[Debug] Мут навсегда: " + targetName);
            } else if (isIpBanCmd) {
                String ip = getPlayerIp(targetName);
                if (ip == null) {
                    Utils.sendMessage(sender, plugin.getPluginConfig().getMessage("general.invalid-player", targetName));
                    return true;
                }
                boolean applied = plugin.getPunishmentManager().ipBanPlayer(ip, moderatorName, reason, 0, silent, skipEvidence);
                if (applied) Utils.sendMessage(sender, plugin.getPluginConfig().getMessage("ipban.success", "[скрыт]"));
                if (debug) plugin.getLogger().info("[Debug] IP-бан навсегда: " + ip);
            }
            return true;
        }

        if (isTemp) {
            if (args.length < 2) {
                Utils.sendMessage(sender, plugin.getPluginConfig().getMessage("general.invalid-arguments", "/" + name + " <ник> <время> [причина] [-s] [-a]"));
                return true;
            }
            String targetName = args[0];
            String timeStr = args[1];
            long duration;
            try {
                duration = Utils.parseDuration(timeStr);
            } catch (IllegalArgumentException e) {
                Utils.sendMessage(sender, plugin.getPluginConfig().getMessage("ban.invalid-time"));
                return true;
            }
            boolean silent = Utils.isSilent(args);
            if (!commandContext.canUseSilent(sender, silent)) return true;
            boolean skipEvidence = Utils.skipsEvidence(args);
            if (!commandContext.canSkipEvidence(sender, skipEvidence)) return true;
            String reason = Utils.buildReason(args, 2, silent);
            if (reason.isEmpty()) reason = "Не указана";

            if (isBanCmd) {
                if (plugin.getPunishmentManager().isBanned(targetName)) {
                    PendingConfirmation pending = pendingConfirmation(sender, targetName, moderatorName, reason, duration, silent, skipEvidence, "ban");
                    plugin.addPending(pendingKey(targetName, "ban"), pending);
                    Component header = plugin.getPluginConfig().getMessage("confirm.header", targetName, "забанен");
                    Component replace = plugin.getPluginConfig().getMessage("confirm.replace")
                            .clickEvent(ClickEvent.runCommand("/punishconfirm " + targetName + " ban replace"))
                            .hoverEvent(HoverEvent.showText(Component.text("Заменить бан")));
                    Component add = plugin.getPluginConfig().getMessage("confirm.add")
                            .clickEvent(ClickEvent.runCommand("/punishconfirm " + targetName + " ban add"))
                            .hoverEvent(HoverEvent.showText(Component.text("Добавить срок")));
                    Utils.sendMessage(sender, header);
                    sender.sendMessage(Component.text().append(replace).append(Component.text("  ")).append(add).build());
                    return true;
                }
                boolean applied = plugin.getPunishmentManager().banPlayer(targetName, moderatorName, reason, duration, silent, skipEvidence);
                if (applied) Utils.sendMessage(sender, plugin.getPluginConfig().getMessage("ban.tempban-success", targetName, Utils.formatDate(System.currentTimeMillis() + duration)));
                if (debug) plugin.getLogger().info("[Debug] Временный бан: " + targetName + " на " + duration + " мс");
            } else if (isMuteCmd) {
                if (plugin.getPunishmentManager().isMuted(targetName)) {
                    if (debug) plugin.getLogger().info("[DebugMute] Игрок " + targetName + " уже замучен, показываем кнопки замены/добавления (временный).");
                    PendingConfirmation pending = pendingConfirmation(sender, targetName, moderatorName, reason, duration, silent, skipEvidence, "mute");
                    plugin.addPending(pendingKey(targetName, "mute"), pending);
                    Component header = plugin.getPluginConfig().getMessage("confirm.header", targetName, "замучен");
                    Component replace = plugin.getPluginConfig().getMessage("confirm.replace")
                            .clickEvent(ClickEvent.runCommand("/punishconfirm " + targetName + " mute replace"))
                            .hoverEvent(HoverEvent.showText(Component.text("Заменить мут")));
                    Component add = plugin.getPluginConfig().getMessage("confirm.add")
                            .clickEvent(ClickEvent.runCommand("/punishconfirm " + targetName + " mute add"))
                            .hoverEvent(HoverEvent.showText(Component.text("Добавить срок")));
                    Utils.sendMessage(sender, header);
                    sender.sendMessage(Component.text().append(replace).append(Component.text("  ")).append(add).build());
                    return true;
                }
                boolean applied = plugin.getPunishmentManager().mutePlayer(targetName, moderatorName, reason, duration, silent, skipEvidence);
                if (applied) Utils.sendMessage(sender, plugin.getPluginConfig().getMessage("mute.tempmute-success", targetName, Utils.parseTime(duration)));
                Player p = Bukkit.getPlayer(targetName);
                if (p != null && p.isOnline()) {
                    plugin.getListeners().refreshMute(p);
                }
                if (debug) plugin.getLogger().info("[DebugMute] Временный мут: " + targetName + " на " + duration + " мс");
            } else if (isIpBanCmd) {
                String ip = getPlayerIp(targetName);
                if (ip == null) {
                    Utils.sendMessage(sender, plugin.getPluginConfig().getMessage("general.invalid-player", targetName));
                    return true;
                }
                boolean applied = plugin.getPunishmentManager().ipBanPlayer(ip, moderatorName, reason, duration, silent, skipEvidence);
                if (applied) Utils.sendMessage(sender, plugin.getPluginConfig().getMessage("ipban.tempipban-success", "[скрыт]", Utils.formatDate(System.currentTimeMillis() + duration)));
                if (debug) plugin.getLogger().info("[Debug] Временный IP-бан: " + ip + " на " + duration + " мс");
            }
            return true;
        }

        if (isUn) {
            if (args.length < 1) {
                Utils.sendMessage(sender, plugin.getPluginConfig().getMessage("general.invalid-arguments", "/" + name + " <ник/IP> [причина] [-s]"));
                return true;
            }
            String targetName = args[0];
            boolean silent = Utils.isSilent(args);
            if (!commandContext.canUseSilent(sender, silent)) return true;
            String reason = Utils.buildReason(args, 1, silent);
            if (reason.isEmpty()) reason = "Не указана";

            if (isBanCmd) {
                if (!plugin.getPunishmentManager().isBanned(targetName)) {
                    Utils.sendMessage(sender, plugin.getPluginConfig().getMessage("unban.not-banned", targetName));
                    return true;
                }
                boolean removed = plugin.getPunishmentManager().unbanPlayer(targetName, moderatorName, reason, silent);
                if (removed) Utils.sendMessage(sender, plugin.getPluginConfig().getMessage("unban.success", targetName));
                if (debug) plugin.getLogger().info("[Debug] Разбан: " + targetName);
            } else if (isMuteCmd) {
                if (!plugin.getPunishmentManager().isMuted(targetName)) {
                    Utils.sendMessage(sender, plugin.getPluginConfig().getMessage("unmute.not-muted", targetName));
                    return true;
                }
                boolean removed = plugin.getPunishmentManager().unmutePlayer(targetName, moderatorName, reason, silent);
                if (removed) Utils.sendMessage(sender, plugin.getPluginConfig().getMessage("unmute.success", targetName));
                Player p = Bukkit.getPlayer(targetName);
                if (removed && p != null && p.isOnline()) {
                    plugin.getListeners().refreshMute(p);
                }
                if (debug) plugin.getLogger().info("[Debug] Размут: " + targetName);
            } else if (isIpBanCmd) {
                String ip = getPlayerIp(targetName);
                if (ip == null) {
                    Utils.sendMessage(sender, plugin.getPluginConfig().getMessage("general.invalid-player", targetName));
                    return true;
                }
                if (plugin.getDatabase().getActiveIpBan(ip) == null) {
                    Utils.sendMessage(sender, plugin.getPluginConfig().getMessage("ipunban.not-ipbanned", ip));
                    return true;
                }
                boolean removed = plugin.getPunishmentManager().ipUnban(ip, moderatorName, reason, silent);
                if (removed) Utils.sendMessage(sender, plugin.getPluginConfig().getMessage("ipunban.success", ip));
                if (debug) plugin.getLogger().info("[Debug] Снятие IP-бана: " + ip);
            }
            return true;
        }

        return false;
    }

    void showStaffHistory(CommandSender sender, String moderatorName, int page) {
        List<Models.HistoryEntry> history = plugin.getDatabase().getHistoryByModerator(moderatorName);
        int perPage = plugin.getPluginConfig().getItemsPerPage();
        int totalPages = Math.max(1, Utils.getTotalPages(history.size(), perPage));
        page = Math.max(1, Math.min(page, totalPages));
        Component message = Component.text("KachBans › История сотрудника " + moderatorName + " · " + page + "/" + totalPages)
                .color(net.kyori.adventure.text.format.NamedTextColor.AQUA)
                .append(Component.newline());
        if (history.isEmpty()) {
            sender.sendMessage(message.append(Component.text("Записей не найдено.", net.kyori.adventure.text.format.NamedTextColor.GRAY)));
            return;
        }
        for (Models.HistoryEntry entry : Utils.paginate(history, page, perPage)) {
            String shortLine = "• " + Utils.formatDate(entry.issuedAt()) + " · " + entry.type() + " → " + entry.playerName();
            String full = "Игрок: " + entry.playerName() + "\nТип: " + entry.type() + "\nСотрудник: "
                    + (entry.moderatorName() == null ? "Console" : entry.moderatorName()) + "\nПричина: " + entry.reason()
                    + "\nДата: " + Utils.formatDate(entry.issuedAt())
                    + (entry.duration() > 0 ? "\nСрок: " + Utils.parseTime(entry.duration()) : "");
            message = message.append(Component.text(shortLine, net.kyori.adventure.text.format.NamedTextColor.GRAY)
                    .hoverEvent(HoverEvent.showText(Component.text(full)))).append(Component.newline());
        }
        Component navigation = Component.empty();
        if (page > 1) navigation = navigation.append(Component.text("[←]", net.kyori.adventure.text.format.NamedTextColor.AQUA)
                .clickEvent(ClickEvent.runCommand("/staffhistory " + moderatorName + " " + (page - 1))));
        if (page < totalPages) navigation = navigation.append(Component.text(" [→]", net.kyori.adventure.text.format.NamedTextColor.AQUA)
                .clickEvent(ClickEvent.runCommand("/staffhistory " + moderatorName + " " + (page + 1))));
        sender.sendMessage(message.append(navigation));
    }

    void showBanList(CommandSender sender, int page) {
        if (debug) plugin.getLogger().info("[Debug] showBanList страница " + page);
        List<Models.Ban> bans = plugin.getPunishmentManager().getActiveBans();
        int perPage = plugin.getPluginConfig().getItemsPerPage();
        int totalPages = Utils.getTotalPages(bans.size(), perPage);
        if (page < 1) page = 1;
        if (page > totalPages && totalPages > 0) page = totalPages;

        if (bans.isEmpty()) {
            Utils.sendMessage(sender, plugin.getPluginConfig().getMessage("banlist.empty"));
            return;
        }

        List<Models.Ban> sublist = Utils.paginate(bans, page, perPage);
        Component header = plugin.getPluginConfig().getMessage("banlist.header", page, totalPages);
        Component msg = Component.text().append(header).append(Component.newline()).build();

        for (Models.Ban ban : sublist) {
            String name = ban.playerName();
            String modName = ban.moderatorName() == null ? "Console" : ban.moderatorName();
            String issuedStr = Utils.formatDate(ban.issuedAt());
            String expiryStr = ban.expiry() == 0 ? "навсегда" : Utils.formatDate(ban.expiry());

            Component entry = plugin.getPluginConfig().getMessage(
                    ban.expiry() == 0 ? "banlist.entry-permanent" : "banlist.entry",
                    name,
                    modName,
                    issuedStr,
                    expiryStr,
                    ban.reason()
            );
            msg = msg.append(entry).append(Component.newline());
        }

        Component buttons = Component.empty();
        if (page > 1) {
            buttons = buttons.append(Utils.getComponent(plugin.getPluginConfig().getButtonPrev())
                    .clickEvent(ClickEvent.runCommand("/banlist " + (page - 1)))
                    .hoverEvent(HoverEvent.showText(Component.text("Предыдущая страница"))));
        }
        if (page < totalPages) {
            if (page > 1) buttons = buttons.append(Component.text(" "));
            buttons = buttons.append(Utils.getComponent(plugin.getPluginConfig().getButtonNext())
                    .clickEvent(ClickEvent.runCommand("/banlist " + (page + 1)))
                    .hoverEvent(HoverEvent.showText(Component.text("Следующая страница"))));
        }
        msg = msg.append(buttons);
        sender.sendMessage(msg);
    }

    void showMuteList(CommandSender sender, int page) {
        if (debug) plugin.getLogger().info("[Debug] showMuteList страница " + page);
        List<Models.Mute> mutes = plugin.getPunishmentManager().getActiveMutes();
        int perPage = plugin.getPluginConfig().getItemsPerPage();
        int totalPages = Utils.getTotalPages(mutes.size(), perPage);
        if (page < 1) page = 1;
        if (page > totalPages && totalPages > 0) page = totalPages;

        if (mutes.isEmpty()) {
            Utils.sendMessage(sender, plugin.getPluginConfig().getMessage("mutelist.empty"));
            return;
        }

        List<Models.Mute> sublist = Utils.paginate(mutes, page, perPage);
        Component header = plugin.getPluginConfig().getMessage("mutelist.header", page, totalPages);
        Component msg = Component.text().append(header).append(Component.newline()).build();

        for (Models.Mute mute : sublist) {
            String name = mute.playerName();
            String modName = mute.moderatorName() == null ? "Console" : mute.moderatorName();
            String issuedStr = Utils.formatDate(mute.issuedAt());
            String expiryStr = mute.expiry() == 0 ? "навсегда" : Utils.parseTime(mute.expiry());

            Component entry = plugin.getPluginConfig().getMessage(
                    mute.expiry() == 0 ? "mutelist.entry-permanent" : "mutelist.entry",
                    name,
                    modName,
                    issuedStr,
                    expiryStr,
                    mute.reason()
            );
            msg = msg.append(entry).append(Component.newline());
        }

        Component buttons = Component.empty();
        if (page > 1) {
            buttons = buttons.append(Utils.getComponent(plugin.getPluginConfig().getButtonPrev())
                    .clickEvent(ClickEvent.runCommand("/mutelist " + (page - 1)))
                    .hoverEvent(HoverEvent.showText(Component.text("Предыдущая страница"))));
        }
        if (page < totalPages) {
            if (page > 1) buttons = buttons.append(Component.text(" "));
            buttons = buttons.append(Utils.getComponent(plugin.getPluginConfig().getButtonNext())
                    .clickEvent(ClickEvent.runCommand("/mutelist " + (page + 1)))
                    .hoverEvent(HoverEvent.showText(Component.text("Следующая страница"))));
        }
        msg = msg.append(buttons);
        sender.sendMessage(msg);
    }

    void showHistory(CommandSender sender, String targetName, int page) {
        if (debug) plugin.getLogger().info("[Debug] showHistory для " + targetName + " страница " + page);
        if (!plugin.getDatabase().isPlayerExists(targetName)) {
            Utils.sendMessage(sender, plugin.getPluginConfig().getMessage("history.unknown-player", targetName));
            return;
        }
        List<Models.HistoryEntry> history = plugin.getDatabase().getHistory(targetName);
        int perPage = plugin.getPluginConfig().getItemsPerPage();
        int totalPages = Utils.getTotalPages(history.size(), perPage);
        if (page < 1) page = 1;
        if (page > totalPages && totalPages > 0) page = totalPages;

        if (history.isEmpty()) {
            Utils.sendMessage(sender, plugin.getPluginConfig().getMessage("history.empty", targetName));
            return;
        }

        List<Models.HistoryEntry> sublist = Utils.paginate(history, page, perPage);
        Component header = plugin.getPluginConfig().getMessage("history.header", targetName, page, totalPages);
        Component msg = Component.text().append(header).append(Component.newline()).build();

        for (Models.HistoryEntry entry : sublist) {
            String modName = entry.moderatorName() == null ? "Console" : entry.moderatorName();

            String durationStr;
            if (entry.type().startsWith("UN")) {
                durationStr = "снято";
            } else {
                durationStr = entry.duration() == 0 ? "навсегда" : Utils.parseTime(entry.duration());
            }

            Component line = plugin.getPluginConfig().getMessage(
                    "history.entry",
                    Utils.formatDate(entry.issuedAt()),
                    entry.type(),
                    modName,
                    entry.reason(),
                    durationStr
            );
            boolean hideIp = plugin.getConfig().getBoolean("privacy.hide-ip-in-messages", true);
            String shownIp = entry.ip() == null || entry.ip().isBlank()
                    ? "не указан"
                    : hideIp ? "[скрыт]" : entry.ip();
            String expiresAt = entry.duration() > 0
                    ? Utils.formatDate(entry.issuedAt() + entry.duration())
                    : entry.type().startsWith("UN") ? "наказание снято" : "навсегда";
            Component hover = Component.text()
                    .append(Component.text("KachBans › полная информация", NamedTextColor.AQUA))
                    .append(Component.newline())
                    .append(Component.text("Тип: ", NamedTextColor.GRAY)).append(Component.text(entry.type(), NamedTextColor.WHITE))
                    .append(Component.newline())
                    .append(Component.text("Игрок: ", NamedTextColor.GRAY)).append(Component.text(targetName, NamedTextColor.WHITE))
                    .append(Component.newline())
                    .append(Component.text("Модератор: ", NamedTextColor.GRAY)).append(Component.text(modName, NamedTextColor.WHITE))
                    .append(Component.newline())
                    .append(Component.text("Причина: ", NamedTextColor.GRAY)).append(Component.text(entry.reason(), NamedTextColor.WHITE))
                    .append(Component.newline())
                    .append(Component.text("Выдано: ", NamedTextColor.GRAY)).append(Component.text(Utils.formatDate(entry.issuedAt()), NamedTextColor.WHITE))
                    .append(Component.newline())
                    .append(Component.text("Длительность: ", NamedTextColor.GRAY)).append(Component.text(durationStr, NamedTextColor.WHITE))
                    .append(Component.newline())
                    .append(Component.text("До: ", NamedTextColor.GRAY)).append(Component.text(expiresAt, NamedTextColor.WHITE))
                    .append(Component.newline())
                    .append(Component.text("IP: ", NamedTextColor.GRAY)).append(Component.text(shownIp, NamedTextColor.WHITE))
                    .build();
            line = line.hoverEvent(HoverEvent.showText(hover));
            msg = msg.append(line).append(Component.newline());
        }

        Component buttons = Component.empty();
        if (page > 1) {
            buttons = buttons.append(Utils.getComponent(plugin.getPluginConfig().getButtonPrev())
                    .clickEvent(ClickEvent.runCommand("/history " + targetName + " " + (page - 1)))
                    .hoverEvent(HoverEvent.showText(Component.text("Предыдущая страница"))));
        }
        if (page < totalPages) {
            if (page > 1) buttons = buttons.append(Component.text(" "));
            buttons = buttons.append(Utils.getComponent(plugin.getPluginConfig().getButtonNext())
                    .clickEvent(ClickEvent.runCommand("/history " + targetName + " " + (page + 1)))
                    .hoverEvent(HoverEvent.showText(Component.text("Следующая страница"))));
        }
        msg = msg.append(buttons);
        sender.sendMessage(msg);
    }

    void showIpHistory(CommandSender sender, String targetName, int page) {
        if (debug) plugin.getLogger().info("[Debug] showIpHistory для " + targetName + " страница " + page);
        if (!plugin.getDatabase().isPlayerExists(targetName)) {
            Utils.sendMessage(sender, plugin.getPluginConfig().getMessage("iphistory.unknown-player", targetName));
            return;
        }
        List<Models.IpHistoryEntry> ipHistory = plugin.getDatabase().getIpHistory(targetName);
        int perPage = plugin.getPluginConfig().getItemsPerPage();
        int totalPages = Utils.getTotalPages(ipHistory.size(), perPage);
        if (page < 1) page = 1;
        if (page > totalPages && totalPages > 0) page = totalPages;

        if (ipHistory.isEmpty()) {
            Utils.sendMessage(sender, plugin.getPluginConfig().getMessage("iphistory.empty", targetName));
            return;
        }

        List<Models.IpHistoryEntry> sublist = Utils.paginate(ipHistory, page, perPage);
        Component header = plugin.getPluginConfig().getMessage("iphistory.header", targetName, page, totalPages);
        Component msg = Component.text().append(header).append(Component.newline()).build();

        for (Models.IpHistoryEntry entry : sublist) {
            Component line = plugin.getPluginConfig().getMessage(
                    "iphistory.entry",
                    entry.ip(),
                    Utils.formatDate(entry.firstSeen()),
                    Utils.formatDate(entry.lastSeen())
            );
            msg = msg.append(line).append(Component.newline());
        }

        Component buttons = Component.empty();
        if (page > 1) {
            buttons = buttons.append(Utils.getComponent(plugin.getPluginConfig().getButtonPrev())
                    .clickEvent(ClickEvent.runCommand("/iphistory " + targetName + " " + (page - 1)))
                    .hoverEvent(HoverEvent.showText(Component.text("Предыдущая страница"))));
        }
        if (page < totalPages) {
            if (page > 1) buttons = buttons.append(Component.text(" "));
            buttons = buttons.append(Utils.getComponent(plugin.getPluginConfig().getButtonNext())
                    .clickEvent(ClickEvent.runCommand("/iphistory " + targetName + " " + (page + 1)))
                    .hoverEvent(HoverEvent.showText(Component.text("Следующая страница"))));
        }
        msg = msg.append(buttons);
        sender.sendMessage(msg);
    }

    void showDupeIp(CommandSender sender, String targetName, int page) {
        if (debug) plugin.getLogger().info("[Debug] showDupeIp для " + targetName + " страница " + page);
        if (!plugin.getDatabase().isPlayerExists(targetName)) {
            Utils.sendMessage(sender, plugin.getPluginConfig().getMessage("dupeip.unknown-player", targetName));
            return;
        }
        List<Models.IpHistoryEntry> playerIps = plugin.getDatabase().getIpHistory(targetName);
        if (playerIps.isEmpty()) {
            Utils.sendMessage(sender, plugin.getPluginConfig().getMessage("dupeip.empty", targetName));
            return;
        }

        String firstIp = playerIps.get(0).ip();
        List<Models.IpHistoryEntry> allWithIp = plugin.getDatabase().getPlayersByIp(firstIp);
        allWithIp.removeIf(entry -> entry.playerName().equalsIgnoreCase(targetName));

        int perPage = plugin.getPluginConfig().getItemsPerPage();
        int totalPages = Utils.getTotalPages(allWithIp.size(), perPage);
        if (page < 1) page = 1;
        if (page > totalPages && totalPages > 0) page = totalPages;

        if (allWithIp.isEmpty()) {
            Utils.sendMessage(sender, plugin.getPluginConfig().getMessage("dupeip.empty", targetName));
            return;
        }

        List<Models.IpHistoryEntry> sublist = Utils.paginate(allWithIp, page, perPage);
        Component header = plugin.getPluginConfig().getMessage("dupeip.header", firstIp, page, totalPages);
        Component msg = Component.text().append(header).append(Component.newline()).build();

        for (Models.IpHistoryEntry entry : sublist) {
            String name = entry.playerName();
            Component line = plugin.getPluginConfig().getMessage("dupeip.entry", name);
            msg = msg.append(line).append(Component.newline());
        }

        Component buttons = Component.empty();
        if (page > 1) {
            buttons = buttons.append(Utils.getComponent(plugin.getPluginConfig().getButtonPrev())
                    .clickEvent(ClickEvent.runCommand("/dupeip " + targetName + " " + (page - 1)))
                    .hoverEvent(HoverEvent.showText(Component.text("Предыдущая страница"))));
        }
        if (page < totalPages) {
            if (page > 1) buttons = buttons.append(Component.text(" "));
            buttons = buttons.append(Utils.getComponent(plugin.getPluginConfig().getButtonNext())
                    .clickEvent(ClickEvent.runCommand("/dupeip " + targetName + " " + (page + 1)))
                    .hoverEvent(HoverEvent.showText(Component.text("Следующая страница"))));
        }
        msg = msg.append(buttons);
        sender.sendMessage(msg);
    }

    void showCheckMute(CommandSender sender, String targetName) {
        if (debug) plugin.getLogger().info("[DebugMute] showCheckMute для " + targetName);
        Player player = Bukkit.getPlayer(targetName);
        Models.Mute mute = null;
        if (player != null && player.hasMetadata("kachanovbans_muted")) {
            mute = (Models.Mute) player.getMetadata("kachanovbans_muted").get(0).value();
            if (debug) plugin.getLogger().info("[DebugMute] showCheckMute: взято из метаданных, expiry=" + mute.expiry());
        }
        if (mute == null) {
            mute = plugin.getPunishmentManager().getActiveMute(targetName);
            if (debug) plugin.getLogger().info("[DebugMute] showCheckMute: взято из БД, expiry=" + (mute != null ? mute.expiry() : "null"));
        }
        if (mute == null) {
            Utils.sendMessage(sender, plugin.getPluginConfig().getMessage("checkmute.not-muted", targetName));
            return;
        }
        String modName = mute.moderatorName() == null ? "Console" : mute.moderatorName();
        String issuedStr = Utils.formatDate(mute.issuedAt());
        String remainingStr;
        long remaining = mute.expiry();
        if (remaining == 0) {
            remainingStr = "навсегда";
        } else if (remaining < 0) {
            remainingStr = "истёк";
        } else {
            remainingStr = Utils.parseTime(remaining);
        }
        Component msg = plugin.getPluginConfig().getMessage(
                "checkmute.muted",
                targetName,
                modName,
                mute.reason(),
                issuedStr,
                remainingStr
        );
        Utils.sendMessage(sender, msg);
    }

    void showCheckBan(CommandSender sender, String targetName) {
        Models.Ban ban = plugin.getPunishmentManager().getActiveBan(targetName);
        if (ban == null) {
            Utils.sendMessage(sender, plugin.getPluginConfig().getMessage("checkban.not-banned", targetName));
            return;
        }
        String modName = ban.moderatorName() == null ? "Console" : ban.moderatorName();
        String issuedStr = Utils.formatDate(ban.issuedAt());
        String remainingStr;
        if (ban.expiry() == 0) {
            remainingStr = "навсегда";
        } else {
            long now = System.currentTimeMillis();
            if (ban.expiry() <= now) {
                remainingStr = "истёк";
            } else {
                remainingStr = Utils.parseTime(ban.expiry() - now);
            }
        }
        Component msg = plugin.getPluginConfig().getMessage(
                "checkban.banned",
                targetName,
                modName,
                ban.reason(),
                issuedStr,
                remainingStr
        );
        Utils.sendMessage(sender, msg);
    }

    private String getPlayerIp(String playerName) {
        String literalIp = parseLiteralIp(playerName);
        if (literalIp != null) return literalIp;
        Player p = Bukkit.getPlayer(playerName);
        if (p != null && p.getAddress() != null) {
            String ip = p.getAddress().getAddress().getHostAddress();
            if (debug) plugin.getLogger().info("[Debug] IP игрока " + playerName + " (онлайн) = " + ip);
            return ip;
        }
        List<Models.IpHistoryEntry> history = plugin.getDatabase().getIpHistory(playerName);
        if (!history.isEmpty()) {
            String ip = history.get(0).ip();
            if (debug) plugin.getLogger().info("[Debug] IP игрока " + playerName + " (из истории) = " + ip);
            return ip;
        }
        if (debug) plugin.getLogger().info("[Debug] Не удалось получить IP для " + playerName);
        return null;
    }

    private String parseLiteralIp(String value) {
        if (value.matches("\\d{1,3}(?:\\.\\d{1,3}){3}")) {
            String[] parts = value.split("\\.");
            for (String part : parts) {
                try { if (Integer.parseInt(part) > 255) return null; }
                catch (NumberFormatException ignored) { return null; }
            }
            return value;
        }
        if (value.contains(":")) {
            try {
                InetAddress parsed = InetAddress.getByName(value);
                return parsed instanceof Inet6Address ? parsed.getHostAddress() : null;
            } catch (Exception ignored) { return null; }
        }
        return null;
    }

    private PendingConfirmation pendingConfirmation(CommandSender sender, String targetName, String moderatorName,
                                                      String reason, long duration, boolean silent,
                                                      boolean skipEvidence, String type) {
        long timeoutSeconds = Math.max(10L, plugin.getConfig().getLong("confirmations.timeout-seconds", 120L));
        return new PendingConfirmation(targetName, moderatorName, reason, duration, silent, skipEvidence, type,
                senderIdentity(sender), System.currentTimeMillis() + timeoutSeconds * 1000L);
    }

    private String senderIdentity(CommandSender sender) {
        if (sender instanceof Player player) return "player:" + player.getUniqueId();
        if (sender instanceof RemoteConsoleCommandSender) return "rcon";
        return "console";
    }

    private String pendingKey(String targetName, String type) {
        return targetName.toLowerCase(Locale.ROOT) + "_" + type.toLowerCase(Locale.ROOT);
    }
}
