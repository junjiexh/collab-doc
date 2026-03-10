package com.collabdoc.collab;

import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Set;
import java.util.UUID;

@Service
public class RedisDocumentStateService {

    private static final Duration STATE_TTL = Duration.ofMinutes(30);
    private static final Duration LOAD_LOCK_TTL = Duration.ofSeconds(10);
    private static final Duration SNAPSHOT_LOCK_TTL = Duration.ofSeconds(30);
    private static final String STATE_PREFIX = "doc:state:";
    private static final String LOAD_LOCK_PREFIX = "doc:lock:";
    private static final String SNAPSHOT_LOCK_PREFIX = "doc:snapshot-lock:";

    private final RedisTemplate<String, byte[]> redisTemplate;

    public RedisDocumentStateService(RedisTemplate<String, byte[]> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public byte[] getState(UUID docId) {
        return redisTemplate.opsForValue().get(STATE_PREFIX + docId);
    }

    public void setState(UUID docId, byte[] state) {
        redisTemplate.opsForValue().set(STATE_PREFIX + docId, state, STATE_TTL);
    }

    public boolean tryAcquireLoadLock(UUID docId) {
        return Boolean.TRUE.equals(
            redisTemplate.opsForValue().setIfAbsent(LOAD_LOCK_PREFIX + docId, new byte[]{1}, LOAD_LOCK_TTL)
        );
    }

    public void releaseLoadLock(UUID docId) {
        redisTemplate.delete(LOAD_LOCK_PREFIX + docId);
    }

    public boolean tryAcquireSnapshotLock(UUID docId) {
        return Boolean.TRUE.equals(
            redisTemplate.opsForValue().setIfAbsent(SNAPSHOT_LOCK_PREFIX + docId, new byte[]{1}, SNAPSHOT_LOCK_TTL)
        );
    }

    public void releaseSnapshotLock(UUID docId) {
        redisTemplate.delete(SNAPSHOT_LOCK_PREFIX + docId);
    }

    public Set<String> findActiveDocumentKeys() {
        return redisTemplate.keys(STATE_PREFIX + "*");
    }

    public UUID extractDocId(String key) {
        return UUID.fromString(key.substring(STATE_PREFIX.length()));
    }
}
