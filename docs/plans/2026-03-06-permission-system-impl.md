# Permission System Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Add user-based document sharing with Viewer/Editor permissions, runtime inheritance via parent chain, and real-time collaboration support for shared users.

**Architecture:** New `permission` domain package with entity, repository, service, controller. Modify existing document/collab code to check permissions via `PermissionService.resolvePermission()`. Frontend adds "Shared with me" sidebar section and read-only editor mode for Viewers.

**Tech Stack:** Java 25 + Spring Boot 3.5.11, PostgreSQL (WITH RECURSIVE CTE), React 19, BlockNote editor, Yjs WebSocket

---

### Task 1: Database Migration

**Files:**
- Create: `backend/src/main/resources/db/migration/V4__add_document_permissions.sql`

**Step 1: Create Flyway migration**

```sql
-- V4__add_document_permissions.sql
CREATE TABLE document_permissions (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    document_id UUID NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
    user_id     UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    permission  VARCHAR(10) NOT NULL CHECK (permission IN ('VIEWER', 'EDITOR')),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),

    UNIQUE (document_id, user_id)
);

CREATE INDEX idx_doc_perms_document ON document_permissions(document_id);
CREATE INDEX idx_doc_perms_user ON document_permissions(user_id);
```

**Step 2: Verify migration applies**

Run: `cd backend && ./gradlew bootRun` (or let tests trigger Flyway)
Expected: Table `document_permissions` created successfully.

**Step 3: Commit**

```bash
git add backend/src/main/resources/db/migration/V4__add_document_permissions.sql
git commit -m "feat: add document_permissions table migration (V4)"
```

---

### Task 2: Permission Entity, Enum, and Repository

**Files:**
- Create: `backend/src/main/java/com/collabdoc/permission/Permission.java` (enum)
- Create: `backend/src/main/java/com/collabdoc/permission/DocumentPermission.java` (entity)
- Create: `backend/src/main/java/com/collabdoc/permission/DocumentPermissionRepository.java`

**Step 1: Create the Permission enum**

```java
package com.collabdoc.permission;

public enum Permission {
    VIEWER, EDITOR
}
```

**Step 2: Create the DocumentPermission entity**

```java
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
```

**Step 3: Create the repository with recursive permission query**

```java
package com.collabdoc.permission;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DocumentPermissionRepository extends JpaRepository<DocumentPermission, UUID> {

    List<DocumentPermission> findByDocumentId(UUID documentId);

    Optional<DocumentPermission> findByDocumentIdAndUserId(UUID documentId, UUID userId);

    /**
     * Walk up the document parent chain and find the first permission for the given user.
     * Returns the permission string ('VIEWER' or 'EDITOR') or empty if no access.
     */
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

    /** Find all documents explicitly shared with a user (for "Shared with me"). */
    List<DocumentPermission> findByUserId(UUID userId);
}
```

**Step 4: Verify compilation**

Run: `cd backend && ./gradlew compileJava`
Expected: BUILD SUCCESSFUL

**Step 5: Commit**

```bash
git add backend/src/main/java/com/collabdoc/permission/
git commit -m "feat: add Permission enum, DocumentPermission entity, and repository"
```

---

### Task 3: PermissionService — Core Permission Logic

**Files:**
- Create: `backend/src/main/java/com/collabdoc/permission/PermissionService.java`

**Step 1: Create PermissionService**

This service wraps permission resolution and provides helper methods used throughout the app.

```java
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

    /**
     * Resolve effective permission for a user on a document.
     * Returns "OWNER", "EDITOR", "VIEWER", or null (no access).
     */
    public String resolvePermission(UUID docId, UUID userId) {
        // Check ownership first
        return documentRepository.findById(docId)
            .map(doc -> {
                if (userId.equals(doc.getOwnerId())) {
                    return "OWNER";
                }
                // Walk parent chain for inherited permissions
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
```

**Step 2: Verify compilation**

Run: `cd backend && ./gradlew compileJava`
Expected: BUILD SUCCESSFUL

**Step 3: Commit**

```bash
git add backend/src/main/java/com/collabdoc/permission/PermissionService.java
git commit -m "feat: add PermissionService with recursive permission resolution"
```

