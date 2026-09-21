package dendzyhype.kachanovBans.bots;

import com.google.gson.*;
import dendzyhype.kachanovBans.KachanovBans;
import dendzyhype.kachanovBans.security.AccessManager;
import dendzyhype.kachanovBans.utils.Utils;
import net.dv8tion.jda.api.*;
import net.dv8tion.jda.api.entities.*;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.DefaultMemberPermissions;
import net.dv8tion.jda.api.interactions.components.buttons.Button;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

public final class BotManager extends ListenerAdapter {
    private final KachanovBans plugin;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ScheduledExecutorService worker = Executors.newScheduledThreadPool(2, r -> { Thread t = new Thread(r, "KachanovBans-Bots"); t.setDaemon(true); return t; });
    private final Map<String, Evidence> evidence = new ConcurrentHashMap<>();
    private final Map<String, String> selectedEvidenceByDiscord = new ConcurrentHashMap<>();
    private final ThreadLocal<List<String>> punishmentEvidence = new ThreadLocal<>();
    private volatile JDA jda;
    private volatile boolean running;
    private long telegramOffset;

    public BotManager(KachanovBans plugin) { this.plugin = plugin; }

    public void start() {
        running = true;
        String discordToken = plugin.getConfig().getString("bots.discord.token", "");
        if (plugin.getConfig().getBoolean("bots.discord.enabled") && !discordToken.isBlank()) {
            try {
                jda = JDABuilder.createDefault(discordToken).addEventListeners(this).build();
                jda.awaitReady();
                registerDiscordCommands();
            } catch (Exception e) { plugin.getLogger().severe("Discord-бот не запущен: " + e.getMessage()); }
        }
        if (telegramEnabled()) worker.execute(this::telegramLoop);
    }

