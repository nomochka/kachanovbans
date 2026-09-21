package dendzyhype.kachanovBans.commands;

import dendzyhype.kachanovBans.KachanovBans;
import dendzyhype.kachanovBans.utils.Utils;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Locale;
import java.util.Set;

public final class CommandRouter implements CommandExecutor {
    private static final Set<String> LEGACY_AUTH_COMMANDS = Set.of("access", "2fa", "ds", "tg");
    private static final Set<String> STAFF_LINK_COMMANDS = Set.of(
            "warn", "unwarn", "kick", "ban", "tempban", "unban", "mute", "tempmute", "unmute",
            "ipban", "tempipban", "ipunban", "punishconfirm", "unblacknick"
    );

    private final KachanovBans plugin;
    private final CommandHandler punishmentCommands;
    private final QueryCommandHandler queryCommands;
    private final VoiceQueryCommandHandler voiceQueryCommands;
    private final LegacyAuthCommandHandler legacyAuthCommands;
    private final AdminCommandHandler adminCommands;
    private final BlackNickCommandHandler blackNickCommands;

    public CommandRouter(KachanovBans plugin) {
        this.plugin = plugin;
        this.punishmentCommands = new CommandHandler(plugin);
        this.queryCommands = new QueryCommandHandler(plugin, punishmentCommands);
        this.voiceQueryCommands = new VoiceQueryCommandHandler(plugin);
        this.legacyAuthCommands = new LegacyAuthCommandHandler(plugin, punishmentCommands);
        this.adminCommands = new AdminCommandHandler(plugin);
        this.blackNickCommands = new BlackNickCommandHandler(plugin);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String name = command.getName().toLowerCase(Locale.ROOT);
        if (LEGACY_AUTH_COMMANDS.contains(name)) {
            return legacyAuthCommands.onCommand(sender, command, label, args);
        }
        if (name.equals("kachanovbans")) {
            return adminCommands.onCommand(sender, command, label, args);
        }
        if (QueryCommandHandler.COMMANDS.contains(name)) {
            return queryCommands.onCommand(sender, command, label, args);
        }
        if (VoiceQueryCommandHandler.COMMANDS.contains(name)) {
            return voiceQueryCommands.onCommand(sender, command, label, args);
        }
        // Выдача чёрного ника требует привязки, а просмотр своего прогресса — нет.
        boolean blackNickIssue = name.equals("blacknick") && args.length >= 2;
        if (sender instanceof Player player && (STAFF_LINK_COMMANDS.contains(name) || blackNickIssue)
                && !plugin.getAccessManager().canUseStaffCommands(player)) {
            Utils.sendMessage(player, "<gradient:#8B5CF6:#22D3EE>KachBans</gradient> <dark_gray>›</dark_gray> "
                    + "<red>Discord не привязан.</red> "
                    + "<gray>Получите проходку через основного бота и повторите команду.</gray>");
            return true;
        }
        if (BlackNickCommandHandler.COMMANDS.contains(name)) {
            return blackNickCommands.onCommand(sender, command, label, args);
        }
        return punishmentCommands.onCommand(sender, command, label, args);
    }
}
