package com.chatsphere.common.protocol;

import java.util.regex.Pattern;

/** The single source of truth for input rules. ChatService must call these; the UI may call them for hints. */
public final class Validation {
    public static void requireAttachmentName(String name) {
        if (name == null || name.isBlank() || name.length() > Limits.ATTACHMENT_NAME_MAX
                || name.equals(".") || name.equals("..") || name.indexOf('/') >= 0
                || name.indexOf('\\') >= 0 || name.indexOf(':') >= 0
                || name.codePoints().anyMatch(Character::isISOControl)) {
            throw new ChatException(ErrorCode.INVALID_ATTACHMENT);
        }
    }

    public static void requireAttachmentSize(int size) {
        if (size < 0) throw new ChatException(ErrorCode.INVALID_ATTACHMENT);
        if (size > Limits.ATTACHMENT_MAX_BYTES) throw new ChatException(ErrorCode.ATTACHMENT_TOO_LARGE);
    }

    private Validation() {}
    private static final Pattern USERNAME = Pattern.compile("^[A-Za-z0-9_]{3,20}$");
    private static final Pattern ROOM = Pattern.compile("^[A-Za-z0-9_-]{3,30}$");

    public static boolean isValidUsername(String s) { return s != null && USERNAME.matcher(s).matches(); }
    public static boolean isValidRoomName(String s) { return s != null && ROOM.matcher(s).matches(); }
    public static boolean isValidPassword(String s) {
        return s != null && s.length() >= Limits.PASSWORD_MIN && s.length() <= Limits.PASSWORD_MAX;
    }
    /** Non-blank and at most MESSAGE_MAX characters. */
    public static boolean isValidMessage(String s) {
        return s != null && !s.isBlank() && s.length() <= Limits.MESSAGE_MAX;
    }

    /** One-line preview for lists: whitespace collapsed, cut to PREVIEW_MAX chars with an ellipsis. */
    public static String preview(String text) {
        if (text == null) return "";
        String t = text.replaceAll("\\s+", " ").trim();
        return t.length() <= Limits.PREVIEW_MAX ? t : t.substring(0, Limits.PREVIEW_MAX - 1) + "\u2026";
    }
}
