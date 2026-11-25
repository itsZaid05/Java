package com.chatsphere.client.view.workspace.state;

import com.chatsphere.client.net.ChatClient;
import com.chatsphere.client.view.workspace.state.ChatListItem.*;
import com.chatsphere.common.model.*;
import javafx.animation.PauseTransition;
import javafx.beans.property.*;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.ObservableSet;
import javafx.util.Duration;

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-conversation ViewModel managing history, sending, typing, and status upgrades.
 * Owned by Person 4.
 */
public class ConversationViewModel {
    private static final long GROUP_WINDOW_MS = 5 * 60 * 1000; // 5 minutes
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("d MMM uuuu");

    private final TargetType type;
    private final String target;
    private final ChatClient client;
    private final String currentUser;

    private final ObservableList<ChatListItem> items = FXCollections.observableArrayList();
    private final BooleanProperty loading = new SimpleBooleanProperty(false);
    private final BooleanProperty hasEarlier = new SimpleBooleanProperty(false);
    private final BooleanProperty error = new SimpleBooleanProperty(false);
    private final ObservableSet<String> typingUsers = FXCollections.observableSet(new HashSet<>());
    private final Map<String, PauseTransition> typingTimers = new ConcurrentHashMap<>();

    private int unreadAtOpen = 0;
    private long oldestLoadedId = 0;
    private long newestLoadedId = 0;
    private long lastTypingSentMs = 0;
    private PauseTransition markReadDebounce;
    private Runnable onUnreadCleared;

    public ConversationViewModel(TargetType type, String target, ChatClient client, String currentUser) {
        this.type = type;
        this.target = target;
        this.client = client;
        this.currentUser = currentUser != null ? currentUser : "";
    }

    public TargetType type() { return type; }
    public String target() { return target; }
    public ObservableList<ChatListItem> items() { return items; }
    public BooleanProperty loadingProperty() { return loading; }
    public BooleanProperty hasEarlierProperty() { return hasEarlier; }
    public BooleanProperty errorProperty() { return error; }
    public ObservableSet<String> typingUsers() { return typingUsers; }
    public int unreadAtOpen() { return unreadAtOpen; }
    public long newestLoadedId() { return newestLoadedId; }

    public void setOnUnreadCleared(Runnable r) { this.onUnreadCleared = r; }

    /** Load first page of 50 messages. */
    public void loadInitial() {
        if (loading.get()) return;
        loading.set(true);
        error.set(false);

        client.loadHistory(type, target, 0, 50).thenAccept(page -> {
            loading.set(false);
            items.clear();
            hasEarlier.set(page.hasMore());

            List<MessageRecord> records = page.messages();
            if (records.isEmpty()) {
                oldestLoadedId = 0;
                newestLoadedId = 0;
                return;
            }

            oldestLoadedId = records.get(0).id();
            newestLoadedId = records.get(records.size() - 1).id();
            long firstUnread = page.firstUnreadId();

            buildInitialItems(records, firstUnread);
        }).exceptionally(ex -> {
            loading.set(false);
            error.set(true);
            return null;
        });
    }

    private void buildInitialItems(List<MessageRecord> records, long firstUnreadId) {
        LocalDate lastDate = null;
        boolean dividerInserted = false;
        int unreadCount = 0;

        if (firstUnreadId > 0) {
            for (MessageRecord r : records) {
                if (r.id() >= firstUnreadId && !r.sender().equals(currentUser)) {
                    unreadCount++;
                }
            }
            if (unreadCount == 0 && firstUnreadId < records.get(0).id()) {
                unreadCount = 1;
            }
        }

        if (firstUnreadId > 0 && firstUnreadId < records.get(0).id()) {
            items.add(new UnreadDividerItem(unreadCount, unreadCount + " unread messages"));
            dividerInserted = true;
        }

        List<MessageItem> msgItems = new ArrayList<>();
        for (MessageRecord r : records) {
            LocalDate msgDate = Instant.ofEpochMilli(r.timestamp()).atZone(ZoneId.systemDefault()).toLocalDate();
            if (lastDate == null || !lastDate.equals(msgDate)) {
                items.add(new DaySeparatorItem(msgDate, formatDay(msgDate)));
                lastDate = msgDate;
            }

            if (!dividerInserted && firstUnreadId > 0 && r.id() >= firstUnreadId) {
                items.add(new UnreadDividerItem(unreadCount, unreadCount + " unread messages"));
                dividerInserted = true;
            }

            boolean isOwn = r.sender().equals(currentUser);
            MessageItem item = new MessageItem(r.id(), null, r.sender(), r.text(), r.timestamp(), r.status(), isOwn, r.attachmentSize());
            msgItems.add(item);
            items.add(item);
        }

        recomputeGrouping(msgItems);
    }