---

### Task 4: Permission DTOs and Controller

**Files:**
- Create: `backend/src/main/java/com/collabdoc/permission/CreatePermissionRequest.java`
- Create: `backend/src/main/java/com/collabdoc/permission/UpdatePermissionRequest.java`
- Create: `backend/src/main/java/com/collabdoc/permission/PermissionResponse.java`
- Create: `backend/src/main/java/com/collabdoc/permission/PermissionController.java`

**Step 1: Create DTOs**

```java
// CreatePermissionRequest.java
package com.collabdoc.permission;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreatePermissionRequest(
    @NotBlank @Size(max = 50) String username,
    @NotNull Permission permission
) {}
```

```java
// UpdatePermissionRequest.java
package com.collabdoc.permission;

import jakarta.validation.constraints.NotNull;

public record UpdatePermissionRequest(
    @NotNull Permission permission
) {}
```

```java
// PermissionResponse.java
package com.collabdoc.permission;

import java.util.UUID;

public record PermissionResponse(
    UUID id,
    UUID userId,
    String username,
    Permission permission
) {}
```

**Step 2: Create PermissionController**

```java
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
                    .map(u -> u.getUsername()).orElse("unknown");
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
            return ResponseEntity.badRequest().body(Map.of("error", "Cannot share with yourself"));
        }

        if (permissionRepository.findByDocumentIdAndUserId(docId, targetUserId).isPresent()) {
            return ResponseEntity.status(409).body(Map.of("error", "Permission already exists for this user"));
        }

        var dp = new DocumentPermission(docId, targetUserId, request.permission());
        permissionRepository.save(dp);

        var response = new PermissionResponse(dp.getId(), targetUserId, request.username(), dp.getPermission());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
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
            .filter(dp -> dp.getDocumentId().equals(docId))
            .map(dp -> {
                dp.setPermission(request.permission());
                permissionRepository.save(dp);
                String username = userRepository.findById(dp.getUserId())
                    .map(u -> u.getUsername()).orElse("unknown");
                return ResponseEntity.ok(new PermissionResponse(dp.getId(), dp.getUserId(), username, dp.getPermission()));
            })
            .orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{permissionId}")
    public ResponseEntity<?> deletePermission(@AuthenticationPrincipal UUID userId,
                                              @PathVariable UUID docId,
                                              @PathVariable UUID permissionId) {
        if (!permissionService.isOwner(docId, userId)) {
            return ResponseEntity.status(403).body(Map.of("error", "Forbidden"));
        }

        return permissionRepository.findById(permissionId)
            .filter(dp -> dp.getDocumentId().equals(docId))
            .map(dp -> {
                permissionRepository.delete(dp);
                return ResponseEntity.noContent().<Void>build();
            })
            .orElse(ResponseEntity.notFound().build());
    }
}
```

**Step 3: Verify compilation**

Run: `cd backend && ./gradlew compileJava`
Expected: BUILD SUCCESSFUL

**Step 4: Commit**

```bash
git add backend/src/main/java/com/collabdoc/permission/
git commit -m "feat: add permission DTOs and PermissionController (CRUD)"
```

---

### Task 5: Shared-With-Me API and Document Permission Field

**Files:**
- Create: `backend/src/main/java/com/collabdoc/permission/SharedDocumentResponse.java`
- Modify: `backend/src/main/java/com/collabdoc/document/DocumentController.java`

**Step 1: Create SharedDocumentResponse**

```java
package com.collabdoc.permission;

import java.util.UUID;

public record SharedDocumentResponse(
    UUID id,
    String title,
    String permission
) {}
```

**Step 2: Add shared-with-me endpoint to DocumentController**

In `DocumentController.java`, add a new dependency on `PermissionService` and `DocumentPermissionRepository`, and add the endpoint. Also modify `getDocument()` to allow shared users and return permission info.

Modify the constructor to inject `PermissionService`:

```java
private final DocumentService documentService;
private final PermissionService permissionService;

public DocumentController(DocumentService documentService, PermissionService permissionService) {
    this.documentService = documentService;
    this.permissionService = permissionService;
}
```

