import { useState, type FormEvent } from "react";
import { useNavigate } from "react-router-dom";
import { useAuth } from "../contexts/AuthContext";
import { theme } from "../theme";

export default function LoginPage() {
  const { login, register } = useAuth();
  const navigate = useNavigate();
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState("");
  const [isSubmitting, setIsSubmitting] = useState(false);

  const handleSubmit = async (action: "login" | "register") => {
    setError("");
    setIsSubmitting(true);
    try {
      if (action === "register") {
        await register(username, password);
      } else {
        await login(username, password);
      }
      navigate("/");
    } catch (e: any) {
      setError(e.message || "Something went wrong");
    } finally {
      setIsSubmitting(false);
    }
  };

  const onSubmit = (e: FormEvent) => {
    e.preventDefault();
    handleSubmit("login");
  };

  return (
    <div style={{ display: "flex", justifyContent: "center", alignItems: "center", height: "100vh", background: theme.loginBg, fontFamily: theme.fontFamily }}>
      <form onSubmit={onSubmit} style={{
        background: theme.contentBg, padding: 32, borderRadius: theme.cardRadius,
        boxShadow: theme.shadow, width: 320,
      }}>
        <h2 style={{ marginTop: 0, marginBottom: 24, color: theme.textPrimary }}>CollabDoc</h2>
        {error && <div style={{ color: theme.error, marginBottom: 12, fontSize: theme.bodyFontSize }}>{error}</div>}
        <input
          type="text" placeholder="Username" value={username}
          onChange={(e) => setUsername(e.target.value)} required
          style={{ width: "100%", padding: 8, marginBottom: 12, borderRadius: theme.radius, border: `1px solid ${theme.border}`, boxSizing: "border-box", fontSize: theme.bodyFontSize, fontFamily: theme.fontFamily }}
        />
        <input
          type="password" placeholder="Password" value={password}
          onChange={(e) => setPassword(e.target.value)} required minLength={4}
          style={{ width: "100%", padding: 8, marginBottom: 16, borderRadius: theme.radius, border: `1px solid ${theme.border}`, boxSizing: "border-box", fontSize: theme.bodyFontSize, fontFamily: theme.fontFamily }}
        />
        <button type="submit" disabled={isSubmitting} style={{
          width: "100%", padding: 10, background: theme.primary, color: theme.primaryText,
          border: "none", borderRadius: theme.btnRadius, cursor: "pointer", marginBottom: 8,
          fontWeight: theme.btnWeight, fontSize: theme.bodyFontSize, fontFamily: theme.fontFamily,
        }}>
          Log in
        </button>
        <button type="button" disabled={isSubmitting} onClick={() => handleSubmit("register")} style={{
          width: "100%", padding: 10, background: "transparent", color: theme.primary,
          border: `1px solid ${theme.primary}`, borderRadius: theme.btnRadius, cursor: "pointer",
          fontWeight: theme.btnWeight, fontSize: theme.bodyFontSize, fontFamily: theme.fontFamily,
        }}>
          Register
        </button>
      </form>
    </div>
  );
}
