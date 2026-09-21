package dendzyhype.kachanovBans.data;

public class Models {
    public record Ban(String playerName, String moderatorName, String reason, long issuedAt, long expiry) {}
    public record Mute(String playerName, String moderatorName, String reason, long issuedAt, long expiry) {}
    public record IpBan(String ip, String reason, long issuedAt, long expiry) {}
    public record HistoryEntry(String playerName, String type, String moderatorName, String reason, long issuedAt, long duration, String ip) {}
    public record IpHistoryEntry(String playerName, String ip, long firstSeen, long lastSeen) {}
    public record BlackNick(String playerName, String moderatorName, String reason, String taskKey,
                            long target, long progress, double previousMaxHealth, long issuedAt) {
        public BlackNick withProgress(long newProgress) {
            return new BlackNick(playerName, moderatorName, reason, taskKey, target, newProgress, previousMaxHealth, issuedAt);
        }
        public long remaining() {
            return Math.max(0, target - progress);
        }
        public boolean complete() {
            return target > 0 && progress >= target;
        }
    }
}
