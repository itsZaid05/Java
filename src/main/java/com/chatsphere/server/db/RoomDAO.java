package com.chatsphere.server.db;

import com.chatsphere.common.model.RoomSummary;
import com.chatsphere.common.protocol.ChatException;
import com.chatsphere.common.protocol.ErrorCode;
import com.chatsphere.common.protocol.Validation;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/** JDBC operations for room records, using the caller's transaction. */
public final class RoomDAO {
    private RoomDAO() {}

    public static void createRoom(Connection c, String user, String room) throws SQLException {
        long uid = UserDAO.requireUser(c, user);
        if (roomId(c, room) != null) throw new ChatException(ErrorCode.ROOM_EXISTS);
        long now = System.currentTimeMillis();
        long rid;
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO rooms(name, created_by, created_at) VALUES (?, ?, ?)", Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, room);
            ps.setLong(2, uid);
            ps.setLong(3, now);
            ps.executeUpdate();
            try (ResultSet k = ps.getGeneratedKeys()) {
                k.next();
                rid = k.getLong(1);
            }
        }
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO room_members(room_id, user_id, last_read_id, joined_at) VALUES (?, ?, 0, ?)")) {
            ps.setLong(1, rid);
            ps.setLong(2, uid);
            ps.setLong(3, now);
            ps.executeUpdate();
        }
    }

    public static void joinRoom(Connection c, String user, String room) throws SQLException {
        long rid = requireRoom(c, room);
        long uid = UserDAO.requireUser(c, user);
        if (marker(c, rid, user) != null) return; // already a member: idempotent
        long newest = MessageDAO.newestRoomMessageId(c, rid);
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO room_members(room_id, user_id, last_read_id, joined_at) VALUES (?, ?, ?, ?)")) {
            ps.setLong(1, rid);
            ps.setLong(2, uid);
            ps.setLong(3, newest);
            ps.setLong(4, System.currentTimeMillis());
            ps.executeUpdate();
        }
    }

    public static void leaveRoom(Connection c, String user, String room) throws SQLException {
        long rid = requireRoom(c, room);
        try (PreparedStatement ps = c.prepareStatement(
                "DELETE FROM room_members WHERE room_id = ? AND user_id = (SELECT id FROM users WHERE username = ?)")) {
            ps.setLong(1, rid);
            ps.setString(2, user);
            ps.executeUpdate();
        }
    }

    private static final String LIST_ROOMS_SQL = """
            SELECT r.name,
                   (SELECT COUNT(*) FROM room_members cm WHERE cm.room_id = r.id) AS member_count,
                   mine.user_id IS NOT NULL AS joined,
                   CASE WHEN mine.user_id IS NULL THEN 0 ELSE
                     (SELECT COUNT(*) FROM messages x
                       WHERE x.room_id = r.id AND x.id > mine.last_read_id AND x.sender_id <> mine.user_id) END AS unread,
                   CASE WHEN mine.user_id IS NULL THEN NULL ELSE
                     (SELECT l.text FROM messages l WHERE l.room_id = r.id ORDER BY l.id DESC LIMIT 1) END AS last_text,
                   CASE WHEN mine.user_id IS NULL THEN 0 ELSE
                     COALESCE((SELECT l.ts FROM messages l WHERE l.room_id = r.id ORDER BY l.id DESC LIMIT 1), 0) END AS last_ts
              FROM rooms r
              LEFT JOIN room_members mine
                     ON mine.room_id = r.id
                    AND mine.user_id = (SELECT id FROM users WHERE username = ?)
             WHERE (? = 0 OR mine.user_id IS NOT NULL)
             ORDER BY r.name""";

    public static List<RoomSummary> listRooms(Connection c, String user, boolean onlyMine) throws SQLException {
        List<RoomSummary> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(LIST_ROOMS_SQL)) {
            ps.setString(1, user);
            ps.setInt(2, onlyMine ? 1 : 0);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new RoomSummary(rs.getString(1), rs.getInt(2), rs.getInt(3) != 0, rs.getInt(4),
                            Validation.preview(rs.getString(5)), rs.getLong(6)));
                }
            }
        }
        return out;
    }

    public static List<String> roomMembers(Connection c, String user, String room) throws SQLException {
        long rid = requireRoom(c, room);
        if (marker(c, rid, user) == null) throw new ChatException(ErrorCode.NOT_MEMBER);
        return memberNames(c, rid);
    }

    public static List<String> roomMembersForFanout(Connection c, String room) throws SQLException {
        Long rid = roomId(c, room);
        return rid == null ? List.<String>of() : memberNames(c, rid);
    }

    public static void markRoomRead(Connection c, String user, String room, long upToId) throws SQLException {
        long rid = requireRoom(c, room);
        if (marker(c, rid, user) == null) throw new ChatException(ErrorCode.NOT_MEMBER);
        long newest = MessageDAO.newestRoomMessageId(c, rid);
        long target = Math.min(upToId, newest); // never point past the newest message
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE room_members SET last_read_id = max(last_read_id, ?) "
                        + "WHERE room_id = ? AND user_id = (SELECT id FROM users WHERE username = ?)")) {
            ps.setLong(1, target);
            ps.setLong(2, rid);
            ps.setString(3, user);
            ps.executeUpdate();
        }
    }

    private static List<String> memberNames(Connection c, long rid) throws SQLException {
        List<String> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT u.username FROM room_members m JOIN users u ON u.id = m.user_id "
                        + "WHERE m.room_id = ? ORDER BY u.username")) {
            ps.setLong(1, rid);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(rs.getString(1));
            }
        }
        return out;
    }

    static Long roomId(Connection c, String room) throws SQLException {
        if (room == null) return null;
        try (PreparedStatement ps = c.prepareStatement("SELECT id FROM rooms WHERE name = ?")) {
            ps.setString(1, room);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : null;
            }
        }
    }

    public static long requireRoom(Connection c, String room) throws SQLException {
        Long id = roomId(c, room);
        if (id == null) throw new ChatException(ErrorCode.NO_SUCH_ROOM);
        return id;
    }

    public static Long marker(Connection c, long roomId, String user) throws SQLException {
        if (user == null) return null;
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT m.last_read_id FROM room_members m JOIN users u ON u.id = m.user_id "
                        + "WHERE m.room_id = ? AND u.username = ?")) {
            ps.setLong(1, roomId);
            ps.setString(2, user);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : null;
            }
        }
    }
}
