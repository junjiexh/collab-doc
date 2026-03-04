const API_BASE = "/api";

export interface DocumentMeta {
  id: string;
  title: string;
  createdAt: string;
  updatedAt: string;
}

export async function listDocuments(): Promise<DocumentMeta[]> {
  const res = await fetch(`${API_BASE}/docs`);
  return res.json();
}

export async function createDocument(title: string): Promise<DocumentMeta> {
  const res = await fetch(`${API_BASE}/docs`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ title }),
  });
  return res.json();
}

export async function deleteDocument(id: string): Promise<void> {
  await fetch(`${API_BASE}/docs/${id}`, { method: "DELETE" });
}
