package com.chatsphere;

import com.chatsphere.client.net.ConnectionState;
import com.chatsphere.client.net.ServerEvent;
import com.chatsphere.client.net.SocketChatClient;
import com.chatsphere.common.model.DeliveryStatus;
import com.chatsphere.common.model.SearchQuery;
import com.chatsphere.common.model.TargetType;
import com.chatsphere.common.protocol.ChatException;
import com.chatsphere.common.protocol.Codec;
import com.chatsphere.common.protocol.ErrorCode;
import com.chatsphere.common.protocol.Limits;
import com.chatsphere.common.protocol.Protocol;
import com.chatsphere.server.net.ChatServer;
import com.chatsphere.server.service.ChatServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end safety net: real sockets, real SQLite, protocol spoken only through Codec and Protocol.
 * Every read has a 2 second timeout, so a missing frame fails the test instead of hanging it.
 */
class ProtocolIntegrationTest {

    @TempDir
    Path tmp;

    private ChatServiceImpl service;
    private ChatServer server;
    private int port;
    private final List<Client> clients = new ArrayList<>();
    private final List<SocketChatClient> desktopClients = new ArrayList<>();

    @BeforeEach
    void startServer() throws Exception {
        service = new ChatServiceImpl(tmp.resolve("integration.db").toString());
        server = new ChatServer(0, service);
        port = server.start();
    }

    @AfterEach
    void stopServer() {
        desktopClients.forEach(SocketChatClient::shutdown);
        clients.forEach(Client::close);
        server.stop();
        service.close();
    }

    // ------------------------------------------------------------------ test client

    private final class Client {
        final Socket socket;
        final BufferedReader in;
        final BufferedWriter out;

        Client() throws IOException {
            socket = new Socket("127.0.0.1", port);
            socket.setSoTimeout(2000);
            in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            out = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
            clients.add(this);
        }

        void send(String cmd, String... fields) throws IOException {
            sendRaw(Codec.encode(cmd, fields));
        }

        void sendRaw(String line) throws IOException {
            out.write(line);
            out.write('\n');
            out.flush();
        }

        /** Next frame, decoded. Fails (does not hang) when nothing arrives within 2 seconds. */
        List<String> read() throws IOException {
            try {
                String line = in.readLine();
                if (line == null) fail("connection closed while waiting for a frame");
                return Codec.decode(line);
            } catch (SocketTimeoutException e) {
                return fail("no frame within 2 seconds");
            }
        }

        /** Next frame must have exactly these fields. */
        void expect(String... expected) throws IOException {
            assertEquals(List.of(expected), read());
        }

        /** Next frame must have this command name; returns all its fields. */
        List<String> readOf(String cmd) throws IOException {
            List<String> f = read();
            assertEquals(cmd, f.get(0), "unexpected frame " + f.get(0));
            return f;
        }

        /** No frame must arrive in the next 300 ms. */
        void assertSilent() throws IOException {
            socket.setSoTimeout(300);
            try {
                String line = in.readLine();
                fail(line == null ? "connection closed unexpectedly" : "unexpected frame " + line.split("\t", 2)[0]);
            } catch (SocketTimeoutException expected) {
                // silence is what we wanted
            } finally {
                socket.setSoTimeout(2000);
            }
        }

        void assertClosedByServer() throws IOException {
            try {
                assertNull(in.readLine(), "server should have closed the connection");
            } catch (SocketTimeoutException e) {
                fail("server did not close the connection");
            }
        }

        void close() {
            try {
                socket.close();
            } catch (IOException ignored) {
                // already closed
            }
        }
    }

    private Client client() throws IOException {
        return new Client();
    }

    /** Registers and logs in on a fresh connection. */
    private Client loggedIn(String name) throws IOException {
        Client c = client();
        c.send(Protocol.REGISTER, "1", name, "password1");
        c.expect(Protocol.OK, "1");
        c.send(Protocol.LOGIN, "2", name, "password1");
        c.expect(Protocol.LOGIN_OK, "2", name);
        return c;
    }

