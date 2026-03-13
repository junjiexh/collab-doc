import { useNavigate, useParams } from "react-router-dom";
import type { SharedDocument } from "../api";
import { theme } from "../theme";

interface SharedDocumentsProps {
  documents: SharedDocument[];
}

export default function SharedDocuments({ documents }: SharedDocumentsProps) {
  const navigate = useNavigate();
  const { docId } = useParams<{ docId: string }>();

  if (documents.length === 0) return null;

  return (
    <>
      <div style={{
        padding: "16px 12px 8px", fontWeight: 600, fontSize: theme.smallFontSize,
        color: theme.textSecondary, textTransform: "uppercase", letterSpacing: "0.05em",
      }}>
        Shared with me
      </div>
      <div style={{ padding: "0 4px" }}>
        {documents.map((doc) => (
          <div key={doc.id} onClick={() => navigate(`/doc/${doc.id}`)}
            style={{
              display: "flex", alignItems: "center", justifyContent: "space-between",
              padding: "4px 8px", cursor: "pointer", borderRadius: theme.radius,
              backgroundColor: docId === doc.id ? theme.activeBg : "transparent",
              fontSize: theme.sidebarFontSize,
            }}
            onMouseEnter={(e) => { if (docId !== doc.id) e.currentTarget.style.backgroundColor = theme.hoverBg; }}
            onMouseLeave={(e) => { if (docId !== doc.id) e.currentTarget.style.backgroundColor = "transparent"; }}
          >
            <span style={{ overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap", flex: 1 }}>
              {doc.title || "Untitled"}
            </span>
            <span style={{ fontSize: 11, color: theme.textSecondary, marginLeft: 8, flexShrink: 0 }}>
              {doc.permission === "EDITOR" ? "编辑权限" : "只读权限"}
            </span>
          </div>
        ))}
      </div>
    </>
  );
}