    /** Load earlier messages when scrolling up. */
    public void loadEarlier() {
        if (loading.get() || !hasEarlier.get() || oldestLoadedId <= 0) return;
        loading.set(true);

        client.loadHistory(type, target, oldestLoadedId, 50).thenAccept(page -> {
            loading.set(false);
            hasEarlier.set(page.hasMore());
            List<MessageRecord> earlier = page.messages();
            if (earlier.isEmpty()) return;

            oldestLoadedId = earlier.get(0).id();

            List<MessageRecord> combined = new ArrayList<>(earlier);
            for (ChatListItem it : items) {
                if (it instanceof MessageItem m && m.id() != null) {
                    combined.add(new MessageRecord(m.id(), m.sender(), m.timestamp(), m.status(), m.text(), m.attachmentSize()));
                }
            }
            combined.sort(Comparator.comparingLong(MessageRecord::id));

            items.clear();
            buildInitialItems(combined, 0);
        }).exceptionally(ex -> {
            loading.set(false);
            return null;
        });
    }

    /** Optimistic send: appends PENDING message immediately, updates to SENT on ack. */
    public void sendMessage(String text) {
        if (text == null || text.isBlank()) return;
        String trimmed = text.trim();

        String tempId = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        MessageItem pending = new MessageItem(null, tempId, currentUser, trimmed, now, DeliveryStatus.PENDING, true);

        LocalDate today = LocalDate.now();
        if (items.isEmpty() || !hasDateSeparatorFor(today)) {
            items.add(new DaySeparatorItem(today, formatDay(today)));
        }

        items.add(pending);
        recomputeAllGrouping();

        var future = (type == TargetType.ROOM)
                ? client.sendRoomMessage(target, trimmed)
                : client.sendPrivateMessage(target, trimmed);

        future.thenAccept(ack -> {
            pending.setId(ack.id());
            pending.setTimestamp(ack.timestamp());
            pending.setStatus(DeliveryStatus.SENT);
            if (ack.id() > newestLoadedId) {
                newestLoadedId = ack.id();
            }
        }).exceptionally(ex -> {
            pending.setStatus(DeliveryStatus.FAILED);
            return null;
        });
    }

    public boolean supportsAttachments() { return client.supportsAttachments(); }

    public CompletableFuture<Void> sendAttachment(Path file) {
        return client.sendAttachment(type, target, file).thenAccept(result -> {
            SentAck ack = result.message();
            handleIncomingMessage(new MessageRecord(ack.id(), currentUser, ack.timestamp(), DeliveryStatus.SENT,
                    file.getFileName().toString(), result.size()));
        });
    }

    public CompletableFuture<Void> saveAttachment(MessageItem message, Path destination) {
        return client.saveAttachment(message.id(), message.attachmentSize(), destination);
    }

    /** Retry a failed message. */
    public void retrySend(MessageItem item) {
        if (item.status() != DeliveryStatus.FAILED) return;
        item.setStatus(DeliveryStatus.PENDING);

        var future = (type == TargetType.ROOM)
                ? client.sendRoomMessage(target, item.text())
                : client.sendPrivateMessage(target, item.text());

        future.thenAccept(ack -> {
            item.setId(ack.id());
            item.setTimestamp(ack.timestamp());
            item.setStatus(DeliveryStatus.SENT);
            if (ack.id() > newestLoadedId) {
                newestLoadedId = ack.id();
            }
        }).exceptionally(ex -> {
            item.setStatus(DeliveryStatus.FAILED);
            return null;
        });
    }

    /** Handle incoming message (from push). */
    public void handleIncomingMessage(MessageRecord msg) {
        removeTypingUser(msg.sender());

        for (ChatListItem it : items) {
            if (it instanceof MessageItem m && m.id() != null && m.id().equals(msg.id())) {
                return;
            }
        }

        LocalDate msgDate = Instant.ofEpochMilli(msg.timestamp()).atZone(ZoneId.systemDefault()).toLocalDate();
        if (items.isEmpty() || !hasDateSeparatorFor(msgDate)) {
            items.add(new DaySeparatorItem(msgDate, formatDay(msgDate)));
        }

        boolean isOwn = msg.sender().equals(currentUser);
        MessageItem item = new MessageItem(msg.id(), null, msg.sender(), msg.text(), msg.timestamp(), msg.status(), isOwn, msg.attachmentSize());
        items.add(item);
        if (msg.id() > newestLoadedId) {
            newestLoadedId = msg.id();
        }
        recomputeAllGrouping();
    }

