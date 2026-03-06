import "./Sidebar.css";
import DocumentTree from "./DocumentTree";
import SharedDocuments from "./SharedDocuments";
import type { TreeNode } from "../utils/tree";
import type { SharedDocument } from "../api";

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
  tree,
  expandedIds,
  onToggle,
  onCreateChild,
  onCreateRoot,
  onDelete,
  onMove,
  sharedDocuments,
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

      <SharedDocuments documents={sharedDocuments} />

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