Add the new endpoint **before** `@GetMapping("/{id}")` to avoid route conflicts:

```java
@GetMapping("/shared-with-me")
public ResponseEntity<?> sharedWithMe(@AuthenticationPrincipal UUID userId) {
    // PermissionService needs a method for this — we'll add it
    var shared = permissionService.getSharedDocuments(userId);
    return ResponseEntity.ok(shared);
}
```

Modify `getDocument()` to work for shared users and return permission:

```java
@GetMapping("/{id}")
public ResponseEntity<?> getDocument(@AuthenticationPrincipal UUID userId, @PathVariable UUID id) {
    String perm = permissionService.resolvePermission(id, userId);
    if (perm == null) {
        return ResponseEntity.status(403).body(Map.of("error", "Forbidden"));
    }
    return documentService.getDocumentById(id)
        .map(doc -> {
            var result = new java.util.HashMap<String, Object>();
            result.put("id", doc.getId());
            result.put("title", doc.getTitle());
            result.put("parentId", doc.getParentId());
            result.put("sortOrder", doc.getSortOrder());
            result.put("createdAt", doc.getCreatedAt());
            result.put("updatedAt", doc.getUpdatedAt());
            result.put("permission", perm);
            return ResponseEntity.ok(result);
        })
        .orElse(ResponseEntity.notFound().build());
}
```

Modify `updateDocument()` to require EDITOR:

```java
@PutMapping("/{id}")
public ResponseEntity<?> updateDocument(@AuthenticationPrincipal UUID userId,
                                        @PathVariable UUID id,
                                        @Valid @RequestBody UpdateDocumentRequest request) {
    if (!permissionService.canEdit(id, userId)) {
        return ResponseEntity.status(403).body(Map.of("error", "Forbidden"));
    }
    return documentService.updateTitle(id, request.title())
        .map(ResponseEntity::ok)
        .orElse(ResponseEntity.notFound().build());
}
```

**Step 3: Add helper methods to PermissionService and DocumentService**

Add to `PermissionService`:

```java
public List<SharedDocumentResponse> getSharedDocuments(UUID userId) {
    return permissionRepository.findByUserId(userId).stream()
        .map(dp -> documentRepository.findById(dp.getDocumentId())
            .map(doc -> new SharedDocumentResponse(doc.getId(), doc.getTitle(), dp.getPermission().name()))
            .orElse(null))
        .filter(Objects::nonNull)
        .toList();
}
```

Add to `DocumentService` a method that fetches by ID without ownership filter:

```java
public Optional<Document> getDocumentById(UUID id) {
    return documentRepository.findById(id);
}
```

Also update `DocumentService.updateTitle()` to remove the ownership filter (since the controller now handles permission checks):

```java
public Optional<Document> updateTitle(UUID id, String title) {
    return documentRepository.findById(id)
        .map(doc -> {
            doc.setTitle(title);
            doc.setUpdatedAt(Instant.now());
            return documentRepository.save(doc);
        });
}
```

Keep the old `updateTitle(id, title, ownerId)` method for backward compatibility — or remove it if nothing else calls it. Check callers before removing.

**Step 4: Verify compilation**

Run: `cd backend && ./gradlew compileJava`
Expected: BUILD SUCCESSFUL

**Step 5: Commit**

```bash
git add backend/src/main/java/com/collabdoc/permission/ backend/src/main/java/com/collabdoc/document/
git commit -m "feat: add shared-with-me endpoint and permission-aware document access"
```

---

### Task 6: Update BlockController Permission Checks

**Files:**
- Modify: `backend/src/main/java/com/collabdoc/collab/BlockController.java`

**Step 1: Replace ownership checks with permission checks**

Inject `PermissionService` instead of `DocumentService` for auth checks:

```java
private final YrsDocumentManager docManager;
private final YjsWebSocketHandler wsHandler;
private final PermissionService permissionService;
private final ObjectMapper objectMapper;

public BlockController(YrsDocumentManager docManager, YjsWebSocketHandler wsHandler,
                       PermissionService permissionService, ObjectMapper objectMapper) {
    this.docManager = docManager;
    this.wsHandler = wsHandler;
    this.permissionService = permissionService;
    this.objectMapper = objectMapper;
}
```

