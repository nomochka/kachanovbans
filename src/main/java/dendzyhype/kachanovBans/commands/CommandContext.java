package dendzyhype.kachanovBans.commands;

import dendzyhype.kachanovBans.KachanovBans;
import dendzyhype.kachanovBans.utils.Utils;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public final class CommandContext {
    private final KachanovBans plugin;

    public CommandContext(KachanovBans plugin) {
        this.plugin = plugin;
    }

    public boolean canUseSilent(CommandSender sender, boolean silent) {
        if (!silent || !(sender instanceof Player player) || Utils.hasExactPermission(player, "kachbans.silent")) {
            return true;
        }
        Utils.sendMessage(sender, plugin.getPluginConfig().getMessage("general.no-permission"));
        return false;
    }

    public boolean canSkipEvidence(CommandSender sender, boolean skipEvidence) {
        if (!skipEvidence || !(sender instanceof Player player)
                || Utils.hasExactPermission(player, "kachbans.evidence.bypass")) {
            return true;
        }
        Utils.sendMessage(sender, plugin.getPluginConfig().getMessage("general.no-permission"));
        return false;
    }
}
