# Stateless Server Architecture Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Refactor the Spring Boot backend from a stateful single-instance to a stateless multi-instance architecture using Redis for document state caching and Pub/Sub for cross-instance broadcast.

**Architecture:** Each Spring Boot instance becomes stateless — document CRDT state is cached in Redis, loaded/saved per operation via FFM. Nginx provides consistent-hash routing for WebSocket soft affinity. Redis Pub/Sub handles cross-instance update/awareness broadcast. PostgreSQL remains the durable persistence layer (unchanged).

**Tech Stack:** Spring Boot 3.5 + spring-boot-starter-data-redis, Guava Striped locks, Redis 7, Nginx, existing yrs-bridge FFM

---

### Task 1: Infrastructure — Docker Compose + Redis

**Files:**
- Modify: `docker-compose.yml`

**Step 1: Add Redis service to Docker Compose**

Edit `docker-compose.yml` to:

```yaml
services:
  postgres:
    image: postgres:17
    environment:
      POSTGRES_DB: collabdoc
      POSTGRES_USER: collabdoc
      POSTGRES_PASSWORD: collabdoc
    ports:
      - "5432:5432"
    volumes:
      - pgdata:/var/lib/postgresql/data

  redis:
    image: redis:7-alpine
    ports:
      - "6379:6379"
    volumes:
      - redisdata:/data

volumes:
  pgdata:
  redisdata:
```

**Step 2: Verify Redis starts**

Run: `docker compose up -d redis && docker compose exec redis redis-cli ping`
Expected: `PONG`

**Step 3: Commit**

```bash
git add docker-compose.yml
git commit -m "infra: add Redis service to Docker Compose"
```

---

### Task 2: Infrastructure — Nginx configuration

**Files:**
- Create: `nginx.conf`
- Modify: `docker-compose.yml`

**Step 1: Create Nginx config file**

Create `nginx.conf` in project root:

```nginx
upstream collab-backend {
    hash $docId consistent;
    server backend:8080;
    # Add more backend instances here for horizontal scaling
}

map $uri $docId {
    ~^/ws/(?<id>[^/]+)$ $id;
    default             $uri;
}

server {
    listen 80;

    location /ws/ {
        proxy_pass http://collab-backend;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_read_timeout 3600s;
    }

    location /api/ {
        proxy_pass http://collab-backend;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
    }

    location / {
        proxy_pass http://frontend:3000;
    }
}
```

**Step 2: Add Nginx service to Docker Compose**

Add to `docker-compose.yml` services:

```yaml
  nginx:
    image: nginx:alpine
    ports:
      - "80:80"
    volumes:
      - ./nginx.conf:/etc/nginx/conf.d/default.conf
    depends_on:
      - redis
```

**Step 3: Verify Nginx config syntax**

Run: `docker compose up -d nginx && docker compose logs nginx`
Expected: No errors (backend not running yet is OK, just check config parsing)

**Step 4: Commit**

```bash
git add nginx.conf docker-compose.yml
git commit -m "infra: add Nginx with consistent-hash WebSocket routing"
```

---

### Task 3: Spring Boot — Add Redis dependencies and configuration

**Files:**
- Modify: `backend/build.gradle.kts`
- Modify: `backend/src/main/resources/application.yml`

**Step 1: Add dependencies to build.gradle.kts**

Add to the `dependencies` block in `backend/build.gradle.kts`:

```kotlin
implementation("org.springframework.boot:spring-boot-starter-data-redis")
implementation("com.google.guava:guava:33.4.0-jre")
```

**Step 2: Add Redis and snapshot config to application.yml**

Add to `backend/src/main/resources/application.yml`:

```yaml
  data:
    redis:
      host: ${REDIS_HOST:localhost}
      port: ${REDIS_PORT:6379}

collabdoc:
  instance-id: ${INSTANCE_ID:${random.uuid}}
  snapshot:
    interval-minutes: ${SNAPSHOT_INTERVAL:5}
```

The full `spring:` section becomes:

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/collabdoc
    username: collabdoc
    password: collabdoc
  jpa:
    hibernate:
      ddl-auto: validate
    open-in-view: false
  flyway:
    enabled: true
  data:
    redis:
      host: ${REDIS_HOST:localhost}
      port: ${REDIS_PORT:6379}
```

**Step 3: Verify compilation**

Run: `cd backend && ./gradlew compileJava`
Expected: BUILD SUCCESSFUL

**Step 4: Commit**

```bash
git add backend/build.gradle.kts backend/src/main/resources/application.yml
git commit -m "feat: add Redis and Guava dependencies, Redis config"
```

---

### Task 4: Redis document state service — RedisDocumentStateService

**Files:**
- Create: `backend/src/main/java/com/collabdoc/collab/RedisDocumentStateService.java`
- Create: `backend/src/test/java/com/collabdoc/collab/RedisDocumentStateServiceTest.java`

This service encapsulates all Redis interactions for document state (get/set/lock/TTL).

**Step 1: Write the failing test**

Create `backend/src/test/java/com/collabdoc/collab/RedisDocumentStateServiceTest.java`:

```java
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
```

**Step 2: Run test to verify it fails**

Run: `cd backend && ./gradlew test --tests "com.collabdoc.collab.RedisDocumentStateServiceTest" 2>&1 | tail -5`
Expected: FAIL — class `RedisDocumentStateService` does not exist

**Step 3: Write implementation**

Create `backend/src/main/java/com/collabdoc/collab/RedisDocumentStateService.java`:

```java
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
```

**Step 4: Run test to verify it passes**

Run: `cd backend && ./gradlew test --tests "com.collabdoc.collab.RedisDocumentStateServiceTest" 2>&1 | tail -5`
Expected: PASS

**Step 5: Commit**

```bash
git add backend/src/main/java/com/collabdoc/collab/RedisDocumentStateService.java \
        backend/src/test/java/com/collabdoc/collab/RedisDocumentStateServiceTest.java
