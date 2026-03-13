import { useCallback, useEffect, useRef, useState } from "react";
import { useParams, useOutletContext } from "react-router-dom";
import CollaborativeEditor from "../components/CollaborativeEditor";
import ShareDialog from "../components/ShareDialog";
import { useAuth } from "../contexts/AuthContext";
import { findNodeById, type TreeNode } from "../utils/tree";
import { theme } from "../theme";

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
    setPermission(null);
    fetch(`/api/docs/${docId}`, { credentials: "include" })
      .then(res => { if (!res.ok) { setPermission(null); return null; } return res.json(); })
      .then(data => { if (data) setPermission(data.permission ?? null); })
      .catch(() => setPermission(null));
  }, [docId]);

  useEffect(() => {
    if (titleRef.current && node) {
      const current = titleRef.current.textContent ?? "";
      if (current !== node.title) { titleRef.current.textContent = node.title; }
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

  const handleKeyDown = useCallback((e: React.KeyboardEvent) => {
    if (e.key === "Enter") { e.preventDefault(); titleRef.current?.blur(); }
  }, []);

  if (!docId) return <p>No document ID</p>;

  return (
    <>
      <div style={{ maxWidth: theme.editorMaxWidth, margin: "0 auto", padding: `24px ${theme.editorPadX}px`, fontFamily: theme.fontFamily }}>
        {permission === "OWNER" && (
          <div style={{ display: "flex", justifyContent: "flex-end", marginBottom: 8 }}>
            <button onClick={() => setShowShare(true)} style={{
              padding: theme.btnPadding, background: "transparent", color: theme.primary,
              border: `1px solid ${theme.primary}`, borderRadius: theme.btnRadius, cursor: "pointer",
              fontSize: theme.smallFontSize, fontWeight: theme.btnWeight, fontFamily: theme.fontFamily,
            }}>
              Share
            </button>
          </div>
        )}
        <div ref={titleRef} contentEditable={permission === "OWNER" || permission === "EDITOR"}
          suppressContentEditableWarning onBlur={saveTitle} onKeyDown={handleKeyDown}
          data-placeholder="Untitled"
          style={{
            fontSize: theme.titleFontSize, fontWeight: 700, outline: "none", border: "none",
            marginBottom: 16, lineHeight: theme.lineHeight, wordBreak: "break-word", color: theme.textPrimary,
          }}
        />
        <style>{`[data-placeholder]:empty::before { content: attr(data-placeholder); color: ${theme.placeholder}; pointer-events: none; }`}</style>
        {permission ? (
          <CollaborativeEditor docId={docId} username={authUser!.username} userId={authUser!.id} editable={permission === "OWNER" || permission === "EDITOR"} />
        ) : (
          <div style={{ padding: 24, color: theme.textSecondary }}>Loading...</div>
        )}
      </div>
      {showShare && docId && <ShareDialog docId={docId} onClose={() => setShowShare(false)} />}
    </>
  );
}