Update `getBlocks()`:
```java
if (!permissionService.canView(docId, userId)) {
    return ResponseEntity.status(403).body(Map.of("error", "Forbidden"));
}
```

Update `insertBlock()` and `deleteBlock()`:
```java
if (!permissionService.canEdit(docId, userId)) {
    return ResponseEntity.status(403).body(Map.of("error", "Forbidden"));
}
```

**Step 2: Verify compilation**

Run: `cd backend && ./gradlew compileJava`
Expected: BUILD SUCCESSFUL

**Step 3: Commit**

```bash
git add backend/src/main/java/com/collabdoc/collab/BlockController.java
git commit -m "feat: update BlockController to use PermissionService for access checks"
```

---

### Task 7: WebSocket Permission Control

**Files:**
- Modify: `backend/src/main/java/com/collabdoc/collab/YjsWebSocketHandler.java`
- Modify: `backend/src/main/java/com/collabdoc/config/WebSocketConfig.java`

**Step 1: Inject PermissionService into YjsWebSocketHandler**

Replace `DocumentService` with `PermissionService`:

```java
private final YrsDocumentManager docManager;
private final PermissionService permissionService;

public YjsWebSocketHandler(YrsDocumentManager docManager, PermissionService permissionService) {
    this.docManager = docManager;
    this.permissionService = permissionService;
}
```

**Step 2: Update `afterConnectionEstablished()` to check permission and store it**

Replace the ownership check block (lines 62-67) with:

```java
// Check permission (owner, editor, or viewer)
UUID userId = (UUID) session.getAttributes().get("userId");
if (userId == null) {
    try { session.close(); } catch (IOException ignored) {}
    return;
}

String permission = permissionService != null ? permissionService.resolvePermission(docId, userId) : null;
if (permission == null) {
    log.warn("Unauthorized WebSocket access: user={}, doc={}", userId, docId);
    try { session.close(); } catch (IOException ignored) {}
    return;
}

// Store permission in session attributes for message filtering
session.getAttributes().put("permission", permission);
```

**Step 3: Update `handleSyncMessage()` to filter writes from Viewers**

In the `MSG_SYNC_STEP2` / `MSG_SYNC_UPDATE` case, add a permission check:

```java
case YjsSyncProtocol.MSG_SYNC_STEP2, YjsSyncProtocol.MSG_SYNC_UPDATE -> {
    // Check if user can edit
    String perm = (String) session.getAttributes().get("permission");
    if (!"OWNER".equals(perm) && !"EDITOR".equals(perm)) {
        // Viewer: drop the update, don't apply or broadcast
        log.debug("Dropping update from viewer session={}", session.getId());
        return;
    }
    byte[] update = YjsSyncProtocol.readPayload(buf);
    byte[] applied = docManager.applyClientUpdate(docId, update);
    if (applied != null) {
        byte[] broadcastMsg = YjsSyncProtocol.encodeSyncUpdate(update);
        broadcastToOthers(docId, session, broadcastMsg);
    }
}
```

**Step 4: Update WebSocketConfig to pass PermissionService**

In `WebSocketConfig.java`, replace `DocumentService` with `PermissionService`:

```java
private final YrsDocumentManager docManager;
private final JwtUtil jwtUtil;
private final PermissionService permissionService;

public WebSocketConfig(YrsDocumentManager docManager, JwtUtil jwtUtil, PermissionService permissionService) {
    this.docManager = docManager;
    this.jwtUtil = jwtUtil;
    this.permissionService = permissionService;
}

@Bean
public YjsWebSocketHandler yjsWebSocketHandler() {
    return new YjsWebSocketHandler(docManager, permissionService);
}
```

**Step 5: Verify compilation**

Run: `cd backend && ./gradlew compileJava`
Expected: BUILD SUCCESSFUL

**Step 6: Commit**

```bash
git add backend/src/main/java/com/collabdoc/collab/YjsWebSocketHandler.java backend/src/main/java/com/collabdoc/config/WebSocketConfig.java
git commit -m "feat: add WebSocket permission control — viewers can connect but not edit"
```

---

### Task 8: Frontend — API Layer for Permissions

