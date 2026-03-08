package com.collabdoc.collab;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.ByteBuffer;
import java.util.UUID;

/**
 * Publishes document updates and awareness messages to Redis Pub/Sub
 * for cross-instance broadcast. Each message is prefixed with the sender
 * instance ID so receivers can filter out their own messages.
 */
@Service
public class RedisPubSubBroadcaster {

    private static final String UPDATES_PREFIX = "doc:updates:";
    private static final String AWARENESS_PREFIX = "doc:awareness:";

    private final RedisTemplate<String, byte[]> redisTemplate;
    private final String instanceId;
    private final byte[] instanceIdBytes;

    public RedisPubSubBroadcaster(RedisTemplate<String, byte[]> redisTemplate,
                                   @Value("${collabdoc.instance-id}") String instanceId) {
        this.redisTemplate = redisTemplate;
        this.instanceId = instanceId;
        this.instanceIdBytes = instanceId.getBytes();
    }

    public void publishUpdate(UUID docId, byte[] update) {
        redisTemplate.convertAndSend(UPDATES_PREFIX + docId, wrapMessage(update));
    }

    public void publishAwareness(UUID docId, byte[] awareness) {
        redisTemplate.convertAndSend(AWARENESS_PREFIX + docId, wrapMessage(awareness));
    }

    public boolean isOwnMessage(byte[] message) {
        if (message.length < 4) return false;
        ByteBuffer buf = ByteBuffer.wrap(message);
        int idLen = buf.getInt();
        if (idLen != instanceIdBytes.length || message.length < 4 + idLen) return false;
        for (int i = 0; i < idLen; i++) {
            if (message[4 + i] != instanceIdBytes[i]) return false;
        }
        return true;
    }

    public byte[] extractPayload(byte[] message) {
        ByteBuffer buf = ByteBuffer.wrap(message);
        int idLen = buf.getInt();
        buf.position(4 + idLen);
        byte[] payload = new byte[buf.remaining()];
        buf.get(payload);
        return payload;
    }

    private byte[] wrapMessage(byte[] payload) {
        ByteBuffer buf = ByteBuffer.allocate(4 + instanceIdBytes.length + payload.length);
        buf.putInt(instanceIdBytes.length);
        buf.put(instanceIdBytes);
        buf.put(payload);
        return buf.array();
    }
}
