package com.collabdoc.controller;

import com.collabdoc.model.Document;
import com.collabdoc.service.DocumentService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/docs")
public class DocumentController {

    private final DocumentService documentService;

    public DocumentController(DocumentService documentService) {
        this.documentService = documentService;
    }

    @GetMapping
    public List<Document> listDocuments(@AuthenticationPrincipal UUID userId) {
        return documentService.listDocumentsForTree(userId);
    }

    @GetMapping("/tree")
    public List<Document> listDocumentsForTree(@AuthenticationPrincipal UUID userId) {
        return documentService.listDocumentsForTree(userId);
    }

    @PostMapping
    public Document createDocument(@AuthenticationPrincipal UUID userId, @RequestBody Map<String, String> body) {
        String title = body.getOrDefault("title", "Untitled");
        String parentIdStr = body.get("parentId");
        UUID parentId = parentIdStr != null ? UUID.fromString(parentIdStr) : null;
        return documentService.createDocument(title, parentId, userId);
    }

    @PutMapping("/{id}/move")
    public ResponseEntity<Document> moveDocument(@AuthenticationPrincipal UUID userId,
                                                  @PathVariable UUID id, @RequestBody Map<String, Object> body) {
        String parentIdStr = (String) body.get("parentId");
        UUID parentId = parentIdStr != null ? UUID.fromString(parentIdStr) : null;
        int sortOrder = (int) body.get("sortOrder");
        return documentService.moveDocument(id, parentId, sortOrder, userId)
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/{id}")
    public ResponseEntity<Document> getDocument(@AuthenticationPrincipal UUID userId, @PathVariable UUID id) {
        return documentService.getDocument(id, userId)
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/{id}")
    public ResponseEntity<Document> updateDocument(@AuthenticationPrincipal UUID userId,
                                                    @PathVariable UUID id, @RequestBody Map<String, String> body) {
        String title = body.get("title");
        return documentService.updateTitle(id, title, userId)
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
