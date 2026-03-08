package com.collabdoc.collab;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisTemplate;

import java.nio.ByteBuffer;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RedisPubSubBroadcasterTest {

    private RedisTemplate<String, byte[]> redisTemplate;
    private RedisPubSubBroadcaster broadcaster;
    private String instanceId;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        redisTemplate = mock(RedisTemplate.class);
        instanceId = "test-instance-1";
        broadcaster = new RedisPubSubBroadcaster(redisTemplate, instanceId);
    }

    @Test
    void publishUpdate_sendsToCorrectChannel() {
        UUID docId = UUID.randomUUID();
        byte[] update = new byte[]{1, 2, 3};

        broadcaster.publishUpdate(docId, update);

        verify(redisTemplate).convertAndSend(eq("doc:updates:" + docId), any(byte[].class));
    }

    @Test
    void publishAwareness_sendsToCorrectChannel() {
        UUID docId = UUID.randomUUID();
        byte[] awareness = new byte[]{4, 5, 6};

        broadcaster.publishAwareness(docId, awareness);

        verify(redisTemplate).convertAndSend(eq("doc:awareness:" + docId), any(byte[].class));
    }

    @Test
    void publishedMessage_containsInstanceIdPrefix() {
        UUID docId = UUID.randomUUID();
        byte[] update = new byte[]{1, 2};

        broadcaster.publishUpdate(docId, update);

        verify(redisTemplate).convertAndSend(eq("doc:updates:" + docId), argThat((byte[] msg) -> {
            ByteBuffer buf = ByteBuffer.wrap(msg);
            int idLen = buf.getInt();
            byte[] idBytes = new byte[idLen];
            buf.get(idBytes);
            String extractedId = new String(idBytes);
            return extractedId.equals(instanceId);
        }));
    }

    @Test
    void isOwnMessage_returnsTrueForOwnInstanceId() {
        byte[] instanceIdBytes = instanceId.getBytes();
        ByteBuffer buf = ByteBuffer.allocate(4 + instanceIdBytes.length + 2);
        buf.putInt(instanceIdBytes.length);
        buf.put(instanceIdBytes);
        buf.put(new byte[]{1, 2});
        byte[] message = buf.array();

        assertTrue(broadcaster.isOwnMessage(message));
    }

    @Test
    void isOwnMessage_returnsFalseForOtherInstanceId() {
        byte[] otherIdBytes = "other-instance".getBytes();
        ByteBuffer buf = ByteBuffer.allocate(4 + otherIdBytes.length + 2);
        buf.putInt(otherIdBytes.length);
        buf.put(otherIdBytes);
        buf.put(new byte[]{1, 2});
        byte[] message = buf.array();

        assertFalse(broadcaster.isOwnMessage(message));
    }

    @Test
    void extractPayload_returnsDataWithoutPrefix() {
        byte[] instanceIdBytes = instanceId.getBytes();
        byte[] payload = new byte[]{7, 8, 9};
        ByteBuffer buf = ByteBuffer.allocate(4 + instanceIdBytes.length + payload.length);
        buf.putInt(instanceIdBytes.length);
        buf.put(instanceIdBytes);
        buf.put(payload);
        byte[] message = buf.array();

        assertArrayEquals(payload, broadcaster.extractPayload(message));
    }
}
