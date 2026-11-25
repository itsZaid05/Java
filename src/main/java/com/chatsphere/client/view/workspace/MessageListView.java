package com.chatsphere.client.view.workspace;

import com.chatsphere.client.view.workspace.state.ChatListItem;
import com.chatsphere.client.view.workspace.state.ChatListItem.*;
import com.chatsphere.client.view.workspace.state.ConversationViewModel;
import javafx.animation.FadeTransition;
import javafx.application.Platform;
import javafx.collections.ListChangeListener;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

/**
 * Message history scroll view with DaySeparators, UnreadDivider, MessageBubbles,
 * "Load earlier" button, and "N new messages" floating pill.
 * Owned by Person 4.
 */
public class MessageListView extends StackPane {
    private final ConversationViewModel viewModel;
    private final ScrollPane scrollPane = new ScrollPane();
    private final VBox messageBox = new VBox();
    private final Button loadEarlierBtn = new Button("Load earlier messages");
    private final StackPane newMessagesPill = new StackPane();
    private final Label newMessagesLabel = new Label();
    private int unreadWhileScrolledUp = 0;

    public MessageListView(ConversationViewModel viewModel) {
        this.viewModel = viewModel;
        getStyleClass().add("message-list-view");

        messageBox.getStyleClass().add("message-column");
        messageBox.setFillWidth(true);
        messageBox.setAlignment(Pos.TOP_CENTER);

        loadEarlierBtn.getStyleClass().add("load-earlier-btn");
        loadEarlierBtn.setOnAction(e -> viewModel.loadEarlier());
        loadEarlierBtn.visibleProperty().bind(viewModel.hasEarlierProperty());
        loadEarlierBtn.managedProperty().bind(loadEarlierBtn.visibleProperty());

        scrollPane.setContent(messageBox);
        scrollPane.setFitToWidth(true);
        scrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scrollPane.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scrollPane.getStyleClass().add("message-list-scroll");

        newMessagesPill.getStyleClass().add("new-messages-pill");
        newMessagesLabel.getStyleClass().add("new-messages-pill-text");
        newMessagesPill.getChildren().add(newMessagesLabel);
        newMessagesPill.setVisible(false);
        newMessagesPill.setManaged(false);
        StackPane.setAlignment(newMessagesPill, Pos.BOTTOM_CENTER);
        StackPane.setMargin(newMessagesPill, new Insets(0, 0, 16, 0));
        newMessagesPill.setOnMouseClicked(e -> {
            scrollToBottom();
            hideNewMessagesPill();
            viewModel.markRead();
        });

        getChildren().addAll(scrollPane, newMessagesPill);

        viewModel.items().addListener((ListChangeListener<ChatListItem>) c -> {
            while (c.next()) {
                if (c.wasAdded() || c.wasRemoved() || c.wasReplaced()) {
                    renderItems();
                }
            }
        });

        scrollPane.vvalueProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal.doubleValue() >= 0.95) {
                hideNewMessagesPill();
                viewModel.markRead();
            }
        });

        renderItems();
    }

    private void renderItems() {
        boolean wasNearBottom = isNearBottom();

        messageBox.getChildren().clear();
        messageBox.getChildren().add(loadEarlierBtn);

        for (ChatListItem item : viewModel.items()) {
            switch (item) {
                case DaySeparatorItem d -> messageBox.getChildren().add(new DaySeparator(d.label()));
                case UnreadDividerItem u -> messageBox.getChildren().add(new UnreadDividerView(u.text()));
                case SystemItem s -> {
                    Label sys = new Label(s.text());
                    sys.getStyleClass().add("system-text");
                    StackPane sysPane = new StackPane(sys);
                    sysPane.getStyleClass().add("system-line");
                    messageBox.getChildren().add(sysPane);
                }
                case MessageItem m -> {
                    MessageBubble bubble = new MessageBubble(m, viewModel.type(), viewModel::retrySend, viewModel::saveAttachment);
                    messageBox.getChildren().add(bubble);
                }
            }
        }

        Platform.runLater(() -> {
            if (wasNearBottom) {
                scrollToBottom();
            } else if (!viewModel.items().isEmpty()) {
                unreadWhileScrolledUp++;
                showNewMessagesPill(unreadWhileScrolledUp + " new message" + (unreadWhileScrolledUp > 1 ? "s" : ""));
            }
        });
    }

    public boolean isNearBottom() {
        return scrollPane.getVvalue() >= 0.90 || scrollPane.getVvalue() == 0.0;
    }

    public void scrollToBottom() {
        scrollPane.setVvalue(1.0);
    }

    private void showNewMessagesPill(String text) {
        newMessagesLabel.setText(text);
        newMessagesPill.setVisible(true);
        newMessagesPill.setManaged(true);
    }

    private void hideNewMessagesPill() {
        unreadWhileScrolledUp = 0;
        newMessagesPill.setVisible(false);
        newMessagesPill.setManaged(false);
    }

    public void highlightMessage(long messageId) {
        for (Node n : messageBox.getChildren()) {
            if (n instanceof MessageBubble b && b.getItem().id() != null && b.getItem().id() == messageId) {
                n.getStyleClass().add("highlight-flash");
                FadeTransition ft = new FadeTransition(Duration.millis(1500), n);
                ft.setFromValue(1.0);
                ft.setToValue(1.0);
                ft.setOnFinished(e -> n.getStyleClass().remove("highlight-flash"));
                ft.play();
                break;
            }
        }
    }
}
