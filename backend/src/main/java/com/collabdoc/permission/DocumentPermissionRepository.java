package com.collabdoc.permission;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DocumentPermissionRepository extends JpaRepository<DocumentPermission, UUID> {

    List<DocumentPermission> findByDocumentId(UUID documentId);

    Optional<DocumentPermission> findByDocumentIdAndUserId(UUID documentId, UUID userId);

    @Query(value = """
        WITH RECURSIVE doc_chain AS (
            SELECT id, parent_id FROM documents WHERE id = :docId
            UNION ALL
            SELECT d.id, d.parent_id
            FROM documents d JOIN doc_chain dc ON d.id = dc.parent_id
        )
        SELECT dp.permission
        FROM doc_chain dc
        JOIN document_permissions dp ON dp.document_id = dc.id AND dp.user_id = :userId
        LIMIT 1
        """, nativeQuery = true)
    Optional<String> resolvePermission(UUID docId, UUID userId);

    List<DocumentPermission> findByUserId(UUID userId);
}
