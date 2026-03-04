import { useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import { listDocuments, createDocument, deleteDocument, DocumentMeta } from "../api";

export default function DocumentList() {
  const [docs, setDocs] = useState<DocumentMeta[]>([]);
  const navigate = useNavigate();

  useEffect(() => {
    listDocuments().then(setDocs);
  }, []);

  const handleCreate = async () => {
    const doc = await createDocument("Untitled");
    navigate(`/doc/${doc.id}`);
  };

  const handleDelete = async (id: string) => {
    await deleteDocument(id);
    setDocs(docs.filter((d) => d.id !== id));
  };

  return (
    <div style={{ maxWidth: 800, margin: "0 auto", padding: 24 }}>
      <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center" }}>
        <h1>Documents</h1>
        <button onClick={handleCreate} style={{ padding: "8px 16px", fontSize: 16 }}>
          + New Document
        </button>
      </div>
      <div style={{ marginTop: 16 }}>
        {docs.length === 0 && <p>No documents yet. Create one to get started.</p>}
        {docs.map((doc) => (
          <div
            key={doc.id}
            style={{
              display: "flex",
              justifyContent: "space-between",
              padding: "12px 16px",
              borderBottom: "1px solid #eee",
              cursor: "pointer",
            }}
          >
            <div onClick={() => navigate(`/doc/${doc.id}`)}>
              <strong>{doc.title}</strong>
              <div style={{ fontSize: 12, color: "#888" }}>
                {new Date(doc.updatedAt).toLocaleString()}
              </div>
            </div>
            <button onClick={() => handleDelete(doc.id)} style={{ color: "red" }}>
              Delete
            </button>
          </div>
        ))}
      </div>
    </div>
  );
}
