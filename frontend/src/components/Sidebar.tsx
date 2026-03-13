import "./Sidebar.css";
import DocumentTree from "./DocumentTree";
import SharedDocuments from "./SharedDocuments";
import type { TreeNode } from "../utils/tree";
import type { SharedDocument } from "../api";
import { theme } from "../theme";

interface SidebarProps {
  tree: TreeNode[];
  expandedIds: Set<string>;
  onToggle: (id: string) => void;
  onCreateChild: (parentId: string) => void;
  onCreateRoot: () => void;
  onDelete: (id: string) => void;
  onMove: (id: string, newParentId: string | null, newIndex: number) => void;
  sharedDocuments: SharedDocument[];
}

export default function Sidebar({
  tree, expandedIds, onToggle, onCreateChild, onCreateRoot, onDelete, onMove, sharedDocuments,
}: SidebarProps) {
  return (
    <div style={{
      width: theme.sidebarWidth, minWidth: 200, flex: 1,
      borderRight: `1px solid ${theme.border}`, display: "flex", flexDirection: "column",
      backgroundColor: theme.sidebarBg, overflow: "hidden", fontFamily: theme.fontFamily,
    }}>
      <div style={{
        padding: "16px 12px 8px", fontWeight: 600, fontSize: theme.smallFontSize,
        color: theme.textSecondary, textTransform: "uppercase", letterSpacing: "0.05em",
      }}>
        Documents
      </div>
      <div style={{ flex: 1, overflowY: "auto", padding: "0 4px" }}>
        {tree.length === 0 ? (
          <div style={{ padding: "12px 8px", color: theme.textSecondary, fontSize: theme.smallFontSize }}>
            No documents yet
          </div>
        ) : (
          <DocumentTree tree={tree} expandedIds={expandedIds} onToggle={onToggle} onCreateChild={onCreateChild} onDelete={onDelete} onMove={onMove} />
        )}
      </div>
      <SharedDocuments documents={sharedDocuments} />
      <div style={{ padding: "8px 12px", borderTop: `1px solid ${theme.border}` }}>
        <button onClick={onCreateRoot} style={{
          width: "100%", padding: "6px 12px", border: "none", borderRadius: theme.radius,
          backgroundColor: "transparent", cursor: "pointer", fontSize: theme.sidebarFontSize,
          color: theme.textSecondary, textAlign: "left", display: "flex", alignItems: "center", gap: 6,
          fontFamily: theme.fontFamily,
        }}
          onMouseEnter={(e) => (e.currentTarget.style.backgroundColor = theme.hoverBg)}
          onMouseLeave={(e) => (e.currentTarget.style.backgroundColor = "transparent")}
        >
          + New Page
        </button>
      </div>
    </div>
  );
}