git commit -m "feat: add RedisDocumentStateService for document state caching"
```

---

### Task 5: Redis config — RedisTemplate bean for byte[] values

**Files:**
- Create: `backend/src/main/java/com/collabdoc/config/RedisConfig.java`

**Step 1: Create Redis configuration**

Create `backend/src/main/java/com/collabdoc/config/RedisConfig.java`:

```java
package com.collabdoc.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

@Configuration
public class RedisConfig {

    @Bean
    public RedisTemplate<String, byte[]> redisTemplate(RedisConnectionFactory connectionFactory) {
        var template = new RedisTemplate<String, byte[]>();
        template.setConnectionFactory(connectionFactory);
        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(RedisSerializer.byteArray());
        return template;
    }
}
```

**Step 2: Verify compilation**

Run: `cd backend && ./gradlew compileJava`
Expected: BUILD SUCCESSFUL

**Step 3: Commit**

```bash
git add backend/src/main/java/com/collabdoc/config/RedisConfig.java
git commit -m "feat: add RedisTemplate bean configuration for byte[] values"
```

---

### Task 6: Refactor YrsDocumentManager — stateless with Redis

**Files:**
- Modify: `backend/src/main/java/com/collabdoc/collab/YrsDocumentManager.java`
- Create: `backend/src/test/java/com/collabdoc/collab/YrsDocumentManagerTest.java`

This is the core refactor: remove `ConcurrentHashMap<UUID, YrsDocument>`, replace with per-operation load from Redis → FFM compute → save to Redis.

**Step 1: Write the failing test**

Create `backend/src/test/java/com/collabdoc/collab/YrsDocumentManagerTest.java`:

```java
package com.collabdoc.collab;

import com.collabdoc.yrs.YrsBridge;
import com.collabdoc.yrs.YrsDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class YrsDocumentManagerTest {

    private YrsBridge bridge;
    private RedisDocumentStateService redisState;
    private DocumentUpdateRepository updateRepo;
    private DocumentSnapshotRepository snapshotRepo;
    private YrsDocumentManager manager;

    @BeforeEach
    void setUp() {
        bridge = mock(YrsBridge.class);
        redisState = mock(RedisDocumentStateService.class);
        updateRepo = mock(DocumentUpdateRepository.class);
        snapshotRepo = mock(DocumentSnapshotRepository.class);
        manager = new YrsDocumentManager(bridge, redisState, updateRepo, snapshotRepo);
    }

    @Test
    void applyClientUpdate_loadsFromRedis_appliesAndSavesBack() {
        UUID docId = UUID.randomUUID();
        byte[] existingState = new byte[]{1, 2, 3};
        byte[] updateBytes = new byte[]{4, 5};
        byte[] appliedResult = new byte[]{6};
        byte[] newState = new byte[]{7, 8, 9};

        when(redisState.getState(docId)).thenReturn(existingState);
        var doc = mock(YrsDocument.class);
        when(bridge.createDocument()).thenReturn(doc);
        when(doc.applyUpdate(updateBytes)).thenReturn(appliedResult);
        when(doc.encodeState()).thenReturn(newState);

        byte[] result = manager.applyClientUpdate(docId, updateBytes);

        assertArrayEquals(appliedResult, result);
        verify(doc).loadState(existingState);
        verify(redisState).setState(docId, newState);
        verify(updateRepo).save(any(DocumentUpdate.class));
        verify(doc).close();
    }

    @Test
    void applyClientUpdate_loadsFromDb_whenRedisMiss() {
        UUID docId = UUID.randomUUID();
        byte[] snapshotData = new byte[]{10, 11};
        byte[] updateBytes = new byte[]{4, 5};
        byte[] appliedResult = new byte[]{6};
        byte[] newState = new byte[]{12, 13};

        when(redisState.getState(docId)).thenReturn(null);
        when(snapshotRepo.findById(docId)).thenReturn(Optional.of(new DocumentSnapshot(docId, snapshotData)));
        when(updateRepo.findByDocIdOrderByIdAsc(docId)).thenReturn(Collections.emptyList());
        var doc = mock(YrsDocument.class);
        when(bridge.createDocument()).thenReturn(doc);
        when(doc.applyUpdate(updateBytes)).thenReturn(appliedResult);
        when(doc.encodeState()).thenReturn(newState);

        byte[] result = manager.applyClientUpdate(docId, updateBytes);

        assertArrayEquals(appliedResult, result);
        verify(doc).loadState(snapshotData);
        verify(redisState).setState(docId, newState);
        verify(doc).close();
    }

    @Test
    void getBlocksJson_loadsFromRedis_returnsJson() {
        UUID docId = UUID.randomUUID();
        byte[] state = new byte[]{1, 2};

        when(redisState.getState(docId)).thenReturn(state);
        var doc = mock(YrsDocument.class);
        when(bridge.createDocument()).thenReturn(doc);
        when(doc.getBlocksJson()).thenReturn("[{\"id\":\"abc\"}]");

        String result = manager.getBlocksJson(docId);

        assertEquals("[{\"id\":\"abc\"}]", result);
        verify(doc).loadState(state);
        verify(doc).close();
    }
}
```

**Step 2: Run test to verify it fails**

Run: `cd backend && ./gradlew test --tests "com.collabdoc.collab.YrsDocumentManagerTest" 2>&1 | tail -5`
Expected: FAIL — constructor signature mismatch

**Step 3: Rewrite YrsDocumentManager**

Replace the entire content of `backend/src/main/java/com/collabdoc/collab/YrsDocumentManager.java`:

```java
package com.collabdoc.collab;

