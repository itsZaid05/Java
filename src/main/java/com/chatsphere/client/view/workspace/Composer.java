package com.chatsphere.client.view.workspace;

import com.chatsphere.client.AppContext;
import com.chatsphere.client.ErrorMessages;
import com.chatsphere.client.component.AppButton;
import com.chatsphere.client.component.IconButton;
import com.chatsphere.client.component.Severity;
import com.chatsphere.client.view.workspace.state.ConversationViewModel;
import com.chatsphere.common.model.TargetType;
import javafx.beans.binding.Bindings;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import org.kordamp.ikonli.javafx.FontIcon;

/**
 * Message composer: multiline text input, character counter, send button.
 * Owned by Person 4.
 */
public class Composer extends VBox {
    private final ConversationViewModel viewModel;
    private final TextArea input = new TextArea();
    private final IconButton sendBtn = new IconButton("fth-send", "Send");
    private final Label counterLabel = new Label();
    private final HBox offlineHintBox = new HBox();

    public Composer(ConversationViewModel viewModel, boolean peerOnline) {
        this.viewModel = viewModel;
        getStyleClass().add("composer");

        if (viewModel.type() == TargetType.PRIVATE && !peerOnline) {
            offlineHintBox.getStyleClass().add("offline-hint-box");
            FontIcon infoIcon = new FontIcon("fth-info");
            infoIcon.setStyle("-fx-icon-color: -cs-text-tertiary;");
            Label hint = new Label(viewModel.target() + " is offline. They'll get this when they sign in.");
            hint.getStyleClass().add("offline-hint-text");
            offlineHintBox.getChildren().addAll(infoIcon, hint);
            getChildren().add(offlineHintBox);
        }

        HBox inputBox = new HBox();
        inputBox.getStyleClass().add("composer-input-box");
        inputBox.setAlignment(Pos.BOTTOM_RIGHT);
        inputBox.setSpacing(8);

        String placeholder = (viewModel.type() == TargetType.ROOM)
                ? "Message #" + viewModel.target()
                : "Message " + viewModel.target();
        input.setPromptText(placeholder);
        input.getStyleClass().add("composer-textarea");
        HBox.setHgrow(input, Priority.ALWAYS);

        counterLabel.getStyleClass().add("composer-counter");
        counterLabel.setVisible(false);
        counterLabel.setManaged(false);

        sendBtn.disableProperty().bind(Bindings.createBooleanBinding(
                () -> input.getText().trim().isEmpty(),
                input.textProperty()
        ));
        sendBtn.setOnAction(e -> doSend());

        input.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.ENTER) {
                if (e.isShiftDown()) {
                    input.insertText(input.getCaretPosition(), "\n");
                } else {
                    doSend();
                }
                e.consume();
            }
        });

        input.textProperty().addListener((obs, oldVal, newVal) -> {
            int len = newVal.length();
            if (len >= 1800) {
                counterLabel.setText(len + " / 2000");
                counterLabel.setVisible(true);
                counterLabel.setManaged(true);
                if (len >= 2000) {
                    counterLabel.getStyleClass().add("warning");
                } else {
                    counterLabel.getStyleClass().remove("warning");
                }
            } else {
                counterLabel.setVisible(false);
                counterLabel.setManaged(false);
            }

            if (!newVal.isBlank()) {
                viewModel.sendTyping();
            }
        });

        if (viewModel.supportsAttachments()) {
            AppButton attach = new AppButton("Attach", AppButton.Variant.GHOST).withIcon("fth-paperclip");
            attach.setTooltip(new javafx.scene.control.Tooltip("Send a file or image up to 5 MiB"));
            attach.setOnAction(e -> chooseAttachment(attach));
            inputBox.getChildren().add(attach);
        }
        inputBox.getChildren().addAll(input, counterLabel, sendBtn);
        getChildren().add(inputBox);
    }

    private void chooseAttachment(AppButton button) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Send a file or image");
        var selected = chooser.showOpenDialog(getScene().getWindow());
        if (selected == null) return;
        button.setDisable(true);
        button.setText("Sending…");
        viewModel.sendAttachment(selected.toPath()).whenComplete((ignored, error) -> {
            button.setDisable(false);
            button.setText("Attach");
            if (error != null) AppContext.get().toast(ErrorMessages.of(error), Severity.ERROR);
            else AppContext.get().toast("Attachment sent.", Severity.SUCCESS);
        });
    }

    private void doSend() {
        String text = input.getText();
        if (text == null || text.isBlank()) return;
        viewModel.sendMessage(text);
        input.clear();
    }

    public void focus() {
        input.requestFocus();
    }
}
