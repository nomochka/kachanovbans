package dendzyhype.kachanovBans.data;

import dendzyhype.kachanovBans.KachanovBans;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

public class MySQLDatabase implements IDatabase {

    private final KachanovBans plugin;
    private final boolean debug;
    private Connection connection;
    private final ThreadLocal<Boolean> transactionActive = ThreadLocal.withInitial(() -> false);

    public MySQLDatabase(KachanovBans plugin) {
        this.plugin = plugin;
        this.debug = plugin.getPluginConfig().isDebug();
    }

    private String getUrl() {
        int timeoutMs = Math.max(1, plugin.getConfig().getInt("mysql.connect-timeout-seconds", 3)) * 1000;
        return "jdbc:mysql://" + plugin.getPluginConfig().getMysqlHost() + ":" +
                plugin.getPluginConfig().getMysqlPort() + "/" +
                plugin.getPluginConfig().getMysqlDatabase() +
                "?useSSL=false&autoReconnect=false&characterEncoding=UTF-8&connectTimeout=" + timeoutMs
                + "&socketTimeout=" + timeoutMs;
    }

    private synchronized Connection getConnection() throws SQLException {
        if (connection == null || connection.isClosed() || !connection.isValid(2)) {
            try {
                Class.forName("com.mysql.cj.jdbc.Driver");
            } catch (ClassNotFoundException exception) {
                throw new SQLException("MySQL JDBC driver is unavailable", exception);
            }
            connection = DriverManager.getConnection(
                    getUrl(),
                    plugin.getPluginConfig().getMysqlUser(),
                    plugin.getPluginConfig().getMysqlPassword()
            );
            if (debug) plugin.getLogger().info("[Debug] Подключение к MySQL установлено.");
        }
        return connection;
    }

    @Override
    public synchronized boolean transaction(Runnable action) {
        Connection activeConnection = null;
        boolean previousAutoCommit = true;
        try {
            activeConnection = getConnection();
            previousAutoCommit = activeConnection.getAutoCommit();
            activeConnection.setAutoCommit(false);
            transactionActive.set(true);
            action.run();
            activeConnection.commit();
            return true;
        } catch (Exception exception) {
            if (activeConnection != null) {
                try { activeConnection.rollback(); }
                catch (SQLException rollbackError) {
                    plugin.getLogger().severe("Ошибка rollback MySQL: " + rollbackError.getMessage());
                }
            }
            plugin.getLogger().severe("Транзакция MySQL отменена: " + exception.getMessage());
            return false;
        } finally {
            transactionActive.remove();
            if (activeConnection != null) {
                try { activeConnection.setAutoCommit(previousAutoCommit); }
                catch (SQLException exception) {
                    plugin.getLogger().severe("Не удалось восстановить autoCommit MySQL: " + exception.getMessage());
                }
            }
        }
    }

    private void writeFailure(String message, SQLException exception) {
        plugin.getLogger().warning(message + ": " + exception.getMessage());
        if (transactionActive.get()) throw new DatabaseWriteException(exception);
    }

    private static final class DatabaseWriteException extends RuntimeException {
        DatabaseWriteException(SQLException cause) { super(cause); }
    }