import com.collabdoc.yrs.YrsBridge;
import com.collabdoc.yrs.YrsDocument;
import com.google.common.util.concurrent.Striped;
import org.springframework.stereotype.Service;

import java.util.UUID;
import java.util.concurrent.locks.Lock;

/**
 * Stateless document operations manager. Each operation loads state from Redis
 * (falling back to PostgreSQL), performs FFM computation, saves back to Redis,
 * and persists the update to PostgreSQL.
 */
@Service
public class YrsDocumentManager {

    private final YrsBridge bridge;
    private final RedisDocumentStateService redisState;
    private final DocumentUpdateRepository updateRepository;
    private final DocumentSnapshotRepository snapshotRepository;
    private final Striped<Lock> locks = Striped.lock(64);

    public YrsDocumentManager(YrsBridge bridge,
                              RedisDocumentStateService redisState,
                              DocumentUpdateRepository updateRepository,
                              DocumentSnapshotRepository snapshotRepository) {
        this.bridge = bridge;
        this.redisState = redisState;
        this.updateRepository = updateRepository;
        this.snapshotRepository = snapshotRepository;
    }

    /** Load document state from Redis, falling back to PostgreSQL. */
    private byte[] loadState(UUID docId) {
        byte[] state = redisState.getState(docId);
        if (state != null) return state;

        // Redis miss — rebuild from PostgreSQL
        var doc = bridge.createDocument();
        try {
            snapshotRepository.findById(docId).ifPresent(snapshot ->
                doc.loadState(snapshot.getStateData())
            );
            var updates = updateRepository.findByDocIdOrderByIdAsc(docId);
            for (var update : updates) {
                doc.applyUpdate(update.getUpdateData());
            }
            byte[] rebuilt = doc.encodeState();
            redisState.setState(docId, rebuilt);
            return rebuilt;
        } finally {
            doc.close();
        }
    }

    /** Execute an operation on a document with per-docId locking. */
    private <T> T withDocument(UUID docId, DocumentOperation<T> operation) {
        Lock lock = locks.get(docId);
        lock.lock();
        try {
            byte[] state = loadState(docId);
            var doc = bridge.createDocument();
            try {
                if (state != null && state.length > 0) {
                    doc.loadState(state);
                }
                return operation.execute(doc);
            } finally {
                doc.close();
            }
        } finally {
            lock.unlock();
        }
    }

    /** Execute a mutating operation: load, mutate, save state + persist update. */
    private byte[] withMutation(UUID docId, MutationOperation mutation) {
        Lock lock = locks.get(docId);
        lock.lock();
        try {
            byte[] state = loadState(docId);
            var doc = bridge.createDocument();
            try {
                if (state != null && state.length > 0) {
                    doc.loadState(state);
                }
                byte[] update = mutation.execute(doc);
                if (update != null) {
                    byte[] newState = doc.encodeState();
                    redisState.setState(docId, newState);
                    updateRepository.save(new DocumentUpdate(docId, update));
                }
                return update;
            } finally {
                doc.close();
            }
        } finally {
            lock.unlock();
        }
    }

    @FunctionalInterface
    private interface DocumentOperation<T> {
        T execute(YrsDocument doc);
    }

    @FunctionalInterface
    private interface MutationOperation {
        byte[] execute(YrsDocument doc);
    }

    // --- Public API (same signatures as before, minus getOrLoadDocument/unloadDocument) ---

    public byte[] applyClientUpdate(UUID docId, byte[] update) {
        return withMutation(docId, doc -> doc.applyUpdate(update));
    }

    public byte[] insertBlock(UUID docId, int index, String blockType, String content, String propsJson) {
        return withMutation(docId, doc -> doc.insertBlock(index, blockType, content, propsJson));
    }

    public byte[] deleteBlock(UUID docId, int index) {
        return withMutation(docId, doc -> doc.deleteBlock(index));
    }

    public String getBlockById(UUID docId, String blockId) {
        return withDocument(docId, doc -> doc.getBlockById(blockId));
    }

    public byte[] updateBlock(UUID docId, String blockId, String newType, String newContent, String newPropsJson) {
        return withMutation(docId, doc -> doc.updateBlock(blockId, newType, newContent, newPropsJson));
    }

    public byte[] deleteBlockById(UUID docId, String blockId) {
        return withMutation(docId, doc -> doc.deleteBlockById(blockId));
    }

    public byte[] insertBlockV2(UUID docId, String blockType, String content, String propsJson, String position, String afterId) {
        return withMutation(docId, doc -> doc.insertBlockV2(blockType, content, propsJson, position, afterId));
    }

