package dendzyhype.kachanovBans.integrations;

import dendzyhype.kachanovBans.KachanovBans;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public final class PlasmoVoiceMuteService {
    private final KachanovBans plugin;

    public PlasmoVoiceMuteService(KachanovBans plugin) {
        this.plugin = plugin;
    }

    public boolean isAvailable() {
        Plugin plasmoVoice = Bukkit.getPluginManager().getPlugin("PlasmoVoice");
        return plasmoVoice != null && plasmoVoice.isEnabled();
    }

    public List<VoiceMute> getActiveMutes() {
        if (!isAvailable()) return List.of();
        try {
            Object storage = muteStorage();
            Method getMutedPlayers = storage.getClass().getMethod("getMutedPlayers");
            Collection<?> rawMutes = (Collection<?>) getMutedPlayers.invoke(storage);
            long now = System.currentTimeMillis();
            List<VoiceMute> result = new ArrayList<>();
            for (Object rawMute : rawMutes) {
                VoiceMute mute = convert(rawMute);
                if (mute.expiry() == 0 || mute.expiry() > now) result.add(mute);
            }
            result.sort(Comparator.comparingLong(VoiceMute::issuedAt).reversed());
            return result;
        } catch (ReflectiveOperationException | RuntimeException exception) {
            plugin.getLogger().warning("Не удалось прочитать voice-муты Plasmo Voice: " + exception.getMessage());
            return List.of();
        }
    }

    public Optional<VoiceMute> findActiveMute(String playerName) {
        return getActiveMutes().stream()
                .filter(mute -> mute.playerName().equalsIgnoreCase(playerName)
                        || mute.playerId().toString().equalsIgnoreCase(playerName))
                .findFirst();
    }

    private Object muteStorage() throws ReflectiveOperationException {
        Plugin plasmoVoice = Bukkit.getPluginManager().getPlugin("PlasmoVoice");
        if (plasmoVoice == null || !plasmoVoice.isEnabled()) {
            throw new IllegalStateException("Plasmo Voice не загружен");
        }
        Field voiceServerField = plasmoVoice.getClass().getDeclaredField("voiceServer");
        voiceServerField.setAccessible(true);
        Object voiceServer = voiceServerField.get(plasmoVoice);
        Object muteManager = voiceServer.getClass().getMethod("getMuteManager").invoke(voiceServer);
        return muteManager.getClass().getMethod("getMuteStorage").invoke(muteManager);
    }

    private VoiceMute convert(Object rawMute) throws ReflectiveOperationException {
        Class<?> type = rawMute.getClass();
        UUID playerId = (UUID) type.getMethod("getPlayerUUID").invoke(rawMute);
        UUID moderatorId = (UUID) type.getMethod("getMutedByPlayerUUID").invoke(rawMute);
        long issuedAt = ((Number) type.getMethod("getMutedAtTime").invoke(rawMute)).longValue();
        long expiry = ((Number) type.getMethod("getMutedToTime").invoke(rawMute)).longValue();
        String reason = (String) type.getMethod("getReason").invoke(rawMute);
        return new VoiceMute(
                playerId,
                playerName(playerId, playerId.toString()),
                moderatorId == null ? "Console" : playerName(moderatorId, moderatorId.toString()),
                reason == null || reason.isBlank() ? "Не указана" : reason,
                issuedAt,
                expiry
        );
    }

    private String playerName(UUID playerId, String fallback) {
        OfflinePlayer player = Bukkit.getOfflinePlayer(playerId);
        String name = player.getName();
        return name == null || name.isBlank() ? fallback : name;
    }

    public record VoiceMute(UUID playerId, String playerName, String moderatorName,
                            String reason, long issuedAt, long expiry) {}
}
