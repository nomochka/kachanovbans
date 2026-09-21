package dendzyhype.kachanovBans.commands;

import dendzyhype.kachanovBans.KachanovBans;
import dendzyhype.kachanovBans.blacknick.BlackNickManager;
import dendzyhype.kachanovBans.data.Models;
import dendzyhype.kachanovBans.utils.Utils;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.RemoteConsoleCommandSender;
import org.bukkit.entity.Player;

import java.util.Locale;
import java.util.Set;

public final class BlackNickCommandHandler implements CommandExecutor {
    public static final Set<String> COMMANDS = Set.of("blacknick", "unblacknick");

    private final KachanovBans plugin;
    private final CommandContext commandContext;

    public BlackNickCommandHandler(KachanovBans plugin) {
        this.plugin = plugin;
        this.commandContext = new CommandContext(plugin);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String name = command.getName().toLowerCase(Locale.ROOT);
        BlackNickManager manager = plugin.getBlackNickManager();
        if (!manager.enabled()) {
            Utils.sendMessage(sender, plugin.getPluginConfig().getMessage("blacknick.disabled"));
            return true;
        }

        // Свой прогресс доступен без прав: иначе наказанный не увидит, что от него требуется.
        if (name.equals("blacknick") && args.length == 0) {
            if (!(sender instanceof Player player)) {
                Utils.sendMessage(sender, plugin.getPluginConfig().getMessage("general.invalid-arguments",
                        "/blacknick <ник> <задание> [причина] [-s]"));
                return true;
            }
            showProgress(sender, player.getName());
            return true;
        }

        if (!sender.hasPermission("kachanovbans." + name)) {
            Utils.sendMessage(sender, plugin.getPluginConfig().getMessage("general.no-permission"));
            return true;
        }

        String moderatorName = sender instanceof Player
                ? sender.getName()
                : sender instanceof RemoteConsoleCommandSender ? "RCON" : "Console";

        if (name.equals("unblacknick")) {
            if (args.length < 1) {
                Utils.sendMessage(sender, plugin.getPluginConfig().getMessage("general.invalid-arguments",
                        "/unblacknick <ник> [причина] [-s]"));
                return true;
            }
            boolean silent = Utils.isSilent(args);
            if (!commandContext.canUseSilent(sender, silent)) return true;
            String reason = Utils.buildReason(args, 1, silent);
            if (!manager.isBlackNicked(args[0])) {
                Utils.sendMessage(sender, plugin.getPluginConfig().getMessage("unblacknick.not-found", args[0]));
                return true;
            }
            boolean removed = manager.remove(args[0], moderatorName, reason, silent, false);
            if (removed) Utils.sendMessage(sender, plugin.getPluginConfig().getMessage("unblacknick.success", args[0]));
            return true;
        }

        // /blacknick <ник> — посмотреть прогресс чужого задания.
        if (args.length == 1) {
            showProgress(sender, args[0]);
            return true;
        }

        boolean silent = Utils.isSilent(args);
        if (!commandContext.canUseSilent(sender, silent)) return true;
        String targetName = args[0];
        String taskKey = args[1].toLowerCase(Locale.ROOT);
        if (manager.task(taskKey) == null) {
            Utils.sendMessage(sender, plugin.getPluginConfig().getMessage("blacknick.unknown-task",
                    String.join(", ", manager.taskKeys())));
            return true;
        }
        if (sender instanceof Player && targetName.equalsIgnoreCase(sender.getName())) {
            Utils.sendMessage(sender, plugin.getPluginConfig().getMessage("blacknick.self"));
            return true;
        }
        if (manager.isBlackNicked(targetName)) {
            Utils.sendMessage(sender, plugin.getPluginConfig().getMessage("blacknick.already", targetName));
            return true;
        }
        String reason = Utils.buildReason(args, 2, silent);
        boolean applied = manager.issue(targetName, moderatorName, taskKey, reason, silent);
        if (applied) {
            Utils.sendMessage(sender, plugin.getPluginConfig().getMessage("blacknick.success",
                    targetName, manager.taskDisplayName(taskKey)));
        }
        return true;
    }

    private void showProgress(CommandSender sender, String targetName) {
        BlackNickManager manager = plugin.getBlackNickManager();
        Models.BlackNick record = manager.get(targetName);
        if (record == null) {
            Utils.sendMessage(sender, plugin.getPluginConfig().getMessage("blacknick.not-found", targetName));
            return;
        }
        Utils.sendMessage(sender, plugin.getPluginConfig().getMessage("blacknick.progress",
                record.playerName(),
                manager.taskDisplayName(record.taskKey()),
                record.progress(),
                record.target(),
                record.remaining(),
                Utils.formatDate(record.issuedAt()),
                record.moderatorName() == null ? "Console" : record.moderatorName()));
    }
}