    public void stop() {
        running = false;
        worker.shutdownNow();

        JDA client = jda;
        jda = null;
        if (client == null) return;

        client.removeEventListener(this);
        client.shutdown();
        try {
            if (!client.awaitShutdown(Duration.ofSeconds(5))) {
                client.shutdownNow();
                client.awaitShutdown(Duration.ofSeconds(2));
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            client.shutdownNow();
        }
    }

    public boolean isDiscordReady() {
        return jda != null && jda.getStatus() == JDA.Status.CONNECTED;
    }

    public void requestLoginApproval(Player player, String nonce) {
        String discordId = plugin.getAccessManager().discordId(player.getUniqueId());
        if (discordId == null || jda == null) return;
        String ip = player.getAddress() == null ? "не определён" : player.getAddress().getAddress().getHostAddress();
        long seconds = Math.max(10, plugin.getConfig().getLong("security.stage-timeout-seconds", 60));
        jda.retrieveUserById(discordId).queue(user -> user.openPrivateChannel().queue(dm -> dm.sendMessage(
                "## Подтверждение входа в Minecraft\n"
                        + "**Игрок:** " + discordEscape(player.getName()) + "\n"
                        + "**IP:** `" + ip + "`\n"
                        + "**Срок запроса:** " + seconds + " сек.\n\n"
                        + "Если это не вы — нажмите **Отклонить вход**. Подключение будет немедленно разорвано.")
                .setActionRow(
                        Button.success("login:" + nonce, "Подтвердить вход"),
                        Button.danger("login-deny:" + nonce, "Отклонить вход")
                ).queue()));
    }

    public void sendLinkConfirmation(AccessManager.LinkRequest request) {
        String text = "Привязка аккаунта Minecraft **" + request.playerName() + "**. Для подтверждения отправьте: /confirm " + request.code();
        if (request.platform().equals("discord")) {
            if (jda == null) return;
            String discordText = "Привязка аккаунта Minecraft **" + discordEscape(request.playerName())
                    + "**. Для подтверждения отправьте: /confirm " + request.code();
            jda.retrieveUserById(request.externalId()).queue(u -> u.openPrivateChannel().queue(c -> c.sendMessage(discordText).queue()),
                    error -> plugin.getLogger().warning("Не удалось отправить подтверждение Discord ID " + request.externalId()));
        } else sendTelegram(request.externalId(), text.replace("**", ""));
    }

    public void requestEvidence(String type, String target, String moderator, String reason) {
        if (!plugin.getConfig().getBoolean("evidence.enabled", true) || canSkipEvidence(moderator)
                || moderator.startsWith("Discord:") || moderator.startsWith("Telegram:")) return;
        Player player = Bukkit.getPlayerExact(moderator);
        if (player == null) return;
        String id = UUID.randomUUID().toString().substring(0, 8);
        long timeout = Math.max(1, plugin.getConfig().getLong("evidence.timeout-minutes", 60));
        Evidence item = new Evidence(id, type, target, moderator, reason, System.currentTimeMillis() + timeout * 60_000L);
        evidence.put(id, item);
        String text = "Доказательства наказания #" + id + "\n" + type + " → " + target + "\nПричина: " + reason + "\nПрикрепите файл/скрин ответом в течение " + timeout + " мин.";
        String discordId = plugin.getAccessManager().discordId(player.getUniqueId());
        String discordText = "Доказательства наказания #" + id + "\n" + discordEscape(type) + " → "
                + discordEscape(target) + "\nПричина: " + discordEscape(reason)
                + "\nПрикрепите файл/скрин ответом в течение " + timeout + " мин.";
        if (discordId != null && jda != null) jda.retrieveUserById(discordId).queue(u -> u.openPrivateChannel().queue(c -> c.sendMessage(discordText).queue()));
        String telegramId = plugin.getAccessManager().telegramId(player.getUniqueId());
        if (telegramId != null) sendTelegram(telegramId, text);
        worker.schedule(() -> expireEvidence(id), timeout, TimeUnit.MINUTES);
    }

    public boolean requestEvidenceApproval(String type, String target, String moderator, String reason, Runnable approvedAction) {
        if (!plugin.getConfig().getBoolean("evidence.enabled", true) || canSkipEvidence(moderator)) return false;
        String discordId = null;
        if (moderator.startsWith("Discord:")) {
            int open = moderator.lastIndexOf('('), close = moderator.lastIndexOf(')');
            if (open >= 0 && close > open) discordId = moderator.substring(open + 1, close);
        } else {
            Player player = Bukkit.getPlayerExact(moderator);
            if (player != null) discordId = plugin.getAccessManager().discordId(player.getUniqueId());
        }
        if (discordId == null || jda == null) {
            plugin.getLogger().warning("Наказание отменено: невозможно запросить доказательства у " + moderator);
            Player player = Bukkit.getPlayerExact(moderator);
            if (player != null) Utils.sendMessage(player, "<gradient:#8B5CF6:#22D3EE>KachBans</gradient> <dark_gray>›</dark_gray> <red>Наказание не выдано:</red> <gray>Discord не привязан или бот недоступен.</gray>");
            return true;
        }
        String id = UUID.randomUUID().toString().substring(0, 8);
        long timeout = Math.max(1, plugin.getConfig().getLong("evidence.timeout-minutes", 60));
        Evidence item = new Evidence(id, type, target, moderator, reason,
                System.currentTimeMillis() + timeout * 60_000L, discordId, approvedAction);
        evidence.put(id, item);
        String message = "## Наказание ожидает доказательства\n"
                + "**ID:** `" + id + "`\n**Тип:** " + discordEscape(type) + "\n**Игрок:** " + discordEscape(target)
                + "\n**Причина:** " + discordEscape(reason) + "\n\nПрикрепите сюда файл, скриншот или видео. "
                + "Наказание будет применено **только после получения вложения**. Срок: " + timeout + " мин.";
        jda.retrieveUserById(discordId).queue(user -> user.openPrivateChannel().queue(dm -> dm.sendMessage(message)
                        .setActionRow(
                                Button.primary("evidence-select:" + id, "Прикрепить доказательства"),
                                Button.danger("evidence-cancel:" + id, "Отменить наказание"))
                        .queue(),
                error -> failPendingEvidence(id, moderator)), error -> failPendingEvidence(id, moderator));
        worker.schedule(() -> expireEvidence(id), timeout, TimeUnit.MINUTES);
        Player player = Bukkit.getPlayerExact(moderator);
        if (player != null) Utils.sendMessage(player, "<gradient:#8B5CF6:#22D3EE>KachBans</gradient> <dark_gray>›</dark_gray> <gray>Прикрепите доказательство в ЛС Discord-боту. До этого наказание не будет выдано.</gray>");
        return true;
    }

    private boolean isServerConsole(String moderator) {
        return moderator == null || moderator.equalsIgnoreCase("Console") || moderator.equalsIgnoreCase("RCON");
    }

    private boolean canSkipEvidence(String moderator) {
        return isServerConsole(moderator);
    }

    private void failPendingEvidence(String id, String moderator) {
        evidence.remove(id);
        selectedEvidenceByDiscord.entrySet().removeIf(entry -> entry.getValue().equals(id));
        plugin.getLogger().warning("Наказание отменено: не удалось отправить ЛС Discord пользователю " + moderator);
    }

    @Override public void onButtonInteraction(@NotNull ButtonInteractionEvent e) {
        if (e.getComponentId().startsWith("evidence-select:")) {
            String id = e.getComponentId().substring("evidence-select:".length());
            Evidence pending = evidence.get(id);
            boolean allowed = pending != null && !pending.complete && !pending.expired && !pending.cancelled
                    && pending.approvedAction != null
                    && pending.deadline >= System.currentTimeMillis()
                    && e.getUser().getId().equals(pending.discordId);
            if (!allowed) {
                e.reply("❌ Заявка истекла, уже закрыта или принадлежит другому модератору.").setEphemeral(true).queue();
                return;
            }
            selectedEvidenceByDiscord.put(e.getUser().getId(), id);
            e.reply("📎 Выбрано наказание **#" + id + "** для **" + discordEscape(pending.target)
                    + "**. Теперь отправьте следующим сообщением скриншот, видео или файл.")
                    .setEphemeral(true).queue();
            return;
        }
        if (e.getComponentId().startsWith("evidence-cancel:")) {
            String id = e.getComponentId().substring("evidence-cancel:".length());
            Evidence pending = evidence.get(id);
            boolean allowed = pending != null && pending.approvedAction != null
                    && e.getUser().getId().equals(pending.discordId);
            if (!allowed) {
                e.reply("Заявка уже закрыта или принадлежит другому модератору.").setEphemeral(true).queue();
                return;
            }
            synchronized (pending) {
                if (pending.complete || pending.expired || pending.cancelled
                        || pending.deadline < System.currentTimeMillis()) {
                    evidence.remove(id, pending);
                    selectedEvidenceByDiscord.entrySet().removeIf(entry -> entry.getValue().equals(id));
                    e.reply("Заявка уже закрыта или истекла.").setEphemeral(true).queue();
                    return;
                }
                pending.cancelled = true;
                if (!evidence.remove(id, pending)) {
                    e.reply("Не удалось отменить заявку: её состояние уже изменилось.").setEphemeral(true).queue();
                    return;
                }
            }
            selectedEvidenceByDiscord.entrySet().removeIf(entry -> entry.getValue().equals(id));
            persistEvidence(pending);
            auditLog("Наказание #" + pending.id + " отменено модератором Discord `"
                    + e.getUser().getId() + "`: " + pending.type + " → " + pending.target);
            Player moderator = Bukkit.getPlayerExact(pending.moderator);
            if (moderator != null) {
                Utils.sendMessage(moderator, "<gradient:#8B5CF6:#22D3EE>KachBans</gradient> <dark_gray>›</dark_gray> <gray>Выдача наказания для <white>"
                        + pending.target + "</white> отменена.</gray>");
            }
            e.editMessage("Наказание **#" + pending.id + "** для **" + discordEscape(pending.target)
                    + "** отменено. Оно не будет применено.").setComponents().queue();
            return;
        }
        if (e.getComponentId().startsWith("login-deny:")) {
            boolean ok = plugin.getAccessManager().rejectDiscordLogin(e.getUser().getId(), e.getComponentId().substring(11));
            auditLog("Discord 2FA: " + e.getUser().getEffectiveName() + " (`" + e.getUser().getId() + "`) — вход " + (ok ? "отклонён" : "не удалось отклонить"));
            if (ok) e.getMessage().editMessageComponents().queue();
            e.reply(ok ? "⛔ Вход отклонён. Подключение к серверу разорвано." : "❌ Запрос уже использован, истёк или относится к другому входу.").setEphemeral(true).queue();
            return;
        }
        if (e.getComponentId().startsWith("login:")) {
            UUID linkedPlayer = plugin.getAccessManager().playerByDiscord(e.getUser().getId());
            Player onlinePlayer = linkedPlayer == null ? null : Bukkit.getPlayer(linkedPlayer);
            boolean accessKeyRequired = onlinePlayer != null && plugin.getAccessManager().isStaff(onlinePlayer);
            boolean ok = plugin.getAccessManager().approveDiscordLogin(e.getUser().getId(), e.getComponentId().substring(6));
            auditLog("Discord 2FA: " + e.getUser().getEffectiveName() + " (`" + e.getUser().getId() + "`) — " + (ok ? "подтверждено" : "отклонено"));
            String success = accessKeyRequired
                    ? "✅ 2FA подтверждена. Теперь введите ключ доступа на сервере."
                    : "✅ 2FA подтверждена. Вход разрешён.";
            if (ok) e.getMessage().editMessageComponents().queue();
            e.reply(ok ? success : "❌ Запрос уже использован, истёк или относится к другому входу.").setEphemeral(true).queue();
        }
    }

    @Override public void onMessageReceived(@NotNull MessageReceivedEvent e) {
        if (e.getAuthor().isBot() || e.isFromGuild()) return;
        String text = e.getMessage().getContentRaw().trim();
        auditLog("ЛС Discord от " + e.getAuthor().getEffectiveName() + " (`" + e.getAuthor().getId() + "`): "
                + (!e.getMessage().getAttachments().isEmpty() ? "получено вложение" : (text.startsWith("/confirm") ? "/confirm [код скрыт]" : text)));
        if (text.equalsIgnoreCase("/link") || text.equalsIgnoreCase("link")) {
            if (!plugin.getAccessManager().legacyAuthenticationEnabled()) {
                e.getChannel().sendMessage("Встроенная привязка KachBans отключена. Используйте основного бота проходок проекта.").queue();
                return;
            }
            if (plugin.getAccessManager().usesExternalDiscordLinks()) {
                e.getChannel().sendMessage("Привязка выполняется через отдельного бота проходок проекта. После получения проходки перезайдите на Minecraft-сервер.").queue();
                return;
            }
            String code = plugin.getAccessManager().createDiscordLinkCode(e.getAuthor().getId());
            e.getChannel().sendMessage(code == null
                    ? "Этот Discord уже привязан к Minecraft-аккаунту."
                    : "## Привязка Minecraft\nВаш одноразовый код: **`" + code + "`**\n\nВведите на сервере: **`/ds add " + code + "`**\nКод действует 10 минут и сработает только один раз.").queue();
            return;
        }
        if (text.toLowerCase(Locale.ROOT).startsWith("/confirm ")) {
            if (!plugin.getAccessManager().legacyAuthenticationEnabled()) {
                e.getChannel().sendMessage("Встроенная привязка KachBans отключена.").queue();
                return;
            }
            boolean ok = plugin.getAccessManager().confirmLink("discord", e.getAuthor().getId(), text.substring(9).trim());
            e.getChannel().sendMessage(ok ? "Discord успешно привязан." : "Код неверен/истёк или этот Discord уже привязан.").queue();
            return;
        }
        if (!e.getMessage().getAttachments().isEmpty()) acceptEvidence("discord", e.getAuthor().getId(), e.getMessage().getAttachments().stream().map(Message.Attachment::getUrl).toList());
    }

    @Override public void onSlashCommandInteraction(@NotNull SlashCommandInteractionEvent e) {
        String command = e.getName();
        auditLog("Slash-команда от " + e.getUser().getEffectiveName() + " (`" + e.getUser().getId() + "`) в <#"
                + e.getChannel().getId() + ">: `" + e.getCommandString() + "`");
        if (command.equals("setbotlogs")) {
            if (!plugin.getConfig().getStringList("bots.allowed-discord-ids").contains(e.getUser().getId())) {
                e.reply("❌ У вас нет права менять канал логов.").setEphemeral(true).queue();
                return;
            }
            if (!e.isFromGuild()) {
                e.reply("❌ Выполните эту команду в нужном канале Discord-сервера.").setEphemeral(true).queue();
                return;
            }
            String channelId = e.getChannel().getId();
            Bukkit.getGlobalRegionScheduler().execute(plugin, () -> {
                plugin.getConfig().set("bots.discord.audit-channel-id", channelId);
                plugin.saveConfig();
            });
            e.reply("✅ Этот канал назначен журналом действий бота.").setEphemeral(true).queue();
            e.getChannel().sendMessage("🛡️ **Журнал KachBans включён.** Здесь фиксируются действия Discord- и Telegram-ботов без публикации токенов и одноразовых кодов.").queue();
            return;
        }
        if (command.equals("setpunishments")) {
            if (!plugin.getConfig().getStringList("bots.allowed-discord-ids").contains(e.getUser().getId())) {
                e.reply("❌ У вас нет права менять канал наказаний.").setEphemeral(true).queue();
                return;
            }
            if (!e.isFromGuild()) {
                e.reply("❌ Выполните эту команду в нужном канале Discord-сервера.").setEphemeral(true).queue();
                return;
            }
            String channelId = e.getChannel().getId();
            Bukkit.getGlobalRegionScheduler().execute(plugin, () -> {
                plugin.getConfig().set("bots.discord.punishment-channel-id", channelId);
                plugin.saveConfig();
            });
            e.reply("✅ Этот канал назначен каналом наказаний. Новые наказания будут публиковаться здесь.").setEphemeral(true).queue();
            return;
        }
        Set<String> punishmentCommands = Set.of("warn", "unwarn", "kick", "ban", "unban", "mute", "unmute",
                "tempban", "tempmute", "blacknick", "unblacknick");
        if (punishmentCommands.contains(command)
                && !plugin.getConfig().getStringList("bots.allowed-discord-ids").contains(e.getUser().getId())) {
            e.reply("Ваш Discord ID не входит в список управления ботом.").setEphemeral(true).queue(); return;
        }
        AccessManager.Role role = plugin.getAccessManager().role("discord", e.getUser().getId());
        if (command.equals("role")) {
            if (role != AccessManager.Role.SUPEROWNER) { e.reply("Только суперовнер.").setEphemeral(true).queue(); return; }
            setRemoteRole(e, "discord"); return;
        }
        if (!plugin.getAccessManager().canPunish(role, command)) { e.reply("Нет права на эту команду.").setEphemeral(true).queue(); return; }
        String target = Objects.requireNonNull(e.getOption("player")).getAsString();
        String reason = e.getOption("reason") == null ? "Не указана" : e.getOption("reason").getAsString();
        String time = e.getOption("time") == null ? null : e.getOption("time").getAsString();
        if (command.equals("warn") || command.equals("unwarn")) time = Objects.requireNonNull(e.getOption("category")).getAsString();
        if (command.equals("blacknick")) time = Objects.requireNonNull(e.getOption("task")).getAsString();
        String actor = "Discord:" + e.getUser().getEffectiveName() + " (" + e.getUser().getId() + ")";
        executeRemote(command, target, time, reason, actor);
        e.reply("Команда передана серверу.").setEphemeral(true).queue();
    }

    private void registerDiscordCommands() {
        for (String name : List.of("kick", "ban", "unban", "mute", "unmute")) jda.upsertCommand(name, "Управление наказаниями")
                .addOption(OptionType.STRING, "player", "Ник", true).addOption(OptionType.STRING, "reason", "Причина", false)
                .setDefaultPermissions(DefaultMemberPermissions.DISABLED).queue();
        jda.upsertCommand("warn", "Выдать предупреждение по настроенному пункту")
                .addOption(OptionType.STRING, "player", "Ник", true)
                .addOption(OptionType.STRING, "category", "Ключ пункта из config.yml", true)
                .addOption(OptionType.STRING, "reason", "Комментарий", false)
                .setDefaultPermissions(DefaultMemberPermissions.DISABLED).queue();
        jda.upsertCommand("unwarn", "Снять один активный варн указанного пункта")
                .addOption(OptionType.STRING, "player", "Ник", true)
                .addOption(OptionType.STRING, "category", "Ключ пункта из config.yml", true)
                .addOption(OptionType.STRING, "reason", "Причина снятия", false)
                .setDefaultPermissions(DefaultMemberPermissions.DISABLED).queue();
        for (String name : List.of("tempban", "tempmute")) jda.upsertCommand(name, "Временное наказание")
                .addOption(OptionType.STRING, "player", "Ник", true).addOption(OptionType.STRING, "time", "Срок: 1d/2h/30m", true)
                .addOption(OptionType.STRING, "reason", "Причина", false).setDefaultPermissions(DefaultMemberPermissions.DISABLED).queue();
        jda.upsertCommand("role", "Назначить роль внешнему администратору").addOption(OptionType.STRING, "id", "Discord ID", true)
                .addOption(OptionType.STRING, "role", "helper/moderator/admin/none", true).setDefaultPermissions(DefaultMemberPermissions.DISABLED).queue();
        jda.upsertCommand("blacknick", "Выдать чёрный ник с тяжёлым заданием")
                .addOption(OptionType.STRING, "player", "Ник", true)
                .addOption(OptionType.STRING, "task", "Ключ задания из config.yml", true)
                .addOption(OptionType.STRING, "reason", "Причина", false)
                .setDefaultPermissions(DefaultMemberPermissions.DISABLED).queue();
        jda.upsertCommand("unblacknick", "Снять чёрный ник")
                .addOption(OptionType.STRING, "player", "Ник", true)
                .addOption(OptionType.STRING, "reason", "Причина снятия", false)
                .setDefaultPermissions(DefaultMemberPermissions.DISABLED).queue();
        jda.upsertCommand("setpunishments", "Назначить текущий канал каналом наказаний").setDefaultPermissions(DefaultMemberPermissions.DISABLED).queue();
        jda.upsertCommand("setbotlogs", "Назначить текущий канал журналом действий бота").setDefaultPermissions(DefaultMemberPermissions.DISABLED).queue();
        for (Guild guild : jda.getGuilds()) {
            for (String name : List.of("kick", "ban", "unban", "mute", "unmute")) guild.upsertCommand(name, "Управление наказаниями")
                    .addOption(OptionType.STRING, "player", "Ник", true).addOption(OptionType.STRING, "reason", "Причина", false)
                    .setDefaultPermissions(DefaultMemberPermissions.DISABLED).queue();
            guild.upsertCommand("warn", "Выдать предупреждение по настроенному пункту")
                    .addOption(OptionType.STRING, "player", "Ник", true)
                    .addOption(OptionType.STRING, "category", "Ключ пункта из config.yml", true)
                    .addOption(OptionType.STRING, "reason", "Комментарий", false)
                    .setDefaultPermissions(DefaultMemberPermissions.DISABLED).queue();
            guild.upsertCommand("unwarn", "Снять один активный варн указанного пункта")
                    .addOption(OptionType.STRING, "player", "Ник", true)
                    .addOption(OptionType.STRING, "category", "Ключ пункта из config.yml", true)
                    .addOption(OptionType.STRING, "reason", "Причина снятия", false)
                    .setDefaultPermissions(DefaultMemberPermissions.DISABLED).queue();
            for (String name : List.of("tempban", "tempmute")) guild.upsertCommand(name, "Временное наказание")
                    .addOption(OptionType.STRING, "player", "Ник", true).addOption(OptionType.STRING, "time", "Срок: 1d/2h/30m", true)
                    .addOption(OptionType.STRING, "reason", "Причина", false).setDefaultPermissions(DefaultMemberPermissions.DISABLED).queue();
            guild.upsertCommand("role", "Назначить роль внешнему администратору").addOption(OptionType.STRING, "id", "Discord ID", true)
                    .addOption(OptionType.STRING, "role", "helper/moderator/admin/none", true).setDefaultPermissions(DefaultMemberPermissions.DISABLED).queue();
            guild.upsertCommand("blacknick", "Выдать чёрный ник с тяжёлым заданием")
                    .addOption(OptionType.STRING, "player", "Ник", true)
                    .addOption(OptionType.STRING, "task", "Ключ задания из config.yml", true)
                    .addOption(OptionType.STRING, "reason", "Причина", false)
                    .setDefaultPermissions(DefaultMemberPermissions.DISABLED).queue();
            guild.upsertCommand("unblacknick", "Снять чёрный ник")
                    .addOption(OptionType.STRING, "player", "Ник", true)
                    .addOption(OptionType.STRING, "reason", "Причина снятия", false)
                    .setDefaultPermissions(DefaultMemberPermissions.DISABLED).queue();
            guild.upsertCommand("setpunishments", "Назначить текущий канал каналом наказаний").setDefaultPermissions(DefaultMemberPermissions.DISABLED).queue();
            guild.upsertCommand("setbotlogs", "Назначить текущий канал журналом действий бота").setDefaultPermissions(DefaultMemberPermissions.DISABLED).queue();
        }
    }

    public void auditLog(String message) {
        String safeMessage = safe(message).replace("@", "@\u200B");
        if (safeMessage.length() > 1500) safeMessage = safeMessage.substring(0, 1500) + "…";
        JDA client = jda;
        String channelId = plugin.getConfig().getString("bots.discord.audit-channel-id", "");
        MessageChannel channel = client == null || channelId.isBlank() ? null : client.getChannelById(MessageChannel.class, channelId);
        if (channel != null) channel.sendMessage("`" + Instant.now() + "` • " + safeMessage).queue(null,
                error -> plugin.getLogger().warning("Не удалось записать Discord audit log: " + error.getMessage()));
        String telegramChat = plugin.getConfig().getString("bots.telegram.audit-chat-id", "");
        Long telegramThread = configuredThread("bots.telegram.audit-thread-id");
        if (!telegramChat.isBlank() && (!telegramChat.startsWith("-") || telegramThread != null))
            sendTelegram(telegramChat, telegramThread, "🛡 " + Instant.now() + " • " + safeMessage.replace("`", ""));
    }

    public void sendPunishment(String type, String targetName, String moderatorName, String reason,
                               long issuedAt, long duration, String ip) {
        sendPunishment(type, targetName, moderatorName, reason, issuedAt, duration, ip, null);
    }

    public void sendPunishment(String type, String targetName, String moderatorName, String reason,
                               long issuedAt, long duration, String ip, Map<String, String> extra) {
        JDA client = jda;
        String channelId = plugin.getConfig().getString("bots.discord.punishment-channel-id", "");
        MessageChannel channel = client == null || channelId.isBlank() ? null : client.getChannelById(MessageChannel.class, channelId);
        if (client != null && !channelId.isBlank() && channel == null) {
            plugin.getLogger().warning("Канал наказаний Discord недоступен: " + channelId);
        }
        boolean removal = Set.of("unwarn", "unban", "unmute", "ipunban", "voiceunmute", "unblacknick")
                .contains(type.toLowerCase(Locale.ROOT));
        int color = removal ? 0x22C55E : (duration > 0 ? 0xF59E0B : 0xEF4444);
        String title = switch (type.toLowerCase(Locale.ROOT)) {
            case "warn" -> "Предупреждение";
            case "unwarn" -> "Снятие предупреждения";
            case "voicemute" -> "Блокировка голосового чата";
            case "tempvoicemute" -> "Временная блокировка голосового чата";
            case "voiceunmute" -> "Снятие блокировки голосового чата";
            case "kick" -> "Исключение с сервера";
            case "ban" -> "Блокировка игрока";
            case "tempban" -> "Временная блокировка";
            case "unban" -> "Снятие блокировки";
            case "mute" -> "Блокировка чата";
            case "tempmute" -> "Временная блокировка чата";
            case "unmute" -> "Снятие блокировки чата";
            case "ipban" -> "Блокировка IP";
            case "tempipban" -> "Временная блокировка IP";
            case "ipunban" -> "Снятие блокировки IP";
            case "blacknick" -> "Чёрный ник";
            case "unblacknick" -> "Снятие чёрного ника";
            case "ban_extend" -> "Продление блокировки";
            case "mute_extend" -> "Продление блокировки чата";
            default -> "Наказание";
        };
        if (channel != null) {
            // Доказательства лежат в ThreadLocal и очистятся до срабатывания асинхронного колбэка JDA,
            // поэтому копируем их здесь, а не внутри колбэка.
            List<String> proof = punishmentEvidence.get();
            List<String> proofCopy = proof == null ? List.of() : List.copyOf(proof);
            EmbedBuilder embed = new EmbedBuilder()
                    .setTitle(title)
                    .setColor(color)
                    .addField(targetName == null ? "IP-адрес" : "Игрок", targetName == null ? discordEscape(ip) : discordEscape(targetName), true)
                    .addField("Модератор", discordEscape(readableModerator(moderatorName)), true)
                    .addField("Причина", discordEscape(reason), false)
                    .addField("Обжаловать?", "Свяжитесь с модератором: " + moderatorDiscord(moderatorName), false)
                    .setTimestamp(Instant.ofEpochMilli(issuedAt))
                    .setFooter("KachBans • система наказаний");
            if (duration > 0) embed.addField("Срок", Utils.parseTime(duration), true);
            if (extra != null) {
                for (Map.Entry<String, String> entry : extra.entrySet()) {
                    if (entry.getKey() == null || entry.getValue() == null) continue;
                    embed.addField(discordEscape(entry.getKey()), discordEscape(entry.getValue()), true);
                }
            }
            if (!proofCopy.isEmpty()) {
                embed.setImage(proofCopy.get(0));
                if (proofCopy.size() > 1) {
                    StringBuilder links = new StringBuilder();
                    for (int i = 1; i < proofCopy.size(); i++) links.append("[Файл ").append(i + 1).append("](").append(proofCopy.get(i)).append(") ");
                    embed.addField("Дополнительные доказательства", links.toString(), false);
                }
            }
            sendWithModeratorAuthor(channel, embed, moderatorName);
        }
        String telegramChat = plugin.getConfig().getString("bots.telegram.punishment-chat-id", "");
        Long punishmentThread = configuredThread("bots.telegram.punishment-thread-id");
        if (!telegramChat.isBlank() && (!telegramChat.startsWith("-") || punishmentThread != null)) {
            StringBuilder extraLines = new StringBuilder();
            if (extra != null) {
                for (Map.Entry<String, String> entry : extra.entrySet()) {
                    if (entry.getKey() == null || entry.getValue() == null) continue;
                    extraLines.append("\n").append(entry.getKey()).append(": ").append(entry.getValue());
                }
            }
            String discordName = cachedModeratorName(moderatorName);
            String telegramText = "⚖ " + title + "\n"
                    + (targetName == null ? "IP: " + safe(ip) : "Игрок: " + safe(targetName)) + "\n"
                    + "Модератор: " + readableModerator(moderatorName)
                    + (discordName == null ? "" : "\nDiscord модератора: " + discordName)
                    + "\nПричина: " + safe(reason)
                    + (duration > 0 ? "\nСрок: " + Utils.parseTime(duration) : "")
                    + extraLines
                    + "\nОбжаловать: свяжитесь с Discord модератора " + moderatorDiscord(moderatorName);
            List<String> proof = punishmentEvidence.get();
            if (proof != null && !proof.isEmpty()) sendTelegramPhoto(telegramChat, punishmentThread, proof.get(0), telegramText);
            else sendTelegram(telegramChat, punishmentThread, telegramText);
        }
    }

    /**
     * Подписывает embed ником и аватаркой выдавшего в Discord. Серверный ник и аватар доступны
     * только через Member, поэтому сперва пробуем участника гильдии, затем глобального пользователя,
     * и в крайнем случае подставляем имя модератора с аватаркой бота.
     */
    private void sendWithModeratorAuthor(MessageChannel channel, EmbedBuilder embed, String moderatorName) {
        JDA client = jda;
        String discordId = moderatorDiscordId(moderatorName);
        if (client == null) return;
        if (discordId == null) {
            publishPunishment(channel, embed, readableModerator(moderatorName), botAvatarUrl());
            return;
        }
        Guild guild = punishmentGuild();
        if (guild != null) {
            guild.retrieveMemberById(discordId).queue(
                    member -> publishPunishment(channel, embed, member.getEffectiveName(), member.getEffectiveAvatarUrl()),
                    error -> retrieveGlobalAuthor(channel, embed, moderatorName, discordId));
            return;
        }
        retrieveGlobalAuthor(channel, embed, moderatorName, discordId);
    }

    private void retrieveGlobalAuthor(MessageChannel channel, EmbedBuilder embed, String moderatorName, String discordId) {
        JDA client = jda;
        if (client == null) return;
        client.retrieveUserById(discordId).queue(
                user -> publishPunishment(channel, embed, user.getEffectiveName(), user.getEffectiveAvatarUrl()),
                error -> publishPunishment(channel, embed, readableModerator(moderatorName), botAvatarUrl()));
    }

    private void publishPunishment(MessageChannel channel, EmbedBuilder embed, String authorName, String avatarUrl) {
        embed.setAuthor(safe(authorName), null, avatarUrl);
        channel.sendMessageEmbeds(embed.build()).queue(null,
                error -> plugin.getLogger().warning("Не удалось отправить наказание в Discord: " + error.getMessage()));
    }

    private String botAvatarUrl() {
        JDA client = jda;
        return client == null ? null : client.getSelfUser().getEffectiveAvatarUrl();
    }

    private Guild punishmentGuild() {
        JDA client = jda;
        if (client == null) return null;
        String guildId = plugin.getConfig().getString("bots.discord.guild-id", "");
        if (!guildId.isBlank()) {
            Guild configured = client.getGuildById(guildId);
            if (configured != null) return configured;
        }
        List<Guild> guilds = client.getGuilds();
        return guilds.isEmpty() ? null : guilds.get(0);
    }

    /** Возвращает Discord ID выдавшего: из строки вида "Discord:Имя (id)" или из привязки по нику. */
    private String moderatorDiscordId(String moderator) {
        if (moderator == null || moderator.isBlank() || isServerConsole(moderator)) return null;
        if (moderator.startsWith("Discord:")) {
            int open = moderator.lastIndexOf('('), close = moderator.lastIndexOf(')');
            return open >= 0 && close > open ? moderator.substring(open + 1, close) : null;
        }
        if (moderator.startsWith("Telegram:")) return null;
        return plugin.getAccessManager().discordIdByPlayerName(moderator);
    }

    /** Ник в Discord из кэша JDA, без сетевого запроса — для текстового сообщения в Telegram. */
    private String cachedModeratorName(String moderator) {
        JDA client = jda;
        String discordId = moderatorDiscordId(moderator);
        if (client == null || discordId == null) return null;
        Guild guild = punishmentGuild();
        Member member = guild == null ? null : guild.getMemberById(discordId);
        if (member != null) return member.getEffectiveName();
        User user = client.getUserById(discordId);
        return user == null ? null : user.getEffectiveName();
    }

    private String readableModerator(String moderator) {
        if (moderator == null || moderator.isBlank()) return "Console";
        return moderator.startsWith("Discord:") ? moderator.substring("Discord:".length()) : moderator;
    }
    private String moderatorDiscord(String moderator) {
        if (moderator == null || moderator.isBlank() || moderator.equalsIgnoreCase("Console")) return "Не привязан";
        if (moderator.startsWith("Discord:")) {
            int open = moderator.lastIndexOf('('), close = moderator.lastIndexOf(')');
            if (open >= 0 && close > open) return "<@" + moderator.substring(open + 1, close) + ">";
            return discordEscape(readableModerator(moderator));
        }
        String id = plugin.getAccessManager().discordIdByPlayerName(moderator);
        return id == null ? "Не привязан" : "<@" + id + ">";
    }
    private static String safe(String value) { return value == null || value.isBlank() ? "Не указано" : value; }

    private static String discordEscape(String value) {
        String text = safe(value);
        return text.replace("\\", "\\\\")
                .replace("*", "\\*")
                .replace("_", "\\_")
                .replace("~", "\\~")
                .replace("`", "\\`")
                .replace("|", "\\|")
                .replace(">", "\\>")
                .replace("#", "\\#")
                .replace("[", "\\[")
                .replace("]", "\\]");
    }

    private void setRemoteRole(SlashCommandInteractionEvent e, String platform) {
        String id = Objects.requireNonNull(e.getOption("id")).getAsString();
        String raw = Objects.requireNonNull(e.getOption("role")).getAsString().toUpperCase(Locale.ROOT);
        try { plugin.getAccessManager().setRole(platform, id, raw.equals("NONE") ? null : AccessManager.Role.valueOf(raw)); e.reply("Роль обновлена.").setEphemeral(true).queue(); }
        catch (IllegalArgumentException ex) { e.reply("Роли: helper, moderator, admin, none.").setEphemeral(true).queue(); }
    }

    private void executeRemote(String command, String player, String time, String reason, String actor) {
        Bukkit.getGlobalRegionScheduler().execute(plugin, () -> {
            long duration = (command.equals("tempban") || command.equals("tempmute")) && time != null
                    ? Utils.parseDuration(time) : 0;
            switch (command) {
                case "warn" -> plugin.getPunishmentManager().warnPlayer(player, actor, time, reason, false, false);
                case "unwarn" -> plugin.getPunishmentManager().unwarnPlayer(player, actor, time, reason, false);
                case "kick" -> plugin.getPunishmentManager().kickPlayer(player, actor, reason, false);
                case "ban" -> plugin.getPunishmentManager().banPlayer(player, actor, reason, 0, false);
                case "tempban" -> { if (duration > 0) plugin.getPunishmentManager().banPlayer(player, actor, reason, duration, false); }
                case "unban" -> plugin.getPunishmentManager().unbanPlayer(player, actor, reason, false);
                case "mute" -> plugin.getPunishmentManager().mutePlayer(player, actor, reason, 0, false);
                case "tempmute" -> { if (duration > 0) plugin.getPunishmentManager().mutePlayer(player, actor, reason, duration, false); }
                case "unmute" -> plugin.getPunishmentManager().unmutePlayer(player, actor, reason, false);
                case "blacknick" -> { if (time != null) plugin.getBlackNickManager().issue(player, actor, time, reason, false); }
                case "unblacknick" -> plugin.getBlackNickManager().remove(player, actor, reason, false, false);
            }
        });
    }

    private boolean telegramEnabled() { return plugin.getConfig().getBoolean("bots.telegram.enabled") && !telegramToken().isBlank(); }
    private String telegramToken() { return plugin.getConfig().getString("bots.telegram.token", ""); }
    private void telegramLoop() {
        while (running && telegramEnabled()) try {
            URI uri = URI.create("https://api.telegram.org/bot" + telegramToken() + "/getUpdates?timeout=25&offset=" + telegramOffset);
            String body = http.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(35)).GET().build(), HttpResponse.BodyHandlers.ofString()).body();
            JsonArray updates = JsonParser.parseString(body).getAsJsonObject().getAsJsonArray("result");
            for (JsonElement el : updates) { JsonObject u = el.getAsJsonObject(); telegramOffset = u.get("update_id").getAsLong() + 1; handleTelegram(u); }
        } catch (Exception e) { if (running) try { Thread.sleep(3000); } catch (InterruptedException ignored) { return; } }
    }

    private void handleTelegram(JsonObject update) {
        if (!update.has("message")) return;
        JsonObject m = update.getAsJsonObject("message"), from = m.getAsJsonObject("from");
        String id = from.get("id").getAsString(), chat = m.getAsJsonObject("chat").get("id").getAsString();
        Long threadId = m.has("message_thread_id") ? m.get("message_thread_id").getAsLong() : null;
        String username = from.has("username") ? from.get("username").getAsString().toLowerCase(Locale.ROOT) : "";
        String text = m.has("text") ? m.get("text").getAsString().trim() : "";
        auditLog("Telegram от @" + (username.isBlank() ? "без_username" : username) + " (`" + id + "`) в чате `" + chat
                + "`: " + ((m.has("photo") || m.has("document") || m.has("video")) ? "получено вложение" : (text.startsWith("/confirm") ? "/confirm [код скрыт]" : text)));
        if (text.toLowerCase(Locale.ROOT).startsWith("/confirm ")) { sendTelegram(chat, threadId, plugin.getAccessManager().confirmLink("telegram", id, text.substring(9).trim()) ? "Telegram привязан." : "Код неверен/истёк."); return; }
        if (m.has("photo") || m.has("document") || m.has("video")) { acceptEvidence("telegram", id, List.of("telegram:file")); return; }
        if (!text.startsWith("/")) return;
        String ownerId = plugin.getConfig().getString("telegramSuperOwnerId", plugin.getConfig().getString("bots.superowner-telegram-id", ""));
        String simpleCommand = text.substring(1).split("@|\\s", 2)[0].toLowerCase(Locale.ROOT);
        if (simpleCommand.equals("setpunishments") || simpleCommand.equals("setbotlogs")) {
            if (!id.equals(ownerId)) { sendTelegram(chat, threadId, "Эту настройку может менять только superowner."); return; }
            String path = simpleCommand.equals("setpunishments") ? "bots.telegram.punishment-chat-id" : "bots.telegram.audit-chat-id";
            String threadPath = simpleCommand.equals("setpunishments") ? "bots.telegram.punishment-thread-id" : "bots.telegram.audit-thread-id";
            Bukkit.getGlobalRegionScheduler().execute(plugin, () -> {
                plugin.getConfig().set(path, chat);
                plugin.getConfig().set(threadPath, threadId);
                plugin.saveConfig();
            });
            sendTelegram(chat, threadId, simpleCommand.equals("setpunishments") ? "✅ Этот чат назначен каналом наказаний." : "✅ Этот чат назначен журналом действий бота.");
            return;
        }
        if (!plugin.getConfig().getStringList("telegramAdminChatIds").contains(chat)) { sendTelegram(chat, threadId, "Команды бота в этом чате отключены."); return; }
        String[] p = text.substring(1).split("\\s+", 4); String command = p[0].toLowerCase(Locale.ROOT);
        AccessManager.Role role = plugin.getAccessManager().role("telegram", id);
        boolean idAllowed = plugin.getConfig().getStringList("telegramAllowedUserIds").contains(id);
        if (role != AccessManager.Role.SUPEROWNER && !idAllowed) { sendTelegram(chat, threadId, "Ваш Telegram ID не разрешён для административных команд."); return; }
        if (command.equals("role") && role == AccessManager.Role.SUPEROWNER && p.length >= 3) {
            try { plugin.getAccessManager().setRole("telegram", p[1], p[2].equalsIgnoreCase("none") ? null : AccessManager.Role.valueOf(p[2].toUpperCase(Locale.ROOT))); sendTelegram(chat, threadId, "Роль обновлена."); }
            catch (Exception ex) { sendTelegram(chat, threadId, "Использование: /role ID helper|moderator|admin|none"); } return;
        }
        if (!plugin.getAccessManager().canPunish(role, command)) { sendTelegram(chat, threadId, "Нет права на команду."); return; }
        boolean temp = command.equals("tempban") || command.equals("tempmute"); int need = temp ? 3 : 2;
        if (p.length < need) { sendTelegram(chat, threadId, "Недостаточно аргументов."); return; }
        executeRemote(command, p[1], temp ? p[2] : null, p.length > (temp ? 3 : 2) ? p[temp ? 3 : 2] : "Не указана", "Telegram:" + id);
        sendTelegram(chat, threadId, "Команда передана серверу.");
    }

    private void sendTelegram(String chatId, String text) {
        sendTelegram(chatId, null, text);
    }

    private void sendTelegram(String chatId, Long threadId, String text) {
        if (!telegramEnabled() || chatId == null || chatId.isBlank()) return;
        JsonObject json = new JsonObject(); json.addProperty("chat_id", chatId); json.addProperty("text", text);
        if (threadId != null && threadId > 0) json.addProperty("message_thread_id", threadId);
        HttpRequest req = HttpRequest.newBuilder(URI.create("https://api.telegram.org/bot" + telegramToken() + "/sendMessage"))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(json.toString(), StandardCharsets.UTF_8)).build();
        http.sendAsync(req, HttpResponse.BodyHandlers.discarding());
    }

    private void sendTelegramPhoto(String chatId, Long threadId, String photoUrl, String caption) {
        if (!telegramEnabled() || chatId == null || chatId.isBlank()) return;
        JsonObject json = new JsonObject();
        json.addProperty("chat_id", chatId);
        json.addProperty("photo", photoUrl);
        json.addProperty("caption", caption.length() > 1000 ? caption.substring(0, 1000) : caption);
        if (threadId != null && threadId > 0) json.addProperty("message_thread_id", threadId);
        HttpRequest req = HttpRequest.newBuilder(URI.create("https://api.telegram.org/bot" + telegramToken() + "/sendPhoto"))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(json.toString(), StandardCharsets.UTF_8)).build();
        http.sendAsync(req, HttpResponse.BodyHandlers.discarding());
    }

    private Long configuredThread(String path) {
        long value = plugin.getConfig().getLong(path, 0L);
        return value > 0 ? value : null;
    }

    private void acceptEvidence(String platform, String externalId, List<String> refs) {
        if (!platform.equals("discord")) return;
        String selectedId = selectedEvidenceByDiscord.get(externalId);
        if (selectedId == null) {
            sendEvidenceHint(externalId, "Сначала нажмите кнопку **«Прикрепить доказательства»** под нужным наказанием, затем отправьте файл ещё раз.");
            return;
        }
        Evidence pending = evidence.get(selectedId);
        if (pending == null || pending.approvedAction == null || pending.cancelled
                || pending.deadline < System.currentTimeMillis()
                || !externalId.equals(pending.discordId)) {
            selectedEvidenceByDiscord.remove(externalId, selectedId);
            sendEvidenceHint(externalId, "Выбранная заявка уже недоступна. Нажмите кнопку под актуальным наказанием.");
            return;
        }
        synchronized (pending) {
            if (pending.complete || pending.expired || pending.cancelled) {
                selectedEvidenceByDiscord.remove(externalId, selectedId);
                sendEvidenceHint(externalId, "Эта заявка уже закрыта.");
                return;
            }
            pending.complete = true;
            pending.references.addAll(refs);
        }
        evidence.remove(selectedId, pending);
        selectedEvidenceByDiscord.remove(externalId, selectedId);
        persistEvidence(pending);
        Bukkit.getGlobalRegionScheduler().execute(plugin, () -> {
            punishmentEvidence.set(List.copyOf(refs));
            try { pending.approvedAction.run(); }
            finally { punishmentEvidence.remove(); }
        });
        auditLog("Доказательства #" + pending.id + " приняты от Discord `" + externalId
                + "`; наказание " + pending.type + " применено к " + pending.target);
        sendEvidenceHint(externalId, "✅ Доказательства #" + pending.id + " приняты. Наказание применено.");
    }

    private void sendEvidenceHint(String discordId, String message) {
        if (jda != null) jda.retrieveUserById(discordId).queue(user -> user.openPrivateChannel()
                .queue(channel -> channel.sendMessage(message).queue()));
    }

    private void publishEvidence(Evidence item, List<String> refs) {
        String channelId = plugin.getConfig().getString("bots.discord.punishment-channel-id", "");
        MessageChannel channel = jda == null || channelId.isBlank() ? null : jda.getChannelById(MessageChannel.class, channelId);
        if (channel != null) {
            String body = "📎 **Доказательства #" + item.id + "**\n"
                    + "**Наказание:** " + item.type + "\n"
                    + "**Игрок:** " + discordEscape(item.target) + "\n"
                    + "**Модератор:** " + discordEscape(readableModerator(item.moderator)) + "\n"
                    + String.join("\n", refs);
            channel.sendMessage(body).queue(null,
                    error -> plugin.getLogger().warning("Не удалось опубликовать доказательства в Discord: " + error.getMessage()));
        }
        String telegramChat = plugin.getConfig().getString("bots.telegram.punishment-chat-id", "");
        if (!telegramChat.isBlank()) sendTelegram(telegramChat, configuredThread("bots.telegram.punishment-thread-id"),
                "📎 Доказательства #" + item.id + "\nНаказание: " + item.type + "\nИгрок: " + item.target
                        + "\nМодератор: " + readableModerator(item.moderator) + "\n" + String.join("\n", refs));
    }

    private void expireEvidence(String id) {
        Evidence item = evidence.get(id);
        if (item == null) return;
        synchronized (item) {
            if (item.complete || item.expired || item.cancelled) {
                evidence.remove(id, item);
                return;
            }
            item.expired = true;
            if (!evidence.remove(id, item)) return;
        }
        selectedEvidenceByDiscord.entrySet().removeIf(entry -> entry.getValue().equals(id));
        String alert = "⚠ Нет доказательств по наказанию #" + item.id + ": " + item.type + " → " + item.target + ", сотрудник " + item.moderator + ", причина: " + item.reason;
        String channelId = plugin.getConfig().getString("bots.discord.senior-channel-id", "");
        if (jda != null && !channelId.isBlank()) { MessageChannel channel = jda.getChannelById(MessageChannel.class, channelId); if (channel != null) channel.sendMessage(alert).queue(); }
        List<String> chats = plugin.getConfig().getStringList("telegramNotificationChatIds");
        if (chats.isEmpty()) sendTelegram(plugin.getConfig().getString("bots.telegram.senior-chat-id", ""), alert);
        else chats.forEach(chat -> sendTelegram(chat, alert));
        persistEvidence(item);
    }

    private synchronized void persistEvidence(Evidence item) {
        try {
            Path path = plugin.getDataFolder().toPath().resolve("evidence.jsonl");
            JsonObject j = new JsonObject(); j.addProperty("id", item.id); j.addProperty("type", item.type); j.addProperty("target", item.target); j.addProperty("moderator", item.moderator);
            j.addProperty("reason", item.reason); j.addProperty("deadline", item.deadline); j.addProperty("complete", item.complete); j.addProperty("cancelled", item.cancelled); j.add("references", new Gson().toJsonTree(item.references));
            Files.writeString(path, j + System.lineSeparator(), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception e) { plugin.getLogger().warning("Не удалось сохранить доказательства: " + e.getMessage()); }
    }
    private static final class Evidence {
        final String id, type, target, moderator, reason; final long deadline; final List<String> references = new ArrayList<>(); volatile boolean complete, expired, cancelled;
        final String discordId; final Runnable approvedAction;
        Evidence(String id, String type, String target, String moderator, String reason, long deadline) { this(id, type, target, moderator, reason, deadline, null, null); }
        Evidence(String id, String type, String target, String moderator, String reason, long deadline, String discordId, Runnable approvedAction) {
            this.id=id; this.type=type; this.target=target; this.moderator=moderator; this.reason=reason; this.deadline=deadline;
            this.discordId=discordId; this.approvedAction=approvedAction;
        }
    }
}
