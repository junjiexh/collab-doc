import { useCallback, useEffect, useState } from "react";
import { Outlet, useNavigate, useParams } from "react-router-dom";
import Sidebar from "../components/Sidebar";
import {
  fetchDocumentTree,
  createDocument,
  deleteDocument,
  moveDocument as apiMoveDocument,
  renameDocument,
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
      if (docId === id || isAncestor(tree, id, docId ?? "")) {
        navigate("/");
      }
      await refreshTree();
    },
    [docId, tree, navigate, refreshTree]
  );

  const handleRename = useCallback(
    async (id: string, title: string) => {
      await renameDocument(id, title);
      await refreshTree();
    },
    [refreshTree]
  );

  const handleMove = useCallback(
    async (id: string, newParentId: string | null, newIndex: number) => {
      await apiMoveDocument(id, newParentId, newIndex);
      // Auto-expand new parent so user can see the result
      if (newParentId) {
        setExpandedIds((prev) => {
          const next = new Set(prev);
          next.add(newParentId);
          saveExpandedIds(next);
          return next;
        });
      }
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
        <Outlet context={{ tree, onRename: handleRename }} />
      </div>
    </div>
  );
}
