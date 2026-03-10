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