    /** alice creates "general", bob joins it. All start-up frames are consumed. */
    private Client[] aliceAndBobInGeneral() throws IOException {
        Client a = loggedIn("alice");
        a.send(Protocol.ROOM_CREATE, "3", "general");
        a.expect(Protocol.OK, "3");
        Client b = loggedIn("bob");
        a.expect(Protocol.USER_STATUS, "bob", "1");
        b.send(Protocol.ROOM_JOIN, "3", "general");
        b.expect(Protocol.OK, "3");
        b.expect(Protocol.ROOM_MEMBERS_CHANGED, "general", "bob", Protocol.JOINED);
        a.expect(Protocol.ROOM_MEMBERS_CHANGED, "general", "bob", Protocol.JOINED);
        return new Client[]{a, b};
    }

    // ------------------------------------------------------------------ scenarios

    @Test
    void register_login_ping_and_error_replies() throws Exception {
        Client a = client();
        a.send(Protocol.PING, "1");
        a.expect(Protocol.PONG, "1");
        a.send(Protocol.REGISTER, "2", "alice", "password1");
        a.expect(Protocol.OK, "2");
        a.send(Protocol.REGISTER, "3", "alice", "password1");
        a.expect(Protocol.ERROR, "3", "USERNAME_TAKEN");
        a.send(Protocol.REGISTER, "4", "al", "password1");
        a.expect(Protocol.ERROR, "4", "INVALID_USERNAME");
        a.send(Protocol.REGISTER, "5", "bobby", "short");
        a.expect(Protocol.ERROR, "5", "INVALID_PASSWORD");

        a.send(Protocol.ROOM_LIST, "6", Protocol.SCOPE_MINE);
        a.expect(Protocol.ERROR, "6", "NOT_LOGGED_IN");

        a.send(Protocol.LOGIN, "7", "alice", "wrong-password");
        a.expect(Protocol.ERROR, "7", "BAD_CREDENTIALS");
        a.send(Protocol.LOGIN, "8", "nobody", "password1");
        a.expect(Protocol.ERROR, "8", "BAD_CREDENTIALS");
        a.send(Protocol.LOGIN, "9", "alice", "password1");
        a.expect(Protocol.LOGIN_OK, "9", "alice");
        a.send(Protocol.PING, "10");
        a.expect(Protocol.PONG, "10");
    }

    @Test
    void room_create_join_leave_with_members_changed() throws Exception {
        Client a = loggedIn("alice");
        a.send(Protocol.ROOM_CREATE, "3", "general");
        a.expect(Protocol.OK, "3");
        a.send(Protocol.ROOM_CREATE, "4", "general");
        a.expect(Protocol.ERROR, "4", "ROOM_EXISTS");
        a.send(Protocol.ROOM_CREATE, "5", "x");
        a.expect(Protocol.ERROR, "5", "INVALID_ROOM_NAME");

        Client b = loggedIn("bob");
        a.expect(Protocol.USER_STATUS, "bob", "1");

        b.send(Protocol.ROOM_LIST, "3", Protocol.SCOPE_ALL);
        b.expect(Protocol.ROOM_LIST_RESULT, "3", "ALL", "1", "general", "1", "0", "0", "", "0");
        b.send(Protocol.ROOM_LIST, "4", Protocol.SCOPE_MINE);
        b.expect(Protocol.ROOM_LIST_RESULT, "4", "MINE", "0");

        b.send(Protocol.ROOM_JOIN, "5", "nowhere");
        b.expect(Protocol.ERROR, "5", "NO_SUCH_ROOM");
        b.send(Protocol.ROOM_JOIN, "6", "general");
        b.expect(Protocol.OK, "6");
        b.expect(Protocol.ROOM_MEMBERS_CHANGED, "general", "bob", "JOINED"); // the joiner is told too
        a.expect(Protocol.ROOM_MEMBERS_CHANGED, "general", "bob", "JOINED");

        a.send(Protocol.ROOM_MEMBERS, "7", "general");
        a.expect(Protocol.ROOM_MEMBERS_RESULT, "7", "general", "2", "alice", "1", "bob", "1");

        b.send(Protocol.ROOM_LEAVE, "8", "general");
        b.expect(Protocol.OK, "8");
        b.expect(Protocol.ROOM_MEMBERS_CHANGED, "general", "bob", "LEFT"); // the leaver is told too
        a.expect(Protocol.ROOM_MEMBERS_CHANGED, "general", "bob", "LEFT");

        b.send(Protocol.ROOM_MEMBERS, "9", "general");
        b.expect(Protocol.ERROR, "9", "NOT_MEMBER");
    }

