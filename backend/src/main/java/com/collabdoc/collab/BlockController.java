package com.collabdoc.collab;

import com.collabdoc.document.DocumentService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/docs/{docId}/blocks")
public class BlockController {

    private final YrsDocumentManager docManager;
    private final YjsWebSocketHandler wsHandler;
    private final DocumentService documentService;

    public BlockController(YrsDocumentManager docManager, YjsWebSocketHandler wsHandler, DocumentService documentService) {
        this.docManager = docManager;
        this.wsHandler = wsHandler;
        this.documentService = documentService;
    }

    /** Get document content as JSON string. */
    @GetMapping
    public ResponseEntity<?> getBlocks(@AuthenticationPrincipal UUID userId, @PathVariable UUID docId) {
        if (!documentService.isOwner(docId, userId)) {
            return ResponseEntity.status(403).body(Map.of("error", "Forbidden"));
        }
        String json = docManager.getBlocksJson(docId);
        return ResponseEntity.ok(Map.of("content", json));
    }

    /** Insert a block at the given index. */
    @PostMapping
    public ResponseEntity<?> insertBlock(
            @AuthenticationPrincipal UUID userId,
            @PathVariable UUID docId,
            @RequestBody Map<String, Object> body) {
        if (!documentService.isOwner(docId, userId)) {
            return ResponseEntity.status(403).body(Map.of("error", "Forbidden"));
        }
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
    public ResponseEntity<?> deleteBlock(
            @AuthenticationPrincipal UUID userId,
            @PathVariable UUID docId,
            @PathVariable int index) {
        if (!documentService.isOwner(docId, userId)) {
            return ResponseEntity.status(403).body(Map.of("error", "Forbidden"));
        }
        byte[] update = docManager.deleteBlock(docId, index);
        if (update != null) {
            wsHandler.broadcastUpdate(docId, update);
        }
        return ResponseEntity.ok(Map.of("status", "ok"));
    }
}