    @Override
    public synchronized void init() {
        String players = "CREATE TABLE IF NOT EXISTS players (" +
                "name VARCHAR(16) PRIMARY KEY, " +
                "ip VARCHAR(45), " +
                "last_login BIGINT" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;";
        String bans = "CREATE TABLE IF NOT EXISTS bans (" +
                "id INT PRIMARY KEY AUTO_INCREMENT, " +
                "player_name VARCHAR(16), " +
                "moderator_name VARCHAR(16), " +
                "reason TEXT, " +
                "issued_at BIGINT, " +
                "expiry BIGINT, " +
                "active BOOLEAN DEFAULT TRUE" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;";
        String mutes = "CREATE TABLE IF NOT EXISTS mutes (" +
                "id INT PRIMARY KEY AUTO_INCREMENT, " +
                "player_name VARCHAR(16), " +
                "moderator_name VARCHAR(16), " +
                "reason TEXT, " +
                "issued_at BIGINT, " +
                "expiry BIGINT, " +
                "active BOOLEAN DEFAULT TRUE" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;";
        String ipBans = "CREATE TABLE IF NOT EXISTS ip_bans (" +
                "ip VARCHAR(45) PRIMARY KEY, " +
                "reason TEXT, " +
                "issued_at BIGINT, " +
                "expiry BIGINT, " +
                "active BOOLEAN DEFAULT TRUE" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;";
        String history = "CREATE TABLE IF NOT EXISTS history (" +
                "id INT PRIMARY KEY AUTO_INCREMENT, " +
                "player_name VARCHAR(16), " +
                "type VARCHAR(20), " +
                "moderator_name VARCHAR(16), " +
                "reason TEXT, " +
                "issued_at BIGINT, " +
                "duration BIGINT, " +
                "ip VARCHAR(45)" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;";
        String ipHistory = "CREATE TABLE IF NOT EXISTS ip_history (" +
                "id INT PRIMARY KEY AUTO_INCREMENT, " +
                "player_name VARCHAR(16), " +
                "ip VARCHAR(45), " +
                "first_seen BIGINT, " +
                "last_seen BIGINT, " +
                "UNIQUE KEY player_ip (player_name, ip)" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;";
        String blackNicks = "CREATE TABLE IF NOT EXISTS black_nicks (" +
                "id INT PRIMARY KEY AUTO_INCREMENT, " +
                "player_name VARCHAR(16), " +
                "moderator_name VARCHAR(128), " +
                "reason TEXT, " +
                "task_key VARCHAR(32), " +
                "target BIGINT, " +
                "progress BIGINT, " +
                "previous_max_health DOUBLE, " +
                "issued_at BIGINT, " +
                "active BOOLEAN DEFAULT TRUE, " +
                "KEY player_active (player_name, active)" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;";

        try (Statement stmt = getConnection().createStatement()) {
            stmt.execute(players);
            stmt.execute(bans);
            stmt.execute(mutes);
            stmt.execute(ipBans);
            stmt.execute(history);
            stmt.execute(ipHistory);
            stmt.execute(blackNicks);
            stmt.execute("ALTER TABLE bans MODIFY moderator_name VARCHAR(128)");
            stmt.execute("ALTER TABLE mutes MODIFY moderator_name VARCHAR(128)");
            stmt.execute("ALTER TABLE history MODIFY moderator_name VARCHAR(128)");
            if (debug) plugin.getLogger().info("[Debug] Таблицы MySQL созданы/проверены.");
        } catch (SQLException e) {
            plugin.getLogger().severe("Ошибка инициализации MySQL таблиц: " + e.getMessage());
        }
    }

    @Override
    public synchronized void close() {
        try {
            if (connection != null && !connection.isClosed()) connection.close();
            if (debug) plugin.getLogger().info("[Debug] Соединение с MySQL закрыто.");
        } catch (SQLException ignored) {}
    }

    @Override
    public synchronized void upsertPlayer(String playerName, String ip, long lastLogin) {
        String sql = "INSERT INTO players (name, ip, last_login) VALUES (?, ?, ?) " +
                "ON DUPLICATE KEY UPDATE ip=COALESCE(VALUES(ip), ip), last_login=VALUES(last_login)";
        try (PreparedStatement ps = getConnection().prepareStatement(sql)) {
            ps.setString(1, playerName);
            ps.setString(2, ip);
            ps.setLong(3, lastLogin);
            ps.executeUpdate();
            if (debug) plugin.getLogger().info("[Debug] Upsert игрока: " + playerName);
        } catch (SQLException e) {
            writeFailure("Не удалось обновить игрока", e);
        }
    }

    @Override
    public synchronized boolean isPlayerExists(String playerName) {
        String sql = "SELECT name FROM players WHERE name = ?";
        try (PreparedStatement ps = getConnection().prepareStatement(sql)) {
            ps.setString(1, playerName);
            ResultSet rs = ps.executeQuery();
            return rs.next();
        } catch (SQLException e) {
            return false;
        }
    }

