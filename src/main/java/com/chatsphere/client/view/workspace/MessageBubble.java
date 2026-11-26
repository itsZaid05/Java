package com.chatsphere.client.view.workspace;

import com.chatsphere.client.AppContext;
import com.chatsphere.client.ErrorMessages;
import com.chatsphere.client.component.Avatar;
import com.chatsphere.client.component.IconButton;
import com.chatsphere.client.component.Severity;
import com.chatsphere.client.view.workspace.state.ChatListItem.MessageItem;
import com.chatsphere.common.model.DeliveryStatus;
import com.chatsphere.common.model.TargetType;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import org.kordamp.ikonli.javafx.FontIcon;

import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
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

        if (item.attachmentSize() == null) {
            Label textLabel = new Label(item.text());
            textLabel.getStyleClass().add("message-text");
            textLabel.setWrapText(true);
            bubbleBox.getChildren().add(textLabel);
        } else {
            bubbleBox.getChildren().add(attachmentCard());
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
        Hyperlink retryLabel = new Hyperlink("Not sent. Retry");
        retryLabel.getStyleClass().add("bubble-failed-label");
        retryLabel.setOnAction(e -> {
            if (onRetry != null) onRetry.accept(item);
        });
        failedBox.getChildren().addAll(alertIcon, retryLabel);

        failedBox.visibleProperty().bind(item.statusProperty().isEqualTo(DeliveryStatus.FAILED));
        failedBox.managedProperty().bind(failedBox.visibleProperty());
        bubbleBox.getChildren().add(failedBox);

        getChildren().add(bubbleBox);
    }

    private HBox attachmentCard() {
        String name = item.text();
        int dot = name.lastIndexOf('.');
        String extension = dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
        boolean image = switch (extension) {
            case "png", "jpg", "jpeg", "gif", "webp", "bmp", "svg" -> true;
            default -> false;
        };
        FontIcon icon = new FontIcon(image ? "fth-image" : "fth-file");
        icon.setIconSize(18);
        icon.getStyleClass().add("attachment-icon");
        StackPane tile = new StackPane(icon);
        tile.getStyleClass().add("attachment-tile");

        Label fileName = new Label(name);
        fileName.getStyleClass().add("attachment-name");
        fileName.setTooltip(new Tooltip(name));
        fileName.setWrapText(true);
        Label details = new Label((image ? "Image" : "File") + " · " + formatSize(item.attachmentSize()));
        details.getStyleClass().add("attachment-details");
        VBox fileInfo = new VBox(2, fileName, details);
        fileInfo.setMinWidth(0);
        HBox.setHgrow(fileInfo, Priority.ALWAYS);

        IconButton save = new IconButton("fth-download", "Save " + name);
        save.getStyleClass().add("attachment-save");
        save.setOnAction(e -> chooseDestination(save));
        HBox row = new HBox(10, tile, fileInfo, save);
        row.getStyleClass().add("attachment-card");
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private static String formatSize(int size) {
        if (size < 1024) return size + " B";
        if (size < 1024 * 1024) return String.format(Locale.ROOT, "%.1f KiB", size / 1024.0);
        return String.format(Locale.ROOT, "%.1f MiB", size / (1024.0 * 1024));
    }

    private void chooseDestination(IconButton button) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Save attachment");
        chooser.setInitialFileName(item.text());
        var destination = chooser.showSaveDialog(getScene().getWindow());
        if (destination == null) return;
        button.setDisable(true);
        button.setIcon("fth-clock");
        saveAttachment.apply(item, destination.toPath()).whenComplete((ignored, error) -> {
            button.setDisable(false);
            button.setIcon("fth-download");
            if (error == null) AppContext.get().toast("Attachment saved.", Severity.SUCCESS);
            else AppContext.get().toast(ErrorMessages.of(error), Severity.ERROR);
        });
    }
}
