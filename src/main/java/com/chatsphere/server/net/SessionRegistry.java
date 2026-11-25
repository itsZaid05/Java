package com.chatsphere.server.net;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Who is online right now. Online/offline state lives ONLY here (never in the database).
 *
 * It is a thin wrapper around a ConcurrentHashMap, so every method is atomic on its own and no extra lock is needed.
 * Sending is non-blocking (ClientHandler.send only calls queue.offer), so a broadcast can never get stuck on a slow
 * client and we never hold a lock while talking to other clients.
 */
public final class SessionRegistry {
    private final ConcurrentHashMap<String, ClientHandler> online = new ConcurrentHashMap<>();

    /** Registers the handler for the user. Returns the handler it replaced (same user logged in elsewhere) or null. */
    public ClientHandler register(String user, ClientHandler handler) {
        return online.put(user, handler);
    }

    /**
     * Removes the user only if the registered handler is still the given one. Returns true if it removed it.
     * This is what stops an old, replaced connection from marking the NEW session offline.
     */
    public boolean unregister(String user, ClientHandler handler) {
        return online.remove(user, handler);
    }

    public boolean isOnline(String user) { return online.containsKey(user); }

    public int onlineCount() { return online.size(); }

    /** Reports whether the recipient's active connection accepted the frame for sending. */
    public boolean sendTo(String user, String frame) {
        ClientHandler h = online.get(user);
        return h != null && h.send(frame);
    }

    /** Queues a frame for every online user except exceptUser (may be null). */
    public void broadcast(String frame, String exceptUser) {
        for (Map.Entry<String, ClientHandler> e : online.entrySet()) {
            if (e.getKey().equals(exceptUser)) continue;
            e.getValue().send(frame);
        }
    }
}
