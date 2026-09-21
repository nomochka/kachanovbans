package dendzyhype.kachanovBans.commands;

import dendzyhype.kachanovBans.KachanovBans;
import dendzyhype.kachanovBans.utils.Utils;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;

import java.util.Locale;
import java.util.Set;

public final class QueryCommandHandler implements CommandExecutor {
    public static final Set<String> COMMANDS = Set.of(
            "staffhistory", "banlist", "mutelist", "history", "iphistory", "dupeip", "checkmute", "checkban"
    );

    private final KachanovBans plugin;
    private final CommandHandler views;

    public QueryCommandHandler(KachanovBans plugin, CommandHandler views) {
        this.plugin = plugin;
        this.views = views;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String name = command.getName().toLowerCase(Locale.ROOT);
        if (!sender.hasPermission("kachanovbans." + name)) {
            Utils.sendMessage(sender, plugin.getPluginConfig().getMessage("general.no-permission"));
            return true;
        }
        if (name.equals("checkmute") || name.equals("checkban")) {
            if (args.length < 1) return usage(sender, "/" + name + " <ник>");
            if (!plugin.getDatabase().isPlayerExists(args[0])) {
                Utils.sendMessage(sender, plugin.getPluginConfig().getMessage("general.invalid-player", args[0]));
                return true;
            }
            if (name.equals("checkmute")) views.showCheckMute(sender, args[0]);
            else views.showCheckBan(sender, args[0]);
            return true;
        }
        if (name.equals("banlist") || name.equals("mutelist")) {
            if (args.length > 0 && args[0].equalsIgnoreCase("close")) return true;
            int page = page(args, 0);
            if (name.equals("banlist")) views.showBanList(sender, page);
            else views.showMuteList(sender, page);
            return true;
        }
        if (args.length < 1) {
            String usage = switch (name) {
                case "history" -> "/history <ник> [страница]";
                case "staffhistory" -> "/staffhistory <сотрудник> [страница]";
                case "iphistory" -> "/iphistory <ник> [страница]";
                default -> "/dupeip <ник> [страница]";
            };
            return usage(sender, usage);
        }
        int page = page(args, 1);
        switch (name) {
            case "history" -> views.showHistory(sender, args[0], page);
            case "staffhistory" -> views.showStaffHistory(sender, args[0], page);
            case "iphistory" -> views.showIpHistory(sender, args[0], page);
            case "dupeip" -> views.showDupeIp(sender, args[0], page);
            default -> { return false; }
        }
        return true;
    }

    private boolean usage(CommandSender sender, String usage) {
        Utils.sendMessage(sender, plugin.getPluginConfig().getMessage("general.invalid-arguments", usage));
        return true;
    }

    private int page(String[] args, int index) {
        if (index >= args.length || args[index].equalsIgnoreCase("close")) return 1;
        try { return Math.max(1, Integer.parseInt(args[index])); }
        catch (NumberFormatException ignored) { return 1; }
    }
}
