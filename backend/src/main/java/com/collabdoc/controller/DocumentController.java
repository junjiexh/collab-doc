package com.collabdoc.controller;

import com.collabdoc.model.Document;
import com.collabdoc.service.DocumentService;
import org.springframework.http.ResponseEntity;
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
    public List<Document> listDocuments() {
        return documentService.listDocuments();
    }

    @GetMapping("/tree")
    public List<Document> listDocumentsForTree() {
        return documentService.listDocumentsForTree();
    }

    @PostMapping
    public Document createDocument(@RequestBody Map<String, String> body) {
        String title = body.getOrDefault("title", "Untitled");
        String parentIdStr = body.get("parentId");
        UUID parentId = parentIdStr != null ? UUID.fromString(parentIdStr) : null;
        return documentService.createDocument(title, parentId);
    }

    @PutMapping("/{id}/move")
    public ResponseEntity<Document> moveDocument(@PathVariable UUID id, @RequestBody Map<String, Object> body) {
        String parentIdStr = (String) body.get("parentId");
        UUID parentId = parentIdStr != null ? UUID.fromString(parentIdStr) : null;
        int sortOrder = (int) body.get("sortOrder");
        return documentService.moveDocument(id, parentId, sortOrder)
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/{id}")
    public ResponseEntity<Document> getDocument(@PathVariable UUID id) {
        return documentService.getDocument(id)
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/{id}")
    public ResponseEntity<Document> updateDocument(@PathVariable UUID id, @RequestBody Map<String, String> body) {
        String title = body.get("title");
        return documentService.updateTitle(id, title)
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteDocument(@PathVariable UUID id) {
        documentService.deleteDocument(id);
        return ResponseEntity.noContent().build();
    }
}
