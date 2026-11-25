package com.chatsphere.client.net;

import com.chatsphere.common.model.*;
import com.chatsphere.common.protocol.ChatException;
import com.chatsphere.common.protocol.Codec;
import com.chatsphere.common.protocol.ErrorCode;
import com.chatsphere.common.protocol.Limits;
import com.chatsphere.common.protocol.Protocol;
import com.chatsphere.common.protocol.Validation;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The real ChatClient: talks to the server over TCP.
 *
 * Threads (all daemon, so they never keep the JVM alive):
 *  - one READER thread per connection: blocking read of lines, Codec.decode, route to a pending request or an event;
 *  - one single-thread WRITER executor: the only code that writes to the socket (so a slow network never blocks the
 *    caller, and frames are written in the order they were submitted);
 *  - one SCHEDULER: PING every 15 s, request timeouts (10 s) and reconnect/backoff waits.
 *
 * Every future completion and every listener call goes through uiExecutor.execute(...). This class never touches JavaFX.
 *
 * Each socket is wrapped in a Conn object. A reader thread only acts while its Conn is still THE current one
 * (this.conn == c), so after a reconnect an old reader can never deliver anything: there is only ever one live reader.
 */
public class SocketChatClient implements ChatClient {
    private static final Logger LOG = Logger.getLogger(SocketChatClient.class.getName());
    static final String READER_THREAD_NAME = "chatsphere-client-reader";
    private static final String UNREACHABLE = "Can't reach server";
    private static final int FAILED_ATTEMPTS_BEFORE_OFFLINE = 5;
    /** Frame names that answer a request. Anything else without a push meaning is ignored (forward compatibility). */
    private static final Set<String> REPLY_FRAMES = Set.of(Protocol.OK, Protocol.ERROR, Protocol.LOGIN_OK, Protocol.PONG,
            Protocol.SENT, Protocol.ROOM_LIST_RESULT, Protocol.ROOM_MEMBERS_RESULT, Protocol.USER_LIST_RESULT,
            Protocol.HISTORY_RESULT, Protocol.SEARCH_RESULT, Protocol.FILE_DATA);

    /** All time values in one place so tests can run fast. Production uses defaults(). */
    static final class Timing {
        final long pingMs, silenceMs, requestTimeoutMs, connectTimeoutMs, slowRetryMs;
        final long[] backoffMs;

        Timing(long pingMs, long silenceMs, long requestTimeoutMs, long connectTimeoutMs, long[] backoffMs, long slowRetryMs) {
            this.pingMs = pingMs;
            this.silenceMs = silenceMs;
            this.requestTimeoutMs = requestTimeoutMs;
            this.connectTimeoutMs = connectTimeoutMs;
            this.backoffMs = backoffMs.clone();
            this.slowRetryMs = slowRetryMs;
        }

        static Timing defaults() {
            return new Timing(Limits.PING_INTERVAL_SEC * 1000L, Limits.SERVER_READ_TIMEOUT_SEC * 1000L,
                    Limits.REQUEST_TIMEOUT_SEC * 1000L, 3000, new long[]{1000, 2000, 4000, 8000}, 15_000);
        }
    }

    /** One request waiting for its reply. */
    private static final class Pending {
        final Consumer<List<String>> onReply;      // receives the reply frame (not an ERROR)
        final Consumer<ChatException> onError;
        volatile ScheduledFuture<?> timeout;

        Pending(Consumer<List<String>> onReply, Consumer<ChatException> onError) {
            this.onReply = onReply;
            this.onError = onError;
        }

        void cancelTimeout() {
            ScheduledFuture<?> t = timeout;
            if (t != null) t.cancel(false);
        }

