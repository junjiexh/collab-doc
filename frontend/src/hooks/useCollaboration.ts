import { useEffect, useState, useRef } from "react";
import * as Y from "yjs";
import { WebsocketProvider } from "y-websocket";

const COLORS = [
  "#FF6B6B",
  "#4ECDC4",
  "#45B7D1",
  "#96CEB4",
  "#FFEAA7",
  "#DDA0DD",
  "#98D8C8",
  "#F7DC6F",
];

interface CollabState {
  doc: Y.Doc;
  provider: WebsocketProvider;
  fragment: Y.XmlFragment;
}

export function useCollaboration(docId: string, username: string, userId: string) {
  const [synced, setSynced] = useState(false);
  const [collab, setCollab] = useState<CollabState | null>(null);
  const userRef = useRef({
    name: username,
    color: COLORS[Math.floor(Math.random() * COLORS.length)],
    userId,
  });

  useEffect(() => {
    const doc = new Y.Doc();
    const wsUrl = `${window.location.protocol === "https:" ? "wss:" : "ws:"}//${window.location.host}/ws`;
    const provider = new WebsocketProvider(wsUrl, docId, doc);
    const fragment = doc.getXmlFragment("document-store");

    // Rename same-user cursors from other tabs to "你自己"
    const onAwarenessChange = () => {
      const states = provider.awareness.states;
      const myClientId = provider.awareness.clientID;
      for (const [clientId, state] of states) {
        if (
          clientId !== myClientId &&
          state.user?.userId === userId &&
          state.user.name !== "你自己"
        ) {
          state.user = { ...state.user, name: "你自己" };
        }
      }
    };
    provider.awareness.on("change", onAwarenessChange);

    const onSync = (isSynced: boolean) => setSynced(isSynced);
    provider.on("sync", onSync);

    setCollab({ doc, provider, fragment });

    return () => {
      provider.awareness.off("change", onAwarenessChange);
      provider.off("sync", onSync);
      provider.destroy();
      doc.destroy();
      setCollab(null);
      setSynced(false);
    };
  }, [docId, userId]);

  return {
    provider: collab?.provider ?? null,
    fragment: collab?.fragment ?? null,
    user: userRef.current,
    synced,
  };
}
