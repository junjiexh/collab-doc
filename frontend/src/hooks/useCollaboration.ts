import { useMemo, useEffect, useState } from "react";
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

export function useCollaboration(docId: string) {
  const [synced, setSynced] = useState(false);

  const { doc, provider, fragment, user } = useMemo(() => {
    const doc = new Y.Doc();
    const wsUrl = `${window.location.protocol === "https:" ? "wss:" : "ws:"}//${window.location.host}/ws`;
    const provider = new WebsocketProvider(wsUrl, docId, doc);
    const fragment = doc.getXmlFragment("document-store");
    const user = {
      name: randomName(),
      color: COLORS[Math.floor(Math.random() * COLORS.length)],
    };
    return { doc, provider, fragment, user };
  }, [docId]);

  useEffect(() => {
    const onSync = (isSynced: boolean) => setSynced(isSynced);
    provider.on("sync", onSync);
    return () => {
      provider.off("sync", onSync);
      provider.destroy();
      doc.destroy();
    };
  }, [provider, doc]);

  return { doc, provider, fragment, user, synced };
}
