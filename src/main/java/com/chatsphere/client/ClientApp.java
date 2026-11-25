package com.chatsphere.client;

import com.chatsphere.client.nav.SceneNavigator;
import com.chatsphere.client.net.ChatClient;
import com.chatsphere.client.net.FakeChatClient;
import com.chatsphere.client.net.SocketChatClient;
import com.chatsphere.client.state.ClientConfig;
import com.chatsphere.client.theme.ThemeGallery;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.stage.Stage;

import java.util.List;
import java.util.concurrent.Executor;

/**
 * Owned by Person 3. Default: SocketChatClient with host and port from config.properties.
 * Flags: --fake uses FakeChatClient (login demo / password1, no server); --gallery shows the component, dialog and search harness.
 */
public class ClientApp extends Application {
    private ChatClient client;

    @Override public void start(Stage stage) {
        List<String> args = getParameters().getRaw();
        Executor ui = Platform::runLater;
        client = args.contains("--fake") ? new FakeChatClient(ui) : new SocketChatClient(ui);
        AppContext.init(client, ClientConfig.load().withOverrides(getParameters().getNamed()));

        SceneNavigator nav = new SceneNavigator(stage);
        if (args.contains("--gallery")) nav.showDevPage(ThemeGallery.create());
        else nav.showAuth();
        stage.show();
    }

    /** The client's threads are daemons, but stop them cleanly anyway. */
    @Override public void stop() {
        if (client instanceof SocketChatClient socket) socket.shutdown();
    }

    public static void main(String[] args) { launch(args); }
}
