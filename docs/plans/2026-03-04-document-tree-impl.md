# Document Tree Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Add a Notion-like hierarchical document tree sidebar with drag-and-drop reordering using dnd-kit.

**Architecture:** parent_id adjacency list model in PostgreSQL. Left sidebar with dnd-kit sortable tree, React Router nested layout for sidebar + editor coexistence. Backend returns flat list, frontend assembles tree.

**Tech Stack:** Spring Boot 3.5 / JPA / Flyway (backend), React 19 / dnd-kit / React Router 7 (frontend)

---

### Task 1: Database Migration

**Files:**
- Create: `backend/src/main/resources/db/migration/V2__add_document_tree.sql`

**Step 1: Write the migration**

```sql
ALTER TABLE documents ADD COLUMN parent_id UUID REFERENCES documents(id) ON DELETE CASCADE;
ALTER TABLE documents ADD COLUMN sort_order INTEGER NOT NULL DEFAULT 0;
CREATE INDEX idx_documents_parent_id ON documents(parent_id);
```

**Step 2: Verify migration applies**

Run: `cd backend && ./gradlew bootRun` (start the app, Flyway runs automatically)
Expected: App starts without errors, `documents` table has `parent_id` and `sort_order` columns.

**Step 3: Commit**

```bash
git add backend/src/main/resources/db/migration/V2__add_document_tree.sql
git commit -m "feat: add parent_id and sort_order to documents table"
```

---

### Task 2: Backend Entity & Repository

**Files:**
- Modify: `backend/src/main/java/com/collabdoc/model/Document.java`
- Modify: `backend/src/main/java/com/collabdoc/repository/DocumentRepository.java`

**Step 1: Add parentId and sortOrder to Document entity**

In `Document.java`, add fields after `updatedAt`:

```java
@Column(name = "parent_id")
private UUID parentId;

@Column(name = "sort_order", nullable = false)
private Integer sortOrder = 0;
```

Add to the constructor `Document(String title)`:

```java
this.sortOrder = 0;
```

Add a new constructor:

```java
public Document(String title, UUID parentId) {
    this(title);
    this.parentId = parentId;
}
```

Add getters/setters:

```java
public UUID getParentId() { return parentId; }
public void setParentId(UUID parentId) { this.parentId = parentId; }
public Integer getSortOrder() { return sortOrder; }
public void setSortOrder(Integer sortOrder) { this.sortOrder = sortOrder; }
```

**Step 2: Add repository query methods**

In `DocumentRepository.java`, add:

```java
import java.util.List;
import org.springframework.data.jpa.repository.Query;

List<Document> findAllByOrderBySortOrderAsc();

@Query("SELECT COALESCE(MAX(d.sortOrder), -1) FROM Document d WHERE d.parentId = :parentId")
int findMaxSortOrderByParentId(UUID parentId);

@Query("SELECT COALESCE(MAX(d.sortOrder), -1) FROM Document d WHERE d.parentId IS NULL")
int findMaxSortOrderForRoot();
```

**Step 3: Verify compilation**

Run: `cd backend && ./gradlew compileJava`
Expected: BUILD SUCCESSFUL

**Step 4: Commit**

```bash
git add backend/src/main/java/com/collabdoc/model/Document.java backend/src/main/java/com/collabdoc/repository/DocumentRepository.java
git commit -m "feat: add parentId and sortOrder to Document entity and repository"
```

---

### Task 3: Backend Service & Controller

**Files:**
- Modify: `backend/src/main/java/com/collabdoc/service/DocumentService.java`
- Modify: `backend/src/main/java/com/collabdoc/controller/DocumentController.java`

**Step 1: Update DocumentService**

Add new methods and modify `createDocument`:

```java
public Document createDocument(String title, UUID parentId) {
    int maxSort;
    if (parentId == null) {
        maxSort = documentRepository.findMaxSortOrderForRoot();
    } else {
        maxSort = documentRepository.findMaxSortOrderByParentId(parentId);
    }
    Document doc = new Document(title, parentId);
    doc.setSortOrder(maxSort + 1);
    return documentRepository.save(doc);
}

public List<Document> listDocumentsForTree() {
    return documentRepository.findAllByOrderBySortOrderAsc();
}

public Optional<Document> moveDocument(UUID id, UUID newParentId, int sortOrder) {
    return documentRepository.findById(id).map(doc -> {
        doc.setParentId(newParentId);
        doc.setSortOrder(sortOrder);
        doc.setUpdatedAt(Instant.now());
        return documentRepository.save(doc);
    });
}
```

