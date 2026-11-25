package com.chatsphere.client.state;

import com.chatsphere.common.protocol.Protocol;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Server address read from config.properties (keys client.host, client.port). Falls back to localhost:5050. */
public record ClientConfig(String host, int port) {
    private static final Logger LOG = Logger.getLogger(ClientConfig.class.getName());

    public static ClientConfig load() {
        Properties p = new Properties();
        try (InputStream in = ClientConfig.class.getResourceAsStream("/config.properties")) {
            if (in != null) p.load(in);
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Could not read config.properties, using defaults", e);
        }
        return new ClientConfig(p.getProperty("client.host", "localhost").trim(), parsePort(p.getProperty("client.port")));
    }

    public ClientConfig withOverrides(Map<String, String> options) {
        String selectedHost = options.getOrDefault("host", host).trim();
        if (selectedHost.isEmpty()) throw new IllegalArgumentException("--host must not be blank");
        String selectedPort = options.get("port");
        if (selectedPort == null) return new ClientConfig(selectedHost, port);
        try {
            int value = Integer.parseInt(selectedPort);
            if (value >= 1 && value <= 65535) return new ClientConfig(selectedHost, value);
        } catch (NumberFormatException ignored) {
            // The message below describes the expected value.
        }
        throw new IllegalArgumentException("--port must be between 1 and 65535");
    }

    static int parsePort(String s) {
        try {
            int v = Integer.parseInt(s == null ? "" : s.trim());
            return v >= 1 && v <= 65535 ? v : Protocol.DEFAULT_PORT;
        } catch (NumberFormatException e) {
            return Protocol.DEFAULT_PORT;
        }
    }
}
