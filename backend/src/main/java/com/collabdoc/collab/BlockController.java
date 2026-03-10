package com.collabdoc.collab;

import com.collabdoc.permission.PermissionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/docs/{docId}/blocks")
public class BlockController {

    private final YrsDocumentManager docManager;
    private final YjsWebSocketHandler wsHandler;
    private final PermissionService permissionService;
    private final ObjectMapper objectMapper;
    private final RedisPubSubBroadcaster pubSubBroadcaster;

    public BlockController(YrsDocumentManager docManager, YjsWebSocketHandler wsHandler,
                           PermissionService permissionService, ObjectMapper objectMapper,
                           RedisPubSubBroadcaster pubSubBroadcaster) {
        this.docManager = docManager;
        this.wsHandler = wsHandler;
        this.permissionService = permissionService;
        this.objectMapper = objectMapper;
        this.pubSubBroadcaster = pubSubBroadcaster;
    }

    /** Get all blocks as parsed JSON array. */
    @GetMapping
    public ResponseEntity<?> getBlocks(@AuthenticationPrincipal UUID userId, @PathVariable UUID docId) {
        if (!permissionService.canView(docId, userId)) {
            return ResponseEntity.status(403).body(Map.of("error", "Forbidden"));
        }
        String json = docManager.getBlocksJson(docId);
        try {
            Object parsed = objectMapper.readValue(json, Object.class);
            return ResponseEntity.ok(parsed);
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of("content", json));
        }
    }

    /** Get a single block by ID. */
    @GetMapping("/{blockId}")
    public ResponseEntity<?> getBlockById(
            @AuthenticationPrincipal UUID userId,
            @PathVariable UUID docId,
            @PathVariable String blockId) {
        if (!permissionService.canView(docId, userId)) {
            return ResponseEntity.status(403).body(Map.of("error", "Forbidden"));
        }
        String json = docManager.getBlockById(docId, blockId);
        if (json == null) {
            return ResponseEntity.status(404).body(Map.of("error", "Block not found"));
        }
        try {
            Object parsed = objectMapper.readValue(json, Object.class);
            return ResponseEntity.ok(parsed);
        } catch (Exception e) {
            return ResponseEntity.ok(json);
        }
    }

    /** Insert block(s) with semantic position. Supports batch via children array. */
    @PostMapping
    public ResponseEntity<?> insertBlock(
            @AuthenticationPrincipal UUID userId,
            @PathVariable UUID docId,
            @Valid @RequestBody InsertBlockV2Request request) {
        if (!permissionService.canEdit(docId, userId)) {
            return ResponseEntity.status(403).body(Map.of("error", "Forbidden"));
        }

        // Insert the primary block
        String propsJson = request.props() != null
                ? objectMapper.valueToTree(request.props()).toString()
                : null;
        byte[] update = docManager.insertBlockV2(docId, request.type(), request.content(),
                propsJson, request.position(), request.afterId());
        if (update != null) {
            wsHandler.broadcastUpdate(docId, update);
            pubSubBroadcaster.publishUpdate(docId, YjsSyncProtocol.encodeSyncUpdate(update));
        }

        // Insert children if provided (batch insert)
        if (request.children() != null && !request.children().isEmpty()) {
            for (var child : request.children()) {
                String childPropsJson = child.props() != null
                        ? objectMapper.valueToTree(child.props()).toString()
                        : null;
                byte[] childUpdate = docManager.insertBlockV2(docId, child.type(), child.content(),
                        childPropsJson, "end", null);
                if (childUpdate != null) {
                    wsHandler.broadcastUpdate(docId, childUpdate);
                    pubSubBroadcaster.publishUpdate(docId, YjsSyncProtocol.encodeSyncUpdate(childUpdate));
                }
            }
        }

        // Return current block list as response
        String blocksJson = docManager.getBlocksJson(docId);
        try {
            Object parsed = objectMapper.readValue(blocksJson, Object.class);
            return ResponseEntity.ok(parsed);
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of("content", blocksJson));
        }
    }

    /** Update a block by ID. Partial patch semantics. */
    @PatchMapping("/{blockId}")
    public ResponseEntity<?> updateBlock(
            @AuthenticationPrincipal UUID userId,
            @PathVariable UUID docId,
            @PathVariable String blockId,
            @RequestBody UpdateBlockRequest request) {
        if (!permissionService.canEdit(docId, userId)) {
            return ResponseEntity.status(403).body(Map.of("error", "Forbidden"));
        }

        String propsJson = request.props() != null
                ? objectMapper.valueToTree(request.props()).toString()
                : null;

        byte[] update = docManager.updateBlock(docId, blockId, request.type(), request.content(), propsJson);
        if (update == null) {
            return ResponseEntity.status(404).body(Map.of("error", "Block not found"));
        }
        wsHandler.broadcastUpdate(docId, update);
        pubSubBroadcaster.publishUpdate(docId, YjsSyncProtocol.encodeSyncUpdate(update));

        // Return updated block
        String blockJson = docManager.getBlockById(docId, blockId);
        try {
            Object parsed = objectMapper.readValue(blockJson, Object.class);
            return ResponseEntity.ok(parsed);
        } catch (Exception e) {
            return ResponseEntity.ok(blockJson);
        }
    }

    /** Delete a block by ID. */
    @DeleteMapping("/{blockId}")
    public ResponseEntity<?> deleteBlock(
            @AuthenticationPrincipal UUID userId,
            @PathVariable UUID docId,
            @PathVariable String blockId) {
        if (!permissionService.canEdit(docId, userId)) {
            return ResponseEntity.status(403).body(Map.of("error", "Forbidden"));
        }

        byte[] update = docManager.deleteBlockById(docId, blockId);
        if (update == null) {
            return ResponseEntity.status(404).body(Map.of("error", "Block not found"));
        }
        wsHandler.broadcastUpdate(docId, update);
        pubSubBroadcaster.publishUpdate(docId, YjsSyncProtocol.encodeSyncUpdate(update));

        return ResponseEntity.ok(Map.of("status", "ok", "deleted", blockId));
    }
}