Update existing `createDocument(String title)` to delegate:

```java
public Document createDocument(String title) {
    return createDocument(title, null);
}
```

**Step 2: Update DocumentController**

Add tree endpoint and move endpoint. Update create to accept parentId:

```java
@GetMapping("/tree")
public List<Document> listDocumentsForTree() {
    return documentService.listDocumentsForTree();
}

@PostMapping
public Document createDocument(@RequestBody Map<String, String> body) {
    String title = body.getOrDefault("title", "Untitled");
    String parentIdStr = body.get("parentId");
    UUID parentId = parentIdStr != null ? UUID.fromString(parentIdStr) : null;
    return documentService.createDocument(title, parentId);
}

@PutMapping("/{id}/move")
public ResponseEntity<Document> moveDocument(@PathVariable UUID id, @RequestBody Map<String, Object> body) {
    String parentIdStr = (String) body.get("parentId");
    UUID parentId = parentIdStr != null ? UUID.fromString(parentIdStr) : null;
    int sortOrder = (int) body.get("sortOrder");
    return documentService.moveDocument(id, parentId, sortOrder)
        .map(ResponseEntity::ok)
        .orElse(ResponseEntity.notFound().build());
}
```

**Step 3: Verify compilation and boot**

Run: `cd backend && ./gradlew compileJava`
Expected: BUILD SUCCESSFUL

**Step 4: Commit**

```bash
git add backend/src/main/java/com/collabdoc/service/DocumentService.java backend/src/main/java/com/collabdoc/controller/DocumentController.java
git commit -m "feat: add tree listing, parentId creation, and move endpoint"
```

---

### Task 4: Install Frontend Dependencies

**Files:**
- Modify: `frontend/package.json`

**Step 1: Install dnd-kit packages**

```bash
cd frontend && npm install @dnd-kit/core @dnd-kit/sortable @dnd-kit/utilities
```

**Step 2: Verify install**

Run: `cd frontend && npm ls @dnd-kit/core`
Expected: Shows installed version

**Step 3: Commit**

```bash
git add frontend/package.json frontend/package-lock.json
git commit -m "feat: install dnd-kit dependencies"
```

---

### Task 5: Frontend API Client Updates

**Files:**
- Modify: `frontend/src/api.ts`

**Step 1: Update DocumentMeta type and add new API functions**

Add `parentId` and `sortOrder` to the `DocumentMeta` interface:

```typescript
export interface DocumentMeta {
  id: string;
  title: string;
  parentId: string | null;
  sortOrder: number;
  createdAt: string;
  updatedAt: string;
}
```

Update `createDocument` to accept optional parentId:

```typescript
export async function createDocument(title: string, parentId?: string): Promise<DocumentMeta> {
  const res = await fetch(`${API_BASE}/docs`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ title, parentId: parentId ?? null }),
  });
  return res.json();
}
```

Add new functions:

```typescript
export async function fetchDocumentTree(): Promise<DocumentMeta[]> {
  const res = await fetch(`${API_BASE}/docs/tree`);
  return res.json();
}

export async function moveDocument(id: string, parentId: string | null, sortOrder: number): Promise<DocumentMeta> {
  const res = await fetch(`${API_BASE}/docs/${id}/move`, {
    method: "PUT",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ parentId, sortOrder }),
  });
  return res.json();
}
```

**Step 2: Verify no TypeScript errors**

Run: `cd frontend && npx tsc --noEmit`
Expected: No errors (or only pre-existing ones)

**Step 3: Commit**

```bash
git add frontend/src/api.ts
git commit -m "feat: add tree API client functions"
```

---

### Task 6: Tree Utility Functions

**Files:**
- Create: `frontend/src/utils/tree.ts`

**Step 1: Create tree building utility**

