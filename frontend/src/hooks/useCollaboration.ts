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

function randomName(): string {
  const adjectives = ["Swift", "Calm", "Bold", "Keen", "Warm"];
  const nouns = ["Fox", "Owl", "Bear", "Wolf", "Deer"];
  return `${adjectives[Math.floor(Math.random() * adjectives.length)]} ${nouns[Math.floor(Math.random() * nouns.length)]}`;
}

interface CollabState {
  doc: Y.Doc;
  provider: WebsocketProvider;
  fragment: Y.XmlFragment;
}

export function useCollaboration(docId: string) {
  const [synced, setSynced] = useState(false);
  const [collab, setCollab] = useState<CollabState | null>(null);
  const userRef = useRef({
    name: randomName(),
    color: COLORS[Math.floor(Math.random() * COLORS.length)],
  });

  useEffect(() => {
    const doc = new Y.Doc();
    const wsUrl = `${window.location.protocol === "https:" ? "wss:" : "ws:"}//${window.location.host}/ws`;
    const provider = new WebsocketProvider(wsUrl, docId, doc);
    const fragment = doc.getXmlFragment("document-store");

    const onSync = (isSynced: boolean) => setSynced(isSynced);
    provider.on("sync", onSync);

    setCollab({ doc, provider, fragment });

    return () => {
      provider.off("sync", onSync);
      provider.destroy();
      doc.destroy();
      setCollab(null);
      setSynced(false);
    };
  }, [docId]);

  return {
    provider: collab?.provider ?? null,
    fragment: collab?.fragment ?? null,
    user: userRef.current,
    synced,
  };
}