    public byte[] getStateVector(UUID docId) {
        return withDocument(docId, YrsDocument::getStateVector);
    }

    public byte[] encodeDiff(UUID docId, byte[] remoteStateVector) {
        return withDocument(docId, doc -> doc.encodeDiff(remoteStateVector));
    }

    public String getBlocksJson(UUID docId) {
        return withDocument(docId, YrsDocument::getBlocksJson);
    }

    /** Ensure document state is loaded into Redis (called on WebSocket connect). */
    public void ensureLoaded(UUID docId) {
        loadState(docId);
    }

    /** Create a snapshot from Redis state to PostgreSQL. */
    public void createSnapshot(UUID docId) {
        byte[] state = redisState.getState(docId);
        if (state != null) {
            snapshotRepository.save(new DocumentSnapshot(docId, state));
            updateRepository.deleteByDocId(docId);
        }
    }
}
```

**Step 4: Run test to verify it passes**

Run: `cd backend && ./gradlew test --tests "com.collabdoc.collab.YrsDocumentManagerTest" 2>&1 | tail -5`
Expected: PASS

**Step 5: Commit**

```bash
git add backend/src/main/java/com/collabdoc/collab/YrsDocumentManager.java \
        backend/src/test/java/com/collabdoc/collab/YrsDocumentManagerTest.java
git commit -m "feat: refactor YrsDocumentManager to stateless Redis-backed operations"
```

---

### Task 7: Redis Pub/Sub — cross-instance broadcast service

**Files:**
- Create: `backend/src/main/java/com/collabdoc/collab/RedisPubSubBroadcaster.java`
- Create: `backend/src/test/java/com/collabdoc/collab/RedisPubSubBroadcasterTest.java`

**Step 1: Write the failing test**

Create `backend/src/test/java/com/collabdoc/collab/RedisPubSubBroadcasterTest.java`:

```java
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
            // Message format: [instanceId length (4 bytes)][instanceId bytes][payload]
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
```

**Step 2: Run test to verify it fails**

Run: `cd backend && ./gradlew test --tests "com.collabdoc.collab.RedisPubSubBroadcasterTest" 2>&1 | tail -5`
Expected: FAIL — class does not exist

**Step 3: Write implementation**

Create `backend/src/main/java/com/collabdoc/collab/RedisPubSubBroadcaster.java`:

```java
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

    /** Check if a received Pub/Sub message was sent by this instance. */
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

    /** Extract the payload (without instance ID prefix) from a Pub/Sub message. */
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
```

**Step 4: Run test to verify it passes**

Run: `cd backend && ./gradlew test --tests "com.collabdoc.collab.RedisPubSubBroadcasterTest" 2>&1 | tail -5`
Expected: PASS

**Step 5: Commit**

```bash
git add backend/src/main/java/com/collabdoc/collab/RedisPubSubBroadcaster.java \
        backend/src/test/java/com/collabdoc/collab/RedisPubSubBroadcasterTest.java
git commit -m "feat: add RedisPubSubBroadcaster for cross-instance update/awareness broadcast"
```

---

### Task 8: Refactor YjsWebSocketHandler — integrate Redis Pub/Sub

**Files:**
- Modify: `backend/src/main/java/com/collabdoc/collab/YjsWebSocketHandler.java`
- Modify: `backend/src/main/java/com/collabdoc/config/WebSocketConfig.java`

**Step 1: Update YjsWebSocketHandler constructor and fields**

Add `RedisPubSubBroadcaster` dependency. Replace `getOrLoadDocument` with `ensureLoaded`. Add Pub/Sub publishing after local broadcast. Update `afterConnectionClosed` to remove snapshot/unload logic (now handled by periodic task + TTL).

Full replacement of `backend/src/main/java/com/collabdoc/collab/YjsWebSocketHandler.java`:

```java
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
 * Cross-instance broadcast is handled via Redis Pub/Sub.
 */
