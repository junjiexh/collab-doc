# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Real-time collaborative document editor with Yjs CRDT sync. Three modules: Spring Boot backend, React frontend, Rust native bridge.

## Build & Run Commands

### Prerequisites
- Docker (for PostgreSQL): `docker compose up -d`
- Rust toolchain (for yrs-bridge)

### yrs-bridge (Rust cdylib)
```bash
cd yrs-bridge && cargo build --release
# Produces target/release/libyrs_bridge.dylib (macOS)
```

### Backend (Spring Boot, Java 25)
```bash
cd backend
./gradlew bootRun          # Run server on :8080 (requires --enable-preview, configured in build.gradle.kts)
./gradlew test             # Run all tests
./gradlew test --tests "com.collabdoc.auth.AuthServiceTest"  # Single test class
```
- Requires `--enable-native-access=ALL-UNNAMED` JVM flag (configured in build.gradle.kts)
- Native lib path default: `../yrs-bridge/target/release/libyrs_bridge.dylib` (override: `YRS_BRIDGE_LIB_PATH` env var)

### Frontend (React 19 + Vite)
```bash
cd frontend
npm install
npm run dev                # Vite dev server on :3000
npm run build              # Production build
```

### E2E Tests (Playwright)
```bash
cd frontend
npx playwright test                              # All tests
npx playwright test e2e/document-sharing.spec.ts # Single spec
npx playwright test --headed                     # With browser UI
```
- Requires both backend (:8080) and frontend (:3000) running
- Setup project in `e2e/auth.setup.ts` registers a test user, saves storageState for other specs

## Architecture

### Data Flow: Real-time Sync
```
Browser (Yjs Y.Doc) ←WebSocket (binary y-protocol)→ YjsWebSocketHandler
                                                        ↓
                                                   YrsDocumentManager (in-memory docs, synchronized access)
                                                        ↓
                                                   YrsDocument (Java FFM wrapper)
                                                        ↓
                                                   libyrs_bridge.dylib (Rust, yrs 0.21 CRDT)
                                                        ↓
                                                   PostgreSQL (snapshots + incremental updates)
```

### Backend Package Structure (`com.collabdoc`)
- `auth` — JWT cookie auth (7-day expiry, filter-based), BCrypt passwords
- `document` — Document CRUD with tree hierarchy (parentId + sortOrder)
- `permission` — OWNER/EDITOR/VIEWER model; child docs inherit parent permissions
- `collab` — WebSocket sync handler, BlockController (REST Agent API), Yrs document management
- `yrs` — Java FFM (Foreign Function & Memory) bridge to Rust native library
- `config` — Spring Security, CORS, WebSocket registration

### WebSocket Protocol (`/ws/{docId}`)
- Binary y-websocket protocol: MSG_SYNC (0) with subtypes STEP1/STEP2/UPDATE, MSG_AWARENESS (1)
- JWT extracted from cookie during handshake
- Permission enforced: VIEWER receives updates only, EDITOR/OWNER can send updates
- Updates broadcast to all sessions in the same document "room"

### yrs-bridge (Rust)
- Wraps `yrs::Doc` with C ABI functions called via Java FFM
- Document structure: XmlFragment "document-store" → blockGroup → blockContainer → blockType → XmlText (BlockNote convention)
- Memory: caller must free returned bytes/strings via `yrs_free_bytes()`/`yrs_free_string()`

### Frontend Key Files
- `src/hooks/useCollaboration.ts` — Creates Yjs doc + WebsocketProvider, manages awareness (cursor colors)
- `src/components/CollaborativeEditor.tsx` — BlockNote integration with Yjs collaboration config
- `src/components/EditorPage.tsx` — Document page with editable title, permission-aware UI
- `src/components/Sidebar.tsx` — Document tree with drag-drop (@dnd-kit), shared docs section
- `src/api.ts` — Centralized fetch client with cookie credentials
- `src/contexts/AuthContext.tsx` — Auth state management

### Database
- PostgreSQL 17 via Docker Compose (db: collabdoc, user: collabdoc, password: collabdoc)
- Flyway migrations V1-V4 in `backend/src/main/resources/db/migration/`
- Tables: users, documents, document_permissions, document_snapshots, document_updates

## Key Conventions
- Backend uses Java 25 preview features (FFM API)
- All REST APIs under `/api/`, auth uses HTTP-only cookie named "token"
- Frontend uses Chinese locale in some UI strings (e.g., "你自己" for same-user cursors)
- Permission hierarchy: OWNER > EDITOR > VIEWER, with recursive inheritance for child documents
