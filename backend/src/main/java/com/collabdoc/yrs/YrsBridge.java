package com.collabdoc.yrs;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.nio.file.Path;

/**
 * Loads the native yrs-bridge library via Java's Foreign Function & Memory (FFM) API
 * and exposes downcall MethodHandles for each C function.
 *
 * <p>Usage:
 * <pre>{@code
 * try (var bridge = new YrsBridge("/path/to/libyrs_bridge.dylib")) {
 *     try (var doc = bridge.createDocument()) {
 *         doc.insertBlock(0, "paragraph", "Hello", null);
 *         byte[] state = doc.encodeState();
 *     }
 * }
 * }</pre>
 */
public class YrsBridge implements AutoCloseable {

    private final Arena arena;

    // ---- MethodHandles for each C function ----

    final MethodHandle yrsDocNew;
    final MethodHandle yrsDocDestroy;
    final MethodHandle yrsDocStateVector;
    final MethodHandle yrsDocEncodeDiff;
    final MethodHandle yrsDocEncodeState;
    final MethodHandle yrsDocApplyUpdate;
    final MethodHandle yrsDocLoadState;
    final MethodHandle yrsDocGetBlocksJson;
    final MethodHandle yrsDocInsertBlock;
    final MethodHandle yrsDocDeleteBlock;
    final MethodHandle yrsDocGetBlockById;
    final MethodHandle yrsDocUpdateBlock;
    final MethodHandle yrsDocDeleteBlockById;
    final MethodHandle yrsDocInsertBlockV2;
    final MethodHandle yrsDocGetBlockAtIndex;
    final MethodHandle yrsFreeBytes;
    final MethodHandle yrsFreeString;

    /**
     * Load the native yrs-bridge library from the given filesystem path.
     *
     * @param libraryPath absolute or relative path to the shared library (.dylib / .so)
     */
    public YrsBridge(String libraryPath) {
        this.arena = Arena.ofShared();
        SymbolLookup lib = SymbolLookup.libraryLookup(Path.of(libraryPath), arena);
        Linker linker = Linker.nativeLinker();

        // YrsDoc* yrs_doc_new()
        yrsDocNew = linker.downcallHandle(
                lib.find("yrs_doc_new").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.ADDRESS)
        );

        // void yrs_doc_destroy(YrsDoc*)
        yrsDocDestroy = linker.downcallHandle(
                lib.find("yrs_doc_destroy").orElseThrow(),
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS)
        );

        // uint8_t* yrs_doc_state_vector(YrsDoc*, uint32_t* out_len)
        yrsDocStateVector = linker.downcallHandle(
                lib.find("yrs_doc_state_vector").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS)
        );

        // uint8_t* yrs_doc_encode_diff(YrsDoc*, uint8_t* sv, uint32_t sv_len, uint32_t* out_len)
        yrsDocEncodeDiff = linker.downcallHandle(
                lib.find("yrs_doc_encode_diff").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS)
        );

        // uint8_t* yrs_doc_encode_state(YrsDoc*, uint32_t* out_len)
        yrsDocEncodeState = linker.downcallHandle(
                lib.find("yrs_doc_encode_state").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS)
        );

        // uint8_t* yrs_doc_apply_update(YrsDoc*, uint8_t* update, uint32_t len, uint32_t* out_len)
        yrsDocApplyUpdate = linker.downcallHandle(
                lib.find("yrs_doc_apply_update").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS)
        );

        // int32_t yrs_doc_load_state(YrsDoc*, uint8_t* state, uint32_t len)
        yrsDocLoadState = linker.downcallHandle(
                lib.find("yrs_doc_load_state").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT)
        );

        // char* yrs_doc_get_blocks_json(YrsDoc*)
        yrsDocGetBlocksJson = linker.downcallHandle(
                lib.find("yrs_doc_get_blocks_json").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS)
        );

        // uint8_t* yrs_doc_insert_block(YrsDoc*, uint32_t index, char* type, char* content, char* props, uint32_t* out_len)
        yrsDocInsertBlock = linker.downcallHandle(
                lib.find("yrs_doc_insert_block").orElseThrow(),
                FunctionDescriptor.of(
                        ValueLayout.ADDRESS,
                        ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                        ValueLayout.ADDRESS
                )
        );

        // uint8_t* yrs_doc_delete_block(YrsDoc*, uint32_t index, uint32_t* out_len)
        yrsDocDeleteBlock = linker.downcallHandle(
                lib.find("yrs_doc_delete_block").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS)
        );

        // char* yrs_doc_get_block_by_id(YrsDoc*, char* block_id)
        yrsDocGetBlockById = linker.downcallHandle(
                lib.find("yrs_doc_get_block_by_id").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS)
        );

        // uint8_t* yrs_doc_update_block(YrsDoc*, char* block_id, char* new_type, char* new_content, char* new_props_json, uint32_t* out_len)
        yrsDocUpdateBlock = linker.downcallHandle(
                lib.find("yrs_doc_update_block").orElseThrow(),
                FunctionDescriptor.of(
                        ValueLayout.ADDRESS,
                        ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                        ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                        ValueLayout.ADDRESS
                )
        );

        // uint8_t* yrs_doc_delete_block_by_id(YrsDoc*, char* block_id, uint32_t* out_len)
        yrsDocDeleteBlockById = linker.downcallHandle(
                lib.find("yrs_doc_delete_block_by_id").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS)
        );

        // uint8_t* yrs_doc_insert_block_v2(YrsDoc*, char* type, char* content, char* props, char* position, char* after_id, uint32_t* out_len)
        yrsDocInsertBlockV2 = linker.downcallHandle(
                lib.find("yrs_doc_insert_block_v2").orElseThrow(),
                FunctionDescriptor.of(
                        ValueLayout.ADDRESS,
                        ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                        ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                        ValueLayout.ADDRESS
                )
        );

        // char* yrs_doc_get_block_at_index(YrsDoc*, uint32_t index)
        yrsDocGetBlockAtIndex = linker.downcallHandle(
                lib.find("yrs_doc_get_block_at_index").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT)
        );

        // void yrs_free_bytes(uint8_t*, uint32_t)
        yrsFreeBytes = linker.downcallHandle(
                lib.find("yrs_free_bytes").orElseThrow(),
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.JAVA_INT)
        );

        // void yrs_free_string(char*)
        yrsFreeString = linker.downcallHandle(
                lib.find("yrs_free_string").orElseThrow(),
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS)
        );
    }

    /**
     * Create a new YrsDocument backed by this bridge's native library.
     *
     * @return a new document instance (caller must close it)
     */
    public YrsDocument createDocument() {
        return new YrsDocument(this);
    }

    @Override
    public void close() {
        arena.close();
    }
}
