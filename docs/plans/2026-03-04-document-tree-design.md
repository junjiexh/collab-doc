# Document Tree Feature Design

## Overview

Add a Notion-like document tree to the collaborative document editor, enabling hierarchical document organization with drag-and-drop reordering.

## Requirements

- Infinite nesting depth
- Left sidebar with document tree, always visible
- Expand/collapse, drag-and-drop sorting/nesting, create child documents, delete (cascade)
- UI library: dnd-kit for drag-and-drop interactions

## Data Model

### Database Changes (Flyway Migration)

Add `parent_id` and `sort_order` to the `documents` table:

```sql
ALTER TABLE documents ADD COLUMN parent_id UUID REFERENCES documents(id) ON DELETE CASCADE;
ALTER TABLE documents ADD COLUMN sort_order INTEGER NOT NULL DEFAULT 0;
CREATE INDEX idx_documents_parent_id ON documents(parent_id);
```

- `parent_id = NULL` → root-level document
- `sort_order` → ordering among siblings
- `ON DELETE CASCADE` → deleting a parent deletes all descendants

### JPA Entity Changes

```java
// Document.java
@Column(name = "parent_id")
private UUID parentId;

@Column(name = "sort_order", nullable = false)
private Integer sortOrder = 0;
```

## API Design

### New Endpoints

```
GET  /api/docs/tree          → Returns flat list of all documents with parentId, sorted
PUT  /api/docs/{id}/move     → Move document: { parentId: UUID|null, sortOrder: int }
```

### Modified Endpoints

```
POST /api/docs               → Add optional parentId parameter
```

### Existing Endpoints (unchanged)

```
GET    /api/docs             → List all documents (flat, backwards compatible)
GET    /api/docs/{id}        → Get single document
PUT    /api/docs/{id}        → Update document title
DELETE /api/docs/{id}        → Delete document (cascade handled by DB)
```

### Tree Query (Recursive CTE)

```sql
WITH RECURSIVE doc_tree AS (
  SELECT id, title, parent_id, sort_order, created_at, updated_at, 0 as depth
  FROM documents WHERE parent_id IS NULL
  UNION ALL
  SELECT d.id, d.title, d.parent_id, d.sort_order, d.created_at, d.updated_at, dt.depth + 1
  FROM documents d JOIN doc_tree dt ON d.parent_id = dt.id
)
SELECT * FROM doc_tree ORDER BY depth, sort_order;
```

In practice, the simpler approach is to fetch all documents with `ORDER BY sort_order` and let the frontend build the tree structure from the flat list using `parentId`.

## Frontend Architecture

### Layout Change

```
Before:                          After:
┌──────────────┐                ┌──────────┬──────────────┐
│ DocumentList │  →click→       │ Sidebar  │              │
│ (full page)  │  EditorPage    │ DocTree  │  EditorPage  │
└──────────────┘                │          │  or Welcome  │
                                └──────────┴──────────────┘
```

### Component Structure

```
App.tsx (Router)
└── MainLayout
    ├── Sidebar (resizable, collapsible)
    │   ├── SidebarHeader
    │   ├── DocumentTree (dnd-kit sortable tree)
    │   │   └── TreeItem (per node: expand/collapse, title, hover actions)
    │   └── NewDocButton
    └── <Outlet />
        ├── EditorPage (/doc/:docId)
        └── WelcomePage (/ - no doc selected)
```

### Routing

```
/ → MainLayout → WelcomePage (empty state)
/doc/:docId → MainLayout → EditorPage
```

React Router nested routes with `<Outlet />` for the content area.

### State Management

No new libraries. React built-in:

- **Tree data**: `useState` + `useEffect` in MainLayout, fetched from `GET /api/docs/tree`
- **Expand/collapse**: `Set<docId>` persisted in `localStorage`
- **Selected document**: Derived from URL params (`useParams`)
- **Drag state**: Managed internally by dnd-kit

### Core Interactions

1. **Expand/Collapse**: Click arrow icon on tree node. State persisted in localStorage.
2. **Drag & Drop**: dnd-kit sortable tree - supports reordering siblings and moving across levels.
3. **Create Child**: Hover node → show `+` button → `POST /api/docs` with `parentId`.
4. **Delete**: Hover node → show `...` menu → confirm → `DELETE /api/docs/{id}` (cascade).
5. **Active Highlight**: Current `docId` from URL highlighted in tree.
6. **Delete Current**: If deleted doc is current or ancestor of current, navigate to `/`.

### Data Flow

```
GET /api/docs/tree → flat list [{id, title, parentId, sortOrder, ...}]
  ↓
buildTree(flatList) → nested [{...doc, children: [...]}]
  ↓
DocumentTree renders with dnd-kit
  ↓
onDragEnd → PUT /api/docs/{id}/move → refetch tree
```

## Tech Decisions

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Tree data model | parent_id adjacency list | Simplest, fast writes for drag-and-drop |
| UI drag library | dnd-kit | Lightweight, good tree support, active community |
| State management | React useState + localStorage | No need for extra lib, state is simple |
| Nesting limit | None (infinite) | Matches Notion behavior |
| Delete behavior | Cascade (delete children) | ON DELETE CASCADE in DB, simplest UX |
