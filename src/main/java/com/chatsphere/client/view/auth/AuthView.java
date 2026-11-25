package com.chatsphere.client.view.auth;

import com.chatsphere.client.AppContext;
import com.chatsphere.client.component.AppButton;
import com.chatsphere.client.component.Banner;
import com.chatsphere.client.component.FormField;
import com.chatsphere.client.component.PasswordField;
import com.chatsphere.client.component.Severity;
import com.chatsphere.client.nav.Disposable;
import com.chatsphere.client.net.ConnectionState;
import com.chatsphere.client.state.AuthViewModel;
import com.chatsphere.client.state.AuthViewModel.Field;
import com.chatsphere.client.state.AuthViewModel.Mode;
import com.chatsphere.client.theme.Spacing;
import com.chatsphere.client.theme.ThemeManager;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.*;
import javafx.scene.shape.Circle;

/**
 * Owned by Person 3. The Auth scene (contract C5): brand panel 40% | form panel 60%.
 * All logic lives in AuthViewModel; this class only builds nodes and wires them.
 */
public class AuthView extends GridPane implements Disposable {
    private final AuthViewModel vm;
    private final FormField username = new FormField("Username", "Username");
    private final PasswordField password = new PasswordField("Password", "Password");
    private final PasswordField confirm = new PasswordField("Confirm password", "Confirm password");
    private final FormField host = new FormField("Host", "localhost");
    private final FormField port = new FormField("Port", "5050");
    private boolean autofocused;

    public AuthView(String notice) {
        AppContext ctx = AppContext.get();
        vm = new AuthViewModel(ctx.client(), ctx.session(), ctx.config(), new AuthViewModel.Actions() {
            @Override public void toast(String text, Severity severity) { ctx.toast(text, severity); }
            @Override public void authenticated(String user) { ctx.nav().showWorkspace(); }
        });
        vm.noticeProperty().set(notice == null ? "" : notice);

        getStylesheets().add(ThemeManager.css("auth.css"));
        getStyleClass().add("auth-view");
        ColumnConstraints left = new ColumnConstraints();
        left.setPercentWidth(40);
        ColumnConstraints right = new ColumnConstraints();
        right.setPercentWidth(60);
        getColumnConstraints().addAll(left, right);
        RowConstraints row = new RowConstraints();
        row.setVgrow(Priority.ALWAYS);
        row.setFillHeight(true);
        getRowConstraints().add(row);
        add(buildBrandPanel(), 0, 0);
        add(buildFormPanel(), 1, 0);

        wireFields();
        vm.setFocusHandler(this::focus);
        vm.connectIfNeeded();
        Platform.runLater(this::autofocusIfConnected);
        vm.connectionProperty().addListener((o, was, now) -> autofocusIfConnected());
    }

    // ------------------------------------------------------------------ brand panel
    private Node buildBrandPanel() {
        Label wordmark = new Label("ChatSphere");
        wordmark.getStyleClass().addAll("title", "auth-brand-wordmark");

        Label statement = new Label("Conversations that wait for you.");
        statement.getStyleClass().addAll("display", "auth-brand-statement");
        statement.setWrapText(true);

        VBox facts = new VBox(Spacing.SPACE_2,
                fact("Rooms and private messages"),
                fact("Messages saved and delivered when you return"),
                fact("See who is online and who is typing"));

        VBox center = new VBox(Spacing.SPACE_5, statement, facts);
        center.setAlignment(Pos.CENTER_LEFT);

        BorderPane brand = new BorderPane();
        brand.getStyleClass().add("auth-brand");
        brand.setPadding(new Insets(Spacing.SPACE_7));
        brand.setTop(wordmark);
        brand.setCenter(center);
        return brand;
    }

    private static Label fact(String text) {
        Label l = new Label(text);
        l.getStyleClass().addAll("body", "auth-brand-fact");
        l.setWrapText(true);
        return l;
    }

