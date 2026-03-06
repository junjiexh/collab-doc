import { useCallback, useEffect, useRef, useState } from "react";
import { useParams, useOutletContext } from "react-router-dom";
import CollaborativeEditor from "../components/CollaborativeEditor";
import ShareDialog from "../components/ShareDialog";
import { useAuth } from "../contexts/AuthContext";
import { findNodeById, type TreeNode } from "../utils/tree";

interface OutletContext {
  tree: TreeNode[];
  onRename: (id: string, title: string) => Promise<void>;
}

export default function EditorPage() {
  const { docId } = useParams<{ docId: string }>();
  const { tree, onRename } = useOutletContext<OutletContext>();
  const { user: authUser } = useAuth();
  const titleRef = useRef<HTMLDivElement>(null);
  const lastSavedTitle = useRef("");
  const [permission, setPermission] = useState<string | null>(null);
  const [showShare, setShowShare] = useState(false);

  const node = docId ? findNodeById(tree, docId) : null;

  useEffect(() => {
    if (!docId) return;
    fetch(`/api/docs/${docId}`, { credentials: "include" })
      .then(res => res.json())
      .then(data => setPermission(data.permission ?? "OWNER"))
      .catch(() => setPermission(null));
  }, [docId]);

  // Sync displayed title when docId or external tree changes
  useEffect(() => {
    if (titleRef.current && node) {
      const current = titleRef.current.textContent ?? "";
      if (current !== node.title) {
        titleRef.current.textContent = node.title;
      }
      lastSavedTitle.current = node.title;
    }
  }, [docId, node?.title]);

  const saveTitle = useCallback(() => {
    if (!docId || !titleRef.current) return;
    const newTitle = (titleRef.current.textContent ?? "").trim();
    if (newTitle && newTitle !== lastSavedTitle.current) {
      lastSavedTitle.current = newTitle;
      onRename(docId, newTitle);
    }
  }, [docId, onRename]);

  const handleKeyDown = useCallback(
    (e: React.KeyboardEvent) => {
      if (e.key === "Enter") {
        e.preventDefault();
        titleRef.current?.blur();
      }
    },
    []
  );

  if (!docId) return <p>No document ID</p>;

  return (
    <>
    <div style={{ maxWidth: 900, margin: "0 auto", padding: 24 }}>
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
      <div
        ref={titleRef}
        contentEditable={permission === "OWNER" || permission === "EDITOR"}
        suppressContentEditableWarning
        onBlur={saveTitle}
        onKeyDown={handleKeyDown}
        data-placeholder="Untitled"
        style={{
          fontSize: 32,
          fontWeight: 700,
          outline: "none",
          border: "none",
          marginBottom: 16,
          lineHeight: 1.3,
          wordBreak: "break-word",
        }}
      />
      <style>{`
        [data-placeholder]:empty::before {
          content: attr(data-placeholder);
          color: #b0b0b0;
          pointer-events: none;
        }
      `}</style>
      <CollaborativeEditor
        docId={docId}
        username={authUser!.username}
        userId={authUser!.id}
        editable={permission === "OWNER" || permission === "EDITOR"}
      />
    </div>
    {showShare && docId && (
      <ShareDialog docId={docId} onClose={() => setShowShare(false)} />
    )}
    </>
  );
}
