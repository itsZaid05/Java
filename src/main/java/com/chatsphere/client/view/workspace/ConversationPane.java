package com.chatsphere.client.view.workspace;

import com.chatsphere.client.AppContext;
import com.chatsphere.client.component.AppButton;
import com.chatsphere.client.view.workspace.state.ConversationItem;
import com.chatsphere.client.view.workspace.state.ConversationViewModel;
import com.chatsphere.client.view.workspace.state.WorkspaceViewModel;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/**
 * Center conversation view: empty state OR conversation header + messages + composer.
 * Owned by Person 4.
 */
public class ConversationPane extends VBox {
    private final WorkspaceViewModel workspaceVm;

    public ConversationPane(WorkspaceViewModel workspaceVm) {
        this.workspaceVm = workspaceVm;
        getStyleClass().add("conversation-pane");

        workspaceVm.selectedProperty().addListener((obs, oldVal, newVal) -> render(newVal));
        render(workspaceVm.selected());
    }

    private void render(ConversationItem selected) {
        getChildren().clear();

        if (selected == null) {
            VBox empty = new VBox(12);
            empty.getStyleClass().add("empty-state-pane");
            empty.setAlignment(Pos.CENTER);
            VBox.setVgrow(empty, Priority.ALWAYS);

            Label title = new Label("Pick a conversation");
            title.getStyleClass().add("empty-state-title");

            Label body = new Label("Choose a room or direct message from the sidebar to start chatting.");
            body.getStyleClass().add("empty-state-body");

            AppButton createRoomBtn = new AppButton("Create a room").withIcon("fth-plus");
            createRoomBtn.setOnAction(e -> AppContext.get().dialogs().showCreateRoom(name -> {
                workspaceVm.refreshRooms();
            }));

            AppButton browseBtn = new AppButton("Browse rooms", AppButton.Variant.SECONDARY);
            browseBtn.setOnAction(e -> AppContext.get().dialogs().showBrowseRooms(name -> {
                workspaceVm.refreshRooms();
            }));

            empty.getChildren().addAll(title, body, createRoomBtn, browseBtn);
            getChildren().add(empty);
        } else {
            ConversationViewModel cvm = workspaceVm.getOrCreateConversationViewModel(selected.type(), selected.key());

            ConversationHeader header = new ConversationHeader(selected, workspaceVm);
            MessageListView messageList = new MessageListView(cvm);
            VBox.setVgrow(messageList, Priority.ALWAYS);

            TypingStrip typingStrip = new TypingStrip(cvm);
            Composer composer = new Composer(cvm, selected.onlineProperty());

            getChildren().addAll(header, messageList, typingStrip, composer);
        }
    }
}
