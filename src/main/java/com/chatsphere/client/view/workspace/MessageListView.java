package com.chatsphere.client.view.workspace;

import com.chatsphere.client.view.workspace.state.ChatListItem;
import com.chatsphere.client.view.workspace.state.ChatListItem.*;
import com.chatsphere.client.view.workspace.state.ConversationViewModel;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.WeakChangeListener;
import javafx.collections.ListChangeListener;
import javafx.collections.WeakListChangeListener;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

/** Message history, earlier pages, and navigation to a search result or the latest message. */
public class MessageListView extends StackPane {
    private final ConversationViewModel viewModel;
    private final ScrollPane scrollPane = new ScrollPane();
    private final VBox messageBox = new VBox();
    private final StackPane stream = new StackPane(messageBox);
    private final Button loadEarlierBtn = new Button("Load earlier messages");
    private final Button latestBtn = new Button("Jump to latest");
    private final Label historyError = new Label("Couldn’t load messages. Try again.");
    private final ListChangeListener<ChatListItem> listListener = change -> scheduleRender();
    private final ChangeListener<Number> focusListener = (observable, previous, id) -> {
        focusChanged = true;
        scheduleRender();
    };
    private final ChangeListener<Boolean> errorListener = (observable, previous, error) -> updateLatestButton();
    private boolean renderScheduled;
    private boolean focusChanged;
    private boolean restoringScroll;

    public MessageListView(ConversationViewModel viewModel) {
        this.viewModel = viewModel;
        getStyleClass().add("message-list-view");

        messageBox.getStyleClass().add("message-column");
        messageBox.setFillWidth(true);
        StackPane.setAlignment(messageBox, Pos.TOP_CENTER);

        loadEarlierBtn.getStyleClass().add("load-earlier-btn");
        loadEarlierBtn.setOnAction(e -> viewModel.loadEarlier());
        loadEarlierBtn.disableProperty().bind(viewModel.loadingProperty());
        loadEarlierBtn.visibleProperty().bind(viewModel.hasEarlierProperty());
        loadEarlierBtn.managedProperty().bind(loadEarlierBtn.visibleProperty());

        historyError.getStyleClass().add("history-error");
        historyError.visibleProperty().bind(viewModel.errorProperty());
        historyError.managedProperty().bind(historyError.visibleProperty());
        historyError.setWrapText(true);

        scrollPane.setContent(stream);
        scrollPane.setFitToWidth(true);
        scrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scrollPane.getStyleClass().add("message-list-scroll");

        latestBtn.getStyleClass().add("new-messages-pill");
        latestBtn.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        latestBtn.managedProperty().bind(latestBtn.visibleProperty());
        latestBtn.disableProperty().bind(viewModel.loadingProperty());
        StackPane.setAlignment(latestBtn, Pos.BOTTOM_CENTER);
        StackPane.setMargin(latestBtn, new Insets(0, 0, 16, 0));
        latestBtn.setOnAction(e -> {
            if (viewModel.historyFocusProperty().get() > 0 || viewModel.errorProperty().get()) {
                viewModel.loadInitial();
            } else {
                scrollToBottom();
                updateLatestButton();
            }
        });

        getChildren().addAll(scrollPane, latestBtn);
        viewModel.items().addListener(new WeakListChangeListener<>(listListener));
        viewModel.historyFocusProperty().addListener(new WeakChangeListener<>(focusListener));
        viewModel.errorProperty().addListener(new WeakChangeListener<>(errorListener));

        scrollPane.vvalueProperty().addListener((obs, previous, value) -> {
            if (!restoringScroll && getScene() != null) {
                updateLatestButton();
                if (value.doubleValue() >= .95 && viewModel.historyFocusProperty().get() == 0) viewModel.markRead();
            }
        });

        focusChanged = true;
        scheduleRender();
    }

