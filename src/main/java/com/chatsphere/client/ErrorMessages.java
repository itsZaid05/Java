package com.chatsphere.client;

import com.chatsphere.common.protocol.ErrorCode;

/** Maps error codes to the exact user-facing copy. Owned by Person 3; everyone uses it, nobody hard-codes error text. */
public final class ErrorMessages {
    private ErrorMessages() {}

    public static String of(ErrorCode code) {
        return switch (code) {
            case BAD_CREDENTIALS -> "Username or password is incorrect.";
            case USERNAME_TAKEN -> "That username is taken.";
            case INVALID_USERNAME -> "Use 3 to 20 letters, numbers or underscores.";
            case INVALID_PASSWORD -> "Use at least 8 characters.";
            case ROOM_EXISTS -> "A room with that name already exists.";
            case INVALID_ROOM_NAME -> "Use 3 to 30 letters, numbers, dashes or underscores.";
            case NO_SUCH_ROOM -> "That room no longer exists.";
            case NOT_MEMBER -> "You are not a member of this room.";
            case NO_SUCH_USER -> "That user does not exist.";
            case INVALID_TARGET -> "You can't message yourself.";
            case INVALID_MESSAGE -> "Messages can't be empty or longer than 2000 characters.";
            case INVALID_ATTACHMENT -> "Choose a file with a valid filename.";
            case ATTACHMENT_TOO_LARGE -> "Choose a file of 5 MiB or smaller.";
            case ATTACHMENT_UNAVAILABLE -> "This attachment is unavailable.";
            case FILE_IO_ERROR -> "The file could not be read or saved.";
            case TRANSFER_IN_PROGRESS -> "Wait for the current upload to finish.";
            case NOT_LOGGED_IN -> "Please sign in again.";
            case NETWORK_ERROR -> "Connection problem. Try again.";
            case TIMEOUT -> "The server took too long to answer.";
            case NOT_CONNECTED -> "You are offline.";
            case BAD_REQUEST, UNKNOWN_COMMAND, SERVER_ERROR -> "Something went wrong. Try again.";
        };
    }

    /** Unwraps CompletionException/ExecutionException and returns the copy for the ChatException inside. */
    public static String of(Throwable t) {
        Throwable c = t;
        while (c != null && !(c instanceof com.chatsphere.common.protocol.ChatException) && c.getCause() != null) c = c.getCause();
        if (c instanceof com.chatsphere.common.protocol.ChatException ce) return of(ce.code());
        return of(ErrorCode.SERVER_ERROR);
    }

    /** The ErrorCode inside a failed future's exception (unwraps CompletionException), or null if there is none. */
    public static ErrorCode codeOf(Throwable t) {
        Throwable c = t;
        while (c != null && !(c instanceof com.chatsphere.common.protocol.ChatException) && c.getCause() != null) c = c.getCause();
        return c instanceof com.chatsphere.common.protocol.ChatException ce ? ce.code() : null;
    }
}
