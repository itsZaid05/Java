package com.chatsphere.client.view.workspace.state;

import com.chatsphere.client.net.ChatClient;
import com.chatsphere.client.net.ConnectionState;
import com.chatsphere.client.net.ServerEvent;
import com.chatsphere.client.net.ServerEvent.*;
import com.chatsphere.client.state.SessionState;
import com.chatsphere.common.model.*;
import javafx.beans.property.*;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Top-level ViewModel managing room/user lists, unread totals, right slot and event routing.
 * Owned by Person 4.
 */
public class WorkspaceViewModel {
    public enum RightSlotMode { NONE, DETAILS, SEARCH }

    private final ChatClient client;
    private final SessionState session;
    private final ObservableList<ConversationItem> rooms = FXCollections.observableArrayList();
    private final ObservableList<ConversationItem> users = FXCollections.observableArrayList();
    private final ObjectProperty<ConversationItem> selected = new SimpleObjectProperty<>();
    private final ObjectProperty<RightSlotMode> rightSlotMode = new SimpleObjectProperty<>(RightSlotMode.NONE);
    private final Map<String, ConversationViewModel> conversationCache = new ConcurrentHashMap<>();
    private final BooleanProperty loading = new SimpleBooleanProperty(false);

    private Runnable unsubscribeEvents;

    public WorkspaceViewModel(ChatClient client, SessionState session) {
        this.client = client;
        this.session = session;

        selected.addListener((obs, oldVal, newVal) -> {
            if (newVal != null) {
                ConversationViewModel cvm = getOrCreateConversationViewModel(newVal.type(), newVal.key());
                if (cvm.items().isEmpty()) {
                    cvm.loadInitial();
                }
                cvm.markRead();
                newVal.setUnread(0);
                updateTotalUnread();
            }
        });
    }

    public ObservableList<ConversationItem> rooms() { return rooms; }
    public ObservableList<ConversationItem> users() { return users; }
    public ObjectProperty<ConversationItem> selectedProperty() { return selected; }
    public ConversationItem selected() { return selected.get(); }
    public void setSelected(ConversationItem item) { selected.set(item); }

    public ObjectProperty<RightSlotMode> rightSlotModeProperty() { return rightSlotMode; }
    public RightSlotMode rightSlotMode() { return rightSlotMode.get(); }
    public void setRightSlotMode(RightSlotMode mode) { rightSlotMode.set(mode); }

    public BooleanProperty loadingProperty() { return loading; }

    /** Start listening to server events and load initial lists. */
    public void start() {
        unsubscribeEvents = client.addEventListener(this::onServerEvent);
        refreshRooms();
        refreshUsers();
    }

    public void refreshRooms() {
        loading.set(true);
        client.listRooms(true).thenAccept(list -> {
            loading.set(false);
            rooms.clear();
            list.sort(Comparator.comparing(RoomSummary::name, String.CASE_INSENSITIVE_ORDER));
            for (RoomSummary r : list) {
                ConversationItem item = new ConversationItem(TargetType.ROOM, r.name(), "#" + r.name());
                item.setPreview(r.lastText());
                item.setLastTimestamp(r.lastTimestamp());
                item.setUnread(r.unread());
                item.setJoined(r.joined());
                item.setMemberCount(r.memberCount());
                rooms.add(item);
            }
            updateTotalUnread();
        }).exceptionally(ex -> {
            loading.set(false);
            return null;
        });
    }

    public void refreshUsers() {
        client.listUsers().thenAccept(list -> {
            for (UserSummary u : list) {
                ConversationItem item = getOrCreateUser(u.username());
                item.setPreview(u.lastText());
                item.setLastTimestamp(u.lastTimestamp());
                item.setUnread(u.unread());
                item.setOnline(u.online());
            }
            sortUsersList();
            updateTotalUnread();
        });
    }

    private ConversationItem getOrCreateUser(String username) {
        ConversationItem item = findItem(TargetType.PRIVATE, username);
        if (item == null) {
            item = new ConversationItem(TargetType.PRIVATE, username, username);
            users.add(item);
        }
        return item;
    }

    /** Sorting: unread first, then online A-Z, then offline A-Z. */
    public void sortUsersList() {
        users.sort((a, b) -> {
            boolean aUnread = a.unread() > 0;
            boolean bUnread = b.unread() > 0;
            if (aUnread != bUnread) return aUnread ? -1 : 1;
            if (a.isOnline() != b.isOnline()) return a.isOnline() ? -1 : 1;
            return a.key().compareToIgnoreCase(b.key());
        });
    }

    public ConversationViewModel getOrCreateConversationViewModel(TargetType type, String target) {
        String cacheKey = type.name() + ":" + target;
        return conversationCache.computeIfAbsent(cacheKey, k -> {
            ConversationViewModel vm = new ConversationViewModel(type, target, client, session.currentUser());
            vm.setOnUnreadCleared(() -> {
                ConversationItem ci = findItem(type, target);
                if (ci != null) {
                    ci.setUnread(0);
                    updateTotalUnread();
                }
            });
            return vm;
        });
    }

