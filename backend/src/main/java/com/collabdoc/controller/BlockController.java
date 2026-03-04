package com.collabdoc.controller;

import com.collabdoc.service.YrsDocumentManager;
import com.collabdoc.websocket.YjsWebSocketHandler;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/docs/{docId}/blocks")
public class BlockController {

    private final YrsDocumentManager docManager;
    private final YjsWebSocketHandler wsHandler;

    public BlockController(YrsDocumentManager docManager, YjsWebSocketHandler wsHandler) {
        this.docManager = docManager;
        this.wsHandler = wsHandler;
    }

    /** Get document content as JSON string. */
    @GetMapping
    public ResponseEntity<Map<String, String>> getBlocks(@PathVariable UUID docId) {
        String json = docManager.getBlocksJson(docId);
        return ResponseEntity.ok(Map.of("content", json));
    }

    /** Insert a block at the given index. */
    @PostMapping
    public ResponseEntity<Map<String, String>> insertBlock(
            @PathVariable UUID docId,
            @RequestBody Map<String, Object> body) {
        int index = ((Number) body.getOrDefault("index", 0)).intValue();
        String blockType = (String) body.getOrDefault("type", "paragraph");
        String content = (String) body.get("content");
        String propsJson = body.containsKey("props")
            ? new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(body.get("props")).toString()
            : null;

        byte[] update = docManager.insertBlock(docId, index, blockType, content, propsJson);
        if (update != null) {
            wsHandler.broadcastUpdate(docId, update);
        }
        return ResponseEntity.ok(Map.of("status", "ok"));
    }

    /** Delete a block at the given index. */
    @DeleteMapping("/{index}")
    public ResponseEntity<Map<String, String>> deleteBlock(
            @PathVariable UUID docId,
            @PathVariable int index) {
        byte[] update = docManager.deleteBlock(docId, index);
        if (update != null) {
            wsHandler.broadcastUpdate(docId, update);
        }
        return ResponseEntity.ok(Map.of("status", "ok"));
    }
}
