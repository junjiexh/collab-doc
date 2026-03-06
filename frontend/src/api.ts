const API_BASE = "/api";

export interface DocumentMeta {
  id: string;
  title: string;
  parentId: string | null;
  sortOrder: number;
  createdAt: string;
  updatedAt: string;
  permission?: string;
}

export interface AuthUser {
  id: string;
  username: string;
}

// --- Auth API ---

export async function authRegister(username: string, password: string): Promise<AuthUser> {
  const res = await fetch(`${API_BASE}/auth/register`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    credentials: "include",
    body: JSON.stringify({ username, password }),
  });
  if (!res.ok) {
    const data = await res.json();
    throw new Error(data.error || "Registration failed");
  }
  return res.json();
}

export async function authLogin(username: string, password: string): Promise<AuthUser> {
  const res = await fetch(`${API_BASE}/auth/login`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    credentials: "include",
    body: JSON.stringify({ username, password }),
  });
  if (!res.ok) {
    const data = await res.json();
    throw new Error(data.error || "Login failed");
  }
  return res.json();
}

export async function authLogout(): Promise<void> {
  await fetch(`${API_BASE}/auth/logout`, {
    method: "POST",
    credentials: "include",
  });
}

export async function authMe(): Promise<AuthUser | null> {
  const res = await fetch(`${API_BASE}/auth/me`, { credentials: "include" });
  if (!res.ok) return null;
  return res.json();
}

// --- Document API ---

export async function listDocuments(): Promise<DocumentMeta[]> {
  const res = await fetch(`${API_BASE}/docs`, { credentials: "include" });
  return res.json();
}

export async function createDocument(title: string, parentId?: string): Promise<DocumentMeta> {
  const res = await fetch(`${API_BASE}/docs`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    credentials: "include",
    body: JSON.stringify({ title, parentId: parentId ?? null }),
  });
  return res.json();
}

export async function deleteDocument(id: string): Promise<void> {
  await fetch(`${API_BASE}/docs/${id}`, { method: "DELETE", credentials: "include" });
}

export async function fetchDocumentTree(): Promise<DocumentMeta[]> {
  const res = await fetch(`${API_BASE}/docs/tree`, { credentials: "include" });
  return res.json();
}

export async function renameDocument(id: string, title: string): Promise<DocumentMeta> {
  const res = await fetch(`${API_BASE}/docs/${id}`, {
    method: "PUT",
    headers: { "Content-Type": "application/json" },
    credentials: "include",
    body: JSON.stringify({ title }),
  });
  return res.json();
}

export async function moveDocument(id: string, parentId: string | null, sortOrder: number): Promise<DocumentMeta> {
  const res = await fetch(`${API_BASE}/docs/${id}/move`, {
    method: "PUT",
    headers: { "Content-Type": "application/json" },
    credentials: "include",
    body: JSON.stringify({ parentId, sortOrder }),
  });
  return res.json();
}

// --- Permission API ---

export interface PermissionEntry {
  id: string;
  userId: string;
  username: string;
  permission: "VIEWER" | "EDITOR";
}

export interface SharedDocument {
  id: string;
  title: string;
  permission: string;
}

export async function listPermissions(docId: string): Promise<PermissionEntry[]> {
  const res = await fetch(`${API_BASE}/docs/${docId}/permissions`, { credentials: "include" });
  if (!res.ok) throw new Error("Failed to list permissions");
  return res.json();
}

export async function addPermission(docId: string, username: string, permission: "VIEWER" | "EDITOR"): Promise<PermissionEntry> {
  const res = await fetch(`${API_BASE}/docs/${docId}/permissions`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    credentials: "include",
    body: JSON.stringify({ username, permission }),
  });
  if (!res.ok) {
    const data = await res.json();
    throw new Error(data.error || "Failed to add permission");
  }
  return res.json();
}

export async function updatePermission(docId: string, permissionId: string, permission: "VIEWER" | "EDITOR"): Promise<PermissionEntry> {
  const res = await fetch(`${API_BASE}/docs/${docId}/permissions/${permissionId}`, {
    method: "PUT",
    headers: { "Content-Type": "application/json" },
    credentials: "include",
    body: JSON.stringify({ permission }),
  });
  if (!res.ok) throw new Error("Failed to update permission");
  return res.json();
}

export async function deletePermission(docId: string, permissionId: string): Promise<void> {
  await fetch(`${API_BASE}/docs/${docId}/permissions/${permissionId}`, {
    method: "DELETE",
    credentials: "include",
  });
}

export async function fetchSharedWithMe(): Promise<SharedDocument[]> {
  const res = await fetch(`${API_BASE}/docs/shared-with-me`, { credentials: "include" });
  if (!res.ok) throw new Error("Failed to fetch shared documents");
  return res.json();
}
