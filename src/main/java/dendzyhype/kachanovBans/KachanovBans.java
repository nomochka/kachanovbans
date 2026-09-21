package dendzyhype.kachanovBans;

import dendzyhype.kachanovBans.commands.CommandRouter;
import dendzyhype.kachanovBans.commands.TabCompleter;
import dendzyhype.kachanovBans.config.Config;
import dendzyhype.kachanovBans.blacknick.BlackNickListeners;
import dendzyhype.kachanovBans.blacknick.BlackNickManager;
import dendzyhype.kachanovBans.data.IDatabase;
import dendzyhype.kachanovBans.data.JsonDatabase;
import dendzyhype.kachanovBans.data.MySQLDatabase;
import dendzyhype.kachanovBans.data.PendingConfirmation;
import dendzyhype.kachanovBans.listeners.Listeners;
import dendzyhype.kachanovBans.integrations.PlasmoVoiceMuteService;
import dendzyhype.kachanovBans.managers.DiscordManager;
import dendzyhype.kachanovBans.managers.PunishmentManager;
import dendzyhype.kachanovBans.security.AccessManager;
import dendzyhype.kachanovBans.bots.BotManager;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class KachanovBans extends JavaPlugin {

    private static KachanovBans instance;
    private Config config;
    private IDatabase database;
    private PunishmentManager punishmentManager;
    private DiscordManager discordManager;
    private Listeners listeners;
    private AccessManager accessManager;
    private BotManager botManager;
    private PlasmoVoiceMuteService plasmoVoiceMuteService;
    private BlackNickManager blackNickManager;
    private BlackNickListeners blackNickListeners;
    private final Map<String, PendingConfirmation> pendingConfirmations = new ConcurrentHashMap<>();

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();
        saveResource("messages.yml", false);
        saveResource("discord.yml", false);

        config = new Config(this);
        config.loadAll();

        String storageType = config.getStorageType();
        if ("mysql".equalsIgnoreCase(storageType)) {
            database = new MySQLDatabase(this);
        } else {
            database = new JsonDatabase(this);
        }
        database.init();

        accessManager = new AccessManager(this);
        botManager = new BotManager(this);
        plasmoVoiceMuteService = new PlasmoVoiceMuteService(this);
        punishmentManager = new PunishmentManager(this);
        discordManager = new DiscordManager(this);
        listeners = new Listeners(this);
        blackNickManager = new BlackNickManager(this);
        blackNickManager.loadActive();
        blackNickListeners = new BlackNickListeners(this);

        CommandRouter commandHandler = new CommandRouter(this);
        for (String cmd : new String[]{"warn","unwarn","staffhistory","kick","ban","tempban","unban","mute","tempmute","unmute",
                "ipban","tempipban","ipunban","banlist","mutelist","vmutelist",
                "history","iphistory","dupeip","checkmute","checkvmute","checkban",
                "blacknick","unblacknick",
                "punishconfirm","kachanovbans","access","ds","tg","2fa"}) {
            getCommand(cmd).setExecutor(commandHandler);
            getCommand(cmd).setTabCompleter(new TabCompleter(this));
        }

        getServer().getPluginManager().registerEvents(listeners, this);
        getServer().getPluginManager().registerEvents(blackNickListeners, this);
        botManager.start();

        // Игроки, вошедшие до включения плагина (перезагрузка), тоже должны получить эффекты.
        Bukkit.getGlobalRegionScheduler().execute(this, () -> {
            for (org.bukkit.entity.Player online : Bukkit.getOnlinePlayers()) {
                if (!blackNickManager.isBlackNicked(online.getName())) continue;
                online.getScheduler().execute(this, () -> blackNickManager.apply(online), null, 1L);
            }
        });

        Bukkit.getGlobalRegionScheduler().runAtFixedRate(
                this,
                task -> punishmentManager.checkExpirations(),
                20L,
                config.getCheckInterval()
        );

        getLogger().info("KachanovBans включён! Хранилище: " + storageType);
    }

    @Override
    public void onDisable() {
        if (blackNickManager != null) {
            blackNickManager.flushAllProgress();
        }
        if (botManager != null) botManager.stop();
        if (database != null) database.close();
        getLogger().info("KachanovBans выключён.");
    }

    public static KachanovBans getInstance() { return instance; }
    public Config getPluginConfig() { return config; }
    public IDatabase getDatabase() { return database; }
    public PunishmentManager getPunishmentManager() { return punishmentManager; }
    public DiscordManager getDiscordManager() { return discordManager; }
    public Listeners getListeners() { return listeners; }
    public AccessManager getAccessManager() { return accessManager; }
    public BotManager getBotManager() { return botManager; }
    public PlasmoVoiceMuteService getPlasmoVoiceMuteService() { return plasmoVoiceMuteService; }
    public BlackNickManager getBlackNickManager() { return blackNickManager; }
    public BlackNickListeners getBlackNickListeners() { return blackNickListeners; }

    public void addPending(String key, PendingConfirmation pending) {
        pendingConfirmations.put(key, pending);
    }

    public PendingConfirmation getPending(String key) {
        return pendingConfirmations.get(key);
    }

    public void removePending(String key) {
        pendingConfirmations.remove(key);
    }
}
