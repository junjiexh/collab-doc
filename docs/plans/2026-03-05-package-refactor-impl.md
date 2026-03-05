# Backend Package Refactor Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Refactor backend from layer-based packaging to domain-based packaging following the auth/ pattern.

**Architecture:** Move Java files into domain packages (auth/, document/, collab/), update all package declarations and imports, delete empty old packages. Each task moves one domain, followed by a compile+test verification.

**Tech Stack:** Java 25, Spring Boot 3.5, Gradle

---

### Task 1: Move User and UserRepository into auth/

**Files:**
- Move: `backend/src/main/java/com/collabdoc/model/User.java` → `backend/src/main/java/com/collabdoc/auth/User.java`
- Move: `backend/src/main/java/com/collabdoc/repository/UserRepository.java` → `backend/src/main/java/com/collabdoc/auth/UserRepository.java`
- Modify: `backend/src/main/java/com/collabdoc/auth/AuthService.java` (update imports)
- Modify: `backend/src/main/java/com/collabdoc/auth/AuthController.java` (update imports)

**Step 1: Move User.java**

Copy `backend/src/main/java/com/collabdoc/model/User.java` to `backend/src/main/java/com/collabdoc/auth/User.java`. Change the package declaration:

```java
package com.collabdoc.auth;
```

Delete the original file.

**Step 2: Move UserRepository.java**

Copy `backend/src/main/java/com/collabdoc/repository/UserRepository.java` to `backend/src/main/java/com/collabdoc/auth/UserRepository.java`. Change the package declaration and update import:

```java
package com.collabdoc.auth;
```

Remove the `import com.collabdoc.model.User;` line (now same package).

**Step 3: Update AuthService.java imports**

Remove:
```java
import com.collabdoc.model.User;
import com.collabdoc.repository.UserRepository;
```
These are now in the same package.

**Step 4: Update AuthController.java imports**

Remove:
```java
import com.collabdoc.model.User;
```
Now in the same package.

**Step 5: Verify compilation**

Run: `cd backend && ./gradlew compileJava`
Expected: BUILD SUCCESSFUL

**Step 6: Run tests**

