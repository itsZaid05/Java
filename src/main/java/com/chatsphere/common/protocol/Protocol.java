package com.chatsphere.common.protocol;

/** Command and event names. Never type these strings anywhere else. */
public final class Protocol {
    private Protocol() {}
    public static final int DEFAULT_PORT = 5050;

    // ---- client -> server (field 1 is always reqId) ----
    public static final String REGISTER = "REGISTER", LOGIN = "LOGIN", LOGOUT = "LOGOUT", PING = "PING";
    public static final String ROOM_LIST = "ROOM_LIST", ROOM_CREATE = "ROOM_CREATE", ROOM_JOIN = "ROOM_JOIN",
            ROOM_LEAVE = "ROOM_LEAVE", ROOM_MEMBERS = "ROOM_MEMBERS", USER_LIST = "USER_LIST";
    public static final String HISTORY = "HISTORY", HISTORY_AROUND = "HISTORY_AROUND";
    public static final String MSG = "MSG", PM = "PM", TYPING = "TYPING", MARK_READ = "MARK_READ", SEARCH = "SEARCH";

    public static final String FILE_START = "FILE_START", FILE_CHUNK = "FILE_CHUNK",
            FILE_SEND = "FILE_SEND", FILE_GET = "FILE_GET", FILE_DATA = "FILE_DATA";

    // ---- server -> client: replies (field 1 is the reqId of the request) ----
    public static final String OK = "OK", ERROR = "ERROR", LOGIN_OK = "LOGIN_OK", PONG = "PONG", SENT = "SENT";
    public static final String ROOM_LIST_RESULT = "ROOM_LIST_RESULT", ROOM_MEMBERS_RESULT = "ROOM_MEMBERS_RESULT",
            USER_LIST_RESULT = "USER_LIST_RESULT", HISTORY_RESULT = "HISTORY_RESULT", SEARCH_RESULT = "SEARCH_RESULT";

    // ---- server -> client: pushes (no reqId) ----
    public static final String MSG_PUSH = "MSG_PUSH", PM_PUSH = "PM_PUSH", USER_STATUS = "USER_STATUS",
            TYPING_PUSH = "TYPING_PUSH", ACK_PUSH = "ACK_PUSH", ROOM_MEMBERS_CHANGED = "ROOM_MEMBERS_CHANGED",
            SESSION_REPLACED = "SESSION_REPLACED";

    // ---- enumerated field values ----
    public static final String SCOPE_MINE = "MINE", SCOPE_ALL = "ALL", SCOPE_CURRENT = "CURRENT";
    public static final String JOINED = "JOINED", LEFT = "LEFT";
}
