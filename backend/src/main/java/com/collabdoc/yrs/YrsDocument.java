package com.collabdoc.yrs;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * Java wrapper around a single Yrs (Yjs) CRDT document backed by native memory.
 *
 * <p>Each instance holds an opaque pointer to a {@code YrsDoc} allocated by the Rust
 * yrs-bridge library. All public methods create a short-lived confined {@link Arena}
 * for temporary native allocations, invoke the appropriate native function via the
 * {@link YrsBridge} downcall handles, and translate results back into Java types.
 *
 * <p>This class implements {@link AutoCloseable}; callers must close the document
 * when finished to release native memory.
 */
public class YrsDocument implements AutoCloseable {

    private final YrsBridge bridge;
    private MemorySegment docPtr;

    YrsDocument(YrsBridge bridge) {
        this.bridge = bridge;
        try {
            this.docPtr = (MemorySegment) bridge.yrsDocNew.invokeExact();
        } catch (Throwable t) {
            throw new RuntimeException("Failed to create native YrsDoc", t);
        }
        if (docPtr.equals(MemorySegment.NULL)) {
            throw new RuntimeException("yrs_doc_new returned null");
        }
    }

    /**
     * Encode the current state vector of this document.
     *
     * @return the state vector as a byte array
     */
    public byte[] getStateVector() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment outLen = arena.allocate(ValueLayout.JAVA_INT);

            MemorySegment ptr = (MemorySegment) bridge.yrsDocStateVector.invokeExact(docPtr, outLen);
            if (ptr.equals(MemorySegment.NULL)) {
                throw new RuntimeException("yrs_doc_state_vector returned null");
            }

            int len = outLen.get(ValueLayout.JAVA_INT, 0);
            byte[] result = ptr.reinterpret(len).toArray(ValueLayout.JAVA_BYTE);

