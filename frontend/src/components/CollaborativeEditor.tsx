import "@blocknote/mantine/style.css";
import { useCreateBlockNote } from "@blocknote/react";
import { BlockNoteView } from "@blocknote/mantine";
import { WebsocketProvider } from "y-websocket";
import * as Y from "yjs";
import { useCollaboration } from "../hooks/useCollaboration";

interface CollaborativeEditorProps {
  docId: string;
  username: string;
  userId: string;
  editable: boolean;
}

export default function CollaborativeEditor({
  docId,
  username,
  userId,
  editable,
}: CollaborativeEditorProps) {
  const { provider, fragment, user, synced } = useCollaboration(docId, username, userId);

  if (!synced || !provider || !fragment) {
    return <div style={{ padding: 24, color: "#888" }}>Connecting...</div>;
  }

  return (
    <BlockNoteEditor provider={provider} fragment={fragment} user={user} editable={editable} />
  );
}

function BlockNoteEditor({
  provider,
  fragment,
  user,
  editable,
}: {
  provider: WebsocketProvider;
  fragment: Y.XmlFragment;
  user: { name: string; color: string };
  editable: boolean;
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
      <BlockNoteView editor={editor} editable={editable} />
    </div>
  );
}