    @Test
    void room_message_is_pushed_to_others_but_not_echoed() throws Exception {
        Client[] ab = aliceAndBobInGeneral();
        Client a = ab[0], b = ab[1];

        b.send(Protocol.MSG, "7", "general", "hello all");
        List<String> sent = b.readOf(Protocol.SENT);
        assertEquals(4, sent.size());
        assertEquals("7", sent.get(1), "SENT echoes the client's temp id");
        long id = Long.parseLong(sent.get(2));
        long ts = Long.parseLong(sent.get(3));
        assertTrue(id > 0 && ts > 0);

        a.expect(Protocol.MSG_PUSH, "general", String.valueOf(id), "bob", String.valueOf(ts), "hello all");
        b.assertSilent(); // never echoed to its sender

        b.send(Protocol.MSG, "8", "general", "   ");
        b.expect(Protocol.ERROR, "8", "INVALID_MESSAGE");
        b.send(Protocol.MSG, "9", "nowhere", "hi");
        b.expect(Protocol.ERROR, "9", "NO_SUCH_ROOM");
    }

    @Test
    void multiline_text_and_backslash_n_round_trip_unchanged() throws Exception {
        Client[] ab = aliceAndBobInGeneral();
        Client a = ab[0], b = ab[1];

        String multiline = "line one\nline two\r\nline\tthree \uD83D\uDE00";
        String literalBackslashN = "C:\\new\\table"; // contains the two characters backslash + n
        assertTrue(literalBackslashN.contains("\\n"));

        for (String text : new String[]{multiline, literalBackslashN}) {
            b.send(Protocol.MSG, "20", "general", text);
            b.readOf(Protocol.SENT);
            List<String> push = a.readOf(Protocol.MSG_PUSH);
            assertEquals(text, push.get(5));
        }

        a.send(Protocol.HISTORY, "21", "ROOM", "general", "0", "0");
        List<String> h = a.readOf(Protocol.HISTORY_RESULT);
        assertEquals("2", h.get(6));
        assertEquals(multiline, h.get(6 + 5));      // 5th field of the first record
        assertEquals(literalBackslashN, h.get(6 + 10));
    }

    @Test
    void private_message_ordering_sent_before_delivered() throws Exception {
        Client a = loggedIn("alice");
        Client b = loggedIn("bob");
        a.expect(Protocol.USER_STATUS, "bob", "1");

        a.send(Protocol.PM, "5", "bob", "psst");
        List<String> sent = a.readOf(Protocol.SENT); // always first
        assertEquals("5", sent.get(1));
        String id = sent.get(2);
        a.expect(Protocol.ACK_PUSH, "DELIVERED", "bob", "1", id);

        b.expect(Protocol.PM_PUSH, id, "alice", sent.get(3), "psst");

        a.send(Protocol.PM, "6", "alice", "to myself");
        a.expect(Protocol.ERROR, "6", "INVALID_TARGET");
        a.send(Protocol.PM, "7", "ghost", "hi");
        a.expect(Protocol.ERROR, "7", "NO_SUCH_USER");
    }

