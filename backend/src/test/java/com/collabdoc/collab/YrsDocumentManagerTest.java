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
        byte[] rebuiltState = new byte[]{20, 21};

        // Redis misses in loadState (initial check + double-check after lock)
        when(redisState.getState(docId)).thenReturn(null);
        when(redisState.tryAcquireLoadLock(docId)).thenReturn(true);
        when(snapshotRepo.findById(docId)).thenReturn(Optional.of(new DocumentSnapshot(docId, snapshotData)));
        when(updateRepo.findByDocIdOrderByIdAsc(docId)).thenReturn(Collections.emptyList());

        // Doc created for DB rebuild in loadState
        var rebuildDoc = mock(YrsDocument.class);
        when(rebuildDoc.encodeState()).thenReturn(rebuiltState);

        // Doc created for the actual mutation in withMutation
        var mutationDoc = mock(YrsDocument.class);
        when(mutationDoc.applyUpdate(updateBytes)).thenReturn(appliedResult);
        when(mutationDoc.encodeState()).thenReturn(newState);

        when(bridge.createDocument()).thenReturn(rebuildDoc, mutationDoc);

        byte[] result = manager.applyClientUpdate(docId, updateBytes);

        assertArrayEquals(appliedResult, result);
        verify(rebuildDoc).loadState(snapshotData);
        verify(rebuildDoc).close();
        verify(mutationDoc).loadState(rebuiltState);
        verify(mutationDoc).close();
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

    @Test
    void createSnapshot_savesToDbAndCleansUpdates() {
        UUID docId = UUID.randomUUID();
        byte[] state = new byte[]{1, 2, 3};
        when(redisState.getState(docId)).thenReturn(state);
        when(updateRepo.findMaxIdByDocId(docId)).thenReturn(42L);

        manager.createSnapshot(docId);

        verify(snapshotRepo).save(any(DocumentSnapshot.class));
        verify(updateRepo).deleteByDocIdAndIdLessThanEqual(docId, 42L);
    }

    @Test
    void createSnapshot_doesNothing_whenNoRedisState() {
        UUID docId = UUID.randomUUID();
        when(redisState.getState(docId)).thenReturn(null);

        manager.createSnapshot(docId);

        verify(snapshotRepo, never()).save(any());
    }
}
