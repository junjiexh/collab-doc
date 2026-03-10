# Stateless Server Architecture Design

## Goal

将 Spring Boot 后端从有状态单实例改造为无状态多实例架构，支持水平扩展、高可用和灵活部署（Kubernetes 等）。

## Current State

后端持有以下进程内状态：

| Component | State | Purpose |
|-----------|-------|---------|
| `YrsDocumentManager` | `ConcurrentHashMap<UUID, YrsDocument>` | 内存中的 CRDT 文档 |
| `YjsWebSocketHandler` | `rooms`, `sessionDocs`, `decoratedSessions` | WebSocket 连接管理 |
| `YrsDocument` | Native memory pointer (FFM) | Rust CRDT 状态 |
| `YrsBridge` | Singleton FFM arena | Native library 绑定 |

文档在首次连接时加载到内存，最后断开时快照并卸载。所有广播在进程内完成。

## Architecture

```
Client ──WebSocket──> Nginx (一致性哈希 by docId)
                          ↓
                     Spring Boot Instance (无状态)
                          ├─ FFM 调用 yrs-bridge（纯计算，不持有状态）
                          ├─ Redis：文档状态热缓存 + Pub/Sub 跨实例广播
                          └─ PostgreSQL：持久化快照 + 增量更新（不变）
```

### Key Decisions

- **Redis 作为文档状态热缓存** — 文档 CRDT 编码状态缓存在 Redis，每次操作 load → compute → save back
- **Nginx 一致性哈希做软亲和** — 同文档的 WebSocket 连接优先路由到同一实例，减少跨实例广播；实例故障时自动迁移
- **FFM 保留** — yrs-bridge 仍通过 Java FFM 加载，每次操作临时创建/销毁 YrsDocument，不长期持有
- **Redis Pub/Sub 做跨实例广播** — 始终发布（简单可靠），本地广播更快到达，Pub/Sub 作为兜底

## Detailed Design

### 1. Redis Data Model

```
doc:state:{docId}            → bytes (Yrs encodeState)   TTL: 30min, 每次 update 续期
doc:lock:{docId}             → instanceId                TTL: 10s, SETNX（加载阶段防并发）
doc:snapshot-lock:{docId}    → instanceId                TTL: 30s, SETNX（快照防重复）
```

Pub/Sub channels:
```
doc:updates:{docId}          → update bytes
doc:awareness:{docId}        → awareness bytes
```

### 2. Document Lifecycle

**首次连接:**
1. Nginx 一致性哈希路由到某实例
2. `SETNX doc:lock:{docId}` 防止多实例同时加载
3. `GET doc:state:{docId}`
   - 命中 → 直接使用
   - 未命中 → 从 PostgreSQL 加载快照 + 增量 updates 重建 → `SET` 到 Redis
4. 发送 SyncStep1 给客户端

**收到 Update:**
1. 按 docId 获取 Striped Lock（进程内串行）
2. `GET doc:state:{docId}` 从 Redis
3. FFM: createDocument → loadState → applyUpdate → encodeState
4. `SET doc:state:{docId}` 写回 Redis（续期 TTL）
5. `INSERT document_updates` 写 PostgreSQL
6. 销毁临时 YrsDocument（释放 native 内存）
7. 本地广播 + Redis Pub/Sub 发布

**Redis TTL 过期:**
- 无人编辑 30 分钟后自动清理
- 不需要显式 unload 逻辑

### 3. YrsDocumentManager Refactor

从有状态的文档缓存变为无状态的操作工具类：

```java
public class YrsDocumentManager {
    private final YrsBridge bridge;
    private final RedisTemplate<String, byte[]> redis;
    private final DocumentSnapshotRepository snapshotRepo;
    private final DocumentUpdateRepository updateRepo;
    private final Striped<Lock> locks = Striped.lock(64);

    public byte[] applyClientUpdate(UUID docId, byte[] update) {
        Lock lock = locks.get(docId);
        lock.lock();
        try {
            byte[] state = loadState(docId);              // Redis → miss → DB
            YrsDocument doc = bridge.createDocument();
            try {
                doc.loadState(state);
                doc.applyUpdate(update);
                byte[] newState = doc.encodeState();
                redis.set("doc:state:" + docId, newState);
                updateRepo.save(docId, update);
                return newState;
            } finally {
                doc.close();
            }
        } finally {
            lock.unlock();
        }
    }
}
```

### 4. WebSocket Session Management

**不变的:** `rooms`, `sessionDocs`, `decoratedSessions` 仍是进程内 Map，每个实例只管自己的连接。

**新增:** 跨实例广播

- 每次 update 和 awareness 消息同时发布到 Redis Pub/Sub
- 实例首次有 session 连接到某 docId 时 SUBSCRIBE 该频道
- 实例最后一个 session 断开该 docId 时 UNSUBSCRIBE
- 收到 Pub/Sub 消息时，检查 sender instanceId，忽略自己发的

### 5. Periodic Snapshots

```java
@Scheduled(fixedRate = 5, timeUnit = TimeUnit.MINUTES)
public void snapshotActiveDocuments() {
    Set<String> keys = redis.keys("doc:state:*");
    for (String key : keys) {
        UUID docId = extractDocId(key);
        if (tryAcquireSnapshotLock(docId)) {
            try {
                byte[] state = redis.get(key);
                snapshotRepo.save(docId, state);
                updateRepo.deleteBeforeSnapshot(docId);
            } finally {
                releaseSnapshotLock(docId);
            }
        }
    }
}
```

任意实例可执行，通过 Redis `SETNX` 快照锁防止多实例重复快照。

### 6. Data Safety

| Scenario | Recovery |
|----------|----------|
| Redis down | 从 PostgreSQL 最近快照 + 增量 updates 重建 |
| Instance down | Redis 状态仍在，新实例直接读取 |
| Redis + Instance down | 增量 updates 已同步写入 PostgreSQL，从 DB 完整重建 |

### 7. Nginx Configuration

```nginx
upstream collab-backend {
    hash $docId consistent;
    server backend1:8080;
    server backend2:8080;
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

REST API 用默认轮询，WebSocket 用 `hash $docId consistent` 软亲和。

## Infrastructure Changes

### Docker Compose

新增 Redis 和 Nginx：

```yaml
redis:
  image: redis:7-alpine
  ports:
    - "6379:6379"
  volumes:
    - redisdata:/data

nginx:
  image: nginx:alpine
  ports:
    - "80:80"
  volumes:
    - ./nginx.conf:/etc/nginx/conf.d/default.conf
  depends_on:
    - backend
```

### Spring Boot Dependencies

```kotlin
// build.gradle.kts
implementation("org.springframework.boot:spring-boot-starter-data-redis")
implementation("com.google.guava:guava:33.x")  // Striped lock
```

### Spring Configuration

```yaml
spring:
  data:
    redis:
      host: ${REDIS_HOST:localhost}
      port: ${REDIS_PORT:6379}

collabdoc:
  snapshot:
    interval-minutes: 5
```

## Scope of Changes

| Module | Change |
|--------|--------|
| Backend Java | YrsDocumentManager, YjsWebSocketHandler, 新增 Redis 配置和快照任务 |
| Infrastructure | 新增 Redis, Nginx |
| Frontend | 零改动 |
| yrs-bridge (Rust) | 零改动 |
| Database schema | 零改动 |
| Auth / Permission | 零改动 |
