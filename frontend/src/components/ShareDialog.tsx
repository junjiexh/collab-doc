import { useState, useEffect } from "react";
import { listPermissions, addPermission, updatePermission, deletePermission, type PermissionEntry } from "../api";
import { theme } from "../theme";

interface ShareDialogProps {
  docId: string;
  onClose: () => void;
}

export default function ShareDialog({ docId, onClose }: ShareDialogProps) {
  const [permissions, setPermissions] = useState<PermissionEntry[]>([]);
  const [username, setUsername] = useState("");
  const [permission, setPermission] = useState<"VIEWER" | "EDITOR">("VIEWER");
  const [error, setError] = useState("");

  const refresh = async () => {
    const perms = await listPermissions(docId);
    setPermissions(perms);
  };

  useEffect(() => { refresh(); }, [docId]);

  const handleAdd = async () => {
    setError("");
    try {
      await addPermission(docId, username.trim(), permission);
      setUsername("");
      await refresh();
    } catch (e: any) {
      setError(e.message);
    }
  };

  const handleUpdate = async (permId: string, newPerm: "VIEWER" | "EDITOR") => {
    await updatePermission(docId, permId, newPerm);
    await refresh();
  };

  const handleDelete = async (permId: string) => {
    await deletePermission(docId, permId);
    await refresh();
  };

  const inputStyle = {
    padding: "6px 10px", border: `1px solid ${theme.border}`, borderRadius: theme.radius,
    fontSize: theme.bodyFontSize, fontFamily: theme.fontFamily, background: theme.contentBg, color: theme.textPrimary,
  };

  return (
    <div style={{
      position: "fixed", top: 0, left: 0, right: 0, bottom: 0,
      backgroundColor: theme.overlayBg, display: "flex", alignItems: "center", justifyContent: "center", zIndex: 1000,
    }} onClick={onClose}>
      <div style={{
        background: theme.contentBg, borderRadius: theme.cardRadius, padding: theme.dialogPad,
        minWidth: theme.dialogWidth, maxWidth: theme.dialogWidth,
        boxShadow: theme.dialogShadow, fontFamily: theme.fontFamily, color: theme.textPrimary,
      }} onClick={(e) => e.stopPropagation()}>
        <h3 style={{ margin: "0 0 16px", fontSize: 18 }}>Share Document</h3>

        <div style={{ display: "flex", gap: 8, marginBottom: 16 }}>
          <input value={username} onChange={(e) => setUsername(e.target.value)} placeholder="Username"
            style={{ ...inputStyle, flex: 1 }} onKeyDown={(e) => e.key === "Enter" && handleAdd()} />
          <select value={permission} onChange={(e) => setPermission(e.target.value as "VIEWER" | "EDITOR")}
            style={inputStyle}>
            <option value="VIEWER">只读权限</option>
            <option value="EDITOR">编辑权限</option>
          </select>
          <button onClick={handleAdd} style={{
            padding: theme.btnPadding, background: "transparent", color: theme.primary,
            border: `1px solid ${theme.primary}`, borderRadius: theme.btnRadius, cursor: "pointer",
            fontSize: theme.bodyFontSize, fontWeight: theme.btnWeight, fontFamily: theme.fontFamily,
          }}>
            Add
          </button>
        </div>

        {error && <div style={{ color: theme.error, fontSize: theme.smallFontSize, marginBottom: 12 }}>{error}</div>}

        {permissions.length === 0 ? (
          <div style={{ color: theme.textSecondary, fontSize: theme.smallFontSize }}>No one has access yet.</div>
        ) : (
          <div style={{ display: "flex", flexDirection: "column", gap: 8 }}>
            {permissions.map((p) => (
              <div key={p.id} style={{ display: "flex", alignItems: "center", justifyContent: "space-between", padding: "6px 0" }}>
                <span style={{ fontSize: theme.bodyFontSize }}>{p.username}</span>
                <div style={{ display: "flex", alignItems: "center", gap: 8 }}>
                  <select value={p.permission} onChange={(e) => handleUpdate(p.id, e.target.value as "VIEWER" | "EDITOR")}
                    style={{ ...inputStyle, padding: "4px 8px", fontSize: theme.smallFontSize }}>
                    <option value="VIEWER">只读权限</option>
                    <option value="EDITOR">编辑权限</option>
                  </select>
                  <button onClick={() => handleDelete(p.id)} style={{
                    background: "none", border: "none", color: theme.error, cursor: "pointer", fontSize: theme.smallFontSize,
                  }}>
                    Remove
                  </button>
                </div>
              </div>
            ))}
          </div>
        )}

        <div style={{ marginTop: 16, textAlign: "right" }}>
          <button onClick={onClose} style={{
            padding: theme.btnPadding, background: "none", border: `1px solid ${theme.border}`,
            borderRadius: theme.btnRadius, cursor: "pointer", fontSize: theme.bodyFontSize,
            color: theme.textSecondary, fontFamily: theme.fontFamily,
          }}>
            Close
          </button>
        </div>
      </div>
    </div>
  );
}
