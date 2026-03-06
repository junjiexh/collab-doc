package com.collabdoc.collab;

import com.collabdoc.permission.PermissionService;
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
    private final PermissionService permissionService;
    private final ObjectMapper objectMapper;

    public BlockController(YrsDocumentManager docManager, YjsWebSocketHandler wsHandler,
                           PermissionService permissionService, ObjectMapper objectMapper) {
        this.docManager = docManager;
        this.wsHandler = wsHandler;
        this.permissionService = permissionService;
        this.objectMapper = objectMapper;
    }

    /** Get document content as JSON string. */
    @GetMapping
    public ResponseEntity<?> getBlocks(@AuthenticationPrincipal UUID userId, @PathVariable UUID docId) {
        if (!permissionService.canView(docId, userId)) {
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
        if (!permissionService.canEdit(docId, userId)) {
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
        if (!permissionService.canEdit(docId, userId)) {
            return ResponseEntity.status(403).body(Map.of("error", "Forbidden"));
        }
        byte[] update = docManager.deleteBlock(docId, index);
        if (update != null) {
            wsHandler.broadcastUpdate(docId, update);
        }
        return ResponseEntity.ok(Map.of("status", "ok"));
    }
}
