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
        return documentRepository.save(new Document(title));
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
