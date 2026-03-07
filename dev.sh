#!/usr/bin/env bash
set -euo pipefail

# ── Project root (where this script lives) ──
ROOT="$(cd "$(dirname "$0")" && pwd)"

# ── Color helpers ──
info()  { printf '\033[1;34m[dev]\033[0m %s\n' "$*"; }
error() { printf '\033[1;31m[dev]\033[0m %s\n' "$*"; }

# ── Cleanup: kill child processes on exit ──
PIDS=()
cleanup() {
    info "Shutting down..."
    for pid in "${PIDS[@]}"; do
        kill "$pid" 2>/dev/null && wait "$pid" 2>/dev/null || true
    done
    info "Done."
}
trap cleanup EXIT INT TERM

# ── 1. Ensure Rust native library is built ──
info "Building yrs-bridge (release)..."
(cd "$ROOT/yrs-bridge" && cargo build --release)

# ── 2. Ensure frontend dependencies are installed ──
if [ ! -d "$ROOT/frontend/node_modules" ]; then
    info "Installing frontend dependencies..."
    (cd "$ROOT/frontend" && pnpm install)
fi

# ── 3. Start backend ──
info "Starting backend on :8080..."
(cd "$ROOT/backend" && ./gradlew bootRun --console=plain -q) &
PIDS+=($!)

# ── 4. Start frontend ──
info "Starting frontend on :3000..."
(cd "$ROOT/frontend" && pnpm dev) &
PIDS+=($!)

# ── 5. Wait ──
info "Backend  → http://localhost:8080"
info "Frontend → http://localhost:3000"
info "Press Ctrl+C to stop."
wait
