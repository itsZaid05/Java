package com.chatsphere.client.state;

import com.chatsphere.client.ErrorMessages;
import com.chatsphere.client.component.Severity;
import com.chatsphere.client.net.ChatClient;
import com.chatsphere.client.net.ConnectionState;
import com.chatsphere.common.protocol.ChatException;
import com.chatsphere.common.protocol.ErrorCode;
import com.chatsphere.common.protocol.Validation;
import javafx.beans.binding.Bindings;
import javafx.beans.binding.BooleanBinding;
import javafx.beans.binding.StringBinding;
import javafx.beans.property.*;

import java.util.function.Consumer;

/**
 * Owned by Person 3. State and rules of the Auth screen (contract C5). Uses only JavaFX properties, so it
 * needs no toolkit in tests. All futures complete on the FX thread (ChatClient contract).
 */
public class AuthViewModel {
    public enum Mode { LOGIN, REGISTER }
    public enum Field { USERNAME, PASSWORD, CONFIRM }

    /** What the view-model needs from the outside world. */
    public interface Actions {
        void toast(String text, Severity severity);
        /** Login succeeded and session.currentUser is set: go to the workspace. */
        void authenticated(String username);
    }

    public static final String MSG_PASSWORD_REQUIRED = "Enter your password.";
    public static final String MSG_MISMATCH = "Passwords don't match.";
    public static final String MSG_PORT = "Enter a port from 1 to 65535.";
    public static final String MSG_HOST = "Enter a server address.";

    private final ChatClient client;
    private final SessionState session;
    private final Actions actions;

    private final ObjectProperty<Mode> mode = new SimpleObjectProperty<>(Mode.LOGIN);
    private final StringProperty username = new SimpleStringProperty("");
    private final StringProperty password = new SimpleStringProperty("");
    private final StringProperty confirm = new SimpleStringProperty("");
    private final StringProperty host = new SimpleStringProperty("");
    private final StringProperty port = new SimpleStringProperty("");
    private final BooleanProperty pending = new SimpleBooleanProperty(false);
    private final StringProperty error = new SimpleStringProperty("");     // banner
    private final StringProperty notice = new SimpleStringProperty("");    // e.g. why the session ended
    private final StringProperty usernameError = new SimpleStringProperty("");
    private final StringProperty passwordError = new SimpleStringProperty("");
    private final StringProperty confirmError = new SimpleStringProperty("");
    private final StringProperty hostError = new SimpleStringProperty("");
    private final StringProperty portError = new SimpleStringProperty("");
    private final BooleanProperty advancedExpanded = new SimpleBooleanProperty(false);
    private final ObjectProperty<ConnectionState> connection = new SimpleObjectProperty<>(ConnectionState.OFFLINE);

    private final BooleanBinding connected = connection.isEqualTo(ConnectionState.CONNECTED);
    private final BooleanBinding canSubmit;
    private final StringBinding connectionText;

    private Consumer<Field> focusHandler = f -> { };
    private boolean attempted;
    private boolean disposed;

    public AuthViewModel(ChatClient client, SessionState session, ClientConfig config, Actions actions) {
        this.client = client;
        this.session = session;
        this.actions = actions;
        String previousAddress = session.serverAddressProperty().get();
        int separator = previousAddress.lastIndexOf(':');
        host.set(separator < 0 ? config.host() : previousAddress.substring(0, separator));
        port.set(separator < 0 ? String.valueOf(config.port()) : previousAddress.substring(separator + 1));

        connection.bind(session.connectionStateProperty());
        canSubmit = Bindings.createBooleanBinding(
                () -> connection.get() == ConnectionState.CONNECTED && !pending.get(), connection, pending);
        connectionText = Bindings.createStringBinding(() -> switch (connection.get()) {
            case CONNECTED -> "Connected";
            case CONNECTING, RECONNECTING -> "Connecting...";
            case OFFLINE -> "Can't reach server";
        }, connection);

        // the connection settings open by themselves when the server cannot be reached
        // (the session mirror lags the client by one event, so also check the client's live state)
        connection.addListener((o, was, now) -> {
            if (attempted && (now == ConnectionState.OFFLINE || now == ConnectionState.RECONNECTING) && liveStateIs(now)) {
                advancedExpanded.set(true);
            }
        });
        // editing a field removes its error; validation itself runs on blur and on submit
        username.addListener((o, a, b) -> usernameError.set(""));
        password.addListener((o, a, b) -> { passwordError.set(""); confirmError.set(""); });
        confirm.addListener((o, a, b) -> confirmError.set(""));
        host.addListener((o, a, b) -> hostError.set(""));
        port.addListener((o, a, b) -> portError.set(""));
    }

