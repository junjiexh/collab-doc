import { useCallback, useEffect, useState } from "react";
import { Outlet, useNavigate, useParams } from "react-router-dom";
import Sidebar from "../components/Sidebar";
import { useAuth } from "../contexts/AuthContext";
import { theme } from "../theme";
import {
  fetchDocumentTree,
  createDocument,
  deleteDocument,
  moveDocument as apiMoveDocument,
  renameDocument,
  fetchSharedWithMe,
  type SharedDocument,
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
  const { user, logout } = useAuth();
  const [tree, setTree] = useState<TreeNode[]>([]);
  const [expandedIds, setExpandedIds] = useState<Set<string>>(loadExpandedIds);
  const [sharedDocs, setSharedDocs] = useState<SharedDocument[]>([]);
  const navigate = useNavigate();
  const { docId } = useParams<{ docId: string }>();

  const refreshTree = useCallback(async () => {
    const docs = await fetchDocumentTree();
    setTree(buildTree(docs));
  }, []);

  const refreshShared = useCallback(async () => {
    const docs = await fetchSharedWithMe();
    setSharedDocs(docs);
  }, []);

  useEffect(() => {
    refreshTree();
  }, [refreshTree]);

  useEffect(() => {
    refreshShared();
  }, [refreshShared]);

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
      <div style={{ display: "flex", flexDirection: "column", height: "100vh" }}>
        <Sidebar
          tree={tree}
          expandedIds={expandedIds}
          onToggle={handleToggle}
          onCreateChild={handleCreateChild}
          onCreateRoot={handleCreateRoot}
          onDelete={handleDelete}
          onMove={handleMove}
          sharedDocuments={sharedDocs}
        />
        <div style={{ padding: "8px 12px", borderTop: `1px solid ${theme.border}`, fontSize: theme.smallFontSize, display: "flex", justifyContent: "space-between", alignItems: "center", fontFamily: theme.fontFamily, backgroundColor: theme.sidebarBg }}>
          <span style={{ color: theme.textPrimary }}>{user?.username}</span>
          <button onClick={logout} style={{ background: "none", border: "none", color: theme.textSecondary, cursor: "pointer", fontSize: theme.smallFontSize }}>
            Log out
          </button>
        </div>
      </div>
      <div style={{ flex: 1, overflow: "auto", backgroundColor: theme.contentBg }}>
        <Outlet context={{ tree, onRename: handleRename }} />
      </div>
    </div>
  );
}
