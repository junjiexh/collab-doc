package com.collabdoc.service;

import com.collabdoc.model.Document;
import com.collabdoc.repository.DocumentRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class DocumentService {

    private final DocumentRepository documentRepository;

    public DocumentService(DocumentRepository documentRepository) {
        this.documentRepository = documentRepository;
    }

    public Document createDocument(String title, UUID parentId, UUID ownerId) {
        int maxSort;
        if (parentId == null) {
            maxSort = documentRepository.findMaxSortOrderByOwnerIdForRoot(ownerId);
        } else {
            maxSort = documentRepository.findMaxSortOrderByOwnerIdAndParentId(ownerId, parentId);
        }
        Document doc = new Document(title, parentId);
        doc.setOwnerId(ownerId);
        doc.setSortOrder(maxSort + 1);
        return documentRepository.save(doc);
    }

    public List<Document> listDocumentsForTree(UUID ownerId) {
        return documentRepository.findByOwnerIdOrderBySortOrderAsc(ownerId);
    }

    public Optional<Document> moveDocument(UUID id, UUID newParentId, int targetIndex, UUID ownerId) {
        return documentRepository.findById(id)
                .filter(doc -> ownerId.equals(doc.getOwnerId()))
                .map(doc -> {
                    List<Document> siblings;
                    if (newParentId == null) {
                        siblings = documentRepository.findByOwnerIdAndParentIdIsNullOrderBySortOrderAsc(ownerId);
                    } else {
                        siblings = documentRepository.findByOwnerIdAndParentIdOrderBySortOrderAsc(ownerId, newParentId);
                    }
                    siblings.removeIf(d -> d.getId().equals(id));
                    int insertAt = Math.max(0, Math.min(targetIndex, siblings.size()));
                    siblings.add(insertAt, doc);
                    for (int i = 0; i < siblings.size(); i++) {
                        siblings.get(i).setSortOrder(i);
                    }
                    doc.setParentId(newParentId);
                    doc.setUpdatedAt(Instant.now());
                    documentRepository.saveAll(siblings);
                    return doc;
                });
    }

    public Optional<Document> getDocument(UUID id, UUID ownerId) {
        return documentRepository.findById(id)
                .filter(doc -> ownerId.equals(doc.getOwnerId()));
    }

    public Optional<Document> updateTitle(UUID id, String title, UUID ownerId) {
        return documentRepository.findById(id)
                .filter(doc -> ownerId.equals(doc.getOwnerId()))
                .map(doc -> {
                    doc.setTitle(title);
                    doc.setUpdatedAt(Instant.now());
                    return documentRepository.save(doc);
                });
    }

    public boolean deleteDocument(UUID id, UUID ownerId) {
        return documentRepository.findById(id)
                .filter(doc -> ownerId.equals(doc.getOwnerId()))
                .map(doc -> {
                    documentRepository.deleteById(id);
                    return true;
                })
                .orElse(false);
    }

    public boolean isOwner(UUID docId, UUID ownerId) {
        return documentRepository.findById(docId)
                .map(doc -> ownerId.equals(doc.getOwnerId()))
                .orElse(false);
    }
}
