import { useCallback, useEffect, useRef } from "react";
import { useParams, useOutletContext } from "react-router-dom";
import CollaborativeEditor from "../components/CollaborativeEditor";
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

  const node = docId ? findNodeById(tree, docId) : null;

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
    <div style={{ maxWidth: 900, margin: "0 auto", padding: 24 }}>
      <div
        ref={titleRef}
        contentEditable
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
      <CollaborativeEditor docId={docId} username={authUser!.username} userId={authUser!.id} />
    </div>
  );
}
