# Backend Package Refactor Design

## Goal

Refactor backend from layer-based packaging (controller/service/model/repository) to domain-based packaging, following the existing auth/ package pattern.

## Target Structure

```
com.collabdoc
├── auth/                    (keep, absorb User/UserRepository)
│   ├── AuthController
│   ├── AuthService
│   ├── JwtAuthFilter
│   ├── JwtUtil
│   ├── User                 (from model/)
│   └── UserRepository       (from repository/)
├── document/                (new)
│   ├── Document             (from model/)
│   ├── DocumentRepository   (from repository/)
│   ├── DocumentController   (from controller/)
│   └── DocumentService      (from service/)
├── collab/                  (new, replaces websocket/)
│   ├── BlockController      (from controller/)
│   ├── YjsWebSocketHandler  (from websocket/)
│   ├── YjsSyncProtocol      (from websocket/)
│   ├── YrsDocumentManager   (from service/)
│   ├── DocumentSnapshot     (from model/)
│   ├── DocumentSnapshotRepository (from repository/)
│   ├── DocumentUpdate       (from model/)
│   └── DocumentUpdateRepository   (from repository/)
├── yrs/                     (keep as-is)
├── config/                  (keep as-is)
└── CollabDocApplication
```

## Packages to Delete

- controller/ (empty after move)
- service/ (empty after move)
- model/ (empty after move)
- repository/ (empty after move)
- websocket/ (empty after move)

## Cross-Package Dependencies

- collab/BlockController → document/DocumentService + collab/YrsDocumentManager + collab/YjsWebSocketHandler
- collab/YjsWebSocketHandler → document/DocumentService + collab/YrsDocumentManager
- config/WebSocketConfig → auth/JwtUtil + collab/* + document/DocumentService
- No circular dependencies
