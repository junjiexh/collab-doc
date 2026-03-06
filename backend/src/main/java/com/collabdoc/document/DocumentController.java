package com.collabdoc.document;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
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
    public ResponseEntity<Document> getDocument(@AuthenticationPrincipal UUID userId, @PathVariable UUID id) {
        return documentService.getDocument(id, userId)
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/{id}")
    public ResponseEntity<Document> updateDocument(@AuthenticationPrincipal UUID userId,
                                                    @PathVariable UUID id,
                                                    @Valid @RequestBody UpdateDocumentRequest request) {
        return documentService.updateTitle(id, request.title(), userId)
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
