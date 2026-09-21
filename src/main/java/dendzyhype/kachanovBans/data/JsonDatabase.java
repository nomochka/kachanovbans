package dendzyhype.kachanovBans.data;

import dendzyhype.kachanovBans.KachanovBans;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.*;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class JsonDatabase implements IDatabase {

    private final KachanovBans plugin;
    private final Gson gson;
    private final Path dataFolder;
    private final boolean debug;

    private final Map<String, PlayerData> players = new ConcurrentHashMap<>();
    private final List<BanData> bans = new ArrayList<>();
    private final List<MuteData> mutes = new ArrayList<>();
    private final List<IpBanData> ipBans = new ArrayList<>();
    private final List<HistoryData> history = new ArrayList<>();
    private final List<IpHistoryData> ipHistory = new ArrayList<>();
    private final List<BlackNickData> blackNicks = new ArrayList<>();

    private final ReentrantReadWriteLock bansLock = new ReentrantReadWriteLock();
    private final ReentrantReadWriteLock mutesLock = new ReentrantReadWriteLock();
    private final ReentrantReadWriteLock ipBansLock = new ReentrantReadWriteLock();
    private final ReentrantReadWriteLock historyLock = new ReentrantReadWriteLock();
    private final ReentrantReadWriteLock ipHistoryLock = new ReentrantReadWriteLock();
    private final ReentrantReadWriteLock blackNicksLock = new ReentrantReadWriteLock();

    public JsonDatabase(KachanovBans plugin) {
        this.plugin = plugin;
        this.debug = plugin.getPluginConfig().isDebug();
        this.gson = new GsonBuilder().setPrettyPrinting().create();
        this.dataFolder = Paths.get(plugin.getDataFolder().getPath(), "data");
        if (debug) plugin.getLogger().info("[Debug] JsonDatabase инициализирован (по нику), папка: " + dataFolder);
    }

    @Override
    public synchronized boolean transaction(Runnable action) {
        String snapshot = snapshotState();
        try {
            action.run();
            return true;
        } catch (RuntimeException exception) {
            restoreState(snapshot);
            try {
                saveAll();
            } catch (RuntimeException restoreError) {
                plugin.getLogger().severe("Не удалось восстановить JSON после отменённой транзакции: "
                        + restoreError.getMessage());
            }
            plugin.getLogger().severe("JSON-транзакция отменена: " + exception.getMessage());
            return false;
        }
    }

    @Override
    public void init() {
        try {
            Files.createDirectories(dataFolder);
            if (debug) plugin.getLogger().info("[Debug] Папка data создана/существует.");
        } catch (IOException e) {
            plugin.getLogger().severe("Не удалось создать папку data: " + e.getMessage());
        }
        loadAll();
        cleanNullRecords();
    }

    private void cleanNullRecords() {
        bansLock.writeLock().lock();
        try {
            boolean removed = bans.removeIf(ban -> ban.playerName == null || ban.playerName.trim().isEmpty());
            if (removed && debug) plugin.getLogger().info("[Debug] Удалены записи банов с пустым playerName.");
        } finally {
            bansLock.writeLock().unlock();
        }
        mutesLock.writeLock().lock();
        try {
            boolean removed = mutes.removeIf(mute -> mute.playerName == null || mute.playerName.trim().isEmpty());
            if (removed && debug) plugin.getLogger().info("[Debug] Удалены записи мутов с пустым playerName.");
        } finally {
            mutesLock.writeLock().unlock();
        }
        historyLock.writeLock().lock();
        try {
            boolean removed = history.removeIf(h -> h.playerName == null || h.playerName.trim().isEmpty());
            if (removed && debug) plugin.getLogger().info("[Debug] Удалены записи истории с пустым playerName.");
        } finally {
            historyLock.writeLock().unlock();
        }
        ipHistoryLock.writeLock().lock();
        try {
            boolean removed = ipHistory.removeIf(ip -> ip.playerName == null || ip.playerName.trim().isEmpty());
            if (removed && debug) plugin.getLogger().info("[Debug] Удалены записи IP-истории с пустым playerName.");
        } finally {
            ipHistoryLock.writeLock().unlock();
        }
        blackNicksLock.writeLock().lock();
        try {
            boolean removed = blackNicks.removeIf(record -> record.playerName == null || record.playerName.trim().isEmpty());
            if (removed && debug) plugin.getLogger().info("[Debug] Удалены записи чёрных ников с пустым playerName.");
        } finally {
            blackNicksLock.writeLock().unlock();
        }
        saveAll();
    }

    @Override
    public void close() {
        saveAll();
        if (debug) plugin.getLogger().info("[Debug] Все данные сохранены, соединение закрыто.");
    }

    private void saveAll() {
        savePlayers();
        saveBans();
        saveMutes();
        saveIpBans();
        saveHistory();
        saveIpHistory();
        saveBlackNicks();
        if (debug) plugin.getLogger().info("[Debug] Все данные сохранены на диск.");
    }

    private <T> T loadJson(String fileName, Type typeOfT, T defaultValue) {
        Path file = dataFolder.resolve(fileName);
        if (!Files.exists(file)) {
            if (debug) plugin.getLogger().info("[Debug] Файл " + fileName + " не найден, создаётся новый.");
            return defaultValue;
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            T result = gson.fromJson(reader, typeOfT);
            if (debug) plugin.getLogger().info("[Debug] Загружен " + fileName + " (" + (result instanceof List ? ((List<?>) result).size() : "?") + " записей)");
            return result;
        } catch (IOException e) {
            plugin.getLogger().warning("Ошибка загрузки " + fileName + ": " + e.getMessage());
            return defaultValue;
        }
    }

    private void saveJson(String fileName, Object data) {
        Path file = dataFolder.resolve(fileName);
        Path temporary = null;
        try {
            temporary = Files.createTempFile(dataFolder, fileName + ".", ".tmp");
            try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                gson.toJson(data, writer);
                writer.flush();
            }
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
            if (debug) plugin.getLogger().info("[Debug] Сохранён " + fileName + " (" + (data instanceof List ? ((List<?>) data).size() : "?") + " записей)");
        } catch (IOException e) {
            throw new JsonStorageException("Ошибка сохранения " + fileName, e);
        } finally {
            if (temporary != null) {
                try { Files.deleteIfExists(temporary); }
                catch (IOException ignored) {}
            }
        }
    }

    private String snapshotState() {
        StateSnapshot snapshot = new StateSnapshot();
        snapshot.players = new HashMap<>(players);
        snapshot.bans = new ArrayList<>(bans);
        snapshot.mutes = new ArrayList<>(mutes);
        snapshot.ipBans = new ArrayList<>(ipBans);
        snapshot.history = new ArrayList<>(history);
        snapshot.ipHistory = new ArrayList<>(ipHistory);
        snapshot.blackNicks = new ArrayList<>(blackNicks);
        return gson.toJson(snapshot);
    }

    private void restoreState(String serialized) {
        StateSnapshot snapshot = gson.fromJson(serialized, StateSnapshot.class);
        players.clear();
        players.putAll(snapshot.players == null ? Map.of() : snapshot.players);
        replace(bans, snapshot.bans);
        replace(mutes, snapshot.mutes);
        replace(ipBans, snapshot.ipBans);
        replace(history, snapshot.history);
        replace(ipHistory, snapshot.ipHistory);
        replace(blackNicks, snapshot.blackNicks);
    }

    private <T> void replace(List<T> target, List<T> source) {
        target.clear();
        if (source != null) target.addAll(source);
    }

    private static final class JsonStorageException extends RuntimeException {
        JsonStorageException(String message, IOException cause) { super(message, cause); }
    }

    private void loadAll() {
        loadPlayers();
        loadBans();
        loadMutes();
        loadIpBans();
        loadHistory();
        loadIpHistory();
        loadBlackNicks();
        if (debug) plugin.getLogger().info("[Debug] Все данные загружены.");
    }

    private void loadPlayers() {
        Type type = new TypeToken<Map<String, PlayerData>>(){}.getType();
        Map<String, PlayerData> loaded = loadJson("players.json", type, new HashMap<>());
        players.clear();
        players.putAll(loaded);
        if (debug) plugin.getLogger().info("[Debug] Загружено игроков: " + players.size());
    }
    private void savePlayers() { saveJson("players.json", players); }

    private void loadBans() {
        Type type = new TypeToken<List<BanData>>(){}.getType();
        List<BanData> loaded = loadJson("bans.json", type, new ArrayList<>());
        bansLock.writeLock().lock();
        try { bans.clear(); bans.addAll(loaded); } finally { bansLock.writeLock().unlock(); }
        if (debug) plugin.getLogger().info("[Debug] Загружено банов: " + bans.size());
    }
    private void saveBans() {
        bansLock.readLock().lock();
        try { saveJson("bans.json", bans); } finally { bansLock.readLock().unlock(); }
    }

    private void loadMutes() {
        Type type = new TypeToken<List<MuteData>>(){}.getType();
        List<MuteData> loaded = loadJson("mutes.json", type, new ArrayList<>());
        mutesLock.writeLock().lock();
        try { mutes.clear(); mutes.addAll(loaded); } finally { mutesLock.writeLock().unlock(); }
        if (debug) plugin.getLogger().info("[Debug] Загружено мутов: " + mutes.size());
    }
    private void saveMutes() {
        mutesLock.readLock().lock();
        try { saveJson("mutes.json", mutes); } finally { mutesLock.readLock().unlock(); }
    }

    private void loadIpBans() {
        Type type = new TypeToken<List<IpBanData>>(){}.getType();
        List<IpBanData> loaded = loadJson("ip_bans.json", type, new ArrayList<>());
        ipBansLock.writeLock().lock();
        try { ipBans.clear(); ipBans.addAll(loaded); } finally { ipBansLock.writeLock().unlock(); }
        if (debug) plugin.getLogger().info("[Debug] Загружено IP-банов: " + ipBans.size());
    }
    private void saveIpBans() {
        ipBansLock.readLock().lock();
        try { saveJson("ip_bans.json", ipBans); } finally { ipBansLock.readLock().unlock(); }
    }

    private void loadHistory() {
        Type type = new TypeToken<List<HistoryData>>(){}.getType();
        List<HistoryData> loaded = loadJson("history.json", type, new ArrayList<>());
        historyLock.writeLock().lock();
        try { history.clear(); history.addAll(loaded); } finally { historyLock.writeLock().unlock(); }
        if (debug) plugin.getLogger().info("[Debug] Загружено записей истории: " + history.size());
    }
    private void saveHistory() {
        historyLock.readLock().lock();
        try { saveJson("history.json", history); } finally { historyLock.readLock().unlock(); }
    }

    private void loadIpHistory() {
        Type type = new TypeToken<List<IpHistoryData>>(){}.getType();
        List<IpHistoryData> loaded = loadJson("ip_history.json", type, new ArrayList<>());
        ipHistoryLock.writeLock().lock();
        try { ipHistory.clear(); ipHistory.addAll(loaded); } finally { ipHistoryLock.writeLock().unlock(); }
        if (debug) plugin.getLogger().info("[Debug] Загружено IP-историй: " + ipHistory.size());
    }
    private void saveIpHistory() {
        ipHistoryLock.readLock().lock();
        try { saveJson("ip_history.json", ipHistory); } finally { ipHistoryLock.readLock().unlock(); }
    }

    private void loadBlackNicks() {
        Type type = new TypeToken<List<BlackNickData>>(){}.getType();
        List<BlackNickData> loaded = loadJson("black_nicks.json", type, new ArrayList<>());
        blackNicksLock.writeLock().lock();
        try { blackNicks.clear(); blackNicks.addAll(loaded); } finally { blackNicksLock.writeLock().unlock(); }
        if (debug) plugin.getLogger().info("[Debug] Загружено чёрных ников: " + blackNicks.size());
    }
    private void saveBlackNicks() {
        blackNicksLock.readLock().lock();
        try { saveJson("black_nicks.json", blackNicks); } finally { blackNicksLock.readLock().unlock(); }
    }

    @Override
    public void upsertPlayer(String playerName, String ip, long lastLogin) {
        players.compute(playerName, (k, existing) -> {
            if (existing == null) {
                if (debug) plugin.getLogger().info("[Debug] Новый игрок: " + playerName + " IP: " + ip);
                return new PlayerData(playerName, ip, lastLogin);
            } else {
                if (debug) plugin.getLogger().info("[Debug] Обновлён игрок: " + playerName + " IP: " + ip);
                if (ip != null) existing.ip = ip;
                existing.lastLogin = lastLogin;
                return existing;
            }
        });
        savePlayers();
    }

    @Override
    public boolean isPlayerExists(String playerName) {
        return players.containsKey(playerName);
    }

    @Override
    public void addBan(String playerName, String moderatorName, String reason, long issuedAt, long expiry) {
        bansLock.writeLock().lock();
        try {
            bans.add(new BanData(playerName, moderatorName, reason, issuedAt, expiry, true));
            saveBans();
            if (debug) plugin.getLogger().info("[Debug] Добавлен бан для " + playerName + " до " + expiry);
        } finally { bansLock.writeLock().unlock(); }
    }

    @Override
    public void deactivateBan(String playerName) {
        bansLock.writeLock().lock();
        try {
            boolean found = false;
            for (BanData ban : bans) {
                if (ban.playerName != null && ban.playerName.equalsIgnoreCase(playerName) && ban.active) {
                    ban.active = false;
                    found = true;
                    if (debug) plugin.getLogger().info("[Debug] Деактивирован бан для " + playerName);
                    break;
                }
            }
            saveBans();
            if (!found && debug) plugin.getLogger().info("[Debug] Не найден активный бан для деактивации: " + playerName);
        } finally { bansLock.writeLock().unlock(); }
    }

    @Override
    public Models.Ban getActiveBan(String playerName) {
        bansLock.readLock().lock();
        try {
            for (BanData ban : bans) {
                if (ban.playerName != null && ban.playerName.equalsIgnoreCase(playerName) && ban.active) {
                    if (debug) plugin.getLogger().info("[Debug] Активный бан найден для " + playerName);
                    return new Models.Ban(ban.playerName, ban.moderatorName, ban.reason, ban.issuedAt, ban.expiry);
                }
            }
            if (debug) plugin.getLogger().info("[Debug] Активных банов для " + playerName + " нет.");
        } finally { bansLock.readLock().unlock(); }
        return null;
    }

    @Override
    public List<Models.Ban> getActiveBans() {
        List<Models.Ban> result = new ArrayList<>();
        bansLock.readLock().lock();
        try {
            for (BanData ban : bans) {
                if (ban.active && ban.playerName != null) {
                    result.add(new Models.Ban(ban.playerName, ban.moderatorName, ban.reason, ban.issuedAt, ban.expiry));
                }
            }
            result.sort((a,b)->Long.compare(b.issuedAt(), a.issuedAt()));
            if (debug) plugin.getLogger().info("[Debug] Всего активных банов: " + result.size());
        } finally { bansLock.readLock().unlock(); }
        return result;
    }

    @Override
    public void addMute(String playerName, String moderatorName, String reason, long issuedAt, long expiry) {
        mutesLock.writeLock().lock();
        try {
            mutes.add(new MuteData(playerName, moderatorName, reason, issuedAt, expiry, true));
            saveMutes();
            if (debug) plugin.getLogger().info("[Debug] Добавлен мут для " + playerName + " осталось " + expiry);
        } finally { mutesLock.writeLock().unlock(); }
    }

    @Override
    public void deactivateMute(String playerName) {
        if (debug) plugin.getLogger().info("[DebugMute] deactivateMute: " + playerName);
        mutesLock.writeLock().lock();
        try {
            boolean found = false;
            for (MuteData mute : mutes) {
                if (mute.playerName != null && mute.playerName.equalsIgnoreCase(playerName) && mute.active) {
                    mute.active = false;
                    found = true;
                    if (debug) plugin.getLogger().info("[DebugMute] Деактивирован мут для " + playerName);
                    break;
                }
            }
            saveMutes();
            if (!found && debug) plugin.getLogger().info("[DebugMute] Не найден активный мут для деактивации: " + playerName);
        } finally { mutesLock.writeLock().unlock(); }
    }

    @Override
    public Models.Mute getActiveMute(String playerName) {
        mutesLock.readLock().lock();
        try {
            for (MuteData mute : mutes) {
                if (mute.playerName != null && mute.playerName.equalsIgnoreCase(playerName) && mute.active) {
                    if (debug) plugin.getLogger().info("[DebugMute] getActiveMute: найден мут для " + playerName + ", expiry=" + mute.expiry);
                    return new Models.Mute(mute.playerName, mute.moderatorName, mute.reason, mute.issuedAt, mute.expiry);
                }
            }
            if (debug) plugin.getLogger().info("[DebugMute] getActiveMute: мут не найден для " + playerName);
        } finally { mutesLock.readLock().unlock(); }
        return null;
    }

    @Override
    public List<Models.Mute> getActiveMutes() {
        List<Models.Mute> result = new ArrayList<>();
        mutesLock.readLock().lock();
        try {
            for (MuteData mute : mutes) {
                if (mute.active && mute.playerName != null) {
                    result.add(new Models.Mute(mute.playerName, mute.moderatorName, mute.reason, mute.issuedAt, mute.expiry));
                }
            }
            result.sort((a,b)->Long.compare(b.issuedAt(), a.issuedAt()));
            if (debug) plugin.getLogger().info("[Debug] Всего активных мутов: " + result.size());
        } finally { mutesLock.readLock().unlock(); }
        return result;
    }

    @Override
    public void updateMuteExpiry(String playerName, long newExpiry) {
        if (debug) plugin.getLogger().info("[DebugMute] updateMuteExpiry: " + playerName + " -> " + newExpiry);
        mutesLock.writeLock().lock();
        try {
            for (MuteData mute : mutes) {
                if (mute.playerName != null && mute.playerName.equalsIgnoreCase(playerName) && mute.active) {
                    mute.expiry = newExpiry;
                    saveMutes();
                    if (debug) plugin.getLogger().info("[DebugMute] Обновлён expiry мута для " + playerName + " -> " + newExpiry);
                    return;
                }
            }
            if (debug) plugin.getLogger().info("[DebugMute] Не найден активный мут для обновления expiry: " + playerName);
        } finally {
            mutesLock.writeLock().unlock();
        }
    }

    @Override
    public void addIpBan(String ip, String reason, long issuedAt, long expiry) {
        ipBansLock.writeLock().lock();
        try {
            ipBans.removeIf(b -> b.ip.equals(ip));
            ipBans.add(new IpBanData(ip, reason, issuedAt, expiry, true));
            saveIpBans();
            if (debug) plugin.getLogger().info("[Debug] Добавлен IP-бан для " + ip + " до " + expiry);
        } finally { ipBansLock.writeLock().unlock(); }
    }

    @Override
    public void deactivateIpBan(String ip) {
        ipBansLock.writeLock().lock();
        try {
            boolean found = false;
            for (IpBanData ban : ipBans) {
                if (ban.ip.equals(ip) && ban.active) {
                    ban.active = false;
                    found = true;
                    if (debug) plugin.getLogger().info("[Debug] Деактивирован IP-бан для " + ip);
                    break;
                }
            }
            saveIpBans();
            if (!found && debug) plugin.getLogger().info("[Debug] Не найден активный IP-бан для деактивации: " + ip);
        } finally { ipBansLock.writeLock().unlock(); }
    }

    @Override
    public Models.IpBan getActiveIpBan(String ip) {
        ipBansLock.readLock().lock();
        try {
            for (IpBanData ban : ipBans) {
                if (ban.ip.equals(ip) && ban.active) {
                    if (debug) plugin.getLogger().info("[Debug] Активный IP-бан найден для " + ip);
                    return new Models.IpBan(ban.ip, ban.reason, ban.issuedAt, ban.expiry);
                }
            }
            if (debug) plugin.getLogger().info("[Debug] Активных IP-банов для " + ip + " нет.");
        } finally { ipBansLock.readLock().unlock(); }
        return null;
    }

    @Override
    public void addHistory(String playerName, String type, String moderatorName, String reason, long issuedAt, long duration, String ip) {
        historyLock.writeLock().lock();
        try {
            history.add(new HistoryData(playerName, type, moderatorName, reason, issuedAt, duration, ip));
            saveHistory();
            if (debug) plugin.getLogger().info("[Debug] Добавлена запись истории для " + playerName + " тип: " + type);
        } finally { historyLock.writeLock().unlock(); }
    }

    @Override
    public List<Models.HistoryEntry> getHistory(String playerName) {
        List<Models.HistoryEntry> result = new ArrayList<>();
        historyLock.readLock().lock();
        try {
            for (HistoryData h : history) {
                if (h.playerName != null && h.playerName.equalsIgnoreCase(playerName)) {
                    result.add(new Models.HistoryEntry(h.playerName, h.type, h.moderatorName, h.reason, h.issuedAt, h.duration, h.ip));
                }
            }
            result.sort((a,b)->Long.compare(b.issuedAt(), a.issuedAt()));
            if (debug) plugin.getLogger().info("[Debug] Найдено записей истории для " + playerName + ": " + result.size());
        } finally { historyLock.readLock().unlock(); }
        return result;
    }

    @Override
    public List<Models.HistoryEntry> getHistoryByModerator(String moderatorName) {
        List<Models.HistoryEntry> result = new ArrayList<>();
        historyLock.readLock().lock();
        try {
            for (HistoryData h : history) {
                if (h.moderatorName != null && h.moderatorName.equalsIgnoreCase(moderatorName)) {
                    result.add(new Models.HistoryEntry(h.playerName, h.type, h.moderatorName, h.reason, h.issuedAt, h.duration, h.ip));
                }
            }
            result.sort((a, b) -> Long.compare(b.issuedAt(), a.issuedAt()));
        } finally {
            historyLock.readLock().unlock();
        }
        return result;
    }

    @Override
    public void addIpHistory(String playerName, String ip, long lastSeen) {
        ipHistoryLock.writeLock().lock();
        try {
            boolean found = false;
            for (IpHistoryData data : ipHistory) {
                if (data.playerName != null && data.playerName.equalsIgnoreCase(playerName) && data.ip.equals(ip)) {
                    data.lastSeen = lastSeen;
                    found = true;
                    break;
                }
            }
            if (!found) {
                ipHistory.add(new IpHistoryData(playerName, ip, lastSeen, lastSeen));
                if (debug) plugin.getLogger().info("[Debug] Новая IP-запись для " + playerName + ": " + ip);
            } else {
                if (debug) plugin.getLogger().info("[Debug] Обновлена IP-запись для " + playerName + ": " + ip);
            }
            saveIpHistory();
        } finally { ipHistoryLock.writeLock().unlock(); }
    }

    @Override
    public List<Models.IpHistoryEntry> getIpHistory(String playerName) {
        List<Models.IpHistoryEntry> result = new ArrayList<>();
        ipHistoryLock.readLock().lock();
        try {
            for (IpHistoryData data : ipHistory) {
                if (data.playerName != null && data.playerName.equalsIgnoreCase(playerName)) {
                    result.add(new Models.IpHistoryEntry(data.playerName, data.ip, data.firstSeen, data.lastSeen));
                }
            }
            result.sort((a,b)->Long.compare(b.lastSeen(), a.lastSeen()));
            if (debug) plugin.getLogger().info("[Debug] IP-история для " + playerName + ": " + result.size() + " записей");
        } finally { ipHistoryLock.readLock().unlock(); }
        return result;
    }

    @Override
    public List<Models.IpHistoryEntry> getPlayersByIp(String ip) {
        List<Models.IpHistoryEntry> result = new ArrayList<>();
        ipHistoryLock.readLock().lock();
        try {
            for (IpHistoryData data : ipHistory) {
                if (data.ip.equals(ip)) {
                    result.add(new Models.IpHistoryEntry(data.playerName, data.ip, data.firstSeen, data.lastSeen));
                }
            }
            if (debug) plugin.getLogger().info("[Debug] Найдено игроков с IP " + ip + ": " + result.size());
        } finally { ipHistoryLock.readLock().unlock(); }
        return result;
    }

    @Override
    public void addBlackNick(Models.BlackNick record) {
        blackNicksLock.writeLock().lock();
        try {
            for (BlackNickData existing : blackNicks) {
                if (existing.playerName != null && existing.playerName.equalsIgnoreCase(record.playerName()) && existing.active) {
                    existing.active = false;
                }
            }
            blackNicks.add(new BlackNickData(record.playerName(), record.moderatorName(), record.reason(),
                    record.taskKey(), record.target(), record.progress(), record.previousMaxHealth(),
                    record.issuedAt(), true));
            saveBlackNicks();
            if (debug) plugin.getLogger().info("[Debug] Добавлен чёрный ник для " + record.playerName());
        } finally { blackNicksLock.writeLock().unlock(); }
    }

    @Override
    public void deactivateBlackNick(String playerName) {
        blackNicksLock.writeLock().lock();
        try {
            boolean found = false;
            for (BlackNickData record : blackNicks) {
                if (record.playerName != null && record.playerName.equalsIgnoreCase(playerName) && record.active) {
                    record.active = false;
                    found = true;
                    break;
                }
            }
            saveBlackNicks();
            if (!found && debug) plugin.getLogger().info("[Debug] Не найден активный чёрный ник для снятия: " + playerName);
        } finally { blackNicksLock.writeLock().unlock(); }
    }

    @Override
    public void updateBlackNickProgress(String playerName, long progress) {
        blackNicksLock.writeLock().lock();
        try {
            for (BlackNickData record : blackNicks) {
                if (record.playerName != null && record.playerName.equalsIgnoreCase(playerName) && record.active) {
                    record.progress = progress;
                    saveBlackNicks();
                    return;
                }
            }
        } finally { blackNicksLock.writeLock().unlock(); }
    }

    @Override
    public Models.BlackNick getActiveBlackNick(String playerName) {
        blackNicksLock.readLock().lock();
        try {
            for (BlackNickData record : blackNicks) {
                if (record.playerName != null && record.playerName.equalsIgnoreCase(playerName) && record.active) {
                    return toModel(record);
                }
            }
        } finally { blackNicksLock.readLock().unlock(); }
        return null;
    }

    @Override
    public List<Models.BlackNick> getActiveBlackNicks() {
        List<Models.BlackNick> result = new ArrayList<>();
        blackNicksLock.readLock().lock();
        try {
            for (BlackNickData record : blackNicks) {
                if (record.active && record.playerName != null) result.add(toModel(record));
            }
            result.sort((a, b) -> Long.compare(b.issuedAt(), a.issuedAt()));
        } finally { blackNicksLock.readLock().unlock(); }
        return result;
    }

    private Models.BlackNick toModel(BlackNickData record) {
        return new Models.BlackNick(record.playerName, record.moderatorName, record.reason, record.taskKey,
                record.target, record.progress, record.previousMaxHealth, record.issuedAt);
    }

    private static class PlayerData {
        String name, ip;
        long lastLogin;
        PlayerData(String name, String ip, long lastLogin) {
            this.name = name;
            this.ip = ip;
            this.lastLogin = lastLogin;
        }
    }

    private static class BanData {
        String playerName, moderatorName, reason;
        long issuedAt, expiry;
        boolean active;
        BanData(String playerName, String moderatorName, String reason, long issuedAt, long expiry, boolean active) {
            this.playerName = playerName;
            this.moderatorName = moderatorName;
            this.reason = reason;
            this.issuedAt = issuedAt;
            this.expiry = expiry;
            this.active = active;
        }
    }

    private static class MuteData {
        String playerName, moderatorName, reason;
        long issuedAt, expiry;
        boolean active;
        MuteData(String playerName, String moderatorName, String reason, long issuedAt, long expiry, boolean active) {
            this.playerName = playerName;
            this.moderatorName = moderatorName;
            this.reason = reason;
            this.issuedAt = issuedAt;
            this.expiry = expiry;
            this.active = active;
        }
    }

    private static class IpBanData {
        String ip, reason;
        long issuedAt, expiry;
        boolean active;
        IpBanData(String ip, String reason, long issuedAt, long expiry, boolean active) {
            this.ip = ip;
            this.reason = reason;
            this.issuedAt = issuedAt;
            this.expiry = expiry;
            this.active = active;
        }
    }

    private static class HistoryData {
        String playerName, type, moderatorName, reason, ip;
        long issuedAt, duration;
        HistoryData(String playerName, String type, String moderatorName, String reason, long issuedAt, long duration, String ip) {
            this.playerName = playerName;
            this.type = type;
            this.moderatorName = moderatorName;
            this.reason = reason;
            this.issuedAt = issuedAt;
            this.duration = duration;
            this.ip = ip;
        }
    }

    private static class IpHistoryData {
        String playerName, ip;
        long firstSeen, lastSeen;
        IpHistoryData(String playerName, String ip, long firstSeen, long lastSeen) {
            this.playerName = playerName;
            this.ip = ip;
            this.firstSeen = firstSeen;
            this.lastSeen = lastSeen;
        }
    }

    private static class BlackNickData {
        String playerName, moderatorName, reason, taskKey;
        long target, progress, issuedAt;
        double previousMaxHealth;
        boolean active;
        BlackNickData(String playerName, String moderatorName, String reason, String taskKey,
                      long target, long progress, double previousMaxHealth, long issuedAt, boolean active) {
            this.playerName = playerName;
            this.moderatorName = moderatorName;
            this.reason = reason;
            this.taskKey = taskKey;
            this.target = target;
            this.progress = progress;
            this.previousMaxHealth = previousMaxHealth;
            this.issuedAt = issuedAt;
            this.active = active;
        }
    }

    private static class StateSnapshot {
        Map<String, PlayerData> players;
        List<BanData> bans;
        List<MuteData> mutes;
        List<IpBanData> ipBans;
        List<HistoryData> history;
        List<IpHistoryData> ipHistory;
        List<BlackNickData> blackNicks;
    }
}
