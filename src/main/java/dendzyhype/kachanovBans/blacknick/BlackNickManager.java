package dendzyhype.kachanovBans.blacknick;

import dendzyhype.kachanovBans.KachanovBans;
import dendzyhype.kachanovBans.data.IDatabase;
import dendzyhype.kachanovBans.data.Models;
import dendzyhype.kachanovBans.utils.Utils;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;


public final class BlackNickManager {

    public static final String EXEMPT_PERMISSION = "kachbans.blacknick.exempt";
    private static final double DEFAULT_MAX_HEALTH = 20.0D;

    public record Task(String key, String displayName, String type, long amount,
                       Set<Material> materials, Set<EntityType> entities) {}

    private final KachanovBans plugin;
    private final IDatabase db;
    private final boolean debug;
    private final Map<String, Models.BlackNick> active = new ConcurrentHashMap<>();
    private final Map<String, Integer> unsavedSteps = new ConcurrentHashMap<>();

    public BlackNickManager(KachanovBans plugin) {
        this.plugin = plugin;
        this.db = plugin.getDatabase();
        this.debug = plugin.getPluginConfig().isDebug();
    }

    public void loadActive() {
        active.clear();
        for (Models.BlackNick record : db.getActiveBlackNicks()) {
            if (record.playerName() != null) active.put(key(record.playerName()), record);
        }
        if (debug) plugin.getLogger().info("[BlackNick] Загружено активных чёрных ников: " + active.size());
    }

    public boolean enabled() {
        return plugin.getConfig().getBoolean("blacknick.enabled", true);
    }

    public double hearts() {
        return Math.max(0.5D, plugin.getConfig().getDouble("blacknick.hearts", 4.0D));
    }

    public boolean isBlackNicked(String playerName) {
        return playerName != null && active.containsKey(key(playerName));
    }

    public Models.BlackNick get(String playerName) {
        return playerName == null ? null : active.get(key(playerName));
    }

    public List<Models.BlackNick> all() {
        return new ArrayList<>(active.values());
    }

    public Set<String> taskKeys() {
        ConfigurationSection tasks = plugin.getConfig().getConfigurationSection("blacknick.tasks");
        return tasks == null ? Set.of() : new TreeSet<>(tasks.getKeys(false));
    }

    public Task task(String rawKey) {
        if (rawKey == null) return null;
        String key = rawKey.toLowerCase(Locale.ROOT);
        ConfigurationSection section = plugin.getConfig().getConfigurationSection("blacknick.tasks." + key);
        if (section == null) return null;
        String type = section.getString("type", "break").toLowerCase(Locale.ROOT);
        if (!Set.of("break", "fish", "kill").contains(type)) {
            plugin.getLogger().warning("Неизвестный тип задания чёрного ника '" + type + "' у пункта " + key);
            return null;
        }
        long amount = Math.max(1L, section.getLong("amount", 1L));
        String displayName = section.getString("display-name", key);
        Set<Material> materials = new HashSet<>();
        for (String raw : readList(section, "material", "materials")) {
            Material material = Material.matchMaterial(raw.toUpperCase(Locale.ROOT));
            if (material == null) plugin.getLogger().warning("Неизвестный блок '" + raw + "' в задании " + key);
            else materials.add(material);
        }
        Set<EntityType> entities = new HashSet<>();
        for (String raw : readList(section, "entity", "entities")) {
            try { entities.add(EntityType.valueOf(raw.toUpperCase(Locale.ROOT))); }
            catch (IllegalArgumentException ignored) { plugin.getLogger().warning("Неизвестный моб '" + raw + "' в задании " + key); }
        }
        if (type.equals("break") && materials.isEmpty()) {
            plugin.getLogger().warning("Задание " + key + " типа break не содержит ни одного корректного блока.");
            return null;
        }
        if (type.equals("kill") && entities.isEmpty()) {
            plugin.getLogger().warning("Задание " + key + " типа kill не содержит ни одного корректного моба.");
            return null;
        }
        return new Task(key, displayName, type, amount, materials, entities);
    }

    private List<String> readList(ConfigurationSection section, String singular, String plural) {
        List<String> values = new ArrayList<>(section.getStringList(plural));
        if (values.isEmpty()) values.addAll(section.getStringList(singular));
        if (values.isEmpty()) {
            String single = section.getString(singular);
            if (single != null && !single.isBlank()) values.add(single);
        }
        return values;
    }

    public String taskDisplayName(String taskKey) {
        Task task = task(taskKey);
        return task == null ? String.valueOf(taskKey) : task.displayName();
    }

