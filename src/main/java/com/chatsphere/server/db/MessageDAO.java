package com.chatsphere.server.db;

import com.chatsphere.common.model.*;
import com.chatsphere.common.protocol.ChatException;
import com.chatsphere.common.protocol.ErrorCode;
import com.chatsphere.common.protocol.Limits;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** JDBC operations for message records, using the caller's transaction. */
public final class MessageDAO {
    private MessageDAO() {}

    public static long insertMessage(Connection c, String type, Long roomId, long senderId, Long recipientId,
                                      String text, long ts) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO messages(type, room_id, sender_id, recipient_id, text, ts, status) "
                        + "VALUES (?, ?, ?, ?, ?, ?, 'SENT')", Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, type);
            if (roomId == null) ps.setNull(2, java.sql.Types.INTEGER); else ps.setLong(2, roomId);
            ps.setLong(3, senderId);
            if (recipientId == null) ps.setNull(4, java.sql.Types.INTEGER); else ps.setLong(4, recipientId);
            ps.setString(5, text);
            ps.setLong(6, ts);
            ps.executeUpdate();
            try (ResultSet k = ps.getGeneratedKeys()) {
                k.next();
                return k.getLong(1);
            }
        }
    }

    public static void insertAttachment(Connection connection, long messageId, byte[] data) throws SQLException {
        try (var statement = connection.prepareStatement(
                "INSERT INTO attachments(message_id, size, content) VALUES (?, ?, ?)")) {
            statement.setLong(1, messageId);
            statement.setInt(2, data.length);
            statement.setBytes(3, data);
            statement.executeUpdate();
        }
    }

    public static byte[] attachmentChunk(Connection connection, long requesterId, long messageId, int offset)
            throws SQLException {
        try (var statement = connection.prepareStatement("""
                SELECT COALESCE(substr(a.content, ?, ?), X'') FROM attachments a
                JOIN messages m ON m.id = a.message_id
                WHERE m.id = ? AND a.size >= ?
                  AND (m.sender_id = ? AND m.type = 'PRIVATE' OR m.recipient_id = ?
                       OR EXISTS (SELECT 1 FROM room_members
                                  WHERE room_id = m.room_id AND user_id = ?))
                """)) {
            statement.setLong(1, (long) offset + 1);
            statement.setInt(2, Limits.ATTACHMENT_CHUNK_BYTES);
            statement.setLong(3, messageId);
            statement.setInt(4, offset);
            statement.setLong(5, requesterId);
            statement.setLong(6, requesterId);
            statement.setLong(7, requesterId);
            try (var result = statement.executeQuery()) {
                return result.next() ? result.getBytes(1) : null;
            }
        }
    }

    public static void markDelivered(Connection c, List<Long> messageIds) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE messages SET status = 'DELIVERED' WHERE id = ? AND type = 'PRIVATE' AND status = 'SENT'")) {
            for (Long id : messageIds) {
                if (id == null) continue;
                ps.setLong(1, id);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    public static Map<String, List<Long>> markAllDeliveredFor(Connection c, String recipient) throws SQLException {
        Map<String, List<Long>> out = new LinkedHashMap<>();
        Long rid = UserDAO.userId(c, recipient);
        if (rid == null) return out;
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT m.id, s.username FROM messages m JOIN users s ON s.id = m.sender_id "
                        + "WHERE m.recipient_id = ? AND m.status = 'SENT' ORDER BY m.id")) {
            ps.setLong(1, rid);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.computeIfAbsent(rs.getString(2), k -> new ArrayList<>()).add(rs.getLong(1));
                }
            }
        }
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE messages SET status = 'DELIVERED' WHERE recipient_id = ? AND status = 'SENT'")) {
            ps.setLong(1, rid);
            ps.executeUpdate();
        }
        return out;
    }

    public static List<Long> markPrivateRead(Connection c, String reader, String peer, long upToId) throws SQLException {
        List<Long> changed = new ArrayList<>();
        Long pid = UserDAO.requireUser(c, peer);
        Long rid = UserDAO.userId(c, reader);
        if (rid == null) return changed;
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT id FROM messages WHERE sender_id = ? AND recipient_id = ? AND id <= ? "
                        + "AND status <> 'READ' ORDER BY id")) {
            ps.setLong(1, pid);
            ps.setLong(2, rid);
            ps.setLong(3, upToId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) changed.add(rs.getLong(1));
            }
        }
        if (!changed.isEmpty()) {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE messages SET status = 'READ' WHERE sender_id = ? AND recipient_id = ? AND id <= ? "
                            + "AND status <> 'READ'")) {
                ps.setLong(1, pid);
                ps.setLong(2, rid);
                ps.setLong(3, upToId);
                ps.executeUpdate();
            }
        }
        return changed;
    }

    /** A resolved, access-checked conversation: the WHERE fragment, its parameters and what we need for unread. */
    private record Conv(boolean room, String where, long[] params, long me, long marker, long ref) {}

    private static final String ROOM_WHERE = "m.room_id = ?";

    private static final String PRIVATE_WHERE =
            "m.type = 'PRIVATE' AND ((m.sender_id = ? AND m.recipient_id = ?) OR (m.sender_id = ? AND m.recipient_id = ?))";

    private static Conv conv(Connection c, String user, TargetType type, String target) throws SQLException {
        if (type == null || target == null) throw new ChatException(ErrorCode.BAD_REQUEST);
        if (type == TargetType.ROOM) {
            long rid = RoomDAO.requireRoom(c, target);
            Long marker = RoomDAO.marker(c, rid, user);
            if (marker == null) throw new ChatException(ErrorCode.NOT_MEMBER);
            long me = UserDAO.requireUser(c, user);
            return new Conv(true, ROOM_WHERE, new long[]{rid}, me, marker, rid);
        }
        Long me = UserDAO.userId(c, user);
        Long peer = UserDAO.userId(c, target);
        if (me == null || peer == null) throw new ChatException(ErrorCode.NO_SUCH_USER);
        return new Conv(false, PRIVATE_WHERE, new long[]{me, peer, peer, me}, me, 0, peer);
    }

    /** extra is one of a few fixed fragments (never user input); extraParams are bound. */
    private static List<MessageRecord> fetch(Connection c, Conv cv, String extra, long[] extraParams,
                                             boolean desc, int limit) throws SQLException {
        String sql = "SELECT m.id, s.username, m.ts, m.status, m.text, a.size FROM messages m "
                + "JOIN users s ON s.id = m.sender_id LEFT JOIN attachments a ON a.message_id = m.id WHERE " + cv.where() + extra
                + " ORDER BY m.id " + (desc ? "DESC" : "ASC") + " LIMIT ?";
        List<MessageRecord> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            int i = 1;
            for (long p : cv.params()) ps.setLong(i++, p);
            for (long p : extraParams) ps.setLong(i++, p);
            ps.setInt(i, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    DeliveryStatus st = cv.room() ? DeliveryStatus.SENT : DeliveryStatus.valueOf(rs.getString(4));
                    out.add(new MessageRecord(rs.getLong(1), rs.getString(2), rs.getLong(3), st, rs.getString(5), (Integer) rs.getObject(6)));
                }
            }
        }
        return out;
    }

    private static long firstUnread(Connection c, Conv cv) throws SQLException {
        if (cv.room()) {
            return scalarLong(c, "SELECT COALESCE(MIN(id), 0) FROM messages WHERE room_id = ? AND id > ? AND sender_id <> ?",
                    cv.ref(), cv.marker(), cv.me());
        }
        return scalarLong(c, "SELECT COALESCE(MIN(id), 0) FROM messages "
                + "WHERE sender_id = ? AND recipient_id = ? AND status <> 'READ'", cv.ref(), cv.me());
    }

    public static HistoryPage history(Connection c, String user, TargetType type, String target, long beforeId, int lim) throws SQLException {
        Conv cv = conv(c, user, type, target);
        List<MessageRecord> rows = beforeId > 0
                ? fetch(c, cv, " AND m.id < ?", new long[]{beforeId}, true, lim + 1)
                : fetch(c, cv, "", new long[0], true, lim + 1);
        boolean hasMore = rows.size() > lim;
        List<MessageRecord> page = new ArrayList<>(hasMore ? rows.subList(0, lim) : rows);
        Collections.reverse(page); // ascending
        long firstUnread = beforeId > 0 ? 0 : firstUnread(c, cv);
        return new HistoryPage(page, hasMore, firstUnread);
    }

    public static HistoryPage historyAround(Connection c, String user, TargetType type, String target, long messageId, int w) throws SQLException {
        Conv cv = conv(c, user, type, target);
        List<MessageRecord> self = fetch(c, cv, " AND m.id = ?", new long[]{messageId}, false, 1);
        if (self.isEmpty()) throw new ChatException(ErrorCode.BAD_REQUEST, "message not in conversation");
        List<MessageRecord> older = fetch(c, cv, " AND m.id < ?", new long[]{messageId}, true, w + 1);
        boolean hasMore = older.size() > w;
        List<MessageRecord> before = new ArrayList<>(hasMore ? older.subList(0, w) : older);
        Collections.reverse(before);
        List<MessageRecord> after = fetch(c, cv, " AND m.id > ?", new long[]{messageId}, false, w);
        List<MessageRecord> all = new ArrayList<>(before);
        all.add(self.get(0));
        all.addAll(after);
        return new HistoryPage(all, hasMore, firstUnread(c, cv));
    }

    public static List<SearchHit> search(Connection c, String user, SearchQuery q) throws SQLException {
        Long me = UserDAO.userId(c, user);
        if (me == null) return List.<SearchHit>of();

        StringBuilder sql = new StringBuilder("""
                SELECT m.id, m.type, r.name, s.username, rc.username, m.ts, m.text
                  FROM messages m
                  JOIN users s ON s.id = m.sender_id
                  LEFT JOIN rooms r ON r.id = m.room_id
                  LEFT JOIN users rc ON rc.id = m.recipient_id
                 WHERE ((m.type = 'ROOM' AND EXISTS (SELECT 1 FROM room_members rm
                                                      WHERE rm.room_id = m.room_id AND rm.user_id = ?))
                     OR (m.type = 'PRIVATE' AND (m.sender_id = ? OR m.recipient_id = ?)))
                   AND m.text LIKE ? ESCAPE '\\'""");
        List<Object> args = new ArrayList<>(List.of(me, me, me, "%" + escapeLike(q.keyword()) + "%"));

        if (q.sender() != null && !q.sender().isEmpty()) {
            sql.append(" AND s.username = ?");
            args.add(q.sender());
        }
        if (q.fromTs() > 0) {
            sql.append(" AND m.ts >= ?");
            args.add(q.fromTs());
        }
        if (q.toTs() > 0) {
            sql.append(" AND m.ts <= ?");
            args.add(q.toTs());
        }
        if (q.currentOnly()) {
            if (q.targetType() == TargetType.ROOM) {
                sql.append(" AND m.type = 'ROOM' AND r.name = ?");
                args.add(q.target());
            } else {
                Long peer = UserDAO.userId(c, q.target());
                if (peer == null) return List.<SearchHit>of();
                sql.append(" AND m.type = 'PRIVATE' AND ((m.sender_id = ? AND m.recipient_id = ?)"
                        + " OR (m.sender_id = ? AND m.recipient_id = ?))");
                args.add(me);
                args.add(peer);
                args.add(peer);
                args.add(me);
            }
        }
        sql.append(" ORDER BY m.id DESC LIMIT ?");
        args.add((long) Limits.SEARCH_MAX_RESULTS);

        List<SearchHit> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(sql.toString())) {
            for (int i = 0; i < args.size(); i++) {
                Object a = args.get(i);
                if (a instanceof Long l) ps.setLong(i + 1, l); else ps.setString(i + 1, (String) a);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String sender = rs.getString(4);
                    if ("ROOM".equals(rs.getString(2))) {
                        out.add(new SearchHit(rs.getLong(1), TargetType.ROOM, rs.getString(3), sender,
                                rs.getLong(6), rs.getString(7)));
                    } else {
                        String other = sender.equals(user) ? rs.getString(5) : sender;
                        out.add(new SearchHit(rs.getLong(1), TargetType.PRIVATE, other, sender,
                                rs.getLong(6), rs.getString(7)));
                    }
                }
            }
        }
        return out;
    }

    /** Makes %, _ and \ literal for LIKE ... ESCAPE '\'. */
    static String escapeLike(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    static long newestRoomMessageId(Connection c, long roomId) throws SQLException {
        return scalarLong(c, "SELECT COALESCE(MAX(id), 0) FROM messages WHERE room_id = ?", roomId);
    }

    private static long scalarLong(Connection c, String sql, long... params) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) ps.setLong(i + 1, params[i]);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        }
    }
}