**Files:**
- Modify: `frontend/src/api.ts`

**Step 1: Add permission types and API functions**

Append to `api.ts`:

```typescript
// --- Permission API ---

export interface PermissionEntry {
  id: string;
  userId: string;
  username: string;
  permission: "VIEWER" | "EDITOR";
}

export interface SharedDocument {
  id: string;
  title: string;
  permission: string;
}

export async function listPermissions(docId: string): Promise<PermissionEntry[]> {
  const res = await fetch(`${API_BASE}/docs/${docId}/permissions`, { credentials: "include" });
  if (!res.ok) throw new Error("Failed to list permissions");
  return res.json();
}

export async function addPermission(docId: string, username: string, permission: "VIEWER" | "EDITOR"): Promise<PermissionEntry> {
  const res = await fetch(`${API_BASE}/docs/${docId}/permissions`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    credentials: "include",
    body: JSON.stringify({ username, permission }),
  });
  if (!res.ok) {
    const data = await res.json();
    throw new Error(data.error || "Failed to add permission");
  }
  return res.json();
}

export async function updatePermission(docId: string, permissionId: string, permission: "VIEWER" | "EDITOR"): Promise<PermissionEntry> {
  const res = await fetch(`${API_BASE}/docs/${docId}/permissions/${permissionId}`, {
    method: "PUT",
    headers: { "Content-Type": "application/json" },
    credentials: "include",
    body: JSON.stringify({ permission }),
  });
  if (!res.ok) throw new Error("Failed to update permission");
  return res.json();
}

export async function deletePermission(docId: string, permissionId: string): Promise<void> {
  await fetch(`${API_BASE}/docs/${docId}/permissions/${permissionId}`, {
    method: "DELETE",
    credentials: "include",
  });
}

export async function fetchSharedWithMe(): Promise<SharedDocument[]> {
  const res = await fetch(`${API_BASE}/docs/shared-with-me`, { credentials: "include" });
  if (!res.ok) throw new Error("Failed to fetch shared documents");
  return res.json();
}
```

Also update `DocumentMeta` to include optional permission field:

```typescript
export interface DocumentMeta {
  id: string;
  title: string;
  parentId: string | null;
  sortOrder: number;
  createdAt: string;
  updatedAt: string;
  permission?: string; // "OWNER" | "EDITOR" | "VIEWER"
}
```

**Step 2: Commit**

```bash
git add frontend/src/api.ts
git commit -m "feat: add permission API functions to frontend"
```

---

### Task 9: Frontend — Shared With Me Sidebar Section

**Files:**
- Create: `frontend/src/components/SharedDocuments.tsx`
- Modify: `frontend/src/components/Sidebar.tsx`
- Modify: `frontend/src/layouts/MainLayout.tsx`

**Step 1: Create SharedDocuments component**

```tsx
// SharedDocuments.tsx
import { useNavigate, useParams } from "react-router-dom";
import type { SharedDocument } from "../api";

interface SharedDocumentsProps {
  documents: SharedDocument[];
}

export default function SharedDocuments({ documents }: SharedDocumentsProps) {
  const navigate = useNavigate();
  const { docId } = useParams<{ docId: string }>();

  if (documents.length === 0) return null;

  return (
    <>
      <div
        style={{
          padding: "16px 12px 8px",
          fontWeight: 600,
          fontSize: 13,
          color: "#999",
          textTransform: "uppercase",
          letterSpacing: "0.05em",
        }}
      >
        Shared with me
      </div>
      <div style={{ padding: "0 4px" }}>
        {documents.map((doc) => (
          <div
            key={doc.id}
            onClick={() => navigate(`/doc/${doc.id}`)}
            style={{
              display: "flex",
              alignItems: "center",
              justifyContent: "space-between",
              padding: "4px 8px",
              cursor: "pointer",
              borderRadius: 4,
              backgroundColor: docId === doc.id ? "rgba(0,0,0,0.08)" : "transparent",
              fontSize: 14,
            }}
            onMouseEnter={(e) => {
              if (docId !== doc.id) e.currentTarget.style.backgroundColor = "rgba(0,0,0,0.04)";
            }}
            onMouseLeave={(e) => {
              if (docId !== doc.id) e.currentTarget.style.backgroundColor = "transparent";
            }}
          >
            <span style={{ overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap", flex: 1 }}>
              {doc.title || "Untitled"}
            </span>
            <span style={{ fontSize: 11, color: "#999", marginLeft: 8, flexShrink: 0 }}>
              {doc.permission === "EDITOR" ? "编辑权限" : "只读权限"}
            </span>
          </div>
        ))}
      </div>
    </>
  );
}
```

