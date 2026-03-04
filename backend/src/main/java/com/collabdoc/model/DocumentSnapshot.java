package com.collabdoc.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "document_snapshots")
public class DocumentSnapshot {
    @Id
    @Column(name = "doc_id")
    private UUID docId;

    @Column(name = "state_data")
    private byte[] stateData;

    @Column(name = "updated_at")
    private Instant updatedAt;

    protected DocumentSnapshot() {}

    public DocumentSnapshot(UUID docId, byte[] stateData) {
        this.docId = docId;
        this.stateData = stateData;
        this.updatedAt = Instant.now();
    }

    public UUID getDocId() { return docId; }
    public byte[] getStateData() { return stateData; }
    public void setStateData(byte[] stateData) {
        this.stateData = stateData;
        this.updatedAt = Instant.now();
    }
    public Instant getUpdatedAt() { return updatedAt; }
}
