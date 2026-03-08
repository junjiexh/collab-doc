package com.collabdoc.collab;

import com.collabdoc.permission.PermissionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.BinaryWebSocketHandler;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;

/**
 * WebSocket handler implementing the y-websocket sync protocol.
 * Each document is a "room" -- all sessions in the same room are synced.
 */
public class YjsWebSocketHandler extends BinaryWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(YjsWebSocketHandler.class);

    private final YrsDocumentManager docManager;
    private final PermissionService permissionService;
    private final RedisPubSubBroadcaster pubSubBroadcaster;

    private static final int SEND_TIME_LIMIT = 5000;   // 5 seconds
    private static final int SEND_BUFFER_LIMIT = 512 * 1024; // 512 KB

    // docId -> set of sessions (wrapped in ConcurrentWebSocketSessionDecorator)
    private final ConcurrentHashMap<UUID, Set<WebSocketSession>> rooms = new ConcurrentHashMap<>();
    // sessionId -> docId
    private final ConcurrentHashMap<String, UUID> sessionDocs = new ConcurrentHashMap<>();
    // sessionId -> decorated session (for thread-safe sends)
    private final ConcurrentHashMap<String, WebSocketSession> decoratedSessions = new ConcurrentHashMap<>();

    public YjsWebSocketHandler(YrsDocumentManager docManager, PermissionService permissionService,
                               RedisPubSubBroadcaster pubSubBroadcaster) {
        this.docManager = docManager;
        this.permissionService = permissionService;
        this.pubSubBroadcaster = pubSubBroadcaster;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        // Extract document ID from the URL path: /ws/{docId}
        String path = session.getUri().getPath();
        String docIdStr = path.substring(path.lastIndexOf('/') + 1);
        UUID docId;
        try {
            docId = UUID.fromString(docIdStr);
        } catch (IllegalArgumentException e) {
            log.warn("Invalid document ID in WebSocket path: {}", docIdStr);
            try { session.close(); } catch (IOException ignored) {}
            return;
        }

        // Verify permission
        UUID userId = (UUID) session.getAttributes().get("userId");
        if (userId == null) {
            try { session.close(); } catch (IOException ignored) {}
            return;
        }

        String permission = permissionService != null ? permissionService.resolvePermission(docId, userId) : null;
        if (permission == null) {
            log.warn("Unauthorized WebSocket access: user={}, doc={}", userId, docId);
            try { session.close(); } catch (IOException ignored) {}
            return;
        }

        session.getAttributes().put("permission", permission);

        // Ensure document is loaded in memory
        docManager.ensureLoaded(docId);

        // Wrap session for thread-safe concurrent sends
        var decorated = new ConcurrentWebSocketSessionDecorator(session, SEND_TIME_LIMIT, SEND_BUFFER_LIMIT);

        // Add session to room
        rooms.computeIfAbsent(docId, k -> ConcurrentHashMap.newKeySet()).add(decorated);
        sessionDocs.put(session.getId(), docId);
        decoratedSessions.put(session.getId(), decorated);

        log.info("WebSocket connected: session={}, doc={}, permission={}", session.getId(), docId, permission);

        // Send sync step 1 (server's state vector) to the client
        try {
            byte[] sv = docManager.getStateVector(docId);
            byte[] msg = YjsSyncProtocol.encodeSyncStep1(sv);
            decorated.sendMessage(new BinaryMessage(msg));
        } catch (Exception e) {
            log.error("Failed to send initial sync", e);
        }
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
        UUID docId = sessionDocs.get(session.getId());
        if (docId == null) return;

        // Use the decorated session for any replies
        WebSocketSession decorated = decoratedSessions.getOrDefault(session.getId(), session);

        ByteBuffer buf = ByteBuffer.wrap(message.getPayload().array());
        if (!buf.hasRemaining()) return;

        int msgType = buf.get() & 0xFF;

        if (msgType == YjsSyncProtocol.MSG_SYNC) {
            handleSyncMessage(decorated, docId, buf);
        } else if (msgType == YjsSyncProtocol.MSG_AWARENESS) {
            // Broadcast awareness messages to all other sessions in the room
            byte[] data = message.getPayload().array();
            broadcastToOthers(docId, decorated, data);
            pubSubBroadcaster.publishAwareness(docId, data);
        }
    }

    private void handleSyncMessage(WebSocketSession session, UUID docId, ByteBuffer buf) {
        if (!buf.hasRemaining()) return;
        int subType = buf.get() & 0xFF;

        switch (subType) {
            case YjsSyncProtocol.MSG_SYNC_STEP1 -> {
                // Client sends its state vector; respond with diff
                byte[] clientSv = YjsSyncProtocol.readPayload(buf);
                byte[] diff = docManager.encodeDiff(docId, clientSv);
                byte[] response = YjsSyncProtocol.encodeSyncStep2(diff);
                sendToSession(session, response);
            }
            case YjsSyncProtocol.MSG_SYNC_STEP2 -> {
                // Allow all users (including viewers) to receive state
                byte[] update = YjsSyncProtocol.readPayload(buf);
                docManager.applyClientUpdate(docId, update);
                // Don't broadcast STEP2 — it's part of initial sync handshake
            }
            case YjsSyncProtocol.MSG_SYNC_UPDATE -> {
                // Only owners and editors can send updates
                String perm = (String) session.getAttributes().get("permission");
                if (!"OWNER".equals(perm) && !"EDITOR".equals(perm)) {
                    log.debug("Dropping update from viewer session={}", session.getId());
                    return;
                }
                byte[] updateData = YjsSyncProtocol.readPayload(buf);
                byte[] applied = docManager.applyClientUpdate(docId, updateData);
                if (applied != null) {
                    byte[] broadcastMsg = YjsSyncProtocol.encodeSyncUpdate(updateData);
                    broadcastToOthers(docId, session, broadcastMsg);
                    pubSubBroadcaster.publishUpdate(docId, broadcastMsg);
                }
            }
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String sessionId = session.getId();
        UUID docId = sessionDocs.remove(sessionId);
        WebSocketSession decorated = decoratedSessions.remove(sessionId);
        if (docId != null) {
            Set<WebSocketSession> sessions = rooms.get(docId);
            if (sessions != null) {
                // Remove the decorated wrapper (which is what we stored in rooms)
                if (decorated != null) sessions.remove(decorated);
                else sessions.remove(session);
                if (sessions.isEmpty()) {
                    rooms.remove(docId);
                }
            }
        }
        log.info("WebSocket disconnected: session={}, doc={}", sessionId, docId);
    }

    /** Broadcast a Yjs update to all WebSocket sessions of a document (from Agent API). */
    public void broadcastUpdate(UUID docId, byte[] update) {
        byte[] msg = YjsSyncProtocol.encodeSyncUpdate(update);
        Set<WebSocketSession> sessions = rooms.get(docId);
        if (sessions != null) {
            for (WebSocketSession s : sessions) {
                sendToSession(s, msg);
            }
        }
    }

    /** Broadcast raw bytes to all local sessions of a document (from Pub/Sub listener). */
    public void broadcastRaw(UUID docId, byte[] data) {
        Set<WebSocketSession> sessions = rooms.get(docId);
        if (sessions != null) {
            for (WebSocketSession s : sessions) {
                sendToSession(s, data);
            }
        }
    }

    private void broadcastToOthers(UUID docId, WebSocketSession sender, byte[] data) {
        Set<WebSocketSession> sessions = rooms.get(docId);
        if (sessions == null) return;
        for (WebSocketSession s : sessions) {
            if (!s.getId().equals(sender.getId()) && s.isOpen()) {
                sendToSession(s, data);
            }
        }
    }

    private void sendToSession(WebSocketSession session, byte[] data) {
        try {
            if (session.isOpen()) {
                session.sendMessage(new BinaryMessage(data));
            }
        } catch (Exception e) {
            log.warn("Failed to send WebSocket message to session {}: {}", session.getId(), e.getMessage());
        }
    }
}
