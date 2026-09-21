package dendzyhype.kachanovBans.commands;

import dendzyhype.kachanovBans.KachanovBans;
import dendzyhype.kachanovBans.utils.Utils;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;

public final class LegacyAuthCommandHandler implements CommandExecutor {
    private final KachanovBans plugin;
    private final CommandExecutor legacyImplementation;

    public LegacyAuthCommandHandler(KachanovBans plugin, CommandExecutor legacyImplementation) {
        this.plugin = plugin;
        this.legacyImplementation = legacyImplementation;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (plugin.getAccessManager().legacyAuthenticationEnabled()) {
            return legacyImplementation.onCommand(sender, command, label, args);
        }
        Utils.sendMessage(sender, "<gradient:#8B5CF6:#22D3EE>KachBans</gradient> <dark_gray>›</dark_gray> "
                + "<gray>Встроенная авторизация отключена. Discord проверяется через основного бота проходок.</gray>");
        return true;
    }
}
