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

        // Try to acquire load lock — another instance might be rebuilding
        if (!redisState.tryAcquireLoadLock(docId)) {
            // Another instance is loading — wait briefly and retry from Redis
            try { Thread.sleep(100); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            state = redisState.getState(docId);
            if (state != null) return state;
            // Still no state — proceed anyway (lock may have expired)
        }

        try {
            // Double-check Redis after acquiring lock
            state = redisState.getState(docId);
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
        } finally {
            redisState.releaseLoadLock(docId);
        }
    }

    /** Execute a read-only operation on a document with per-docId locking. */
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

    // --- Public API ---

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
        Lock lock = locks.get(docId);
        lock.lock();
        try {
            byte[] state = redisState.getState(docId);
            if (state != null) {
                Long maxUpdateId = updateRepository.findMaxIdByDocId(docId);
                snapshotRepository.save(new DocumentSnapshot(docId, state));
                if (maxUpdateId != null) {
                    updateRepository.deleteByDocIdAndIdLessThanEqual(docId, maxUpdateId);
                }
            }
        } finally {
            lock.unlock();
        }
    }
}
