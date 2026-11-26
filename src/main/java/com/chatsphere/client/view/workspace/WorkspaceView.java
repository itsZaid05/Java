package com.chatsphere.client.view.workspace;

import com.chatsphere.client.AppContext;
import com.chatsphere.client.nav.Disposable;
import com.chatsphere.client.theme.ThemeManager;
import com.chatsphere.client.view.search.SearchPanel;
import com.chatsphere.client.view.workspace.state.ConversationItem;
import com.chatsphere.client.view.workspace.state.WorkspaceViewModel;
import com.chatsphere.client.view.workspace.state.WorkspaceViewModel.RightSlotMode;
import com.chatsphere.common.model.SearchHit;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

/**
 * Top-level Workspace screen composing Sidebar, ConversationPane and RightSlot.
 * Owned by Person 4.
 */
public class WorkspaceView extends BorderPane implements Disposable {
    private final WorkspaceViewModel viewModel;
    private final Sidebar sidebar;
    private final ConversationPane conversationPane;
    private final StackPane rightSlot = new StackPane();

    public WorkspaceView() {
        this(new WorkspaceViewModel(AppContext.get().client(), AppContext.get().session()));
    }

    public WorkspaceView(WorkspaceViewModel viewModel) {
        this.viewModel = viewModel;
        getStyleClass().add("workspace-root");
        getStylesheets().add(ThemeManager.css("workspace.css"));

        this.sidebar = new Sidebar(viewModel);
        this.conversationPane = new ConversationPane(viewModel);

        setLeft(sidebar);
        setCenter(conversationPane);
        setRight(rightSlot);

        rightSlot.setVisible(false);
        rightSlot.setManaged(false);

        viewModel.rightSlotModeProperty().addListener((obs, oldVal, newVal) -> updateRightSlot(newVal));
        viewModel.selectedProperty().addListener((obs, previous, selected) -> {
            if (viewModel.rightSlotMode() == RightSlotMode.DETAILS) {
                updateRightSlot(RightSlotMode.DETAILS);
            } else if (!rightSlot.getChildren().isEmpty()
                    && rightSlot.getChildren().getFirst() instanceof SearchPanel search) {
                search.setCurrentConversation(selected == null ? null : selected.type(),
                        selected == null ? null : selected.key());
            }
        });

        addEventFilter(KeyEvent.KEY_PRESSED, this::handleGlobalKey);

        Platform.runLater(() -> {
            if (getScene() != null && getScene().getWindow() instanceof Stage stage) {
                stage.titleProperty().bind(Bindings.createStringBinding(() -> {
                    int unread = AppContext.get().session().totalUnreadProperty().get();
                    return (unread > 0) ? "(" + unread + ") ChatSphere" : "ChatSphere";
                }, AppContext.get().session().totalUnreadProperty()));
            }
        });

        viewModel.start();
    }

    private void updateRightSlot(RightSlotMode mode) {
        rightSlot.getChildren().clear();
        if (mode == RightSlotMode.NONE || mode == null) {
            rightSlot.setVisible(false);
            rightSlot.setManaged(false);
        } else if (mode == RightSlotMode.DETAILS) {
            ConversationItem sel = viewModel.selected();
            if (sel != null) {
                rightSlot.getChildren().add(new DetailsPane(sel, viewModel));
                rightSlot.setVisible(true);
                rightSlot.setManaged(true);
            } else {
                rightSlot.setVisible(false);
                rightSlot.setManaged(false);
            }
        } else if (mode == RightSlotMode.SEARCH) {
            SearchPanel searchPanel = new SearchPanel(this::onSearchHitSelected, () -> viewModel.setRightSlotMode(RightSlotMode.NONE));
            ConversationItem sel = viewModel.selected();
            if (sel != null) {
                searchPanel.setCurrentConversation(sel.type(), sel.key());
            }
            rightSlot.getChildren().add(searchPanel);
            rightSlot.setVisible(true);
            rightSlot.setManaged(true);
            searchPanel.focusQuery();
        }
    }

    private void onSearchHitSelected(SearchHit hit) {
        ConversationItem item = viewModel.findItem(hit.targetType(), hit.target());
        if (item != null) {
            viewModel.setSelected(item);
            var cvm = viewModel.getOrCreateConversationViewModel(hit.targetType(), hit.target());
            cvm.jumpToMessage(hit.id());
        }
    }

    private void handleGlobalKey(KeyEvent e) {
        if (e.isControlDown() && e.getCode() == KeyCode.F) {
            viewModel.setRightSlotMode(RightSlotMode.SEARCH);
            e.consume();
        } else if (e.isControlDown() && e.getCode() == KeyCode.N) {
            AppContext.get().dialogs().showCreateRoom(name -> viewModel.refreshRooms());
            e.consume();
        } else if (e.getCode() == KeyCode.ESCAPE) {
            if (viewModel.rightSlotMode() != RightSlotMode.NONE) {
                viewModel.setRightSlotMode(RightSlotMode.NONE);
                e.consume();
            }
        } else if (e.isAltDown() && e.getCode() == KeyCode.UP) {
            viewModel.selectPreviousConversation();
            e.consume();
        } else if (e.isAltDown() && e.getCode() == KeyCode.DOWN) {
            viewModel.selectNextConversation();
            e.consume();
        }
    }

    @Override
    public void dispose() {
        viewModel.dispose();
    }
}