    @Test
    void offline_delivery_happens_at_login() throws Exception {
        Client a = loggedIn("alice");
        Client reg = client(); // bob registers but stays offline
        reg.send(Protocol.REGISTER, "1", "bob", "password1");
        reg.expect(Protocol.OK, "1");
        reg.close();

        a.send(Protocol.PM, "5", "bob", "are you there?");
        List<String> sent = a.readOf(Protocol.SENT);
        a.assertSilent(); // bob is offline: SENT only
        String id = sent.get(2);

        Client b = client();
        b.send(Protocol.LOGIN, "1", "bob", "password1");
        b.expect(Protocol.LOGIN_OK, "1", "bob");
        a.expect(Protocol.USER_STATUS, "bob", "1");
        a.expect(Protocol.ACK_PUSH, "DELIVERED", "bob", "1", id);

        // bob sees the unread message in his user list
        b.send(Protocol.USER_LIST, "2");
        List<String> users = b.readOf(Protocol.USER_LIST_RESULT);
        assertEquals(List.of("2", "1", "alice", "1", "1", "are you there?"), users.subList(1, 7));
    }

    @Test
    void mark_read_pushes_ack_read_to_the_sender() throws Exception {
        Client a = loggedIn("alice");
        Client b = loggedIn("bob");
        a.expect(Protocol.USER_STATUS, "bob", "1");

        a.send(Protocol.PM, "5", "bob", "read me");
        String id = a.readOf(Protocol.SENT).get(2);
        a.readOf(Protocol.ACK_PUSH);
        b.readOf(Protocol.PM_PUSH);

        b.send(Protocol.MARK_READ, "9", "PRIVATE", "alice", id);
        b.expect(Protocol.OK, "9");
        a.expect(Protocol.ACK_PUSH, "READ", "bob", "1", id);

        // marking again changes nothing, so no second push
        b.send(Protocol.MARK_READ, "10", "PRIVATE", "alice", id);
        b.expect(Protocol.OK, "10");
        a.assertSilent();

        b.send(Protocol.MARK_READ, "11", "PRIVATE", "ghost", id);
        b.expect(Protocol.ERROR, "11", "NO_SUCH_USER");
    }

    @Test
    void history_paging_walks_120_messages_as_50_50_20() throws Exception {
        Client a = loggedIn("alice");
        a.send(Protocol.ROOM_CREATE, "2", "general");
        a.expect(Protocol.OK, "2");
        List<String> allIds = new ArrayList<>();
        for (int i = 1; i <= 120; i++) {
            a.send(Protocol.MSG, String.valueOf(100 + i), "general", "message " + i);
            allIds.add(a.readOf(Protocol.SENT).get(2));
        }

        a.send(Protocol.HISTORY, "500", "ROOM", "general", "0", "0");
        List<String> p1 = a.readOf(Protocol.HISTORY_RESULT);
        assertEquals(List.of("500", "ROOM", "general", "1", "0", "50"), p1.subList(1, 7)); // hasMore, no unread
        assertEquals(7 + 50 * 5, p1.size());
        assertEquals(allIds.get(119), p1.get(7 + 49 * 5), "oldest first, newest last");
        String oldest1 = p1.get(7);
        assertEquals(allIds.get(70), oldest1);

        a.send(Protocol.HISTORY, "501", "ROOM", "general", oldest1, "50");
        List<String> p2 = a.readOf(Protocol.HISTORY_RESULT);
        assertEquals(List.of("501", "ROOM", "general", "1", "0", "50"), p2.subList(1, 7));
        String oldest2 = p2.get(7);
        assertEquals(allIds.get(20), oldest2);

        a.send(Protocol.HISTORY, "502", "ROOM", "general", oldest2, "50");
        List<String> p3 = a.readOf(Protocol.HISTORY_RESULT);
        assertEquals(List.of("502", "ROOM", "general", "0", "0", "20"), p3.subList(1, 7)); // nothing older
        assertEquals(allIds.get(0), p3.get(7));

        a.send(Protocol.HISTORY_AROUND, "503", "ROOM", "general", allIds.get(60));
        List<String> around = a.readOf(Protocol.HISTORY_RESULT);
        assertEquals("51", around.get(6));

        a.send(Protocol.HISTORY_AROUND, "504", "ROOM", "general", "999999");
        a.expect(Protocol.ERROR, "504", "BAD_REQUEST");
    }

