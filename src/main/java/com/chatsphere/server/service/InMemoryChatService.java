package com.chatsphere.server.service;

import com.chatsphere.common.model.*;
import com.chatsphere.common.protocol.*;
import java.util.*;

/**
 * Reference implementation held in RAM. Passwords are stored in clear text: DEV ONLY.
 * It defines the exact behavior that the SQLite implementation (ChatServiceImpl) must match.
 */
public class InMemoryChatService implements ChatService {

    private static final class Room {
        final Set<String> members = new LinkedHashSet<>();
        final Map<String, Long> lastRead = new HashMap<>();
    }
    private static final class Msg {
        final long id; final TargetType type; final String room; final String sender; final String recipient;
        final long ts; DeliveryStatus status; final String text;
        Msg(long id, TargetType type, String room, String sender, String recipient, long ts, String text) {
            this.id = id; this.type = type; this.room = room; this.sender = sender; this.recipient = recipient;
            this.ts = ts; this.status = DeliveryStatus.SENT; this.text = text;
        }
    }

    private final Map<String, String> users = new TreeMap<>();
    private final Map<String, Room> rooms = new TreeMap<>();
    private final List<Msg> msgs = new ArrayList<>();
    private final Map<Long, byte[]> attachments = new HashMap<>();
    private long nextId = 1;

    // ---------- helpers ----------
    private void needUser(String u) { if (!users.containsKey(u)) throw new ChatException(ErrorCode.NO_SUCH_USER); }
    private Room needRoom(String n) {
        Room r = rooms.get(n);
        if (r == null) throw new ChatException(ErrorCode.NO_SUCH_ROOM);
        return r;
    }
    private Room needMemberRoom(String user, String name) {
        Room r = needRoom(name);
        if (!r.members.contains(user)) throw new ChatException(ErrorCode.NOT_MEMBER);
        return r;
    }
    private long maxRoomId(String room) {
        long m = 0;
        for (Msg x : msgs) if (x.type == TargetType.ROOM && x.room.equals(room)) m = x.id;
        return m;
    }
    private MessageRecord rec(Msg m) {
        byte[] data = attachments.get(m.id);
        return new MessageRecord(m.id, m.sender, m.ts,
                m.type == TargetType.ROOM ? DeliveryStatus.SENT : m.status, m.text, data == null ? null : data.length);
    }
    private boolean between(Msg m, String a, String b) {
        return m.type == TargetType.PRIVATE
                && ((m.sender.equals(a) && m.recipient.equals(b)) || (m.sender.equals(b) && m.recipient.equals(a)));
    }
    private List<Msg> conversation(String user, TargetType type, String target) {
        List<Msg> out = new ArrayList<>();
        if (type == TargetType.ROOM) {
            needMemberRoom(user, target);
            for (Msg m : msgs) if (m.type == TargetType.ROOM && m.room.equals(target)) out.add(m);
        } else {
            needUser(target);
            for (Msg m : msgs) if (between(m, user, target)) out.add(m);
        }
        return out;
    }
    private long firstUnread(String user, TargetType type, String target, List<Msg> conv) {
        if (type == TargetType.ROOM) {
            long lastRead = rooms.get(target).lastRead.getOrDefault(user, 0L);
            for (Msg m : conv) if (m.id > lastRead && !m.sender.equals(user)) return m.id;
        } else {
            for (Msg m : conv) if (m.sender.equals(target) && m.status != DeliveryStatus.READ) return m.id;
        }
        return 0;
    }

    // ---------- accounts ----------
    @Override public synchronized void register(String username, String password) {
        if (!Validation.isValidUsername(username)) throw new ChatException(ErrorCode.INVALID_USERNAME);
        if (!Validation.isValidPassword(password)) throw new ChatException(ErrorCode.INVALID_PASSWORD);
        if (users.containsKey(username)) throw new ChatException(ErrorCode.USERNAME_TAKEN);
        users.put(username, password);
    }
    @Override public synchronized void verifyLogin(String username, String password) {
        if (username == null || password == null) throw new ChatException(ErrorCode.BAD_CREDENTIALS);
        String p = users.get(username);
        if (p == null || !p.equals(password)) throw new ChatException(ErrorCode.BAD_CREDENTIALS);
    }

