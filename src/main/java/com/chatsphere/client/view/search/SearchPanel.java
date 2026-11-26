package com.chatsphere.client.view.search;

import com.chatsphere.client.AppContext;
import com.chatsphere.client.component.Banner;
import com.chatsphere.client.component.IconButton;
import com.chatsphere.client.component.SegmentedTabs;
import com.chatsphere.client.component.Severity;
import com.chatsphere.client.component.Spinner;
import com.chatsphere.client.state.SearchSnippet;
import com.chatsphere.client.state.SearchViewModel;
import com.chatsphere.client.state.SearchViewModel.Scope;
import com.chatsphere.client.theme.Spacing;
import com.chatsphere.client.theme.ThemeManager;
import com.chatsphere.common.model.SearchHit;
import com.chatsphere.common.model.TargetType;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import javafx.util.Duration;

import java.time.ZoneId;
import java.util.function.Consumer;

/**
 * Owned by Person 3. The Search panel (Person 4 hosts it in the right slot, 280 wide). Keep the public API.
 * Query field (debounced 300 ms, Enter searches now), sender and date filters, scope toggle, results list.
 * States: idle hint, loading, results, empty, error with Retry (C8).
 */
public class SearchPanel extends VBox {
    private static final double WIDTH = 280;

    private final SearchViewModel vm;
    private final Consumer<SearchHit> onHitSelected;
    private final Runnable onClose;

    private final TextField query = new TextField();
    private final ComboBox<String> sender = new ComboBox<>();
    private final DatePicker from = new DatePicker();
    private final DatePicker to = new DatePicker();
    private final SegmentedTabs scopeTabs = new SegmentedTabs("Current chat", "All chats");
    private final ListView<SearchHit> list = new ListView<>();
    private final StackPane content = new StackPane();
    private final PauseTransition debounce = new PauseTransition(Duration.millis(300));

