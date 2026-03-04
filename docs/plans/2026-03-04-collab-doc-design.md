# CollabDoc - 协作文档应用设计文档

## 概述

构建一个类 Notion 的协作文档 Web 应用，支持实时多人协作编辑和 AI Agent 程序化访问。

## 需求

- **后端**: Java 25 + Spring Boot 3.4
- **协作算法**: CRDT (Yjs/Yrs)，非 OT
- **编辑器**: Notion 风格块编辑器
- **Agent 访问**: REST API 直接编辑文档，与人类用户实时协作
- **MVP 认证**: 无认证，匿名/随机用户名模式
- **部署**: Docker Compose 本地开发优先

## 技术栈

| 层 | 技术 | 说明 |
|---|---|---|
| 前端框架 | React 19 + TypeScript | SPA，Vite 构建 |
| 编辑器 | BlockNote | Notion 风格块编辑，内置 Yjs 协作 |
| CRDT 前端 | Yjs + y-websocket | 浏览器端 CRDT 引擎 |
| 后端框架 | Java 25 + Spring Boot 3.4 | WebSocket + REST API |
| CRDT 后端 | Yrs (Rust) via FFM API | 服务端 Yjs 文档操作 |
| 数据库 | PostgreSQL 17 | 文档持久化 |
| 本地开发 | Docker Compose | PostgreSQL 容器 |

## 架构

```
┌──────────────────────────────────────────────────────────┐
│                       Clients                            │
│                                                          │
│  ┌─────────────┐  ┌─────────────┐  ┌──────────────────┐ │
│  │  Browser 1   │  │  Browser 2   │  │   AI Agent       │ │
│  │  React +     │  │  React +     │  │  (REST API)      │ │
│  │  BlockNote + │  │  BlockNote + │  │                  │ │
│  │  Yjs         │  │  Yjs         │  └──────┬───────────┘ │
│  └──────┬───────┘  └──────┬───────┘         │             │
│         │ WebSocket        │ WebSocket       │ REST        │
└─────────┼──────────────────┼─────────────────┼────────────┘
          │                  │                 │
┌─────────▼──────────────────▼─────────────────▼────────────┐
│                  Java 25 Backend (Spring Boot 3)           │
│                                                            │
│  ┌────────────────────┐  ┌─────────────────────────────┐  │
│  │  WebSocket Handler  │  │  Agent REST Controller      │  │
│  │  (Yjs sync protocol)│  │  POST /api/docs/{id}/blocks │  │
│  └────────┬───────────┘  └──────────┬──────────────────┘  │
│           │                         │                      │
│  ┌────────▼─────────────────────────▼──────────────────┐  │
│  │              Yrs Bridge (FFM API → Rust Yrs)         │  │
│  │  - 维护每个文档的 Yjs Doc 实例                        │  │
│  │  - 浏览器 WebSocket 更新 ↔ Yrs Doc 同步              │  │
│  │  - Agent REST 操作 → Yrs Doc 变更 → 广播给浏览器      │  │
│  └────────────────────────┬────────────────────────────┘  │
│                           │                                │
│  ┌────────────────────────▼────────────────────────────┐  │
│  │              Document Service                        │  │
│  │  - 文档 CRUD                                         │  │
│  │  - Yjs 状态持久化 (二进制 snapshot + 增量更新)        │  │
│  └────────────────────────┬────────────────────────────┘  │
│                           │                                │
│  ┌────────────────────────▼────────────────────────────┐  │
│  │              PostgreSQL                              │  │
│  └─────────────────────────────────────────────────────┘  │
└────────────────────────────────────────────────────────────┘
```

## MVP Block 类型

| Block 类型 | 说明 |
|---|---|
| paragraph | 段落文本 |
| heading | 标题 (H1-H3) |
| bulletListItem | 无序列表项 |
| numberedListItem | 有序列表项 |
| codeBlock | 代码块 |
| blockquote | 引用 |
| divider | 分割线 |

## Block 数据结构

与 BlockNote 对齐：

```json
{
  "id": "block-uuid",
  "type": "paragraph",
  "props": {},
  "content": [
    { "type": "text", "text": "Hello ", "styles": {} },
    { "type": "text", "text": "world", "styles": { "bold": true } }
  ],
  "children": []
}
```