**Step 2: Update Sidebar to accept and render shared documents**

Add to `SidebarProps`:
```typescript
sharedDocuments: SharedDocument[];
```

Add `SharedDocuments` component after the document tree section (after the closing `</div>` of the tree scroll area), before the "+ New Page" button:

```tsx
import SharedDocuments from "./SharedDocuments";
import type { SharedDocument } from "../api";

// In the component body, after the tree section div:
<SharedDocuments documents={sharedDocuments} />
```

**Step 3: Update MainLayout to fetch and pass shared documents**

Add state and fetch:
```typescript
import { fetchSharedWithMe, type SharedDocument } from "../api";

const [sharedDocs, setSharedDocs] = useState<SharedDocument[]>([]);

// In refreshTree or as separate effect:
const refreshShared = useCallback(async () => {
  const docs = await fetchSharedWithMe();
  setSharedDocs(docs);
}, []);

useEffect(() => {
  refreshShared();
}, [refreshShared]);
```

Pass to Sidebar:
```tsx
<Sidebar
  tree={tree}
  sharedDocuments={sharedDocs}
  // ... existing props
/>
```

**Step 4: Verify it compiles**

Run: `cd frontend && npm run build`
Expected: Build succeeds

**Step 5: Commit**

```bash
git add frontend/src/components/SharedDocuments.tsx frontend/src/components/Sidebar.tsx frontend/src/layouts/MainLayout.tsx
git commit -m "feat: add Shared with me sidebar section"
```

---

### Task 10: Frontend — Editor Page Permission Awareness

**Files:**
- Modify: `frontend/src/pages/EditorPage.tsx`
- Modify: `frontend/src/components/CollaborativeEditor.tsx`

**Step 1: Fetch document with permission and pass to editor**

In `EditorPage.tsx`, fetch the document to get the `permission` field:

```typescript
import { useEffect, useState } from "react";

const [permission, setPermission] = useState<string | null>(null);

useEffect(() => {
  if (!docId) return;
  fetch(`/api/docs/${docId}`, { credentials: "include" })
    .then(res => res.json())
    .then(data => setPermission(data.permission ?? "OWNER"))
    .catch(() => setPermission(null));
}, [docId]);
```

Make title non-editable for viewers:
```tsx
<div
  ref={titleRef}
  contentEditable={permission === "OWNER" || permission === "EDITOR"}
  // ... rest unchanged
/>
```

Pass `editable` prop to editor:
```tsx
<CollaborativeEditor
  docId={docId}
  username={authUser!.username}
  userId={authUser!.id}
  editable={permission === "OWNER" || permission === "EDITOR"}
/>
```

**Step 2: Update CollaborativeEditor to accept `editable` prop**

Add `editable` to `CollaborativeEditorProps`:
```typescript
interface CollaborativeEditorProps {
  docId: string;
  username: string;
  userId: string;
  editable: boolean;
}
```

Pass to `BlockNoteEditor`:
```tsx
<BlockNoteEditor provider={provider} fragment={fragment} user={user} editable={editable} />
```

Update `BlockNoteEditor` to use `editable`:
```tsx
function BlockNoteEditor({
  provider,
  fragment,
  user,
  editable,
}: {
  provider: WebsocketProvider;
  fragment: Y.XmlFragment;
  user: { name: string; color: string };
  editable: boolean;
}) {
  const editor = useCreateBlockNote({
    collaboration: {
      provider,
      fragment,
      user,
    },
  });

  return (
    <div style={{ minHeight: "70vh" }}>
      <BlockNoteView editor={editor} editable={editable} />
    </div>
  );
}
```

**Step 3: Verify it compiles**