    // ------------------------------------------------------------------ form panel
    private Node buildFormPanel() {
        Label heading = new Label();
        heading.getStyleClass().add("display");
        heading.textProperty().bind(Bindings.when(vm.modeProperty().isEqualTo(Mode.LOGIN))
                .then("Sign in").otherwise("Create account"));
        Label sub = new Label();
        sub.getStyleClass().addAll("body", "text-secondary");
        sub.textProperty().bind(Bindings.when(vm.modeProperty().isEqualTo(Mode.LOGIN))
                .then("Welcome back. Sign in to continue.").otherwise("Choose a username and password."));

        Banner notice = banner(Severity.WARNING, vm.noticeProperty());
        Banner error = banner(Severity.ERROR, vm.errorProperty());

        AppButton submit = new AppButton("Sign in").large();
        submit.setMaxWidth(Double.MAX_VALUE);
        submit.disableProperty().bind(vm.connectedBinding().not());   // pending is handled by AppButton itself
        submit.pendingProperty().bind(vm.pendingProperty());
        submit.setOnAction(e -> vm.submit());

        Hyperlink modeLink = new Hyperlink();
        modeLink.textProperty().bind(Bindings.when(vm.modeProperty().isEqualTo(Mode.LOGIN))
                .then("New here? Create an account").otherwise("Have an account? Sign in"));
        modeLink.disableProperty().bind(vm.pendingProperty());
        modeLink.setOnAction(e -> { vm.toggleMode(); username.requestInputFocus(); });

        VBox advanced = buildAdvanced();

        VBox form = new VBox(Spacing.SPACE_4, heading, sub, notice, error, username, password, confirm, submit, modeLink, advanced);
        form.getStyleClass().add("auth-form");
        form.setPrefWidth(360);
        form.setMinWidth(360);
        form.setMaxWidth(360);
        form.setAlignment(Pos.TOP_LEFT);
        VBox.setMargin(modeLink, new Insets(-Spacing.SPACE_2, 0, 0, 0));

        // mode-dependent bits
        vm.modeProperty().addListener((o, a, b) -> applyMode(submit));
        applyMode(submit);

        StackPane centered = new StackPane(form);
        centered.setPadding(new Insets(Spacing.SPACE_7, Spacing.SPACE_5, Spacing.SPACE_7, Spacing.SPACE_5));
        ScrollPane scroll = new ScrollPane(centered);
        scroll.getStyleClass().add("auth-scroll");
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        centered.minHeightProperty().bind(scroll.heightProperty().subtract(2));

        HBox chip = buildChip();
        StackPane panel = new StackPane(scroll, chip);
        panel.getStyleClass().add("auth-form-panel");
        StackPane.setAlignment(chip, Pos.TOP_RIGHT);
        StackPane.setMargin(chip, new Insets(Spacing.SPACE_5));
        chip.setPickOnBounds(false);
        return panel;
    }

    private Banner banner(Severity severity, javafx.beans.property.StringProperty text) {
        Banner b = new Banner(severity, text.get());
        Runnable refresh = () -> {
            boolean has = !text.get().isEmpty();
            b.setText(text.get());
            b.setVisible(has);
            b.setManaged(has);
        };
        text.addListener((o, a, c) -> refresh.run());
        refresh.run();
        return b;
    }

    private void applyMode(AppButton submit) {
        boolean register = vm.mode() == Mode.REGISTER;
        submit.setText(register ? "Create account" : "Sign in");
        confirm.setVisible(register);
        confirm.setManaged(register);
        password.setStrengthHint(register);
        password.setHelper(register ? "At least 8 characters" : "");
    }

    private VBox buildAdvanced() {
        AppButton toggle = new AppButton("Connection settings", AppButton.Variant.GHOST).withIcon("fth-chevron-right");
        toggle.getStyleClass().add("disclosure");
        toggle.setAlignment(Pos.CENTER_LEFT);
        toggle.setOnAction(e -> vm.advancedExpandedProperty().set(!vm.advancedExpandedProperty().get()));

        host.input().getStyleClass().add("mono");
        port.input().getStyleClass().add("mono");
        host.textProperty().bindBidirectional(vm.hostProperty());
        port.textProperty().bindBidirectional(vm.portProperty());
        host.setOnSubmit(vm::retry);
        port.setOnSubmit(vm::retry);
        port.setPrefWidth(96);
        port.setMinWidth(96);
        HBox.setHgrow(host, Priority.ALWAYS);
        HBox hostPort = new HBox(Spacing.SPACE_2, host, port);
        hostPort.setAlignment(Pos.TOP_LEFT);
        hostPort.setPadding(new Insets(Spacing.SPACE_1, 0, 0, 0));

        vm.hostErrorProperty().addListener((o, a, b) -> host.setError(b));
        vm.portErrorProperty().addListener((o, a, b) -> port.setError(b));

        AppButton connect = new AppButton("Connect", AppButton.Variant.SECONDARY);
        connect.setOnAction(e -> vm.retry());
        connect.disableProperty().bind(vm.pendingProperty()
                .or(vm.connectionProperty().isEqualTo(ConnectionState.CONNECTING)));
        VBox connectionSettings = new VBox(Spacing.SPACE_2, hostPort, connect);
        connectionSettings.managedProperty().bind(vm.advancedExpandedProperty());
        connectionSettings.visibleProperty().bind(vm.advancedExpandedProperty());
        vm.advancedExpandedProperty().addListener((o, a, open) ->
                toggle.withIcon(open ? "fth-chevron-down" : "fth-chevron-right"));
        return new VBox(0, toggle, connectionSettings);
    }

