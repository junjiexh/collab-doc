import "@blocknote/mantine/style.css";
import { useCreateBlockNote } from "@blocknote/react";
import { BlockNoteView } from "@blocknote/mantine";
import { WebsocketProvider } from "y-websocket";
import * as Y from "yjs";
import { useCollaboration } from "../hooks/useCollaboration";

interface CollaborativeEditorProps {
  docId: string;
}

export default function CollaborativeEditor({
  docId,
}: CollaborativeEditorProps) {
  const { provider, fragment, user, synced } = useCollaboration(docId);

  if (!synced || !provider || !fragment) {
    return <div style={{ padding: 24, color: "#888" }}>Connecting...</div>;
  }

  return (
    <BlockNoteEditor provider={provider} fragment={fragment} user={user} />
  );
}

function BlockNoteEditor({
  provider,
  fragment,
  user,
}: {
  provider: WebsocketProvider;
  fragment: Y.XmlFragment;
  user: { name: string; color: string };
}) {
  const editor = useCreateBlockNote({
    collaboration: {
      provider,
      fragment,
      user,
    },
  });

  return (
    <div style={{ minHeight: "70vh" }}>
      <BlockNoteView editor={editor} />
    </div>
  );
}