    // ------------------------------------------------------------------ properties
    public ObjectProperty<Mode> modeProperty() { return mode; }
    public Mode mode() { return mode.get(); }
    public StringProperty usernameProperty() { return username; }
    public StringProperty passwordProperty() { return password; }
    public StringProperty confirmProperty() { return confirm; }
    public StringProperty hostProperty() { return host; }
    public StringProperty portProperty() { return port; }
    public BooleanProperty pendingProperty() { return pending; }
    public StringProperty errorProperty() { return error; }
    public StringProperty noticeProperty() { return notice; }
    public StringProperty usernameErrorProperty() { return usernameError; }
    public StringProperty passwordErrorProperty() { return passwordError; }
    public StringProperty confirmErrorProperty() { return confirmError; }
    public StringProperty hostErrorProperty() { return hostError; }
    public StringProperty portErrorProperty() { return portError; }
    public BooleanProperty advancedExpandedProperty() { return advancedExpanded; }
    public ObjectProperty<ConnectionState> connectionProperty() { return connection; }
    public BooleanBinding connectedBinding() { return connected; }
    /** Submit is allowed only while connected and nothing is running. */
    public BooleanBinding canSubmit() { return canSubmit; }
    public StringBinding connectionTextBinding() { return connectionText; }

    /** The view moves keyboard focus when the view-model asks for it. */
    public void setFocusHandler(Consumer<Field> handler) { this.focusHandler = handler; }

    // ------------------------------------------------------------------ mode
    public void setMode(Mode next) {
        if (mode.get() == next) return;
        mode.set(next);
        clearFieldErrors();
        error.set("");
        password.set("");
        confirm.set("");
    }

    public void toggleMode() { setMode(mode.get() == Mode.LOGIN ? Mode.REGISTER : Mode.LOGIN); }

    private void clearFieldErrors() {
        usernameError.set("");
        passwordError.set("");
        confirmError.set("");
    }

    // ------------------------------------------------------------------ validation (pure rules + error properties)
    static String usernameProblem(String u) {
        return Validation.isValidUsername(u) ? "" : ErrorMessages.of(ErrorCode.INVALID_USERNAME);
    }

    static String passwordProblem(Mode m, String p) {
        if (m == Mode.LOGIN) return p == null || p.isEmpty() ? MSG_PASSWORD_REQUIRED : "";
        return Validation.isValidPassword(p) ? "" : ErrorMessages.of(ErrorCode.INVALID_PASSWORD);
    }

    static String confirmProblem(Mode m, String p, String c) {
        return m == Mode.REGISTER && !p.equals(c) ? MSG_MISMATCH : "";
    }

    public boolean validateUsername() {
        usernameError.set(usernameProblem(username.get()));
        return usernameError.get().isEmpty();
    }

    public boolean validatePassword() {
        passwordError.set(passwordProblem(mode.get(), password.get()));
        return passwordError.get().isEmpty();
    }

    public boolean validateConfirm() {
        confirmError.set(confirmProblem(mode.get(), password.get(), confirm.get()));
        return confirmError.get().isEmpty();
    }

    /** Validates every visible field, focuses the first invalid one. */
    public boolean validateAll() {
        boolean u = validateUsername(), p = validatePassword(), c = validateConfirm();
        if (!u) focusHandler.accept(Field.USERNAME);
        else if (!p) focusHandler.accept(Field.PASSWORD);
        else if (!c) focusHandler.accept(Field.CONFIRM);
        return u && p && c;
    }

