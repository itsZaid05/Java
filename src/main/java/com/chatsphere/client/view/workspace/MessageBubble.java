package com.chatsphere.client.view.workspace;

import com.chatsphere.client.AppContext;
import com.chatsphere.client.ErrorMessages;
import com.chatsphere.client.component.Avatar;
import com.chatsphere.client.component.Severity;
import com.chatsphere.client.view.workspace.state.ChatListItem.MessageItem;
import com.chatsphere.common.model.DeliveryStatus;
import com.chatsphere.common.model.TargetType;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.Hyperlink;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import org.kordamp.ikonli.javafx.FontIcon;

import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;
import java.util.function.Consumer;

/**
 * Renders a single message bubble (own or incoming).
 * Owned by Person 4.
 */
public class MessageBubble extends HBox {
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");

    private final MessageItem item;
    private final TargetType targetType;
    private final Consumer<MessageItem> onRetry;

    private final BiFunction<MessageItem, Path, CompletableFuture<Void>> saveAttachment;

    public MessageBubble(MessageItem item, TargetType targetType, Consumer<MessageItem> onRetry,
                         BiFunction<MessageItem, Path, CompletableFuture<Void>> saveAttachment) {
        this.item = item;
        this.targetType = targetType;
        this.onRetry = onRetry;
        this.saveAttachment = saveAttachment;

        getStyleClass().add("message-bubble-row");
        if (item.isOwn()) {
            getStyleClass().add("own");
            setAlignment(Pos.TOP_RIGHT);
        } else {
            setAlignment(Pos.TOP_LEFT);
        }

        buildUI();
    }

    public MessageItem getItem() { return item; }

    private void buildUI() {
        getChildren().clear();

        // Avatar for incoming group header
        if (!item.isOwn()) {
            VBox avatarBox = new VBox();
            avatarBox.setPrefWidth(32);
            avatarBox.setMinWidth(32);
            avatarBox.setAlignment(Pos.TOP_CENTER);

            if (item.isFirstInGroup()) {
                Avatar avatar = new Avatar(item.sender(), 32);
                avatarBox.getChildren().add(avatar);
            }
            getChildren().add(avatarBox);
        }

        // Bubble content box
        VBox bubbleBox = new VBox();
        bubbleBox.getStyleClass().add("message-bubble");
        bubbleBox.getStyleClass().add(item.isOwn() ? "outgoing" : "incoming");

        // Sender name above bubble (in rooms for incoming firstInGroup)
        if (!item.isOwn() && targetType == TargetType.ROOM && item.isFirstInGroup()) {
            Label senderLabel = new Label(item.sender());
            senderLabel.getStyleClass().add("sender-name-label");
            bubbleBox.getChildren().add(senderLabel);
        }

        // Message text
        Label textLabel = new Label(item.text());
        textLabel.getStyleClass().add("message-text");
        textLabel.setWrapText(true);
        textLabel.setMaxWidth(520);
        bubbleBox.getChildren().add(textLabel);
        if (item.attachmentSize() != null) {
            Hyperlink download = new Hyperlink("Save attachment…");
            download.setOnAction(e -> chooseDestination(download));
            bubbleBox.getChildren().add(download);
        }

        // Meta row: time + tick
        HBox metaRow = new HBox();
        metaRow.getStyleClass().add("message-meta-row");

        String timeStr = Instant.ofEpochMilli(item.timestamp()).atZone(ZoneId.systemDefault()).format(TIME_FMT);
        Label timeLabel = new Label(timeStr);
        timeLabel.getStyleClass().add("message-time-label");
        metaRow.getChildren().add(timeLabel);

        // Ticks for own private messages only
        if (item.isOwn() && targetType == TargetType.PRIVATE) {
            StatusTick tick = new StatusTick();
            tick.statusProperty().bind(item.statusProperty());
            metaRow.getChildren().add(tick);
        }

        bubbleBox.getChildren().add(metaRow);

        // Failed state retry link
        HBox failedBox = new HBox();
        failedBox.getStyleClass().add("bubble-failed-box");
        FontIcon alertIcon = new FontIcon("fth-alert-circle");
        alertIcon.setStyle("-fx-icon-color: -cs-danger;");
        Label retryLabel = new Label("Not sent. Retry");
        retryLabel.getStyleClass().add("bubble-failed-label");
        retryLabel.setOnMouseClicked(e -> {
            if (onRetry != null) onRetry.accept(item);
        });
        failedBox.getChildren().addAll(alertIcon, retryLabel);

        failedBox.visibleProperty().bind(item.statusProperty().isEqualTo(DeliveryStatus.FAILED));
        failedBox.managedProperty().bind(failedBox.visibleProperty());
        bubbleBox.getChildren().add(failedBox);

        getChildren().add(bubbleBox);
    }

    private void chooseDestination(Hyperlink link) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Save attachment");
        chooser.setInitialFileName(item.text());
        var destination = chooser.showSaveDialog(getScene().getWindow());
        if (destination == null) return;
        link.setDisable(true);
        link.setText("Saving…");
        saveAttachment.apply(item, destination.toPath()).whenComplete((ignored, error) -> {
            link.setDisable(false);
            link.setText("Save attachment…");
            if (error == null) AppContext.get().toast("Attachment saved.", Severity.SUCCESS);
            else AppContext.get().toast(ErrorMessages.of(error), Severity.ERROR);
        });
    }
}
