package com.collabdoc.permission;

import com.collabdoc.auth.UserRepository;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/docs/{docId}/permissions")
public class PermissionController {

    private final DocumentPermissionRepository permissionRepository;
    private final PermissionService permissionService;
    private final UserRepository userRepository;

    public PermissionController(DocumentPermissionRepository permissionRepository,
                                PermissionService permissionService,
                                UserRepository userRepository) {
        this.permissionRepository = permissionRepository;
        this.permissionService = permissionService;
        this.userRepository = userRepository;
    }

    @GetMapping
    public ResponseEntity<?> listPermissions(@AuthenticationPrincipal UUID userId,
                                             @PathVariable UUID docId) {
        if (!permissionService.isOwner(docId, userId)) {
            return ResponseEntity.status(403).body(Map.of("error", "Forbidden"));
        }
        List<PermissionResponse> responses = permissionRepository.findByDocumentId(docId).stream()
            .map(dp -> {
                String username = userRepository.findById(dp.getUserId())
                    .map(u -> u.getUsername())
                    .orElse("unknown");
                return new PermissionResponse(dp.getId(), dp.getUserId(), username, dp.getPermission());
            })
            .toList();
        return ResponseEntity.ok(responses);
    }

    @PostMapping
    public ResponseEntity<?> addPermission(@AuthenticationPrincipal UUID userId,
                                           @PathVariable UUID docId,
                                           @Valid @RequestBody CreatePermissionRequest request) {
        if (!permissionService.isOwner(docId, userId)) {
            return ResponseEntity.status(403).body(Map.of("error", "Forbidden"));
        }
        var targetUser = userRepository.findByUsername(request.username());
        if (targetUser.isEmpty()) {
            return ResponseEntity.status(404).body(Map.of("error", "User not found"));
        }
        UUID targetUserId = targetUser.get().getId();
        if (targetUserId.equals(userId)) {
            return ResponseEntity.status(400).body(Map.of("error", "Cannot add permission for yourself"));
        }
        var existing = permissionRepository.findByDocumentIdAndUserId(docId, targetUserId);
        if (existing.isPresent()) {
            return ResponseEntity.status(409).body(Map.of("error", "Permission already exists for this user"));
        }
        var dp = new DocumentPermission(docId, targetUserId, request.permission());
        permissionRepository.save(dp);
        return ResponseEntity.status(HttpStatus.CREATED).body(new PermissionResponse(dp.getId(), targetUserId, request.username(), dp.getPermission()));
    }

    @PutMapping("/{permissionId}")
    public ResponseEntity<?> updatePermission(@AuthenticationPrincipal UUID userId,
                                              @PathVariable UUID docId,
                                              @PathVariable UUID permissionId,
                                              @Valid @RequestBody UpdatePermissionRequest request) {
        if (!permissionService.isOwner(docId, userId)) {
            return ResponseEntity.status(403).body(Map.of("error", "Forbidden"));
        }
        return permissionRepository.findById(permissionId)
            .map(dp -> {
                dp.setPermission(request.permission());
                permissionRepository.save(dp);
                String username = userRepository.findById(dp.getUserId())
                    .map(u -> u.getUsername())
                    .orElse("unknown");
                return ResponseEntity.ok((Object) new PermissionResponse(dp.getId(), dp.getUserId(), username, dp.getPermission()));
            })
            .orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{permissionId}")
    public ResponseEntity<?> revokePermission(@AuthenticationPrincipal UUID userId,
                                              @PathVariable UUID docId,
                                              @PathVariable UUID permissionId) {
        if (!permissionService.isOwner(docId, userId)) {
            return ResponseEntity.status(403).body(Map.of("error", "Forbidden"));
        }
        return permissionRepository.findById(permissionId)
            .map(dp -> {
                permissionRepository.delete(dp);
                return ResponseEntity.noContent().<Void>build();
            })
            .orElse(ResponseEntity.notFound().build());
    }
}
