package dendzyhype.kachanovBans.blacknick;

import dendzyhype.kachanovBans.KachanovBans;
import dendzyhype.kachanovBans.data.Models;
import dendzyhype.kachanovBans.utils.Utils;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class BlackNickListeners implements Listener {

    private final KachanovBans plugin;
    private final BlackNickManager manager;

    public BlackNickListeners(KachanovBans plugin) {
        this.plugin = plugin;
        this.manager = plugin.getBlackNickManager();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (!manager.isBlackNicked(player.getName())) return;
        manager.apply(player);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        manager.flushProgress(player.getName());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void restrictCommands(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        Models.BlackNick record = record(player);
        if (record == null) return;
        if (Utils.hasExactPermission(player, BlackNickManager.EXEMPT_PERMISSION)) return;
        String root = commandRoot(event.getMessage());
        if (root.isBlank() || allowedCommands().contains(root)) return;
        event.setCancelled(true);
        Utils.sendMessage(player, plugin.getPluginConfig().getMessage("blacknick.command-blocked",
                manager.taskDisplayName(record.taskKey())));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        Models.BlackNick record = record(player);
        if (record == null) return;
        Material broken = event.getBlock().getType();
        if (!dropWhitelist().contains(broken)) {
            event.setDropItems(false);
            event.setExpToDrop(0);
        }
        BlackNickManager.Task task = manager.task(record.taskKey());
        if (task != null && task.type().equals("break") && task.materials().contains(broken)) {
            manager.addProgress(player, "break", 1);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFish(PlayerFishEvent event) {
        if (event.getState() != PlayerFishEvent.State.CAUGHT_FISH) return;
        Player player = event.getPlayer();
        if (record(player) == null) return;
        manager.addProgress(player, "fish", 1);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDeath(EntityDeathEvent event) {
        Player killer = event.getEntity().getKiller();
        if (killer == null) return;
        Models.BlackNick record = record(killer);
        if (record == null) return;
        BlackNickManager.Task task = manager.task(record.taskKey());
        if (task == null || !task.type().equals("kill")) return;
        if (!task.entities().contains(event.getEntity().getType())) return;
        manager.addProgress(killer, "kill", 1);
    }

    private Models.BlackNick record(Player player) {
        return manager.get(player.getName());
    }

    private Set<String> allowedCommands() {
        Set<String> allowed = new HashSet<>();
        allowed.add("blacknick");
        for (String raw : plugin.getConfig().getStringList("blacknick.allowed-commands")) {
            String root = commandRoot(raw);
            if (!root.isBlank()) allowed.add(root);
        }
        return allowed;
    }

    private Set<Material> dropWhitelist() {
        Set<Material> whitelist = new HashSet<>();
        List<String> configured = plugin.getConfig().getStringList("blacknick.drop-whitelist");
        for (String raw : configured) {
            Material material = Material.matchMaterial(raw.toUpperCase(Locale.ROOT));
            if (material != null) whitelist.add(material);
        }
        return whitelist;
    }

    private String commandRoot(String command) {
        if (command == null) return "";
        String value = command.trim();
        if (value.startsWith("/")) value = value.substring(1);
        int space = value.indexOf(' ');
        String root = (space < 0 ? value : value.substring(0, space)).toLowerCase(Locale.ROOT);
        int namespace = root.indexOf(':');
        return namespace < 0 ? root : root.substring(namespace + 1);
    }
}