```typescript
import type { DocumentMeta } from "../api";

export interface TreeNode extends DocumentMeta {
  children: TreeNode[];
}

export function buildTree(flatList: DocumentMeta[]): TreeNode[] {
  const map = new Map<string, TreeNode>();
  const roots: TreeNode[] = [];

  // Create nodes
  for (const doc of flatList) {
    map.set(doc.id, { ...doc, children: [] });
  }

  // Build tree
  for (const doc of flatList) {
    const node = map.get(doc.id)!;
    if (doc.parentId && map.has(doc.parentId)) {
      map.get(doc.parentId)!.children.push(node);
    } else {
      roots.push(node);
    }
  }

  // Sort children by sortOrder
  const sortChildren = (nodes: TreeNode[]) => {
    nodes.sort((a, b) => a.sortOrder - b.sortOrder);
    nodes.forEach((n) => sortChildren(n.children));
  };
  sortChildren(roots);

  return roots;
}

export function flattenTree(nodes: TreeNode[], expandedIds: Set<string>): FlattenedItem[] {
  const result: FlattenedItem[] = [];

  const traverse = (items: TreeNode[], depth: number) => {
    for (const item of items) {
      result.push({ ...item, depth });
      if (expandedIds.has(item.id) && item.children.length > 0) {
        traverse(item.children, depth + 1);
      }
    }
  };

  traverse(nodes, 0);
  return result;
}

export interface FlattenedItem extends TreeNode {
  depth: number;
}

export function findNodeById(nodes: TreeNode[], id: string): TreeNode | null {
  for (const node of nodes) {
    if (node.id === id) return node;
    const found = findNodeById(node.children, id);
    if (found) return found;
  }
  return null;
}

export function isAncestor(nodes: TreeNode[], ancestorId: string, descendantId: string): boolean {
  const ancestor = findNodeById(nodes, ancestorId);
  if (!ancestor) return false;
  return findNodeById(ancestor.children, descendantId) !== null;
}
```

**Step 2: Commit**

```bash
git add frontend/src/utils/tree.ts
git commit -m "feat: add tree building and flattening utilities"
```

---

### Task 7: Sidebar & TreeItem Components

**Files:**
- Create: `frontend/src/components/Sidebar.tsx`
- Create: `frontend/src/components/DocumentTree.tsx`
- Create: `frontend/src/components/TreeItem.tsx`

**Step 1: Create TreeItem component**

`frontend/src/components/TreeItem.tsx`:

```tsx
import React, { forwardRef, HTMLAttributes } from "react";

interface TreeItemProps extends HTMLAttributes<HTMLDivElement> {
  id: string;
  title: string;
  depth: number;
  hasChildren: boolean;
  isExpanded: boolean;
  isActive: boolean;
  onToggle: () => void;
  onCreateChild: () => void;
  onDelete: () => void;
  isDragging?: boolean;
  handleProps?: Record<string, unknown>;
}

export const TreeItem = forwardRef<HTMLDivElement, TreeItemProps>(
  (
    {
      id,
      title,
      depth,
      hasChildren,
      isExpanded,
      isActive,
      onToggle,
      onCreateChild,
      onDelete,
      isDragging,
      handleProps,
      style,
      ...props
    },
    ref
  ) => {
    return (
      <div
        ref={ref}
        style={{
          display: "flex",
          alignItems: "center",
          padding: "4px 8px",
          paddingLeft: `${depth * 20 + 8}px`,
          cursor: "pointer",
          borderRadius: 4,
          backgroundColor: isActive
            ? "rgba(0,0,0,0.08)"
            : isDragging
            ? "rgba(0,0,0,0.04)"
            : "transparent",
          opacity: isDragging ? 0.5 : 1,
          userSelect: "none",
          fontSize: 14,
          ...style,
        }}
        {...props}
      >
        <span
          onClick={(e) => {
            e.stopPropagation();
            if (hasChildren) onToggle();
          }}
          style={{
            width: 20,
            display: "inline-flex",
            justifyContent: "center",
            color: "#999",
            flexShrink: 0,
          }}
        >
          {hasChildren ? (isExpanded ? "▾" : "▸") : ""}
        </span>

        <span
          {...handleProps}
          style={{
            flex: 1,
            overflow: "hidden",
            textOverflow: "ellipsis",
            whiteSpace: "nowrap",
          }}
        >
          {title || "Untitled"}
        </span>

        <span
          className="tree-item-actions"
          style={{
            display: "flex",
            gap: 4,
            opacity: 0,
            transition: "opacity 0.15s",
            flexShrink: 0,
          }}
        >
          <button
            onClick={(e) => {
              e.stopPropagation();
              onCreateChild();
            }}
            style={{
              border: "none",
              background: "none",
              cursor: "pointer",
              padding: "0 4px",
              fontSize: 14,
              color: "#666",
            }}
            title="New sub-page"
          >
            +
          </button>
          <button
            onClick={(e) => {
              e.stopPropagation();
              onDelete();
            }}
            style={{
              border: "none",
              background: "none",
              cursor: "pointer",
              padding: "0 4px",
              fontSize: 14,
              color: "#666",
            }}
            title="Delete"
          >
            ×
          </button>
        </span>
      </div>
    );
  }
);

TreeItem.displayName = "TreeItem";
```

