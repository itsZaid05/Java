package com.chatsphere.common.protocol;

/** Shared numeric limits. Server enforces them; the UI mirrors them for fast feedback. */
public final class Limits {
    private Limits() {}
    public static final int USERNAME_MIN = 3, USERNAME_MAX = 20;
    public static final int PASSWORD_MIN = 8, PASSWORD_MAX = 100;
    public static final int ROOM_MIN = 3, ROOM_MAX = 30;
    public static final int MESSAGE_MAX = 2000;
    public static final int ATTACHMENT_MAX_BYTES = 5 * 1024 * 1024;
    public static final int ATTACHMENT_CHUNK_BYTES = 32 * 1024;
    public static final int ATTACHMENT_NAME_MAX = 200;
    public static final int HISTORY_DEFAULT = 50, HISTORY_MAX = 100;
    public static final int AROUND_WINDOW = 25;        // messages before AND after the target in HISTORY_AROUND
    public static final int SEARCH_MAX_RESULTS = 100;
    public static final int PREVIEW_MAX = 80;          // chars of last-message preview in lists
    public static final int MAX_LINE_BYTES = 65_536;   // longest accepted request
    public static final int MAX_RESULT_LINE_BYTES = 1024 * 1024; // batched history and search replies
    public static final int PING_INTERVAL_SEC = 15;    // client sends PING this often
    public static final int SERVER_READ_TIMEOUT_SEC = 45; // server drops a connection silent for this long
    public static final int REQUEST_TIMEOUT_SEC = 10;  // client fails a request with TIMEOUT after this
}
