package com.chatsphere.client.net;

import com.chatsphere.common.model.*;
import com.chatsphere.common.protocol.ChatException;
import com.chatsphere.common.protocol.Codec;
import com.chatsphere.common.protocol.ErrorCode;
import com.chatsphere.common.protocol.Protocol;
import com.chatsphere.common.protocol.Limits;
import com.chatsphere.common.protocol.Validation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.List;

/**
 * Pure functions that turn decoded server frames (the List&lt;String&gt; from Codec.decode, field 0 = frame name) into
 * Java objects. No sockets, no threads, no state: easy to test against the golden frames.
 *
 * A malformed frame (wrong field count, bad number, unknown enum value) makes the method throw
 * ChatException(BAD_REQUEST). It never throws anything else (no IndexOutOfBounds / NumberFormat).
 */
public final class ProtocolParser {
    private ProtocolParser() {}

    public static final String SESSION_REPLACED_REASON = "Your account was signed in somewhere else.";

    // =================================================================== pushes
    /**
     * Converts a push frame into a ServerEvent. Returns null for frames that are not pushes (replies to requests).
     * ERROR with reqId 0 is an unsolicited error and becomes ServerError; ERROR with any other reqId is a reply (null).
     */
    public static ServerEvent parsePush(List<String> f) {
        switch (f.get(0)) {
            case Protocol.MSG_PUSH -> {
                Integer size = attachmentSize(f, 6);
                return new ServerEvent.RoomMessage(f.get(1),
                        new MessageRecord(Codec.toLong(f.get(2)), f.get(3), Codec.toLong(f.get(4)),
                                DeliveryStatus.SENT, f.get(5), size));
            }
            case Protocol.PM_PUSH -> {
                Integer size = attachmentSize(f, 5);
                return new ServerEvent.PrivateMessage(f.get(2),
                        new MessageRecord(Codec.toLong(f.get(1)), f.get(2), Codec.toLong(f.get(3)),
                                DeliveryStatus.DELIVERED, f.get(4), size));
            }
            case Protocol.USER_STATUS -> {
                Codec.requireSize(f, 3);
                return new ServerEvent.UserStatus(f.get(1), Codec.toBool(f.get(2)));
            }
            case Protocol.TYPING_PUSH -> {
                Codec.requireSize(f, 4);
                return new ServerEvent.Typing(Codec.toEnum(TargetType.class, f.get(1)), f.get(2), f.get(3));
            }
            case Protocol.ACK_PUSH -> {
                if (f.size() < 4) throw bad("ACK_PUSH too short");
                int count = count(f.get(3));
                Codec.requireSize(f, 4 + count);
                DeliveryStatus status = Codec.toEnum(DeliveryStatus.class, f.get(1));
                if (status != DeliveryStatus.DELIVERED && status != DeliveryStatus.READ) throw bad("ACK_PUSH status");
                List<Long> ids = new ArrayList<>(count);
                for (int i = 0; i < count; i++) ids.add(Codec.toLong(f.get(4 + i)));
                return new ServerEvent.StatusUpdate(status, f.get(2), List.copyOf(ids));
            }
            case Protocol.ROOM_MEMBERS_CHANGED -> {
                Codec.requireSize(f, 4);
                String action = f.get(3);
                if (!action.equals(Protocol.JOINED) && !action.equals(Protocol.LEFT)) throw bad("action");
                return new ServerEvent.RoomMembersChanged(f.get(1), f.get(2), action.equals(Protocol.JOINED));
            }
            case Protocol.SESSION_REPLACED -> {
                Codec.requireSize(f, 1);
                return new ServerEvent.SessionEnded(SESSION_REPLACED_REASON);
            }
            case Protocol.ERROR -> {
                if (f.size() >= 2 && f.get(1).equals("0")) {
                    Codec.requireSize(f, 3);
                    return new ServerEvent.ServerError(ErrorCode.fromWire(f.get(2)));
                }
                return null;
            }
            default -> {
                return null;
            }
        }
    }

    // =================================================================== replies
    /** SENT reqId messageId timestamp */
    public static SentAck parseSent(List<String> f) {
        expect(f, Protocol.SENT);
        Codec.requireSize(f, 4);
        return new SentAck(Codec.toLong(f.get(2)), Codec.toLong(f.get(3)));
    }

    /** ROOM_LIST_RESULT reqId scope count then count x [name, memberCount, joined, unread, lastText, lastTs] */
    public static List<RoomSummary> parseRoomList(List<String> f) {
        expect(f, Protocol.ROOM_LIST_RESULT);
        int n = records(f, 3, 4, 6);
        List<RoomSummary> out = new ArrayList<>(n);
        for (int i = 0, p = 4; i < n; i++, p += 6) {
            out.add(new RoomSummary(f.get(p), Codec.toInt(f.get(p + 1)), Codec.toBool(f.get(p + 2)),
                    Codec.toInt(f.get(p + 3)), f.get(p + 4), Codec.toLong(f.get(p + 5))));
        }
        return out;
    }