    @Test
    void search_with_filters_and_stride() throws Exception {
        Client[] ab = aliceAndBobInGeneral();
        Client a = ab[0], b = ab[1];

        a.send(Protocol.MSG, "10", "general", "the build is green");
        a.readOf(Protocol.SENT);
        b.readOf(Protocol.MSG_PUSH);
        b.send(Protocol.MSG, "11", "general", "no match here");
        b.readOf(Protocol.SENT);
        a.readOf(Protocol.MSG_PUSH);
        b.send(Protocol.MSG, "12", "general", "Build failed on CI");
        b.readOf(Protocol.SENT);
        a.readOf(Protocol.MSG_PUSH);

        // everywhere, anyone: two hits, newest first
        a.send(Protocol.SEARCH, "20", "build", "", "0", "0", "ALL", "", "");
        List<String> r = a.readOf(Protocol.SEARCH_RESULT);
        assertEquals("20", r.get(1));
        assertEquals("2", r.get(2));
        assertEquals(3 + 2 * 6, r.size());
        assertEquals(List.of("ROOM", "general", "bob"), r.subList(4, 7));
        assertEquals("Build failed on CI", r.get(8));
        assertEquals("the build is green", r.get(14));

        // sender filter
        a.send(Protocol.SEARCH, "21", "build", "alice", "0", "0", "ALL", "", "");
        List<String> byAlice = a.readOf(Protocol.SEARCH_RESULT);
        assertEquals("1", byAlice.get(2));
        assertEquals("alice", byAlice.get(6));

        // this conversation only (a private chat with no messages) -> nothing
        a.send(Protocol.SEARCH, "22", "build", "", "0", "0", "CURRENT", "PRIVATE", "bob");
        a.expect(Protocol.SEARCH_RESULT, "22", "0");
        a.send(Protocol.SEARCH, "23", "build", "", "0", "0", "CURRENT", "ROOM", "general");
        assertEquals("2", a.readOf(Protocol.SEARCH_RESULT).get(2));

        // blank keyword
        a.send(Protocol.SEARCH, "24", " ", "", "0", "0", "ALL", "", "");
        a.expect(Protocol.ERROR, "24", "BAD_REQUEST");
    }

    @Test
    void typing_is_relayed_without_a_reply() throws Exception {
        Client[] ab = aliceAndBobInGeneral();
        Client a = ab[0], b = ab[1];

        b.send(Protocol.TYPING, "0", "ROOM", "general");
        a.expect(Protocol.TYPING_PUSH, "ROOM", "general", "bob");
        b.assertSilent();

        b.send(Protocol.TYPING, "0", "PRIVATE", "alice");
        a.expect(Protocol.TYPING_PUSH, "PRIVATE", "bob", "bob"); // for PRIVATE, target equals user

        b.send(Protocol.TYPING, "0", "ROOM", "nowhere"); // never errors
        b.assertSilent();
    }

    @Test
    void presence_is_pushed_on_login_and_disconnect() throws Exception {
        Client a = loggedIn("alice");
        Client b = loggedIn("bob");
        a.expect(Protocol.USER_STATUS, "bob", "1");
        b.close();
        a.expect(Protocol.USER_STATUS, "bob", "0");

        Client c = loggedIn("carol");
        a.expect(Protocol.USER_STATUS, "carol", "1");
        c.send(Protocol.LOGOUT, "5");
        c.expect(Protocol.OK, "5");
        c.assertClosedByServer();
        a.expect(Protocol.USER_STATUS, "carol", "0");
    }

