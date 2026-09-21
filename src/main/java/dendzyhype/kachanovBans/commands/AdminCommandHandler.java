package dendzyhype.kachanovBans.commands;

import dendzyhype.kachanovBans.KachanovBans;
import dendzyhype.kachanovBans.migration.EssentialsMigration;
import dendzyhype.kachanovBans.utils.Utils;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public final class AdminCommandHandler implements CommandExecutor {
    private final KachanovBans plugin;

    public AdminCommandHandler(KachanovBans plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 2 && args[0].equalsIgnoreCase("migrate") && args[1].equalsIgnoreCase("essentials")) {
            if (sender instanceof Player player && !player.isOp()) {
                Utils.sendMessage(sender, "<gradient:#8B5CF6:#22D3EE>KachBans</gradient> <dark_gray>›</dark_gray> <red>Команда доступна только OP.</red>");
                return true;
            }
            EssentialsMigration.Result result = new EssentialsMigration(plugin).migrate();
            String sourceNote = result.essentialsDataFound() ? ""
                    : " <yellow>Папка Essentials/userdata не найдена: импортированы только баны из banned-players.json.</yellow>";
            Utils.sendMessage(sender, "<gradient:#8B5CF6:#22D3EE>KachBans</gradient> <dark_gray>›</dark_gray> <green>Миграция завершена.</green> "
                    + "<gray>Банов:</gray> <white>" + result.bans() + "</white><gray>, мутов:</gray> <white>" + result.mutes()
                    + "</white><gray>, пропущено:</gray> <white>" + result.skipped() + "</white><gray>, ошибок:</gray> <white>"
                    + result.errors() + "</white>." + sourceNote);
            return true;
        }
        if (!sender.hasPermission("kachanovbans.reload")) {
            Utils.sendMessage(sender, plugin.getPluginConfig().getMessage("general.no-permission"));
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            plugin.getPluginConfig().reload();
            Utils.sendMessage(sender, plugin.getPluginConfig().getMessage("general.reloaded"));
            return true;
        }
        Utils.sendMessage(sender, plugin.getPluginConfig().getMessage(
                "general.invalid-arguments", "/kachanovbans reload | /kachanovbans migrate essentials"));
        return true;
    }
}