    /** Выдаёт чёрный ник. Работает и для офлайн-игрока: эффекты применятся при входе. */
    public boolean issue(String playerName, String moderatorName, String taskKey, String reason, boolean silent) {
        if (!enabled()) return false;
        if (Utils.hasExactPermission(playerName, "kachbans.punishment.protected")) {
            notifyModerator(moderatorName, "<red>Игрок <white>" + playerName + "</white> защищён от наказаний.</red>");
            return false;
        }
        Player online = Bukkit.getPlayerExact(playerName);
        if (online != null && Utils.hasExactPermission(online, EXEMPT_PERMISSION)) {
            notifyModerator(moderatorName, "<red>Игрок <white>" + playerName + "</white> исключён из чёрных ников.</red>");
            return false;
        }
        if (isBlackNicked(playerName)) {
            notifyModerator(moderatorName, "<gray>У <white>" + playerName + "</white> уже висит чёрный ник.</gray>");
            return false;
        }
        Task task = task(taskKey);
        if (task == null) return false;

        long now = System.currentTimeMillis();
        double previousMaxHealth = online == null ? DEFAULT_MAX_HEALTH : currentMaxHealth(online);
        Models.BlackNick record = new Models.BlackNick(online == null ? playerName : online.getName(),
                moderatorName, normalizeReason(reason), task.key(), task.amount(), 0L, previousMaxHealth, now);
        String details = task.displayName() + " • цель: " + task.amount()
                + (record.reason().equals("Не указана") ? "" : " • " + record.reason());
        boolean stored = db.transaction(() -> {
            db.upsertPlayer(record.playerName(), null, now);
            db.addBlackNick(record);
            db.addHistory(record.playerName(), "BLACK_NICK", moderatorName, details, now, 0, null);
        });
        if (!stored) {
            notifyModerator(moderatorName, "<red>Ошибка базы:</red> <gray>чёрный ник для <white>"
                    + playerName + "</white> не выдан.</gray>");
            plugin.getLogger().severe("Чёрный ник не выдан из-за ошибки базы: " + playerName);
            return false;
        }
        active.put(key(record.playerName()), record);
        if (online != null) apply(online);

        if (!silent && plugin.getConfig().getBoolean("blacknick.broadcast", true)) {
            Bukkit.broadcast(plugin.getPluginConfig().getMessage("blacknick.moderator-broadcast",
                    moderatorName, record.playerName(), task.displayName(), task.amount()));
        }
        if (online != null) {
            Utils.sendMessage(online, plugin.getPluginConfig().getMessage("blacknick.target-applied",
                    task.displayName(), task.amount(), record.reason(), (long) hearts()));
        }
        if (plugin.getPunishmentManager().shouldPublishPunishment(moderatorName, silent)) {
            plugin.getBotManager().sendPunishment("blacknick", record.playerName(), moderatorName,
                    record.reason(), now, 0, null,
                    Map.of("Задание", task.displayName(), "Цель", String.valueOf(task.amount())));
        }
        return true;
    }

    /** Снимает чёрный ник. completed=true — задание выполнено самим игроком. */
    public boolean remove(String playerName, String moderatorName, String reason, boolean silent, boolean completed) {
        Models.BlackNick record = get(playerName);
        if (record == null) return false;

        long now = System.currentTimeMillis();
        String normalizedReason = completed ? "Задание выполнено" : normalizeReason(reason);
        String details = taskDisplayName(record.taskKey()) + " • " + normalizedReason;
        boolean stored = db.transaction(() -> {
            db.deactivateBlackNick(record.playerName());
            db.addHistory(record.playerName(), "UNBLACK_NICK", moderatorName, details, now, 0, null);
        });
        if (!stored) {
            notifyModerator(moderatorName, "<red>Ошибка базы:</red> <gray>чёрный ник с <white>"
                    + playerName + "</white> не снят.</gray>");
            plugin.getLogger().severe("Чёрный ник не снят из-за ошибки базы: " + playerName);
            return false;
        }
        active.remove(key(record.playerName()));
        unsavedSteps.remove(key(record.playerName()));

        Player online = Bukkit.getPlayerExact(record.playerName());
        if (online != null) restore(online, record);

        if (!silent && plugin.getConfig().getBoolean("blacknick.broadcast", true)) {
            Bukkit.broadcast(plugin.getPluginConfig().getMessage(
                    completed ? "blacknick.completed-broadcast" : "unblacknick.moderator-broadcast",
                    moderatorName, record.playerName(), taskDisplayName(record.taskKey()), normalizedReason));
        }
        if (online != null) {
            Utils.sendMessage(online, plugin.getPluginConfig().getMessage(
                    completed ? "blacknick.completed" : "unblacknick.target-removed",
                    taskDisplayName(record.taskKey())));
        }
        if (plugin.getPunishmentManager().shouldPublishPunishment(moderatorName, silent)) {
            plugin.getBotManager().sendPunishment("unblacknick", record.playerName(), moderatorName,
                    normalizedReason, now, 0, null,
                    Map.of("Задание", taskDisplayName(record.taskKey()),
                            "Прогресс", record.progress() + "/" + record.target()));
        }
        return true;
    }

