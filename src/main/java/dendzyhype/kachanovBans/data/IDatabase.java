package dendzyhype.kachanovBans.data;

import java.util.List;

public interface IDatabase {
    void init();
    void close();

    default boolean transaction(Runnable action) {
        try {
            action.run();
            return true;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    void upsertPlayer(String playerName, String ip, long lastLogin);
    boolean isPlayerExists(String playerName);

    void addBan(String playerName, String moderatorName, String reason, long issuedAt, long expiry);
    void deactivateBan(String playerName);
    Models.Ban getActiveBan(String playerName);
    List<Models.Ban> getActiveBans();

    void addMute(String playerName, String moderatorName, String reason, long issuedAt, long expiry);
    void deactivateMute(String playerName);
    Models.Mute getActiveMute(String playerName);
    List<Models.Mute> getActiveMutes();
    void updateMuteExpiry(String playerName, long newExpiry);

    void addIpBan(String ip, String reason, long issuedAt, long expiry);
    void deactivateIpBan(String ip);
    Models.IpBan getActiveIpBan(String ip);

    void addHistory(String playerName, String type, String moderatorName, String reason, long issuedAt, long duration, String ip);
    List<Models.HistoryEntry> getHistory(String playerName);
    List<Models.HistoryEntry> getHistoryByModerator(String moderatorName);

    void addIpHistory(String playerName, String ip, long lastSeen);
    List<Models.IpHistoryEntry> getIpHistory(String playerName);
    List<Models.IpHistoryEntry> getPlayersByIp(String ip);

    void addBlackNick(Models.BlackNick record);
    void deactivateBlackNick(String playerName);
    void updateBlackNickProgress(String playerName, long progress);
    Models.BlackNick getActiveBlackNick(String playerName);
    List<Models.BlackNick> getActiveBlackNicks();
}
