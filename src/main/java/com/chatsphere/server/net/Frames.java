package com.chatsphere.server.net;

import com.chatsphere.common.model.*;
import com.chatsphere.common.protocol.Codec;
import com.chatsphere.common.protocol.ErrorCode;
import com.chatsphere.common.protocol.Protocol;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * The ONLY place where the server builds frames. Every method goes through Codec.encode and the Protocol constants,
 * so escaping is always right and no TAB is ever typed by hand. Returned strings have no trailing newline
 * (the writer thread appends it).
 */
public final class Frames {
    private Frames() {}

    // ---------------------------------------------------------------- replies
    public static String ok(String reqId) { return Codec.encode(Protocol.OK, reqId); }

    public static String error(String reqId, ErrorCode code) { return Codec.encode(Protocol.ERROR, reqId, code.name()); }

    public static String loginOk(String reqId, String username) { return Codec.encode(Protocol.LOGIN_OK, reqId, username); }

    public static String pong(String reqId) { return Codec.encode(Protocol.PONG, reqId); }

    public static String sent(String reqId, long messageId, long timestamp) {
        return Codec.encode(Protocol.SENT, reqId, Codec.str(messageId), Codec.str(timestamp));
    }

    public static String fileData(String reqId, byte[] data) {
        return Codec.encode(Protocol.FILE_DATA, reqId, Base64.getEncoder().encodeToString(data));
    }

    public static String roomListResult(String reqId, String scope, List<RoomSummary> rooms) {
        List<String> f = new ArrayList<>();
        f.add(reqId);
        f.add(scope);
        f.add(Codec.str(rooms.size()));
        for (RoomSummary r : rooms) {
            f.add(r.name());
            f.add(Codec.str(r.memberCount()));
            f.add(Codec.str(r.joined()));
            f.add(Codec.str(r.unread()));
            f.add(r.lastText());
            f.add(Codec.str(r.lastTimestamp()));
        }
        return Codec.encode(Protocol.ROOM_LIST_RESULT, f);
    }

    public static String roomMembersResult(String reqId, String room, List<MemberInfo> members) {
        List<String> f = new ArrayList<>();
        f.add(reqId);
        f.add(room);
        f.add(Codec.str(members.size()));
        for (MemberInfo m : members) {
            f.add(m.username());
            f.add(Codec.str(m.online()));
        }
        return Codec.encode(Protocol.ROOM_MEMBERS_RESULT, f);
    }

    public static String userListResult(String reqId, List<UserSummary> users) {
        List<String> f = new ArrayList<>();
        f.add(reqId);
        f.add(Codec.str(users.size()));
        for (UserSummary u : users) {
            f.add(u.username());
            f.add(Codec.str(u.online()));
            f.add(Codec.str(u.unread()));
            f.add(u.lastText());
            f.add(Codec.str(u.lastTimestamp()));
        }
        return Codec.encode(Protocol.USER_LIST_RESULT, f);
    }

    public static String historyResult(String reqId, TargetType type, String target, HistoryPage page) {
        List<String> f = new ArrayList<>();
        f.add(reqId);
        f.add(type.name());
        f.add(target);
        f.add(Codec.str(page.hasMore()));
        f.add(Codec.str(page.firstUnreadId()));
        f.add(Codec.str(page.messages().size()));
        for (MessageRecord m : page.messages()) {
            f.add(Codec.str(m.id()));
            f.add(m.sender());
            f.add(Codec.str(m.timestamp()));
            f.add(m.status().name());
            f.add(m.text());
        }
        // Optional trailer: attachment message IDs and their byte lengths.
        for (MessageRecord m : page.messages()) {
            if (m.attachmentSize() != null) {
                f.add(Codec.str(m.id()));
                f.add(Codec.str(m.attachmentSize()));
            }
        }
        return Codec.encode(Protocol.HISTORY_RESULT, f);
    }

    public static String searchResult(String reqId, List<SearchHit> hits) {
        List<String> f = new ArrayList<>();
        f.add(reqId);
        f.add(Codec.str(hits.size()));
        for (SearchHit h : hits) {
            f.add(Codec.str(h.id()));
            f.add(h.targetType().name());
            f.add(h.target());
            f.add(h.sender());
            f.add(Codec.str(h.timestamp()));
            f.add(h.text());
        }
        return Codec.encode(Protocol.SEARCH_RESULT, f);
    }

    // ---------------------------------------------------------------- pushes
    public static String msgPush(String room, MessageRecord m) {
        return messagePush(Protocol.MSG_PUSH, List.of(room, Codec.str(m.id()), m.sender(), Codec.str(m.timestamp()), m.text()), m);
    }

    public static String pmPush(MessageRecord m) {
        return messagePush(Protocol.PM_PUSH, List.of(Codec.str(m.id()), m.sender(), Codec.str(m.timestamp()), m.text()), m);
    }

    private static String messagePush(String command, List<String> fields, MessageRecord message) {
        if (message.attachmentSize() == null) return Codec.encode(command, fields);
        List<String> withSize = new ArrayList<>(fields);
        withSize.add(Codec.str(message.attachmentSize()));
        return Codec.encode(command, withSize);
    }

    public static String userStatus(String username, boolean online) {
        return Codec.encode(Protocol.USER_STATUS, username, Codec.str(online));
    }

    public static String typingPush(TargetType type, String target, String user) {
        return Codec.encode(Protocol.TYPING_PUSH, type.name(), target, user);
    }

    /** status is DELIVERED or READ; peer is the other user (from the receiver's point of view). */
    public static String ackPush(DeliveryStatus status, String peer, List<Long> ids) {
        List<String> f = new ArrayList<>();
        f.add(status.name());
        f.add(peer);
        f.add(Codec.str(ids.size()));
        for (long id : ids) f.add(Codec.str(id));
        return Codec.encode(Protocol.ACK_PUSH, f);
    }

    public static String roomMembersChanged(String room, String user, boolean joined) {
        return Codec.encode(Protocol.ROOM_MEMBERS_CHANGED, room, user, joined ? Protocol.JOINED : Protocol.LEFT);
    }

    public static String sessionReplaced() { return Codec.encode(Protocol.SESSION_REPLACED); }
}
