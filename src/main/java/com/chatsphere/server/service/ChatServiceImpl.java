package com.chatsphere.server.service;

import com.chatsphere.common.model.*;
import com.chatsphere.common.protocol.ChatException;
import com.chatsphere.common.protocol.ErrorCode;
import com.chatsphere.common.protocol.Limits;
import com.chatsphere.common.protocol.Validation;
import com.chatsphere.server.db.Database;
import com.chatsphere.server.db.MessageDAO;
import com.chatsphere.server.db.RoomDAO;
import com.chatsphere.server.db.UserDAO;
import com.chatsphere.server.security.PasswordHasher;

import java.util.List;
import java.util.Map;

/**
 * SQLite/JDBC implementation of {@link ChatService}. Behaves exactly like {@link InMemoryChatService}
 * (same error codes, same order of checks) but persists everything.
 *
 * <p>Thread safety: one shared connection, every public method that touches it is synchronized.
 * Password hashing (slow on purpose) runs outside the lock.
 * Every statement is a PreparedStatement; user input is never concatenated into SQL.
 */
public class ChatServiceImpl implements ChatService, AutoCloseable {

    private final Database db;

    public ChatServiceImpl(String dbFilePath) {
        this.db = new Database(dbFilePath);
    }

    /** Not part of ChatService: lets tests and ServerMain release the file. */
    @Override
    public synchronized void close() {
        db.close();
    }

    // =====================================================================================
    // accounts
    // =====================================================================================

    @Override
    public void register(String username, String password) {
        if (!Validation.isValidUsername(username)) throw new ChatException(ErrorCode.INVALID_USERNAME);
        if (!Validation.isValidPassword(password)) throw new ChatException(ErrorCode.INVALID_PASSWORD);
        String hash = PasswordHasher.hash(password); // slow: outside the lock
        insertUser(username, hash);
    }

    private synchronized void insertUser(String username, String hash) {
        db.inTransaction(c -> {
            UserDAO.insertUser(c, username, hash);
            return null;
        });
    }

    @Override
    public void verifyLogin(String username, String password) {
        String stored = findHash(username);
        if (stored == null || password == null) {
            PasswordHasher.dummyVerify(); // same cost whether or not the user exists
            throw new ChatException(ErrorCode.BAD_CREDENTIALS);
        }
        if (!PasswordHasher.verify(password, stored)) throw new ChatException(ErrorCode.BAD_CREDENTIALS);
    }

    private synchronized String findHash(String username) {
        return db.inTransaction(c -> UserDAO.findHash(c, username));
    }

    // =====================================================================================
    // rooms
    // =====================================================================================

    @Override
    public synchronized void createRoom(String user, String room) {
        if (!Validation.isValidRoomName(room)) throw new ChatException(ErrorCode.INVALID_ROOM_NAME);
        db.inTransaction(c -> {
            RoomDAO.createRoom(c, user, room);
            return null;
        });
    }

    @Override
    public synchronized void joinRoom(String user, String room) {
        db.inTransaction(c -> {
            RoomDAO.joinRoom(c, user, room);
            return null;
        });
    }

    @Override
    public synchronized void leaveRoom(String user, String room) {
        db.inTransaction(c -> {
            RoomDAO.leaveRoom(c, user, room);
            return null;
        });
    }

    @Override
    public synchronized List<RoomSummary> listRooms(String user, boolean onlyMine) {
        return db.inTransaction(c -> RoomDAO.listRooms(c, user, onlyMine));
    }

    @Override
    public synchronized List<String> roomMembers(String user, String room) {
        return db.inTransaction(c -> RoomDAO.roomMembers(c, user, room));
    }

    @Override
    public synchronized List<String> roomMembersForFanout(String room) {
        return db.inTransaction(c -> RoomDAO.roomMembersForFanout(c, room));
    }

    // =====================================================================================
    // users
    // =====================================================================================

    @Override
    public synchronized List<UserSummary> listUsers(String user) {
        return db.inTransaction(c -> UserDAO.listUsers(c, user));
    }

    // =====================================================================================
    // messaging
    // =====================================================================================

    @Override
    public synchronized MessageRecord saveRoomMessage(String sender, String room, String text) {
        if (!Validation.isValidMessage(text)) throw new ChatException(ErrorCode.INVALID_MESSAGE);
        return saveMessage(sender, TargetType.ROOM, room, text, null);
    }

