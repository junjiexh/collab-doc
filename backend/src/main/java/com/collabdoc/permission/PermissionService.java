package com.collabdoc.permission;

import com.collabdoc.document.DocumentRepository;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;

@Service
public class PermissionService {

    private final DocumentPermissionRepository permissionRepository;
    private final DocumentRepository documentRepository;

    public PermissionService(DocumentPermissionRepository permissionRepository,
                             DocumentRepository documentRepository) {
        this.permissionRepository = permissionRepository;
        this.documentRepository = documentRepository;
    }

    public String resolvePermission(UUID docId, UUID userId) {
        return documentRepository.findById(docId)
            .map(doc -> {
                if (userId.equals(doc.getOwnerId())) {
                    return "OWNER";
                }
                return permissionRepository.resolvePermission(docId, userId)
                    .orElse(null);
            })
            .orElse(null);
    }

    public boolean canView(UUID docId, UUID userId) {
        return resolvePermission(docId, userId) != null;
    }

    public boolean canEdit(UUID docId, UUID userId) {
        String perm = resolvePermission(docId, userId);
        return "OWNER".equals(perm) || "EDITOR".equals(perm);
    }

    public boolean isOwner(UUID docId, UUID userId) {
        return "OWNER".equals(resolvePermission(docId, userId));
    }
}
