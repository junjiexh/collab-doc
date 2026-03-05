import { useState, type FormEvent } from "react";
import { useNavigate } from "react-router-dom";
import { useAuth } from "../contexts/AuthContext";

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
    <div style={{ display: "flex", justifyContent: "center", alignItems: "center", height: "100vh", background: "#fafafa" }}>
      <form onSubmit={onSubmit} style={{
        background: "#fff", padding: 32, borderRadius: 8,
        boxShadow: "0 2px 8px rgba(0,0,0,0.1)", width: 320,
      }}>
        <h2 style={{ marginTop: 0, marginBottom: 24 }}>CollabDoc</h2>
        {error && <div style={{ color: "#e53e3e", marginBottom: 12, fontSize: 14 }}>{error}</div>}
        <input
          type="text"
          placeholder="Username"
          value={username}
          onChange={(e) => setUsername(e.target.value)}
          required
          style={{ width: "100%", padding: 8, marginBottom: 12, borderRadius: 4, border: "1px solid #ddd", boxSizing: "border-box" }}
        />
        <input
          type="password"
          placeholder="Password"
          value={password}
          onChange={(e) => setPassword(e.target.value)}
          required
          minLength={4}
          style={{ width: "100%", padding: 8, marginBottom: 16, borderRadius: 4, border: "1px solid #ddd", boxSizing: "border-box" }}
        />
        <button
          type="submit"
          disabled={isSubmitting}
          style={{ width: "100%", padding: 10, background: "#2563eb", color: "#fff", border: "none", borderRadius: 4, cursor: "pointer", marginBottom: 8 }}
        >
          Log in
        </button>
        <button
          type="button"
          disabled={isSubmitting}
          onClick={() => handleSubmit("register")}
          style={{ width: "100%", padding: 10, background: "#f3f4f6", color: "#333", border: "1px solid #ddd", borderRadius: 4, cursor: "pointer" }}
        >
          Register
        </button>
      </form>
    </div>
  );
}
