package com.chatsphere.server.db;

import com.chatsphere.common.model.UserSummary;
import com.chatsphere.common.protocol.ChatException;
import com.chatsphere.common.protocol.ErrorCode;
import com.chatsphere.common.protocol.Validation;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/** JDBC operations for user records, using the caller's transaction. */
public final class UserDAO {
    private UserDAO() {}

    public static void insertUser(Connection c, String username, String hash) throws SQLException {
        if (userId(c, username) != null) throw new ChatException(ErrorCode.USERNAME_TAKEN);
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO users(username, password_hash, created_at) VALUES (?, ?, ?)")) {
            ps.setString(1, username);
            ps.setString(2, hash);
            ps.setLong(3, System.currentTimeMillis());
            ps.executeUpdate();
        }
    }

    public static String findHash(Connection c, String username) throws SQLException {
        if (username == null) return null;
        try (PreparedStatement ps = c.prepareStatement("SELECT password_hash FROM users WHERE username = ?")) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private static final String LIST_USERS_SQL = """
            SELECT u.username,
                   (SELECT COUNT(*) FROM messages x
                     WHERE x.sender_id = u.id AND x.recipient_id = me.id AND x.status <> 'READ') AS unread,
                   last.text,
                   last.ts
              FROM users u
              LEFT JOIN users me ON me.username = ?
              LEFT JOIN messages last ON last.id = max(
                     COALESCE((SELECT MAX(a.id) FROM messages a WHERE a.sender_id = u.id AND a.recipient_id = me.id), 0),
                     COALESCE((SELECT MAX(b.id) FROM messages b WHERE b.sender_id = me.id AND b.recipient_id = u.id), 0))
             WHERE u.username <> ?
             ORDER BY u.username""";

    public static List<UserSummary> listUsers(Connection c, String user) throws SQLException {
        List<UserSummary> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(LIST_USERS_SQL)) {
            ps.setString(1, user);
            ps.setString(2, user);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new UserSummary(rs.getString(1), false, rs.getInt(2),
                            Validation.preview(rs.getString(3)), rs.getLong(4)));
                }
            }
        }
        return out;
    }

    public static Long userId(Connection c, String username) throws SQLException {
        if (username == null) return null;
        try (PreparedStatement ps = c.prepareStatement("SELECT id FROM users WHERE username = ?")) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : null;
            }
        }
    }

    public static long requireUser(Connection c, String username) throws SQLException {
        Long id = userId(c, username);
        if (id == null) throw new ChatException(ErrorCode.NO_SUCH_USER);
        return id;
    }
}