    @Override
    public synchronized MessageRecord savePrivateMessage(String sender, String recipient, String text) {
        if (!Validation.isValidMessage(text)) throw new ChatException(ErrorCode.INVALID_MESSAGE);
        return saveMessage(sender, TargetType.PRIVATE, recipient, text, null);
    }

    @Override
    public synchronized MessageRecord saveAttachment(String sender, TargetType type, String target, String filename,
                                                     byte[] data) {
        Validation.requireAttachmentName(filename);
        Validation.requireAttachmentSize(data.length);
        return saveMessage(sender, type, target, filename, data);
    }

    private MessageRecord saveMessage(String sender, TargetType type, String target, String text, byte[] data) {
        if (type == TargetType.PRIVATE && sender != null && sender.equals(target)) throw new ChatException(ErrorCode.INVALID_TARGET);
        return db.inTransaction(c -> {
            Long roomId = null;
            Long recipientId = null;
            if (type == TargetType.ROOM) {
                roomId = RoomDAO.requireRoom(c, target);
                if (RoomDAO.marker(c, roomId, sender) == null) throw new ChatException(ErrorCode.NOT_MEMBER);
            } else {
                recipientId = UserDAO.requireUser(c, target);
            }
            long senderId = UserDAO.requireUser(c, sender);
            long now = System.currentTimeMillis();
            long id = MessageDAO.insertMessage(c, type.name(), roomId, senderId, recipientId, text, now);
            if (data != null) MessageDAO.insertAttachment(c, id, data);
            return new MessageRecord(id, sender, now, DeliveryStatus.SENT, text, data == null ? null : data.length);
        });
    }

    @Override
    public synchronized byte[] attachmentChunk(String requester, long messageId, int offset) {
        if (messageId < 1 || offset < 0) throw new ChatException(ErrorCode.ATTACHMENT_UNAVAILABLE);
        return db.inTransaction(c -> {
            long requesterId = UserDAO.requireUser(c, requester);
            byte[] slice = MessageDAO.attachmentChunk(c, requesterId, messageId, offset);
            if (slice == null) throw new ChatException(ErrorCode.ATTACHMENT_UNAVAILABLE);
            return slice;
        });
    }

    @Override
    public synchronized void markDelivered(List<Long> messageIds) {
        if (messageIds == null || messageIds.isEmpty()) return;
        db.inTransaction(c -> {
            MessageDAO.markDelivered(c, messageIds);
            return null;
        });
    }

    @Override
    public synchronized Map<String, List<Long>> markAllDeliveredFor(String recipient) {
        return db.inTransaction(c -> MessageDAO.markAllDeliveredFor(c, recipient));
    }

    @Override
    public synchronized List<Long> markPrivateRead(String reader, String peer, long upToId) {
        return db.inTransaction(c -> MessageDAO.markPrivateRead(c, reader, peer, upToId));
    }

    @Override
    public synchronized void markRoomRead(String user, String room, long upToId) {
        db.inTransaction(c -> {
            RoomDAO.markRoomRead(c, user, room, upToId);
            return null;
        });
    }

    // =====================================================================================
    // history
    // =====================================================================================

    @Override
    public synchronized HistoryPage history(String user, TargetType type, String target, long beforeId, int limit) {
        int lim = limit <= 0 ? Limits.HISTORY_DEFAULT : Math.min(limit, Limits.HISTORY_MAX);
        return db.inTransaction(c -> MessageDAO.history(c, user, type, target, beforeId, lim));
    }

    @Override
    public synchronized HistoryPage historyAround(String user, TargetType type, String target, long messageId) {
        final int w = Limits.AROUND_WINDOW;
        return db.inTransaction(c -> MessageDAO.historyAround(c, user, type, target, messageId, w));
    }

    // =====================================================================================
    // search
    // =====================================================================================

    @Override
    public synchronized List<SearchHit> search(String user, SearchQuery q) {
        if (q == null || q.keyword() == null || q.keyword().isBlank()) throw new ChatException(ErrorCode.BAD_REQUEST);
        if (q.currentOnly() && (q.targetType() == null || q.target() == null || q.target().isBlank())) {
            throw new ChatException(ErrorCode.BAD_REQUEST);
        }
        return db.inTransaction(c -> MessageDAO.search(c, user, q));
    }

}