Run: `cd frontend && npm run build`
Expected: Build succeeds

**Step 4: Commit**

```bash
git add frontend/src/pages/EditorPage.tsx frontend/src/components/CollaborativeEditor.tsx
git commit -m "feat: freeze editor for viewers, pass permission to frontend components"
```

---

### Task 11: Frontend — Share Dialog (Owner Only)

**Files:**
- Create: `frontend/src/components/ShareDialog.tsx`
- Modify: `frontend/src/pages/EditorPage.tsx`

**Step 1: Create ShareDialog component**

This is a simple modal that lets the owner add/remove/update permissions. It shows when the user clicks a "Share" button (only visible to owners).

```tsx
// ShareDialog.tsx
import { useState, useEffect } from "react";
import {
  listPermissions,
  addPermission,
  updatePermission,
  deletePermission,
  type PermissionEntry,
} from "../api";

interface ShareDialogProps {
  docId: string;
  onClose: () => void;
}

export default function ShareDialog({ docId, onClose }: ShareDialogProps) {
  const [permissions, setPermissions] = useState<PermissionEntry[]>([]);
  const [username, setUsername] = useState("");
  const [permission, setPermission] = useState<"VIEWER" | "EDITOR">("VIEWER");
  const [error, setError] = useState("");

  const refresh = async () => {
    const perms = await listPermissions(docId);
    setPermissions(perms);
  };

  useEffect(() => { refresh(); }, [docId]);

  const handleAdd = async () => {
    setError("");
    try {
      await addPermission(docId, username.trim(), permission);
      setUsername("");
      await refresh();
    } catch (e: any) {
      setError(e.message);
    }
  };

  const handleUpdate = async (permId: string, newPerm: "VIEWER" | "EDITOR") => {
    await updatePermission(docId, permId, newPerm);
    await refresh();
  };

  const handleDelete = async (permId: string) => {
    await deletePermission(docId, permId);
    await refresh();
  };

  return (
    <div style={{
      position: "fixed", top: 0, left: 0, right: 0, bottom: 0,
      backgroundColor: "rgba(0,0,0,0.3)", display: "flex", alignItems: "center", justifyContent: "center", zIndex: 1000,
    }} onClick={onClose}>
      <div style={{
        background: "#fff", borderRadius: 8, padding: 24, minWidth: 400, maxWidth: 500,
        boxShadow: "0 4px 24px rgba(0,0,0,0.15)",
      }} onClick={(e) => e.stopPropagation()}>
        <h3 style={{ margin: "0 0 16px", fontSize: 18 }}>Share Document</h3>

        <div style={{ display: "flex", gap: 8, marginBottom: 16 }}>
          <input
            value={username}
            onChange={(e) => setUsername(e.target.value)}
            placeholder="Username"
            style={{ flex: 1, padding: "6px 10px", border: "1px solid #ddd", borderRadius: 4, fontSize: 14 }}
            onKeyDown={(e) => e.key === "Enter" && handleAdd()}
          />
          <select
            value={permission}
            onChange={(e) => setPermission(e.target.value as "VIEWER" | "EDITOR")}
            style={{ padding: "6px 10px", border: "1px solid #ddd", borderRadius: 4, fontSize: 14 }}
          >
            <option value="VIEWER">只读权限</option>
            <option value="EDITOR">编辑权限</option>
          </select>
          <button onClick={handleAdd} style={{
            padding: "6px 16px", backgroundColor: "#2383e2", color: "#fff",
            border: "none", borderRadius: 4, cursor: "pointer", fontSize: 14,
          }}>
            Add
          </button>
        </div>

        {error && <div style={{ color: "#e53e3e", fontSize: 13, marginBottom: 12 }}>{error}</div>}

        {permissions.length === 0 ? (
          <div style={{ color: "#999", fontSize: 13 }}>No one has access yet.</div>
        ) : (
          <div style={{ display: "flex", flexDirection: "column", gap: 8 }}>
            {permissions.map((p) => (
              <div key={p.id} style={{ display: "flex", alignItems: "center", justifyContent: "space-between", padding: "6px 0" }}>
                <span style={{ fontSize: 14 }}>{p.username}</span>
                <div style={{ display: "flex", alignItems: "center", gap: 8 }}>
                  <select
                    value={p.permission}
                    onChange={(e) => handleUpdate(p.id, e.target.value as "VIEWER" | "EDITOR")}
                    style={{ padding: "4px 8px", border: "1px solid #ddd", borderRadius: 4, fontSize: 13 }}
                  >
                    <option value="VIEWER">只读权限</option>
                    <option value="EDITOR">编辑权限</option>
                  </select>
                  <button onClick={() => handleDelete(p.id)} style={{
                    background: "none", border: "none", color: "#e53e3e", cursor: "pointer", fontSize: 13,
                  }}>
                    Remove
                  </button>
                </div>
              </div>
            ))}
          </div>
        )}

        <div style={{ marginTop: 16, textAlign: "right" }}>
          <button onClick={onClose} style={{
            padding: "6px 16px", background: "none", border: "1px solid #ddd",
            borderRadius: 4, cursor: "pointer", fontSize: 14,
          }}>
            Close
          </button>
        </div>
      </div>
    </div>
  );
}
```