    /** onHitSelected: user clicked a result (Person 4 jumps to it). onClose: user pressed the close button or Esc. */
    public SearchPanel(Consumer<SearchHit> onHitSelected, Runnable onClose) {
        this.onHitSelected = onHitSelected;
        this.onClose = onClose;
        this.vm = new SearchViewModel(AppContext.get().client(), ZoneId.systemDefault());

        getStylesheets().add(ThemeManager.css("search.css"));
        getStyleClass().addAll("search-panel", "surface");
        setPrefWidth(WIDTH);
        setMinWidth(WIDTH);
        setMaxWidth(WIDTH);

        getChildren().addAll(header(), controls(), content);
        VBox.setVgrow(content, Priority.ALWAYS);

        configureList();
        wire();
        renderState();
        addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.ESCAPE) {
                onClose.run();
                e.consume();
            }
        });
    }

    // ------------------------------------------------------------------ public API
    /** Puts the cursor in the query field (and loads the sender list the first time). */
    public void focusQuery() {
        vm.loadUsers();
        Platform.runLater(() -> {
            query.requestFocus();
            query.selectAll();
        });
    }

    /** Pre-selects the current chat. Pass a null target to search all chats. */
    public void setCurrentConversation(TargetType type, String target) {
        vm.setCurrent(type, target);
    }

    // ------------------------------------------------------------------ layout
    private Node header() {
        Label title = new Label("Search");
        title.getStyleClass().add("subtitle");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        IconButton close = new IconButton("fth-x", "Close search");
        close.setOnAction(e -> onClose.run());
        HBox row = new HBox(Spacing.SPACE_2, title, spacer, close);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPrefHeight(64);
        row.setMinHeight(64);
        row.setPadding(new Insets(0, Spacing.SPACE_3, 0, Spacing.SPACE_4));
        row.getStyleClass().add("border-bottom");
        return row;
    }

    private Node controls() {
        query.setPromptText("Search messages");
        query.setAccessibleText("Search messages");

        sender.setItems(vm.senders());
        sender.setMaxWidth(Double.MAX_VALUE);
        sender.setAccessibleText("Sender");
        Label senderLabel = fieldLabel("From person");

        from.setPromptText("From");
        from.setAccessibleText("From date");
        to.setPromptText("To");
        to.setAccessibleText("To date");
        from.setMaxWidth(Double.MAX_VALUE);
        to.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(from, Priority.ALWAYS);
        HBox.setHgrow(to, Priority.ALWAYS);
        HBox dates = new HBox(Spacing.SPACE_2, from, to);

        scopeTabs.setMaxWidth(Double.MAX_VALUE);

        VBox box = new VBox(Spacing.SPACE_2, query, senderLabel, sender, fieldLabel("Dates"), dates, scopeTabs);
        box.setPadding(new Insets(Spacing.SPACE_3, Spacing.SPACE_4, Spacing.SPACE_3, Spacing.SPACE_4));
        box.getStyleClass().add("border-bottom");
        return box;
    }

    private static Label fieldLabel(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("form-label");
        return l;
    }

    // ------------------------------------------------------------------ wiring
    private void wire() {
        // view <-> view-model
        query.textProperty().bindBidirectional(vm.queryProperty());
        sender.valueProperty().bindBidirectional(vm.senderProperty());
        from.valueProperty().bindBidirectional(vm.fromProperty());
        to.valueProperty().bindBidirectional(vm.toProperty());

        // typing: wait 300 ms; Enter: search now
        query.textProperty().addListener((o, a, b) -> debounce.playFromStart());
        debounce.setOnFinished(e -> vm.search());
        query.setOnAction(e -> {
            debounce.stop();
            vm.search();
        });

        // any filter change re-runs the search when there is a keyword
        sender.valueProperty().addListener((o, a, b) -> vm.searchIfActive());
        from.valueProperty().addListener((o, a, b) -> vm.searchIfActive());
        to.valueProperty().addListener((o, a, b) -> vm.searchIfActive());

        scopeTabs.selectedIndexProperty().set(vm.scopeProperty().get() == Scope.CURRENT ? 0 : 1);
        scopeTabs.setSegmentDisabled(0, !vm.hasCurrentProperty().get());
        scopeTabs.selectedIndexProperty().addListener((o, a, b) -> {
            vm.scopeProperty().set(b.intValue() == 0 ? Scope.CURRENT : Scope.ALL);
            vm.searchIfActive();
        });
        vm.scopeProperty().addListener((o, a, b) -> scopeTabs.selectedIndexProperty().set(b == Scope.CURRENT ? 0 : 1));
        vm.hasCurrentProperty().addListener((o, a, b) -> scopeTabs.setSegmentDisabled(0, !b));

        vm.stateProperty().addListener((o, a, b) -> renderState());
    }

    private void renderState() {
        Node view = switch (vm.state()) {
            case IDLE -> hint("Search your messages", "Type a keyword. Use the filters to narrow it down.");
            case LOADING -> loading();
            case RESULTS -> list;
            case EMPTY -> hint("No messages match your search", "Try another keyword or widen the filters.");
            case ERROR -> error();
        };
        content.getChildren().setAll(view);
    }

    private static Node hint(String title, String body) {
        Label t = new Label(title);
        t.getStyleClass().add("body-strong");
        t.setWrapText(true);
        t.setAlignment(Pos.CENTER);
        Label b = new Label(body);
        b.getStyleClass().addAll("small", "text-secondary");
        b.setWrapText(true);
        b.setAlignment(Pos.CENTER);
        VBox box = new VBox(Spacing.SPACE_1, t, b);
        box.setAlignment(Pos.CENTER);
        box.setPadding(new Insets(Spacing.SPACE_5));
        return box;
    }

    private static Node loading() {
        Label l = new Label("Searching...");
        l.getStyleClass().addAll("small", "text-secondary");
        HBox box = new HBox(Spacing.SPACE_2, new Spinner(20), l);
        box.setAlignment(Pos.CENTER);
        return box;
    }

    private Node error() {
        Banner b = new Banner(Severity.ERROR, vm.errorTextProperty().get());
        b.setAction("Retry", vm::search);
        VBox box = new VBox(b);
        box.setPadding(new Insets(Spacing.SPACE_4));
        box.setAlignment(Pos.TOP_CENTER);
        return box;
    }

    // ------------------------------------------------------------------ results
    private void configureList() {
        list.getStyleClass().add("search-list");
        list.setItems(vm.results());
        list.setPlaceholder(new Label());
        list.setCellFactory(lv -> new HitCell());
        list.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER && list.getSelectionModel().getSelectedItem() != null) {
                onHitSelected.accept(list.getSelectionModel().getSelectedItem());
                e.consume();
            }
        });
    }

    /** One result: sender, date, scope label and a snippet with the keyword marked. */
    private final class HitCell extends ListCell<SearchHit> {
        HitCell() {
            setOnMouseClicked(e -> {
                if (!isEmpty() && getItem() != null) onHitSelected.accept(getItem());
            });
        }

        @Override
        protected void updateItem(SearchHit hit, boolean empty) {
            super.updateItem(hit, empty);
            if (empty || hit == null) {
                setGraphic(null);
                setText(null);
                setAccessibleText(null);
                return;
            }
            Label who = new Label(hit.sender());
            who.getStyleClass().add("body-strong");
            who.setMinWidth(0);
            Label when = new Label(SearchViewModel.formatDate(hit.timestamp(), vm.zone()));
            when.getStyleClass().addAll("caption", "text-tertiary");
            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);
            HBox top = new HBox(Spacing.SPACE_2, who, spacer, when);
            top.setAlignment(Pos.CENTER_LEFT);

            Label where = new Label(SearchViewModel.scopeLabel(hit));
            where.getStyleClass().addAll("small", "text-secondary");

            TextFlow snippet = new TextFlow();
            snippet.getStyleClass().add("search-snippet-flow");
            snippet.prefWidthProperty().bind(list.widthProperty().subtract(2 * Spacing.SPACE_4 + 16));
            snippet.setMaxWidth(Region.USE_PREF_SIZE);
            StringBuilder plain = new StringBuilder();
            for (SearchSnippet.Segment seg : SearchSnippet.of(hit.text(), vm.lastKeyword(), 40, 100)) {
                plain.append(seg.text());
                if (seg.match()) {
                    Label mark = new Label(seg.text());
                    mark.getStyleClass().add("search-mark");
                    snippet.getChildren().add(mark);
                } else {
                    Text t = new Text(seg.text());
                    t.getStyleClass().add("search-snippet");
                    snippet.getChildren().add(t);
                }
            }

            VBox box = new VBox(Spacing.SPACE_1, top, where, snippet);
            setText(null);
            setGraphic(box);
            setAccessibleText(hit.sender() + ", " + SearchViewModel.scopeLabel(hit) + ", " + plain);
        }
    }
}