    @Test
    void second_login_replaces_the_first_session() throws Exception {
        Client first = loggedIn("alice");
        Client second = client();
        second.send(Protocol.LOGIN, "1", "alice", "password1");
        second.expect(Protocol.LOGIN_OK, "1", "alice");

        first.expect(Protocol.SESSION_REPLACED);
        first.assertClosedByServer();

        // the new session works and the replaced one did not trigger an "offline" push
        second.send(Protocol.PING, "2");
        second.expect(Protocol.PONG, "2");
        second.assertSilent();
    }

    @Test
    void malformed_frames_get_errors_and_the_connection_stays_usable() throws Exception {
        Client a = loggedIn("alice");

        a.send(Protocol.MSG, "5", "general"); // wrong number of fields
        a.expect(Protocol.ERROR, "5", "BAD_REQUEST");

        a.sendRaw("PING\t6\tbad\\qescape"); // invalid escape sequence: reqId unreadable
        a.expect(Protocol.ERROR, "0", "BAD_REQUEST");

        a.send("FROBNICATE", "7");
        a.expect(Protocol.ERROR, "7", "UNKNOWN_COMMAND");

        a.send(Protocol.HISTORY, "8", "ROOM", "general", "abc", "0"); // not a number
        a.expect(Protocol.ERROR, "8", "BAD_REQUEST");

        a.send(Protocol.HISTORY, "9", "NOPE", "general", "0", "0"); // not a TargetType
        a.expect(Protocol.ERROR, "9", "BAD_REQUEST");

        a.sendRaw(""); // empty frame
        a.expect(Protocol.ERROR, "0", "BAD_REQUEST");

        a.send(Protocol.PING, "10");
        a.expect(Protocol.PONG, "10");
    }

    @Test
    void history_survives_a_server_restart() throws Exception {
        Client a = loggedIn("alice");
        a.send(Protocol.ROOM_CREATE, "3", "general");
        a.expect(Protocol.OK, "3");
        a.send(Protocol.MSG, "4", "general", "persisted");
        String id = a.readOf(Protocol.SENT).get(2);
        a.close();

        server.stop();
        service.close();
        service = new ChatServiceImpl(tmp.resolve("integration.db").toString());
        server = new ChatServer(0, service);
        port = server.start();

        Client again = client();
        again.send(Protocol.LOGIN, "1", "alice", "password1");
        again.expect(Protocol.LOGIN_OK, "1", "alice");
        again.send(Protocol.HISTORY, "2", "ROOM", "general", "0", "0");
        List<String> h = again.readOf(Protocol.HISTORY_RESULT);
        assertEquals("1", h.get(6));
        assertEquals(id, h.get(7));
        assertEquals("persisted", h.get(11));
    }

