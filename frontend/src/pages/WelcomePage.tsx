import { theme } from "../theme";

export default function WelcomePage() {
  return (
    <div style={{
      display: "flex", justifyContent: "center", alignItems: "center", height: "100%",
      color: theme.textSecondary, fontSize: theme.bodyFontSize + 1, fontFamily: theme.fontFamily,
    }}>
      Select a document or create a new one
    </div>
  );
}
