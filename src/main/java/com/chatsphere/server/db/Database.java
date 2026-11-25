package com.chatsphere.server.db;

import com.chatsphere.common.protocol.ChatException;
import com.chatsphere.common.protocol.ErrorCode;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * One shared SQLite connection, the schema, the pragmas and a transaction helper.
 * Callers must serialize access (ChatServiceImpl synchronizes its public methods).
 * Never log SQL parameters: they may contain message text.
 */
public final class Database implements AutoCloseable {
    private static final Logger LOG = Logger.getLogger(Database.class.getName());

    /** Bump when the schema changes and add a migration in {@link #migrate}. */
    public static final int SCHEMA_VERSION = 2;

    private static final String[] SCHEMA = {
        """
        CREATE TABLE IF NOT EXISTS schema_version (
          version INTEGER NOT NULL)""",
        """
        CREATE TABLE IF NOT EXISTS users (
          id INTEGER PRIMARY KEY AUTOINCREMENT,
          username TEXT NOT NULL UNIQUE,
          password_hash TEXT NOT NULL,
          created_at INTEGER NOT NULL)""",
        """
        CREATE TABLE IF NOT EXISTS rooms (
          id INTEGER PRIMARY KEY AUTOINCREMENT,
          name TEXT NOT NULL UNIQUE,
          created_by INTEGER NOT NULL REFERENCES users(id),
          created_at INTEGER NOT NULL)""",
        """
        CREATE TABLE IF NOT EXISTS room_members (
          room_id INTEGER NOT NULL REFERENCES rooms(id),
          user_id INTEGER NOT NULL REFERENCES users(id),
          last_read_id INTEGER NOT NULL DEFAULT 0,
          joined_at INTEGER NOT NULL,
          PRIMARY KEY (room_id, user_id))""",
        """
        CREATE TABLE IF NOT EXISTS messages (
          id INTEGER PRIMARY KEY AUTOINCREMENT,
          type TEXT NOT NULL CHECK (type IN ('ROOM','PRIVATE')),
          room_id INTEGER REFERENCES rooms(id),
          sender_id INTEGER NOT NULL REFERENCES users(id),
          recipient_id INTEGER REFERENCES users(id),
          text TEXT NOT NULL,
          ts INTEGER NOT NULL,
          status TEXT NOT NULL DEFAULT 'SENT' CHECK (status IN ('SENT','DELIVERED','READ')),
          CHECK ((type='ROOM' AND room_id IS NOT NULL AND recipient_id IS NULL)
              OR (type='PRIVATE' AND room_id IS NULL AND recipient_id IS NOT NULL)))""",
        """
        CREATE TABLE IF NOT EXISTS attachments (
          message_id INTEGER PRIMARY KEY REFERENCES messages(id) ON DELETE CASCADE,
          size INTEGER NOT NULL CHECK (size >= 0),
          content BLOB NOT NULL CHECK (length(content) = size))""",
        "CREATE INDEX IF NOT EXISTS idx_msg_room ON messages(room_id, id)",
        "CREATE INDEX IF NOT EXISTS idx_msg_pm ON messages(sender_id, recipient_id, id)",
        "CREATE INDEX IF NOT EXISTS idx_msg_rcpt ON messages(recipient_id, status)"
    };

    /** Database work that runs inside one transaction. */
    @FunctionalInterface
    public interface SqlWork<T> {
        T run(Connection c) throws SQLException;
    }

    private final Connection conn;

    public Database(String path) {
        try {
            conn = DriverManager.getConnection("jdbc:sqlite:" + path);
            try (Statement st = conn.createStatement()) {
                st.execute("PRAGMA foreign_keys = ON");
                st.execute("PRAGMA journal_mode = WAL");
                st.execute("PRAGMA busy_timeout = 5000");
                for (String ddl : SCHEMA) st.execute(ddl);
            }
            migrate();
        } catch (SQLException e) {
            throw serverError("cannot open database", e);
        }
    }

    private void migrate() throws SQLException {
        try (Statement st = conn.createStatement()) {
            var rs = st.executeQuery("SELECT MAX(version) FROM schema_version");
            int current = rs.next() ? rs.getInt(1) : 0; // 0 when the table is empty
            rs.close();
            if (current == 0) {
                st.execute("INSERT INTO schema_version(version) VALUES (" + SCHEMA_VERSION + ")");
            }
            if (current == 1) {
                // The idempotent schema setup above already created the attachment table.
                st.execute("UPDATE schema_version SET version = 2");
            }
        }
    }

    /** Runs the work in one transaction: commit on success, rollback on any failure. */
    public <T> T inTransaction(SqlWork<T> work) {
        try {
            conn.setAutoCommit(false);
            try {
                T result = work.run(conn);
                conn.commit();
                return result;
            } catch (SQLException | RuntimeException e) {
                rollbackQuietly();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw serverError("database error", e);
        }
    }

    private void rollbackQuietly() {
        try {
            conn.rollback();
        } catch (SQLException e) {
            LOG.log(Level.WARNING, "rollback failed", e);
        }
    }

    private static ChatException serverError(String what, SQLException cause) {
        LOG.log(Level.SEVERE, what + " (" + cause.getErrorCode() + ")", cause);
        ChatException ex = new ChatException(ErrorCode.SERVER_ERROR, what);
        ex.initCause(cause);
        return ex;
    }

    @Override
    public void close() {
        try {
            conn.close();
        } catch (SQLException e) {
            LOG.log(Level.WARNING, "close failed", e);
        }
    }
}
