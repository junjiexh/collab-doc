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

    public Document createDocument(String title) {
        return createDocument(title, null);
    }

    public Document createDocument(String title, UUID parentId) {
        int maxSort;
        if (parentId == null) {
            maxSort = documentRepository.findMaxSortOrderForRoot();
        } else {
            maxSort = documentRepository.findMaxSortOrderByParentId(parentId);
        }
        Document doc = new Document(title, parentId);
        doc.setSortOrder(maxSort + 1);
        return documentRepository.save(doc);
    }

    public List<Document> listDocumentsForTree() {
        return documentRepository.findAllByOrderBySortOrderAsc();
    }

    public Optional<Document> moveDocument(UUID id, UUID newParentId, int targetIndex) {
        return documentRepository.findById(id).map(doc -> {
            // Get siblings at the destination (excluding the moved doc)
            List<Document> siblings;
            if (newParentId == null) {
                siblings = documentRepository.findByParentIdIsNullOrderBySortOrderAsc();
            } else {
                siblings = documentRepository.findByParentIdOrderBySortOrderAsc(newParentId);
            }
            siblings.removeIf(d -> d.getId().equals(id));

            // Clamp target index
            int insertAt = Math.max(0, Math.min(targetIndex, siblings.size()));

            // Insert the moved doc at the target position
            siblings.add(insertAt, doc);

            // Re-number all siblings sequentially
            for (int i = 0; i < siblings.size(); i++) {
                siblings.get(i).setSortOrder(i);
            }

            doc.setParentId(newParentId);
            doc.setUpdatedAt(Instant.now());

            documentRepository.saveAll(siblings);
            return doc;
        });
    }

    public Optional<Document> getDocument(UUID id) {
        return documentRepository.findById(id);
    }

    public List<Document> listDocuments() {
        return documentRepository.findAll();
    }

    public Optional<Document> updateTitle(UUID id, String title) {
        return documentRepository.findById(id).map(doc -> {
            doc.setTitle(title);
            doc.setUpdatedAt(Instant.now());
            return documentRepository.save(doc);
        });
    }

    public void deleteDocument(UUID id) {
        documentRepository.deleteById(id);
    }
}