        void succeed(List<String> frame) {
            try {
                onReply.accept(frame);
            } catch (ChatException e) {
                onError.accept(e);
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "Could not process a reply", e);
                onError.accept(new ChatException(ErrorCode.SERVER_ERROR));
            }
        }
    }

    /** One TCP connection with its own reader thread and heartbeat. */
    private final class Conn {
        final Socket socket;
        final OutputStream out;
        volatile ScheduledFuture<?> heartbeat;
        volatile boolean restoring;                 // true while the silent re-login of a reconnect is in flight

        Conn(Socket socket) throws IOException {
            this.socket = socket;
            this.out = socket.getOutputStream();
        }

        void close() {
            ScheduledFuture<?> h = heartbeat;
            if (h != null) h.cancel(false);
            try { socket.close(); } catch (IOException ignored) { }
        }
    }

    private final Executor ui;
    private final Timing timing;
    private final AtomicBoolean uploading = new AtomicBoolean();
    private final ExecutorService writer = Executors.newSingleThreadExecutor(daemon("chatsphere-client-writer"));
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(daemon("chatsphere-client-scheduler"));
    private final AtomicLong reqCounter = new AtomicLong();
    private final ConcurrentHashMap<String, Pending> pending = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<Consumer<ServerEvent>> listeners = new CopyOnWriteArrayList<>();

    private final Object lock = new Object();          // guards everything below that is not volatile
    private volatile Conn conn;                        // the live connection, or null
    private volatile ConnectionState state = ConnectionState.OFFLINE;
    private int lastRetry;
    private String lastDetail = "";
    private String host;
    private int port;
    private boolean wantConnection;                    // false after disconnect()/logout()/session end/first connect failure
    private boolean connecting;                        // a connect attempt is running (never two at once)
    private boolean sessionActive;                     // logged in: credentials are kept, a drop means "reconnect"
    private boolean shutdown;
    private String user;
    private char[] password;
    private int failedAttempts;
    private ScheduledFuture<?> reconnectTask;

    /** The executor runs every future completion and every listener (Platform::runLater in the app, Runnable::run in tests). */
    public SocketChatClient(Executor uiExecutor) { this(uiExecutor, Timing.defaults()); }

    SocketChatClient(Executor uiExecutor, Timing timing) {
        this.ui = uiExecutor;
        this.timing = timing;
    }

    private static ThreadFactory daemon(String name) {
        return r -> {
            Thread t = new Thread(r, name);
            t.setDaemon(true);
            return t;
        };
    }

    // =================================================================== connection
    @Override
    public void connect(String host, int port) {
        synchronized (lock) {
            if (shutdown || state == ConnectionState.CONNECTED || state == ConnectionState.CONNECTING) return;
            this.host = host;
            this.port = port;
            wantConnection = true;
            cancelReconnectLocked();
            setStateLocked(ConnectionState.CONNECTING, 0, "");
        }
        scheduler.execute(guarded(this::attemptConnect));
    }

    @Override
    public void disconnect() {
        Conn c;
        synchronized (lock) {
            wantConnection = false;
            sessionActive = false;
            wipeCredentialsLocked();
            cancelReconnectLocked();
            c = conn;
            conn = null;
            setStateLocked(ConnectionState.OFFLINE, 0, "");
        }
        if (c != null) c.close();
        failAllPending();
    }

    /** Stops the background threads for good (call when the application exits). The client is unusable afterwards. */
    public void shutdown() {
        synchronized (lock) { shutdown = true; }
        disconnect();
        writer.shutdownNow();
        scheduler.shutdownNow();
    }

    @Override
    public void retryNow() {
        synchronized (lock) {
            if (shutdown || host == null || conn != null || state == ConnectionState.CONNECTED || state == ConnectionState.CONNECTING) return;
            cancelReconnectLocked();                                   // skip whatever backoff wait is running
            wantConnection = true;
            setStateLocked(sessionActive ? ConnectionState.RECONNECTING : ConnectionState.CONNECTING, 0, "");
        }
        scheduler.execute(guarded(this::attemptConnect));
    }

    @Override
    public ConnectionState getConnectionState() { return state; }

    @Override
    public Runnable addEventListener(Consumer<ServerEvent> listener) {
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    /** One connect attempt, run on the scheduler thread. Never runs twice at the same time. */
    private void attemptConnect() {
        String h;
        int p;
        synchronized (lock) {
            if (shutdown || !wantConnection || connecting || conn != null) return;
            connecting = true;
            reconnectTask = null;
            h = host;
            p = port;
        }
        Socket s = new Socket();
        Conn c;
        try {
            s.connect(new InetSocketAddress(h, p), (int) timing.connectTimeoutMs);
            s.setTcpNoDelay(true);
            s.setSoTimeout((int) timing.silenceMs);              // nothing received for this long = dead connection
            c = new Conn(s);
        } catch (IOException | RuntimeException e) {
            closeQuietly(s);
            onConnectFailed();
            return;
        }
        boolean relogin;
        String u;
        char[] pw;
        synchronized (lock) {
            connecting = false;
            if (shutdown || !wantConnection) {
                closeQuietly(s);
                return;
            }
            conn = c;
            relogin = sessionActive;
            u = user;
            pw = password == null ? null : password.clone();
            c.restoring = relogin;
            if (!relogin) setStateLocked(ConnectionState.CONNECTED, 0, "");
        }
        Thread reader = new Thread(() -> readLoop(c), READER_THREAD_NAME);
        reader.setDaemon(true);
        reader.start();
        try {
            c.heartbeat = scheduler.scheduleWithFixedDelay(guarded(() -> sendPing(c)), timing.pingMs, timing.pingMs, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            return;                                               // shut down meanwhile
        }
        if (relogin && pw != null) restoreSession(c, u, pw);
    }

    private void onConnectFailed() {
        synchronized (lock) {
            connecting = false;
            if (shutdown || !wantConnection) return;
            if (sessionActive) {
                failedAttempts++;
                scheduleReconnectLocked();
            } else {
                wantConnection = false;
                setStateLocked(ConnectionState.OFFLINE, 0, UNREACHABLE);
            }
        }
    }

    /** Backoff 1, 2, 4, 8 s, then every 15 s. After 5 failed attempts we keep trying but report OFFLINE. */
    private void scheduleReconnectLocked() {
        long delay = failedAttempts < timing.backoffMs.length ? timing.backoffMs[failedAttempts] : timing.slowRetryMs;
        if (failedAttempts >= FAILED_ATTEMPTS_BEFORE_OFFLINE) setStateLocked(ConnectionState.OFFLINE, 0, UNREACHABLE);
        else setStateLocked(ConnectionState.RECONNECTING, (int) Math.ceil(delay / 1000.0), "");
        cancelReconnectLocked();
        try {
            reconnectTask = scheduler.schedule(guarded(this::attemptConnect), delay, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            // shut down
        }
    }

    private void cancelReconnectLocked() {
        if (reconnectTask != null) {
            reconnectTask.cancel(false);
            reconnectTask = null;
        }
    }

    /** Connection died (read error, write error, timeout, EOF). Safe to call from any thread, any number of times. */
    private void onConnectionLost(Conn c, String why) {
        synchronized (lock) {
            if (conn != c) return;                               // stale connection, or already handled
            conn = null;
        }
        LOG.fine("Connection lost: " + why);
        c.close();
        failAllPending();                                        // pending requests fail with NETWORK_ERROR
        synchronized (lock) {
            if (shutdown || !wantConnection) {
                setStateLocked(ConnectionState.OFFLINE, 0, "");
            } else if (sessionActive) {
                failedAttempts = c.restoring ? failedAttempts + 1 : 0;
                scheduleReconnectLocked();
            } else {
                wantConnection = false;
                setStateLocked(ConnectionState.OFFLINE, 0, "Connection lost");
            }
        }
    }

    /** The session is over for good (SESSION_REPLACED, or the saved credentials were rejected). No reconnect. */
    private void endSession(Conn c, String reason) {
        synchronized (lock) {
            if (conn != c) return;
            conn = null;
            wantConnection = false;
            sessionActive = false;
            wipeCredentialsLocked();
            cancelReconnectLocked();
        }
        c.close();
        failAllPending();
        emit(new ServerEvent.SessionEnded(reason));
        synchronized (lock) { setStateLocked(ConnectionState.OFFLINE, 0, ""); }
    }

    /** After a reconnect: silently log in again with the credentials kept in memory. */
    private void restoreSession(Conn c, String u, char[] pw) {
        String id = nextId();
        byte[] bytes = frameBytes(Protocol.LOGIN, id, List.of(u, new String(pw)));
        Arrays.fill(pw, '\0');
        Pending p = new Pending(f -> {
            if (!f.get(0).equals(Protocol.LOGIN_OK)) throw new ChatException(ErrorCode.SERVER_ERROR);
            synchronized (lock) {
                if (conn != c) return;
                c.restoring = false;
                failedAttempts = 0;
                setStateLocked(ConnectionState.CONNECTED, 0, "");
            }
            emit(new ServerEvent.SessionRestored());
        }, err -> {
            if (err.code() == ErrorCode.BAD_CREDENTIALS) endSession(c, "Your sign-in is no longer valid. Please sign in again.");
            else onConnectionLost(c, "re-login failed: " + err.code());
        });
        register(id, p);
        write(c, bytes);
    }

    // =================================================================== session
    @Override
    public CompletableFuture<Void> register(String username, char[] password) {
        return request(Protocol.REGISTER, List.of(nz(username), new String(password)), f -> { expectOk(f); return null; });
    }

    @Override
    public CompletableFuture<Void> login(String username, char[] password) {
        char[] copy = password.clone();                           // kept for silent re-login only if the login succeeds
        CompletableFuture<Void> result = request(Protocol.LOGIN, List.of(nz(username), new String(password)), f -> {
            if (!f.get(0).equals(Protocol.LOGIN_OK)) throw new ChatException(ErrorCode.SERVER_ERROR);
            synchronized (lock) {
                wipeCredentialsLocked();
                user = username;
                this.password = copy;
                sessionActive = true;
            }
            return null;
        });
        result.whenComplete((v, e) -> { if (e != null) Arrays.fill(copy, '\0'); });
        return result;
    }

    @Override
    public CompletableFuture<Void> logout() {
        CompletableFuture<Void> done = new CompletableFuture<>();
        Conn c;
        synchronized (lock) {
            wantConnection = false;                               // no reconnect from now on
            sessionActive = false;
            wipeCredentialsLocked();
            cancelReconnectLocked();
            c = state == ConnectionState.CONNECTED ? conn : null;
        }
        if (c == null) {
            disconnect();
            completeOnUi(done, null);
            return done;
        }
        String id = nextId();
        Runnable finish = () -> { disconnect(); completeOnUi(done, null); };   // however LOGOUT ends, we are logged out
        register(id, new Pending(f -> finish.run(), e -> finish.run()));
        write(c, frameBytes(Protocol.LOGOUT, id, List.of()));
        return done;
    }

    // =================================================================== rooms & users
    @Override
    public CompletableFuture<List<RoomSummary>> listRooms(boolean onlyMine) {
        return request(Protocol.ROOM_LIST, List.of(onlyMine ? Protocol.SCOPE_MINE : Protocol.SCOPE_ALL), ProtocolParser::parseRoomList);
    }

    @Override
    public CompletableFuture<Void> createRoom(String room) {
        return request(Protocol.ROOM_CREATE, List.of(nz(room)), f -> { expectOk(f); return null; });
    }

    @Override
    public CompletableFuture<Void> joinRoom(String room) {
        return request(Protocol.ROOM_JOIN, List.of(nz(room)), f -> { expectOk(f); return null; });
    }

    @Override
    public CompletableFuture<Void> leaveRoom(String room) {
        return request(Protocol.ROOM_LEAVE, List.of(nz(room)), f -> { expectOk(f); return null; });
    }

    @Override
    public CompletableFuture<List<MemberInfo>> listRoomMembers(String room) {
        return request(Protocol.ROOM_MEMBERS, List.of(nz(room)), ProtocolParser::parseRoomMembers);
    }

    @Override
    public CompletableFuture<List<UserSummary>> listUsers() {
        return request(Protocol.USER_LIST, List.of(), ProtocolParser::parseUserList);
    }

    // =================================================================== messaging
    @Override
    public CompletableFuture<HistoryPage> loadHistory(TargetType type, String target, long beforeId, int limit) {
        return request(Protocol.HISTORY, List.of(type.name(), nz(target), Codec.str(beforeId), Codec.str((long) limit)), ProtocolParser::parseHistory);
    }

    @Override
    public CompletableFuture<HistoryPage> loadHistoryAround(TargetType type, String target, long messageId) {
        return request(Protocol.HISTORY_AROUND, List.of(type.name(), nz(target), Codec.str(messageId)), ProtocolParser::parseHistory);
    }

    @Override
    public CompletableFuture<SentAck> sendRoomMessage(String room, String text) {
        return request(Protocol.MSG, List.of(nz(room), nz(text)), ProtocolParser::parseSent);
    }

    @Override
    public CompletableFuture<SentAck> sendPrivateMessage(String user, String text) {
        return request(Protocol.PM, List.of(nz(user), nz(text)), ProtocolParser::parseSent);
    }

    /** Fire and forget: reqId 0, no pending entry, silently dropped when not connected. */
    @Override
    public void sendTyping(TargetType type, String target) {
        Conn c = connectedConn();
        if (c == null) return;
        write(c, frameBytes(Protocol.TYPING, "0", List.of(type.name(), nz(target))));
    }

    @Override
    public CompletableFuture<Void> markRead(TargetType type, String target, long upToMessageId) {
        return request(Protocol.MARK_READ, List.of(type.name(), nz(target), Codec.str(upToMessageId)), f -> { expectOk(f); return null; });
    }

    // =================================================================== search
    @Override
    public CompletableFuture<List<SearchHit>> search(SearchQuery q) {
        boolean current = q.currentOnly();
        List<String> fields = List.of(nz(q.keyword()), nz(q.sender()), Codec.str(q.fromTs()), Codec.str(q.toTs()),
                current ? Protocol.SCOPE_CURRENT : Protocol.SCOPE_ALL,
                current && q.targetType() != null ? q.targetType().name() : "",
                current ? nz(q.target()) : "");
        return request(Protocol.SEARCH, fields, ProtocolParser::parseSearch);
    }

    // =================================================================== requests
    @Override public boolean supportsAttachments() { return true; }

    @Override
    public CompletableFuture<AttachmentAck> sendAttachment(TargetType type, String target, Path file) {
        CompletableFuture<AttachmentAck> result = new CompletableFuture<>();
        Conn connection = connectedConn();
        if (!uploading.compareAndSet(false, true)) {
            failOnUi(result, new ChatException(ErrorCode.TRANSFER_IN_PROGRESS));
            return result;
        }
        result.whenComplete((ack, error) -> uploading.set(false));
        Thread.ofVirtual().name("chatsphere-upload").start(() -> {
            try {
                int size = upload(connection, type, target, file);
                request(connection, Protocol.FILE_SEND, List.of(),
                        fields -> new AttachmentAck(ProtocolParser.parseSent(fields), size), result);
            } catch (IOException | SecurityException e) {
                failOnUi(result, new ChatException(ErrorCode.FILE_IO_ERROR));
            } catch (RuntimeException e) {
                failOnUi(result, transferError(e));
            }
        });
        return result;
    }

    private int upload(Conn connection, TargetType type, String target, Path file) throws IOException {
        String filename = file.getFileName().toString();
        Validation.requireAttachmentName(filename);
        if (!Files.isRegularFile(file)) throw new ChatException(ErrorCode.FILE_IO_ERROR);
        long length = Files.size(file);
        if (length > Limits.ATTACHMENT_MAX_BYTES) throw new ChatException(ErrorCode.ATTACHMENT_TOO_LARGE);
        int size = (int) length;

        try (InputStream input = Files.newInputStream(file)) {
            request(connection, Protocol.FILE_START, List.of(type.name(), target, filename, Codec.str(size)),
                    SocketChatClient::parseOk).join();
            int remaining = size;
            while (remaining > 0) {
                int count = Math.min(remaining, Limits.ATTACHMENT_CHUNK_BYTES);
                byte[] chunk = input.readNBytes(count);
                if (chunk.length != count) throw new IOException("File shortened during upload");
                request(connection, Protocol.FILE_CHUNK, List.of(Base64.getEncoder().encodeToString(chunk)),
                        SocketChatClient::parseOk).join();
                remaining -= count;
            }
            if (input.read() != -1) throw new IOException("File grew during upload");
        }
        return size;
    }

    @Override
    public CompletableFuture<Void> saveAttachment(long messageId, int size, Path destination) {
        CompletableFuture<Void> result = new CompletableFuture<>();
        Conn connection = connectedConn();
        Thread.ofVirtual().name("chatsphere-download").start(() -> {
            try {
                download(connection, messageId, size, destination);
                completeOnUi(result, null);
            } catch (IOException | SecurityException e) {
                failOnUi(result, new ChatException(ErrorCode.FILE_IO_ERROR));
            } catch (RuntimeException e) {
                failOnUi(result, transferError(e));
            }
        });
        return result;
    }

    private void download(Conn connection, long messageId, int size, Path destination) throws IOException {
        Validation.requireAttachmentSize(size);
        Path target = destination.toAbsolutePath();
        Path temporary = Files.createTempFile(target.getParent(), ".chatsphere-", ".part");
        try {
            try (OutputStream output = Files.newOutputStream(temporary)) {
                int offset = 0;
                do {
                    byte[] chunk = request(connection, Protocol.FILE_GET, List.of(Codec.str(messageId), Codec.str(offset)),
                            SocketChatClient::parseFileData).join();
                    if (chunk.length != Math.min(size - offset, Limits.ATTACHMENT_CHUNK_BYTES)) {
                        throw new ChatException(ErrorCode.BAD_REQUEST, "unexpected attachment length");
                    }
                    output.write(chunk);
                    offset += chunk.length;
                } while (offset < size);
            }
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static Void parseOk(List<String> fields) {
        expectOk(fields);
        return null;
    }

    private static byte[] parseFileData(List<String> fields) {
        Codec.requireSize(fields, 3);
        if (!Protocol.FILE_DATA.equals(fields.get(0))) throw new ChatException(ErrorCode.BAD_REQUEST);
        try {
            return Base64.getDecoder().decode(fields.get(2));
        } catch (IllegalArgumentException e) {
            throw new ChatException(ErrorCode.BAD_REQUEST, "invalid attachment data");
        }
    }

    private static ChatException transferError(RuntimeException failure) {
        Throwable cause = failure instanceof CompletionException ? failure.getCause() : failure;
        return cause instanceof ChatException chat ? chat : new ChatException(ErrorCode.SERVER_ERROR);
    }

    private Conn connectedConn() {
        synchronized (lock) { return state == ConnectionState.CONNECTED ? conn : null; }
    }

    /**
     * Sends one request and returns a future for its reply. Fails at once with NOT_CONNECTED when offline (requests are
     * never queued). The parser runs on the reader thread; the future completes through the UI executor.
     */
    private <T> CompletableFuture<T> request(String cmd, List<String> fields, Function<List<String>, T> parser) {
        return request(connectedConn(), cmd, fields, parser);
    }

    private <T> CompletableFuture<T> request(Conn c, String cmd, List<String> fields, Function<List<String>, T> parser) {
        CompletableFuture<T> future = new CompletableFuture<>();
        request(c, cmd, fields, parser, future);
        return future;
    }

    private <T> void request(Conn c, String cmd, List<String> fields, Function<List<String>, T> parser,
                             CompletableFuture<T> future) {
        if (c == null || c != connectedConn()) {
            failOnUi(future, new ChatException(ErrorCode.NOT_CONNECTED));
            return;
        }
        String id = nextId();
        byte[] bytes = frameBytes(cmd, id, fields);
        if (bytes.length - 1 > Limits.MAX_LINE_BYTES) {           // the server would close the connection: refuse locally
            failOnUi(future, new ChatException(ErrorCode.BAD_REQUEST, "request too large"));
            return;
        }
        register(id, new Pending(f -> completeOnUi(future, parser.apply(f)), e -> failOnUi(future, e)));
        write(c, bytes);
    }

    private String nextId() { return String.valueOf(reqCounter.incrementAndGet()); }

    /** Stores the pending request and starts its timeout (10 s: fails with TIMEOUT). */
    private void register(String id, Pending p) {
        pending.put(id, p);
        try {
            p.timeout = scheduler.schedule(guarded(() -> {
                Pending x = pending.remove(id);
                if (x != null) x.onError.accept(new ChatException(ErrorCode.TIMEOUT));
            }), timing.requestTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            pending.remove(id);
        }
    }

    private void failAllPending() {
        for (String id : new ArrayList<>(pending.keySet())) {
            Pending p = pending.remove(id);
            if (p == null) continue;
            p.cancelTimeout();
            p.onError.accept(new ChatException(ErrorCode.NETWORK_ERROR));
        }
    }

    private static byte[] frameBytes(String cmd, String reqId, List<String> fields) {
        List<String> all = new ArrayList<>(fields.size() + 1);
        all.add(reqId);
        all.addAll(fields);
        return (Codec.encode(cmd, all) + "\n").getBytes(StandardCharsets.UTF_8);
    }

    /** Hands the bytes to the single writer thread: the ONLY place that writes to a socket. */
    private void write(Conn c, byte[] bytes) {
        try {
            writer.execute(() -> {
                if (conn != c) return;
                try {
                    c.out.write(bytes);
                    c.out.flush();
                } catch (IOException e) {
                    onConnectionLost(c, "write failed");
                }
            });
        } catch (RejectedExecutionException e) {
            // shut down
        }
    }

    private void sendPing(Conn c) {
        if (conn != c) return;
        write(c, frameBytes(Protocol.PING, nextId(), List.of()));
    }

    // =================================================================== reader (one thread per connection)
    private void readLoop(Conn c) {
        try {
            InputStream in = new BufferedInputStream(c.socket.getInputStream(), 8192);
            byte[] line = new byte[Limits.MAX_RESULT_LINE_BYTES];
            int len = 0;
            while (true) {
                int b = in.read();
                if (b < 0) { onConnectionLost(c, "server closed the connection"); return; }
                if (b == '\n') {
                    String text = new String(line, 0, len, StandardCharsets.UTF_8);
                    len = 0;
                    if (!handleLine(c, text)) return;
                } else if (len == line.length) {
                    onConnectionLost(c, "line too long");
                    return;
                } else {
                    line[len++] = (byte) b;
                }
            }
        } catch (SocketTimeoutException e) {
            onConnectionLost(c, "no data from server");
        } catch (IOException e) {
            onConnectionLost(c, e.getClass().getSimpleName());
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Reader failed", e);
            onConnectionLost(c, "reader error");
        }
    }

    /** Returns false when this reader must stop. */
    private boolean handleLine(Conn c, String text) {
        if (conn != c) return false;                             // we were replaced or closed: stop reading
        List<String> f;
        ServerEvent ev;
        try {
            f = Codec.decode(text);
            ev = ProtocolParser.parsePush(f);
        } catch (ChatException e) {
            LOG.warning("Ignoring a malformed frame from the server");
            return true;
        }
        if (ev != null) {
            if (ev instanceof ServerEvent.SessionEnded se) {      // SESSION_REPLACED
                endSession(c, se.reason());
                return false;
            }
            emit(ev);
            return true;
        }
        if (f.size() < 2 || !REPLY_FRAMES.contains(f.get(0))) return true;   // unknown frame: ignore
        Pending p = pending.remove(f.get(1));                     // PONGs and replies that arrive after a timeout find nothing
        if (p == null) return true;
        p.cancelTimeout();
        if (f.get(0).equals(Protocol.ERROR)) {
            ErrorCode code = f.size() >= 3 ? ErrorCode.fromWire(f.get(2)) : ErrorCode.SERVER_ERROR;
            p.onError.accept(new ChatException(code));
        } else {
            p.succeed(f);
        }
        return true;
    }

    // =================================================================== UI hand-off, state, helpers
    private <T> void completeOnUi(CompletableFuture<T> f, T value) { ui(() -> f.complete(value)); }

    private void failOnUi(CompletableFuture<?> f, ChatException e) { ui(() -> f.completeExceptionally(e)); }

    private void emit(ServerEvent ev) {
        ui(() -> {
            for (Consumer<ServerEvent> l : listeners) {
                try { l.accept(ev); } catch (RuntimeException e) { LOG.log(Level.WARNING, "Event listener failed", e); }
            }
        });
    }

    private void ui(Runnable r) {
        try {
            ui.execute(r);
        } catch (RejectedExecutionException e) {
            LOG.fine("UI executor rejected a task");
        }
    }

    private void setStateLocked(ConnectionState s, int retry, String detail) {
        if (s == state && retry == lastRetry && detail.equals(lastDetail)) return;
        state = s;
        lastRetry = retry;
        lastDetail = detail;
        emit(new ServerEvent.ConnectionStateChanged(s, retry, detail));
    }

    private void wipeCredentialsLocked() {
        if (password != null) Arrays.fill(password, '\0');
        password = null;
        user = null;
    }

    /** A scheduled task that dies on an exception is never run again, so every body is wrapped. */
    private static Runnable guarded(Runnable r) {
        return () -> {
            try { r.run(); } catch (Throwable t) { LOG.log(Level.WARNING, "Scheduled task failed", t); }
        };
    }

    private static void expectOk(List<String> f) {
        if (!f.get(0).equals(Protocol.OK)) throw new ChatException(ErrorCode.SERVER_ERROR);
    }

    private static String nz(String s) { return s == null ? "" : s; }

    private static void closeQuietly(Socket s) {
        try { s.close(); } catch (IOException ignored) { }
    }
}
