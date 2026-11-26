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
    private final ReadOnlyLongWrapper historyFocus = new ReadOnlyLongWrapper();
    private long historyRequest;
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
    public ReadOnlyLongProperty historyFocusProperty() { return historyFocus.getReadOnlyProperty(); }
    public ObservableSet<String> typingUsers() { return typingUsers; }
    public int unreadAtOpen() { return unreadAtOpen; }
    public long newestLoadedId() { return newestLoadedId; }

    public void setOnUnreadCleared(Runnable r) { this.onUnreadCleared = r; }

    /** Load the latest page, keeping unsent drafts and newer live pushes. */
    public void loadInitial() {
        if (loading.get()) return;
        long request = ++historyRequest;
        loading.set(true);
        error.set(false);

        client.loadHistory(type, target, 0, 50).whenComplete((page, failure) -> {
            if (request != historyRequest) return;
            loading.set(false);
            if (failure != null) {
                error.set(true);
                return;
            }
            List<MessageRecord> records = new ArrayList<>(page.messages());
            long lastStored = records.isEmpty() ? 0 : records.getLast().id();
            records.addAll(savedMessagesAfter(lastStored));
            historyFocus.set(0);
            replaceHistory(records, page.firstUnreadId(), page.hasMore());
        });
    }

    private List<MessageRecord> savedMessagesAfter(long id) {
        return items.stream()
                .filter(MessageItem.class::isInstance)
                .map(MessageItem.class::cast)
                .filter(m -> m.id() != null && m.id() > id)
                .map(m -> new MessageRecord(m.id(), m.sender(), m.timestamp(), m.status(), m.text(), m.attachmentSize()))
                .toList();
    }

    private void replaceHistory(List<MessageRecord> records, long firstUnreadId, boolean hasMore) {
        List<MessageItem> unsent = items.stream()
                .filter(MessageItem.class::isInstance)
                .map(MessageItem.class::cast)
                .filter(m -> m.id() == null)
                .toList();
        List<MessageRecord> sorted = records.stream().sorted(Comparator.comparingLong(MessageRecord::id)).toList();
        oldestLoadedId = sorted.isEmpty() ? 0 : sorted.getFirst().id();
        newestLoadedId = sorted.isEmpty() ? 0 : sorted.getLast().id();
        hasEarlier.set(hasMore);

        List<ChatListItem> replacement = new ArrayList<>();
        List<MessageItem> grouped = new ArrayList<>();
        LocalDate previousDate = null;
        boolean showUnread = firstUnreadId > 0;
        long unreadCount = showUnread ? sorted.stream()
                .filter(r -> r.id() >= firstUnreadId && !r.sender().equals(currentUser)).count() : 0;

        for (MessageRecord r : sorted) {
            LocalDate date = localDate(r.timestamp());
            if (!date.equals(previousDate)) {
                replacement.add(new DaySeparatorItem(date, formatDay(date)));
                previousDate = date;
            }
            if (showUnread && unreadCount > 0 && r.id() >= firstUnreadId) {
                replacement.add(new UnreadDividerItem((int) unreadCount,
                        unreadCount + (unreadCount == 1 ? " unread message" : " unread messages")));
                showUnread = false;
            }
            MessageItem item = new MessageItem(r.id(), null, r.sender(), r.text(), r.timestamp(),
                    r.status(), r.sender().equals(currentUser), r.attachmentSize());
            grouped.add(item);
            replacement.add(item);
        }

        for (MessageItem item : unsent) {
            LocalDate date = localDate(item.timestamp());
            if (!date.equals(previousDate)) {
                replacement.add(new DaySeparatorItem(date, formatDay(date)));
                previousDate = date;
            }
            grouped.add(item);
            replacement.add(item);
        }
        recomputeGrouping(grouped);
        items.setAll(replacement);
    }

    /** Prepend one page without replacing a pending send or a retryable failure. */
    public void loadEarlier() {
        if (loading.get() || !hasEarlier.get() || oldestLoadedId <= 0) return;
        long request = ++historyRequest;
        loading.set(true);
        error.set(false);
        client.loadHistory(type, target, oldestLoadedId, 50).whenComplete((page, failure) -> {
            if (request != historyRequest) return;
            loading.set(false);
            if (failure != null) {
                error.set(true);
                return;
            }
            if (page.messages().isEmpty()) {
                hasEarlier.set(false);
                return;
            }
            List<MessageRecord> records = new ArrayList<>(page.messages());
            records.addAll(savedMessagesAfter(0));
            replaceHistory(records, 0, page.hasMore());
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

        LocalDate msgDate = localDate(msg.timestamp());
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
        try {
            timer.play();
        } catch (IllegalStateException ignored) {
            // View-model tests can run without a JavaFX toolkit.
        }
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

    /** A search result supersedes in-flight history so the correct window wins. */
    public void jumpToMessage(long messageId) {
        long request = ++historyRequest;
        loading.set(true);
        error.set(false);
        client.loadHistoryAround(type, target, messageId).whenComplete((page, failure) -> {
            if (request != historyRequest) return;
            loading.set(false);
            if (failure != null || page.messages().stream().noneMatch(message -> message.id() == messageId)) {
                error.set(true);
                return;
            }
            historyFocus.set(0);
            replaceHistory(page.messages(), 0, page.hasMore());
            historyFocus.set(messageId);
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

            boolean first = prev == null || !sameGroup(prev, curr);
            boolean last = next == null || !sameGroup(curr, next);

            curr.setFirstInGroup(first);
            curr.setLastInGroup(last);
        }
    }

    private boolean sameGroup(MessageItem earlier, MessageItem later) {
        return earlier.sender().equals(later.sender())
                && Math.abs(later.timestamp() - earlier.timestamp()) <= GROUP_WINDOW_MS
                && localDate(earlier.timestamp()).equals(localDate(later.timestamp()));
    }

    private static LocalDate localDate(long timestamp) {
        return Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()).toLocalDate();
    }

    private String formatDay(LocalDate d) {
        LocalDate today = LocalDate.now();
        if (d.equals(today)) return "Today";
        if (d.equals(today.minusDays(1))) return "Yesterday";
        return d.format(DATE_FMT);
    }

    public void dispose() {
        historyRequest++;
        for (PauseTransition t : typingTimers.values()) t.stop();
        typingTimers.clear();
        typingUsers.clear();
        if (markReadDebounce != null) markReadDebounce.stop();
    }
}
