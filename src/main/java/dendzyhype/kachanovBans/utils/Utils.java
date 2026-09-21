package dendzyhype.kachanovBans.utils;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.cacheddata.Result;
import net.luckperms.api.model.user.User;
import net.luckperms.api.node.Node;
import net.luckperms.api.util.Tristate;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Utils {

    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();
    private static final Pattern TIME_PATTERN = Pattern.compile("(\\d+)([dhms])");
    private static final String DEFAULT_TIMEZONE = "Europe/Moscow";

    public static void sendMessage(CommandSender sender, String message) {
        if (message == null || message.isEmpty()) return;
        sender.sendMessage(MINI_MESSAGE.deserialize(message));
    }

    public static void sendMessage(CommandSender sender, Component component) {
        if (component == null) return;
        sender.sendMessage(component);
    }

    public static String parseTime(long millis) {
        long seconds = millis / 1000;
        long minutes = seconds / 60;
        long hours = minutes / 60;
        long days = hours / 24;
        seconds %= 60;
        minutes %= 60;
        hours %= 24;
        StringBuilder sb = new StringBuilder();
        if (days > 0) sb.append(days).append("д ");
        if (hours > 0) sb.append(hours).append("ч ");
        if (minutes > 0) sb.append(minutes).append("м ");
        if (seconds > 0 || sb.isEmpty()) sb.append(seconds).append("с");
        return sb.toString().trim();
    }

    public static long parseDuration(String input) throws IllegalArgumentException {
        if (input == null || input.isEmpty()) throw new IllegalArgumentException("Пустое время");
        Matcher matcher = TIME_PATTERN.matcher(input);
        long total = 0;
        boolean found = false;
        int parsedUntil = 0;
        while (matcher.find()) {
            if (matcher.start() != parsedUntil) throw new IllegalArgumentException("Неверный формат времени");
            int value = Integer.parseInt(matcher.group(1));
            char unit = matcher.group(2).charAt(0);
            long multiplier = switch (unit) {
                case 'd' -> 86400000L;
                case 'h' -> 3600000L;
                case 'm' -> 60000L;
                case 's' -> 1000L;
                default -> throw new IllegalArgumentException("Неизвестная единица: " + unit);
            };
            try {
                total = Math.addExact(total, Math.multiplyExact((long) value, multiplier));
            } catch (ArithmeticException exception) {
                throw new IllegalArgumentException("Слишком большой срок", exception);
            }
            found = true;
            parsedUntil = matcher.end();
        }
        if (!found || parsedUntil != input.length() || total <= 0) {
            throw new IllegalArgumentException("Неверный формат времени");
        }
        return total;
    }

    public static String formatDate(long timestamp) {
        if (timestamp == 0) return "навсегда";
        ZonedDateTime zdt = ZonedDateTime.ofInstant(new Date(timestamp).toInstant(),
                ZoneId.of(DEFAULT_TIMEZONE));
        return zdt.format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss"));
    }

    public static Component getComponent(String text) {
        return MINI_MESSAGE.deserialize(text);
    }

    public static String fromComponent(Component component) {
        return LegacyComponentSerializer.legacySection().serialize(component);
    }

    public static boolean isSilent(String[] args) {
        return Arrays.stream(args).anyMatch(arg -> arg.equalsIgnoreCase("-s"));
    }

    public static boolean skipsEvidence(String[] args) {
        return Arrays.stream(args).anyMatch(arg -> arg.equalsIgnoreCase("-a"));
    }

    public static boolean hasExactPermission(Player player, String permission) {
        LuckPerms luckPerms = Bukkit.getServicesManager().load(LuckPerms.class);
        if (luckPerms == null) return false;
        User user = luckPerms.getUserManager().getUser(player.getUniqueId());
        return hasExactPermission(user, permission);
    }

    public static boolean hasExactPermission(String playerName, String permission) {
        Player online = Bukkit.getPlayerExact(playerName);
        if (online != null) return hasExactPermission(online, permission);
        LuckPerms luckPerms = Bukkit.getServicesManager().load(LuckPerms.class);
        if (luckPerms == null) return false;
        try {
            User user = luckPerms.getUserManager().getUser(playerName);
            if (user == null) {
                UUID uuid = luckPerms.getUserManager().lookupUniqueId(playerName).get(3, TimeUnit.SECONDS);
                if (uuid == null) return false;
                user = luckPerms.getUserManager().loadUser(uuid, playerName).get(3, TimeUnit.SECONDS);
            }
            return hasExactPermission(user, permission);
        } catch (Exception ignored) {
            return false;
        }
    }

    private static boolean hasExactPermission(User user, String permission) {
        if (user == null) return false;
        Result<Tristate, Node> result = user.getCachedData().getPermissionData().queryPermission(permission);
        Node source = result.node();
        return result.result() == Tristate.TRUE
                && source != null
                && source.getValue()
                && source.getKey().equalsIgnoreCase(permission);
    }

    public static String buildReason(String[] args, int start, boolean silent) {
        if (start >= args.length) return "Не указана";
        return Arrays.stream(Arrays.copyOfRange(args, start, args.length))
                .filter(arg -> !arg.equalsIgnoreCase("-s") && !arg.equalsIgnoreCase("-a"))
                .collect(java.util.stream.Collectors.joining(" "));
    }

    public static String getPlayerName(UUID uuid) {
        Player p = Bukkit.getPlayer(uuid);
        if (p != null) return p.getName();
        return null;
    }

    public static <T> List<T> paginate(List<T> list, int page, int perPage) {
        int start = (page - 1) * perPage;
        if (start >= list.size()) return Collections.emptyList();
        int end = Math.min(start + perPage, list.size());
        return list.subList(start, end);
    }

    public static int getTotalPages(int total, int perPage) {
        return (int) Math.ceil((double) total / perPage);
    }
}
