import "@blocknote/mantine/style.css";
import { useCreateBlockNote } from "@blocknote/react";
import { BlockNoteView } from "@blocknote/mantine";
import { useCollaboration } from "../hooks/useCollaboration";

interface CollaborativeEditorProps {
  docId: string;
}

export default function CollaborativeEditor({
  docId,
}: CollaborativeEditorProps) {
  const { provider, fragment, user, synced } = useCollaboration(docId);

  const editor = useCreateBlockNote({
    collaboration: {
      provider,
      fragment,
      user,
    },
  });

  if (!synced) {
    return <div style={{ padding: 24, color: "#888" }}>Connecting...</div>;
  }

  return (
    <div style={{ minHeight: "70vh" }}>
      <BlockNoteView editor={editor} />
    </div>
  );
}
