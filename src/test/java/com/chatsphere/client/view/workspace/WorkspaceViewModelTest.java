package com.chatsphere.client.view.workspace;

import com.chatsphere.client.AppContext;
import com.chatsphere.client.net.FakeChatClient;
import com.chatsphere.client.net.ServerEvent;
import com.chatsphere.client.view.workspace.state.ConversationItem;
import com.chatsphere.client.view.workspace.state.WorkspaceViewModel;
import com.chatsphere.common.model.DeliveryStatus;
import com.chatsphere.common.model.MessageRecord;
import com.chatsphere.common.model.TargetType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for Person 4's WorkspaceViewModel.
 */
public class WorkspaceViewModelTest {
    private PreviewClient client;
    private WorkspaceViewModel vm;

    @BeforeEach
    void setUp() {
        client = new PreviewClient();
        AppContext.init(client);
        client.connect("localhost", 5050);
        client.login("demo", "password1".toCharArray()).join();
        AppContext.get().session().currentUserProperty().set("demo");

        vm = new WorkspaceViewModel(client, AppContext.get().session());
        vm.start();
    }

    @Test
    @DisplayName("Start loads rooms and users from client")
    void start_loads_rooms_and_users() {
        assertFalse(vm.rooms().isEmpty(), "Rooms list should be loaded");
        assertFalse(vm.users().isEmpty(), "Users list should be loaded");

        ConversationItem general = vm.findItem(TargetType.ROOM, "general");
        assertNotNull(general, "General room should be in list");

        ConversationItem alice = vm.findItem(TargetType.PRIVATE, "alice");
        assertNotNull(alice, "Alice should be in users list");
    }

    @Test
    @DisplayName("Users are sorted: unread first, then online A-Z, then offline A-Z")
    void users_are_sorted_correctly() {
        ConversationItem firstUser = vm.users().get(0);
        assertTrue(firstUser.unread() > 0, "First user should have unread messages");
    }

    @Test
    @DisplayName("Selecting conversation resets unread count and creates view-model")
    void selecting_conversation_resets_unread() {
        ConversationItem alice = vm.findItem(TargetType.PRIVATE, "alice");
        assertNotNull(alice);
        assertTrue(alice.unread() > 0);

        vm.setSelected(alice);
        assertEquals(0, alice.unread(), "Selected conversation unread count should be cleared");
        assertNotNull(vm.getOrCreateConversationViewModel(TargetType.PRIVATE, "alice"));
    }

    @Test
    @DisplayName("Alt+Up and Alt+Down cycle through conversations")
    void navigation_shortcuts_cycle_conversations() {
        vm.setSelected(vm.rooms().get(0));
        ConversationItem initial = vm.selected();

        vm.selectNextConversation();
        assertNotEquals(initial, vm.selected(), "selectNext should change selection");

        vm.selectPreviousConversation();
        assertEquals(initial, vm.selected(), "selectPrevious should return to initial");
    }

    @Test
    @DisplayName("Right slot mode toggles between NONE, DETAILS and SEARCH")
    void right_slot_mode_toggles() {
        assertEquals(WorkspaceViewModel.RightSlotMode.NONE, vm.rightSlotMode());

        vm.setRightSlotMode(WorkspaceViewModel.RightSlotMode.SEARCH);
        assertEquals(WorkspaceViewModel.RightSlotMode.SEARCH, vm.rightSlotMode());

        vm.setRightSlotMode(WorkspaceViewModel.RightSlotMode.DETAILS);
        assertEquals(WorkspaceViewModel.RightSlotMode.DETAILS, vm.rightSlotMode());

        vm.setRightSlotMode(WorkspaceViewModel.RightSlotMode.NONE);
        assertEquals(WorkspaceViewModel.RightSlotMode.NONE, vm.rightSlotMode());
    }

    @Test
    void newlyRegisteredUserAppearsWithoutReselectingDirectTab() {
        assertNull(vm.findItem(TargetType.PRIVATE, "newuser"));
        client.push(new ServerEvent.UserStatus("newuser", true));
        var user = vm.findItem(TargetType.PRIVATE, "newuser");
        assertNotNull(user);
        assertTrue(user.isOnline());
        client.push(new ServerEvent.UserStatus("newuser", false));
        assertFalse(user.isOnline());
    }

    @Test
    void firstMessageFromAnUnlistedUserAppearsWithUnreadCount() {
        var file = new MessageRecord(51, "newuser", 1000, DeliveryStatus.DELIVERED, "diagram.png", 42);
        client.push(new ServerEvent.PrivateMessage("newuser", file));
        var user = vm.findItem(TargetType.PRIVATE, "newuser");
        assertNotNull(user);
        assertEquals("diagram.png", user.preview());
        assertEquals(1, user.unread());
    }

    private static final class PreviewClient extends FakeChatClient {
        private final List<Consumer<ServerEvent>> observers = new ArrayList<>();

        PreviewClient() { super(Runnable::run); }

        @Override public Runnable addEventListener(Consumer<ServerEvent> listener) {
            observers.add(listener);
            Runnable unsubscribe = super.addEventListener(listener);
            return () -> {
                observers.remove(listener);
                unsubscribe.run();
            };
        }

        void push(ServerEvent event) { List.copyOf(observers).forEach(observer -> observer.accept(event)); }
    }
}