Run: `cd backend && ./gradlew test`
Expected: All tests pass (YrsBridgeTest should still pass as it doesn't touch auth)

**Step 7: Commit**

```bash
git add -A backend/src/main/java/com/collabdoc/auth/ backend/src/main/java/com/collabdoc/model/User.java backend/src/main/java/com/collabdoc/repository/UserRepository.java
git commit -m "refactor: move User and UserRepository into auth package"
```

---

### Task 2: Create document/ package

**Files:**
- Move: `backend/src/main/java/com/collabdoc/model/Document.java` → `backend/src/main/java/com/collabdoc/document/Document.java`
- Move: `backend/src/main/java/com/collabdoc/repository/DocumentRepository.java` → `backend/src/main/java/com/collabdoc/document/DocumentRepository.java`
- Move: `backend/src/main/java/com/collabdoc/controller/DocumentController.java` → `backend/src/main/java/com/collabdoc/document/DocumentController.java`
- Move: `backend/src/main/java/com/collabdoc/service/DocumentService.java` → `backend/src/main/java/com/collabdoc/document/DocumentService.java`
- Modify: any files that import from the old locations

**Step 1: Move Document.java**

Copy to `backend/src/main/java/com/collabdoc/document/Document.java`, change package:

```java
package com.collabdoc.document;
```

Delete original.

**Step 2: Move DocumentRepository.java**

Copy to `backend/src/main/java/com/collabdoc/document/DocumentRepository.java`, change package and remove old import:

```java
package com.collabdoc.document;
```

Remove `import com.collabdoc.model.Document;` (same package now).

**Step 3: Move DocumentService.java**

Copy to `backend/src/main/java/com/collabdoc/document/DocumentService.java`, change package and update imports:

```java
package com.collabdoc.document;
```

Remove:
```java
import com.collabdoc.model.Document;
import com.collabdoc.repository.DocumentRepository;
```
(Same package now.)

**Step 4: Move DocumentController.java**

Copy to `backend/src/main/java/com/collabdoc/document/DocumentController.java`, change package and update imports:

```java
package com.collabdoc.document;
```

Remove:
```java
import com.collabdoc.model.Document;
import com.collabdoc.service.DocumentService;
```
(Same package now.)

**Step 5: Update external references**

Files that import from old locations need updating. Search and replace in:

- `backend/src/main/java/com/collabdoc/controller/BlockController.java`: change `import com.collabdoc.service.DocumentService` → `import com.collabdoc.document.DocumentService`
- `backend/src/main/java/com/collabdoc/websocket/YjsWebSocketHandler.java`: change `import com.collabdoc.service.DocumentService` → `import com.collabdoc.document.DocumentService`
- `backend/src/main/java/com/collabdoc/config/WebSocketConfig.java`: change `import com.collabdoc.service.DocumentService` → `import com.collabdoc.document.DocumentService`

**Step 6: Verify compilation**

Run: `cd backend && ./gradlew compileJava`
Expected: BUILD SUCCESSFUL

**Step 7: Run tests**

Run: `cd backend && ./gradlew test`
Expected: All tests pass

**Step 8: Commit**

```bash
git add -A backend/src/main/java/com/collabdoc/
git commit -m "refactor: create document package with Document, DocumentRepository, DocumentController, DocumentService"
```

---

### Task 3: Create collab/ package

**Files:**
- Move: `backend/src/main/java/com/collabdoc/controller/BlockController.java` → `backend/src/main/java/com/collabdoc/collab/BlockController.java`
- Move: `backend/src/main/java/com/collabdoc/websocket/YjsWebSocketHandler.java` → `backend/src/main/java/com/collabdoc/collab/YjsWebSocketHandler.java`
- Move: `backend/src/main/java/com/collabdoc/websocket/YjsSyncProtocol.java` → `backend/src/main/java/com/collabdoc/collab/YjsSyncProtocol.java`
- Move: `backend/src/main/java/com/collabdoc/service/YrsDocumentManager.java` → `backend/src/main/java/com/collabdoc/collab/YrsDocumentManager.java`
- Move: `backend/src/main/java/com/collabdoc/model/DocumentSnapshot.java` → `backend/src/main/java/com/collabdoc/collab/DocumentSnapshot.java`
- Move: `backend/src/main/java/com/collabdoc/repository/DocumentSnapshotRepository.java` → `backend/src/main/java/com/collabdoc/collab/DocumentSnapshotRepository.java`
- Move: `backend/src/main/java/com/collabdoc/model/DocumentUpdate.java` → `backend/src/main/java/com/collabdoc/collab/DocumentUpdate.java`
- Move: `backend/src/main/java/com/collabdoc/repository/DocumentUpdateRepository.java` → `backend/src/main/java/com/collabdoc/collab/DocumentUpdateRepository.java`

**Step 1: Move DocumentSnapshot.java and DocumentUpdate.java**

Copy both to `backend/src/main/java/com/collabdoc/collab/`, change package declarations:

```java
package com.collabdoc.collab;
```

Delete originals from model/.

**Step 2: Move DocumentSnapshotRepository.java and DocumentUpdateRepository.java**

Copy both to `backend/src/main/java/com/collabdoc/collab/`, change package declarations:

```java
package com.collabdoc.collab;
```

Remove old model imports (now same package):
- Remove `import com.collabdoc.model.DocumentSnapshot;`
- Remove `import com.collabdoc.model.DocumentUpdate;`

Delete originals from repository/.

**Step 3: Move YrsDocumentManager.java**

Copy to `backend/src/main/java/com/collabdoc/collab/YrsDocumentManager.java`, change package:

```java
package com.collabdoc.collab;
```

Remove (now same package):
```java
import com.collabdoc.repository.DocumentSnapshotRepository;
import com.collabdoc.repository.DocumentUpdateRepository;
import com.collabdoc.model.DocumentSnapshot;
import com.collabdoc.model.DocumentUpdate;
```

Keep (cross-package):
```java
import com.collabdoc.yrs.YrsBridge;
import com.collabdoc.yrs.YrsDocument;
```

Delete original from service/.

**Step 4: Move YjsWebSocketHandler.java and YjsSyncProtocol.java**

Copy both to `backend/src/main/java/com/collabdoc/collab/`, change package declarations:

```java
package com.collabdoc.collab;
```

In YjsWebSocketHandler.java, remove (now same package):
```java
import com.collabdoc.service.YrsDocumentManager;
```

Update:
```java
import com.collabdoc.document.DocumentService;
```
(Should already be updated from Task 2, verify it's correct.)

Delete originals from websocket/.

**Step 5: Move BlockController.java**

Copy to `backend/src/main/java/com/collabdoc/collab/BlockController.java`, change package:

```java
package com.collabdoc.collab;
```

Remove (now same package):
```java
import com.collabdoc.service.YrsDocumentManager;
import com.collabdoc.websocket.YjsWebSocketHandler;
```

Update (should already be from Task 2):
```java
import com.collabdoc.document.DocumentService;
```

Delete original from controller/.

**Step 6: Update external references**

In `backend/src/main/java/com/collabdoc/config/WebSocketConfig.java`, update imports:
- `import com.collabdoc.websocket.YjsWebSocketHandler` → `import com.collabdoc.collab.YjsWebSocketHandler`
- `import com.collabdoc.service.YrsDocumentManager` → `import com.collabdoc.collab.YrsDocumentManager`

**Step 7: Verify compilation**

Run: `cd backend && ./gradlew compileJava`
Expected: BUILD SUCCESSFUL

**Step 8: Run tests**

Run: `cd backend && ./gradlew test`
Expected: All tests pass

**Step 9: Commit**

```bash
git add -A backend/src/main/java/com/collabdoc/
git commit -m "refactor: create collab package with BlockController, WebSocket, YrsDocumentManager, Snapshot/Update"
```

---

### Task 4: Delete empty packages and verify

**Step 1: Verify old packages are empty**

Run:
```bash
find backend/src/main/java/com/collabdoc/controller backend/src/main/java/com/collabdoc/service backend/src/main/java/com/collabdoc/model backend/src/main/java/com/collabdoc/repository backend/src/main/java/com/collabdoc/websocket -type f 2>/dev/null
```
Expected: No files found (all moved).

**Step 2: Delete empty directories**

```bash
rm -rf backend/src/main/java/com/collabdoc/controller
rm -rf backend/src/main/java/com/collabdoc/service
rm -rf backend/src/main/java/com/collabdoc/model
rm -rf backend/src/main/java/com/collabdoc/repository
rm -rf backend/src/main/java/com/collabdoc/websocket
```

**Step 3: Final compilation and test**

Run: `cd backend && ./gradlew clean test`
Expected: BUILD SUCCESSFUL, all tests pass

**Step 4: Verify final structure**

Run: `find backend/src/main/java/com/collabdoc -type f -name "*.java" | sort`

Expected:
```
backend/src/main/java/com/collabdoc/auth/AuthController.java
backend/src/main/java/com/collabdoc/auth/AuthService.java
backend/src/main/java/com/collabdoc/auth/JwtAuthFilter.java
backend/src/main/java/com/collabdoc/auth/JwtUtil.java
backend/src/main/java/com/collabdoc/auth/User.java
backend/src/main/java/com/collabdoc/auth/UserRepository.java
backend/src/main/java/com/collabdoc/collab/BlockController.java
backend/src/main/java/com/collabdoc/collab/DocumentSnapshot.java
backend/src/main/java/com/collabdoc/collab/DocumentSnapshotRepository.java
backend/src/main/java/com/collabdoc/collab/DocumentUpdate.java
backend/src/main/java/com/collabdoc/collab/DocumentUpdateRepository.java
backend/src/main/java/com/collabdoc/collab/YjsSyncProtocol.java
backend/src/main/java/com/collabdoc/collab/YjsWebSocketHandler.java
backend/src/main/java/com/collabdoc/collab/YrsDocumentManager.java
backend/src/main/java/com/collabdoc/CollabDocApplication.java
backend/src/main/java/com/collabdoc/config/CorsConfig.java
backend/src/main/java/com/collabdoc/config/SecurityConfig.java
backend/src/main/java/com/collabdoc/config/WebSocketConfig.java
backend/src/main/java/com/collabdoc/config/YrsConfig.java
backend/src/main/java/com/collabdoc/document/Document.java
backend/src/main/java/com/collabdoc/document/DocumentController.java
backend/src/main/java/com/collabdoc/document/DocumentRepository.java
backend/src/main/java/com/collabdoc/document/DocumentService.java
backend/src/main/java/com/collabdoc/yrs/YrsBridge.java
backend/src/main/java/com/collabdoc/yrs/YrsDocument.java
```

**Step 5: Commit**

```bash
git add -A backend/src/main/java/com/collabdoc/
git commit -m "refactor: remove empty legacy packages (controller, service, model, repository, websocket)"
```