**Step 2: Create DocumentTree component**

`frontend/src/components/DocumentTree.tsx`:

```tsx
import { useCallback, useMemo, useState } from "react";
import { useNavigate, useParams } from "react-router-dom";
import {
  DndContext,
  closestCenter,
  DragEndEvent,
  DragStartEvent,
  DragOverlay,
  PointerSensor,
  useSensor,
  useSensors,
} from "@dnd-kit/core";
import {
  SortableContext,
  useSortable,
  verticalListSortingStrategy,
} from "@dnd-kit/sortable";
import { CSS } from "@dnd-kit/utilities";
import { TreeItem } from "./TreeItem";
import type { TreeNode, FlattenedItem } from "../utils/tree";
import { flattenTree, findNodeById, isAncestor } from "../utils/tree";

interface DocumentTreeProps {
  tree: TreeNode[];
  expandedIds: Set<string>;
  onToggle: (id: string) => void;
  onCreateChild: (parentId: string) => void;
  onDelete: (id: string) => void;
  onMove: (id: string, newParentId: string | null, newIndex: number) => void;
}

function SortableTreeItem({
  item,
  isActive,
  expandedIds,
  onToggle,
  onCreateChild,
  onDelete,
  onClick,
}: {
  item: FlattenedItem;
  isActive: boolean;
  expandedIds: Set<string>;
  onToggle: (id: string) => void;
  onCreateChild: (parentId: string) => void;
  onDelete: (id: string) => void;
  onClick: (id: string) => void;
}) {
  const {
    attributes,
    listeners,
    setNodeRef,
    transform,
    transition,
    isDragging,
  } = useSortable({ id: item.id });

  const style = {
    transform: CSS.Translate.toString(transform),
    transition,
  };

  return (
    <TreeItem
      ref={setNodeRef}
      id={item.id}
      title={item.title}
      depth={item.depth}
      hasChildren={item.children.length > 0}
      isExpanded={expandedIds.has(item.id)}
      isActive={isActive}
      isDragging={isDragging}
      onToggle={() => onToggle(item.id)}
      onCreateChild={() => onCreateChild(item.id)}
      onDelete={() => onDelete(item.id)}
      onClick={() => onClick(item.id)}
      handleProps={{ ...attributes, ...listeners }}
      style={style}
    />
  );
}

export default function DocumentTree({
  tree,
  expandedIds,
  onToggle,
  onCreateChild,
  onDelete,
  onMove,
}: DocumentTreeProps) {
  const navigate = useNavigate();
  const { docId } = useParams<{ docId: string }>();
  const [activeId, setActiveId] = useState<string | null>(null);

  const sensors = useSensors(
    useSensor(PointerSensor, { activationConstraint: { distance: 5 } })
  );

  const flatItems = useMemo(
    () => flattenTree(tree, expandedIds),
    [tree, expandedIds]
  );

  const handleDragStart = useCallback((event: DragStartEvent) => {
    setActiveId(String(event.active.id));
  }, []);

  const handleDragEnd = useCallback(
    (event: DragEndEvent) => {
      setActiveId(null);
      const { active, over } = event;
      if (!over || active.id === over.id) return;

      const activeItem = flatItems.find((i) => i.id === active.id);
      const overItem = flatItems.find((i) => i.id === over.id);
      if (!activeItem || !overItem) return;

      // Prevent dropping a parent into its own descendant
      if (isAncestor(tree, String(active.id), String(over.id))) return;

      // Move to same parent as the target, at target's position
      const newParentId = overItem.parentId;
      const siblings = flatItems.filter(
        (i) => i.parentId === newParentId && i.id !== String(active.id)
      );
      const overIndex = siblings.findIndex((i) => i.id === over.id);
      const newIndex = overIndex === -1 ? 0 : overIndex;

      onMove(String(active.id), newParentId, newIndex);
    },
    [flatItems, tree, onMove]
  );

  const activeItem = activeId
    ? flatItems.find((i) => i.id === activeId)
    : null;

  return (
    <DndContext
      sensors={sensors}
      collisionDetection={closestCenter}
      onDragStart={handleDragStart}
      onDragEnd={handleDragEnd}
    >
      <SortableContext
        items={flatItems.map((i) => i.id)}
        strategy={verticalListSortingStrategy}
      >
        {flatItems.map((item) => (
          <SortableTreeItem
            key={item.id}
            item={item}
            isActive={docId === item.id}
            expandedIds={expandedIds}
            onToggle={onToggle}
            onCreateChild={onCreateChild}
            onDelete={onDelete}
            onClick={(id) => navigate(`/doc/${id}`)}
          />
        ))}
      </SortableContext>

      <DragOverlay>
        {activeItem ? (
          <TreeItem
            id={activeItem.id}
            title={activeItem.title}
            depth={0}
            hasChildren={activeItem.children.length > 0}
            isExpanded={false}
            isActive={false}
            isDragging
            onToggle={() => {}}
            onCreateChild={() => {}}
            onDelete={() => {}}
          />
        ) : null}
      </DragOverlay>
    </DndContext>
  );
}
```