    private void onServerEvent(ServerEvent event) {
        switch (event) {
            case RoomMessage(String room, MessageRecord message) -> {
                ConversationItem item = findItem(TargetType.ROOM, room);
                if (item != null) {
                    item.setPreview(message.text());
                    item.setLastTimestamp(message.timestamp());
                    if (isSelected(TargetType.ROOM, room)) {
                        ConversationViewModel vm = conversationCache.get("ROOM:" + room);
                        if (vm != null) vm.handleIncomingMessage(message);
                        vm.markRead();
                    } else {
                        item.setUnread(item.unread() + 1);
                        updateTotalUnread();
                    }
                }
            }
            case PrivateMessage(String peer, MessageRecord message) -> {
                ConversationItem item = getOrCreateUser(peer);
                item.setPreview(message.text());
                item.setLastTimestamp(message.timestamp());
                if (isSelected(TargetType.PRIVATE, peer)) {
                    ConversationViewModel vm = getOrCreateConversationViewModel(TargetType.PRIVATE, peer);
                    vm.handleIncomingMessage(message);
                    vm.markRead();
                } else {
                    item.setUnread(item.unread() + 1);
                    sortUsersList();
                    updateTotalUnread();
                }
            }
            case StatusUpdate(DeliveryStatus status, String peer, List<Long> ids) -> {
                ConversationViewModel vm = conversationCache.get("PRIVATE:" + peer);
                if (vm != null) {
                    vm.handleStatusUpdate(status, ids);
                }
            }
            case UserStatus(String username, boolean online) -> {
                getOrCreateUser(username).setOnline(online);
                sortUsersList();
            }
            case Typing(TargetType type, String target, String user) -> {
                ConversationViewModel vm = conversationCache.get(type.name() + ":" + target);
                if (vm != null) {
                    vm.handleUserTyping(user);
                }
            }
            case RoomMembersChanged(String room, String user, boolean joined) -> {
                ConversationItem item = findItem(TargetType.ROOM, room);
                if (item != null) {
                    item.setMemberCount(joined ? item.memberCount() + 1 : Math.max(0, item.memberCount() - 1));
                }
                ConversationViewModel vm = conversationCache.get("ROOM:" + room);
                if (vm != null) {
                    vm.handleMemberChanged(user, joined);
                }
                if (user.equals(session.currentUser()) && !joined) {
                    rooms.removeIf(r -> r.key().equals(room));
                    if (isSelected(TargetType.ROOM, room)) {
                        selected.set(rooms.isEmpty() ? null : rooms.get(0));
                    }
                }
            }
            case ConnectionStateChanged(ConnectionState state, int retry, String detail) -> {
                session.connectionStateProperty().set(state);
            }
            case SessionRestored() -> {
                refreshRooms();
                refreshUsers();
                if (selected.get() != null) {
                    ConversationViewModel vm = conversationCache.get(selected.get().type().name() + ":" + selected.get().key());
                    if (vm != null) vm.loadInitial();
                }
            }
            case SessionEnded(String reason) -> {
                dispose();
            }
            default -> {}
        }
    }

    private boolean isSelected(TargetType type, String key) {
        ConversationItem s = selected.get();
        return s != null && s.type() == type && s.key().equals(key);
    }

    public ConversationItem findItem(TargetType type, String key) {
        ObservableList<ConversationItem> list = (type == TargetType.ROOM) ? rooms : users;
        for (ConversationItem ci : list) {
            if (ci.key().equals(key)) return ci;
        }
        return null;
    }

    public void selectNextConversation() {
        ObservableList<ConversationItem> currentList = (selected.get() != null && selected.get().type() == TargetType.PRIVATE) ? users : rooms;
        if (currentList.isEmpty()) return;
        int idx = currentList.indexOf(selected.get());
        int next = (idx + 1) % currentList.size();
        selected.set(currentList.get(next));
    }

    public void selectPreviousConversation() {
        ObservableList<ConversationItem> currentList = (selected.get() != null && selected.get().type() == TargetType.PRIVATE) ? users : rooms;
        if (currentList.isEmpty()) return;
        int idx = currentList.indexOf(selected.get());
        int prev = (idx - 1 + currentList.size()) % currentList.size();
        selected.set(currentList.get(prev));
    }

    private void updateTotalUnread() {
        int total = 0;
        for (ConversationItem r : rooms) total += r.unread();
        for (ConversationItem u : users) total += u.unread();
        session.totalUnreadProperty().set(total);
    }

    public void dispose() {
        if (unsubscribeEvents != null) {
            unsubscribeEvents.run();
            unsubscribeEvents = null;
        }
        for (ConversationViewModel vm : conversationCache.values()) {
            vm.dispose();
        }
        conversationCache.clear();
    }
}
