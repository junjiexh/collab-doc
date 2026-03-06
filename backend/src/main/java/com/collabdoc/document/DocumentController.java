package com.collabdoc.document;

import com.collabdoc.permission.PermissionService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/docs")
public class DocumentController {

    private final DocumentService documentService;
    private final PermissionService permissionService;

    public DocumentController(DocumentService documentService, PermissionService permissionService) {
        this.documentService = documentService;
        this.permissionService = permissionService;
    }

    @GetMapping
    public List<Document> listDocuments(@AuthenticationPrincipal UUID userId) {
        return documentService.listDocumentsForTree(userId);
    }

    @GetMapping("/tree")
    public List<Document> listDocumentsForTree(@AuthenticationPrincipal UUID userId) {
        return documentService.listDocumentsForTree(userId);
    }

    @GetMapping("/shared-with-me")
    public ResponseEntity<?> sharedWithMe(@AuthenticationPrincipal UUID userId) {
        return ResponseEntity.ok(permissionService.getSharedDocuments(userId));
    }

    @PostMapping
    public Document createDocument(@AuthenticationPrincipal UUID userId,
                                   @Valid @RequestBody CreateDocumentRequest request) {
        String title = request.title() != null ? request.title() : "Untitled";
        UUID parentId = request.parentId() != null ? UUID.fromString(request.parentId()) : null;
        return documentService.createDocument(title, parentId, userId);
    }

    @PutMapping("/{id}/move")
    public ResponseEntity<Document> moveDocument(@AuthenticationPrincipal UUID userId,
                                                  @PathVariable UUID id,
                                                  @Valid @RequestBody MoveDocumentRequest request) {
        UUID parentId = request.parentId() != null ? UUID.fromString(request.parentId()) : null;
        return documentService.moveDocument(id, parentId, request.sortOrder(), userId)
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> getDocument(@AuthenticationPrincipal UUID userId, @PathVariable UUID id) {
        String perm = permissionService.resolvePermission(id, userId);
        if (perm == null) {
            return ResponseEntity.status(403).body(Map.of("error", "Forbidden"));
        }
        return documentService.getDocumentById(id)
            .map(doc -> {
                var result = new HashMap<String, Object>();
                result.put("id", doc.getId());
                result.put("title", doc.getTitle());
                result.put("parentId", doc.getParentId());
                result.put("sortOrder", doc.getSortOrder());
                result.put("createdAt", doc.getCreatedAt());
                result.put("updatedAt", doc.getUpdatedAt());
                result.put("permission", perm);
                return ResponseEntity.ok(result);
            })
            .orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> updateDocument(@AuthenticationPrincipal UUID userId,
                                            @PathVariable UUID id,
                                            @Valid @RequestBody UpdateDocumentRequest request) {
        if (!permissionService.canEdit(id, userId)) {
            return ResponseEntity.status(403).body(Map.of("error", "Forbidden"));
        }
        return documentService.updateTitle(id, request.title())
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteDocument(@AuthenticationPrincipal UUID userId, @PathVariable UUID id) {
        if (documentService.deleteDocument(id, userId)) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.notFound().build();
    }
}