**Step 3: Create Sidebar component**

`frontend/src/components/Sidebar.tsx`:

```tsx
import { useNavigate } from "react-router-dom";
import DocumentTree from "./DocumentTree";
import type { TreeNode } from "../utils/tree";

interface SidebarProps {
  tree: TreeNode[];
  expandedIds: Set<string>;
  onToggle: (id: string) => void;
  onCreateChild: (parentId: string) => void;
  onCreateRoot: () => void;
  onDelete: (id: string) => void;
  onMove: (id: string, newParentId: string | null, newIndex: number) => void;
}

export default function Sidebar({
  tree,
  expandedIds,
  onToggle,
  onCreateChild,
  onCreateRoot,
  onDelete,
  onMove,
}: SidebarProps) {
  return (
    <div
      style={{
        width: 260,
        minWidth: 200,
        height: "100vh",
        borderRight: "1px solid #e8e8e8",
        display: "flex",
        flexDirection: "column",
        backgroundColor: "#fbfbfa",
        overflow: "hidden",
      }}
    >
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
        Documents
      </div>

      <div style={{ flex: 1, overflowY: "auto", padding: "0 4px" }}>
        {tree.length === 0 ? (
          <div style={{ padding: "12px 8px", color: "#999", fontSize: 13 }}>
            No documents yet
          </div>
        ) : (
          <DocumentTree
            tree={tree}
            expandedIds={expandedIds}
            onToggle={onToggle}
            onCreateChild={onCreateChild}
            onDelete={onDelete}
            onMove={onMove}
          />
        )}
      </div>

      <div style={{ padding: "8px 12px", borderTop: "1px solid #e8e8e8" }}>
        <button
          onClick={onCreateRoot}
          style={{
            width: "100%",
            padding: "6px 12px",
            border: "none",
            borderRadius: 4,
            backgroundColor: "transparent",
            cursor: "pointer",
            fontSize: 14,
            color: "#666",
            textAlign: "left",
            display: "flex",
            alignItems: "center",
            gap: 6,
          }}
          onMouseEnter={(e) =>
            (e.currentTarget.style.backgroundColor = "rgba(0,0,0,0.04)")
          }
          onMouseLeave={(e) =>
            (e.currentTarget.style.backgroundColor = "transparent")
          }
        >
          + New Page
        </button>
      </div>
    </div>
  );
}
```