    private HBox buildChip() {
        Circle dot = new Circle(5);
        dot.getStyleClass().add("chip-dot");
        Label text = new Label();
        text.getStyleClass().addAll("small", "text-secondary");
        text.textProperty().bind(vm.connectionTextBinding());
        Hyperlink retry = new Hyperlink("Retry");
        retry.getStyleClass().add("small");
        retry.setOnAction(e -> vm.retry());
        retry.visibleProperty().bind(vm.connectionProperty().isEqualTo(ConnectionState.OFFLINE)
                .or(vm.connectionProperty().isEqualTo(ConnectionState.RECONNECTING)));
        retry.managedProperty().bind(retry.visibleProperty());

        HBox chip = new HBox(Spacing.SPACE_2, dot, text, retry);
        chip.getStyleClass().add("auth-chip");
        chip.setAlignment(Pos.CENTER);
        chip.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        Runnable style = () -> {
            dot.getStyleClass().removeAll("connected", "connecting", "offline");
            dot.getStyleClass().add(switch (vm.connectionProperty().get()) {
                case CONNECTED -> "connected";
                case CONNECTING, RECONNECTING -> "connecting";
                case OFFLINE -> "offline";
            });
            chip.setAccessibleText("Connection: " + vm.connectionTextBinding().get());
        };
        vm.connectionProperty().addListener((o, a, b) -> style.run());
        style.run();
        return chip;
    }

    // ------------------------------------------------------------------ wiring
    private void wireFields() {
        username.textProperty().bindBidirectional(vm.usernameProperty());
        password.textProperty().bindBidirectional(vm.passwordProperty());
        confirm.textProperty().bindBidirectional(vm.confirmProperty());

        vm.usernameErrorProperty().addListener((o, a, b) -> username.setError(b));
        vm.passwordErrorProperty().addListener((o, a, b) -> password.setError(b));
        vm.confirmErrorProperty().addListener((o, a, b) -> confirm.setError(b));

        for (FormField f : new FormField[]{username, password, confirm}) {
            f.setOnSubmit(vm::submit);
            f.input().editableProperty().bind(vm.pendingProperty().not());
        }

        // validate on blur (only fields the user has typed in; empty ones are checked on submit)
        username.input().focusedProperty().addListener((o, was, now) -> {
            if (!now && !username.getText().isEmpty()) vm.validateUsername();
        });
        password.input().focusedProperty().addListener((o, was, now) -> {
            if (!now && vm.mode() == Mode.REGISTER && !password.getText().isEmpty()) vm.validatePassword();
        });
        confirm.input().focusedProperty().addListener((o, was, now) -> {
            if (!now && !confirm.getText().isEmpty()) vm.validateConfirm();
        });
    }

    private void focus(Field field) {
        FormField target = switch (field) {
            case USERNAME -> username;
            case PASSWORD -> password;
            case CONFIRM -> confirm;
        };
        Platform.runLater(target::requestInputFocus);
    }

    /** Autofocus the username once the connection is up (unless the user already clicked into a field). */
    private void autofocusIfConnected() {
        if (autofocused || vm.connectionProperty().get() != ConnectionState.CONNECTED) return;
        autofocused = true;
        var owner = getScene() == null ? null : getScene().getFocusOwner();
        boolean userIsTyping = owner instanceof javafx.scene.control.TextInputControl;
        if (!userIsTyping) username.requestInputFocus();
    }

    @Override
    public void dispose() {
        vm.dispose();
    }
}
