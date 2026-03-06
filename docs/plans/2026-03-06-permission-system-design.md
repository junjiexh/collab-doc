# Permission System Design

## Overview

为 CollabDoc 添加基于用户的文档权限分享系统，类似 Notion 的分享模式。文档拥有者可以通过用户名邀请特定用户，为其设置查看或编辑权限。

## Requirements

- 基于用户的分享（通过精确用户名）
- 两级权限：Viewer（只读权限）/ Editor（编辑权限）
- 权限沿文档树继承（分享父文档，子文档自动继承）
- 侧边栏新增独立的「Shared with me」区域
- Viewer 可通过 WebSocket 实时查看更新，但不能编辑
- 前端编辑器对 Viewer 冻结（editable: false），后端同时丢弃 Viewer 的 UPDATE 消息

## Data Model

### New Table: `document_permissions`

```sql
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

Only explicit shares are stored. Inherited permissions are resolved at runtime via parent chain traversal.

### New JPA Entity

```java
@Entity
@Table(name = "document_permissions")
public class DocumentPermission {
    @Id UUID id;
    UUID documentId;
    UUID userId;
    @Enumerated(EnumType.STRING) Permission permission; // VIEWER, EDITOR
    Instant createdAt;
}

public enum Permission { VIEWER, EDITOR }
```

## Permission Resolution

### Core Method: `resolvePermission(documentId, userId)`

1. If userId is the document's ownerId -> return OWNER
2. Walk up the parent chain from documentId:
   - At each level, check document_permissions for a matching userId record
   - Found -> return that permission (VIEWER / EDITOR)
   - Not found -> continue to parent
3. Reached root with no match -> return null (no access)

### SQL Implementation (WITH RECURSIVE)

```sql
WITH RECURSIVE doc_chain AS (
    SELECT id, parent_id FROM documents WHERE id = :docId
    UNION ALL
    SELECT d.id, d.parent_id
    FROM documents d JOIN doc_chain dc ON d.id = dc.parent_id
)
SELECT dp.permission
FROM doc_chain dc
JOIN document_permissions dp ON dp.document_id = dc.id AND dp.user_id = :userId
LIMIT 1;
```

### Helper Methods

- `canView(docId, userId)` - permission is not null (VIEWER or EDITOR)
- `canEdit(docId, userId)` - permission is EDITOR or user is OWNER
- `canManagePermissions(docId, userId)` - OWNER only

## API Design

### New Permission Management APIs (Owner only)

| Method | Path | Description |
|--------|------|-------------|
| POST | `/api/docs/{docId}/permissions` | Add share (username + permission) |
| GET | `/api/docs/{docId}/permissions` | List all explicit permissions |
| PUT | `/api/docs/{docId}/permissions/{permissionId}` | Update permission level |
| DELETE | `/api/docs/{docId}/permissions/{permissionId}` | Revoke permission |

Request/Response:

```
POST /api/docs/{docId}/permissions
{ "username": "alice", "permission": "VIEWER" }
-> 201 { "id": "...", "userId": "...", "username": "alice", "permission": "VIEWER" }
```

### New Shared Documents API

| Method | Path | Description |
|--------|------|-------------|
| GET | `/api/docs/shared-with-me` | List documents shared with current user (with permission level) |

### Modified Existing API Permissions

| API | Current | New |
|-----|---------|-----|
| GET `/api/docs/{docId}` | Owner only | Owner + permitted users |
| PUT `/api/docs/{docId}` | Owner only | Owner + EDITOR |
| DELETE `/api/docs/{docId}` | Owner only | Owner only (unchanged) |
| GET `/api/docs` (tree) | Owner's docs | Unchanged |
| POST/DELETE `/api/docs/{docId}/blocks` | Owner only | Owner + EDITOR |
| GET `/api/docs/{docId}/blocks` | Owner only | Owner + permitted users |

The `GET /api/docs/{docId}` response adds a `permission` field: `"OWNER"` / `"EDITOR"` / `"VIEWER"`.

## WebSocket Permission Control

### Connection Handshake

- Resolve permission; allow connection if not null
- Store permission level in WebSocket session attributes

### Message Handling

| Message Type | Viewer | Editor / Owner |
|-------------|--------|----------------|
| MSG_SYNC_STEP1 (request state) | Allowed | Allowed |
| MSG_SYNC_STEP2 (receive state) | Allowed | Allowed |
| MSG_SYNC_UPDATE (send edit) | Dropped, not broadcast | Allowed, broadcast |
| MSG_AWARENESS (cursor/presence) | Allowed | Allowed |

## Frontend Permission Control

### Editor Behavior

- BlockNote editor: `editable: false` for Viewer (can select/copy text, cannot modify)
- Hide/disable editing toolbar for Viewer
- Title editing disabled for Viewer

### UI Permissions Matrix

| Feature | Owner | Editor | Viewer |
|---------|-------|--------|--------|
| Edit content | Yes | Yes | No (editor frozen) |
| Select/copy text | Yes | Yes | Yes |
| Edit title | Yes | Yes | No |
| Delete document | Yes | No | No |
| Manage permissions | Yes | No | No |
| View real-time updates | Yes | Yes | Yes |
| Online cursor display | Yes | Yes | Yes (gray cursor) |

### Sidebar

- Existing document tree unchanged (own documents only)
- New "Shared with me" section showing shared documents
- Each entry shows: document title + permission label (只读权限 / 编辑权限)

## Error Handling

| Scenario | Response |
|----------|----------|
| Share to non-existent username | 404, user not found |
| Duplicate share to same user | 409 Conflict |
| Share to self (owner) | 400 Bad Request |
| Non-owner manages permissions | 403 Forbidden |
| Viewer attempts edit via API | 403 Forbidden |
| Access document without permission | 403 Forbidden |

## Document Operation Side Effects

| Scenario | Behavior |
|----------|----------|
| Owner deletes document | CASCADE deletes permission records |
| New child under shared parent | Inherits permission at runtime (no extra work) |
| Move child out of shared parent | Loses inherited permission at runtime (no extra work) |
| Shared user deleted | CASCADE deletes their permission records |

## Approach: Runtime Inheritance (Approach A)

Chosen over redundant storage because:
- Clean data, no redundancy - only one record per explicit share
- Document moves automatically correct (runtime resolution)
- Revoking share only needs one delete, children auto-lose access
- Document tree depth is shallow (3-5 levels), recursive query performance is fine
- PostgreSQL WITH RECURSIVE CTE handles this efficiently
