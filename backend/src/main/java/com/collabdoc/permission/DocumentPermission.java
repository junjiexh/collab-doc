package com.collabdoc.permission;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "document_permissions")
public class DocumentPermission {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "document_id", nullable = false)
    private UUID documentId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Permission permission;

    @Column(name = "created_at")
    private Instant createdAt;

    protected DocumentPermission() {}

    public DocumentPermission(UUID documentId, UUID userId, Permission permission) {
        this.documentId = documentId;
        this.userId = userId;
        this.permission = permission;
        this.createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getDocumentId() { return documentId; }
    public UUID getUserId() { return userId; }
    public Permission getPermission() { return permission; }
    public void setPermission(Permission permission) { this.permission = permission; }
    public Instant getCreatedAt() { return createdAt; }
}
