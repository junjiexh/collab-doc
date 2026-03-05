package com.collabdoc.collab;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface DocumentUpdateRepository extends JpaRepository<DocumentUpdate, Long> {
    List<DocumentUpdate> findByDocIdOrderByIdAsc(UUID docId);
    void deleteByDocId(UUID docId);
}
