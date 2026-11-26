package com.chatsphere.client.view.workspace;

import com.chatsphere.client.component.Avatar;
import com.chatsphere.client.component.UnreadBadge;
import com.chatsphere.client.view.workspace.state.ConversationItem;
import com.chatsphere.common.model.TargetType;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * 56px high row in the sidebar list.
 * Owned by Person 4.
 */
public class ConversationRow extends HBox {
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("d MMM");

    private final ConversationItem item;

    public ConversationRow(ConversationItem item, boolean isSelected) {
        this.item = item;
        getStyleClass().add("conversation-row");
        if (isSelected) getStyleClass().add("selected");
        if (item.unread() > 0) getStyleClass().add("unread");

        setAlignment(Pos.CENTER_LEFT);
        setSpacing(12);

        if (item.type() == TargetType.ROOM) {
            StackPane tile = new StackPane();
            tile.getStyleClass().add("room-tile");
            Label hash = new Label("#");
            hash.getStyleClass().add("room-symbol");
            tile.getChildren().add(hash);
            getChildren().add(tile);
        } else {
            Avatar avatar = new Avatar(item.key(), 32);
            avatar.setShowPresence(true);
            avatar.onlineProperty().bind(item.onlineProperty());
            getChildren().add(avatar);
        }

        VBox textCol = new VBox();
        textCol.setAlignment(Pos.CENTER_LEFT);
        textCol.setSpacing(2);
        HBox.setHgrow(textCol, Priority.ALWAYS);

        HBox topRow = new HBox(8);
        topRow.setAlignment(Pos.CENTER_LEFT);
        Label titleLabel = new Label(item.title());
        titleLabel.getStyleClass().add("row-title");
        titleLabel.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(titleLabel, Priority.ALWAYS);

        Label timeLabel = new Label();
        timeLabel.getStyleClass().add("row-time");
        if (item.lastTimestamp() > 0) {
            timeLabel.setText(formatTime(item.lastTimestamp()));
        }
        item.lastTimestampProperty().addListener((obs, oldVal, newVal) -> {
            timeLabel.setText(newVal.longValue() > 0 ? formatTime(newVal.longValue()) : "");
        });
        topRow.getChildren().addAll(titleLabel, timeLabel);

        HBox bottomRow = new HBox(8);
        bottomRow.setAlignment(Pos.CENTER_LEFT);
        Label previewLabel = new Label();
        previewLabel.getStyleClass().add("row-preview");
        previewLabel.textProperty().bind(item.previewProperty());
        previewLabel.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(previewLabel, Priority.ALWAYS);

        UnreadBadge badge = new UnreadBadge();
        badge.countProperty().bind(item.unreadProperty());

        bottomRow.getChildren().addAll(previewLabel, badge);
        textCol.getChildren().addAll(topRow, bottomRow);

        getChildren().add(textCol);
    }

    private String formatTime(long ts) {
        LocalDate date = Instant.ofEpochMilli(ts).atZone(ZoneId.systemDefault()).toLocalDate();
        LocalDate today = LocalDate.now();
        if (date.equals(today)) {
            return Instant.ofEpochMilli(ts).atZone(ZoneId.systemDefault()).format(TIME_FMT);
        }
        return date.format(DATE_FMT);
    }
}
