package dendzyhype.kachanovBans.commands;

import dendzyhype.kachanovBans.KachanovBans;
import dendzyhype.kachanovBans.integrations.PlasmoVoiceMuteService.VoiceMute;
import dendzyhype.kachanovBans.utils.Utils;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;

import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class VoiceQueryCommandHandler implements CommandExecutor {
    public static final Set<String> COMMANDS = Set.of("vmutelist", "checkvmute");

    private final KachanovBans plugin;

    public VoiceQueryCommandHandler(KachanovBans plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String name = command.getName().toLowerCase(Locale.ROOT);
        if (!sender.hasPermission("kachanovbans." + name)) {
            Utils.sendMessage(sender, plugin.getPluginConfig().getMessage("general.no-permission"));
            return true;
        }
        if (!plugin.getPlasmoVoiceMuteService().isAvailable()) {
            Utils.sendMessage(sender, plugin.getPluginConfig().getMessage("vmutelist.unavailable"));
            return true;
        }
        if (name.equals("checkvmute")) {
            if (args.length < 1) {
                Utils.sendMessage(sender, plugin.getPluginConfig().getMessage("general.invalid-arguments", "/checkvmute <ник>"));
                return true;
            }
            showCheckVoiceMute(sender, args[0]);
            return true;
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("close")) return true;
        showVoiceMuteList(sender, page(args));
        return true;
    }

    private void showVoiceMuteList(CommandSender sender, int requestedPage) {
        List<VoiceMute> mutes = plugin.getPlasmoVoiceMuteService().getActiveMutes();
        if (mutes.isEmpty()) {
            Utils.sendMessage(sender, plugin.getPluginConfig().getMessage("vmutelist.empty"));
            return;
        }
        int perPage = plugin.getPluginConfig().getItemsPerPage();
        int totalPages = Utils.getTotalPages(mutes.size(), perPage);
        int page = Math.min(Math.max(1, requestedPage), totalPages);
        Component message = Component.text()
                .append(plugin.getPluginConfig().getMessage("vmutelist.header", page, totalPages))
                .append(Component.newline())
                .build();
        for (VoiceMute mute : Utils.paginate(mutes, page, perPage)) {
            String expiry = mute.expiry() == 0 ? "навсегда" : Utils.formatDate(mute.expiry());
            message = message.append(plugin.getPluginConfig().getMessage(
                    mute.expiry() == 0 ? "vmutelist.entry-permanent" : "vmutelist.entry",
                    mute.playerName(), mute.moderatorName(), expiry, mute.reason()
            )).append(Component.newline());
        }
        if (page > 1) {
            message = message.append(pageButton("/vmutelist " + (page - 1), plugin.getPluginConfig().getButtonPrev(), "Предыдущая страница"));
        }
        if (page < totalPages) {
            if (page > 1) message = message.append(Component.space());
            message = message.append(pageButton("/vmutelist " + (page + 1), plugin.getPluginConfig().getButtonNext(), "Следующая страница"));
        }
        sender.sendMessage(message);
    }

    private void showCheckVoiceMute(CommandSender sender, String playerName) {
        VoiceMute mute = plugin.getPlasmoVoiceMuteService().findActiveMute(playerName).orElse(null);
        if (mute == null) {
            Utils.sendMessage(sender, plugin.getPluginConfig().getMessage("checkvmute.not-muted", playerName));
            return;
        }
        String remaining = mute.expiry() == 0
                ? "навсегда"
                : Utils.parseTime(Math.max(0, mute.expiry() - System.currentTimeMillis()));
        Utils.sendMessage(sender, plugin.getPluginConfig().getMessage(
                "checkvmute.muted", mute.playerName(), mute.moderatorName(), mute.reason(),
                Utils.formatDate(mute.issuedAt()), remaining
        ));
    }

    private Component pageButton(String command, String text, String hover) {
        return Utils.getComponent(text)
                .clickEvent(ClickEvent.runCommand(command))
                .hoverEvent(HoverEvent.showText(Component.text(hover)));
    }

    private int page(String[] args) {
        if (args.length == 0) return 1;
        try { return Math.max(1, Integer.parseInt(args[0])); }
        catch (NumberFormatException ignored) { return 1; }
    }
}