    // ---------- rooms ----------
    @Override public synchronized void createRoom(String user, String room) {
        if (!Validation.isValidRoomName(room)) throw new ChatException(ErrorCode.INVALID_ROOM_NAME);
        if (rooms.containsKey(room)) throw new ChatException(ErrorCode.ROOM_EXISTS);
        needUser(user);
        Room r = new Room();
        r.members.add(user);
        r.lastRead.put(user, 0L);
        rooms.put(room, r);
    }
    @Override public synchronized void joinRoom(String user, String room) {
        Room r = needRoom(room);
        needUser(user);
        if (r.members.add(user)) r.lastRead.put(user, maxRoomId(room));
    }
    @Override public synchronized void leaveRoom(String user, String room) {
        Room r = needRoom(room);
        r.members.remove(user);
        r.lastRead.remove(user);
    }
    @Override public synchronized List<RoomSummary> listRooms(String user, boolean onlyMine) {
        List<RoomSummary> out = new ArrayList<>();
        for (Map.Entry<String, Room> e : rooms.entrySet()) {
            String name = e.getKey();
            Room r = e.getValue();
            boolean joined = r.members.contains(user);
            if (onlyMine && !joined) continue;
            int unread = 0;
            String lastText = "";
            long lastTs = 0;
            long lastRead = r.lastRead.getOrDefault(user, 0L);
            for (Msg m : msgs) {
                if (m.type != TargetType.ROOM || !m.room.equals(name)) continue;
                if (joined && m.id > lastRead && !m.sender.equals(user)) unread++;
                lastText = Validation.preview(m.text);
                lastTs = m.ts;
            }
            out.add(new RoomSummary(name, r.members.size(), joined, unread, lastText, lastTs));
        }
        return out;
    }
    @Override public synchronized List<String> roomMembers(String user, String room) {
        return new ArrayList<>(new TreeSet<>(needMemberRoom(user, room).members));
    }
    @Override public synchronized List<String> roomMembersForFanout(String room) {
        Room r = rooms.get(room);
        return r == null ? List.of() : new ArrayList<>(r.members);
    }

    // ---------- users ----------
    @Override public synchronized List<UserSummary> listUsers(String user) {
        List<UserSummary> out = new ArrayList<>();
        for (String u : users.keySet()) {
            if (u.equals(user)) continue;
            int unread = 0;
            String lastText = "";
            long lastTs = 0;
            for (Msg m : msgs) {
                if (!between(m, user, u)) continue;
                if (m.sender.equals(u) && m.status != DeliveryStatus.READ) unread++;
                lastText = Validation.preview(m.text);
                lastTs = m.ts;
            }
            out.add(new UserSummary(u, false, unread, lastText, lastTs));
        }
        return out;
    }

    // ---------- messaging ----------
    @Override public synchronized MessageRecord saveRoomMessage(String sender, String room, String text) {
        if (!Validation.isValidMessage(text)) throw new ChatException(ErrorCode.INVALID_MESSAGE);
        needMemberRoom(sender, room);
        Msg m = new Msg(nextId++, TargetType.ROOM, room, sender, null, System.currentTimeMillis(), text);
        msgs.add(m);
        return rec(m);
    }
    @Override public synchronized MessageRecord savePrivateMessage(String sender, String recipient, String text) {
        if (!Validation.isValidMessage(text)) throw new ChatException(ErrorCode.INVALID_MESSAGE);
        if (sender != null && sender.equals(recipient)) throw new ChatException(ErrorCode.INVALID_TARGET);
        needUser(sender);
        needUser(recipient);
        Msg m = new Msg(nextId++, TargetType.PRIVATE, null, sender, recipient, System.currentTimeMillis(), text);
        msgs.add(m);
        return rec(m);
    }
    @Override
    public synchronized MessageRecord saveAttachment(String sender, TargetType type, String target, String filename,
                                                     byte[] data) {
        Validation.requireAttachmentName(filename);
        Validation.requireAttachmentSize(data.length);
        MessageRecord message = type == TargetType.ROOM
                ? saveRoomMessage(sender, target, filename) : savePrivateMessage(sender, target, filename);
        attachments.put(message.id(), data.clone());
        return new MessageRecord(message.id(), sender, message.timestamp(), message.status(), filename, data.length);
    }

    @Override
    public synchronized byte[] attachmentChunk(String requester, long messageId, int offset) {
        byte[] data = attachments.get(messageId);
        if (data != null && offset >= 0 && offset <= data.length) {
            for (Msg m : msgs) {
                if (m.id != messageId) continue;
                boolean allowed = m.type == TargetType.ROOM ? rooms.get(m.room).members.contains(requester)
                        : m.sender.equals(requester) || m.recipient.equals(requester);
                if (allowed) return Arrays.copyOfRange(data, offset, Math.min(offset + Limits.ATTACHMENT_CHUNK_BYTES, data.length));
                break;
            }
        }
        throw new ChatException(ErrorCode.ATTACHMENT_UNAVAILABLE);
    }

