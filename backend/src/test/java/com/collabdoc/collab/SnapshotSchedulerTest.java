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

        scheduler.snapshotActiveDocuments();

        verify(redisState).releaseSnapshotLock(doc1);
    }
}
