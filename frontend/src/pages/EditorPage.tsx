import { useParams } from "react-router-dom";

export default function EditorPage() {
  const { docId } = useParams<{ docId: string }>();
  return (
    <div style={{ maxWidth: 900, margin: "0 auto", padding: 24 }}>
      <h2>Editor: {docId}</h2>
      <p>BlockNote editor will go here (Task 9)</p>
    </div>
  );
}