    /** Начисляет прогресс задания. Запись в базу батчами, чтобы не грузить её на каждый блок. */
    public void addProgress(Player player, String expectedType, long amount) {
        if (amount <= 0) return;
        Models.BlackNick record = get(player.getName());
        if (record == null) return;
        Task task = task(record.taskKey());
        if (task == null || !task.type().equals(expectedType)) return;

        long updatedProgress = Math.min(record.target(), record.progress() + amount);
        if (updatedProgress == record.progress()) return;
        Models.BlackNick updated = record.withProgress(updatedProgress);
        active.put(key(record.playerName()), updated);

        if (updated.complete()) {
            unsavedSteps.remove(key(record.playerName()));
            db.updateBlackNickProgress(record.playerName(), updatedProgress);
            remove(record.playerName(), "Сервер", "Задание выполнено", false, true);
            return;
        }
        int saveEvery = Math.max(1, plugin.getConfig().getInt("blacknick.progress-save-every", 25));
        int steps = unsavedSteps.merge(key(record.playerName()), 1, Integer::sum);
        if (steps >= saveEvery) {
            unsavedSteps.remove(key(record.playerName()));
            db.updateBlackNickProgress(record.playerName(), updatedProgress);
        }
    }

    /** Сбрасывает накопленный в памяти прогресс в базу. */
    public void flushProgress(String playerName) {
        Models.BlackNick record = get(playerName);
        if (record == null) return;
        if (unsavedSteps.remove(key(record.playerName())) != null) {
            db.updateBlackNickProgress(record.playerName(), record.progress());
        }
    }

    public void flushAllProgress() {
        for (Models.BlackNick record : all()) flushProgress(record.playerName());
    }

    /** Навешивает визуальные эффекты и урезает здоровье. */
    public void apply(Player player) {
        Models.BlackNick record = get(player.getName());
        if (record == null) return;
        applyMaxHealth(player, hearts() * 2.0D);
    }

    /** Возвращает игрока в обычное состояние. */
    public void restore(Player player, Models.BlackNick record) {
        double previous = record == null || record.previousMaxHealth() <= 0 ? DEFAULT_MAX_HEALTH : record.previousMaxHealth();
        applyMaxHealth(player, previous);
    }

    private void applyMaxHealth(Player player, double value) {
        AttributeInstance attribute = maxHealthAttribute(player);
        if (attribute == null) {
            plugin.getLogger().warning("Атрибут максимального здоровья недоступен, здоровье " + player.getName() + " не изменено.");
            return;
        }
        attribute.setBaseValue(value);
        if (player.getHealth() > value) player.setHealth(value);
    }

    private double currentMaxHealth(Player player) {
        AttributeInstance attribute = maxHealthAttribute(player);
        return attribute == null ? DEFAULT_MAX_HEALTH : attribute.getBaseValue();
    }

    /**
     * Достаёт атрибут через реестр, а не через константу перечисления: в 1.21.3+ enum-константу
     * переименовали из GENERIC_MAX_HEALTH в MAX_HEALTH, и жёсткая ссылка ломается в рантайме.
     */
    private AttributeInstance maxHealthAttribute(Player player) {
        Attribute attribute = Registry.ATTRIBUTE.get(NamespacedKey.minecraft("max_health"));
        if (attribute == null) attribute = Registry.ATTRIBUTE.get(NamespacedKey.minecraft("generic.max_health"));
        return attribute == null ? null : player.getAttribute(attribute);
    }

    private void notifyModerator(String moderatorName, String message) {
        Player moderator = moderatorName == null ? null : Bukkit.getPlayerExact(moderatorName);
        if (moderator != null) {
            Utils.sendMessage(moderator, "<gradient:#8B5CF6:#22D3EE>KachBans</gradient> <dark_gray>›</dark_gray> " + message);
        }
    }

    private String normalizeReason(String reason) {
        return reason == null || reason.isBlank() ? "Не указана" : reason;
    }

    private String key(String playerName) {
        return playerName.toLowerCase(Locale.ROOT);
    }
}