package com.chatsphere.client.net;

import com.chatsphere.common.model.*;
import com.chatsphere.common.protocol.ChatException;
import com.chatsphere.common.protocol.ErrorCode;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * The ONLY thing UI code may use to talk to the server. Implementations: SocketChatClient (real), FakeChatClient (dev).
 * Threading: every CompletableFuture completes on the FX thread and every listener runs on the FX thread.
 * Failures: futures complete exceptionally with ChatException (use ex.getCause() when it arrives wrapped in CompletionException).
 * Passwords are char[] so they can be wiped. After a successful login() the implementation keeps credentials in memory
 * for silent re-login and wipes them on logout().
 */
public interface ChatClient {
    // ---------- connection ----------
    /** Async. Progress is reported with ConnectionStateChanged events. */
    void connect(String host, int port);
    void disconnect();
    /** Skip the backoff wait and try now (used by the Retry buttons). */
    void retryNow();
    ConnectionState getConnectionState();
    /** Subscribe to events. Returns a Runnable that unsubscribes. Many listeners are allowed. */
    Runnable addEventListener(java.util.function.Consumer<ServerEvent> listener);

    // ---------- session ----------
    CompletableFuture<Void> register(String username, char[] password);
    CompletableFuture<Void> login(String username, char[] password);
    CompletableFuture<Void> logout();

    // ---------- rooms & users ----------
    CompletableFuture<List<RoomSummary>> listRooms(boolean onlyMine);
    CompletableFuture<Void> createRoom(String room);
    CompletableFuture<Void> joinRoom(String room);
    CompletableFuture<Void> leaveRoom(String room);
    CompletableFuture<List<MemberInfo>> listRoomMembers(String room);
    CompletableFuture<List<UserSummary>> listUsers();

    // ---------- messaging ----------
    CompletableFuture<HistoryPage> loadHistory(TargetType type, String target, long beforeId, int limit);
    CompletableFuture<HistoryPage> loadHistoryAround(TargetType type, String target, long messageId);
    CompletableFuture<SentAck> sendRoomMessage(String room, String text);
    CompletableFuture<SentAck> sendPrivateMessage(String user, String text);
    record AttachmentAck(SentAck message, int size) {}

    default boolean supportsAttachments() { return false; }

    default CompletableFuture<AttachmentAck> sendAttachment(TargetType type, String target, Path file) {
        return CompletableFuture.failedFuture(new ChatException(ErrorCode.UNKNOWN_COMMAND));
    }

    default CompletableFuture<Void> saveAttachment(long messageId, int size, Path destination) {
        return CompletableFuture.failedFuture(new ChatException(ErrorCode.UNKNOWN_COMMAND));
    }

    /** Fire and forget. The caller throttles (max 1 per 2 seconds per conversation). */
    void sendTyping(TargetType type, String target);
    CompletableFuture<Void> markRead(TargetType type, String target, long upToMessageId);

    // ---------- search ----------
    CompletableFuture<List<SearchHit>> search(SearchQuery query);
}
