package com.collabdoc.collab;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RedisDocumentStateServiceTest {

    private RedisTemplate<String, byte[]> redisTemplate;
    private ValueOperations<String, byte[]> valueOps;
    private RedisDocumentStateService service;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        redisTemplate = mock(RedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        service = new RedisDocumentStateService(redisTemplate);
    }

    @Test
    void getState_returnsBytes_whenKeyExists() {
        UUID docId = UUID.randomUUID();
        byte[] expected = new byte[]{1, 2, 3};
        when(valueOps.get("doc:state:" + docId)).thenReturn(expected);

        byte[] result = service.getState(docId);
        assertArrayEquals(expected, result);
    }

    @Test
    void getState_returnsNull_whenKeyMissing() {
        UUID docId = UUID.randomUUID();
        when(valueOps.get("doc:state:" + docId)).thenReturn(null);

        assertNull(service.getState(docId));
    }

    @Test
    void setState_setsValueWithTtl() {
        UUID docId = UUID.randomUUID();
        byte[] state = new byte[]{4, 5, 6};

        service.setState(docId, state);

        verify(valueOps).set("doc:state:" + docId, state, Duration.ofMinutes(30));
    }

    @Test
    void tryAcquireLoadLock_returnsTrue_whenLockFree() {
        UUID docId = UUID.randomUUID();
        when(valueOps.setIfAbsent(eq("doc:lock:" + docId), any(byte[].class), eq(Duration.ofSeconds(10))))
                .thenReturn(true);

        assertTrue(service.tryAcquireLoadLock(docId));
    }

    @Test
    void tryAcquireLoadLock_returnsFalse_whenLockHeld() {
        UUID docId = UUID.randomUUID();
        when(valueOps.setIfAbsent(eq("doc:lock:" + docId), any(byte[].class), eq(Duration.ofSeconds(10))))
                .thenReturn(false);

        assertFalse(service.tryAcquireLoadLock(docId));
    }

    @Test
    void releaseLoadLock_deletesKey() {
        UUID docId = UUID.randomUUID();

        service.releaseLoadLock(docId);

        verify(redisTemplate).delete("doc:lock:" + docId);
    }
}
