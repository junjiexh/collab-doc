package com.collabdoc.collab;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "document_updates")
public class DocumentUpdate {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "doc_id")
    private UUID docId;

    @Column(name = "update_data")
    private byte[] updateData;

    @Column(name = "created_at")
    private Instant createdAt;

    protected DocumentUpdate() {}

    public DocumentUpdate(UUID docId, byte[] updateData) {
        this.docId = docId;
        this.updateData = updateData;
        this.createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public UUID getDocId() { return docId; }
    public byte[] getUpdateData() { return updateData; }
    public Instant getCreatedAt() { return createdAt; }
}