    @Test
    void roomImageArrivesLiveAndOnlyMembersCanSaveIt() throws Exception {
        SocketChatClient alice = desktopClient("alice", true);
        SocketChatClient bob = desktopClient("bob", true);
        SocketChatClient carol = desktopClient("carol", true);
        await(alice.createRoom("general"));
        await(bob.joinRoom("general"));

        Path source = tmp.resolve("lab photo.png");
        var image = new BufferedImage(160, 160, BufferedImage.TYPE_INT_RGB);
        var random = new Random(7);
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) image.setRGB(x, y, random.nextInt());
        }
        assertTrue(ImageIO.write(image, "png", source.toFile()));
        byte[] original = Files.readAllBytes(source);
        assertTrue(original.length > Limits.ATTACHMENT_CHUNK_BYTES);

        var incoming = new CompletableFuture<ServerEvent.RoomMessage>();
        bob.addEventListener(event -> {
            if (event instanceof ServerEvent.RoomMessage message) incoming.complete(message);
        });
        var sent = await(alice.sendAttachment(TargetType.ROOM, "general", source));
        var received = await(incoming).message();
        assertEquals(sent.message().id(), received.id());
        assertEquals("lab photo.png", received.text());
        assertEquals(original.length, received.attachmentSize());
        assertEquals(received, await(bob.loadHistory(TargetType.ROOM, "general", 0, 50)).messages().getFirst());

        Path saved = tmp.resolve("download.png");
        await(bob.saveAttachment(received.id(), received.attachmentSize(), saved));
        assertArrayEquals(original, Files.readAllBytes(saved));

        assertTransferError(ErrorCode.ATTACHMENT_UNAVAILABLE, carol.saveAttachment(received.id(), original.length, saved));
        await(bob.leaveRoom("general"));
        assertTransferError(ErrorCode.ATTACHMENT_UNAVAILABLE, bob.saveAttachment(received.id(), original.length, saved));
        assertArrayEquals(original, Files.readAllBytes(saved), "failed downloads must preserve an existing file");
    }

    @Test
    void offlineAttachmentSurvivesRestartAndAppearsUnreadWithReceipts() throws Exception {
        SocketChatClient alice = desktopClient("alice", true);
        service.register("bob", "password1");
        Path source = Files.writeString(tmp.resolve("study-notes.txt"), "JAVA LAN test\nOne, two, three.");
        var sent = await(alice.sendAttachment(TargetType.PRIVATE, "bob", source));
        assertEquals(DeliveryStatus.SENT,
                await(alice.loadHistory(TargetType.PRIVATE, "bob", 0, 1)).messages().getFirst().status());

        alice.shutdown();
        server.stop();
        service.close();
        startServer();
        SocketChatClient sender = desktopClient("alice", false);
        var receipts = new LinkedBlockingQueue<ServerEvent.StatusUpdate>();
        sender.addEventListener(event -> {
            if (event instanceof ServerEvent.StatusUpdate update) receipts.add(update);
        });
        SocketChatClient bob = desktopClient("bob", false);
        var delivered = receipts.poll(5, TimeUnit.SECONDS);
        assertNotNull(delivered);
        assertEquals(DeliveryStatus.DELIVERED, delivered.status());
        assertEquals(List.of(sent.message().id()), delivered.messageIds());
        assertEquals(1, await(bob.listUsers()).getFirst().unread());

        var history = await(bob.loadHistory(TargetType.PRIVATE, "alice", 0, 50));
        var restored = history.messages().getFirst();
        assertEquals(sent.message().id(), restored.id());
        assertEquals(restored.id(), history.firstUnreadId());
        assertEquals(sent.size(), restored.attachmentSize());
        Path saved = tmp.resolve("saved-notes.txt");
        await(bob.saveAttachment(restored.id(), restored.attachmentSize(), saved));
        assertArrayEquals(Files.readAllBytes(source), Files.readAllBytes(saved));

        await(bob.markRead(TargetType.PRIVATE, "alice", restored.id()));
        var read = receipts.poll(5, TimeUnit.SECONDS);
        assertNotNull(read);
        assertEquals(DeliveryStatus.READ, read.status());
        assertEquals(List.of(restored.id()), read.messageIds());
        assertEquals(0, await(bob.listUsers()).getFirst().unread());

        SocketChatClient carol = desktopClient("carol", true);
        assertTransferError(ErrorCode.ATTACHMENT_UNAVAILABLE,
                carol.saveAttachment(restored.id(), restored.attachmentSize(), tmp.resolve("unavailable.txt")));
    }

    @Test
    void incompleteAndOversizedUploadsLeaveHistoryUnchanged() throws Exception {
        Client alice = loggedIn("alice");
        alice.send(Protocol.ROOM_CREATE, "3", "general");
        alice.expect(Protocol.OK, "3");
        alice.send(Protocol.FILE_START, "4", "ROOM", "general", "large.bin", Codec.str(Limits.ATTACHMENT_MAX_BYTES + 1));
        alice.expect(Protocol.ERROR, "4", ErrorCode.ATTACHMENT_TOO_LARGE.name());
        alice.send(Protocol.FILE_START, "5", "ROOM", "general", "incomplete.bin", "3");
        alice.expect(Protocol.OK, "5");
        alice.send(Protocol.FILE_SEND, "6");
        alice.expect(Protocol.ERROR, "6", ErrorCode.BAD_REQUEST.name());
        assertTrue(service.history("alice", TargetType.ROOM, "general", 0, 1).messages().isEmpty());
    }

    @Test
    void desktopClientLoadsHistoryAndSearchWithLongUnicodeMessages() throws Exception {
        SocketChatClient alice = desktopClient("alice", true);
        await(alice.createRoom("general"));
        String text = "测试".repeat(1000);
        for (int i = 0; i < 50; i++) service.saveRoomMessage("alice", "general", text);
        var page = await(alice.loadHistory(TargetType.ROOM, "general", 0, 50));
        assertEquals(50, page.messages().size());
        assertEquals(text, page.messages().getFirst().text());
        var results = await(alice.search(new SearchQuery("测试", "", 0, 0, true, TargetType.ROOM, "general")));
        assertEquals(50, results.size());
    }

    @Test
    void onlineDirectTransferAcceptsExactLimitAndEmptyFileAndRejectsOneByteOver() throws Exception {
        SocketChatClient alice = desktopClient("alice", true);
        SocketChatClient bob = desktopClient("bob", true);
        byte[] bytes = new byte[Limits.ATTACHMENT_MAX_BYTES];
        new Random(53).nextBytes(bytes);
        Path original = Files.write(tmp.resolve("limit.bin"), bytes);
        var result = await(alice.sendAttachment(TargetType.PRIVATE, "bob", original));
        Path downloaded = tmp.resolve("limit-downloaded.bin");
        await(bob.saveAttachment(result.message().id(), result.size(), downloaded));
        assertEquals(-1, Files.mismatch(original, downloaded));

        Path empty = Files.createFile(tmp.resolve("empty.txt"));
        var emptyResult = await(alice.sendAttachment(TargetType.PRIVATE, "bob", empty));
        Path emptyCopy = tmp.resolve("empty-downloaded.txt");
        await(bob.saveAttachment(emptyResult.message().id(), emptyResult.size(), emptyCopy));
        assertEquals(0, Files.size(emptyCopy));

        Files.write(original, new byte[]{1}, java.nio.file.StandardOpenOption.APPEND);
        assertTransferError(ErrorCode.ATTACHMENT_TOO_LARGE,
                alice.sendAttachment(TargetType.PRIVATE, "bob", original));
        var history = await(bob.loadHistory(TargetType.PRIVATE, "alice", 0, 50));
        assertEquals(List.of("limit.bin", "empty.txt"), history.messages().stream().map(m -> m.text()).toList());
    }

    private SocketChatClient desktopClient(String user, boolean register) throws Exception {
        var client = new SocketChatClient(Runnable::run);
        desktopClients.add(client);
        var connected = new CountDownLatch(1);
        client.addEventListener(event -> {
            if (event instanceof ServerEvent.ConnectionStateChanged state && state.state() == ConnectionState.CONNECTED) {
                connected.countDown();
            }
        });
        client.connect("127.0.0.1", port);
        assertTrue(connected.await(5, TimeUnit.SECONDS));
        if (register) await(client.register(user, "password1".toCharArray()));
        await(client.login(user, "password1".toCharArray()));
        return client;
    }

    private static <T> T await(CompletableFuture<T> future) throws Exception {
        return future.get(8, TimeUnit.SECONDS);
    }

    private static void assertTransferError(ErrorCode code, CompletableFuture<?> future) throws Exception {
        var failure = assertThrows(ExecutionException.class, () -> await(future));
        assertEquals(code, assertInstanceOf(ChatException.class, failure.getCause()).code());
    }
}