    /** ROOM_MEMBERS_RESULT reqId room count then count x [username, online] */
    public static List<MemberInfo> parseRoomMembers(List<String> f) {
        expect(f, Protocol.ROOM_MEMBERS_RESULT);
        int n = records(f, 3, 4, 2);
        List<MemberInfo> out = new ArrayList<>(n);
        for (int i = 0, p = 4; i < n; i++, p += 2) out.add(new MemberInfo(f.get(p), Codec.toBool(f.get(p + 1))));
        return out;
    }

    /** USER_LIST_RESULT reqId count then count x [username, online, unread, lastText, lastTs] */
    public static List<UserSummary> parseUserList(List<String> f) {
        expect(f, Protocol.USER_LIST_RESULT);
        int n = records(f, 2, 3, 5);
        List<UserSummary> out = new ArrayList<>(n);
        for (int i = 0, p = 3; i < n; i++, p += 5) {
            out.add(new UserSummary(f.get(p), Codec.toBool(f.get(p + 1)), Codec.toInt(f.get(p + 2)),
                    f.get(p + 3), Codec.toLong(f.get(p + 4))));
        }
        return out;
    }

    /** HISTORY_RESULT reqId targetType target hasMore firstUnreadId count then count x [id, sender, ts, status, text] */
    public static HistoryPage parseHistory(List<String> f) {
        expect(f, Protocol.HISTORY_RESULT);
        if (f.size() < 7) throw bad("history header");
        int n = count(f.get(6));
        if (n > (f.size() - 7) / 5) throw bad("history count");
        int trailer = 7 + n * 5;
        if ((f.size() - trailer) % 2 != 0 || (f.size() - trailer) / 2 > n) {
            throw bad("history attachment metadata");
        }
        Map<Long, Integer> attachments = new HashMap<>();
        for (int p = trailer; p < f.size(); p += 2) {
            long id = Codec.toLong(f.get(p));
            if (attachments.put(id, parseAttachmentSize(f.get(p + 1))) != null) throw bad("duplicate attachment");
        }
        boolean hasMore = Codec.toBool(f.get(4));
        long firstUnread = Codec.toLong(f.get(5));
        List<MessageRecord> out = new ArrayList<>(n);
        for (int i = 0, p = 7; i < n; i++, p += 5) {
            DeliveryStatus st = Codec.toEnum(DeliveryStatus.class, f.get(p + 3));
            if (st == DeliveryStatus.PENDING || st == DeliveryStatus.FAILED) throw bad("status " + st);   // client-only values
            long id = Codec.toLong(f.get(p));
            String text = f.get(p + 4);
            Integer size = attachments.remove(id);
            if (size != null) Validation.requireAttachmentName(text);
            out.add(new MessageRecord(id, f.get(p + 1), Codec.toLong(f.get(p + 2)), st, text, size));
        }
        if (!attachments.isEmpty()) throw bad("attachment outside history page");
        return new HistoryPage(List.copyOf(out), hasMore, firstUnread);
    }

    /** SEARCH_RESULT reqId count then count x [id, targetType, target, sender, ts, text] */
    public static List<SearchHit> parseSearch(List<String> f) {
        expect(f, Protocol.SEARCH_RESULT);
        int n = records(f, 2, 3, 6);
        List<SearchHit> out = new ArrayList<>(n);
        for (int i = 0, p = 3; i < n; i++, p += 6) {
            out.add(new SearchHit(Codec.toLong(f.get(p)), Codec.toEnum(TargetType.class, f.get(p + 1)), f.get(p + 2),
                    f.get(p + 3), Codec.toLong(f.get(p + 4)), f.get(p + 5)));
        }
        return out;
    }

    private static Integer attachmentSize(List<String> fields, int textFrameSize) {
        if (fields.size() == textFrameSize) return null;
        Codec.requireSize(fields, textFrameSize + 1);
        Validation.requireAttachmentName(fields.get(textFrameSize - 1));
        return parseAttachmentSize(fields.get(textFrameSize));
    }

    private static int parseAttachmentSize(String value) {
        int size = Codec.toInt(value);
        if (size < 0 || size > Limits.ATTACHMENT_MAX_BYTES) throw bad("attachment size");
        return size;
    }

    // =================================================================== helpers
    private static void expect(List<String> f, String name) {
        if (!f.get(0).equals(name)) throw bad("expected " + name + " but got " + f.get(0));
    }

    /**
     * Reads the count field at countIndex and checks the frame has exactly firstRecord + count*stride fields.
     * Checking the size BEFORE looping means a lying count can never cause a huge loop or an out-of-range access.
     */
    private static int records(List<String> f, int countIndex, int firstRecord, int stride) {
        if (f.size() <= countIndex) throw bad("frame too short");
        int n = count(f.get(countIndex));
        if (f.size() != firstRecord + (long) n * stride) throw bad("expected " + (firstRecord + (long) n * stride) + " fields, got " + f.size());
        return n;
    }

    private static int count(String s) {
        int n = Codec.toInt(s);
        if (n < 0) throw bad("negative count");
        return n;
    }

    private static ChatException bad(String detail) { return new ChatException(ErrorCode.BAD_REQUEST, detail); }
}
