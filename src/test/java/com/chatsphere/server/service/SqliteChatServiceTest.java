package com.chatsphere.server.service;

import com.chatsphere.common.model.*;
import com.chatsphere.common.protocol.ChatException;
import com.chatsphere.common.protocol.ErrorCode;
import com.chatsphere.server.db.Database;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Runs the whole contract against SQLite, plus the tests that only make sense for a database. */
class SqliteChatServiceTest extends AbstractChatServiceTest {

    @TempDir
    Path tmp;

    private final List<ChatServiceImpl> opened = new ArrayList<>();

    @Override
    protected ChatService create() {
        ChatServiceImpl s = new ChatServiceImpl(tmp.resolve("contract.db").toString());
        opened.add(s);
        return s;
    }

    @Override
    protected void destroy() {
        opened.forEach(ChatServiceImpl::close);
        opened.clear();
    }

    // ------------------------------------------------------------------ 11. persistence

    @Test
    void data_survives_closing_and_reopening_the_file() {
        String path = tmp.resolve("persist.db").toString();
        long messageId;
        try (ChatServiceImpl first = new ChatServiceImpl(path)) {
            first.register("alice", "password1");
            first.register("bob", "password1");
            first.createRoom("alice", "general");
            first.joinRoom("bob", "general");
            messageId = first.saveRoomMessage("alice", "general", "still here after restart").id();
            first.savePrivateMessage("alice", "bob", "unread pm");
        }
        try (ChatServiceImpl second = new ChatServiceImpl(path)) {
            second.verifyLogin("alice", "password1");
            second.verifyLogin("bob", "password1");
            assertThrows(ChatException.class, () -> second.verifyLogin("bob", "wrong-password"));
            assertEquals(List.of("alice", "bob"), second.roomMembers("alice", "general"));

            HistoryPage page = second.history("bob", TargetType.ROOM, "general", 0, 0);
            assertEquals(1, page.messages().size());
            assertEquals(messageId, page.messages().get(0).id());
            assertEquals("still here after restart", page.messages().get(0).text());

            assertEquals(1, second.listUsers("bob").get(0).unread());

            // ids keep increasing across restarts
            long next = second.saveRoomMessage("bob", "general", "after restart").id();
            assertTrue(next > messageId);
        }
    }

    @Test
    void opening_an_existing_database_does_not_wipe_it() {
        String path = tmp.resolve("twice.db").toString();
        try (ChatServiceImpl a = new ChatServiceImpl(path)) {
            a.register("alice", "password1");
        }
        try (ChatServiceImpl b = new ChatServiceImpl(path)) {
            ChatException ex = assertThrows(ChatException.class, () -> b.register("alice", "password1"));
            assertEquals(ErrorCode.USERNAME_TAKEN, ex.code());
        }
    }

    // ------------------------------------------------------------------ security

