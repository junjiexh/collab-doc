package com.collabdoc.collab;

import com.collabdoc.document.DocumentService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
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
    private final ObjectMapper objectMapper;

    public BlockController(YrsDocumentManager docManager, YjsWebSocketHandler wsHandler,
                           DocumentService documentService, ObjectMapper objectMapper) {
        this.docManager = docManager;
        this.wsHandler = wsHandler;
        this.documentService = documentService;
        this.objectMapper = objectMapper;
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
            @Valid @RequestBody InsertBlockRequest request) {
        if (!documentService.isOwner(docId, userId)) {
            return ResponseEntity.status(403).body(Map.of("error", "Forbidden"));
        }
        String propsJson = request.props() != null
            ? objectMapper.valueToTree(request.props()).toString()
            : null;

        byte[] update = docManager.insertBlock(docId, request.index(), request.type(), request.content(), propsJson);
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
