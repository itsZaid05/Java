package com.chatsphere.common.protocol;

/** Error codes. Wire codes travel in ERROR frames; LOCAL codes are produced by the client only. */
public enum ErrorCode {
    // ---- wire codes (server -> client) ----
    BAD_REQUEST, UNKNOWN_COMMAND, NOT_LOGGED_IN, BAD_CREDENTIALS,
    USERNAME_TAKEN, INVALID_USERNAME, INVALID_PASSWORD,
    ROOM_EXISTS, INVALID_ROOM_NAME, NO_SUCH_ROOM, NOT_MEMBER,
    NO_SUCH_USER, INVALID_TARGET, INVALID_MESSAGE, SERVER_ERROR,
    INVALID_ATTACHMENT, ATTACHMENT_TOO_LARGE, ATTACHMENT_UNAVAILABLE,
    // ---- local codes (never sent on the wire) ----
    NETWORK_ERROR, TIMEOUT, NOT_CONNECTED, FILE_IO_ERROR, TRANSFER_IN_PROGRESS;

    public boolean isLocal() {
        return this == NETWORK_ERROR || this == TIMEOUT || this == NOT_CONNECTED || this == FILE_IO_ERROR || this == TRANSFER_IN_PROGRESS;
    }

    /** Unknown wire strings map to SERVER_ERROR so a newer server never crashes an older client. */
    public static ErrorCode fromWire(String s) {
        try { return valueOf(s); } catch (IllegalArgumentException | NullPointerException e) { return SERVER_ERROR; }
    }
}