    @Test
    void passwords_in_the_file_are_hashed_not_plaintext() throws Exception {
        String path = tmp.resolve("secret.db").toString();
        try (ChatServiceImpl s = new ChatServiceImpl(path)) {
            s.register("alice", "my-very-secret-pw");
            s.register("bob", "my-very-secret-pw");
        }
        // raw bytes of the main file (and the WAL file, if the driver left one behind)
        for (String suffix : new String[]{"", "-wal"}) {
            Path p = Path.of(path + suffix);
            if (Files.exists(p)) {
                String raw = new String(Files.readAllBytes(p), StandardCharsets.ISO_8859_1);
                assertFalse(raw.contains("my-very-secret-pw"), "plaintext password found in " + p.getFileName());
            }
        }
        // the column looks like pbkdf2$120000$salt$hash and equal passwords get different hashes
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + path);
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT password_hash FROM users ORDER BY id")) {
            List<String> hashes = new ArrayList<>();
            while (rs.next()) hashes.add(rs.getString(1));
            assertEquals(2, hashes.size());
            for (String h : hashes) assertTrue(h.matches("pbkdf2\\$120000\\$[A-Za-z0-9+/=]+\\$[A-Za-z0-9+/=]+"), h);
            assertNotEquals(hashes.get(0), hashes.get(1), "each user gets a fresh random salt");
        }
    }

    @Test
    void sql_injection_attempts_are_harmless() {
        svc.register("alice", "password1");
        svc.createRoom("alice", "general");
        svc.saveRoomMessage("alice", "general", "normal message");

        assertEquals(ErrorCode.INVALID_USERNAME, codeOf(() -> svc.register("x'; DROP TABLE users;--", "password1")));
        assertEquals(ErrorCode.INVALID_ROOM_NAME, codeOf(() -> svc.createRoom("alice", "a'; DROP TABLE rooms;--")));
        assertEquals(ErrorCode.BAD_CREDENTIALS, codeOf(() -> svc.verifyLogin("alice' OR '1'='1", "x' OR '1'='1")));
        assertEquals(ErrorCode.NO_SUCH_ROOM, codeOf(() -> svc.joinRoom("alice", "general' OR '1'='1")));

        // hostile text is stored and searched as plain data
        String hostile = "'); DROP TABLE messages; --";
        svc.saveRoomMessage("alice", "general", hostile);
        assertEquals(1, svc.search("alice", new SearchQuery("DROP TABLE", "", 0, 0, false, null, "")).size());
        assertTrue(svc.search("alice", new SearchQuery("' OR 1=1 --", "", 0, 0, false, null, "")).isEmpty());
        assertEquals(2, svc.history("alice", TargetType.ROOM, "general", 0, 0).messages().size());
        svc.verifyLogin("alice", "password1"); // tables still exist
    }

    private static ErrorCode codeOf(Runnable action) {
        try {
            action.run();
        } catch (ChatException e) {
            return e.code();
        }
        return null;
    }

    // ------------------------------------------------------------------ schema

    @Test
    void schema_version_table_is_filled_once() throws Exception {
        String path = tmp.resolve("version.db").toString();
        new ChatServiceImpl(path).close();
        new ChatServiceImpl(path).close(); // opening twice must not add a second row
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + path);
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*), MAX(version) FROM schema_version")) {
            assertTrue(rs.next());
            assertEquals(1, rs.getInt(1));
            assertEquals(Database.SCHEMA_VERSION, rs.getInt(2));
        }
    }

    @Test
    void migrates_version_one_with_existing_chat() throws Exception {
        String path = tmp.resolve("old.db").toString();
        try (ChatServiceImpl initial = new ChatServiceImpl(path)) {
            initial.register("alice", "password1");
            initial.createRoom("alice", "general");
            initial.saveRoomMessage("alice", "general", "old chat");
        }
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + path);
             Statement st = c.createStatement()) {
            st.executeUpdate("DROP TABLE attachments");
            st.executeUpdate("UPDATE schema_version SET version = 1");
        }
        try (ChatServiceImpl migrated = new ChatServiceImpl(path)) {
            var old = migrated.history("alice", TargetType.ROOM, "general", 0, 1).messages().getFirst();
            assertEquals("old chat", old.text());
            assertNull(old.attachmentSize());
            var file = migrated.saveAttachment("alice", TargetType.ROOM, "general", "blank.txt", new byte[0]);
            assertEquals(0, file.attachmentSize());
            assertArrayEquals(new byte[0], migrated.attachmentChunk("alice", file.id(), 0));
        }
    }

    @Test
    void database_rejects_inconsistent_rows() throws Exception {
        String path = tmp.resolve("checks.db").toString();
        new ChatServiceImpl(path).close();
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + path);
             Statement st = c.createStatement()) {
            st.execute("PRAGMA foreign_keys = ON");
            st.execute("INSERT INTO users(username, password_hash, created_at) VALUES ('u1','h',1)");
            // a ROOM message without a room violates the CHECK constraint
            assertThrows(java.sql.SQLException.class, () -> st.execute(
                    "INSERT INTO messages(type, sender_id, text, ts) VALUES ('ROOM', 1, 'x', 1)"));
            // unknown sender violates the foreign key
            assertThrows(java.sql.SQLException.class, () -> st.execute(
                    "INSERT INTO messages(type, room_id, sender_id, recipient_id, text, ts) VALUES ('PRIVATE', NULL, 99, 1, 'x', 1)"));
        }
    }
}
