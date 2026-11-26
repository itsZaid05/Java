package com.chatsphere.client.view.workspace;

import com.chatsphere.client.view.workspace.state.ConversationViewModel;
import javafx.collections.SetChangeListener;
import javafx.collections.WeakSetChangeListener;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;

import java.util.ArrayList;
import java.util.List;

/**
 * 20px high typing indicator strip.
 * Owned by Person 4.
 */
public class TypingStrip extends HBox {
    private final ConversationViewModel viewModel;
    private final Label typingLabel = new Label();
    private final SetChangeListener<String> typingListener = change -> updateTypingText();

    public TypingStrip(ConversationViewModel viewModel) {
        this.viewModel = viewModel;
        getStyleClass().add("typing-strip");

        typingLabel.getStyleClass().add("typing-text");
        getChildren().add(typingLabel);

        viewModel.typingUsers().addListener(new WeakSetChangeListener<>(typingListener));
        updateTypingText();
    }

    private void updateTypingText() {
        List<String> list = new ArrayList<>(viewModel.typingUsers());
        if (list.isEmpty()) {
            typingLabel.setText("");
        } else if (list.size() == 1) {
            typingLabel.setText(list.get(0) + " is typing...");
        } else if (list.size() == 2) {
            typingLabel.setText(list.get(0) + " and " + list.get(1) + " are typing...");
        } else {
            typingLabel.setText("Several people are typing...");
        }
    }
}