public class YjsWebSocketHandler extends BinaryWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(YjsWebSocketHandler.class);

    private final YrsDocumentManager docManager;
    private final PermissionService permissionService;
    private final RedisPubSubBroadcaster pubSubBroadcaster;

    private static final int SEND_TIME_LIMIT = 5000;
    private static final int SEND_BUFFER_LIMIT = 512 * 1024;

    private final ConcurrentHashMap<UUID, Set<WebSocketSession>> rooms = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, UUID> sessionDocs = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, WebSocketSession> decoratedSessions = new ConcurrentHashMap<>();

    public YjsWebSocketHandler(YrsDocumentManager docManager, PermissionService permissionService,
                                RedisPubSubBroadcaster pubSubBroadcaster) {
        this.docManager = docManager;
        this.permissionService = permissionService;
        this.pubSubBroadcaster = pubSubBroadcaster;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
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

        // Ensure document state is loaded into Redis
        docManager.ensureLoaded(docId);

        var decorated = new ConcurrentWebSocketSessionDecorator(session, SEND_TIME_LIMIT, SEND_BUFFER_LIMIT);

        rooms.computeIfAbsent(docId, k -> ConcurrentHashMap.newKeySet()).add(decorated);
        sessionDocs.put(session.getId(), docId);
        decoratedSessions.put(session.getId(), decorated);

        log.info("WebSocket connected: session={}, doc={}, permission={}", session.getId(), docId, permission);

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

        WebSocketSession decorated = decoratedSessions.getOrDefault(session.getId(), session);

        ByteBuffer buf = ByteBuffer.wrap(message.getPayload().array());
        if (!buf.hasRemaining()) return;

        int msgType = buf.get() & 0xFF;

        if (msgType == YjsSyncProtocol.MSG_SYNC) {
            handleSyncMessage(decorated, docId, buf);
        } else if (msgType == YjsSyncProtocol.MSG_AWARENESS) {
            byte[] data = message.getPayload().array();
            broadcastToOthers(docId, decorated, data);
            // Publish awareness to other instances
            pubSubBroadcaster.publishAwareness(docId, data);
        }
    }

    private void handleSyncMessage(WebSocketSession session, UUID docId, ByteBuffer buf) {
        if (!buf.hasRemaining()) return;
        int subType = buf.get() & 0xFF;

        switch (subType) {
            case YjsSyncProtocol.MSG_SYNC_STEP1 -> {
                byte[] clientSv = YjsSyncProtocol.readPayload(buf);
                byte[] diff = docManager.encodeDiff(docId, clientSv);
                byte[] response = YjsSyncProtocol.encodeSyncStep2(diff);
                sendToSession(session, response);
            }
            case YjsSyncProtocol.MSG_SYNC_STEP2 -> {
                byte[] update = YjsSyncProtocol.readPayload(buf);
                docManager.applyClientUpdate(docId, update);
            }
            case YjsSyncProtocol.MSG_SYNC_UPDATE -> {
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
                    // Publish update to other instances
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
                if (decorated != null) sessions.remove(decorated);
                else sessions.remove(session);
                if (sessions.isEmpty()) {
                    rooms.remove(docId);
                    // No snapshot/unload — Redis TTL and periodic snapshot handle cleanup
                }
            }
        }
        log.info("WebSocket disconnected: session={}, doc={}", sessionId, docId);
    }

    /** Broadcast a Yjs update to all local WebSocket sessions of a document (from Agent API or Pub/Sub). */
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
```

**Step 2: Update WebSocketConfig to pass RedisPubSubBroadcaster**

Modify `backend/src/main/java/com/collabdoc/config/WebSocketConfig.java`:

Add the `RedisPubSubBroadcaster` field and update the constructor and bean method:

```java
package com.collabdoc.config;

import com.collabdoc.auth.JwtUtil;
import com.collabdoc.permission.PermissionService;
import com.collabdoc.collab.YrsDocumentManager;
import com.collabdoc.collab.YjsWebSocketHandler;
import com.collabdoc.collab.RedisPubSubBroadcaster;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;
import java.util.UUID;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final YrsDocumentManager docManager;
    private final JwtUtil jwtUtil;
    private final PermissionService permissionService;
    private final RedisPubSubBroadcaster pubSubBroadcaster;

    public WebSocketConfig(YrsDocumentManager docManager, JwtUtil jwtUtil,
                           PermissionService permissionService, RedisPubSubBroadcaster pubSubBroadcaster) {
        this.docManager = docManager;
        this.jwtUtil = jwtUtil;
        this.permissionService = permissionService;
        this.pubSubBroadcaster = pubSubBroadcaster;
    }

    @Bean
    public YjsWebSocketHandler yjsWebSocketHandler() {
        return new YjsWebSocketHandler(docManager, permissionService, pubSubBroadcaster);
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(yjsWebSocketHandler(), "/ws/*")
                .addInterceptors(jwtHandshakeInterceptor())
                .setAllowedOrigins("http://localhost:3000");
    }

    private HandshakeInterceptor jwtHandshakeInterceptor() {
        return new HandshakeInterceptor() {
            @Override
            public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                           WebSocketHandler wsHandler, Map<String, Object> attributes) {
                if (request instanceof ServletServerHttpRequest servletRequest) {
                    HttpServletRequest httpRequest = servletRequest.getServletRequest();
                    Cookie[] cookies = httpRequest.getCookies();
                    if (cookies != null) {
                        for (Cookie cookie : cookies) {
                            if ("token".equals(cookie.getName()) && jwtUtil.isValid(cookie.getValue())) {
                                UUID userId = jwtUtil.getUserId(cookie.getValue());
                                attributes.put("userId", userId);
                                return true;
                            }
                        }
                    }
                }
                return false;
            }

            @Override
            public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                       WebSocketHandler wsHandler, Exception exception) {}
        };
    }
}
```

**Step 3: Verify compilation**

Run: `cd backend && ./gradlew compileJava`
Expected: BUILD SUCCESSFUL

**Step 4: Commit**

```bash
git add backend/src/main/java/com/collabdoc/collab/YjsWebSocketHandler.java \
        backend/src/main/java/com/collabdoc/config/WebSocketConfig.java