    /** Status upgrade for own private messages. Never downgrades. */
    public void handleStatusUpdate(DeliveryStatus newStatus, List<Long> ids) {
        if (type != TargetType.PRIVATE || ids == null || ids.isEmpty()) return;
        Set<Long> set = new HashSet<>(ids);

        for (ChatListItem it : items) {
            if (it instanceof MessageItem m && m.isOwn() && m.id() != null && set.contains(m.id())) {
                if (newStatus.ordinal() > m.status().ordinal()) {
                    m.setStatus(newStatus);
                }
            }
        }
    }

    /** Typing incoming push: show for 4 seconds then expire. */
    public void handleUserTyping(String user) {
        if (user.equals(currentUser)) return;
        typingUsers.add(user);

        PauseTransition existing = typingTimers.get(user);
        if (existing != null) {
            existing.stop();
        }

        PauseTransition timer = new PauseTransition(Duration.seconds(4));
        timer.setOnFinished(e -> removeTypingUser(user));
        typingTimers.put(user, timer);
        try { try { timer.play(); } catch (Exception ignored) {} } catch (Exception ignored) {}
    }

    private void removeTypingUser(String user) {
        typingUsers.remove(user);
        PauseTransition t = typingTimers.remove(user);
        if (t != null) t.stop();
    }

    /** Throttled typing broadcast: at most 1 per 2 seconds. */
    public void sendTyping() {
        long now = System.currentTimeMillis();
        if (now - lastTypingSentMs >= 2000) {
            lastTypingSentMs = now;
            client.sendTyping(type, target);
        }
    }

    /** Mark conversation read (debounced 500ms). */
    public void markRead() {
        if (newestLoadedId <= 0) return;
        if (markReadDebounce != null) {
            markReadDebounce.stop();
        }
        markReadDebounce = new PauseTransition(Duration.millis(500));
        markReadDebounce.setOnFinished(e -> {
            client.markRead(type, target, newestLoadedId).thenRun(() -> {
                if (onUnreadCleared != null) onUnreadCleared.run();
            });
        });
        try {
            markReadDebounce.play();
        } catch (Exception ignored) {
            client.markRead(type, target, newestLoadedId).thenRun(() -> {
                if (onUnreadCleared != null) onUnreadCleared.run();
            });
        }
    }

    public void handleMemberChanged(String user, boolean joined) {
        items.add(new SystemItem(user + " " + (joined ? "joined the room" : "left the room")));
    }

    /** Jump to message (search hit). */
    public void jumpToMessage(long messageId) {
        loading.set(true);
        client.loadHistoryAround(type, target, messageId).thenAccept(page -> {
            loading.set(false);
            items.clear();
            hasEarlier.set(page.hasMore());
            if (!page.messages().isEmpty()) {
                oldestLoadedId = page.messages().get(0).id();
                newestLoadedId = page.messages().get(page.messages().size() - 1).id();
            }
            buildInitialItems(page.messages(), 0);
        }).exceptionally(ex -> {
            loading.set(false);
            return null;
        });
    }

    private boolean hasDateSeparatorFor(LocalDate date) {
        for (int i = items.size() - 1; i >= 0; i--) {
            if (items.get(i) instanceof DaySeparatorItem d) {
                return d.date().equals(date);
            }
        }
        return false;
    }

    private void recomputeAllGrouping() {
        List<MessageItem> msgItems = new ArrayList<>();
        for (ChatListItem it : items) {
            if (it instanceof MessageItem m) msgItems.add(m);
        }
        recomputeGrouping(msgItems);
    }

    private void recomputeGrouping(List<MessageItem> list) {
        for (int i = 0; i < list.size(); i++) {
            MessageItem curr = list.get(i);
            MessageItem prev = (i > 0) ? list.get(i - 1) : null;
            MessageItem next = (i < list.size() - 1) ? list.get(i + 1) : null;

            boolean first = (prev == null)
                    || !prev.sender().equals(curr.sender())
                    || (curr.timestamp() - prev.timestamp() > GROUP_WINDOW_MS);

            boolean last = (next == null)
                    || !next.sender().equals(curr.sender())
                    || (next.timestamp() - curr.timestamp() > GROUP_WINDOW_MS);

            curr.setFirstInGroup(first);
            curr.setLastInGroup(last);
        }
    }

    private String formatDay(LocalDate d) {
        LocalDate today = LocalDate.now();
        if (d.equals(today)) return "Today";
        if (d.equals(today.minusDays(1))) return "Yesterday";
        return d.format(DATE_FMT);
    }

    public void dispose() {
        for (PauseTransition t : typingTimers.values()) t.stop();
        typingTimers.clear();
        typingUsers.clear();
        if (markReadDebounce != null) markReadDebounce.stop();
    }
}