## 数据库设计

```sql
CREATE TABLE documents (
    id          UUID PRIMARY KEY,
    title       VARCHAR(500),
    created_at  TIMESTAMPTZ DEFAULT now(),
    updated_at  TIMESTAMPTZ DEFAULT now()
);

CREATE TABLE document_updates (
    id          BIGSERIAL PRIMARY KEY,
    doc_id      UUID REFERENCES documents(id),
    update_data BYTEA NOT NULL,
    created_at  TIMESTAMPTZ DEFAULT now()
);

CREATE TABLE document_snapshots (
    doc_id      UUID PRIMARY KEY REFERENCES documents(id),
    state_data  BYTEA NOT NULL,
    updated_at  TIMESTAMPTZ DEFAULT now()
);
```

## Agent REST API

```
GET    /api/docs                                -- 列出所有文档
POST   /api/docs                                -- 创建文档
GET    /api/docs/{id}                           -- 获取文档元数据
GET    /api/docs/{id}/blocks                    -- 获取所有 blocks (JSON)
POST   /api/docs/{id}/blocks                    -- 插入 block
PUT    /api/docs/{id}/blocks/{blockId}          -- 更新 block
DELETE /api/docs/{id}/blocks/{blockId}          -- 删除 block
PATCH  /api/docs/{id}/blocks/{blockId}/content  -- 更新 block 内容
```

## 协作数据流

**人类用户编辑**:
1. Browser 中 BlockNote/Yjs 生成 binary update
2. 通过 WebSocket 发送到 Java 后端
3. Yrs Bridge 应用 update 到服务端 YDoc
4. 持久化 update 到 PostgreSQL
5. 广播 update 给同文档的其他 WebSocket 连接

**Agent 编辑**:
1. Agent 调用 REST API (如 POST /api/docs/{id}/blocks)
2. Java Controller 接收请求
3. Yrs Bridge 操作服务端 YDoc，生成 Yjs update
4. 持久化 update 到 PostgreSQL
5. 通过 WebSocket 广播给所有浏览器客户端

## Yrs FFM Bridge

Rust thin wrapper library (`yrs-bridge/`):
- 编译为动态库 (.dylib / .so)
- 暴露 C ABI 函数供 Java FFM 调用
- 核心操作: create_doc, apply_update, insert_text, delete_text, encode_state, get_blocks

Java 侧通过 `java.lang.foreign` API 加载并调用。

## 项目结构

```
collab-doc-new/
├── frontend/                 -- React + BlockNote
│   ├── package.json
│   ├── vite.config.ts
│   └── src/
│       ├── App.tsx
│       ├── components/
│       │   ├── Editor.tsx
│       │   ├── CollaborationProvider.tsx
│       │   ├── DocumentList.tsx
│       │   └── UserCursors.tsx
│       ├── hooks/
│       │   ├── useYjsProvider.ts
│       │   └── useDocument.ts
│       └── types/
│           └── block.ts
├── backend/                  -- Java 25 + Spring Boot
│   ├── build.gradle.kts
│   └── src/main/java/
│       └── com/collabdoc/
│           ├── CollabDocApplication.java
│           ├── controller/
│           │   ├── DocumentController.java
│           │   ├── BlockController.java
│           │   └── YjsWebSocketHandler.java
│           ├── service/
│           │   ├── DocumentService.java
│           │   └── YrsDocumentManager.java
│           ├── repository/
│           │   ├── DocumentRepository.java
│           │   └── DocumentUpdateRepository.java
│           ├── yrs/
│           │   └── YrsBridge.java
│           └── model/
│               ├── Document.java
│               ├── DocumentUpdate.java
│               └── Block.java
├── yrs-bridge/               -- Rust thin wrapper
│   ├── Cargo.toml
│   └── src/lib.rs
├── docker-compose.yml
└── docs/plans/
```

## 非功能性要求 (MVP)

- 单服务器部署，不考虑水平扩展
- 文档数量 < 1000，并发用户 < 50
- 无认证，匿名访问
- 基本错误处理，无监控