    // ------------------------------------------------------------------ submit
    public void submit() {
        if (!canSubmit.get()) return;
        notice.set("");
        error.set("");
        if (!validateAll()) return;
        String u = username.get();
        pending.set(true);
        if (mode.get() == Mode.REGISTER) register(u);
        else login(u, false);
    }

    private void register(String u) {
        client.register(u, password.get().toCharArray()).whenComplete((v, t) -> {
            if (disposed) return;
            if (t != null) {
                pending.set(false);
                onRegisterFailed(t);
                return;
            }
            actions.toast("Account created", Severity.SUCCESS);
            login(u, true);   // register does not log in: same credentials, straight away
        });
    }

    private void login(String u, boolean afterRegister) {
        client.login(u, password.get().toCharArray()).whenComplete((v, t) -> {
            if (disposed) return;
            pending.set(false);
            if (t != null) {
                onLoginFailed(t, afterRegister);
                return;
            }
            password.set("");
            confirm.set("");
            session.currentUserProperty().set(u);
            actions.authenticated(u);
        });
    }

    private void onRegisterFailed(Throwable t) {
        ErrorCode code = codeOf(t);
        if (code == ErrorCode.USERNAME_TAKEN || code == ErrorCode.INVALID_USERNAME) {
            usernameError.set(ErrorMessages.of(t));
            focusHandler.accept(Field.USERNAME);
        } else if (code == ErrorCode.INVALID_PASSWORD) {
            passwordError.set(ErrorMessages.of(t));
            focusHandler.accept(Field.PASSWORD);
        } else {
            error.set(ErrorMessages.of(t));
        }
    }

    private void onLoginFailed(Throwable t, boolean afterRegister) {
        if (afterRegister) setMode(Mode.LOGIN);  // the account exists now, so "register" again would only fail
        password.set("");                        // only the password is cleared
        error.set(ErrorMessages.of(t));          // BAD_CREDENTIALS and everything else: banner
        focusHandler.accept(Field.PASSWORD);
    }

    static ErrorCode codeOf(Throwable t) {
        Throwable c = t;
        while (c != null && !(c instanceof ChatException) && c.getCause() != null) c = c.getCause();
        return c instanceof ChatException ce ? ce.code() : null;
    }

    // ------------------------------------------------------------------ connection
    /** On show: connect unless already connected or connecting. */
    public void connectIfNeeded() {
        ConnectionState s = client != null ? client.getConnectionState() : connection.get();
        if (s != ConnectionState.CONNECTED && s != ConnectionState.CONNECTING) startConnect(false);
    }

    private boolean liveStateIs(ConnectionState s) {
        return client == null || client.getConnectionState() == s;
    }

    /** The Retry link. Uses the (possibly edited) host and port. */
    public void retry() { startConnect(true); }

    private void startConnect(boolean manual) {
        String h = host.get() == null ? "" : host.get().trim();
        int p = parsePort(port.get());
        hostError.set(h.isEmpty() ? MSG_HOST : "");
        portError.set(p < 0 ? MSG_PORT : "");
        if (h.isEmpty() || p < 0) {
            advancedExpanded.set(true);
            return;
        }
        ConnectionState state = client.getConnectionState();
        if (state == ConnectionState.CONNECTING || pending.get()) return;
        attempted = true;
        String address = h + ":" + p;
        boolean changed = !address.equals(session.serverAddressProperty().get());
        session.serverAddressProperty().set(address);
        if (manual && !changed && state == ConnectionState.RECONNECTING) {
            client.retryNow();
            return;
        }
        if (changed && state != ConnectionState.OFFLINE) client.disconnect();
        client.connect(h, p);
    }

    /** 1..65535, or -1. */
    static int parsePort(String s) {
        try {
            int v = Integer.parseInt(s == null ? "" : s.trim());
            return v >= 1 && v <= 65535 ? v : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** Called when the view is removed: ignore late replies and release the session binding. */
    public void dispose() {
        disposed = true;
        connection.unbind();
    }
}
