package dendzyhype.kachanovBans.data;

public class PendingConfirmation {
    private final String targetName;
    private final String moderatorName;
    private final String reason;
    private final long duration;
    private final boolean silent;
    private final boolean skipEvidence;
    private final String type;
    private final String ownerId;
    private final long expiresAt;

    public PendingConfirmation(String targetName, String moderatorName, String reason, long duration, boolean silent, String type) {
        this(targetName, moderatorName, reason, duration, silent, false, type);
    }

    public PendingConfirmation(String targetName, String moderatorName, String reason, long duration, boolean silent, boolean skipEvidence, String type) {
        this(targetName, moderatorName, reason, duration, silent, skipEvidence, type,
                moderatorName == null ? "Console" : moderatorName, Long.MAX_VALUE);
    }

    public PendingConfirmation(String targetName, String moderatorName, String reason, long duration, boolean silent,
                               boolean skipEvidence, String type, String ownerId, long expiresAt) {
        this.targetName = targetName;
        this.moderatorName = moderatorName;
        this.reason = reason;
        this.duration = duration;
        this.silent = silent;
        this.skipEvidence = skipEvidence;
        this.type = type;
        this.ownerId = ownerId;
        this.expiresAt = expiresAt;
    }

    public String getTargetName() { return targetName; }
    public String getModeratorName() { return moderatorName; }
    public String getReason() { return reason; }
    public long getDuration() { return duration; }
    public boolean isSilent() { return silent; }
    public boolean skipsEvidence() { return skipEvidence; }
    public String getType() { return type; }
    public String getOwnerId() { return ownerId; }
    public long getExpiresAt() { return expiresAt; }
}
