package com.collabdoc.collab;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface DocumentSnapshotRepository extends JpaRepository<DocumentSnapshot, UUID> {
}
