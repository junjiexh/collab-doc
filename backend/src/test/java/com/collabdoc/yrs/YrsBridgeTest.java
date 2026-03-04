package com.collabdoc.yrs;

import org.junit.jupiter.api.*;

import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for the Java FFM bridge to the yrs-bridge Rust library.
 * These tests exercise the full native call chain: Java -> FFM -> Rust -> Yrs CRDT.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class YrsBridgeTest {

    private static YrsBridge bridge;

    @BeforeAll
    static void setUp() {
        // Detect OS to pick the correct library extension
        String osName = System.getProperty("os.name", "").toLowerCase();
        String libName;
        if (osName.contains("mac") || osName.contains("darwin")) {
            libName = "libyrs_bridge.dylib";
        } else {
            libName = "libyrs_bridge.so";
        }

        // Library is at ../yrs-bridge/target/release/ relative to the backend directory.
        // Tests run with CWD = backend project dir.
        Path libPath = Path.of("../yrs-bridge/target/release", libName).toAbsolutePath().normalize();
        System.out.println("Loading native library from: " + libPath);

        bridge = new YrsBridge(libPath.toString());
    }

    @AfterAll
    static void tearDown() {
        if (bridge != null) {
            bridge.close();
        }
    }

    @Test
    @Order(1)
    void createAndDestroyDocument() {
        YrsDocument doc = bridge.createDocument();
        assertNotNull(doc);
        doc.close();
    }

    @Test
    @Order(2)
    void emptyDocumentStateVector() {
        try (YrsDocument doc = bridge.createDocument()) {
            byte[] sv = doc.getStateVector();
            assertNotNull(sv);
            assertTrue(sv.length > 0, "State vector should be non-empty even for an empty doc");
        }
    }

    @Test
    @Order(3)
    void insertBlockAndEncode() {
        try (YrsDocument doc = bridge.createDocument()) {
            byte[] update = doc.insertBlock(0, "paragraph", "Hello, FFM!", null);
            assertNotNull(update);
            assertTrue(update.length > 0);

            String json = doc.getBlocksJson();
            assertNotNull(json);
            assertTrue(json.contains("paragraph"), "JSON should contain the paragraph block type");
            assertTrue(json.contains("Hello, FFM!"), "JSON should contain the text content");
        }
    }

    @Test
    @Order(4)
    void syncTwoDocuments() {
        try (YrsDocument doc1 = bridge.createDocument();
             YrsDocument doc2 = bridge.createDocument()) {

            // Insert content into doc1
            doc1.insertBlock(0, "paragraph", "Synced paragraph", null);

            // Get doc2's state vector
            byte[] sv2 = doc2.getStateVector();

            // Get diff from doc1 relative to doc2's state vector
            byte[] diff = doc1.encodeDiff(sv2);
            assertNotNull(diff);
            assertTrue(diff.length > 0);

            // Apply the diff to doc2
            byte[] broadcast = doc2.applyUpdate(diff);
            assertNotNull(broadcast);

            // Both documents should now have the same state
            byte[] state1 = doc1.encodeState();
            byte[] state2 = doc2.encodeState();
            assertArrayEquals(state1, state2, "After sync, both documents should have identical state");
        }
    }

    @Test
    @Order(5)
    void loadStateFromSnapshot() {
        byte[] snapshot;

        // Create doc1 with some content and take a snapshot
        try (YrsDocument doc1 = bridge.createDocument()) {
            doc1.insertBlock(0, "paragraph", "Persisted content", null);
            doc1.insertBlock(1, "heading", "A heading", "{\"level\":\"2\"}");
            snapshot = doc1.encodeState();
            assertNotNull(snapshot);
            assertTrue(snapshot.length > 0);
        }

        // Create doc2 and load the snapshot
        try (YrsDocument doc2 = bridge.createDocument()) {
            doc2.loadState(snapshot);

            String json = doc2.getBlocksJson();
            assertNotNull(json);
            assertTrue(json.contains("paragraph"), "Restored doc should contain paragraph block");
            assertTrue(json.contains("heading"), "Restored doc should contain heading block");
            assertTrue(json.contains("Persisted content"), "Restored doc should contain persisted text");
        }
    }

    @Test
    @Order(6)
    void deleteBlock() {
        try (YrsDocument doc = bridge.createDocument()) {
            doc.insertBlock(0, "paragraph", "First", null);
            doc.insertBlock(1, "paragraph", "Second", null);

            // Verify we have 2 blocks
            String json = doc.getBlocksJson();
            assertTrue(json.contains("First"));
            assertTrue(json.contains("Second"));

            // Delete the first block
            byte[] update = doc.deleteBlock(0);
            assertNotNull(update);
            assertTrue(update.length > 0);

            // Verify we have 1 block remaining
            json = doc.getBlocksJson();
            assertFalse(json.contains("First"), "First block should be deleted");
            assertTrue(json.contains("Second"), "Second block should remain");
        }
    }

    @Test
    @Order(7)
    void emptyDocumentBlocksJson() {
        try (YrsDocument doc = bridge.createDocument()) {
            String json = doc.getBlocksJson();
            assertNotNull(json);
            assertEquals("[]", json, "Empty document should return empty JSON array");
        }
    }

    @Test
    @Order(8)
    void applyUpdateReturnsNullForInvalidData() {
        try (YrsDocument doc = bridge.createDocument()) {
            byte[] invalid = new byte[]{0, 1, 2, 3};
            byte[] result = doc.applyUpdate(invalid);
            assertNull(result, "Applying invalid update data should return null");
        }
    }
}
