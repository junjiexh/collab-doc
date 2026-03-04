import { useParams } from "react-router-dom";
import CollaborativeEditor from "../components/CollaborativeEditor";

export default function EditorPage() {
  const { docId } = useParams<{ docId: string }>();

  if (!docId) return <p>No document ID</p>;

  return (
    <div style={{ maxWidth: 900, margin: "0 auto", padding: 24 }}>
      <CollaborativeEditor docId={docId} />
    </div>
  );
}
