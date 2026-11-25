package com.chatsphere.server.service;

import com.chatsphere.common.model.*;
import java.util.List;
import java.util.Map;

/**
 * The boundary between Person 1 (networking) and Person 2 (data + rules).
 * Rules for EVERY method:
 *  - All input validation happens here (use common.protocol.Validation). Callers only check frame arity.
 *  - Business errors are thrown as ChatException(ErrorCode). Infrastructure failures may throw any RuntimeException.
 *  - Thread-safe: may be called concurrently from many connection threads.
 *  - Never touches sockets or online/offline state. "online" is owned by the server's session registry.
 */
public interface ChatService {

    // ---------- accounts ----------
    /** INVALID_USERNAME, INVALID_PASSWORD, USERNAME_TAKEN. Stores a salted hash, never the password. */
    void register(String username, String password);
    /** BAD_CREDENTIALS for unknown user AND for wrong password (never reveal which). */
    void verifyLogin(String username, String password);

    // ---------- rooms ----------
    /** INVALID_ROOM_NAME, ROOM_EXISTS. Creator becomes a member with lastRead = 0. */
    void createRoom(String user, String room);
    /** NO_SUCH_ROOM. Idempotent. A new member's lastRead = newest message id in the room (older history is not "unread"). */
    void joinRoom(String user, String room);
    /** NO_SUCH_ROOM. Idempotent (leaving a room you are not in is OK). Room and its messages are kept. */
    void leaveRoom(String user, String room);
    /** onlyMine=true: rooms the user joined. false: all rooms. Order: by name ascending. */
    List<RoomSummary> listRooms(String user, boolean onlyMine);
    /** Usernames of members. NO_SUCH_ROOM, NOT_MEMBER (caller must be a member). Order: by name ascending. */
    List<String> roomMembers(String user, String room);
    /** Same as roomMembers but with NO membership check; returns an empty list for an unknown room. Used for fan-out. */
    List<String> roomMembersForFanout(String room);

    // ---------- users ----------
    /** Every registered user except `user`, order by username ascending, online always false. */
    List<UserSummary> listUsers(String user);

    // ---------- messaging ----------
    /** INVALID_MESSAGE, NO_SUCH_ROOM, NOT_MEMBER. Returns the stored record (status SENT, new id, timestamp = now). */
    MessageRecord saveRoomMessage(String sender, String room, String text);
    /** INVALID_MESSAGE, INVALID_TARGET (to self), NO_SUCH_USER. Stored with status SENT. */
    MessageRecord savePrivateMessage(String sender, String recipient, String text);
    MessageRecord saveAttachment(String sender, TargetType type, String target, String filename, byte[] data);
    /** Returns a bounded slice when the requester may view the message. */
    byte[] attachmentChunk(String requester, long messageId, int offset);
    /** SENT -> DELIVERED for these private message ids. Ignores ids that are not SENT. */
    void markDelivered(List<Long> messageIds);
    /** On login: every SENT private message addressed to `recipient` becomes DELIVERED. Returns senderName -> ids changed. */
    Map<String, List<Long>> markAllDeliveredFor(String recipient);
    /** Messages peer->reader with id <= upToId and status != READ become READ. Returns the ids that changed. */
    List<Long> markPrivateRead(String reader, String peer, long upToId);
    /** Moves the room read marker forward (never backward). NO_SUCH_ROOM, NOT_MEMBER. */
    void markRoomRead(String user, String room, long upToId);

    // ---------- history & search ----------
    /**
     * beforeId == 0: newest `limit` messages. beforeId > 0: newest `limit` messages with id < beforeId.
     * limit <= 0 -> Limits.HISTORY_DEFAULT; limit > HISTORY_MAX -> HISTORY_MAX. Result ascending by id.
     * ROOM requires membership (NOT_MEMBER). PRIVATE target must exist (NO_SUCH_USER).
     * firstUnreadId is computed only when beforeId == 0, otherwise 0.
     */
    HistoryPage history(String user, TargetType type, String target, long beforeId, int limit);
    /** Window of up to AROUND_WINDOW messages before and after messageId, plus the message. BAD_REQUEST if not in this conversation. */
    HistoryPage historyAround(String user, TargetType type, String target, long messageId);
    /**
     * Only searches rooms the user is a member of and private chats the user takes part in.
     * Blank keyword -> BAD_REQUEST. Newest first, at most SEARCH_MAX_RESULTS. Case-insensitive substring match.
     */
    List<SearchHit> search(String user, SearchQuery query);
}
