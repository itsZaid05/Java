package com.chatsphere.client;

import com.chatsphere.client.component.Severity;
import com.chatsphere.client.component.ToastHost;
import com.chatsphere.client.nav.SceneNavigator;
import com.chatsphere.client.net.ChatClient;
import com.chatsphere.client.state.ClientConfig;
import com.chatsphere.client.state.SessionState;
import com.chatsphere.client.view.dialog.DialogService;
import javafx.application.Platform;

import java.util.logging.Logger;

/** Owned by Person 3. The single entry point to shared services. Keep the public API. */
public final class AppContext {
    private static final Logger LOG = Logger.getLogger(AppContext.class.getName());
    private static AppContext instance;

    private final ChatClient client;
    private final SessionState session;
    private final DialogService dialogs = new DialogService();
    private final ClientConfig config;
    private SceneNavigator navigator;
    private ToastHost toastHost;

    private AppContext(ChatClient client, ClientConfig config) {
        this.client = client;
        this.config = config;
        this.session = new SessionState(client);
    }

    public static void init(ChatClient client) { init(client, ClientConfig.load()); }

    public static void init(ChatClient client, ClientConfig config) {
        instance = new AppContext(client, config);
    }
    public static AppContext get() {
        if (instance == null) throw new IllegalStateException("AppContext.init(...) was not called");
        return instance;
    }

    public ChatClient client() { return client; }
    public SessionState session() { return session; }
    public DialogService dialogs() { return dialogs; }
    /** Host and port from config.properties. */
    public ClientConfig config() { return config; }
    /** Page navigation (Auth / Workspace). Available once the SceneNavigator exists. */
    public SceneNavigator nav() {
        if (navigator == null) throw new IllegalStateException("SceneNavigator not created yet");
        return navigator;
    }

    /** Called by the SceneNavigator when it is created. */
    public void attach(SceneNavigator nav, ToastHost host) {
        this.navigator = nav;
        this.toastHost = host;
    }

    /** Shows a toast at the bottom of the window (3 s, queued). Safe to call from any thread. */
    public void toast(String text, Severity severity) {
        if (toastHost == null) { LOG.info("toast (" + severity + "): " + text); return; }
        if (Platform.isFxApplicationThread()) toastHost.show(text, severity);
        else Platform.runLater(() -> toastHost.show(text, severity));
    }
}