git commit -m "feat: integrate Redis Pub/Sub into WebSocket handler for cross-instance broadcast"
```

---

### Task 9: Redis Pub/Sub listener — receive cross-instance messages

**Files:**
- Create: `backend/src/main/java/com/collabdoc/collab/RedisPubSubListener.java`
- Modify: `backend/src/main/java/com/collabdoc/config/RedisConfig.java`

**Step 1: Create Pub/Sub message listener**

Create `backend/src/main/java/com/collabdoc/collab/RedisPubSubListener.java`:

```java
package com.collabdoc.collab;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Listens for Redis Pub/Sub messages from other instances and
 * forwards them to local WebSocket sessions.
 */
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

        // Skip messages from this instance
        if (broadcaster.isOwnMessage(body)) {
            return;
        }

        // Extract docId from channel name (e.g., "doc:updates:<uuid>" or "doc:awareness:<uuid>")
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
```

**Step 2: Register Pub/Sub listener in RedisConfig**

Update `backend/src/main/java/com/collabdoc/config/RedisConfig.java`:

```java
package com.collabdoc.config;

import com.collabdoc.collab.RedisPubSubListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

@Configuration
public class RedisConfig {

    @Bean
    public RedisTemplate<String, byte[]> redisTemplate(RedisConnectionFactory connectionFactory) {
        var template = new RedisTemplate<String, byte[]>();
        template.setConnectionFactory(connectionFactory);
        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(RedisSerializer.byteArray());
        return template;
    }

    @Bean
    public RedisMessageListenerContainer redisMessageListenerContainer(
            RedisConnectionFactory connectionFactory,
            RedisPubSubListener listener) {
        var container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(listener, new PatternTopic("doc:updates:*"));
        container.addMessageListener(listener, new PatternTopic("doc:awareness:*"));
        return container;
    }
}
```

**Step 3: Verify compilation**

Run: `cd backend && ./gradlew compileJava`
Expected: BUILD SUCCESSFUL

**Step 4: Commit**

```bash
git add backend/src/main/java/com/collabdoc/collab/RedisPubSubListener.java \
        backend/src/main/java/com/collabdoc/config/RedisConfig.java
git commit -m "feat: add Redis Pub/Sub listener for cross-instance message forwarding"
```

---

### Task 10: Periodic snapshot scheduler

**Files:**
- Create: `backend/src/main/java/com/collabdoc/collab/SnapshotScheduler.java`
- Create: `backend/src/test/java/com/collabdoc/collab/SnapshotSchedulerTest.java`

**Step 1: Write the failing test**

Create `backend/src/test/java/com/collabdoc/collab/SnapshotSchedulerTest.java`:

```java
package com.collabdoc.collab;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.mockito.Mockito.*;

class SnapshotSchedulerTest {

    private RedisDocumentStateService redisState;
    private YrsDocumentManager docManager;
    private SnapshotScheduler scheduler;

    @BeforeEach
    void setUp() {
        redisState = mock(RedisDocumentStateService.class);
        docManager = mock(YrsDocumentManager.class);
        scheduler = new SnapshotScheduler(redisState, docManager);
    }

    @Test
    void snapshotActiveDocuments_snapshotsEachActiveDoc() {
        UUID doc1 = UUID.randomUUID();
        UUID doc2 = UUID.randomUUID();
        when(redisState.findActiveDocumentKeys()).thenReturn(Set.of("doc:state:" + doc1, "doc:state:" + doc2));
        when(redisState.extractDocId("doc:state:" + doc1)).thenReturn(doc1);
        when(redisState.extractDocId("doc:state:" + doc2)).thenReturn(doc2);
        when(redisState.tryAcquireSnapshotLock(doc1)).thenReturn(true);
        when(redisState.tryAcquireSnapshotLock(doc2)).thenReturn(true);

        scheduler.snapshotActiveDocuments();

        verify(docManager).createSnapshot(doc1);
        verify(docManager).createSnapshot(doc2);
        verify(redisState).releaseSnapshotLock(doc1);
        verify(redisState).releaseSnapshotLock(doc2);
    }

    @Test
    void snapshotActiveDocuments_skipsLockedDocs() {
        UUID doc1 = UUID.randomUUID();
        when(redisState.findActiveDocumentKeys()).thenReturn(Set.of("doc:state:" + doc1));
        when(redisState.extractDocId("doc:state:" + doc1)).thenReturn(doc1);
        when(redisState.tryAcquireSnapshotLock(doc1)).thenReturn(false);

        scheduler.snapshotActiveDocuments();

        verify(docManager, never()).createSnapshot(doc1);
    }

    @Test
    void snapshotActiveDocuments_releasesLockOnException() {
        UUID doc1 = UUID.randomUUID();
        when(redisState.findActiveDocumentKeys()).thenReturn(Set.of("doc:state:" + doc1));
        when(redisState.extractDocId("doc:state:" + doc1)).thenReturn(doc1);
        when(redisState.tryAcquireSnapshotLock(doc1)).thenReturn(true);
        doThrow(new RuntimeException("DB error")).when(docManager).createSnapshot(doc1);

        try {
            scheduler.snapshotActiveDocuments();
        } catch (Exception ignored) {}

        verify(redisState).releaseSnapshotLock(doc1);
    }
}
```

**Step 2: Run test to verify it fails**

Run: `cd backend && ./gradlew test --tests "com.collabdoc.collab.SnapshotSchedulerTest" 2>&1 | tail -5`
Expected: FAIL — class does not exist

**Step 3: Write implementation**

Create `backend/src/main/java/com/collabdoc/collab/SnapshotScheduler.java`:

```java
package com.collabdoc.collab;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Component
public class SnapshotScheduler {

    private static final Logger log = LoggerFactory.getLogger(SnapshotScheduler.class);

    private final RedisDocumentStateService redisState;
    private final YrsDocumentManager docManager;

