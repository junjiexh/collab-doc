#!/usr/bin/env bash
set -euo pipefail

# ── Project root (where this script lives) ──
ROOT="$(cd "$(dirname "$0")" && pwd)"

# ── Color helpers ──
info()  { printf '\033[1;34m[dev]\033[0m %s\n' "$*"; }
error() { printf '\033[1;31m[dev]\033[0m %s\n' "$*"; }

# ── Cleanup: stop Docker on exit ──
cleanup() {
    info "Stopping Docker containers..."
    docker compose -f "$ROOT/docker-compose.yml" down
    info "Done."
}
trap cleanup EXIT INT TERM

# ── 0. Start Docker (PostgreSQL, Redis) ──
info "Starting Docker containers..."
docker compose -f "$ROOT/docker-compose.yml" up -d postgres redis

info "Waiting for PostgreSQL to be ready..."
until docker compose -f "$ROOT/docker-compose.yml" exec -T postgres pg_isready -U collabdoc -q 2>/dev/null; do
    sleep 1
done
info "PostgreSQL is ready."

# ── 1. Ensure Rust native library is built ──
info "Building yrs-bridge (release)..."
(cd "$ROOT/yrs-bridge" && cargo build --release)

# ── 2. Ensure frontend dependencies are installed ──
if [ ! -d "$ROOT/frontend/node_modules" ]; then
    info "Installing frontend dependencies..."
    (cd "$ROOT/frontend" && pnpm install)
fi

# ── 3. Start backend + frontend concurrently ──
info "Starting backend and frontend..."
npx concurrently \
    --kill-others \
    --names "backend,frontend" \
    --prefix-colors "blue,green" \
    "cd '$ROOT/backend' && ./gradlew bootRun --console=plain -q" \
    "cd '$ROOT/frontend' && pnpm dev"
