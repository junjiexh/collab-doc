package com.collabdoc.websocket;

import com.collabdoc.service.YrsDocumentManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.BinaryWebSocketHandler;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WebSocket handler implementing the y-websocket sync protocol.
 * Each document is a "room" -- all sessions in the same room are synced.
 */
public class YjsWebSocketHandler extends BinaryWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(YjsWebSocketHandler.class);

    private final YrsDocumentManager docManager;

    // docId -> set of sessions
    private final ConcurrentHashMap<UUID, Set<WebSocketSession>> rooms = new ConcurrentHashMap<>();
    // sessionId -> docId
    private final ConcurrentHashMap<String, UUID> sessionDocs = new ConcurrentHashMap<>();

    public YjsWebSocketHandler(YrsDocumentManager docManager) {
        this.docManager = docManager;
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

        // Ensure document is loaded in memory
        docManager.getOrLoadDocument(docId);

        // Add session to room
        rooms.computeIfAbsent(docId, k -> ConcurrentHashMap.newKeySet()).add(session);
        sessionDocs.put(session.getId(), docId);

        log.info("WebSocket connected: session={}, doc={}", session.getId(), docId);

        // Send sync step 1 (server's state vector) to the client
        try {
            byte[] sv = docManager.getStateVector(docId);
            byte[] msg = YjsSyncProtocol.encodeSyncStep1(sv);
            session.sendMessage(new BinaryMessage(msg));
        } catch (Exception e) {
            log.error("Failed to send initial sync", e);
        }
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
        UUID docId = sessionDocs.get(session.getId());
        if (docId == null) return;

        ByteBuffer buf = ByteBuffer.wrap(message.getPayload().array());
        if (!buf.hasRemaining()) return;

        int msgType = buf.get() & 0xFF;

        if (msgType == YjsSyncProtocol.MSG_SYNC) {
            handleSyncMessage(session, docId, buf);
        } else if (msgType == YjsSyncProtocol.MSG_AWARENESS) {
            // Broadcast awareness messages to all other sessions in the room
            broadcastToOthers(docId, session, message.getPayload().array());
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
            case YjsSyncProtocol.MSG_SYNC_STEP2, YjsSyncProtocol.MSG_SYNC_UPDATE -> {
                // Client sends an update; apply and broadcast
                byte[] update = YjsSyncProtocol.readPayload(buf);
                byte[] applied = docManager.applyClientUpdate(docId, update);
                if (applied != null) {
                    // Broadcast the update to all OTHER clients
                    byte[] broadcastMsg = YjsSyncProtocol.encodeSyncUpdate(update);
                    broadcastToOthers(docId, session, broadcastMsg);
                }
            }
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        UUID docId = sessionDocs.remove(session.getId());
        if (docId != null) {
            Set<WebSocketSession> sessions = rooms.get(docId);
            if (sessions != null) {
                sessions.remove(session);
                if (sessions.isEmpty()) {
                    rooms.remove(docId);
                    // Optionally create snapshot and unload document
                    docManager.createSnapshot(docId);
                    docManager.unloadDocument(docId);
                }
            }
        }
        log.info("WebSocket disconnected: session={}, doc={}", session.getId(), docId);
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
        } catch (IOException e) {
            log.warn("Failed to send WebSocket message to session {}", session.getId(), e);
        }
    }
}
