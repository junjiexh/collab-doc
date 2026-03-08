package com.collabdoc.collab;

import com.collabdoc.yrs.YrsBridge;
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
 * Redis miss -> DB load -> FFM compute -> Redis save -> subsequent Redis hit.
 */
class StatelessFlowIntegrationTest {

    private YrsBridge bridge;
    private RedisDocumentStateService redisState;
    private DocumentUpdateRepository updateRepo;
    private DocumentSnapshotRepository snapshotRepo;
    private YrsDocumentManager manager;

    @BeforeEach
    void setUp() {
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

        // First call: Redis miss, DB empty
        when(redisState.getState(docId)).thenReturn(null);
        when(snapshotRepo.findById(docId)).thenReturn(Optional.empty());
        when(updateRepo.findByDocIdOrderByIdAsc(docId)).thenReturn(Collections.emptyList());

        // Insert a block
        byte[] update = manager.insertBlockV2(docId, "paragraph", "Hello world", null, "end", null);
        assertNotNull(update);

        // Verify state was saved to Redis
        verify(redisState, atLeastOnce()).setState(eq(docId), any(byte[].class));

        // Capture the saved state for next call
        var stateCaptor = org.mockito.ArgumentCaptor.forClass(byte[].class);
        verify(redisState, atLeastOnce()).setState(eq(docId), stateCaptor.capture());
        byte[] savedState = stateCaptor.getAllValues().getLast();

        // Second call: Redis hit
        reset(redisState, snapshotRepo, updateRepo);
        when(redisState.getState(docId)).thenReturn(savedState);

        String blocksJson = manager.getBlocksJson(docId);
        assertNotNull(blocksJson);
        assertTrue(blocksJson.contains("Hello world"));

        // Verify no DB access on second call (Redis hit)
        verify(snapshotRepo, never()).findById(any());
    }
}