**Step 4: Add CSS for hover actions**

Create `frontend/src/components/Sidebar.css`:

```css
.tree-item-actions {
  opacity: 0;
  transition: opacity 0.15s;
}

div:hover > .tree-item-actions {
  opacity: 1;
}
```

Note: The hover effect on tree-item-actions uses inline styles. For it to work on hover, we need a simple global CSS rule. Import this in Sidebar.tsx with `import "./Sidebar.css"`.

Update `Sidebar.tsx` to add: `import "./Sidebar.css";` at the top.

**Step 5: Commit**

```bash
git add frontend/src/components/TreeItem.tsx frontend/src/components/DocumentTree.tsx frontend/src/components/Sidebar.tsx frontend/src/components/Sidebar.css
git commit -m "feat: add Sidebar, DocumentTree, and TreeItem components"
```

---

### Task 8: MainLayout & Routing Changes

**Files:**
- Create: `frontend/src/layouts/MainLayout.tsx`
- Modify: `frontend/src/App.tsx`
- Create: `frontend/src/pages/WelcomePage.tsx`
- Modify: `frontend/src/pages/EditorPage.tsx`

**Step 1: Create WelcomePage**

`frontend/src/pages/WelcomePage.tsx`:

```tsx
export default function WelcomePage() {
  return (
    <div
      style={{
        display: "flex",
        justifyContent: "center",
        alignItems: "center",
        height: "100%",
        color: "#999",
        fontSize: 16,
      }}
    >
      Select a document or create a new one
    </div>
  );
}
```

**Step 2: Create MainLayout**

`frontend/src/layouts/MainLayout.tsx`:

```tsx
import { useCallback, useEffect, useState } from "react";
import { Outlet, useNavigate, useParams } from "react-router-dom";
import Sidebar from "../components/Sidebar";
import {
  fetchDocumentTree,
  createDocument,
  deleteDocument,
  moveDocument as apiMoveDocument,
} from "../api";
import { buildTree, isAncestor, type TreeNode } from "../utils/tree";

const EXPANDED_STORAGE_KEY = "doc-tree-expanded";

function loadExpandedIds(): Set<string> {
  try {
    const stored = localStorage.getItem(EXPANDED_STORAGE_KEY);
    return stored ? new Set(JSON.parse(stored)) : new Set();
  } catch {
    return new Set();
  }
}

function saveExpandedIds(ids: Set<string>) {
  localStorage.setItem(EXPANDED_STORAGE_KEY, JSON.stringify([...ids]));
}

export default function MainLayout() {
  const [tree, setTree] = useState<TreeNode[]>([]);
  const [expandedIds, setExpandedIds] = useState<Set<string>>(loadExpandedIds);
  const navigate = useNavigate();
  const { docId } = useParams<{ docId: string }>();

  const refreshTree = useCallback(async () => {
    const docs = await fetchDocumentTree();
    setTree(buildTree(docs));
  }, []);

  useEffect(() => {
    refreshTree();
  }, [refreshTree]);

  const handleToggle = useCallback((id: string) => {
    setExpandedIds((prev) => {
      const next = new Set(prev);
      if (next.has(id)) {
        next.delete(id);
      } else {
        next.add(id);
      }
      saveExpandedIds(next);
      return next;
    });
  }, []);

  const handleCreateRoot = useCallback(async () => {
    const doc = await createDocument("Untitled");
    await refreshTree();
    navigate(`/doc/${doc.id}`);
  }, [navigate, refreshTree]);

  const handleCreateChild = useCallback(
    async (parentId: string) => {
      const doc = await createDocument("Untitled", parentId);
      // Auto-expand parent
      setExpandedIds((prev) => {
        const next = new Set(prev);
        next.add(parentId);
        saveExpandedIds(next);
        return next;
      });
      await refreshTree();
      navigate(`/doc/${doc.id}`);
    },
    [navigate, refreshTree]
  );

  const handleDelete = useCallback(
    async (id: string) => {
      if (!confirm("Delete this document and all its sub-pages?")) return;
      await deleteDocument(id);
      // If deleted doc is current or ancestor, navigate away
      if (docId === id || isAncestor(tree, id, docId ?? "")) {
        navigate("/");
      }
      await refreshTree();
    },
    [docId, tree, navigate, refreshTree]
  );

  const handleMove = useCallback(
    async (id: string, newParentId: string | null, newIndex: number) => {
      await apiMoveDocument(id, newParentId, newIndex);
      await refreshTree();
    },
    [refreshTree]
  );

  return (
    <div style={{ display: "flex", height: "100vh", overflow: "hidden" }}>
      <Sidebar
        tree={tree}
        expandedIds={expandedIds}
        onToggle={handleToggle}
        onCreateChild={handleCreateChild}
        onCreateRoot={handleCreateRoot}
        onDelete={handleDelete}
        onMove={handleMove}
      />
      <div style={{ flex: 1, overflow: "auto" }}>
        <Outlet />
      </div>
    </div>
  );
}
```

