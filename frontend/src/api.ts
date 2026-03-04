const API_BASE = "/api";

export interface DocumentMeta {
  id: string;
  title: string;
  parentId: string | null;
  sortOrder: number;
  createdAt: string;
  updatedAt: string;
}

export async function listDocuments(): Promise<DocumentMeta[]> {
  const res = await fetch(`${API_BASE}/docs`);
  return res.json();
}

export async function createDocument(title: string, parentId?: string): Promise<DocumentMeta> {
  const res = await fetch(`${API_BASE}/docs`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ title, parentId: parentId ?? null }),
  });
  return res.json();
}

export async function deleteDocument(id: string): Promise<void> {
  await fetch(`${API_BASE}/docs/${id}`, { method: "DELETE" });
}

export async function fetchDocumentTree(): Promise<DocumentMeta[]> {
  const res = await fetch(`${API_BASE}/docs/tree`);
  return res.json();
}

export async function moveDocument(id: string, parentId: string | null, sortOrder: number): Promise<DocumentMeta> {
  const res = await fetch(`${API_BASE}/docs/${id}/move`, {
    method: "PUT",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ parentId, sortOrder }),
  });
  return res.json();
}
