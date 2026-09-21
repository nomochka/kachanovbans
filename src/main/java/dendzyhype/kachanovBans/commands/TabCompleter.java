package dendzyhype.kachanovBans.commands;

import dendzyhype.kachanovBans.KachanovBans;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class TabCompleter implements org.bukkit.command.TabCompleter {

    private final KachanovBans plugin;

    public TabCompleter(KachanovBans plugin) {
        this.plugin = plugin;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        String command = cmd.getName().toLowerCase();
        List<String> completions = new ArrayList<>();

        if (!plugin.getAccessManager().legacyAuthenticationEnabled()
                && java.util.Set.of("access", "2fa", "ds", "tg").contains(command)) {
            return completions;
        }

        switch (command) {
            case "warn":
            case "unwarn":
                if (args.length == 1) {
                    completions.addAll(getPlayerNames(args[0]));
                } else if (args.length == 2) {
                    completions.addAll(filter(new ArrayList<>(plugin.getPunishmentManager().warningCategories()), args[1]));
                } else {
                    if (!java.util.Arrays.asList(args).contains("-s")) completions.add("-s");
                    if (command.equals("warn") && !java.util.Arrays.asList(args).contains("-a")) completions.add("-a");
                }
                break;

            case "staffhistory":
                if (args.length == 1) completions.addAll(getPlayerNames(args[0]));
                break;

            case "2fa":
                if (args.length == 1) {
                    completions.add("on");
                    completions.add("off");
                    completions.add("status");
                    completions = filter(completions, args[0]);
                }
                break;

            case "kick":
            case "ban":
            case "tempban":
            case "mute":
            case "tempmute":
            case "ipban":
            case "tempipban":
                if (args.length == 1) {
                    completions.addAll(getPlayerNames(args[0]));
                } else if (args.length == 2 && (command.equals("tempban") || command.equals("tempmute") || command.equals("tempipban"))) {
                    completions.add("1h");
                    completions.add("2h");
                    completions.add("4h");
                    completions.add("8h");
                    completions.add("12h");
                    completions.add("24h");
                    completions.add("1d");
                    completions.add("2d");
                    completions.add("7d");
                    completions.add("30d");
                    completions.add("1h30m");
                    completions.add("4h15m");
                    completions = filter(completions, args[1]);
                } else if (args.length >= 2) {
                    if (!java.util.Arrays.asList(args).contains("-s")) completions.add("-s");
                    if (!command.equals("kick") && !java.util.Arrays.asList(args).contains("-a")) completions.add("-a");
                }
                break;

            case "unban":
            case "unmute":
            case "ipunban":
                if (args.length == 1) {
                    completions.addAll(getPlayerNames(args[0]));
                }
                break;

            case "banlist":
            case "mutelist":
            case "vmutelist":
                if (args.length == 1) {
                    completions.add("1");
                    completions.add("2");
                    completions.add("3");
                    completions = filter(completions, args[0]);
                }
                break;

            case "history":
            case "iphistory":
            case "dupeip":
                if (args.length == 1) {
                    completions.addAll(getPlayerNames(args[0]));
                } else if (args.length == 2) {
                    completions.add("1");
                    completions.add("2");
                    completions.add("3");
                    completions = filter(completions, args[1]);
                }
                break;

            case "checkmute":
            case "checkvmute":
            case "checkban":
                if (args.length == 1) {
                    completions.addAll(getPlayerNames(args[0]));
                }
                break;

            case "blacknick":
                if (args.length == 1) {
                    completions.addAll(getPlayerNames(args[0]));
                } else if (args.length == 2) {
                    completions.addAll(filter(new ArrayList<>(plugin.getBlackNickManager().taskKeys()), args[1]));
                } else if (!java.util.Arrays.asList(args).contains("-s")) {
                    completions.add("-s");
                }
                break;

            case "unblacknick":
                if (args.length == 1) {
                    List<String> punished = new ArrayList<>();
                    plugin.getBlackNickManager().all().forEach(record -> punished.add(record.playerName()));
                    completions.addAll(filter(punished, args[0]));
                } else if (!java.util.Arrays.asList(args).contains("-s")) {
                    completions.add("-s");
                }
                break;

            case "punishconfirm":
                break;

            case "kachanovbans":
                if (args.length == 1) {
                    completions.add("reload");
                    completions = filter(completions, args[0]);
                }
                break;
        }

        return completions;
    }

    private List<String> getPlayerNames(String partial) {
        List<String> names = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            names.add(p.getName());
        }
        return filter(names, partial);
    }

    private List<String> filter(List<String> list, String partial) {
        return list.stream()
                .filter(s -> s.toLowerCase().startsWith(partial.toLowerCase()))
                .collect(Collectors.toList());
    }
}