    public SnapshotScheduler(RedisDocumentStateService redisState, YrsDocumentManager docManager) {
        this.redisState = redisState;
        this.docManager = docManager;
    }

    @Scheduled(fixedRateString = "${collabdoc.snapshot.interval-minutes:5}", timeUnit = TimeUnit.MINUTES)
    public void snapshotActiveDocuments() {
        Set<String> keys = redisState.findActiveDocumentKeys();
        if (keys == null || keys.isEmpty()) return;

        log.info("Snapshot scheduler: found {} active documents", keys.size());

        for (String key : keys) {
            UUID docId = redisState.extractDocId(key);
            if (!redisState.tryAcquireSnapshotLock(docId)) {
                log.debug("Snapshot lock held for doc={}, skipping", docId);
                continue;
            }
            try {
                docManager.createSnapshot(docId);
                log.debug("Snapshot created for doc={}", docId);
            } catch (Exception e) {
                log.error("Failed to snapshot doc={}: {}", docId, e.getMessage());
            } finally {
                redisState.releaseSnapshotLock(docId);
            }
        }
    }
}
```

**Step 4: Enable scheduling in Spring Boot**

Add `@EnableScheduling` to the application class. Check which file has `@SpringBootApplication`:

The annotation should be added to the main application class (likely `CollabDocApplication.java` or similar). Add `@EnableScheduling` import and annotation:

```java
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class CollabDocApplication { ... }
```

**Step 5: Run test to verify it passes**

Run: `cd backend && ./gradlew test --tests "com.collabdoc.collab.SnapshotSchedulerTest" 2>&1 | tail -5`
Expected: PASS

**Step 6: Commit**

```bash
git add backend/src/main/java/com/collabdoc/collab/SnapshotScheduler.java \
        backend/src/test/java/com/collabdoc/collab/SnapshotSchedulerTest.java \
        backend/src/main/java/com/collabdoc/CollabDocApplication.java
git commit -m "feat: add periodic snapshot scheduler with distributed locking"
```

---

### Task 11: Update BlockController — add Pub/Sub broadcast for Agent API

**Files:**
- Modify: `backend/src/main/java/com/collabdoc/collab/BlockController.java`

The current `BlockController` calls `wsHandler.broadcastUpdate()` for local broadcast. We need to also publish to Redis Pub/Sub so other instances receive Agent API mutations.

**Step 1: Add RedisPubSubBroadcaster to BlockController**

Update `backend/src/main/java/com/collabdoc/collab/BlockController.java`:

Add the field and constructor parameter:

```java
private final RedisPubSubBroadcaster pubSubBroadcaster;

public BlockController(YrsDocumentManager docManager, YjsWebSocketHandler wsHandler,
                       PermissionService permissionService, ObjectMapper objectMapper,
                       RedisPubSubBroadcaster pubSubBroadcaster) {
    this.docManager = docManager;
    this.wsHandler = wsHandler;
    this.permissionService = permissionService;
    this.objectMapper = objectMapper;
    this.pubSubBroadcaster = pubSubBroadcaster;
}
```

**Step 2: Add Pub/Sub publish after each broadcastUpdate call**

After every `wsHandler.broadcastUpdate(docId, update)` call, add:

```java
pubSubBroadcaster.publishUpdate(docId, YjsSyncProtocol.encodeSyncUpdate(update));
```

There are 3 places in BlockController where `broadcastUpdate` is called:
1. `insertBlock` method (line 85) — primary block insert
2. `insertBlock` method (line 97) — child block insert in batch
3. `updateBlock` method (line 131)
4. `deleteBlock` method (line 157)

For each, add the `pubSubBroadcaster.publishUpdate(...)` line right after `wsHandler.broadcastUpdate(...)`.

**Step 3: Verify compilation**

Run: `cd backend && ./gradlew compileJava`
Expected: BUILD SUCCESSFUL

**Step 4: Commit**

```bash
git add backend/src/main/java/com/collabdoc/collab/BlockController.java
git commit -m "feat: add Redis Pub/Sub broadcast to BlockController for cross-instance Agent API updates"
```

---

### Task 12: Integration test — full stateless flow with embedded Redis

**Files:**
- Create: `backend/src/test/java/com/collabdoc/collab/StatelessFlowIntegrationTest.java`

This test verifies the full load-from-Redis → mutate → save-to-Redis cycle using the real `YrsDocumentManager` with mocked Redis and real FFM bridge.

**Step 1: Write integration test**

Create `backend/src/test/java/com/collabdoc/collab/StatelessFlowIntegrationTest.java`:

```java
package com.collabdoc.collab;

import com.collabdoc.yrs.YrsBridge;
import com.collabdoc.yrs.YrsDocument;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Integration test verifying the stateless document flow:
 * Redis miss → DB load → FFM compute → Redis save → subsequent Redis hit.
 */
class StatelessFlowIntegrationTest {

    private YrsBridge bridge;
    private RedisDocumentStateService redisState;
    private DocumentUpdateRepository updateRepo;
    private DocumentSnapshotRepository snapshotRepo;
    private YrsDocumentManager manager;

