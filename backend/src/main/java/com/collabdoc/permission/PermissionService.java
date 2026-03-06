package com.collabdoc.permission;

import com.collabdoc.document.DocumentRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;
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

    public List<SharedDocumentResponse> getSharedDocuments(UUID userId) {
        List<DocumentPermission> directPerms = permissionRepository.findByUserId(userId);
        List<SharedDocumentResponse> result = new java.util.ArrayList<>();

        for (DocumentPermission dp : directPerms) {
            documentRepository.findById(dp.getDocumentId()).ifPresent(doc -> {
                result.add(new SharedDocumentResponse(doc.getId(), doc.getTitle(), dp.getPermission().name()));
                // Add children recursively
                addChildDocuments(doc.getId(), dp.getPermission().name(), result);
            });
        }

        return result;
    }

    private void addChildDocuments(UUID parentId, String permission, List<SharedDocumentResponse> result) {
        List<com.collabdoc.document.Document> children = documentRepository.findByParentIdOrderBySortOrderAsc(parentId);
        for (var child : children) {
            result.add(new SharedDocumentResponse(child.getId(), child.getTitle(), permission));
            addChildDocuments(child.getId(), permission, result);
        }
    }
}
