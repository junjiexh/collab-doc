package com.collabdoc.collab;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.UUID;

public interface DocumentUpdateRepository extends JpaRepository<DocumentUpdate, Long> {
    List<DocumentUpdate> findByDocIdOrderByIdAsc(UUID docId);
    void deleteByDocId(UUID docId);

    @Query("SELECT MAX(u.id) FROM DocumentUpdate u WHERE u.docId = :docId")
    Long findMaxIdByDocId(@Param("docId") UUID docId);

    void deleteByDocIdAndIdLessThanEqual(UUID docId, Long id);
}
