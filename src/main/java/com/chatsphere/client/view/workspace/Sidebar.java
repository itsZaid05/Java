package com.chatsphere.client.view.workspace;

import com.chatsphere.client.AppContext;
import com.chatsphere.client.component.*;
import com.chatsphere.client.net.ConnectionState;
import com.chatsphere.common.model.TargetType;
import com.chatsphere.client.view.workspace.state.ConversationItem;
import com.chatsphere.client.view.workspace.state.WorkspaceViewModel;
import com.chatsphere.client.view.workspace.state.WorkspaceViewModel.RightSlotMode;
import javafx.beans.binding.Bindings;
import javafx.collections.ListChangeListener;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.*;
import org.kordamp.ikonli.javafx.FontIcon;

/**
 * 300px fixed width sidebar: header, search entry, segmented tabs, lists, footer.
 * Owned by Person 4.
 */
public class Sidebar extends VBox {
    private final WorkspaceViewModel viewModel;
    private final SegmentedTabs tabs = new SegmentedTabs("Rooms", "Direct");
    private final VBox roomSection = new VBox();
    private final VBox roomListBox = new VBox();
    private final VBox userListBox = new VBox();
    private final StackPane listContainer = new StackPane();

    public Sidebar(WorkspaceViewModel viewModel) {
        this.viewModel = viewModel;
        getStyleClass().add("sidebar");
        setPrefWidth(300);

        buildHeader();
        buildSearchEntry();
        buildTabs();
        buildLists();
        buildFooter();
    }

    private void buildHeader() {
        HBox header = new HBox();
        header.getStyleClass().add("sidebar-header");
        header.setAlignment(Pos.CENTER_LEFT);
        header.setSpacing(10);

        String user = AppContext.get().session().currentUser();
        Avatar avatar = new Avatar(user != null ? user : "demo", 32);
        avatar.setShowPresence(true);
        avatar.onlineProperty().set(true);

        VBox userBox = new VBox();
        userBox.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(userBox, Priority.ALWAYS);

        Label userLabel = new Label(user != null ? user : "demo");
        userLabel.getStyleClass().add("user-title");

        Label youLabel = new Label("You");
        youLabel.getStyleClass().add("user-subtitle");
        userBox.getChildren().addAll(userLabel, youLabel);

        IconButton settingsBtn = new IconButton("fth-settings", "Settings");
        settingsBtn.setOnAction(e -> AppContext.get().dialogs().showSettings());

        header.getChildren().addAll(avatar, userBox, settingsBtn);
        getChildren().add(header);
    }

    private void buildSearchEntry() {
        HBox searchEntry = new HBox();
        searchEntry.getStyleClass().add("sidebar-search-btn");
        searchEntry.setAlignment(Pos.CENTER_LEFT);
        searchEntry.setSpacing(8);

        FontIcon icon = new FontIcon("fth-search");
        icon.setIconSize(16);
        icon.getStyleClass().add("sidebar-search-icon");

        Label placeholder = new Label("Search messages");
        placeholder.getStyleClass().add("sidebar-search-text");
        HBox.setHgrow(placeholder, Priority.ALWAYS);

        Label shortcut = new Label("Ctrl+F");
        shortcut.getStyleClass().add("sidebar-search-shortcut");

        searchEntry.getChildren().addAll(icon, placeholder, shortcut);
        searchEntry.setOnMouseClicked(e -> viewModel.setRightSlotMode(RightSlotMode.SEARCH));

        VBox pad = new VBox(searchEntry);
        pad.setPadding(new javafx.geometry.Insets(8, 16, 8, 16));
        getChildren().add(pad);
    }