            bridge.yrsFreeBytes.invokeExact(ptr, len);
            return result;
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw new RuntimeException("Failed to get state vector", t);
        }
    }

    /**
     * Compute a diff between this document's current state and the provided
     * remote state vector. The resulting bytes can be sent to the remote peer
     * so it can catch up.
     *
     * @param stateVector the remote peer's state vector
     * @return the encoded diff (update) bytes
     */
    public byte[] encodeDiff(byte[] stateVector) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment svNative = arena.allocateFrom(ValueLayout.JAVA_BYTE, stateVector);
            MemorySegment outLen = arena.allocate(ValueLayout.JAVA_INT);

            MemorySegment ptr = (MemorySegment) bridge.yrsDocEncodeDiff.invokeExact(
                    docPtr, svNative, stateVector.length, outLen
            );
            if (ptr.equals(MemorySegment.NULL)) {
                throw new RuntimeException("yrs_doc_encode_diff returned null");
            }

            int len = outLen.get(ValueLayout.JAVA_INT, 0);
            byte[] result = ptr.reinterpret(len).toArray(ValueLayout.JAVA_BYTE);

            bridge.yrsFreeBytes.invokeExact(ptr, len);
            return result;
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw new RuntimeException("Failed to encode diff", t);
        }
    }

    /**
     * Encode the full document state as an update (relative to an empty state vector).
     * Useful for persistence snapshots.
     *
     * @return the full state as a byte array
     */
    public byte[] encodeState() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment outLen = arena.allocate(ValueLayout.JAVA_INT);

            MemorySegment ptr = (MemorySegment) bridge.yrsDocEncodeState.invokeExact(docPtr, outLen);
            if (ptr.equals(MemorySegment.NULL)) {
                throw new RuntimeException("yrs_doc_encode_state returned null");
            }

            int len = outLen.get(ValueLayout.JAVA_INT, 0);
            byte[] result = ptr.reinterpret(len).toArray(ValueLayout.JAVA_BYTE);

            bridge.yrsFreeBytes.invokeExact(ptr, len);
            return result;
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw new RuntimeException("Failed to encode state", t);
        }
    }

    /**
     * Apply a binary update (from another client or from persistence) to this document.
     *
     * @param update the update bytes to apply
     * @return a copy of the update bytes suitable for broadcasting to other clients,
     *         or {@code null} if the update could not be applied
     */
    public byte[] applyUpdate(byte[] update) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment updateNative = arena.allocateFrom(ValueLayout.JAVA_BYTE, update);
            MemorySegment outLen = arena.allocate(ValueLayout.JAVA_INT);

            MemorySegment ptr = (MemorySegment) bridge.yrsDocApplyUpdate.invokeExact(
                    docPtr, updateNative, update.length, outLen
            );
            if (ptr.equals(MemorySegment.NULL)) {
                return null;
            }

            int len = outLen.get(ValueLayout.JAVA_INT, 0);
            byte[] result = ptr.reinterpret(len).toArray(ValueLayout.JAVA_BYTE);

            bridge.yrsFreeBytes.invokeExact(ptr, len);
            return result;
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw new RuntimeException("Failed to apply update", t);
        }
    }

    /**
     * Load a full state snapshot into this document. Typically used on startup
     * to restore persisted state.
     *
     * @param state the state bytes (encoded via {@link #encodeState()})
     * @throws RuntimeException if the state could not be loaded
     */
    public void loadState(byte[] state) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment stateNative = arena.allocateFrom(ValueLayout.JAVA_BYTE, state);

            int result = (int) bridge.yrsDocLoadState.invokeExact(docPtr, stateNative, state.length);
            if (result != 0) {
                throw new RuntimeException("yrs_doc_load_state failed with code " + result);
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw new RuntimeException("Failed to load state", t);
        }
    }

    /**
     * Get all blocks in the document as a JSON string.
     *
     * @return JSON array string representing the document blocks
     */
    public String getBlocksJson() {
        try {
            MemorySegment ptr = (MemorySegment) bridge.yrsDocGetBlocksJson.invokeExact(docPtr);
            if (ptr.equals(MemorySegment.NULL)) {
                throw new RuntimeException("yrs_doc_get_blocks_json returned null");
            }

            String result = ptr.reinterpret(Long.MAX_VALUE).getString(0);

            bridge.yrsFreeString.invokeExact(ptr);
            return result;
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw new RuntimeException("Failed to get blocks JSON", t);
        }
    }

    /**
     * Get a single block by its ID.
     *
     * @param blockId the block's UUID string
     * @return JSON string of the block, or null if not found
     */
    public String getBlockById(String blockId) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment idNative = arena.allocateFrom(blockId);
            MemorySegment ptr = (MemorySegment) bridge.yrsDocGetBlockById.invokeExact(docPtr, idNative);
            if (ptr.equals(MemorySegment.NULL)) {
                return null;
            }
            String result = ptr.reinterpret(Long.MAX_VALUE).getString(0);
            bridge.yrsFreeString.invokeExact(ptr);
            return result;
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw new RuntimeException("Failed to get block by ID", t);
        }
    }

    /**
     * Update a block's content, type, and/or properties.
     *
     * @param blockId   the block's UUID string
     * @param newType   new block type (null to keep current)
     * @param newContent new text content (null to keep current)
     * @param newPropsJson new properties JSON (null to keep current)
     * @return update diff bytes for broadcasting, or null if block not found
     */
    public byte[] updateBlock(String blockId, String newType, String newContent, String newPropsJson) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment idNative = arena.allocateFrom(blockId);
            MemorySegment typeNative = (newType != null) ? arena.allocateFrom(newType) : MemorySegment.NULL;
            MemorySegment contentNative = (newContent != null) ? arena.allocateFrom(newContent) : MemorySegment.NULL;
            MemorySegment propsNative = (newPropsJson != null) ? arena.allocateFrom(newPropsJson) : MemorySegment.NULL;
            MemorySegment outLen = arena.allocate(ValueLayout.JAVA_INT);

            MemorySegment ptr = (MemorySegment) bridge.yrsDocUpdateBlock.invokeExact(
                    docPtr, idNative, typeNative, contentNative, propsNative, outLen
            );
            if (ptr.equals(MemorySegment.NULL)) {
                return null;
            }

            int len = outLen.get(ValueLayout.JAVA_INT, 0);
            byte[] result = ptr.reinterpret(len).toArray(ValueLayout.JAVA_BYTE);
            bridge.yrsFreeBytes.invokeExact(ptr, len);
            return result;
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw new RuntimeException("Failed to update block", t);
        }
    }

    /**
     * Delete a block by its ID.
     *
     * @param blockId the block's UUID string
     * @return update diff bytes for broadcasting, or null if block not found
     */
    public byte[] deleteBlockById(String blockId) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment idNative = arena.allocateFrom(blockId);
            MemorySegment outLen = arena.allocate(ValueLayout.JAVA_INT);

            MemorySegment ptr = (MemorySegment) bridge.yrsDocDeleteBlockById.invokeExact(
                    docPtr, idNative, outLen
            );
            if (ptr.equals(MemorySegment.NULL)) {
                return null;
            }

            int len = outLen.get(ValueLayout.JAVA_INT, 0);
            byte[] result = ptr.reinterpret(len).toArray(ValueLayout.JAVA_BYTE);
            bridge.yrsFreeBytes.invokeExact(ptr, len);
            return result;
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw new RuntimeException("Failed to delete block by ID", t);
        }
    }

    /**
     * Insert a block with semantic position control.
     *
     * @param blockType  XML tag name (e.g. "paragraph", "heading")
     * @param content    text content (may be null)
     * @param propsJson  properties JSON (may be null)
     * @param position   one of "start", "end", "after_block"
     * @param afterId    block ID to insert after (only used when position = "after_block")
     * @return update diff bytes for broadcasting
     */
    public byte[] insertBlockV2(String blockType, String content, String propsJson, String position, String afterId) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment typeNative = arena.allocateFrom(blockType);
            MemorySegment contentNative = (content != null) ? arena.allocateFrom(content) : MemorySegment.NULL;
            MemorySegment propsNative = (propsJson != null) ? arena.allocateFrom(propsJson) : MemorySegment.NULL;
            MemorySegment posNative = arena.allocateFrom(position);
            MemorySegment afterNative = (afterId != null) ? arena.allocateFrom(afterId) : MemorySegment.NULL;
            MemorySegment outLen = arena.allocate(ValueLayout.JAVA_INT);

            MemorySegment ptr = (MemorySegment) bridge.yrsDocInsertBlockV2.invokeExact(
                    docPtr, typeNative, contentNative, propsNative, posNative, afterNative, outLen
            );
            if (ptr.equals(MemorySegment.NULL)) {
                throw new RuntimeException("yrs_doc_insert_block_v2 returned null");
            }

            int len = outLen.get(ValueLayout.JAVA_INT, 0);
            byte[] result = ptr.reinterpret(len).toArray(ValueLayout.JAVA_BYTE);
            bridge.yrsFreeBytes.invokeExact(ptr, len);
            return result;
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw new RuntimeException("Failed to insert block v2", t);
        }
    }

    /**
     * Get a single block by its index in the blockGroup.
     *
     * @param index 0-based position
     * @return JSON string of the block, or null if out of bounds
     */
    public String getBlockAtIndex(int index) {
        try {
            MemorySegment ptr = (MemorySegment) bridge.yrsDocGetBlockAtIndex.invokeExact(docPtr, index);
            if (ptr.equals(MemorySegment.NULL)) {
                return null;
            }
            String result = ptr.reinterpret(Long.MAX_VALUE).getString(0);
            bridge.yrsFreeString.invokeExact(ptr);
            return result;
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw new RuntimeException("Failed to get block at index", t);
        }
    }

    /**
     * Insert a new block at the given index in the document.
     *
     * @param index     position to insert (0-based)
     * @param blockType XML tag name (e.g. "paragraph", "heading")
     * @param content   text content for the block (may be null or empty)
     * @param propsJson optional JSON object string with block properties (may be null)
     * @return the update diff bytes for broadcasting to other clients
     */
    public byte[] insertBlock(int index, String blockType, String content, String propsJson) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment typeNative = arena.allocateFrom(blockType);
            MemorySegment contentNative = (content != null)
                    ? arena.allocateFrom(content)
                    : MemorySegment.NULL;
            MemorySegment propsNative = (propsJson != null)
                    ? arena.allocateFrom(propsJson)
                    : MemorySegment.NULL;
            MemorySegment outLen = arena.allocate(ValueLayout.JAVA_INT);

            MemorySegment ptr = (MemorySegment) bridge.yrsDocInsertBlock.invokeExact(
                    docPtr, index, typeNative, contentNative, propsNative, outLen
            );
            if (ptr.equals(MemorySegment.NULL)) {
                throw new RuntimeException("yrs_doc_insert_block returned null");
            }

            int len = outLen.get(ValueLayout.JAVA_INT, 0);
            byte[] result = ptr.reinterpret(len).toArray(ValueLayout.JAVA_BYTE);

            bridge.yrsFreeBytes.invokeExact(ptr, len);
            return result;
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw new RuntimeException("Failed to insert block", t);
        }
    }

    /**
     * Delete the block at the given index in the document.
     *
     * @param index position of the block to delete (0-based)
     * @return the update diff bytes for broadcasting to other clients
     */
    public byte[] deleteBlock(int index) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment outLen = arena.allocate(ValueLayout.JAVA_INT);

            MemorySegment ptr = (MemorySegment) bridge.yrsDocDeleteBlock.invokeExact(
                    docPtr, index, outLen
            );
            if (ptr.equals(MemorySegment.NULL)) {
                throw new RuntimeException("yrs_doc_delete_block returned null");
            }

            int len = outLen.get(ValueLayout.JAVA_INT, 0);
            byte[] result = ptr.reinterpret(len).toArray(ValueLayout.JAVA_BYTE);

            bridge.yrsFreeBytes.invokeExact(ptr, len);
            return result;
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw new RuntimeException("Failed to delete block", t);
        }
    }

    /**
     * Destroy the native document and release its memory.
     * After calling close, no other methods may be called on this instance.
     */
    @Override
    public void close() {
        if (docPtr != null && !docPtr.equals(MemorySegment.NULL)) {
            try {
                bridge.yrsDocDestroy.invokeExact(docPtr);
            } catch (Throwable t) {
                throw new RuntimeException("Failed to destroy native YrsDoc", t);
            }
            docPtr = MemorySegment.NULL;
        }
    }
}
