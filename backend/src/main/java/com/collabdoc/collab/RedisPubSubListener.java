package com.collabdoc.collab;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class RedisPubSubListener implements MessageListener {

    private static final Logger log = LoggerFactory.getLogger(RedisPubSubListener.class);

    private final YjsWebSocketHandler wsHandler;
    private final RedisPubSubBroadcaster broadcaster;

    public RedisPubSubListener(YjsWebSocketHandler wsHandler, RedisPubSubBroadcaster broadcaster) {
        this.wsHandler = wsHandler;
        this.broadcaster = broadcaster;
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        byte[] body = message.getBody();

        if (broadcaster.isOwnMessage(body)) {
            return;
        }

        String channel = new String(message.getChannel());
        String docIdStr;
        if (channel.startsWith("doc:updates:")) {
            docIdStr = channel.substring("doc:updates:".length());
        } else if (channel.startsWith("doc:awareness:")) {
            docIdStr = channel.substring("doc:awareness:".length());
        } else {
            return;
        }

        try {
            UUID docId = UUID.fromString(docIdStr);
            byte[] payload = broadcaster.extractPayload(body);
            wsHandler.broadcastRaw(docId, payload);
        } catch (Exception e) {
            log.warn("Failed to process Pub/Sub message from channel {}: {}", channel, e.getMessage());
        }
    }
}