**Step 2: Add Share button to EditorPage**

In `EditorPage.tsx`, add a "Share" button visible only to owners:

```tsx
import ShareDialog from "../components/ShareDialog";

const [showShare, setShowShare] = useState(false);

// In the JSX, above the title:
{permission === "OWNER" && (
  <div style={{ display: "flex", justifyContent: "flex-end", marginBottom: 8 }}>
    <button
      onClick={() => setShowShare(true)}
      style={{
        padding: "4px 12px", backgroundColor: "#2383e2", color: "#fff",
        border: "none", borderRadius: 4, cursor: "pointer", fontSize: 13,
      }}
    >
      Share
    </button>
  </div>
)}

{showShare && docId && (
  <ShareDialog docId={docId} onClose={() => setShowShare(false)} />
)}
```

**Step 3: Verify it compiles**

Run: `cd frontend && npm run build`
Expected: Build succeeds

**Step 4: Commit**

```bash
git add frontend/src/components/ShareDialog.tsx frontend/src/pages/EditorPage.tsx
git commit -m "feat: add ShareDialog for document owner to manage permissions"
```

---

### Task 12: End-to-End Manual Testing

**Step 1: Start the application**

```bash
cd backend && ./gradlew bootRun &
cd frontend && npm run dev &
```

**Step 2: Test permission CRUD**

1. Register two users: `alice` and `bob`
2. As `alice`, create a document with a child document
3. As `alice`, click "Share" and share the parent document with `bob` as VIEWER
4. As `bob`, check "Shared with me" — parent document should appear with "只读权限"
5. As `bob`, open the shared document — editor should be frozen (can select text, cannot type)
6. As `bob`, navigate to child document via URL — should also be accessible (inherited permission)
7. As `alice`, update permission to EDITOR
8. As `bob`, refresh — editor should now be editable
9. As `alice`, revoke permission
10. As `bob`, refresh — document should no longer be accessible

**Step 3: Test WebSocket**

1. As `alice`, create a document and share with `bob` as VIEWER
2. Both open the same document
3. As `alice`, type some text
4. As `bob`, verify real-time updates appear
5. As `bob`, try to type — nothing should happen (editor frozen + backend drops updates)

**Step 4: Test error cases**

1. Try to share with non-existent username → "User not found"
2. Try to share with yourself → "Cannot share with yourself"
3. Try to share with same user twice → "Permission already exists"

---

### Task 13: Final Cleanup and Commit

**Step 1: Remove old `isOwner` usage in DocumentService if now unused**

Check all callers of `DocumentService.isOwner()`. If only `YjsWebSocketHandler` used it (and we replaced that), it can be removed. Keep it if other code still references it.

Similarly, check `DocumentService.getDocument(id, ownerId)` — if replaced by `getDocumentById(id)`, remove the old method.

**Step 2: Final compilation check**

Run: `cd backend && ./gradlew compileJava && cd ../frontend && npm run build`
Expected: Both succeed

**Step 3: Final commit**

```bash
git add -A
git commit -m "refactor: clean up unused ownership methods after permission system migration"
```