    @Override
    public synchronized void addBan(String playerName, String moderatorName, String reason, long issuedAt, long expiry) {
        String sql = "INSERT INTO bans (player_name, moderator_name, reason, issued_at, expiry, active) VALUES (?, ?, ?, ?, ?, TRUE)";
        try (PreparedStatement ps = getConnection().prepareStatement(sql)) {
            ps.setString(1, playerName);
            ps.setString(2, moderatorName);
            ps.setString(3, reason);
            ps.setLong(4, issuedAt);
            ps.setLong(5, expiry);
            ps.executeUpdate();
            if (debug) plugin.getLogger().info("[Debug] Добавлен бан для " + playerName);
        } catch (SQLException e) {
            writeFailure("Ошибка добавления бана", e);
        }
    }

    @Override
    public synchronized void deactivateBan(String playerName) {
        String sql = "UPDATE bans SET active = FALSE WHERE player_name = ? AND active = TRUE";
        try (PreparedStatement ps = getConnection().prepareStatement(sql)) {
            ps.setString(1, playerName);
            ps.executeUpdate();
            if (debug) plugin.getLogger().info("[Debug] Деактивирован бан для " + playerName);
        } catch (SQLException e) {
            writeFailure("Ошибка деактивации бана", e);
        }
    }

    @Override
    public synchronized Models.Ban getActiveBan(String playerName) {
        String sql = "SELECT * FROM bans WHERE player_name = ? AND active = TRUE ORDER BY issued_at DESC LIMIT 1";
        try (PreparedStatement ps = getConnection().prepareStatement(sql)) {
            ps.setString(1, playerName);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                return new Models.Ban(
                        rs.getString("player_name"),
                        rs.getString("moderator_name"),
                        rs.getString("reason"),
                        rs.getLong("issued_at"),
                        rs.getLong("expiry")
                );
            }
        } catch (SQLException ignored) {}
        return null;
    }

    @Override
    public synchronized List<Models.Ban> getActiveBans() {
        List<Models.Ban> list = new ArrayList<>();
        String sql = "SELECT * FROM bans WHERE active = TRUE ORDER BY issued_at DESC";
        try (Statement stmt = getConnection().createStatement(); ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                list.add(new Models.Ban(
                        rs.getString("player_name"),
                        rs.getString("moderator_name"),
                        rs.getString("reason"),
                        rs.getLong("issued_at"),
                        rs.getLong("expiry")
                ));
            }
        } catch (SQLException ignored) {}
        return list;
    }

    @Override
    public synchronized void addMute(String playerName, String moderatorName, String reason, long issuedAt, long expiry) {
        String sql = "INSERT INTO mutes (player_name, moderator_name, reason, issued_at, expiry, active) VALUES (?, ?, ?, ?, ?, TRUE)";
        try (PreparedStatement ps = getConnection().prepareStatement(sql)) {
            ps.setString(1, playerName);
            ps.setString(2, moderatorName);
            ps.setString(3, reason);
            ps.setLong(4, issuedAt);
            ps.setLong(5, expiry);
            ps.executeUpdate();
            if (debug) plugin.getLogger().info("[Debug] Добавлен мут для " + playerName);
        } catch (SQLException e) {
            writeFailure("Ошибка добавления мута", e);
        }
    }

    @Override
    public synchronized void deactivateMute(String playerName) {
        if (debug) plugin.getLogger().info("[DebugMute] deactivateMute: " + playerName);
        String sql = "UPDATE mutes SET active = FALSE WHERE player_name = ? AND active = TRUE";
        try (PreparedStatement ps = getConnection().prepareStatement(sql)) {
            ps.setString(1, playerName);
            int updated = ps.executeUpdate();
            if (debug) plugin.getLogger().info("[DebugMute] Деактивирован мут для " + playerName + ", обновлено записей: " + updated);
        } catch (SQLException e) {
            writeFailure("Ошибка деактивации мута", e);
        }
    }

    @Override
    public synchronized Models.Mute getActiveMute(String playerName) {
        String sql = "SELECT * FROM mutes WHERE player_name = ? AND active = TRUE ORDER BY issued_at DESC LIMIT 1";
        try (PreparedStatement ps = getConnection().prepareStatement(sql)) {
            ps.setString(1, playerName);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                if (debug) plugin.getLogger().info("[DebugMute] getActiveMute: найден мут для " + playerName + ", expiry=" + rs.getLong("expiry"));
                return new Models.Mute(
                        rs.getString("player_name"),
                        rs.getString("moderator_name"),
                        rs.getString("reason"),
                        rs.getLong("issued_at"),
                        rs.getLong("expiry")
                );
            }
        } catch (SQLException ignored) {}
        if (debug) plugin.getLogger().info("[DebugMute] getActiveMute: мут не найден для " + playerName);
        return null;
    }

    @Override
    public synchronized List<Models.Mute> getActiveMutes() {
        List<Models.Mute> list = new ArrayList<>();
        String sql = "SELECT * FROM mutes WHERE active = TRUE ORDER BY issued_at DESC";
        try (Statement stmt = getConnection().createStatement(); ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                list.add(new Models.Mute(
                        rs.getString("player_name"),
                        rs.getString("moderator_name"),
                        rs.getString("reason"),
                        rs.getLong("issued_at"),
                        rs.getLong("expiry")
                ));
            }
        } catch (SQLException ignored) {}
        return list;
    }

    @Override
    public synchronized void updateMuteExpiry(String playerName, long newExpiry) {
        if (debug) plugin.getLogger().info("[DebugMute] updateMuteExpiry: " + playerName + " -> " + newExpiry);
        String sql = "UPDATE mutes SET expiry = ? WHERE player_name = ? AND active = TRUE";
        try (PreparedStatement ps = getConnection().prepareStatement(sql)) {
            ps.setLong(1, newExpiry);
            ps.setString(2, playerName);
            int updated = ps.executeUpdate();
            if (debug) plugin.getLogger().info("[DebugMute] Обновлён expiry мута для " + playerName + " -> " + newExpiry + ", обновлено записей: " + updated);
        } catch (SQLException e) {
            writeFailure("Ошибка обновления expiry мута", e);
        }
    }

    @Override
    public synchronized void addIpBan(String ip, String reason, long issuedAt, long expiry) {
        String sql = "INSERT INTO ip_bans (ip, reason, issued_at, expiry, active) VALUES (?, ?, ?, ?, TRUE) " +
                "ON DUPLICATE KEY UPDATE reason=VALUES(reason), issued_at=VALUES(issued_at), expiry=VALUES(expiry), active=TRUE";
        try (PreparedStatement ps = getConnection().prepareStatement(sql)) {
            ps.setString(1, ip);
            ps.setString(2, reason);
            ps.setLong(3, issuedAt);
            ps.setLong(4, expiry);
            ps.executeUpdate();
            if (debug) plugin.getLogger().info("[Debug] Добавлен IP-бан для " + ip);
        } catch (SQLException e) {
            writeFailure("Ошибка IP-бана", e);
        }
    }

    @Override
    public synchronized void deactivateIpBan(String ip) {
        String sql = "UPDATE ip_bans SET active = FALSE WHERE ip = ? AND active = TRUE";
        try (PreparedStatement ps = getConnection().prepareStatement(sql)) {
            ps.setString(1, ip);
            ps.executeUpdate();
            if (debug) plugin.getLogger().info("[Debug] Деактивирован IP-бан для " + ip);
        } catch (SQLException e) {
            writeFailure("Ошибка снятия IP-бана", e);
        }
    }

    @Override
    public synchronized Models.IpBan getActiveIpBan(String ip) {
        String sql = "SELECT * FROM ip_bans WHERE ip = ? AND active = TRUE";
        try (PreparedStatement ps = getConnection().prepareStatement(sql)) {
            ps.setString(1, ip);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                return new Models.IpBan(
                        rs.getString("ip"),
                        rs.getString("reason"),
                        rs.getLong("issued_at"),
                        rs.getLong("expiry")
                );
            }
        } catch (SQLException ignored) {}
        return null;
    }

    @Override
    public synchronized void addHistory(String playerName, String type, String moderatorName, String reason, long issuedAt, long duration, String ip) {
        String sql = "INSERT INTO history (player_name, type, moderator_name, reason, issued_at, duration, ip) VALUES (?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = getConnection().prepareStatement(sql)) {
            ps.setString(1, playerName);
            ps.setString(2, type);
            ps.setString(3, moderatorName);
            ps.setString(4, reason);
            ps.setLong(5, issuedAt);
            ps.setLong(6, duration);
            ps.setString(7, ip);
            ps.executeUpdate();
            if (debug) plugin.getLogger().info("[Debug] Добавлена запись истории для " + playerName + " тип: " + type);
        } catch (SQLException e) {
            writeFailure("Ошибка записи истории", e);
        }
    }

    @Override
    public synchronized List<Models.HistoryEntry> getHistory(String playerName) {
        List<Models.HistoryEntry> list = new ArrayList<>();
        String sql = "SELECT * FROM history WHERE player_name = ? ORDER BY issued_at DESC";
        try (PreparedStatement ps = getConnection().prepareStatement(sql)) {
            ps.setString(1, playerName);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                list.add(new Models.HistoryEntry(
                        rs.getString("player_name"),
                        rs.getString("type"),
                        rs.getString("moderator_name"),
                        rs.getString("reason"),
                        rs.getLong("issued_at"),
                        rs.getLong("duration"),
                        rs.getString("ip")
                ));
            }
        } catch (SQLException ignored) {}
        return list;
    }

    @Override
    public synchronized List<Models.HistoryEntry> getHistoryByModerator(String moderatorName) {
        List<Models.HistoryEntry> list = new ArrayList<>();
        String sql = "SELECT * FROM history WHERE LOWER(moderator_name) = LOWER(?) ORDER BY issued_at DESC";
        try (PreparedStatement ps = getConnection().prepareStatement(sql)) {
            ps.setString(1, moderatorName);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                list.add(new Models.HistoryEntry(
                        rs.getString("player_name"), rs.getString("type"), rs.getString("moderator_name"),
                        rs.getString("reason"), rs.getLong("issued_at"), rs.getLong("duration"), rs.getString("ip")
                ));
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Ошибка чтения истории сотрудника: " + e.getMessage());
        }
        return list;
    }

    @Override
    public synchronized void addIpHistory(String playerName, String ip, long lastSeen) {
        String sql = "INSERT INTO ip_history (player_name, ip, first_seen, last_seen) VALUES (?, ?, ?, ?) " +
                "ON DUPLICATE KEY UPDATE last_seen=VALUES(last_seen)";
        try (PreparedStatement ps = getConnection().prepareStatement(sql)) {
            ps.setString(1, playerName);
            ps.setString(2, ip);
            ps.setLong(3, lastSeen);
            ps.setLong(4, lastSeen);
            ps.executeUpdate();
            if (debug) plugin.getLogger().info("[Debug] Добавлена IP-история для " + playerName + " IP: " + ip);
        } catch (SQLException e) {
            writeFailure("Ошибка записи IP-истории", e);
        }
    }

    @Override
    public synchronized List<Models.IpHistoryEntry> getIpHistory(String playerName) {
        List<Models.IpHistoryEntry> list = new ArrayList<>();
        String sql = "SELECT * FROM ip_history WHERE player_name = ? ORDER BY last_seen DESC";
        try (PreparedStatement ps = getConnection().prepareStatement(sql)) {
            ps.setString(1, playerName);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                list.add(new Models.IpHistoryEntry(
                        rs.getString("player_name"),
                        rs.getString("ip"),
                        rs.getLong("first_seen"),
                        rs.getLong("last_seen")
                ));
            }
        } catch (SQLException ignored) {}
        return list;
    }

    @Override
    public synchronized List<Models.IpHistoryEntry> getPlayersByIp(String ip) {
        List<Models.IpHistoryEntry> list = new ArrayList<>();
        String sql = "SELECT * FROM ip_history WHERE ip = ?";
        try (PreparedStatement ps = getConnection().prepareStatement(sql)) {
            ps.setString(1, ip);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                list.add(new Models.IpHistoryEntry(
                        rs.getString("player_name"),
                        rs.getString("ip"),
                        rs.getLong("first_seen"),
                        rs.getLong("last_seen")
                ));
            }
        } catch (SQLException ignored) {}
        return list;
    }

    @Override
    public synchronized void addBlackNick(Models.BlackNick record) {
        String deactivate = "UPDATE black_nicks SET active = FALSE WHERE player_name = ? AND active = TRUE";
        String insert = "INSERT INTO black_nicks (player_name, moderator_name, reason, task_key, target, progress, "
                + "previous_max_health, issued_at, active) VALUES (?, ?, ?, ?, ?, ?, ?, ?, TRUE)";
        try {
            try (PreparedStatement ps = getConnection().prepareStatement(deactivate)) {
                ps.setString(1, record.playerName());
                ps.executeUpdate();
            }
            try (PreparedStatement ps = getConnection().prepareStatement(insert)) {
                ps.setString(1, record.playerName());
                ps.setString(2, record.moderatorName());
                ps.setString(3, record.reason());
                ps.setString(4, record.taskKey());
                ps.setLong(5, record.target());
                ps.setLong(6, record.progress());
                ps.setDouble(7, record.previousMaxHealth());
                ps.setLong(8, record.issuedAt());
                ps.executeUpdate();
            }
            if (debug) plugin.getLogger().info("[Debug] Добавлен чёрный ник для " + record.playerName());
        } catch (SQLException e) {
            writeFailure("Ошибка добавления чёрного ника", e);
        }
    }

    @Override
    public synchronized void deactivateBlackNick(String playerName) {
        String sql = "UPDATE black_nicks SET active = FALSE WHERE player_name = ? AND active = TRUE";
        try (PreparedStatement ps = getConnection().prepareStatement(sql)) {
            ps.setString(1, playerName);
            ps.executeUpdate();
            if (debug) plugin.getLogger().info("[Debug] Снят чёрный ник у " + playerName);
        } catch (SQLException e) {
            writeFailure("Ошибка снятия чёрного ника", e);
        }
    }

    @Override
    public synchronized void updateBlackNickProgress(String playerName, long progress) {
        String sql = "UPDATE black_nicks SET progress = ? WHERE player_name = ? AND active = TRUE";
        try (PreparedStatement ps = getConnection().prepareStatement(sql)) {
            ps.setLong(1, progress);
            ps.setString(2, playerName);
            ps.executeUpdate();
        } catch (SQLException e) {
            writeFailure("Ошибка обновления прогресса чёрного ника", e);
        }
    }

    @Override
    public synchronized Models.BlackNick getActiveBlackNick(String playerName) {
        String sql = "SELECT * FROM black_nicks WHERE player_name = ? AND active = TRUE ORDER BY issued_at DESC LIMIT 1";
        try (PreparedStatement ps = getConnection().prepareStatement(sql)) {
            ps.setString(1, playerName);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return readBlackNick(rs);
        } catch (SQLException ignored) {}
        return null;
    }

    @Override
    public synchronized List<Models.BlackNick> getActiveBlackNicks() {
        List<Models.BlackNick> list = new ArrayList<>();
        String sql = "SELECT * FROM black_nicks WHERE active = TRUE ORDER BY issued_at DESC";
        try (Statement stmt = getConnection().createStatement(); ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) list.add(readBlackNick(rs));
        } catch (SQLException ignored) {}
        return list;
    }

    private Models.BlackNick readBlackNick(ResultSet rs) throws SQLException {
        return new Models.BlackNick(
                rs.getString("player_name"),
                rs.getString("moderator_name"),
                rs.getString("reason"),
                rs.getString("task_key"),
                rs.getLong("target"),
                rs.getLong("progress"),
                rs.getDouble("previous_max_health"),
                rs.getLong("issued_at")
        );
    }
}
