import { useState, useEffect } from "react";
import {
  listPermissions,
  addPermission,
  updatePermission,
  deletePermission,
  type PermissionEntry,
} from "../api";

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

  return (
    <div style={{
      position: "fixed", top: 0, left: 0, right: 0, bottom: 0,
      backgroundColor: "rgba(0,0,0,0.3)", display: "flex", alignItems: "center", justifyContent: "center", zIndex: 1000,
    }} onClick={onClose}>
      <div style={{
        background: "#fff", borderRadius: 8, padding: 24, minWidth: 400, maxWidth: 500,
        boxShadow: "0 4px 24px rgba(0,0,0,0.15)",
      }} onClick={(e) => e.stopPropagation()}>
        <h3 style={{ margin: "0 0 16px", fontSize: 18 }}>Share Document</h3>

        <div style={{ display: "flex", gap: 8, marginBottom: 16 }}>
          <input
            value={username}
            onChange={(e) => setUsername(e.target.value)}
            placeholder="Username"
            style={{ flex: 1, padding: "6px 10px", border: "1px solid #ddd", borderRadius: 4, fontSize: 14 }}
            onKeyDown={(e) => e.key === "Enter" && handleAdd()}
          />
          <select
            value={permission}
            onChange={(e) => setPermission(e.target.value as "VIEWER" | "EDITOR")}
            style={{ padding: "6px 10px", border: "1px solid #ddd", borderRadius: 4, fontSize: 14 }}
          >
            <option value="VIEWER">只读权限</option>
            <option value="EDITOR">编辑权限</option>
          </select>
          <button onClick={handleAdd} style={{
            padding: "6px 16px", backgroundColor: "#2383e2", color: "#fff",
            border: "none", borderRadius: 4, cursor: "pointer", fontSize: 14,
          }}>
            Add
          </button>
        </div>

        {error && <div style={{ color: "#e53e3e", fontSize: 13, marginBottom: 12 }}>{error}</div>}

        {permissions.length === 0 ? (
          <div style={{ color: "#999", fontSize: 13 }}>No one has access yet.</div>
        ) : (
          <div style={{ display: "flex", flexDirection: "column", gap: 8 }}>
            {permissions.map((p) => (
              <div key={p.id} style={{ display: "flex", alignItems: "center", justifyContent: "space-between", padding: "6px 0" }}>
                <span style={{ fontSize: 14 }}>{p.username}</span>
                <div style={{ display: "flex", alignItems: "center", gap: 8 }}>
                  <select
                    value={p.permission}
                    onChange={(e) => handleUpdate(p.id, e.target.value as "VIEWER" | "EDITOR")}
                    style={{ padding: "4px 8px", border: "1px solid #ddd", borderRadius: 4, fontSize: 13 }}
                  >
                    <option value="VIEWER">只读权限</option>
                    <option value="EDITOR">编辑权限</option>
                  </select>
                  <button onClick={() => handleDelete(p.id)} style={{
                    background: "none", border: "none", color: "#e53e3e", cursor: "pointer", fontSize: 13,
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
            padding: "6px 16px", background: "none", border: "1px solid #ddd",
            borderRadius: 4, cursor: "pointer", fontSize: 14,
          }}>
            Close
          </button>
        </div>
      </div>
    </div>
  );
}