**Step 3: Update EditorPage - remove back link**

Replace `frontend/src/pages/EditorPage.tsx`:

```tsx
import { useParams } from "react-router-dom";
import CollaborativeEditor from "../components/CollaborativeEditor";

export default function EditorPage() {
  const { docId } = useParams<{ docId: string }>();

  if (!docId) return <p>No document ID</p>;

  return (
    <div style={{ maxWidth: 900, margin: "0 auto", padding: 24 }}>
      <CollaborativeEditor docId={docId} />
    </div>
  );
}
```

**Step 4: Update App.tsx routing**

Replace `frontend/src/App.tsx`:

```tsx
import { BrowserRouter, Routes, Route } from "react-router-dom";
import MainLayout from "./layouts/MainLayout";
import EditorPage from "./pages/EditorPage";
import WelcomePage from "./pages/WelcomePage";

export default function App() {
  return (
    <BrowserRouter>
      <Routes>
        <Route element={<MainLayout />}>
          <Route path="/" element={<WelcomePage />} />
          <Route path="/doc/:docId" element={<EditorPage />} />
        </Route>
      </Routes>
    </BrowserRouter>
  );
}
```

**Step 5: Commit**

```bash
git add frontend/src/layouts/MainLayout.tsx frontend/src/pages/WelcomePage.tsx frontend/src/pages/EditorPage.tsx frontend/src/App.tsx
git commit -m "feat: add MainLayout with sidebar, update routing to nested layout"
```

---

### Task 9: Remove Old DocumentList Page

**Files:**
- Delete: `frontend/src/pages/DocumentList.tsx`

**Step 1: Delete the file**

The document list is replaced by the sidebar tree. Delete `frontend/src/pages/DocumentList.tsx`.

**Step 2: Verify no imports reference it**

Search for `DocumentList` in the frontend codebase. The old import in `App.tsx` was already replaced in Task 8.

**Step 3: Verify frontend compiles**

Run: `cd frontend && npx tsc --noEmit`
Expected: No errors

**Step 4: Commit**

```bash
git rm frontend/src/pages/DocumentList.tsx
git commit -m "refactor: remove old flat DocumentList page"
```

---

### Task 10: Manual Integration Test

**Step 1: Start backend and frontend**

```bash
# Terminal 1
cd backend && ./gradlew bootRun

# Terminal 2
cd frontend && npm run dev
```

**Step 2: Verify these scenarios**

1. Open http://localhost:5173 - should see sidebar with "Documents" header and empty state
2. Click "+ New Page" - creates a root document, navigates to editor
3. Hover over the document in sidebar - should see + and × buttons
4. Click + on a document - creates a child document
5. Expand/collapse a parent document with the arrow
6. Drag a document to reorder
7. Delete a document - should cascade delete children
8. Refresh the page - expand/collapse state should persist

**Step 3: Final commit if any fixes needed**

```bash
git add -A
git commit -m "fix: integration fixes for document tree"
```