    /** A history page should render once, even when multiple observable values changed. */
    private void scheduleRender() {
        if (renderScheduled) return;
        renderScheduled = true;
        Platform.runLater(() -> {
            renderScheduled = false;
            if (getScene() != null) renderItems();
        });
    }

    private void renderItems() {
        boolean nearBottom = isNearBottom();
        double top = scrollPane.getVvalue() * scrollRange();
        MessageBubble anchor = messageBox.getChildren().stream()
                .filter(MessageBubble.class::isInstance)
                .map(MessageBubble.class::cast)
                .filter(bubble -> bubble.getBoundsInParent().getMaxY() >= top)
                .findFirst().orElse(null);
        double offset = anchor == null ? 0 : anchor.getBoundsInParent().getMinY() - top;
        boolean jump = focusChanged;
        focusChanged = false;

        restoringScroll = true;
        try {
            messageBox.getChildren().setAll(loadEarlierBtn, historyError);
            for (ChatListItem item : viewModel.items()) {
                switch (item) {
                    case DaySeparatorItem d -> messageBox.getChildren().add(new DaySeparator(d.label()));
                    case UnreadDividerItem u -> messageBox.getChildren().add(new UnreadDividerView(u.text()));
                    case SystemItem s -> {
                        Label label = new Label(s.text());
                        label.getStyleClass().add("system-text");
                        StackPane line = new StackPane(label);
                        line.getStyleClass().add("system-line");
                        messageBox.getChildren().add(line);
                    }
                    case MessageItem m -> messageBox.getChildren().add(
                            new MessageBubble(m, viewModel.type(), viewModel::retrySend, viewModel::saveAttachment));
                }
            }
            applyCss();
            layout();
            scrollPane.layout();

            long focusedId = viewModel.historyFocusProperty().get();
            if (jump && focusedId > 0) {
                highlightMessage(focusedId);
            } else if ((jump || nearBottom) && focusedId == 0) {
                scrollToBottom();
            } else if (anchor != null) {
                MessageBubble replacement = findBubble(anchor.getItem());
                scrollTo(replacement == null ? top : replacement.getBoundsInParent().getMinY() - offset);
            }
        } finally {
            restoringScroll = false;
        }
        updateLatestButton();
    }

    private MessageBubble findBubble(MessageItem item) {
        return messageBox.getChildren().stream()
                .filter(MessageBubble.class::isInstance)
                .map(MessageBubble.class::cast)
                .filter(bubble -> item.id() == null ? bubble.getItem() == item : item.id().equals(bubble.getItem().id()))
                .findFirst().orElse(null);
    }

    private double scrollRange() {
        return Math.max(0, stream.getHeight() - scrollPane.getViewportBounds().getHeight());
    }

    private void scrollTo(double top) {
        double range = scrollRange();
        scrollPane.setVvalue(range == 0 ? 0 : Math.clamp(top / range, 0, 1));
    }

    public boolean isNearBottom() {
        return scrollRange() <= 1 || scrollPane.getVvalue() >= .90;
    }

    public void scrollToBottom() {
        scrollPane.setVvalue(1);
        viewModel.markRead();
    }

    private void updateLatestButton() {
        latestBtn.setText(viewModel.errorProperty().get() ? "Retry history" : "Jump to latest");
        latestBtn.setVisible(viewModel.errorProperty().get()
                || viewModel.historyFocusProperty().get() > 0 || !isNearBottom());
    }

    private void highlightMessage(long messageId) {
        for (Node node : messageBox.getChildren()) {
            if (node instanceof MessageBubble bubble && bubble.getItem().id() != null
                    && bubble.getItem().id() == messageId) {
                scrollTo(node.getBoundsInParent().getMinY() - scrollPane.getViewportBounds().getHeight() / 3);
                node.getStyleClass().add("highlight-flash");
                PauseTransition highlight = new PauseTransition(Duration.seconds(2));
                highlight.setOnFinished(e -> node.getStyleClass().remove("highlight-flash"));
                highlight.play();
                break;
            }
        }
    }
}