    private void buildTabs() {
        VBox tabBox = new VBox(tabs);
        tabBox.setPadding(new javafx.geometry.Insets(0, 16, 8, 16));
        getChildren().add(tabBox);

        tabs.selectedIndexProperty().addListener((obs, oldVal, newVal) -> {
            boolean isRooms = newVal.intValue() == 0;
            roomSection.setVisible(isRooms);
            roomSection.setManaged(isRooms);
            userListBox.setVisible(!isRooms);
            userListBox.setManaged(!isRooms);
            if (!isRooms) viewModel.refreshUsers();
        });
        viewModel.selectedProperty().addListener((obs, previous, selected) -> {
            if (selected != null) {
                tabs.selectedIndexProperty().set(selected.type() == TargetType.ROOM ? 0 : 1);
            }
        });
    }

    private void buildLists() {
        HBox actionBar = new HBox(8);
        actionBar.getStyleClass().add("sidebar-action-bar");

        Button newRoomBtn = new Button("New room");
        newRoomBtn.getStyleClass().add("ghost-action-btn");
        newRoomBtn.setGraphic(new FontIcon("fth-plus"));
        newRoomBtn.setOnAction(e -> AppContext.get().dialogs().showCreateRoom(name -> {
            viewModel.refreshRooms();
        }));

        Button browseBtn = new Button("Browse");
        browseBtn.getStyleClass().add("ghost-action-btn");
        browseBtn.setGraphic(new FontIcon("fth-compass"));
        browseBtn.setOnAction(e -> AppContext.get().dialogs().showBrowseRooms(name -> {
            viewModel.refreshRooms();
        }));

        actionBar.getChildren().addAll(newRoomBtn, browseBtn);
        roomSection.getChildren().addAll(actionBar, roomListBox);

        ScrollPane sp = new ScrollPane(listContainer);
        sp.setFitToWidth(true);
        sp.getStyleClass().add("message-list-scroll");
        VBox.setVgrow(sp, Priority.ALWAYS);

        listContainer.getChildren().addAll(roomSection, userListBox);
        userListBox.setVisible(false);
        userListBox.setManaged(false);

        getChildren().add(sp);

        viewModel.rooms().addListener((ListChangeListener<ConversationItem>) c -> renderRooms());
        viewModel.users().addListener((ListChangeListener<ConversationItem>) c -> renderUsers());
        viewModel.selectedProperty().addListener((obs, oldVal, newVal) -> {
            renderRooms();
            renderUsers();
        });

        renderRooms();
        renderUsers();
    }

    private void renderRooms() {
        roomListBox.getChildren().clear();
        for (ConversationItem item : viewModel.rooms()) {
            boolean sel = viewModel.selected() != null && viewModel.selected().type() == item.type()
                    && viewModel.selected().key().equals(item.key());
            ConversationRow row = new ConversationRow(item, sel);
            row.setOnMouseClicked(e -> viewModel.setSelected(item));
            roomListBox.getChildren().add(row);
        }
    }

    private void renderUsers() {
        userListBox.getChildren().clear();
        for (ConversationItem item : viewModel.users()) {
            boolean sel = viewModel.selected() != null && viewModel.selected().type() == item.type()
                    && viewModel.selected().key().equals(item.key());
            ConversationRow row = new ConversationRow(item, sel);
            row.setOnMouseClicked(e -> viewModel.setSelected(item));
            userListBox.getChildren().add(row);
        }
    }

    private void buildFooter() {
        HBox footer = new HBox();
        footer.getStyleClass().add("sidebar-footer");
        footer.setSpacing(8);

        PresenceDot dot = new PresenceDot();
        dot.onlineProperty().bind(AppContext.get().session().connectionStateProperty().isEqualTo(ConnectionState.CONNECTED));

        Label status = new Label();
        status.getStyleClass().add("status-text");
        status.textProperty().bind(Bindings.createStringBinding(() -> {
            ConnectionState s = AppContext.get().session().connectionStateProperty().get();
            if (s == null) return "Offline";
            return switch (s) {
                case CONNECTED -> "Connected";
                case CONNECTING -> "Connecting...";
                case RECONNECTING -> "Reconnecting...";
                case OFFLINE -> "Offline";
            };
        }, AppContext.get().session().connectionStateProperty()));

        footer.getChildren().addAll(dot, status);
        getChildren().add(footer);
    }
}
