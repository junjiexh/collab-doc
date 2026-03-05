package com.collabdoc.document;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.util.List;
import java.util.UUID;

public interface DocumentRepository extends JpaRepository<Document, UUID> {

    List<Document> findAllByOrderBySortOrderAsc();

    List<Document> findByParentIdOrderBySortOrderAsc(UUID parentId);

    List<Document> findByParentIdIsNullOrderBySortOrderAsc();

    @Query("SELECT COALESCE(MAX(d.sortOrder), -1) FROM Document d WHERE d.parentId = :parentId")
    int findMaxSortOrderByParentId(UUID parentId);

    @Query("SELECT COALESCE(MAX(d.sortOrder), -1) FROM Document d WHERE d.parentId IS NULL")
    int findMaxSortOrderForRoot();

    List<Document> findByOwnerIdAndParentIdIsNullOrderBySortOrderAsc(UUID ownerId);
    List<Document> findByOwnerIdAndParentIdOrderBySortOrderAsc(UUID ownerId, UUID parentId);
    List<Document> findByOwnerIdOrderBySortOrderAsc(UUID ownerId);

    @Query("SELECT COALESCE(MAX(d.sortOrder), -1) FROM Document d WHERE d.ownerId = :ownerId AND d.parentId = :parentId")
    int findMaxSortOrderByOwnerIdAndParentId(UUID ownerId, UUID parentId);

    @Query("SELECT COALESCE(MAX(d.sortOrder), -1) FROM Document d WHERE d.ownerId = :ownerId AND d.parentId IS NULL")
    int findMaxSortOrderByOwnerIdForRoot(UUID ownerId);
}
