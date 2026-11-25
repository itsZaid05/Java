package com.chatsphere.client.state;

import com.chatsphere.client.component.PasswordField;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ClientConfigTest {
    @Test void portFallsBackToDefaultOnGarbage() {
        assertEquals(5050, ClientConfig.parsePort(null));
        assertEquals(5050, ClientConfig.parsePort("abc"));
        assertEquals(5050, ClientConfig.parsePort("70000"));
        assertEquals(6000, ClientConfig.parsePort(" 6000 "));
    }

    @Test void commandLineOverridesPackagedAddress() {
        var config = new ClientConfig("localhost", 5050);
        assertEquals(new ClientConfig("192.168.1.5", 5070),
                config.withOverrides(Map.of("host", "192.168.1.5", "port", "5070")));
        assertEquals(config, config.withOverrides(Map.of()));
        assertThrows(IllegalArgumentException.class, () -> config.withOverrides(Map.of("port", "0")));
    }

    @Test void strengthLabels() {
        assertEquals("weak", PasswordField.strengthOf("abc"));
        assertEquals("weak", PasswordField.strengthOf("abcdefgh"));
        assertEquals("fair", PasswordField.strengthOf("abcdefgh1"));
        assertEquals("strong", PasswordField.strengthOf("abcdefgh1234"));
    }
}