    @BeforeEach
    void setUp() {
        // Use real YrsBridge if native lib is available, otherwise mock
        try {
            String libPath = System.getenv("YRS_BRIDGE_LIB_PATH");
            if (libPath == null) libPath = "../yrs-bridge/target/release/libyrs_bridge.dylib";
            bridge = new YrsBridge(libPath);
        } catch (Exception e) {
            bridge = null;
        }

        redisState = mock(RedisDocumentStateService.class);
        updateRepo = mock(DocumentUpdateRepository.class);
        snapshotRepo = mock(DocumentSnapshotRepository.class);
    }

    @AfterEach
    void tearDown() {
        if (bridge != null) bridge.close();
    }

    @Test
    void fullFlow_insertBlock_thenReadBack() {
        if (bridge == null) return; // Skip if native lib not available

        manager = new YrsDocumentManager(bridge, redisState, updateRepo, snapshotRepo);
        UUID docId = UUID.randomUUID();

        // First call: Redis miss, DB empty → creates empty doc
        when(redisState.getState(docId)).thenReturn(null);
        when(snapshotRepo.findById(docId)).thenReturn(Optional.empty());
        when(updateRepo.findByDocIdOrderByIdAsc(docId)).thenReturn(Collections.emptyList());

        // Insert a block
        byte[] update = manager.insertBlockV2(docId, "paragraph", "Hello world", null, "end", null);
        assertNotNull(update);

        // Verify state was saved to Redis
        verify(redisState).setState(eq(docId), any(byte[].class));

        // Capture the saved state for next call
        var stateCaptor = org.mockito.ArgumentCaptor.forClass(byte[].class);
        verify(redisState).setState(eq(docId), stateCaptor.capture());
        byte[] savedState = stateCaptor.getValue();

        // Second call: Redis hit → returns state directly
        reset(redisState);
        when(redisState.getState(docId)).thenReturn(savedState);

        String blocksJson = manager.getBlocksJson(docId);
        assertNotNull(blocksJson);
        assertTrue(blocksJson.contains("Hello world"));

        // Verify no DB access on second call (Redis hit)
        verify(snapshotRepo, never()).findById(any());
    }
}
```

**Step 2: Run the integration test**

Run: `cd backend && ./gradlew test --tests "com.collabdoc.collab.StatelessFlowIntegrationTest" 2>&1 | tail -10`
Expected: PASS (or skip if native lib not built)

**Step 3: Commit**

```bash
git add backend/src/test/java/com/collabdoc/collab/StatelessFlowIntegrationTest.java
git commit -m "test: add stateless flow integration test for Redis-backed document operations"
```

---

### Task 13: Verify full application starts with Redis

**Step 1: Build the Rust native library (if not built)**

Run: `cd yrs-bridge && cargo build --release`
Expected: Produces `target/release/libyrs_bridge.dylib`

**Step 2: Start infrastructure**

Run: `docker compose up -d`
Expected: PostgreSQL and Redis running

**Step 3: Start the backend**

Run: `cd backend && ./gradlew bootRun`
Expected: Application starts on :8080 without errors, Redis connection established

**Step 4: Start the frontend**

Run: `cd frontend && npm run dev`
Expected: Vite dev server on :3000

**Step 5: Manual smoke test**

1. Open `http://localhost:3000` in browser
2. Create a document, type some text
3. Open another browser/incognito window, open the same document
4. Verify real-time sync works
5. Check Redis has the document state: `docker compose exec redis redis-cli keys "doc:*"`

**Step 6: Commit any remaining fixes**

```bash
git add -A
git commit -m "fix: resolve any startup issues from stateless refactor"
```

---

## Summary of Files Changed

| Action | File |
|--------|------|
| Modify | `docker-compose.yml` |
| Create | `nginx.conf` |
| Modify | `backend/build.gradle.kts` |
| Modify | `backend/src/main/resources/application.yml` |
| Create | `backend/src/main/java/com/collabdoc/config/RedisConfig.java` |
| Create | `backend/src/main/java/com/collabdoc/collab/RedisDocumentStateService.java` |
| Create | `backend/src/test/java/com/collabdoc/collab/RedisDocumentStateServiceTest.java` |
| Rewrite | `backend/src/main/java/com/collabdoc/collab/YrsDocumentManager.java` |
| Create | `backend/src/test/java/com/collabdoc/collab/YrsDocumentManagerTest.java` |
| Create | `backend/src/main/java/com/collabdoc/collab/RedisPubSubBroadcaster.java` |
| Create | `backend/src/test/java/com/collabdoc/collab/RedisPubSubBroadcasterTest.java` |
| Rewrite | `backend/src/main/java/com/collabdoc/collab/YjsWebSocketHandler.java` |
| Modify | `backend/src/main/java/com/collabdoc/config/WebSocketConfig.java` |
| Create | `backend/src/main/java/com/collabdoc/collab/RedisPubSubListener.java` |
| Create | `backend/src/main/java/com/collabdoc/collab/SnapshotScheduler.java` |
| Create | `backend/src/test/java/com/collabdoc/collab/SnapshotSchedulerTest.java` |
| Modify | `backend/src/main/java/com/collabdoc/collab/BlockController.java` |
| Create | `backend/src/test/java/com/collabdoc/collab/StatelessFlowIntegrationTest.java` |
| Modify | `backend/src/main/java/com/collabdoc/CollabDocApplication.java` (add `@EnableScheduling`) |

## Unchanged

- Frontend (zero changes)
- yrs-bridge Rust (zero changes)
- Database schema (zero changes)
- Auth/Permission code (zero changes)