    @Override public synchronized void markDelivered(List<Long> ids) {
        if (ids == null || ids.isEmpty()) return;
        Set<Long> set = new HashSet<>(ids);
        for (Msg m : msgs)
            if (m.type == TargetType.PRIVATE && set.contains(m.id) && m.status == DeliveryStatus.SENT)
                m.status = DeliveryStatus.DELIVERED;
    }
    @Override public synchronized Map<String, List<Long>> markAllDeliveredFor(String recipient) {
        Map<String, List<Long>> out = new LinkedHashMap<>();
        for (Msg m : msgs) {
            if (m.type == TargetType.PRIVATE && m.recipient.equals(recipient) && m.status == DeliveryStatus.SENT) {
                m.status = DeliveryStatus.DELIVERED;
                out.computeIfAbsent(m.sender, k -> new ArrayList<>()).add(m.id);
            }
        }
        return out;
    }
    @Override public synchronized List<Long> markPrivateRead(String reader, String peer, long upToId) {
        needUser(peer);
        if (!users.containsKey(reader)) return List.of();
        List<Long> changed = new ArrayList<>();
        for (Msg m : msgs) {
            if (m.type == TargetType.PRIVATE && m.sender.equals(peer) && m.recipient.equals(reader)
                    && m.id <= upToId && m.status != DeliveryStatus.READ) {
                m.status = DeliveryStatus.READ;
                changed.add(m.id);
            }
        }
        return changed;
    }
    @Override public synchronized void markRoomRead(String user, String room, long upToId) {
        Room r = needMemberRoom(user, room);
        long newest = 0;
        for (Msg m : msgs) {
            if (m.type == TargetType.ROOM && room.equals(m.room) && m.id > newest) {
                newest = m.id;
            }
        }
        long target = Math.min(upToId, newest);
        r.lastRead.merge(user, target, Math::max);
    }

    // ---------- history & search ----------
    @Override public synchronized HistoryPage history(String user, TargetType type, String target, long beforeId, int limit) {
        int lim = limit <= 0 ? Limits.HISTORY_DEFAULT : Math.min(limit, Limits.HISTORY_MAX);
        List<Msg> conv = conversation(user, type, target);
        List<Msg> eligible = new ArrayList<>();
        for (Msg m : conv) if (beforeId == 0 || m.id < beforeId) eligible.add(m);
        boolean hasMore = eligible.size() > lim;
        List<Msg> page = eligible.subList(Math.max(0, eligible.size() - lim), eligible.size());
        List<MessageRecord> out = new ArrayList<>();
        for (Msg m : page) out.add(rec(m));
        return new HistoryPage(out, hasMore, beforeId == 0 ? firstUnread(user, type, target, conv) : 0);
    }
    @Override public synchronized HistoryPage historyAround(String user, TargetType type, String target, long messageId) {
        List<Msg> conv = conversation(user, type, target);
        int idx = -1;
        for (int i = 0; i < conv.size(); i++) if (conv.get(i).id == messageId) { idx = i; break; }
        if (idx < 0) throw new ChatException(ErrorCode.BAD_REQUEST, "message not in conversation");
        int from = Math.max(0, idx - Limits.AROUND_WINDOW);
        int to = Math.min(conv.size(), idx + Limits.AROUND_WINDOW + 1);
        List<MessageRecord> out = new ArrayList<>();
        for (Msg m : conv.subList(from, to)) out.add(rec(m));
        return new HistoryPage(out, from > 0, firstUnread(user, type, target, conv));
    }
    @Override public synchronized List<SearchHit> search(String user, SearchQuery q) {
        if (q == null || q.keyword() == null || q.keyword().isBlank()) throw new ChatException(ErrorCode.BAD_REQUEST);
        if (q.currentOnly() && (q.targetType() == null || q.target() == null || q.target().isBlank())) {
            throw new ChatException(ErrorCode.BAD_REQUEST);
        }
        String kw = q.keyword().trim().toLowerCase();
        List<SearchHit> hits = new ArrayList<>();
        for (int i = msgs.size() - 1; i >= 0 && hits.size() < Limits.SEARCH_MAX_RESULTS; i--) {
            Msg m = msgs.get(i);
            String target;
            if (m.type == TargetType.ROOM) {
                Room r = rooms.get(m.room);
                if (r == null || !r.members.contains(user)) continue;
                target = m.room;
            } else {
                if (!m.sender.equals(user) && !m.recipient.equals(user)) continue;
                target = m.sender.equals(user) ? m.recipient : m.sender;
            }
            if (q.currentOnly() && (m.type != q.targetType() || !target.equals(q.target()))) continue;
            if (q.sender() != null && !q.sender().isBlank() && !m.sender.equals(q.sender())) continue;
            if (q.fromTs() > 0 && m.ts < q.fromTs()) continue;
            if (q.toTs() > 0 && m.ts > q.toTs()) continue;
            if (!m.text.toLowerCase().contains(kw)) continue;
            hits.add(new SearchHit(m.id, m.type, target, m.sender, m.ts, m.text));
        }
        return hits;
    }
}
